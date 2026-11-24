"""
โมเดลใบหน้า (ใช้ร่วมกันระหว่าง POST /embedding และตัวอ่านกล้อง)
  - YOLOv8 face-pose: กรอบใบหน้า + 5 landmarks (ตา x2, จมูก, มุมปาก x2)
  - SFace (OpenCV Zoo): embedding 128 มิติ (L2 normalize แล้ว)
เรียกโมเดลทีละงาน (INFER_LOCK) — ตัวอ่านกล้องหลายตัวกับ /embedding ใช้โมเดลชุดเดียวกัน
"""
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
