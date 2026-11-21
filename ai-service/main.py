"""
AI Service: รับภาพ -> YOLOv8 (face + 5 landmarks) -> ตรวจคุณภาพ -> SFace embedding 128 มิติ
Model ที่ต้องเตรียม (mount เข้า /models):
  - yolov8 face-pose weights (.pt) ที่ output keypoints 5 จุด (ตา x2, จมูก, มุมปาก x2)
  - face_recognition_sface_2021dec.onnx  (OpenCV Zoo)
"""
import os

import cv2
import numpy as np
from fastapi import FastAPI, HTTPException, UploadFile
from ultralytics import YOLO

YOLO_PATH = os.getenv("YOLO_FACE_MODEL", "/models/yolov8n-face-pose.pt")
SFACE_PATH = os.getenv("SFACE_MODEL", "/models/face_recognition_sface_2021dec.onnx")
MIN_CONF = float(os.getenv("MIN_CONF", "0.8"))
MIN_FACE_PX = int(os.getenv("MIN_FACE_PX", "112"))
MIN_BLUR = float(os.getenv("MIN_BLUR", "60"))   # variance of Laplacian

detector = YOLO(YOLO_PATH)
recognizer = cv2.FaceRecognizerSF.create(SFACE_PATH, "")

app = FastAPI()


@app.get("/health")
def health():
    return {"status": "ok"}


def detect_faces(img: np.ndarray):
    res = detector.predict(img, conf=0.5, verbose=False)[0]
    if res.boxes is None or len(res.boxes) == 0:
        return []
    if res.keypoints is None:
        raise HTTPException(500, "YOLO model ไม่มี keypoints (ต้องเป็นรุ่น face-pose)")
    faces = []
    for box, kps in zip(res.boxes, res.keypoints.xy):
        pts = kps.cpu().numpy()
        if pts.shape[0] != 5:
            raise HTTPException(500, "YOLO model ต้องให้ 5 landmarks")
        x1, y1, x2, y2 = box.xyxy[0].tolist()
        conf = float(box.conf[0])
        # รูปแบบที่ SFace.alignCrop ต้องการ: [x,y,w,h, 5 landmarks (10 ค่า), score]
        # ลำดับ landmark: ตา(ซ้ายภาพ), ตา(ขวาภาพ), จมูก, ปาก(ซ้ายภาพ), ปาก(ขวาภาพ)
        row = np.array([x1, y1, x2 - x1, y2 - y1, *pts.flatten(), conf], dtype=np.float32)
        faces.append(row)
    return faces


def check_quality(img: np.ndarray, face: np.ndarray, name: str):
    x, y, w, h, conf = face[0], face[1], face[2], face[3], face[-1]
    if conf < MIN_CONF:
        raise HTTPException(422, f"{name}: ความมั่นใจในการตรวจใบหน้าต่ำ")
    if min(w, h) < MIN_FACE_PX:
        raise HTTPException(422, f"{name}: ใบหน้าเล็กเกินไป ขยับเข้าใกล้กล้อง")
    x1, y1 = max(int(x), 0), max(int(y), 0)
    crop = img[y1:int(y + h), x1:int(x + w)]
    if crop.size == 0:
        raise HTTPException(422, f"{name}: ใบหน้าอยู่นอกกรอบภาพ")
    blur = cv2.Laplacian(cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY), cv2.CV_64F).var()
    if blur < MIN_BLUR:
        raise HTTPException(422, f"{name}: ภาพเบลอ")


@app.post("/embedding")
async def embedding(files: list[UploadFile]):
    items = []
    for i, f in enumerate(files):
        name = f.filename or f"image {i + 1}"
        data = np.frombuffer(await f.read(), np.uint8)
        img = cv2.imdecode(data, cv2.IMREAD_COLOR)
        if img is None:
            raise HTTPException(422, f"{name}: อ่านไฟล์ภาพไม่ได้")

        faces = detect_faces(img)
        if len(faces) == 0:
            raise HTTPException(422, f"{name}: ไม่พบใบหน้า")
        if len(faces) > 1:
            raise HTTPException(422, f"{name}: พบหลายใบหน้า ต้องมีเพียง 1 คน")

        face = faces[0]
        check_quality(img, face, name)

        aligned = recognizer.alignCrop(img, face)
        feat = recognizer.feature(aligned).flatten().astype(np.float32)
        feat /= np.linalg.norm(feat)  # L2 normalize
        items.append({"embedding": feat.tolist(), "quality": round(float(face[-1]), 4)})

    return {"model": "SFace", "model_version": "2021dec", "items": items}
