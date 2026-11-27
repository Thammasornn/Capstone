"""
คุณภาพภาพใบหน้า (ไม่ใช้โมเดล · ใช้ร่วมกันระหว่าง POST /embedding และตัวอ่านกล้อง)
  - blur = variance of Laplacian ของกรอบหน้า (ยิ่งต่ำยิ่งเบลอ)
  - brightness = ค่าเฉลี่ยระดับเทา 0–255 ของกรอบหน้า (มืด / ย้อนแสง / สว่างจ้า)
ตัดกรอบหน้าเป็น view ของภาพเดิม (ไม่ copy ทั้งเฟรม) · แปลงเทาเฉพาะกรอบหน้า
"""
import cv2
import numpy as np


def face_crop(img: np.ndarray, face: np.ndarray) -> np.ndarray:
    """กรอบหน้า [x, y, w, h, ...] ที่ตัดให้อยู่ในภาพ · ว่าง = หน้าอยู่นอกภาพ"""
    x, y, w, h = (float(v) for v in face[:4])
    x1, y1 = max(int(x), 0), max(int(y), 0)
    return img[y1:max(int(y + h), 0), x1:max(int(x + w), 0)]


def measure(img: np.ndarray, face: np.ndarray) -> tuple[float, float] | None:
    """(blur, brightness) ของกรอบหน้า · None = กรอบหน้าอยู่นอกภาพ"""
    crop = face_crop(img, face)
    if crop.size == 0:
        return None
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY) if crop.ndim == 3 else crop
    return float(cv2.Laplacian(gray, cv2.CV_64F).var()), float(gray.mean())
