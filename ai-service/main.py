"""
AI Service
  - POST /embedding: รับภาพ -> YOLOv8 (face + 5 landmarks) -> ตรวจคุณภาพ
    -> SFace embedding 128 มิติ -> ตรวจว่าทุกรูปเป็นคนเดียวกัน (ลงทะเบียนใบหน้า · ไม่มีการตรวจภาพหลอก)
  - GET /metrics: เวลาจริงของแต่ละขั้น (p50 / p95) ทั้ง /embedding และตัวอ่านกล้อง
  - ตัวอ่านกล้อง (camera_worker.py): อ่าน RTSP จาก MediaMTX -> จับคู่ใบหน้า -> ส่งผลไป backend /api/internal/**
    เปิดด้วย CAMERA_WORKER=true (ต้องมี FACE_EMBEDDING_KEY, INTERNAL_API_KEY, BACKEND_URL, MEDIAMTX_RTSP_URL)
Model ที่ต้องเตรียม (mount เข้า /models):
  - yolov8 face-pose weights (.pt) ที่ output keypoints 5 จุด (ตา x2, จมูก, มุมปาก x2)
  - face_recognition_sface_2021dec.onnx  (OpenCV Zoo)
"""
import logging
import os
from contextlib import asynccontextmanager

import cv2
import numpy as np
from fastapi import FastAPI, Header, HTTPException, Response, UploadFile

# ตั้ง log ก่อน import vision — ไม่งั้น log INFO ที่เกิดตอน import (โหลดโมเดล) หายไป
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [%(name)s] %(message)s")
log = logging.getLogger("ai-service")

import vision  # noqa: E402  (โหลดโมเดลตอน import)
from camera_worker import BackendClient, Config, Supervisor  # noqa: E402
from gallery import GalleryStore, load_key  # noqa: E402
from metrics import Timings  # noqa: E402

MIN_CONF = float(os.getenv("MIN_CONF", "0.8"))
MIN_FACE_PX = int(os.getenv("MIN_FACE_PX", "112"))
MIN_BLUR = float(os.getenv("MIN_BLUR", "60"))   # variance of Laplacian
SAME_PERSON_MIN = float(os.getenv("SAME_PERSON_MIN", "0.363"))       # cosine ขั้นต่ำระหว่างทุกคู่รูป (ค่า SFace)

INTERNAL_KEY = os.getenv("INTERNAL_API_KEY", "")   # ต้องตรงกับ backend: app.internal.api-key
CAMERA_WORKER = os.getenv("CAMERA_WORKER", "false").strip().lower() in ("1", "true", "yes", "on")
EMBEDDING_KEY = os.getenv("FACE_EMBEDDING_KEY", "")

# embedding ที่โหลดไว้จับคู่ใบหน้าจากกล้อง (แยกตามห้อง) · ถอนความยินยอมต้องลบออกทันที
gallery: GalleryStore | None = None
supervisor: Supervisor | None = None
register_metrics = Timings()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    global gallery, supervisor
    if CAMERA_WORKER:
        if not EMBEDDING_KEY or not INTERNAL_KEY:
            log.error("CAMERA_WORKER=true แต่ไม่มี FACE_EMBEDDING_KEY / INTERNAL_API_KEY — ไม่เปิดตัวอ่านกล้อง")
        else:
            cfg = Config()
            gallery = GalleryStore(load_key(EMBEDDING_KEY))
            supervisor = Supervisor(cfg, BackendClient(cfg.backend_url, cfg.internal_key), gallery, vision)
            supervisor.start()
    else:
        log.info("ตัวอ่านกล้องปิดอยู่ (CAMERA_WORKER=false)")
    yield
    if supervisor:
        supervisor.stop()


app = FastAPI(lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/cameras/status")
def cameras_status():
    """สถานะตัวอ่านกล้อง (ไม่มีข้อมูลส่วนตัว: แค่จำนวน) · ใช้ตรวจจากใน cluster"""
    if supervisor is None:
        return {"enabled": False, "cameras": []}
    return {"enabled": True, "cameras": supervisor.status()}


def _require_internal_key(given: str | None):
    if not INTERNAL_KEY or given != INTERNAL_KEY:
        raise HTTPException(401, "invalid internal key")


@app.get("/metrics")
def metrics():
    """เวลาจริงต่อขั้น (ms: n / mean / p50 / p95 / max) · ใน cluster เท่านั้น · ไม่มีข้อมูลส่วนตัว"""
    return {
        "registration": register_metrics.summary(),
        "cameras": supervisor.metrics() if supervisor else {},
    }


@app.delete("/students/{student_id}/embeddings", status_code=204)
def forget_student(student_id: str, x_internal_key: str | None = Header(default=None)):
    """B7: backend เรียกหลังถอนความยินยอม → ลบ embedding ของนักศึกษาออกจากหน่วยความจำ (ทุกห้อง)"""
    _require_internal_key(x_internal_key)
    if gallery is not None:
        gallery.forget(student_id)
    return Response(status_code=204)


def detect_faces(img: np.ndarray):
    try:
        return vision.detect(img)
    except vision.ModelError as e:
        raise HTTPException(500, str(e)) from None


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


def check_same_person(feats: list[np.ndarray], names: list[str]) -> float | None:
    """ทุกคู่รูปต้องมี cosine >= SAME_PERSON_MIN · ไม่ผ่าน → 422 ชี้รูปที่เข้ากับรูปอื่นน้อยที่สุด"""
    if len(feats) < 2:
        return None
    f = np.stack(feats)
    sims = f @ f.T
    n = len(feats)
    off = sims[~np.eye(n, dtype=bool)].reshape(n, n - 1)
    lowest = float(off.min())
    if lowest < SAME_PERSON_MIN:
        register_metrics.count("rejected_not_same_person")
        odd = int(np.argmin(off.mean(axis=1)))
        log.info("embedding: ปฏิเสธชุดภาพ (cosine ต่ำสุดระหว่างรูป %.3f < %.3f)", lowest, SAME_PERSON_MIN)
        raise HTTPException(422, f"{names[odd]}: ใบหน้าไม่ตรงกับรูปอื่น ทุกรูปต้องเป็นคนเดียวกัน "
                                 "(เจ้าของบัญชี) ถ่ายใหม่แล้วลองอีกครั้ง")
    return lowest


@app.post("/embedding")
async def embedding(files: list[UploadFile]):
    items, feats, names = [], [], []
    for i, f in enumerate(files):
        name = f.filename or f"image {i + 1}"
        with register_metrics.timer("decode"):
            data = np.frombuffer(await f.read(), np.uint8)
            img = cv2.imdecode(data, cv2.IMREAD_COLOR)
        if img is None:
            raise HTTPException(422, f"{name}: อ่านไฟล์ภาพไม่ได้")

        with register_metrics.timer("detect"):
            faces = detect_faces(img)
        if len(faces) == 0:
            raise HTTPException(422, f"{name}: ไม่พบใบหน้า")
        if len(faces) > 1:
            raise HTTPException(422, f"{name}: พบหลายใบหน้า ต้องมีเพียง 1 คน")

        face = faces[0]
        check_quality(img, face, name)

        with register_metrics.timer("feature"):
            try:
                feat = vision.feature(img, face)
            except vision.EmbeddingError:
                raise HTTPException(422, f"{name}: สร้างข้อมูลใบหน้าไม่ได้ ถ่ายใหม่ให้เห็นหน้าชัด ๆ") from None
        feats.append(feat)
        names.append(name)
        # liveness = null เสมอ: ไม่มีการตรวจภาพหลอก (คงช่องไว้ให้ response รูปแบบเดิม · backend ไม่ได้อ่าน)
        items.append({"embedding": feat.tolist(), "quality": round(float(face[-1]), 4), "liveness": None})

    same = check_same_person(feats, names)
    register_metrics.count("registrations")
    register_metrics.count("images", len(items))
    log.info("embedding: %d ภาพผ่าน · cosine ต่ำสุดระหว่างรูป %s", len(items),
             "-" if same is None else f"{same:.3f}")
    return {"model": "SFace", "model_version": "2021dec", "items": items}
