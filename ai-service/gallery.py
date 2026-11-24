"""
embedding ที่ใช้จับคู่ใบหน้าจากกล้อง แยกตามห้อง (โหลดจาก backend: GET /api/internal/rooms/{roomId}/gallery)

รูปแบบ embedding จาก backend (EmbeddingCipher.java): base64 ของ nonce 12 ไบต์ + ciphertext + tag 16 ไบต์
  AES-256-GCM · กุญแจ FACE_EMBEDDING_KEY (base64 32 ไบต์) · AAD = student_id (UUID string)
  plaintext = float32 little-endian 128 ค่า
ถอนความยินยอม → backend เรียก DELETE /students/{id}/embeddings → forget() ลบออกจากทุกห้องทันที
"""
import base64
import logging
import threading
import time
from dataclasses import dataclass, field

import numpy as np
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

log = logging.getLogger("gallery")

NONCE_BYTES = 12
DIM = 128


def load_key(b64_key: str) -> AESGCM:
    raw = base64.b64decode(b64_key.strip())
    if len(raw) != 32:
        raise ValueError("FACE_EMBEDDING_KEY ต้องเป็น base64 ของ 32 ไบต์ (AES-256)")
    return AESGCM(raw)


def decrypt_embedding(aes: AESGCM, student_id: str, blob_b64: str) -> np.ndarray:
    blob = base64.b64decode(blob_b64)
    plain = aes.decrypt(blob[:NONCE_BYTES], blob[NONCE_BYTES:], student_id.encode("ascii"))
    vec = np.frombuffer(plain, dtype="<f4").astype(np.float32)
    if vec.shape != (DIM,):
        raise ValueError(f"embedding ต้องมี {DIM} มิติ (ได้ {vec.shape[0]})")
    norm = np.linalg.norm(vec)
    return vec / norm if norm > 0 else vec


@dataclass
class RoomGallery:
    session_id: str | None
    camera_enabled: bool
    owners: list[str] = field(default_factory=list)            # student_id ของแต่ละแถวใน matrix
    matrix: np.ndarray = field(default_factory=lambda: np.zeros((0, DIM), np.float32))
    loaded_at: float = field(default_factory=time.monotonic)

    @property
    def students(self) -> int:
        return len(set(self.owners))

    @property
    def active(self) -> bool:
        """มีคาบ + เปิดเช็คชื่อด้วยกล้อง + มีใบหน้าให้จับคู่"""
        return self.session_id is not None and self.camera_enabled and len(self.owners) > 0


def best_matches(feats: np.ndarray, gallery: RoomGallery, threshold: float, margin: float
                 ) -> list[tuple[str | None, float]]:
    """
    ใบหน้าละ 1 ผล: (student_id, cosine) เมื่อคะแนนสูงสุด >= threshold และห่างจากนักศึกษาคนที่ 2 >= margin
    ไม่ผ่าน → (None, คะแนนสูงสุด) · นักศึกษา 1 คนมีได้หลาย embedding → ใช้ค่าสูงสุดของคนนั้น
    """
    if len(feats) == 0:
        return []
    if not gallery.owners:
        return [(None, -1.0)] * len(feats)
    sims = feats @ gallery.matrix.T                       # (ใบหน้า, แถว)
    ids = list(dict.fromkeys(gallery.owners))             # ลำดับคงที่ ไม่ซ้ำ
    col_owner = np.array([ids.index(o) for o in gallery.owners])
    per_student = np.full((len(feats), len(ids)), -1.0, np.float32)
    for col, k in enumerate(col_owner):
        per_student[:, k] = np.maximum(per_student[:, k], sims[:, col])
    out = []
    for row in per_student:
        order = np.argsort(row)[::-1]
        best = float(row[order[0]])
        second = float(row[order[1]]) if len(order) > 1 else -1.0
        out.append((ids[order[0]], best) if best >= threshold and best - second >= margin else (None, best))
    return out


class GalleryStore:
    def __init__(self, aes: AESGCM):
        self._aes = aes
        self._rooms: dict[str, RoomGallery] = {}
        self._lock = threading.Lock()

    def update(self, room_id: str, payload: dict) -> RoomGallery:
        owners, rows, bad = [], [], 0
        for s in payload.get("students") or []:
            sid = s["studentId"]
            for blob in s.get("embeddings") or []:
                try:
                    rows.append(decrypt_embedding(self._aes, sid, blob))
                    owners.append(sid)
                except Exception:  # กุญแจไม่ตรง / ข้อมูลเสีย — ข้ามแถวนั้น ไม่ log ค่า
                    bad += 1
        if bad:
            log.warning("room %s: ข้าม embedding ที่ถอดรหัสไม่ได้ %d ชุด (ข้อมูล mock ใน 02_seed_mock.sql เป็นไบต์สุ่ม"
                        " หรือ FACE_EMBEDDING_KEY ไม่ตรงกับ backend)", room_id[:8], bad)
        g = RoomGallery(payload.get("sessionId"), bool(payload.get("cameraEnabled")), owners,
                        np.stack(rows) if rows else np.zeros((0, DIM), np.float32))
        with self._lock:
            self._rooms[room_id] = g
        return g

    def get(self, room_id: str) -> RoomGallery | None:
        with self._lock:
            return self._rooms.get(room_id)

    def drop_room(self, room_id: str) -> None:
        with self._lock:
            self._rooms.pop(room_id, None)

    def forget(self, student_id: str) -> int:
        """ลบ embedding ของนักศึกษาออกจากทุกห้อง · คืนจำนวนแถวที่ลบ"""
        removed = 0
        with self._lock:
            for room_id, g in list(self._rooms.items()):
                keep = [i for i, o in enumerate(g.owners) if o != student_id]
                if len(keep) != len(g.owners):
                    removed += len(g.owners) - len(keep)
                    self._rooms[room_id] = RoomGallery(g.session_id, g.camera_enabled,
                                                       [g.owners[i] for i in keep], g.matrix[keep], g.loaded_at)
        return removed
