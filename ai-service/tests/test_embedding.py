"""
ทดสอบ vision.py (YOLO face-pose + SFace · ไม่มี anti-spoofing), POST /embedding (คุณภาพภาพ + ทุกรูปเป็นคนเดียวกัน),
GET /metrics และเครื่องมือวัดผล — โดยไม่ต้องมีไฟล์โมเดล: YOLO / SFace ปลอม
"""
import importlib.util
import logging
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


# ---------------- vision.py (ของจริง · โมเดลปลอม) ----------------

class _T:
    """tensor ปลอมพอให้ vision.detect ใช้ได้ (.cpu().numpy() / .tolist() / [i])"""
    def __init__(self, a):
        self.a = np.asarray(a, np.float32)

    def cpu(self):
        return self

    def numpy(self):
        return self.a

    def tolist(self):
        return self.a.tolist()

    def __getitem__(self, i):
        return _T(self.a[i]) if np.ndim(self.a[i]) else float(self.a[i])


def yolo_result(boxes, kps, keypoints=True):
    """boxes = [(x1, y1, x2, y2, conf)] · kps = [[(x, y) × n]]"""
    bs = [types.SimpleNamespace(xyxy=_T([b[:4]]), conf=_T([b[4]])) for b in boxes]
    kp = types.SimpleNamespace(xy=[_T(k) for k in kps]) if keypoints else None
    return types.SimpleNamespace(boxes=bs, keypoints=kp)


def load_vision(monkeypatch, result=None, feat=None):
    """โหลด vision.py ใหม่ (ชื่อ vision_under_test) · ห้ามโหลด ONNX อื่นนอกจาก SFace (cv2.dnn ถูกเรียก = fail)"""
    holder = {"result": result, "feat": feat}

    class Det:
        def predict(self, img, conf, verbose):
            holder["conf"] = conf
            return [holder["result"]]

    class Rec:
        def alignCrop(self, img, face):
            holder["aligned"] = face
            return img

        def feature(self, aligned):
            return np.asarray(holder["feat"], np.float32).reshape(1, -1)

    fake_ul = types.ModuleType("ultralytics")
    fake_ul.YOLO = lambda path: Det()
    monkeypatch.setitem(sys.modules, "ultralytics", fake_ul)
    monkeypatch.setattr(cv2, "FaceRecognizerSF", types.SimpleNamespace(create=lambda *a: Rec()))

    def no_dnn(*a, **k):
        raise AssertionError("vision.py ต้องไม่โหลดโมเดล ONNX ผ่าน cv2.dnn (ไม่มี anti-spoofing แล้ว)")

    monkeypatch.setattr(cv2.dnn, "readNetFromONNX", no_dnn)
    monkeypatch.setenv("YOLO_FACE_MODEL", "/nonexistent/yolo.pt")
    monkeypatch.setenv("SFACE_MODEL", "/nonexistent/sface.onnx")
    spec = importlib.util.spec_from_file_location("vision_under_test", ROOT / "vision.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod, holder


FIVE = [(10, 20), (30, 20), (20, 30), (12, 40), (28, 40)]


def test_vision_imports_without_any_antispoof_model(monkeypatch):
    v, _ = load_vision(monkeypatch)
    for name in ("liveness", "spoof_crop", "ANTISPOOF_READY", "ANTISPOOF_ENFORCE", "SPOOF_LOCK"):
        assert not hasattr(v, name), name
    assert callable(v.detect) and callable(v.feature)


def test_detect_returns_box_landmarks_score(monkeypatch):
    v, h = load_vision(monkeypatch, yolo_result([(5, 6, 105, 126, 0.91)], [FIVE]))
    faces = v.detect(np.zeros((200, 200, 3), np.uint8))
    assert h["conf"] == 0.5 and len(faces) == 1
    f = faces[0]
    assert f.shape == (15,) and f.dtype == np.float32
    assert f[:4].tolist() == [5, 6, 100, 120]                       # x, y, w, h
    assert f[4:14].tolist() == [c for pt in FIVE for c in pt]
    assert f[-1] == pytest.approx(0.91)


def test_detect_no_face_and_wrong_model(monkeypatch):
    v, h = load_vision(monkeypatch, yolo_result([], []))
    img = np.zeros((50, 50, 3), np.uint8)
    assert v.detect(img) == []
    h["result"] = yolo_result([(0, 0, 10, 10, 0.9)], [FIVE], keypoints=False)
    with pytest.raises(v.ModelError):
        v.detect(img)
    h["result"] = yolo_result([(0, 0, 10, 10, 0.9)], [FIVE[:3]])
    with pytest.raises(v.ModelError):
        v.detect(img)


def test_feature_is_l2_normalized_128(monkeypatch):
    raw = np.arange(1, 129, dtype=np.float32)
    v, _ = load_vision(monkeypatch, feat=raw)
    out = v.feature(np.zeros((112, 112, 3), np.uint8), np.zeros(15, np.float32))
    assert out.shape == (128,) and out.dtype == np.float32
    assert np.linalg.norm(out) == pytest.approx(1.0, rel=1e-6)
    np.testing.assert_allclose(out, raw / np.linalg.norm(raw), rtol=1e-6)


@pytest.mark.parametrize("bad", [np.zeros(128), np.zeros(0), np.ones(64), np.full(128, np.nan)])
def test_feature_rejects_invalid_embedding(monkeypatch, bad):
    v, _ = load_vision(monkeypatch, feat=bad)
    with pytest.raises(v.EmbeddingError):
        v.feature(np.zeros((112, 112, 3), np.uint8), np.zeros(15, np.float32))


# ---------------- main.py: POST /embedding ----------------

class FakeVision(types.ModuleType):
    """ภาพ = สีเดียว: ช่อง B = index ของ embedding (คนละคน = คนละแกน · 255 = embedding เสีย) · ช่อง G = จำนวนใบหน้า"""
    class ModelError(RuntimeError):
        pass

    class EmbeddingError(ValueError):
        pass

    def __init__(self):
        super().__init__("vision")

    def detect(self, img):
        return [np.array([10, 10, 150, 150, *([0] * 10), 0.95], np.float32)] * int(img[0, 0, 1])

    def feature(self, img, face):
        i = int(img[0, 0, 0])
        if i == 255:
            raise self.EmbeddingError("bad")
        return axis(i)


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


def png(person: int, faces: int = 1) -> bytes:
    img = np.zeros((200, 200, 3), np.uint8)
    img[:, :, 0] = person
    img[:, :, 1] = faces
    return cv2.imencode(".png", img)[1].tobytes()


def post(client, *imgs):
    return client.post("/embedding", files=[("files", (f"face_{i + 1}.png", b, "image/png"))
                                            for i, b in enumerate(imgs)])


def test_embedding_ok_response_shape(api):
    client, _, _ = api
    r = post(client, png(0), png(0), png(0))
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"model", "model_version", "items"}
    assert body["model"] == "SFace" and body["model_version"] == "2021dec" and len(body["items"]) == 3
    for it in body["items"]:
        assert set(it) == {"embedding", "quality", "liveness"}
        assert it["liveness"] is None                          # ไม่มีการตรวจภาพหลอก · ไม่มีคะแนนปลอม
        assert len(it["embedding"]) == 128 and it["quality"] == 0.95
        assert np.linalg.norm(it["embedding"]) == pytest.approx(1.0)


def test_embedding_face_count_and_bad_image(api):
    client, _, _ = api
    r = post(client, png(0), png(0, faces=0))
    assert r.status_code == 422 and r.json()["detail"] == "face_2.png: ไม่พบใบหน้า"
    r = post(client, png(0, faces=2))
    assert r.status_code == 422 and r.json()["detail"] == "face_1.png: พบหลายใบหน้า ต้องมีเพียง 1 คน"
    r = post(client, b"not an image")
    assert r.status_code == 422 and r.json()["detail"] == "face_1.png: อ่านไฟล์ภาพไม่ได้"


def test_embedding_invalid_embedding_is_422(api):
    client, _, _ = api
    r = post(client, png(0), png(255))
    assert r.status_code == 422 and r.json()["detail"].startswith("face_2.png: สร้างข้อมูลใบหน้าไม่ได้")


def test_embedding_rejects_images_of_different_people(api):
    client, _, main = api
    assert main.SAME_PERSON_MIN == pytest.approx(0.363)       # ค่าเดิม
    r = post(client, png(0), png(0), png(7))
    assert r.status_code == 422
    assert r.json()["detail"].startswith("face_3.png: ใบหน้าไม่ตรงกับรูปอื่น")
    assert post(client, png(3)).status_code == 200            # รูปเดียว = ไม่ต้องเทียบ


def test_quality_checks(api):
    from fastapi import HTTPException
    # fixture แทน main.check_quality ไว้ → โหลด main.py อีกชุดเพื่อใช้ check_quality ตัวจริง (vision ยังเป็นตัวปลอม)
    spec = importlib.util.spec_from_file_location("main_quality", ROOT / "main.py")
    real = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(real)
    rng = np.random.default_rng(0)
    sharp = rng.integers(0, 256, (300, 300, 3), dtype=np.uint8)
    flat = np.full((300, 300, 3), 128, np.uint8)

    def face(x, y, w, h, conf):
        return np.array([x, y, w, h, *([0] * 10), conf], np.float32)

    real.check_quality(sharp, face(20, 20, 150, 150, 0.95), "ok.png")      # ผ่าน
    cases = [(sharp, face(20, 20, 150, 150, 0.5), "ความมั่นใจในการตรวจใบหน้าต่ำ"),
             (sharp, face(20, 20, 80, 80, 0.95), "ใบหน้าเล็กเกินไป"),
             (sharp, face(400, 400, 150, 150, 0.95), "ใบหน้าอยู่นอกกรอบภาพ"),
             (flat, face(20, 20, 150, 150, 0.95), "ภาพเบลอ")]
    for img, f, msg in cases:
        with pytest.raises(HTTPException) as e:
            real.check_quality(img, f, "x.png")
        assert e.value.status_code == 422 and msg in e.value.detail


def test_metrics_endpoint_counts_registration(api):
    client, _, _ = api
    post(client, png(0), png(0), png(0))
    post(client, png(0), png(0), png(9))                      # คนละคน → ปฏิเสธ
    m = client.get("/metrics").json()
    assert set(m) == {"registration", "cameras"}             # ไม่มี antispoof แล้ว
    reg = m["registration"]
    assert reg["counts"]["registrations"] == 1 and reg["counts"]["rejected_not_same_person"] == 1
    assert "rejected_spoof" not in reg["counts"] and "liveness" not in reg["ms"]
    assert reg["ms"]["detect"]["n"] == 6 and reg["ms"]["feature"]["n"] == 6
    assert m["cameras"] == {}


def test_startup_does_not_mention_antispoof(api, caplog):
    from fastapi.testclient import TestClient
    _, _, main = api
    caplog.set_level(logging.INFO)
    with TestClient(main.app):
        pass
    assert "ตัวอ่านกล้องปิดอยู่" in caplog.text and "anti-spoofing" not in caplog.text


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

    # ภาพปลอม: ชื่อไฟล์บอกว่าเป็นใคร (แกน embedding) / ไม่มีหน้า
    def make(root, person, name):
        d = root / person
        d.mkdir(parents=True, exist_ok=True)
        (d / name).write_bytes(b"")

    for p, i in (("p01", 0), ("p02", 1)):
        for k in range(3):
            make(tmp_path / "enroll", p, f"{i}_{k}.jpg")
    make(tmp_path / "probe", "p01", "0_a.jpg")
    make(tmp_path / "probe", "p01", "1_b.jpg")          # p01 แต่หน้าเหมือน p02 → ผิดคน
    make(tmp_path / "probe", "p02", "1_c.jpg")
    make(tmp_path / "probe", "p02", "noface.jpg")
    make(tmp_path / "probe", "x99", "5_d.jpg")          # คนแปลกหน้า

    class V:
        def detect(self, img):
            return [] if img == "noface" else [np.array([0, 0, 100, 100, *([0] * 10), 0.9], np.float32)]

        def feature(self, img, f):
            return axis(int(img.split("_")[0]))

    runner = ev.Runner(V(), read=lambda p: p.stem)
    report = ev.evaluate(runner, ev.list_people(tmp_path / "enroll"), ev.list_people(tmp_path / "probe"),
                         [0.363], 0.05, 40, 0.6)
    assert "| คนที่ลงทะเบียน | 2 คน · 6 ภาพ (ใช้ไม่ได้ 0) |" in report
    assert "ตรวจพบใบหน้าในภาพทดสอบ | 80.0% (4/5)" in report
    assert "| Rank-1 (ไม่ใช้ threshold) | 66.7% (2/3) |" in report
    # threshold 0.363: TAR 2/3 · ถูกคน 2/3 · ผิดคน 1/3 · คนแปลกหน้าถูกจับคู่ 0/1 · accuracy (2 + 1) / 4
    assert "| 0.363 | 66.7% (2/3) |" in report and "| 75.0% (3/4) |" in report
    assert "SFace embedding (ต่อใบหน้า) | 10 |" in report
    assert "spoof" not in report.lower() and "MiniFASNet" not in report
