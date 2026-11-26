"""
โมเดลใบหน้า (ใช้ร่วมกันระหว่าง POST /embedding และตัวอ่านกล้อง)
  - YOLOv8 face-pose: กรอบใบหน้า + 5 landmarks (ตา x2, จมูก, มุมปาก x2)
  - SFace (OpenCV Zoo): embedding 128 มิติ (L2 normalize แล้ว)
  - MiniFASNet V2 + V1SE (Silent-Face-Anti-Spoofing, ONNX): โอกาสที่เป็นใบหน้าจริง (ไม่ใช่รูปพิมพ์ / หน้าจอ)
    ไม่มีไฟล์ หรือ ANTISPOOF=off → ANTISPOOF_READY = False (pod ยังขึ้น · log WARN)
เรียกโมเดลทีละงาน (INFER_LOCK) — ตัวอ่านกล้องหลายตัวกับ /embedding ใช้โมเดลชุดเดียวกัน
"""
import logging
import os
import threading

import cv2
import numpy as np
from ultralytics import YOLO

YOLO_PATH = os.getenv("YOLO_FACE_MODEL", "/models/yolov8n-face-pose.pt")
SFACE_PATH = os.getenv("SFACE_MODEL", "/models/face_recognition_sface_2021dec.onnx")

detector = YOLO(YOLO_PATH)
recognizer = cv2.FaceRecognizerSF.create(SFACE_PATH, "")
INFER_LOCK = threading.Lock()

log = logging.getLogger("vision")

# ---- anti-spoofing ----
# on = ปฏิเสธภาพที่คะแนนต่ำกว่า ANTISPOOF_MIN_REAL · log = คำนวณ + คืนคะแนน แต่ไม่ปฏิเสธ · off = ไม่ใช้
ANTISPOOF = os.getenv("ANTISPOOF", "on").strip().lower()
ANTISPOOF_MODELS = [   # (path, scale ที่ขยายกรอบหน้า) ตามที่โมเดลแต่ละตัวเทรนไว้
    (os.getenv("ANTISPOOF_MODEL_V2", "/models/minifasnet_v2.onnx"), 2.7),
    (os.getenv("ANTISPOOF_MODEL_V1SE", "/models/minifasnet_v1se.onnx"), 4.0),
]
SPOOF_INPUT = 80           # input 80x80 BGR 0–255 ไม่ normalize · output 3 class (index 1 = ใบหน้าจริง)
SPOOF_LOCK = threading.Lock()
_spoof_nets: list[tuple[cv2.dnn.Net, float]] = []


def _load_antispoof():
    if ANTISPOOF in ("off", "0", "false", "no"):
        log.info("anti-spoofing ปิดอยู่ (ANTISPOOF=off)")
        return
    missing = [p for p, _ in ANTISPOOF_MODELS if not os.path.isfile(p)]
    if missing:
        log.warning("anti-spoofing ปิดเอง: ไม่พบโมเดล %s — รัน devops/models/fetch-models.sh แล้ว restart ai-service",
                    ", ".join(os.path.basename(p) for p in missing))
        return
    try:
        for path, scale in ANTISPOOF_MODELS:
            _spoof_nets.append((cv2.dnn.readNetFromONNX(path), scale))
    except cv2.error as e:
        _spoof_nets.clear()
        log.warning("anti-spoofing ปิดเอง: โหลดโมเดลไม่ได้ (%s)", str(e).splitlines()[0])
        return
    log.info("anti-spoofing พร้อม (MiniFASNet V2 + V1SE · โหมด %s)", ANTISPOOF)


_load_antispoof()
ANTISPOOF_READY = bool(_spoof_nets)
ANTISPOOF_ENFORCE = ANTISPOOF_READY and ANTISPOOF not in ("log", "monitor")


class ModelError(RuntimeError):
    """โมเดลไม่ใช่รุ่นที่ต้องการ (ไม่มี keypoints / ไม่ใช่ 5 จุด)"""


def detect(img: np.ndarray, conf: float = 0.5) -> list[np.ndarray]:
    """คืนแถวละใบหน้า: [x, y, w, h, 5 landmarks (10 ค่า), score] — รูปแบบที่ SFace.alignCrop ต้องการ"""
    with INFER_LOCK:
        res = detector.predict(img, conf=conf, verbose=False)[0]
    if res.boxes is None or len(res.boxes) == 0:
        return []
    if res.keypoints is None:
        raise ModelError("YOLO model ไม่มี keypoints (ต้องเป็นรุ่น face-pose)")
    faces = []
    for box, kps in zip(res.boxes, res.keypoints.xy):
        pts = kps.cpu().numpy()
        if pts.shape[0] != 5:
            raise ModelError("YOLO model ต้องให้ 5 landmarks")
        x1, y1, x2, y2 = box.xyxy[0].tolist()
        score = float(box.conf[0])
        # ลำดับ landmark: ตา(ซ้ายภาพ), ตา(ขวาภาพ), จมูก, ปาก(ซ้ายภาพ), ปาก(ขวาภาพ)
        faces.append(np.array([x1, y1, x2 - x1, y2 - y1, *pts.flatten(), score], dtype=np.float32))
    return faces


def feature(img: np.ndarray, face: np.ndarray) -> np.ndarray:
    """SFace embedding 128 มิติ (L2 normalize แล้ว)"""
    with INFER_LOCK:
        aligned = recognizer.alignCrop(img, face)
        feat = recognizer.feature(aligned).flatten().astype(np.float32)
    return feat / np.linalg.norm(feat)


def spoof_crop(img: np.ndarray, face: np.ndarray, scale: float, size: int = SPOOF_INPUT) -> np.ndarray:
    """ขยายกรอบหน้า ×scale รอบจุดกึ่งกลาง (เลื่อนให้อยู่ในภาพ · ไม่เกินขนาดภาพ) แล้วย่อเป็น size×size
    — วิธีเดียวกับ CropImage ของ Silent-Face-Anti-Spoofing (โมเดลเห็นบริบทรอบหน้า เช่น ขอบจอ / ขอบกระดาษ)"""
    src_h, src_w = img.shape[:2]
    x, y, w, h = (float(v) for v in face[:4])
    w, h = max(w, 1.0), max(h, 1.0)
    scale = min((src_h - 1) / h, (src_w - 1) / w, scale)
    nw, nh = w * scale, h * scale
    cx, cy = x + w / 2, y + h / 2
    x1, y1, x2, y2 = cx - nw / 2, cy - nh / 2, cx + nw / 2, cy + nh / 2
    if x1 < 0:
        x2, x1 = x2 - x1, 0
    if y1 < 0:
        y2, y1 = y2 - y1, 0
    if x2 > src_w - 1:
        x1, x2 = x1 - (x2 - src_w + 1), src_w - 1
    if y2 > src_h - 1:
        y1, y2 = y1 - (y2 - src_h + 1), src_h - 1
    crop = img[int(max(y1, 0)):int(y2) + 1, int(max(x1, 0)):int(x2) + 1]
    return cv2.resize(crop, (size, size))


def liveness(img: np.ndarray, face: np.ndarray) -> float | None:
    """โอกาสที่เป็นใบหน้าจริง 0–1 (เฉลี่ย softmax class 1 ของ 2 โมเดล) · None = anti-spoofing ไม่พร้อม"""
    if not _spoof_nets:
        return None
    probs = []
    for net, scale in _spoof_nets:
        blob = spoof_crop(img, face, scale).astype(np.float32).transpose(2, 0, 1)[None]
        with SPOOF_LOCK:
            net.setInput(blob)
            logits = net.forward().ravel().astype(np.float64)
        e = np.exp(logits - logits.max())
        probs.append(float(e[1] / e.sum()))
    return float(np.mean(probs))
