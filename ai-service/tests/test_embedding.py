"""
ทดสอบ POST /embedding (anti-spoofing + ทุกรูปเป็นคนเดียวกัน), GET /metrics, vision.liveness และเครื่องมือวัดผล
โดยไม่ต้องมีโมเดล: vision ปลอม / ultralytics ปลอม / cv2.dnn ปลอม
"""
import importlib.util
import os
import sys
import types
import uuid
from pathlib import Path

import cv2
import numpy as np
import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from metrics import Timings, one_line  # noqa: E402


def axis(i):
    v = np.zeros(128, np.float32)
    v[i] = 1.0
    return v


# ---------------- vision.py (ของจริง แต่ไม่มีไฟล์โมเดล) ----------------

def load_vision(monkeypatch, tmp_path, mode="on", files=True, logits=(0.0, 2.0, 0.0)):
    """โหลด vision.py ใหม่ (ชื่อ vision_under_test) · YOLO / SFace ปลอม · cv2.dnn คืน logits ที่กำหนด"""
    fake_ul = types.ModuleType("ultralytics")
    fake_ul.YOLO = lambda path: None
    monkeypatch.setitem(sys.modules, "ultralytics", fake_ul)
    monkeypatch.setattr(cv2, "FaceRecognizerSF", types.SimpleNamespace(create=lambda *a: None))

    class Net:
        def __init__(self):
            self.inputs = []

        def setInput(self, blob):
            self.inputs.append(blob)

        def forward(self):
            return np.array([logits], np.float32)

    nets = []

    def read(path):
        nets.append(Net())
        return nets[-1]

    monkeypatch.setattr(cv2.dnn, "readNetFromONNX", read)
    v2, v1 = tmp_path / "v2.onnx", tmp_path / "v1se.onnx"
    if files:
        v2.write_bytes(b"x")
        v1.write_bytes(b"x")
    monkeypatch.setenv("ANTISPOOF", mode)
    monkeypatch.setenv("ANTISPOOF_MODEL_V2", str(v2))
    monkeypatch.setenv("ANTISPOOF_MODEL_V1SE", str(v1))
    spec = importlib.util.spec_from_file_location("vision_under_test", ROOT / "vision.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod, nets


def test_liveness_is_mean_softmax_of_real_class(monkeypatch, tmp_path):
    v, nets = load_vision(monkeypatch, tmp_path, logits=(0.0, 2.0, 0.0))
    assert v.ANTISPOOF_READY and v.ANTISPOOF_ENFORCE and len(nets) == 2
    img = np.zeros((480, 640, 3), np.uint8)
    face = np.array([300, 200, 100, 120, *([0] * 10), 0.9], np.float32)
    want = np.exp(2) / (np.exp(2) + 2)
    assert v.liveness(img, face) == pytest.approx(want, rel=1e-5)
    for net in nets:
        blob = net.inputs[-1]
        assert blob.shape == (1, 3, 80, 80) and blob.dtype == np.float32    # NCHW 80x80 ไม่ normalize


def test_spoof_crop_stays_inside_image():
    spec = importlib.util.spec_from_file_location("vision_crop", ROOT / "vision.py")
    src = spec.loader.get_source("vision_crop")
    ns: dict = {}
    # ดึงเฉพาะ spoof_crop มาทดสอบ (ไม่ต้องโหลดโมเดล)
    start = src.index("def spoof_crop")
    end = src.index("def liveness")
    exec("import cv2\nimport numpy as np\nSPOOF_INPUT = 80\n" + src[start:end], ns)
    img = np.arange(480 * 640 * 3, dtype=np.uint32).reshape(480, 640, 3).astype(np.uint8)
    for box in ([0, 0, 50, 50], [600, 440, 40, 40], [0, 0, 640, 480], [300, 200, 100, 100]):
        face = np.array([*box, *([0] * 10), 0.9], np.float32)
        assert ns["spoof_crop"](img, face, 4.0).shape == (80, 80, 3)


def test_antispoof_disables_itself_without_model_files(monkeypatch, tmp_path, caplog):
    v, _ = load_vision(monkeypatch, tmp_path, files=False)
    assert not v.ANTISPOOF_READY and not v.ANTISPOOF_ENFORCE
    assert v.liveness(np.zeros((10, 10, 3), np.uint8), np.zeros(15, np.float32)) is None
    assert "ปิดเอง" in caplog.text


def test_antispoof_log_mode_scores_but_does_not_enforce(monkeypatch, tmp_path):
    v, _ = load_vision(monkeypatch, tmp_path, mode="log")
    assert v.ANTISPOOF_READY and not v.ANTISPOOF_ENFORCE
    v, _ = load_vision(monkeypatch, tmp_path, mode="off")
    assert not v.ANTISPOOF_READY


# ---------------- main.py: POST /embedding ----------------

class FakeVision(types.ModuleType):
    """ภาพ = สีเดียว: ช่อง B = index ของ embedding (คนละคน = คนละแกน) · ช่อง G = liveness × 100"""
    class ModelError(RuntimeError):
        pass

    def __init__(self):
        super().__init__("vision")
        self.ANTISPOOF_READY = True
        self.ANTISPOOF_ENFORCE = True

    def detect(self, img):
        return [np.array([10, 10, 150, 150, *([0] * 10), 0.95], np.float32)]

    def feature(self, img, face):
        return axis(int(img[0, 0, 0]))

    def liveness(self, img, face):
        return None if not self.ANTISPOOF_READY else img[0, 0, 1] / 100


@pytest.fixture()
def api(monkeypatch):
    fv = FakeVision()
    monkeypatch.setitem(sys.modules, "vision", fv)
    monkeypatch.delitem(sys.modules, "main", raising=False)
    import main
    monkeypatch.setattr(main, "check_quality", lambda img, face, name: None)   # ภาพสีเดียว = เบลอเสมอ
    from fastapi.testclient import TestClient
    yield TestClient(main.app), fv, main
    sys.modules.pop("main", None)


def png(person: int, live: float) -> bytes:
    img = np.zeros((200, 200, 3), np.uint8)
    img[:, :, 0] = person
    img[:, :, 1] = int(round(live * 100))
    return cv2.imencode(".png", img)[1].tobytes()


def post(client, *imgs):
    return client.post("/embedding", files=[("files", (f"face_{i + 1}.png", b, "image/png"))
                                            for i, b in enumerate(imgs)])


def test_embedding_ok_returns_liveness(api):
    client, _, _ = api
    r = post(client, png(0, 0.95), png(0, 0.9), png(0, 0.8))
    assert r.status_code == 200
    body = r.json()
    assert body["model"] == "SFace" and len(body["items"]) == 3
    assert [it["liveness"] for it in body["items"]] == [0.95, 0.9, 0.8]
    assert len(body["items"][0]["embedding"]) == 128


def test_embedding_rejects_spoof(api):
    client, _, _ = api
    r = post(client, png(0, 0.95), png(0, 0.4), png(0, 0.9))
    assert r.status_code == 422
    assert r.json()["detail"].startswith("face_2.png: ภาพอาจเป็นรูปถ่ายหรือภาพจากหน้าจอ")


def test_embedding_log_mode_and_missing_model_do_not_reject(api):
    client, fv, _ = api
    fv.ANTISPOOF_ENFORCE = False                       # ANTISPOOF=log
    r = post(client, png(0, 0.1), png(0, 0.1), png(0, 0.1))
    assert r.status_code == 200 and r.json()["items"][0]["liveness"] == 0.1
    fv.ANTISPOOF_READY = False                         # ไม่มีไฟล์โมเดล
    r = post(client, png(0, 0.1), png(0, 0.1), png(0, 0.1))
    assert r.status_code == 200 and r.json()["items"][0]["liveness"] is None


def test_embedding_rejects_images_of_different_people(api):
    client, _, _ = api
    r = post(client, png(0, 0.9), png(0, 0.9), png(7, 0.9))
    assert r.status_code == 422
    assert r.json()["detail"].startswith("face_3.png: ใบหน้าไม่ตรงกับรูปอื่น")


def test_metrics_endpoint_counts_registration(api):
    client, _, main = api
    post(client, png(0, 0.9), png(0, 0.9), png(0, 0.9))
    post(client, png(0, 0.9), png(0, 0.2), png(0, 0.9))
    m = client.get("/metrics").json()
    assert m["antispoof"] == {"ready": True, "enforce": True, "minReal": 0.7}
    reg = m["registration"]
    assert reg["counts"]["registrations"] == 1 and reg["counts"]["rejected_spoof"] == 1
    assert reg["ms"]["detect"]["n"] == 5 and reg["ms"]["feature"]["n"] == 4
    assert m["cameras"] == {}


# ---------------- metrics.py ----------------

def test_timings_summary_percentiles():
    t = Timings(clock=lambda: 10.0)
    for v in range(1, 101):
        t.add("detect", v)
    t.count("frames_processed", 4)
    t.count("faces_detected", 10)
    s = t.summary()
    assert s["ms"]["detect"]["p50"] == pytest.approx(50.5) and s["ms"]["detect"]["p95"] == pytest.approx(95.05)
    assert s["ms"]["detect"]["n"] == 100 and s["ms"]["detect"]["max"] == 100
    assert "ใบหน้า/เฟรม 2.5" in one_line(s)
    small = Timings(window=3)
    for v in range(10):
        small.add("x", v)
    assert small.summary()["ms"]["x"]["n"] == 3


def test_camera_worker_records_stage_timings():
    from test_camera_worker import KEY, FakeBackend, FakeVision as CamVision, face_row, make_worker, payload
    from gallery import GalleryStore, load_key
    import test_camera_worker as tcw

    a = str(uuid.uuid4())
    store = GalleryStore(load_key(KEY))
    store.update(tcw.ROOM, payload(students={a: [tcw.axis(0)]}))
    w = make_worker(CamVision([face_row(0), face_row(9)]), FakeBackend(), store)
    frame = np.zeros((10, 10, 3), np.uint8)
    w.process(frame, now=100)
    w.process(frame, now=101.5)
    s = w.metrics.summary()
    assert s["counts"]["frames_processed"] == 2 and s["counts"]["faces_detected"] == 4
    assert s["counts"]["faces_matched"] == 2
    assert s["ms"]["feature"]["n"] == 4 and s["ms"]["frame"]["n"] == 2 and s["ms"]["match"]["n"] == 2
    assert s["ms"]["sighting"]["n"] == 1 and s["ms"]["sighting"]["p50"] >= 1500      # เห็นครั้งแรก t=100 → ส่ง t=101.5


# ---------------- tools/eval_recognition.py ----------------

def test_eval_recognition_report(tmp_path):
    sys.path.insert(0, str(ROOT / "tools"))
    import eval_recognition as ev

    # ภาพปลอม: ชื่อไฟล์บอกว่าเป็นใคร (แกน embedding) / liveness / ไม่มีหน้า
    def make(root, person, name):
        d = root / person
        d.mkdir(parents=True, exist_ok=True)
        (d / name).write_bytes(b"")

    for p, i in (("p01", 0), ("p02", 1)):
        for k in range(3):
            make(tmp_path / "enroll", p, f"{i}_live95_{k}.jpg")
    make(tmp_path / "probe", "p01", "0_live90_a.jpg")
    make(tmp_path / "probe", "p01", "1_live90_b.jpg")          # p01 แต่หน้าเหมือน p02 → ผิดคน
    make(tmp_path / "probe", "p02", "1_live90_c.jpg")
    make(tmp_path / "probe", "p02", "noface.jpg")
    make(tmp_path / "probe", "x99", "5_live90_d.jpg")          # คนแปลกหน้า
    make(tmp_path / "spoof", "print", "0_live20_e.jpg")

    class V:
        ANTISPOOF_READY = True

        def detect(self, img):
            return [] if img == "noface" else [np.array([0, 0, 100, 100, *([0] * 10), 0.9], np.float32)]

        def feature(self, img, f):
            return axis(int(img.split("_")[0]))

        def liveness(self, img, f):
            return int(img.split("_")[1][4:]) / 100

    runner = ev.Runner(V(), read=lambda p: p.stem)
    report = ev.evaluate(runner, ev.list_people(tmp_path / "enroll"), ev.list_people(tmp_path / "probe"),
                         ev.list_images(tmp_path / "spoof"), [0.363], 0.05, 40, 0.6, [0.7])
    assert "| คนที่ลงทะเบียน | 2 คน · 6 ภาพ (ใช้ไม่ได้ 0) |" in report
    assert "ตรวจพบใบหน้าในภาพทดสอบ | 80.0% (4/5)" in report
    assert "| Rank-1 (ไม่ใช้ threshold) | 66.7% (2/3) |" in report
    # threshold 0.363: TAR 2/3 · ถูกคน 2/3 · ผิดคน 1/3 · คนแปลกหน้าถูกจับคู่ 0/1 · accuracy (2 + 1) / 4
    assert "| 0.363 | 66.7% (2/3) |" in report and "| 75.0% (3/4) |" in report
    assert "| 0.70 | 0.0% (0/10) | 0.0% (0/1) |" in report
    assert "SFace embedding (ต่อใบหน้า) | 11 |" in report


def test_startup_logs_antispoof_state(api, caplog):
    import logging
    from fastapi.testclient import TestClient
    _, fv, main = api
    caplog.set_level(logging.INFO, logger="ai-service")
    with TestClient(main.app):
        pass
    assert "anti-spoofing: พร้อม · ปฏิเสธภาพที่คะแนน < 0.70" in caplog.text
    caplog.clear()
    fv.ANTISPOOF_READY = fv.ANTISPOOF_ENFORCE = False
    with TestClient(main.app):
        pass
    assert "anti-spoofing: ปิด" in caplog.text
