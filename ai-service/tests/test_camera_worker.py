"""
ทดสอบตัวอ่านกล้อง / gallery โดยไม่ต้องมีโมเดล (vision ปลอม) และไม่ต้องมี backend (BackendClient ปลอม)
  cd ai-service && pip install -r requirements-dev.txt && python -m pytest tests
"""
import base64
import os
import sys
import uuid

import numpy as np
import pytest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from camera_worker import CameraWorker, Config, Supervisor  # noqa: E402
from gallery import GalleryStore, RoomGallery, best_matches, decrypt_embedding, load_key  # noqa: E402

KEY = base64.b64encode(bytes(range(32))).decode()
ROOM = str(uuid.uuid4())


def unit(v):
    v = np.asarray(v, np.float32)
    return v / np.linalg.norm(v)


def axis(i, noise=0.0, seed=0):
    v = np.zeros(128, np.float32)
    v[i] = 1.0
    if noise:
        v += np.random.default_rng(seed).normal(0, noise, 128).astype(np.float32)
    return unit(v)


def seal(student_id: str, vec: np.ndarray, key_b64: str = KEY) -> str:
    """รูปแบบเดียวกับ EmbeddingCipher.java: nonce 12 + ciphertext + tag 16 · AAD = student_id"""
    nonce = os.urandom(12)
    ct = AESGCM(base64.b64decode(key_b64)).encrypt(nonce, np.asarray(vec, "<f4").tobytes(), student_id.encode())
    return base64.b64encode(nonce + ct).decode()


def payload(session="s-1", enabled=True, students=None):
    return {"sessionId": session, "cameraEnabled": enabled,
            "students": [{"studentId": sid, "embeddings": [seal(sid, v) for v in vecs]}
                         for sid, vecs in (students or {}).items()]}


# ---------------- gallery ----------------

def test_decrypt_matches_java_format_and_rejects_wrong_student():
    aes = load_key(KEY)
    sid, other = str(uuid.uuid4()), str(uuid.uuid4())
    vec = np.arange(128, dtype=np.float32)
    blob = seal(sid, vec)
    assert np.allclose(decrypt_embedding(aes, sid, blob), unit(vec))
    with pytest.raises(Exception):
        decrypt_embedding(aes, other, blob)          # AAD ต่างคน → ถอดไม่ได้ (กันสลับ embedding)


def test_load_key_requires_32_bytes():
    with pytest.raises(ValueError):
        load_key(base64.b64encode(b"short").decode())


def test_store_skips_undecryptable_rows():
    store = GalleryStore(load_key(KEY))
    a = str(uuid.uuid4())
    p = payload(students={a: [axis(0)]})
    p["students"][0]["embeddings"].append(seal(a, axis(1), base64.b64encode(os.urandom(32)).decode()))
    g = store.update(ROOM, p)
    assert g.owners == [a] and g.matrix.shape == (1, 128) and g.active


def test_best_matches_threshold_margin_and_multiple_vectors():
    a, b = "a" * 36, "b" * 36
    g = RoomGallery("s", True, [a, a, b], np.stack([axis(0), axis(1), axis(2)]))
    feats = np.stack([
        axis(1),                       # ตรงกับ embedding ชุดที่ 2 ของ a
        unit(axis(2) + 0.02),          # ใกล้ b
        axis(5),                       # ไม่ใช่ใคร
        unit(axis(0) + axis(2)),       # ก้ำกึ่ง a / b → margin ไม่ผ่าน
    ])
    res = best_matches(feats, g, threshold=0.363, margin=0.05)
    assert res[0][0] == a and res[0][1] == pytest.approx(1.0, abs=1e-5)
    assert res[1][0] == b
    assert res[2][0] is None
    assert res[3][0] is None and res[3][1] == pytest.approx(0.7071, abs=1e-3)


def test_forget_removes_student_from_every_room():
    store = GalleryStore(load_key(KEY))
    a, b = str(uuid.uuid4()), str(uuid.uuid4())
    store.update("r1", payload(students={a: [axis(0), axis(1)], b: [axis(2)]}))
    store.update("r2", payload(students={a: [axis(3)]}))
    assert store.forget(a) == 3
    assert store.get("r1").owners == [b] and store.get("r1").matrix.shape == (1, 128)
    assert store.get("r2").owners == [] and not store.get("r2").active


# ---------------- worker ----------------

class FakeBackend:
    def __init__(self, cameras=None, galleries=None):
        self.cameras = cameras or []
        self.galleries = galleries or {}
        self.posts = []

    def get(self, path):
        if path == "/api/internal/cameras":
            return self.cameras
        room = path.split("/")[4]
        return self.galleries.get(room, {"sessionId": None, "cameraEnabled": False, "students": []})

    def post(self, path, body):
        self.posts.append((path, body))
        if path.endswith("/sightings"):
            return {"result": "checked_in", "reason": None, "sessionId": "s-1", "status": "on_time"}
        return body

    def sent(self, suffix):
        return [b for p, b in self.posts if p.endswith(suffix)]


class FakeVision:
    """ทุกเฟรมเห็นใบหน้าตาม faces · feature คืน vector ตาม index ที่ฝังไว้ในแถว (ช่องที่ 4 = x landmark แรก)"""
    def __init__(self, faces):
        self.faces = faces
        self.calls = 0

    def detect(self, frame):
        self.calls += 1
        return self.faces

    def feature(self, frame, face):
        return axis(int(face[4]))


def face_row(feature_index, w=80, score=0.9):
    return np.array([10, 10, w, w, feature_index, 0, 0, 0, 0, 0, 0, 0, 0, 0, score], np.float32)


def make_worker(vision, backend, store, **cfg):
    c = Config(**{"frame_interval": 0, "faces_interval": 5, "min_hits": 2, "hit_window": 10,
                  "sighting_cooldown": 60, **cfg})
    return CameraWorker({"cameraCode": "CAM-1", "roomId": ROOM, "streamPath": "/room-1/"}, c, backend, store, vision)


def test_worker_skips_detection_when_no_session():
    store = GalleryStore(load_key(KEY))
    store.update(ROOM, {"sessionId": None, "cameraEnabled": False, "students": []})
    vision, backend = FakeVision([face_row(0)]), FakeBackend()
    w = make_worker(vision, backend, store)
    w.process(np.zeros((10, 10, 3), np.uint8), now=100)
    assert vision.calls == 0 and backend.posts == []
    assert w.url.endswith("/room-1")


def test_worker_needs_two_hits_then_respects_cooldown():
    a = str(uuid.uuid4())
    store = GalleryStore(load_key(KEY))
    store.update(ROOM, payload(students={a: [axis(0)]}))
    vision, backend = FakeVision([face_row(0), face_row(9)]), FakeBackend()
    w = make_worker(vision, backend, store)
    frame = np.zeros((10, 10, 3), np.uint8)

    w.process(frame, now=100)
    assert backend.sent("/sightings") == []                    # เห็นครั้งแรก ยังไม่ส่ง
    assert backend.sent("/faces")[0]["detected"] == 2
    w.process(frame, now=101)
    s = backend.sent("/sightings")
    assert len(s) == 1 and s[0]["studentId"] == a and s[0]["matchScore"] == pytest.approx(1.0)
    w.process(frame, now=102)
    w.process(frame, now=103)
    assert len(backend.sent("/sightings")) == 1                # cooldown 60 วินาที
    w.process(frame, now=170)
    w.process(frame, now=171)
    assert len(backend.sent("/sightings")) == 2
    assert len(backend.sent("/faces")) == 2                    # ส่งจำนวนใบหน้าทุก 5 วินาที: t=100, t=170


def test_worker_ignores_small_or_low_confidence_faces_and_disabled_camera():
    a = str(uuid.uuid4())
    store = GalleryStore(load_key(KEY))
    store.update(ROOM, payload(students={a: [axis(0)]}))
    backend = FakeBackend()
    w = make_worker(FakeVision([face_row(0, w=20), face_row(0, score=0.3)]), backend, store, min_hits=1)
    w.process(np.zeros((10, 10, 3), np.uint8), now=100)
    assert backend.sent("/sightings") == []
    store.update(ROOM, payload(enabled=False, students={a: [axis(0)]}))
    w2 = make_worker(FakeVision([face_row(0)]), backend, store, min_hits=1)
    w2.process(np.zeros((10, 10, 3), np.uint8), now=100)
    assert backend.sent("/sightings") == []
    assert len(backend.sent("/faces")) == 2                    # ยังรายงานจำนวนใบหน้าแม้ปิดเช็คชื่อด้วยกล้อง


# ---------------- supervisor ----------------

class FakeCapture:
    def __init__(self, frames):
        self.frames = frames

    def isOpened(self):
        return True

    def grab(self):
        if self.frames <= 0:
            return False
        self.frames -= 1
        return True

    def retrieve(self):
        return True, np.zeros((10, 10, 3), np.uint8)

    def release(self):
        pass


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def test_supervisor_starts_workers_heartbeats_and_drops_removed_camera():
    cam = {"cameraCode": "CAM-1", "roomId": ROOM, "streamPath": "room-1"}
    backend = FakeBackend(cameras=[cam], galleries={ROOM: {"sessionId": None, "cameraEnabled": False, "students": []}})
    clock = Clock()
    store = GalleryStore(load_key(KEY))
    sup = Supervisor(Config(heartbeat_interval=30, signal_timeout=10), backend, store, FakeVision([]),
                     open_capture=lambda url: None, clock=clock)   # เปิดภาพไม่ได้
    sup.tick()
    w = sup.workers["CAM-1"]
    assert store.get(ROOM) is not None
    assert backend.sent("/heartbeat") == [{"status": "no_signal"}]

    w.last_frame_at = clock.t                      # มีเฟรมเข้ามา
    clock.t += 5
    sup.tick()
    assert backend.sent("/heartbeat")[-1] == {"status": "live"}
    clock.t += 5
    w.last_frame_at = clock.t                      # เฟรมยังเข้ามาเรื่อย ๆ
    sup.tick()
    assert len(backend.sent("/heartbeat")) == 2    # สถานะเดิม ยังไม่ครบ 30 วินาที → ไม่ส่งซ้ำ
    clock.t += 30
    sup.tick()
    assert backend.sent("/heartbeat")[-1] == {"status": "no_signal"}   # เฟรมล่าสุดเกิน 10 วินาที

    backend.cameras = []
    clock.t += 60
    sup.tick()
    assert sup.workers == {} and store.get(ROOM) is None
    w.stop()
    assert sup.status() == []


def test_worker_thread_reads_frames_and_reconnects():
    store = GalleryStore(load_key(KEY))
    opened = []

    def opener(url):
        opened.append(url)
        return FakeCapture(frames=3)

    c = Config(frame_interval=0, rtsp_base="rtsp://mtx:8554")
    w = CameraWorker({"cameraCode": "CAM-1", "roomId": ROOM, "streamPath": "room-1"}, c, FakeBackend(), store,
                     FakeVision([]), open_capture=opener)
    w.start()
    import time
    deadline = time.time() + 5
    while len(opened) < 2 and time.time() < deadline:
        time.sleep(0.05)
    w.stop()
    w.join(timeout=5)
    assert opened[:2] == ["rtsp://mtx:8554/room-1"] * 2      # ภาพหลุด (grab ได้ False) → เปิดใหม่
    assert w.last_frame_at is not None


class SlowCapture(FakeCapture):
    def grab(self):
        import time
        time.sleep(0.01)
        return True


def test_error_clears_once_stream_opens():
    import time
    c = Config(frame_interval=0, rtsp_base="rtsp://mtx:8554")
    w = CameraWorker({"cameraCode": "CAM-1", "roomId": ROOM, "streamPath": "room-1"}, c, FakeBackend(),
                     GalleryStore(load_key(KEY)), FakeVision([]), open_capture=lambda url: SlowCapture(frames=0))
    w.last_error = "เปิด rtsp://mtx:8554/room-1 ไม่ได้"     # จากรอบก่อนที่ MediaMTX ยังไม่มีภาพ
    w.start()
    deadline = time.time() + 5
    while w.last_frame_at is None and time.time() < deadline:
        time.sleep(0.02)
    try:
        assert w.last_frame_at is not None and w.last_error is None
    finally:
        w.stop()
        w.join(timeout=5)
