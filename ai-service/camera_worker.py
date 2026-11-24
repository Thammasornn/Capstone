"""
ตัวอ่านกล้อง realtime: อ่าน RTSP จาก MediaMTX → ตรวจ + จับคู่ใบหน้า → ส่งผลไป backend (/api/internal/**)

  Supervisor (1 thread)
    - ทุก CAMERAS_REFRESH วินาที: GET /api/internal/cameras → เปิด / ปิด CameraWorker ให้ตรงกับกล้องใน DB
    - ทุก GALLERY_REFRESH วินาที: GET /api/internal/rooms/{roomId}/gallery → embedding ของคาบที่อยู่ในห้องตอนนี้
    - ทุก 5 วินาที: heartbeat ของแต่ละกล้อง (live = มีเฟรมเข้ามาใน SIGNAL_TIMEOUT วินาทีล่าสุด) ส่งเมื่อสถานะเปลี่ยน
      หรือทุก HEARTBEAT_INTERVAL วินาที
  CameraWorker (1 thread ต่อกล้อง)
    - อ่านเฟรมต่อเนื่อง (ไม่ให้ค้างใน buffer) · ทุก FRAME_INTERVAL วินาทีเอาเฟรมล่าสุดไปตรวจ
    - ตรวจเฉพาะตอนห้องมีคาบ · ส่งจำนวนใบหน้า (faces) ทุก FACES_INTERVAL วินาที
    - จับคู่ได้ = cosine >= MATCH_THRESHOLD และห่างคนที่ 2 >= MATCH_MARGIN · ต้องเห็นซ้ำ MIN_HITS ครั้งใน HIT_WINDOW วินาที
      แล้วส่ง sighting (คนเดิมไม่ส่งซ้ำภายใน SIGHTING_COOLDOWN วินาที) — กติกาเช็คชื่อทั้งหมดอยู่ที่ backend
ไม่เก็บภาพ · log ไม่มี embedding / ชื่อ (มีแค่ 8 ตัวแรกของ student_id)
"""
import json
import logging
import os
import threading
import time
import urllib.error
import urllib.request
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import numpy as np

from gallery import GalleryStore, best_matches

log = logging.getLogger("camera")
TH = timezone(timedelta(hours=7))   # เวลาไทย (ไม่มี DST) — backend รับ seenAt เป็นเวลาไทยไม่มี offset


def _env_bool(name: str, default: bool) -> bool:
    return os.getenv(name, str(default)).strip().lower() in ("1", "true", "yes", "on")


@dataclass
class Config:
    backend_url: str = os.getenv("BACKEND_URL", "http://backend:8081").rstrip("/")
    rtsp_base: str = os.getenv("MEDIAMTX_RTSP_URL", "rtsp://mediamtx.media.svc.cluster.local:8554").rstrip("/")
    internal_key: str = os.getenv("INTERNAL_API_KEY", "")
    frame_interval: float = float(os.getenv("FRAME_INTERVAL", "1.0"))
    match_threshold: float = float(os.getenv("MATCH_THRESHOLD", "0.363"))   # ค่าแนะนำของ SFace (cosine)
    match_margin: float = float(os.getenv("MATCH_MARGIN", "0.05"))
    min_hits: int = int(os.getenv("MIN_HITS", "2"))
    hit_window: float = float(os.getenv("HIT_WINDOW", "10"))
    sighting_cooldown: float = float(os.getenv("SIGHTING_COOLDOWN", "60"))
    min_conf: float = float(os.getenv("CAMERA_MIN_CONF", "0.6"))
    min_face_px: int = int(os.getenv("CAMERA_MIN_FACE_PX", "40"))
    faces_interval: float = float(os.getenv("FACES_INTERVAL", "5"))
    heartbeat_interval: float = float(os.getenv("HEARTBEAT_INTERVAL", "30"))
    signal_timeout: float = float(os.getenv("SIGNAL_TIMEOUT", "10"))
    cameras_refresh: float = float(os.getenv("CAMERAS_REFRESH", "60"))
    gallery_refresh: float = float(os.getenv("GALLERY_REFRESH", "30"))
    tick: float = 5.0


class BackendError(RuntimeError):
    pass


class BackendClient:
    def __init__(self, base_url: str, key: str, timeout: float = 5.0):
        self.base_url, self.key, self.timeout = base_url, key, timeout

    def _call(self, method: str, path: str, body: dict | None = None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base_url + path, data=data, method=method,
                                     headers={"X-Internal-Key": self.key, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as res:
                raw = res.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            raise BackendError(f"{method} {path} → {e.code}") from None
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise BackendError(f"{method} {path} → {e}") from None

    def get(self, path: str):
        return self._call("GET", path)

    def post(self, path: str, body: dict):
        return self._call("POST", path, body)


def open_rtsp(url: str):
    """VideoCapture ผ่าน FFMPEG · RTSP แบบ TCP · timeout 5 วินาทีทั้งตอนเปิดและตอนอ่าน (กล้องหลุดจะไม่ค้าง)"""
    import cv2
    os.environ.setdefault("OPENCV_FFMPEG_CAPTURE_OPTIONS", "rtsp_transport;tcp")
    return cv2.VideoCapture(url, cv2.CAP_FFMPEG,
                            [cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, 5000, cv2.CAP_PROP_READ_TIMEOUT_MSEC, 5000])


def now_th() -> str:
    return datetime.now(TH).replace(tzinfo=None).isoformat(timespec="seconds")


class CameraWorker(threading.Thread):
    def __init__(self, cam: dict, cfg: Config, backend: BackendClient, gallery: GalleryStore, vision,
                 open_capture=open_rtsp, clock=time.monotonic):
        super().__init__(name=f"camera-{cam['cameraCode']}", daemon=True)
        self.code: str = cam["cameraCode"]
        self.room_id: str = cam["roomId"]
        self.stream_path: str = cam["streamPath"].strip("/")
        self.url = f"{cfg.rtsp_base}/{self.stream_path}"
        self.cfg, self.backend, self.gallery, self.vision = cfg, backend, gallery, vision
        self.open_capture, self.clock = open_capture, clock
        self.stop_event = threading.Event()
        self.last_frame_at: float | None = None
        self.last_faces_at = -1e9
        self.last_detected: int | None = None
        self.hits: dict[str, deque] = {}
        self.last_sent: dict[str, float] = {}
        self.sightings_sent = 0
        self.last_error: str | None = None

    # ---------- อ่านภาพ ----------
    def run(self):
        backoff = 2.0
        while not self.stop_event.is_set():
            cap = self.open_capture(self.url)
            if cap is None or not cap.isOpened():
                self._error(f"เปิด {self.url} ไม่ได้")
                self.stop_event.wait(backoff)
                backoff = min(backoff * 2, 30.0)
                continue
            log.info("%s: เปิดภาพ %s แล้ว", self.code, self.url)
            backoff = 2.0
            try:
                self._read(cap)
            finally:
                cap.release()
            if not self.stop_event.is_set():
                self._error("ภาพหลุด จะเปิดใหม่")
                self.stop_event.wait(backoff)

    def _read(self, cap):
        next_process = 0.0
        while not self.stop_event.is_set():
            if not cap.grab():
                return
            now = self.clock()
            self.last_frame_at = now
            if now < next_process:
                continue
            next_process = now + self.cfg.frame_interval
            ok, frame = cap.retrieve()
            if ok and frame is not None:
                try:
                    self.process(frame, now)
                except Exception as e:  # ไม่ให้ thread ตาย · log แค่ข้อความ
                    self._error(f"ตรวจภาพไม่สำเร็จ: {e}")

    def _error(self, msg: str):
        if msg != self.last_error:
            log.warning("%s: %s", self.code, msg)
        self.last_error = msg

    # ---------- ตรวจ + จับคู่ ----------
    def process(self, frame: np.ndarray, now: float):
        g = self.gallery.get(self.room_id)
        if g is None or g.session_id is None:      # ไม่มีคาบ → ไม่ต้องตรวจ (ประหยัด CPU)
            return
        faces = self.vision.detect(frame)
        self._report_faces(len(faces), now)
        if not g.active:
            return
        good = [f for f in faces if f[-1] >= self.cfg.min_conf and min(f[2], f[3]) >= self.cfg.min_face_px]
        if not good:
            return
        feats = np.stack([self.vision.feature(frame, f) for f in good])
        seen_at = now_th()
        for sid, score in best_matches(feats, g, self.cfg.match_threshold, self.cfg.match_margin):
            if sid is not None:
                self._hit(sid, score, now, seen_at)

    def _report_faces(self, detected: int, now: float):
        if now - self.last_faces_at < self.cfg.faces_interval:
            return
        self.last_faces_at, self.last_detected = now, detected
        try:
            self.backend.post(f"/api/internal/rooms/{self.room_id}/faces", {"detected": detected, "at": now_th()})
        except BackendError as e:
            self._error(str(e))

    def _hit(self, sid: str, score: float, now: float, seen_at: str):
        q = self.hits.setdefault(sid, deque())
        q.append((now, score, seen_at))
        while q and now - q[0][0] > self.cfg.hit_window:
            q.popleft()
        if len(q) < self.cfg.min_hits or now - self.last_sent.get(sid, -1e9) < self.cfg.sighting_cooldown:
            return
        first_seen = q[0][2]                      # เวลาที่เห็นครั้งแรกในช่วงนี้ (ใช้ตัดสินตรงเวลา / สาย)
        best = max(s for _, s, _ in q)
        self.last_sent[sid] = now
        q.clear()
        try:
            res = self.backend.post(f"/api/internal/rooms/{self.room_id}/sightings",
                                    {"studentId": sid, "matchScore": round(best, 4), "seenAt": first_seen})
            self.sightings_sent += 1
            log.info("%s: student %s… score %.3f → %s%s", self.code, sid[:8], best, res.get("result"),
                     f" ({res['reason']})" if res.get("reason") else f" ({res['status']})" if res.get("status") else "")
        except BackendError as e:
            self.last_sent.pop(sid, None)         # ส่งไม่สำเร็จ → ลองใหม่รอบหน้า
            self._error(str(e))

    def signal(self, now: float) -> str:
        return "live" if self.last_frame_at is not None and now - self.last_frame_at < self.cfg.signal_timeout \
            else "no_signal"

    def stop(self):
        self.stop_event.set()


class Supervisor(threading.Thread):
    def __init__(self, cfg: Config, backend: BackendClient, gallery: GalleryStore, vision,
                 open_capture=open_rtsp, clock=time.monotonic):
        super().__init__(name="camera-supervisor", daemon=True)
        self.cfg, self.backend, self.gallery, self.vision = cfg, backend, gallery, vision
        self.open_capture, self.clock = open_capture, clock
        self.workers: dict[str, CameraWorker] = {}
        self.hb_sent: dict[str, tuple[str, float]] = {}
        self.next_cameras = 0.0
        self.next_gallery = 0.0
        self.stop_event = threading.Event()
        self.last_error: str | None = None

    def run(self):
        log.info("เริ่มตัวอ่านกล้อง · backend %s · MediaMTX %s", self.cfg.backend_url, self.cfg.rtsp_base)
        while not self.stop_event.is_set():
            self.tick()
            self.stop_event.wait(self.cfg.tick)
        for w in self.workers.values():
            w.stop()

    def tick(self):
        now = self.clock()
        if now >= self.next_cameras:
            self.next_cameras = now + self.cfg.cameras_refresh
            self.refresh_cameras()
        if now >= self.next_gallery:
            self.next_gallery = now + self.cfg.gallery_refresh
            self.refresh_galleries()
        for w in list(self.workers.values()):
            self.heartbeat(w, now)

    def refresh_cameras(self):
        try:
            cams = self.backend.get("/api/internal/cameras") or []
        except BackendError as e:
            self._error(str(e))
            return
        wanted = {c["cameraCode"]: c for c in cams}
        for code in list(self.workers):
            w = self.workers[code]
            c = wanted.get(code)
            if c is None or c["roomId"] != w.room_id or c["streamPath"].strip("/") != w.stream_path:
                log.info("%s: หยุดอ่าน (กล้องถูกปิด / ย้ายห้อง / เปลี่ยน path)", code)
                w.stop()
                del self.workers[code]
                self.hb_sent.pop(code, None)
                if not any(x.room_id == w.room_id for x in self.workers.values()):
                    self.gallery.drop_room(w.room_id)
        for code, c in wanted.items():
            if code not in self.workers:
                w = CameraWorker(c, self.cfg, self.backend, self.gallery, self.vision, self.open_capture, self.clock)
                self.workers[code] = w
                w.start()
        self.last_error = None

    def refresh_galleries(self):
        for room_id in {w.room_id for w in self.workers.values()}:
            try:
                before = self.gallery.get(room_id)
                g = self.gallery.update(room_id, self.backend.get(f"/api/internal/rooms/{room_id}/gallery"))
                if before is None or (before.session_id, before.students) != (g.session_id, g.students):
                    log.info("room %s: คาบ %s · กล้องเช็คชื่อ %s · ใบหน้า %d คน", room_id[:8],
                             (g.session_id or "-")[:8], "เปิด" if g.camera_enabled else "ปิด", g.students)
            except BackendError as e:
                self._error(str(e))           # backend ล่ม: ใช้ข้อมูลเดิมไปก่อน

    def heartbeat(self, w: CameraWorker, now: float):
        status = w.signal(now)
        prev = self.hb_sent.get(w.code)
        if prev and prev[0] == status and now - prev[1] < self.cfg.heartbeat_interval:
            return
        try:
            self.backend.post(f"/api/internal/cameras/{w.code}/heartbeat", {"status": status})
            if not prev or prev[0] != status:
                log.info("%s: %s", w.code, status)
            self.hb_sent[w.code] = (status, now)
        except BackendError as e:
            self._error(str(e))

    def _error(self, msg: str):
        if msg != self.last_error:
            log.warning("%s", msg)
        self.last_error = msg

    def status(self) -> list[dict]:
        now = self.clock()
        out = []
        for w in self.workers.values():
            g = self.gallery.get(w.room_id)
            out.append({
                "cameraCode": w.code, "roomId": w.room_id, "streamPath": w.stream_path,
                "signal": w.signal(now),
                "lastFrameAgeSec": None if w.last_frame_at is None else round(now - w.last_frame_at, 1),
                "session": g is not None and g.session_id is not None,
                "cameraEnabled": bool(g and g.camera_enabled),
                "galleryStudents": g.students if g else 0,
                "facesLastReport": w.last_detected,
                "sightingsSent": w.sightings_sent,
                "lastError": w.last_error,
            })
        return out

    def stop(self):
        self.stop_event.set()
