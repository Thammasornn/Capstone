"""
วัดเวลาจริงของแต่ละขั้น (ms) เก็บเป็นหน้าต่างเลื่อน แล้วสรุป p50 / p95 / mean / max
  - ตัวอ่านกล้อง: retrieve (แปลงเฟรม), detect (YOLO), feature (SFace ต่อใบหน้า), match (cosine), frame (รวมทั้งเฟรม),
    sighting (เห็นครั้งแรก → POST sighting เสร็จ), post_sighting (เวลารอ backend)
  - POST /embedding: decode, detect, liveness (MiniFASNetV2), feature ต่อภาพ · ตัวอ่านกล้องมี liveness ต่อหน้าด้วย
เปิดดูได้ที่ GET /metrics (ใน cluster เท่านั้น · ai-service ไม่มี Ingress) · ไม่มีชื่อ / embedding / ภาพ
"""
import threading
import time
from collections import deque

import numpy as np

WINDOW = 2000          # เก็บค่าล่าสุดกี่ค่าต่อขั้น


class Timings:
    def __init__(self, window: int = WINDOW, clock=time.monotonic):
        self._lock = threading.Lock()
        self._samples: dict[str, deque] = {}
        self._counts: dict[str, int] = {}
        self._clock = clock
        self._window = window
        self.started_at = clock()

    def add(self, name: str, ms: float) -> None:
        with self._lock:
            self._samples.setdefault(name, deque(maxlen=self._window)).append(float(ms))

    def count(self, name: str, n: int = 1) -> None:
        with self._lock:
            self._counts[name] = self._counts.get(name, 0) + n

    def timer(self, name: str):
        return _Timer(self, name)

    def summary(self) -> dict:
        with self._lock:
            samples = {k: np.asarray(v, np.float64) for k, v in self._samples.items() if v}
            counts = dict(self._counts)
        uptime = max(self._clock() - self.started_at, 1e-9)
        out = {"uptimeSec": round(uptime, 1), "counts": counts,
               "ratePerSec": {k: round(v / uptime, 3) for k, v in counts.items()}, "ms": {}}
        for k, a in samples.items():
            out["ms"][k] = {"n": int(a.size), "mean": round(float(a.mean()), 2),
                            "p50": round(float(np.percentile(a, 50)), 2),
                            "p95": round(float(np.percentile(a, 95)), 2), "max": round(float(a.max()), 2)}
        return out

    def reset(self) -> None:
        with self._lock:
            self._samples.clear()
            self._counts.clear()
            self.started_at = self._clock()


class _Timer:
    def __init__(self, t: Timings, name: str):
        self.t, self.name = t, name

    def __enter__(self):
        self.start = time.perf_counter()
        return self

    def __exit__(self, *exc):
        self.ms = (time.perf_counter() - self.start) * 1000
        self.t.add(self.name, self.ms)
        return False


def one_line(s: dict, keys=("frame", "detect", "feature", "sighting")) -> str:
    """สรุปสั้นสำหรับ log: frame p50/p95 · fps · ใบหน้า/เฟรม"""
    parts = []
    for k in keys:
        m = s["ms"].get(k)
        if m:
            parts.append(f"{k} p50 {m['p50']:.0f} / p95 {m['p95']:.0f} ms (n={m['n']})")
    r = s["ratePerSec"]
    if "frames_processed" in r:
        parts.append(f"ตรวจ {r['frames_processed']:.2f} เฟรม/วินาที · สตรีม {r.get('frames_grabbed', 0):.1f} เฟรม/วินาที")
    c = s["counts"]
    if c.get("frames_processed"):
        parts.append(f"ใบหน้า/เฟรม {c.get('faces_detected', 0) / c['frames_processed']:.1f}")
    return " · ".join(parts) or "ยังไม่มีข้อมูล"
