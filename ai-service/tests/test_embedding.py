"""
ทดสอบ vision.py (YOLO face-pose + SFace + MiniFASNetV2), POST /embedding (คุณภาพ + anti-spoofing + คนเดียวกัน),
ตัวอ่านกล้อง (คุณภาพ / anti-spoofing ก่อน SFace / หลายเฟรม), GET /metrics และเครื่องมือวัดผล
— ไม่ต้องมีไฟล์โมเดล: YOLO / SFace / onnxruntime ปลอม
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


class FakeORT(types.ModuleType):
    """onnxruntime ปลอม: บันทึก option / input · คืน logits ที่กำหนด"""
    def __init__(self, logits=(0.0, 2.0, 0.0), in_shape=("batch_size", 3, 80, 80), out_shape=("batch_size", 3)):
        super().__init__("onnxruntime")
        self.logits, self.in_shape, self.out_shape = logits, in_shape, out_shape
        self.sessions, self.inputs, self.run_error = [], [], None
        self.ExecutionMode = types.SimpleNamespace(ORT_SEQUENTIAL="seq")
        self.GraphOptimizationLevel = types.SimpleNamespace(ORT_ENABLE_ALL="all")
        ort = self

        class SessionOptions:
            pass

        class InferenceSession:
            def __init__(self, path, opts, providers):
                self.path, self.opts, self.providers = path, opts, providers
                ort.sessions.append(self)

            def get_inputs(self):
                return [types.SimpleNamespace(name="input", shape=list(ort.in_shape))]

            def get_outputs(self):
                return [types.SimpleNamespace(name="output", shape=list(ort.out_shape))]

            def run(self, names, feeds):
                if ort.run_error:
                    raise RuntimeError(ort.run_error)
                ort.inputs.append(feeds["input"])
                return [np.array([ort.logits], np.float32)]

        self.SessionOptions, self.InferenceSession = SessionOptions, InferenceSession


def load_vision(monkeypatch, result=None, feat=None, mode="log", model=None, ort=None):
    """โหลด vision.py ใหม่ (ชื่อ vision_under_test) · model = path ไฟล์ MiniFASNet (None = ไม่มีไฟล์)
    · ห้ามใช้ cv2.dnn (anti-spoofing ใช้ onnxruntime)"""
    holder = {"result": result, "feat": feat, "ort": ort or FakeORT()}
    monkeypatch.setitem(sys.modules, "onnxruntime", holder["ort"])
    monkeypatch.setenv("ANTISPOOF", mode)
    monkeypatch.setenv("ANTISPOOF_MODEL", str(model) if model else "/nonexistent/2.7_80x80_MiniFASNetV2.onnx")

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
        raise AssertionError("vision.py ต้องไม่ใช้ cv2.dnn (MiniFASNetV2 ใช้ onnxruntime)")

    monkeypatch.setattr(cv2.dnn, "readNetFromONNX", no_dnn)
    monkeypatch.setenv("YOLO_FACE_MODEL", "/nonexistent/yolo.pt")
    monkeypatch.setenv("SFACE_MODEL", "/nonexistent/sface.onnx")
    spec = importlib.util.spec_from_file_location("vision_under_test", ROOT / "vision.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod, holder


FIVE = [(10, 20), (30, 20), (20, 30), (12, 40), (28, 40)]


@pytest.fixture()
def model_file(tmp_path):
    f = tmp_path / "2.7_80x80_MiniFASNetV2.onnx"
    f.write_bytes(b"x")
    return f


FACE = np.array([60, 50, 80, 90, *([0] * 10), 0.9], np.float32)


def test_vision_imports_without_antispoof_model_file(monkeypatch, caplog):
    v, h = load_vision(monkeypatch)
    assert v.ANTISPOOF == "log" and not v.ANTISPOOF_READY and not v.ANTISPOOF_ENFORCE
    assert "ไม่พบโมเดล" in v.ANTISPOOF_ERROR and "ใช้ไม่ได้" in caplog.text and h["ort"].sessions == []
    with pytest.raises(v.AntiSpoofError):                     # ตรวจไม่ได้ ≠ ใบหน้าจริง
        v.liveness(np.zeros((200, 200, 3), np.uint8), FACE)
    assert callable(v.detect) and callable(v.feature)


def test_liveness_preprocessing_and_softmax(monkeypatch, model_file):
    v, h = load_vision(monkeypatch, model=model_file)
    ort = h["ort"]
    assert v.ANTISPOOF_READY and not v.ANTISPOOF_ENFORCE      # ค่าเริ่มต้น = log
    sess = ort.sessions[0]
    assert sess.providers == ["CPUExecutionProvider"] and sess.opts.intra_op_num_threads == 1
    assert sess.opts.inter_op_num_threads == 1 and sess.opts.execution_mode == "seq"
    img = np.zeros((240, 320, 3), np.uint8)
    img[:, :] = (10, 20, 200)                                  # B, G, R
    score = v.liveness(img, FACE)
    assert score == pytest.approx(np.exp(2) / (np.exp(2) + 2), rel=1e-6)   # softmax · index 1 = จริง
    blob = ort.inputs[-1]
    assert blob.shape == (1, 3, 80, 80) and blob.dtype == np.float32     # NCHW 80×80
    assert blob[0, 0].max() == 10 and blob[0, 2].max() == 200            # BGR ตามลำดับ · 0–255 ไม่ normalize


def test_liveness_errors_are_not_real(monkeypatch, model_file):
    ort = FakeORT(logits=(np.nan, 1.0, 0.0))
    v, _ = load_vision(monkeypatch, model=model_file, ort=ort)
    img = np.full((240, 320, 3), 128, np.uint8)
    with pytest.raises(v.AntiSpoofError):
        v.liveness(img, FACE)
    ort.logits, ort.run_error = (0.0, 1.0, 0.0), "boom"
    with pytest.raises(v.AntiSpoofError):
        v.liveness(img, FACE)


def test_antispoof_modes(monkeypatch, model_file):
    v, _ = load_vision(monkeypatch, model=model_file, mode="on")
    assert v.ANTISPOOF_READY and v.ANTISPOOF_ENFORCE
    v, _ = load_vision(monkeypatch, mode="on")                 # on แต่ไม่มีไฟล์ = ENFORCE (fail closed)
    assert not v.ANTISPOOF_READY and v.ANTISPOOF_ENFORCE
    v, h = load_vision(monkeypatch, model=model_file, mode="off")
    assert not v.ANTISPOOF_READY and not v.ANTISPOOF_ENFORCE and h["ort"].sessions == []
    v, _ = load_vision(monkeypatch, model=model_file, mode="weird")
    assert v.ANTISPOOF == "log"
    v, _ = load_vision(monkeypatch, model=model_file, ort=FakeORT(in_shape=("b", 3, 112, 112)))
    assert not v.ANTISPOOF_READY and "รูปแบบโมเดลไม่ตรง" in v.ANTISPOOF_ERROR


def test_spoof_crop_stays_inside_image(monkeypatch):
    v, _ = load_vision(monkeypatch)
    img = np.arange(480 * 640 * 3, dtype=np.uint32).reshape(480, 640, 3).astype(np.uint8)
    for box in ([0, 0, 50, 50], [600, 440, 40, 40], [0, 0, 640, 480], [300, 200, 100, 100]):
        face = np.array([*box, *([0] * 10), 0.9], np.float32)
        assert v.spoof_crop(img, face).shape == (80, 80, 3)


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
    """ภาพ = สีเดียว: ช่อง B = index ของ embedding (คนละคน = คนละแกน · 255 = embedding เสีย) · ช่อง G = จำนวนใบหน้า
    · ช่อง R = liveness × 100 (255 = ตรวจไม่ได้)"""
    class ModelError(RuntimeError):
        pass

    class EmbeddingError(ValueError):
        pass

    class AntiSpoofError(RuntimeError):
        pass

    def __init__(self):
        super().__init__("vision")
        self.ANTISPOOF, self.ANTISPOOF_READY, self.ANTISPOOF_ENFORCE = "log", True, False
        self.ANTISPOOF_ERROR, self.ANTISPOOF_MODEL = None, "/models/2.7_80x80_MiniFASNetV2.onnx"
        self.feature_calls = self.liveness_calls = 0

    def set_mode(self, mode, ready=True):
        self.ANTISPOOF, self.ANTISPOOF_READY = mode, ready and mode != "off"
        self.ANTISPOOF_ENFORCE = mode == "on"

    def liveness(self, img, face):
        self.liveness_calls += 1
        r = int(img[0, 0, 2])
        if r == 255 or not self.ANTISPOOF_READY:
            raise self.AntiSpoofError("ตรวจไม่ได้")
        return r / 100

    def detect(self, img):
        return [np.array([10, 10, 150, 150, *([0] * 10), 0.95], np.float32)] * int(img[0, 0, 1])

    def feature(self, img, face):
        self.feature_calls += 1
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


def png(person: int, faces: int = 1, live: float = 0.95) -> bytes:
    img = np.zeros((200, 200, 3), np.uint8)
    img[:, :, 0] = person
    img[:, :, 1] = faces
    img[:, :, 2] = 255 if live is None else int(round(live * 100))
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
        assert it["liveness"] == 0.95                          # คะแนนจริงจาก MiniFASNet (โหมด log)
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


def test_embedding_log_mode_scores_but_never_rejects(api):
    client, fv, _ = api
    r = post(client, png(0, live=0.2), png(0, live=0.3), png(0, live=None))   # ภาพหลอก / ตรวจไม่ได้
    assert r.status_code == 200
    assert [it["liveness"] for it in r.json()["items"]] == [0.2, 0.3, None]
    reg = client.get("/metrics").json()["registration"]["counts"]
    assert reg["spoof_suspect"] == 3 and reg["antispoof_errors"] == 1 and "rejected_spoof" not in reg


def test_embedding_on_mode_rejects_spoof_before_sface(api):
    client, fv, _ = api
    fv.set_mode("on")
    r = post(client, png(0, live=0.95), png(0, live=0.4), png(0, live=0.9))
    assert r.status_code == 422 and r.json()["detail"].startswith("face_2.png: ภาพอาจเป็นรูปถ่ายหรือภาพจากหน้าจอ")
    assert fv.feature_calls == 1                               # ภาพที่ 2 ไม่ถึง SFace
    r = post(client, png(0, live=None))                        # ตรวจไม่ได้ = ไม่ผ่าน (ไม่ถือว่าเป็นหน้าจริง)
    assert r.status_code == 422 and "ตรวจความเป็นใบหน้าจริงไม่ได้" in r.json()["detail"]
    fv.set_mode("on", ready=False)                             # on แต่ไม่มีโมเดล = ปฏิเสธ (fail closed)
    assert post(client, png(0, live=0.99)).status_code == 422
    fv.set_mode("on")
    r = post(client, png(0, live=0.95), png(0, live=0.7))      # เท่ากับเกณฑ์ = ผ่าน
    assert r.status_code == 200 and client.get("/metrics").json()["registration"]["counts"]["rejected_spoof"] == 3


def test_embedding_off_mode_skips_liveness(api):
    client, fv, _ = api
    fv.set_mode("off")
    r = post(client, png(0, live=0.1))
    assert r.status_code == 200 and r.json()["items"][0]["liveness"] is None and fv.liveness_calls == 0


def test_quality_checks(api):
    from fastapi import HTTPException
    # fixture แทน main.check_quality ไว้ → โหลด main.py อีกชุดเพื่อใช้ check_quality ตัวจริง (vision ยังเป็นตัวปลอม)
    spec = importlib.util.spec_from_file_location("main_quality", ROOT / "main.py")
    real = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(real)
    rng = np.random.default_rng(0)
    sharp = rng.integers(0, 256, (300, 300, 3), dtype=np.uint8)
    flat = np.full((300, 300, 3), 128, np.uint8)
    dark = rng.integers(0, 40, (300, 300, 3), dtype=np.uint8)
    bright = rng.integers(236, 256, (300, 300, 3), dtype=np.uint8)

    def face(x, y, w, h, conf):
        return np.array([x, y, w, h, *([0] * 10), conf], np.float32)

    real.check_quality(sharp, face(20, 20, 150, 150, 0.95), "ok.png")      # ผ่าน
    cases = [(sharp, face(20, 20, 150, 150, 0.5), "ความมั่นใจในการตรวจใบหน้าต่ำ"),
             (sharp, face(20, 20, 80, 80, 0.95), "ใบหน้าเล็กเกินไป"),
             (sharp, face(400, 400, 150, 150, 0.95), "ใบหน้าอยู่นอกกรอบภาพ"),
             (flat, face(20, 20, 150, 150, 0.95), "ภาพเบลอ"),
             (dark, face(20, 20, 150, 150, 0.95), "ภาพมืดเกินไป"),
             (bright, face(20, 20, 150, 150, 0.95), "ภาพสว่างเกินไป")]
    for img, f, msg in cases:
        with pytest.raises(HTTPException) as e:
            real.check_quality(img, f, "x.png")
        assert e.value.status_code == 422 and msg in e.value.detail


def test_quality_measure():
    from quality import measure
    img = np.full((100, 100, 3), 128, np.uint8)
    blur, bright = measure(img, np.array([10, 10, 50, 50, *([0] * 10), 0.9], np.float32))
    assert blur == 0 and bright == 128
    assert measure(img, np.array([200, 200, 50, 50, *([0] * 10), 0.9], np.float32)) is None


def test_metrics_endpoint_counts_registration(api):
    client, _, _ = api
    post(client, png(0), png(0), png(0))
    post(client, png(0), png(0), png(9))                      # คนละคน → ปฏิเสธ
    m = client.get("/metrics").json()
    assert set(m) == {"antispoof", "process", "registration", "cameras"}
    assert m["antispoof"] == {"mode": "log", "ready": True, "enforce": False, "minReal": 0.7,
                              "model": "2.7_80x80_MiniFASNetV2.onnx", "error": None}
    assert {"cpuSec", "cpuPercent", "rssMB", "threads", "uptimeSec"} <= set(m["process"])
    reg = m["registration"]
    assert reg["counts"]["registrations"] == 1 and reg["counts"]["rejected_not_same_person"] == 1
    assert reg["ms"]["detect"]["n"] == 6 and reg["ms"]["liveness"]["n"] == 6 and reg["ms"]["feature"]["n"] == 6
    assert m["cameras"] == {}


def test_startup_logs_antispoof_state(api, caplog):
    from fastapi.testclient import TestClient
    _, fv, main = api
    caplog.set_level(logging.INFO)
    with TestClient(main.app):
        pass
    assert "anti-spoofing: พร้อม · โหมด log" in caplog.text and "ตัวอ่านกล้องปิดอยู่" in caplog.text
    caplog.clear()
    fv.set_mode("on", ready=False)
    fv.ANTISPOOF_ERROR = "ไม่พบโมเดล x"
    with TestClient(main.app):
        pass
    assert "anti-spoofing: ใช้ไม่ได้ (ไม่พบโมเดล x) — โหมด on: ปฏิเสธทุกภาพ" in caplog.text


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
    frame = np.full((100, 100, 3), 128, np.uint8)
    w.process(frame, now=100)
    w.process(frame, now=101.5)
    s = w.metrics.summary()
    assert s["counts"]["frames_processed"] == 2 and s["counts"]["faces_detected"] == 4
    assert s["counts"]["faces_matched"] == 2
    assert s["ms"]["feature"]["n"] == 4 and s["ms"]["frame"]["n"] == 2 and s["ms"]["match"]["n"] == 2
    assert s["ms"]["sighting"]["n"] == 1 and s["ms"]["sighting"]["p50"] >= 1500      # เห็นครั้งแรก t=100 → ส่ง t=101.5


# ---------------- camera_worker.py: คุณภาพ + anti-spoofing ก่อน SFace ----------------

def cam_setup(mode="log", lives=(0.9,), faces=None):
    """กล้อง 1 ตัว · นักศึกษา 1 คน (แกน 0) · liveness คืนค่าตามลำดับใน lives (None = ตรวจไม่ได้)"""
    from test_camera_worker import KEY, FakeBackend, FakeVision as CamVision, face_row, make_worker, payload
    from gallery import GalleryStore, load_key
    import test_camera_worker as tcw

    class V(CamVision):
        class AntiSpoofError(RuntimeError):
            pass

        def __init__(self, faces):
            super().__init__(faces)
            self.ANTISPOOF, self.ANTISPOOF_ENFORCE = mode, mode == "on"
            self.lives, self.feature_calls, self.liveness_calls = list(lives), 0, 0

        def liveness(self, frame, face):
            self.liveness_calls += 1
            v = self.lives.pop(0) if len(self.lives) > 1 else self.lives[0]
            if v is None:
                raise self.AntiSpoofError("boom")
            return v

        def feature(self, frame, face):
            self.feature_calls += 1
            return super().feature(frame, face)

    a = str(uuid.uuid4())
    store = GalleryStore(load_key(KEY))
    store.update(tcw.ROOM, payload(students={a: [tcw.axis(0)]}))
    v = V(faces if faces is not None else [face_row(0)])
    backend = FakeBackend()
    return make_worker(v, backend, store), v, backend, a


GRAY = np.full((100, 100, 3), 128, np.uint8)


def test_camera_low_quality_face_skips_antispoof_and_sface():
    w, v, backend, _ = cam_setup()
    w.process(np.full((100, 100, 3), 10, np.uint8), now=100)     # มืดเกิน
    w.process(np.full((100, 100, 3), 250, np.uint8), now=101)    # สว่างเกิน
    assert v.liveness_calls == 0 and v.feature_calls == 0 and backend.sent("/sightings") == []
    assert w.metrics.summary()["counts"]["faces_low_quality"] == 2


def test_camera_log_mode_sends_but_flags_low_liveness(caplog):
    caplog.set_level(logging.INFO, logger="camera")
    w, v, backend, a = cam_setup(mode="log", lives=(0.2,))
    w.process(GRAY, now=100)
    w.process(GRAY, now=101)
    s = backend.sent("/sightings")
    assert len(s) == 1 and s[0]["studentId"] == a and set(s[0]) == {"studentId", "matchScore", "seenAt"}
    c = w.metrics.summary()["counts"]
    assert c["faces_liveness_checked"] == 2 and c["faces_spoof_suspect"] == 2 and c["sightings_low_liveness"] == 1
    assert "faces_rejected_spoof" not in c and "liveness เฉลี่ย 0.20 (2/2 เฟรม) ⚠" in caplog.text


def test_camera_on_mode_rejects_spoof_before_sface():
    w, v, backend, _ = cam_setup(mode="on", lives=(0.2,))
    for t in range(100, 105):
        w.process(GRAY, now=t)
    assert v.feature_calls == 0 and backend.sent("/sightings") == []
    assert w.metrics.summary()["counts"]["faces_rejected_spoof"] == 5


def test_camera_on_mode_needs_every_counted_frame_real():
    w, v, backend, a = cam_setup(mode="on", lives=(0.9, 0.3, 0.95, 0.9))
    w.process(GRAY, now=100)          # จริง → hit 1
    w.process(GRAY, now=101)          # หลอก → ไม่ถึง SFace · ไม่นับ
    assert backend.sent("/sightings") == []
    w.process(GRAY, now=102)          # จริง → hit 2 → ส่ง
    s = backend.sent("/sightings")
    assert len(s) == 1 and s[0]["studentId"] == a and v.feature_calls == 2


def test_camera_on_mode_inference_error_is_not_real():
    w, v, backend, _ = cam_setup(mode="on", lives=(None,))
    for t in range(100, 104):
        w.process(GRAY, now=t)
    c = w.metrics.summary()["counts"]
    assert backend.sent("/sightings") == [] and v.feature_calls == 0
    assert c["antispoof_errors"] == 4 and c["faces_rejected_spoof"] == 4


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
    make(tmp_path / "spoof", "phone", "0_live80_f.jpg")        # หน้าจอผ่านเกณฑ์ 0.7

    class V:
        ANTISPOOF_READY = True

        class AntiSpoofError(RuntimeError):
            pass

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
    assert "| ANTISPOOF_MIN_REAL | BPCER | APCER | APCER phone | APCER print |" in report
    assert "| 0.70 | 0.0% (0/10) | 50.0% (1/2) | 100.0% (1/1) | 0.0% (0/1) |" in report
    assert "SFace embedding (ต่อใบหน้า) | 12 |" in report
