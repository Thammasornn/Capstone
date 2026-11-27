"""
โมเดลใบหน้า (ใช้ร่วมกันระหว่าง POST /embedding และตัวอ่านกล้อง)
  - YOLOv8 face-pose: กรอบใบหน้า + 5 landmarks (ตา x2, จมูก, มุมปาก x2)
  - SFace (OpenCV Zoo): embedding 128 มิติ (L2 normalize แล้ว)
  - MiniFASNetV2 (Silent-Face-Anti-Spoofing · ONNX Runtime CPU): โอกาสเป็นใบหน้าจริง 0–1
      ANTISPOOF = log (ค่าเริ่มต้น: คำนวณ + บันทึก ไม่ปฏิเสธ) · on (ปฏิเสธ) · off (ไม่โหลด ไม่คำนวณ)
      โหลดไม่ได้ / ไม่มีไฟล์ → ANTISPOOF_READY = False (pod ยังขึ้น · log WARN) · โหมด on = ปฏิเสธทุกภาพ (fail closed)
YOLO + SFace เรียกทีละงาน (INFER_LOCK) · MiniFASNet ใช้ session เดียวร่วมกัน (ONNX Runtime เรียกพร้อมกันได้)
"""
import logging
import os
import threading

import cv2
import numpy as np
from ultralytics import YOLO

log = logging.getLogger("vision")

YOLO_PATH = os.getenv("YOLO_FACE_MODEL", "/models/yolov8n-face-pose.pt")
SFACE_PATH = os.getenv("SFACE_MODEL", "/models/face_recognition_sface_2021dec.onnx")

if os.getenv("TORCH_THREADS"):          # จำกัด thread ของ YOLO (torch) · ไม่ตั้ง = ค่าเริ่มต้นของ torch
    import torch
    torch.set_num_threads(int(os.environ["TORCH_THREADS"]))

detector = YOLO(YOLO_PATH)
recognizer = cv2.FaceRecognizerSF.create(SFACE_PATH, "")
INFER_LOCK = threading.Lock()
EMBEDDING_DIM = 128

# ---- anti-spoofing: MiniFASNetV2 ----
# ตรวจกับโค้ดต้นฉบับ (minivision-ai/Silent-Face-Anti-Spoofing: generate_patches.py, functional.py, test.py)
# และ uniface (yakhyo/uniface spoofing/minifasnet.py): ขยายกรอบหน้า ×2.7 → 80×80 · BGR · 0–255 ไม่หาร ไม่ normalize ·
# NCHW float32 · output = logits 3 class → softmax · index 1 = ใบหน้าจริง (0, 2 = ภาพหลอก ต้นฉบับไม่ระบุว่าชนิดไหน)
ANTISPOOF_MODES = ("on", "log", "off")
ANTISPOOF = os.getenv("ANTISPOOF", "log").strip().lower()
if ANTISPOOF not in ANTISPOOF_MODES:
    log.warning("ANTISPOOF=%r ไม่รู้จัก (ใช้ on / log / off) → ใช้ log", ANTISPOOF)
    ANTISPOOF = "log"
ANTISPOOF_MODEL = os.getenv("ANTISPOOF_MODEL", "/models/2.7_80x80_MiniFASNetV2.onnx")
ANTISPOOF_THREADS = int(os.getenv("ANTISPOOF_THREADS", "1"))   # 1 = ไม่แย่ง CPU กับ YOLO (วัดแล้ว ~1.7 ms/หน้า)
SPOOF_SCALE = 2.7
SPOOF_INPUT = 80
REAL_CLASS = 1


class AntiSpoofError(RuntimeError):
    """คำนวณ liveness ไม่ได้ (โมเดลไม่พร้อม / inference error) — ห้ามถือว่าเป็นใบหน้าจริง"""


def _load_antispoof():
    """คืน (session, ชื่อ input, ข้อความ error)"""
    if ANTISPOOF == "off":
        log.info("anti-spoofing ปิดอยู่ (ANTISPOOF=off)")
        return None, None, "ANTISPOOF=off"
    if not os.path.isfile(ANTISPOOF_MODEL):
        msg = f"ไม่พบโมเดล {os.path.basename(ANTISPOOF_MODEL)}"
        log.warning("anti-spoofing ใช้ไม่ได้: %s — รัน devops/models/fetch-models.sh แล้ว restart ai-service", msg)
        return None, None, msg
    try:
        import onnxruntime as ort
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = ANTISPOOF_THREADS
        opts.inter_op_num_threads = 1
        opts.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        sess = ort.InferenceSession(ANTISPOOF_MODEL, opts, providers=["CPUExecutionProvider"])
        inp = sess.get_inputs()[0]
        if list(inp.shape[1:]) != [3, SPOOF_INPUT, SPOOF_INPUT] or sess.get_outputs()[0].shape[-1] != 3:
            raise ValueError(f"รูปแบบโมเดลไม่ตรง (input {inp.shape} · output {sess.get_outputs()[0].shape})")
    except Exception as e:   # noqa: BLE001  (onnxruntime ไม่มี / ไฟล์เสีย / ไม่ใช่ MiniFASNet 80×80)
        msg = f"โหลดโมเดลไม่ได้ ({str(e).splitlines()[0][:200]})"
        log.warning("anti-spoofing ใช้ไม่ได้: %s", msg)
        return None, None, msg
    log.info("anti-spoofing พร้อม (MiniFASNetV2 · ONNX Runtime CPU %d thread · โหมด %s)", ANTISPOOF_THREADS, ANTISPOOF)
    return sess, inp.name, None


_spoof_sess, _spoof_input, ANTISPOOF_ERROR = _load_antispoof()
ANTISPOOF_READY = _spoof_sess is not None
ANTISPOOF_ENFORCE = ANTISPOOF == "on"          # on แต่ไม่พร้อม = ปฏิเสธทุกภาพ (ไม่ปล่อยผ่านเมื่อตรวจไม่ได้)


class ModelError(RuntimeError):
    """โมเดลไม่ใช่รุ่นที่ต้องการ (ไม่มี keypoints / ไม่ใช่ 5 จุด)"""


class EmbeddingError(ValueError):
    """SFace ให้ embedding ที่ใช้ไม่ได้ (ว่าง / ขนาดไม่ใช่ 128 / ค่าเป็น 0 ทั้งหมด / NaN)"""


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
    norm = float(np.linalg.norm(feat)) if feat.size else 0.0
    if feat.size != EMBEDDING_DIM or not np.isfinite(norm) or norm == 0.0:
        raise EmbeddingError(f"embedding ใช้ไม่ได้ (ขนาด {feat.size} · norm {norm})")
    return feat / norm



def spoof_crop(img: np.ndarray, face: np.ndarray, scale: float = SPOOF_SCALE, size: int = SPOOF_INPUT) -> np.ndarray:
    """ขยายกรอบหน้า ×scale รอบจุดกึ่งกลาง (เลื่อนให้อยู่ในภาพ · ไม่เกินขนาดภาพ) แล้วย่อเป็น size×size
    — วิธีเดียวกับ CropImage ของ Silent-Face-Anti-Spoofing (โมเดลเห็นบริบทรอบหน้า เช่น ขอบจอ / ขอบกระดาษ)
    ตัดเป็น view ของภาพเดิม (ไม่ copy ทั้งเฟรม) · copy เฉพาะผล resize 80×80"""
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
    if crop.size == 0:
        raise AntiSpoofError("กรอบหน้าอยู่นอกภาพ")
    return cv2.resize(crop, (size, size))


def liveness(img: np.ndarray, face: np.ndarray) -> float:
    """โอกาสที่เป็นใบหน้าจริง 0–1 (softmax class 1) · ตรวจไม่ได้ = AntiSpoofError (ห้ามถือว่าผ่าน)"""
    if _spoof_sess is None:
        raise AntiSpoofError(ANTISPOOF_ERROR or "anti-spoofing ไม่พร้อม")
    blob = spoof_crop(img, face).astype(np.float32).transpose(2, 0, 1)[None]
    try:
        logits = np.asarray(_spoof_sess.run(None, {_spoof_input: blob})[0], np.float64).ravel()
    except Exception as e:   # noqa: BLE001
        raise AntiSpoofError(f"inference error: {str(e).splitlines()[0][:200]}") from None
    if logits.size != 3 or not np.all(np.isfinite(logits)):
        raise AntiSpoofError("ผลของโมเดลไม่ถูกต้อง")
    e = np.exp(logits - logits.max())
    return float(e[REAL_CLASS] / e.sum())
