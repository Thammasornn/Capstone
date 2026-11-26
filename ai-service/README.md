# ai-service

FastAPI · YOLOv8 face-pose + SFace · ลงทะเบียนใบหน้า + อ่านกล้อง realtime

| ไฟล์ | ทำอะไร |
|---|---|
| [`main.py`](main.py) | API (FastAPI) + เปิดตัวอ่านกล้องตอน start |
| [`vision.py`](vision.py) | โมเดล YOLO face-pose + SFace (ใช้ร่วมกัน · เรียกทีละงาน) |
| [`gallery.py`](gallery.py) | ถอดรหัส embedding จาก backend (AES-GCM) + จับคู่ cosine แยกตามห้อง |
| [`camera_worker.py`](camera_worker.py) | อ่าน RTSP จาก MediaMTX → จับคู่ → ส่ง heartbeat / faces / sightings ไป backend |
| [`tests/`](tests) | ทดสอบ gallery / ตัวอ่านกล้องโดยไม่ต้องมีโมเดล |

| เส้น | ใช้ทำอะไร |
|---|---|
| `GET /health` | ตรวจว่าพร้อม |
| `POST /embedding` (multipart `files`) | ตรวจภาพ + คืน embedding 128 มิติ ต่อภาพ · ภาพไม่ผ่าน = 422 `{detail}` |
| `DELETE /students/{student_id}/embeddings` | backend เรียกหลังนักศึกษาถอนความยินยอม (B7) → ลบ embedding ของคนนั้นออกจากหน่วยความจำ (ทุกห้อง) · ต้องแนบ `X-Internal-Key` |
| `GET /cameras/status` | สถานะตัวอ่านกล้อง: สัญญาณ, อายุเฟรมล่าสุด, มีคาบไหม, จำนวนคนใน gallery, จำนวนใบหน้า, sightings ที่ส่ง, error ล่าสุด (ไม่มีชื่อ / embedding) |

## ตั้งค่า

| ตัวแปร | ใช้ทำอะไร |
|---|---|
| `INTERNAL_API_KEY` | ต้องตรงกับ `INTERNAL_API_KEY` ของ backend · ไม่ตั้ง = ปฏิเสธ `DELETE /students/...` ทุกครั้ง |
| `YOLO_FACE_MODEL`, `SFACE_MODEL` | path ของโมเดล |
| `MIN_CONF`, `MIN_FACE_PX`, `MIN_BLUR` | เกณฑ์คุณภาพภาพตอนลงทะเบียน |
| `CAMERA_WORKER` | `true` = เปิดตัวอ่านกล้อง (K3s เปิดไว้) · ค่าเริ่มต้น `false` (รันในเครื่องไม่ต้องมี MediaMTX) |
| `FACE_EMBEDDING_KEY` | ค่าเดียวกับ backend · ใช้ถอดรหัส embedding · ไม่มี = ไม่เปิดตัวอ่านกล้อง |
| `BACKEND_URL`, `MEDIAMTX_RTSP_URL` | `http://backend:8081`, `rtsp://mediamtx.media.svc.cluster.local:8554` (ภาพ = `<MEDIAMTX_RTSP_URL>/<streamPath>`) |
| `MATCH_THRESHOLD` (0.363), `MATCH_MARGIN` (0.05) | cosine ขั้นต่ำ (ค่าแนะนำของ SFace) และต้องห่างคนที่ 2 เท่าไร |
| `MIN_HITS` (2), `HIT_WINDOW` (10), `SIGHTING_COOLDOWN` (60) | ต้องเห็นซ้ำกี่ครั้งในกี่วินาทีจึงส่ง · คนเดิมส่งซ้ำได้ทุกกี่วินาที |
| `FRAME_INTERVAL` (1), `CAMERA_MIN_CONF` (0.6), `CAMERA_MIN_FACE_PX` (40) | ตรวจภาพทุกกี่วินาที · เกณฑ์ใบหน้าจากกล้อง |
| `FACES_INTERVAL` (5), `HEARTBEAT_INTERVAL` (30), `SIGNAL_TIMEOUT` (10), `CAMERAS_REFRESH` (60), `GALLERY_REFRESH` (30) | รอบส่งจำนวนใบหน้า / heartbeat · ไม่มีเฟรมกี่วินาที = `no_signal` · รอบโหลดรายชื่อกล้อง / gallery |

## ตัวอ่านกล้อง

```
backend GET /api/internal/cameras ──▶ 1 thread ต่อกล้อง: rtsp://…/<streamPath> (TCP)
backend GET /api/internal/rooms/{id}/gallery (ทุก 30 วินาที) ──▶ embedding ของคาบในห้องตอนนี้ (ถอดรหัสในหน่วยความจำ)
เฟรมล่าสุดทุก 1 วินาที (เฉพาะตอนมีคาบ) → YOLO → SFace → cosine → เห็นซ้ำ 2 ครั้งใน 10 วินาที → POST sightings
ทุก 5 วินาที: POST faces (จำนวนใบหน้า) · heartbeat live / no_signal เมื่อเปลี่ยน หรือทุก 30 วินาที
```

- กติกาเช็คชื่อ (ตรงเวลา / สาย / ปิดรับ / อาจารย์แก้มือแล้ว) อยู่ที่ backend ทั้งหมด · `seenAt` = เวลาที่เห็นครั้งแรกในช่วงที่นับ
- ไม่เก็บภาพ · ไม่ log embedding / ชื่อ · ถอนความยินยอม → `DELETE /students/{id}/embeddings` ลบทันที + รอบถัดไป backend ก็ไม่ส่งมาแล้ว
- embedding ใน `02_seed_mock.sql` เป็นไบต์สุ่ม → ถอดไม่ได้ ถูกข้าม (log WARN) · จับคู่ได้เฉพาะคนที่ลงทะเบียนใบหน้าจริงผ่านหน้าเว็บ
- ดูสถานะใน K3s: `kubectl -n uat exec deploy/ai-service -- python -c "import urllib.request as u;print(u.urlopen('http://localhost:8080/cameras/status').read().decode())"`

## ทดสอบ (ไม่ต้องมีโมเดล)

```bash
cd ai-service
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest tests
```

## บันทึกการทำงาน (Changelog)

### 2026-11-26 — วัดเวลาจริง (/metrics) + เครื่องมือวัดความแม่นยำ + anti-spoofing ตอนลงทะเบียน (Step 1)
- **ทำอะไร:** `metrics.py` จับเวลาทุกขั้น (retrieve / detect / feature / match / frame / sighting = เห็นครั้งแรก → ส่งเสร็จ) p50/p95 → `GET /metrics` + log สรุปทุก `METRICS_LOG_INTERVAL` (60) วินาที · `/embedding` ตรวจ MiniFASNet V2+V1SE (`ANTISPOOF` on/log/off, `ANTISPOOF_MIN_REAL` 0.7 → 422 "ภาพอาจเป็นรูปถ่ายหรือภาพจากหน้าจอ") + ทุกรูปต้องเป็นคนเดียวกัน (`SAME_PERSON_MIN` 0.363 → 422) · response เพิ่ม `items[].liveness` (ตรวจแล้ว backend Jackson 3 เพิกเฉย field ใหม่) · ไม่มีไฟล์โมเดล = ปิด anti-spoofing เอง (WARN, pod ยังขึ้น) · `tools/eval_recognition.py` วัด TAR/FAR/accuracy/rank-1/APCER/BPCER + เวลาต่อใบหน้า พิมพ์ตาราง markdown
- **ไฟล์ที่เกี่ยวข้อง:** `metrics.py`, `vision.py`, `main.py`, `camera_worker.py`, `tools/eval_recognition.py`, `tests/test_embedding.py`, `requirements-dev.txt`, `devops/models/fetch-models.sh`, `devops/k8s/base/ai-service.yaml`
- **เหตุผล:** กรรมการต้องการตัวเลขความแม่นยำ / ความเร็วจริง · กันการลงทะเบียนด้วยรูปถ่ายหรือรูปคนอื่น
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** pytest 23 ข้อผ่าน · SHA-256 ของ MiniFASNet ตรวจกับไฟล์จริงแล้ว · cv2.dnn โหลดได้ (input 80×80, ~2–3 ms/หน้าบน Mac) · **ยังไม่ได้วัดความแม่นยำ anti-spoofing กับหน้าคนจริง** — ถ้าคนจริงโดนปฏิเสธบน uat ตั้ง `ANTISPOOF=log` · วิธีวัด: เก็บภาพนอก repo (ห้าม commit · PDPA) เป็น `enroll/<p01>/`, `probe/<p01>/` (ภาพจากกล้องห้อง 4303), `spoof/` แล้วรัน `python tools/eval_recognition.py --enroll … --probe … --spoof … --models /opt/bangmod/models --out report.md` · ยังไม่ได้เขียนคู่มือเก็บข้อมูลฉบับเต็ม / อัปเดต devops/README.md และ root README

### 2026-11-25 — `/cameras/status` ล้าง error หลังเปิดภาพได้
- **ทำอะไร:** `CameraWorker` ตั้ง `last_error = None` เมื่อเปิด RTSP สำเร็จ · test reconnect ไม่พึ่งจังหวะเวลา + เพิ่ม test ว่า error ถูกล้างเมื่อเปิดภาพได้ (pytest 11 ข้อผ่าน)
- **ไฟล์ที่เกี่ยวข้อง:** `camera_worker.py`, `tests/test_camera_worker.py`
- **เหตุผล:** บน uat สถานะเป็น `live` แต่ `lastError` ยังค้างข้อความ "เปิด … ไม่ได้" จากตอน MediaMTX ยังไม่มีภาพ ทำให้อ่านสถานะผิด

### 2026-11-25 — ตัวอ่านกล้อง realtime (MediaMTX → จับคู่ใบหน้า → เช็คชื่อ)
- **ทำอะไร:** แยกโมเดลไป `vision.py` (มี lock ใช้ร่วมกับ `/embedding`) · `gallery.py` ถอดรหัส embedding จาก backend (AES-256-GCM รูปแบบเดียวกับ `EmbeddingCipher.java`, AAD = student_id) + `best_matches` (cosine สูงสุดของแต่ละคน, threshold + margin) · `camera_worker.py`: `Supervisor` โหลดรายชื่อกล้อง / gallery จาก backend, ส่ง heartbeat; `CameraWorker` 1 thread ต่อกล้อง อ่าน RTSP (TCP, timeout 5 วินาที, ต่อใหม่เอง) ตรวจเฉพาะตอนมีคาบ ส่ง faces / sightings · `main.py` เปิดตัวอ่านตอน start เมื่อ `CAMERA_WORKER=true`, เพิ่ม `GET /cameras/status`, `DELETE /students/{id}/embeddings` ลบจากทุกห้อง · `requirements.txt` เพิ่ม `cryptography` · `Dockerfile` copy ทุก `*.py` · เพิ่ม `tests/` + `requirements-dev.txt`
- **ไฟล์ที่เกี่ยวข้อง:** `main.py`, `vision.py`, `gallery.py`, `camera_worker.py`, `tests/test_camera_worker.py`, `requirements.txt`, `requirements-dev.txt`, `Dockerfile`
- **เหตุผล:** ภาพจากกล้อง Pi มาถึง MediaMTX แล้ว แต่ยังไม่มีอะไรอ่านไปเช็คชื่อ (ป้ายกล้องออนไลน์ก็ยังเป็นข้อมูล mock)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** `pytest` 10 ข้อผ่าน (vision ปลอม) · Python ถอด embedding ที่ Java เข้ารหัสได้ · ทดสอบครบสายกับ backend จริง (Postgres ชั่วคราว + คาบ demo ห้อง 4302) + ภาพจริงผ่าน `cv2.VideoCapture` (ไฟล์วิดีโอ เพราะ ffmpeg ในเครื่องเป็น RTSP server ไม่ได้) + โมเดลปลอม: ได้ `checked_in (on_time)` ใน `attendance_records` (`source = camera`, `match_score = 1.0000`), คนแปลกหน้าไม่ถูกจับคู่, CAM-4302 `live` ส่วนกล้องที่ไม่มีภาพ `no_signal` · **ยังไม่ได้ทดสอบกับโมเดลจริงและ RTSP จริงจาก MediaMTX** (ทำบน uat) · ยังไม่ได้ build image จริง (เครื่องที่แก้ไม่มี docker)

### 2026-11-21 — Dockerfile
- **ทำอะไร:** เพิ่ม `Dockerfile` (python 3.12-slim + lib ของ OpenCV, uvicorn port 8080) · โมเดล mount เข้า `/models` ตอนรัน
- **ไฟล์ที่เกี่ยวข้อง:** `Dockerfile`
- **เหตุผล:** deploy บน K3s (`devops/k8s/base/ai-service.yaml`)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ยังไม่ได้ build image จริง

### 2026-11-21 — endpoint ลบ embedding ตอนถอนความยินยอม
- **ทำอะไร:** เพิ่ม `DELETE /students/{student_id}/embeddings` (ตรวจ `X-Internal-Key` = `INTERNAL_API_KEY`) และ `GALLERY` ไว้เก็บ embedding สำหรับจับคู่ใบหน้าจากกล้อง
- **ไฟล์ที่เกี่ยวข้อง:** `main.py`, `README.md`
- **เหตุผล:** B7 — ถอนความยินยอมแล้วต้องลบข้อมูลใบหน้าออกจาก AI Service ด้วย ไม่ใช่แค่ใน DB
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ตัวจับคู่ใบหน้า realtime ยังไม่ได้ทำ เมื่อทำให้โหลด embedding เข้า `GALLERY` และส่งผลไปที่ `/api/internal/**` ของ backend
