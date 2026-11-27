# ai-service

FastAPI · YOLOv8 face-pose + MiniFASNetV2 (anti-spoofing · ONNX Runtime CPU) + SFace · ลงทะเบียนใบหน้า + อ่านกล้อง realtime

| ไฟล์ | ทำอะไร |
|---|---|
| [`main.py`](main.py) | API (FastAPI) + เปิดตัวอ่านกล้องตอน start |
| [`vision.py`](vision.py) | โมเดล YOLO face-pose + SFace (ใช้ร่วมกัน · เรียกทีละงาน) · embedding เสีย (ว่าง / ไม่ใช่ 128 / 0 / NaN) = `EmbeddingError` · MiniFASNetV2 `liveness()` (ตรวจไม่ได้ = `AntiSpoofError`) |
| [`quality.py`](quality.py) | คุณภาพกรอบหน้า: เบลอ (variance of Laplacian) + ความสว่าง (ใช้ร่วมกันระหว่างลงทะเบียน / กล้อง) |
| [`gallery.py`](gallery.py) | ถอดรหัส embedding จาก backend (AES-GCM) + จับคู่ cosine แยกตามห้อง |
| [`camera_worker.py`](camera_worker.py) | อ่าน RTSP จาก MediaMTX → จับคู่ → ส่ง heartbeat / faces / sightings ไป backend |
| [`metrics.py`](metrics.py) | จับเวลาแต่ละขั้น (p50 / p95) → `GET /metrics` + log สรุป |
| [`tools/eval_recognition.py`](tools/eval_recognition.py) | วัดความแม่นยำ + ความเร็วจากภาพจริง → ตาราง markdown (ดู "วัดผลด้วยข้อมูลจริง") |
| [`tests/`](tests) | ทดสอบ gallery / ตัวอ่านกล้อง / `/embedding` / metrics / เครื่องมือวัดผล โดยไม่ต้องมีโมเดล |

| เส้น | ใช้ทำอะไร |
|---|---|
| `GET /health` | ตรวจว่าพร้อม |
| `POST /embedding` (multipart `files`) | ตรวจภาพ (1 ใบหน้า → คุณภาพ → anti-spoofing → SFace → ทุกรูปเป็นคนเดียวกัน) + คืน `{model, model_version, items: [{embedding (128), quality, liveness}]}` · `liveness` = คะแนน 0–1 หรือ `null` (ปิด / ตรวจไม่ได้) · backend อ่านแค่ `embedding`, `quality` · ภาพไม่ผ่าน = 422 `{detail}` |
| `DELETE /students/{student_id}/embeddings` | backend เรียกหลังนักศึกษาถอนความยินยอม (B7) → ลบ embedding ของคนนั้นออกจากหน่วยความจำ (ทุกห้อง) · ต้องแนบ `X-Internal-Key` |
| `GET /metrics` | เวลาจริงต่อขั้น (`n` / `mean` / `p50` / `p95` / `max` ms) ของ `/embedding` และแต่ละกล้อง + สถานะ anti-spoofing + CPU / RAM ของ process · ใน cluster เท่านั้น (ไม่มี Ingress) |
| `GET /cameras/status` | สถานะตัวอ่านกล้อง: สัญญาณ, อายุเฟรมล่าสุด, มีคาบไหม, จำนวนคนใน gallery, จำนวนใบหน้า, sightings ที่ส่ง, error ล่าสุด (ไม่มีชื่อ / embedding) |

## ตั้งค่า

| ตัวแปร | ใช้ทำอะไร |
|---|---|
| `INTERNAL_API_KEY` | ต้องตรงกับ `INTERNAL_API_KEY` ของ backend · ไม่ตั้ง = ปฏิเสธ `DELETE /students/...` ทุกครั้ง |
| `YOLO_FACE_MODEL`, `SFACE_MODEL` | path ของโมเดล |
| `MIN_CONF` (0.8), `MIN_FACE_PX` (112), `MIN_BLUR` (60), `MIN_BRIGHTNESS` (40), `MAX_BRIGHTNESS` (230) | เกณฑ์คุณภาพภาพตอนลงทะเบียน (ความสว่าง = ค่าเฉลี่ยเทา 0–255 ของกรอบหน้า) |
| `ANTISPOOF` (`log`) | `log` = คำนวณ + บันทึกคะแนน ไม่ปฏิเสธ (ค่าเริ่มต้น · ใช้เก็บคะแนนจริงก่อน) · `on` = ปฏิเสธ (คะแนนต่ำ **หรือตรวจไม่ได้** = ไม่ผ่าน · ไม่มีโมเดล = ปฏิเสธทุกภาพ) · `off` = ไม่โหลด ไม่คำนวณ |
| `ANTISPOOF_MIN_REAL` (0.7) | โอกาสเป็นใบหน้าจริงขั้นต่ำ (softmax class 1 ของ MiniFASNetV2) · ใช้ทั้งลงทะเบียนและกล้อง |
| `ANTISPOOF_MODEL` | `/models/2.7_80x80_MiniFASNetV2.onnx` |
| `ANTISPOOF_THREADS` (1), `TORCH_THREADS` (ไม่ตั้ง) | thread ของ ONNX Runtime (1 = ไม่แย่ง CPU กับ YOLO) · thread ของ torch/YOLO (ไม่ตั้ง = ค่าเริ่มต้นของ torch) |
| `SAME_PERSON_MIN` (0.363) | cosine ขั้นต่ำระหว่างทุกคู่รูปที่ส่งมาลงทะเบียน (ต่ำกว่า = ไม่ใช่คนเดียวกัน → 422) |
| `CAMERA_WORKER` | `true` = เปิดตัวอ่านกล้อง (K3s เปิดไว้) · ค่าเริ่มต้น `false` (รันในเครื่องไม่ต้องมี MediaMTX) |
| `FACE_EMBEDDING_KEY` | ค่าเดียวกับ backend · ใช้ถอดรหัส embedding · ไม่มี = ไม่เปิดตัวอ่านกล้อง |
| `BACKEND_URL`, `MEDIAMTX_RTSP_URL` | `http://backend:8081`, `rtsp://mediamtx.media.svc.cluster.local:8554` (ภาพ = `<MEDIAMTX_RTSP_URL>/<streamPath>`) |
| `MATCH_THRESHOLD` (0.363), `MATCH_MARGIN` (0.05) | cosine ขั้นต่ำ (ค่าแนะนำของ SFace) และต้องห่างคนที่ 2 เท่าไร |
| `MIN_HITS` (2), `HIT_WINDOW` (10), `SIGHTING_COOLDOWN` (60) | ต้องเห็นซ้ำกี่ครั้งในกี่วินาทีจึงส่ง · คนเดิมส่งซ้ำได้ทุกกี่วินาที |
| `FRAME_INTERVAL` (1), `CAMERA_MIN_CONF` (0.6), `CAMERA_MIN_FACE_PX` (40) | ตรวจภาพทุกกี่วินาที · เกณฑ์ใบหน้าจากกล้อง |
| `CAMERA_MIN_BRIGHTNESS` (30), `CAMERA_MAX_BRIGHTNESS` (235), `CAMERA_MIN_BLUR` (0 = ไม่ตรวจ) | คุณภาพกรอบหน้าจากกล้อง (ไม่ผ่าน = ไม่รัน MiniFASNet / SFace) · เบลอยังไม่ตรวจ: หน้าเล็กจากกล้องห้องค่าต่ำเป็นปกติ ต้องดูข้อมูลจริงก่อนตั้ง |
| `FACES_INTERVAL` (5), `HEARTBEAT_INTERVAL` (30), `SIGNAL_TIMEOUT` (10), `CAMERAS_REFRESH` (60), `GALLERY_REFRESH` (30) | รอบส่งจำนวนใบหน้า / heartbeat · ไม่มีเฟรมกี่วินาที = `no_signal` · รอบโหลดรายชื่อกล้อง / gallery |
| `METRICS_LOG_INTERVAL` (60) | log สรุปเวลาทุกกี่วินาที (`0` = ไม่ log) |

## ตัวอ่านกล้อง

```
backend GET /api/internal/cameras ──▶ 1 thread ต่อกล้อง: rtsp://…/<streamPath> (TCP)
backend GET /api/internal/rooms/{id}/gallery (ทุก 30 วินาที) ──▶ embedding ของคาบในห้องตอนนี้ (ถอดรหัสในหน่วยความจำ)
เฟรมล่าสุดทุก 1 วินาที (เฉพาะตอนมีคาบ · เฟรมระหว่างรอบ grab ทิ้งไม่ decode) → YOLO → คุณภาพ → MiniFASNetV2 → SFace → cosine
  → เห็นซ้ำ 2 ครั้งใน 10 วินาที (ทุกเฟรมที่นับต้องผ่าน anti-spoofing ในโหมด on) → POST sightings
ทุก 5 วินาที: POST faces (จำนวนใบหน้า) · heartbeat live / no_signal เมื่อเปลี่ยน หรือทุก 30 วินาที
```

- กติกาเช็คชื่อ (ตรงเวลา / สาย / ปิดรับ / อาจารย์แก้มือแล้ว) อยู่ที่ backend ทั้งหมด · `seenAt` = เวลาที่เห็นครั้งแรกในช่วงที่นับ
- ไม่เก็บภาพ · ไม่ log embedding / ชื่อ · ถอนความยินยอม → `DELETE /students/{id}/embeddings` ลบทันที + รอบถัดไป backend ก็ไม่ส่งมาแล้ว
- embedding ใน `02_seed_mock.sql` เป็นไบต์สุ่ม → ถอดไม่ได้ ถูกข้าม (log WARN) · จับคู่ได้เฉพาะคนที่ลงทะเบียนใบหน้าจริงผ่านหน้าเว็บ
- anti-spoofing (`ANTISPOOF`): โหมด `on` ใบหน้าที่คะแนน < `ANTISPOOF_MIN_REAL` หรือตรวจไม่ได้ **ไม่ถูกส่งเข้า SFace** (ไม่นับเป็น hit) · โหมด `log` ส่ง sighting ตามปกติ แต่ log `liveness เฉลี่ย 0.xx (n/n เฟรม) ⚠ ต่ำกว่าเกณฑ์` + นับ `sightings_low_liveness` = จำนวนครั้งที่ "ถ้าเปิด on จะไม่ได้เช็คชื่อ" · body ที่ส่ง backend ไม่เปลี่ยน
- ไม่มี face tracking: ตรวจ 1 เฟรม/วินาที หน้าขยับไกลเกินกว่าจะ track ระหว่างเฟรมได้แม่น และยังไม่มีข้อมูลว่า YOLO เป็นคอขวด — ตัดสินจาก `/metrics` (ดู `detect` p50 กับ `process.cpuPercent`)
- ดูสถานะใน K3s: `kubectl -n uat exec deploy/ai-service -- python -c "import urllib.request as u;print(u.urlopen('http://localhost:8080/cameras/status').read().decode())"`

## ดูเวลาจริงบน uat (`/metrics`)

ai-service ไม่มี Ingress → เปิดจากใน pod หรือ port-forward เท่านั้น (ไม่ต้อง sudo)

```bash
kubectl -n uat exec deploy/ai-service -- python -c "import urllib.request as u;print(u.urlopen('http://localhost:8080/metrics').read().decode())" | python3 -m json.tool
```
```bash
kubectl -n uat logs deploy/ai-service --since=1h | grep metrics      # สรุปทุก 60 วินาที ตอนมีคาบ
```

| ค่าใน `/metrics` | ความหมาย |
|---|---|
| `cameras.<รหัสกล้อง>.ms.frame` | เวลาทั้งเฟรม (ดึงภาพ + YOLO + SFace ทุกหน้า + จับคู่) |
| `….ms.detect` / `….ms.feature` / `….ms.match` | YOLO ต่อเฟรม / SFace **ต่อ 1 ใบหน้า** / cosine ต่อเฟรม |
| `….ms.sighting` | เห็นหน้าครั้งแรก → backend ตอบ sighting แล้ว (รวมรอเห็นซ้ำ `MIN_HITS` ครั้ง ≈ `FRAME_INTERVAL` วินาที) |
| `….ratePerSec.frames_processed` / `frames_grabbed` | เฟรมที่ตรวจ / เฟรมที่สตรีมส่งมา ต่อวินาที |
| `….counts.faces_detected` / `faces_matched` | จำนวนใบหน้าที่เจอ / จับคู่ได้ทั้งหมด |
| `….ms.liveness` · `….counts.faces_liveness_checked` | MiniFASNetV2 ต่อ 1 ใบหน้า · จำนวนครั้งที่รัน (เทียบ `frames_processed` = YOLO, `faces_embedded` = SFace) |
| `….counts.faces_low_quality` / `faces_spoof_suspect` / `faces_rejected_spoof` / `antispoof_errors` | ไม่ผ่านคุณภาพ / คะแนนต่ำกว่าเกณฑ์ (ทุกโหมด) / ถูกตัดก่อน SFace (โหมด on) / ตรวจไม่ได้ |
| `….counts.sightings_low_liveness` | sighting ที่คะแนนเฉลี่ยต่ำกว่าเกณฑ์ (โหมด log = ถ้าเปิด on จะไม่ส่ง) |
| `….counts.frames_dropped` | เฟรมที่ grab แล้วทิ้ง (ไม่ decode) ระหว่างรอบ `FRAME_INTERVAL` |
| `registration.ms.decode/detect/liveness/feature` | ต่อ 1 ภาพของ `POST /embedding` · `counts.registrations`, `images`, `spoof_suspect`, `rejected_spoof`, `antispoof_errors`, `rejected_not_same_person` |
| `antispoof` | `mode` / `ready` / `enforce` / `minReal` / `model` / `error` |
| `process` | `cpuPercent` (เฉลี่ยตั้งแต่ start · 100 = 1 core เต็ม) / `cpuSec` / `rssMB` / `threads` — ใช้เทียบก่อน–หลัง (`kubectl -n uat top pod -l app=ai-service` ก็ได้) |

เวลาต่อ 1 ใบหน้า (ตอบกรรมการ) ≈ `detect.p50 ÷ (faces_detected ÷ frames_processed)` + `feature.p50` · ค่าเก็บ 2000 ค่าล่าสุดตั้งแต่ pod start (restart = เริ่มใหม่)

## วัดผลด้วยข้อมูลจริง (ห้อง 4303 · ก่อนนำเสนอ)

> **PDPA: ภาพจริงห้าม commit / ห้ามส่งในแชท / ห้ามใส่สไลด์** (ยกเว้นสมาชิกทีมที่ยินยอม) · ผู้เข้าร่วมต้องยินยอมเป็นลายลักษณ์อักษรว่าใช้ภาพเพื่อวัดผลโครงงาน · เก็บในโฟลเดอร์ทีมนอก repo · ชื่อโฟลเดอร์ใช้รหัส `p01`, `p02` … ไม่ใช้ชื่อจริง · ลบภาพเมื่อได้ตัวเลขแล้ว

### 1. เก็บข้อมูล (~1–1.5 ชั่วโมง)

| ชุด | ใคร | ทำอย่างไร | จำนวน |
|---|---|---|---|
| `enroll/pXX/` | คนที่ "ลงทะเบียน" ≥ 8 คน | ถ่ายแบบหน้าเว็บ (หน้าตรง / หันซ้าย / หันขวา) ด้วยมือถือหรือเว็บแคม ระยะ ~40 ซม. แสงในห้องปกติ | 3–5 รูป / คน |
| `probe/pXX/` | คนเดียวกับ enroll | ภาพจากกล้อง Pi ห้อง 4303 · **ทีละคน** (คนอื่นออกนอกภาพ → ติดป้ายง่าย ไม่ต้องครอป) · ตำแหน่ง: แถวหน้า / กลาง / หลัง · แสง: ไฟปกติ / ไฟปิดครึ่งหรือม่านเปิดย้อนแสง · ท่า: มองกล้อง / ก้มจดหรือมองจอ / หันข้าง ~30° · (แว่น / แมสก์ เป็นชุดแยกถ้ามี) | 5 เฟรม / ตำแหน่ง / แสง / คน (≈ 30–60 / คน) |
| `probe/xXX/` | "คนแปลกหน้า" ≥ 2 คน (ไม่อยู่ใน enroll) | เหมือน probe | ≈ 30 / คน |
| `spoof/<ชนิด>/` (`print`, `phone`, `screen`, `video`) | ภาพของคนใน enroll | รูปพิมพ์ A4 สี / รูปบนมือถือ / รูปบนแท็บเล็ตหรือ notebook / วิดีโอบนมือถือ · ถ่ายด้วยมือถือแบบตอนลงทะเบียน (ระยะ ~40 ซม.) **และ** ยกหน้ากล้อง Pi (แถวหน้า) · ชื่อโฟลเดอร์ = ชนิด (รายงานแยก APCER ต่อชนิด) | ≥ 10 / ชนิด |

ดึงเฟรมจากกล้อง Pi (ระหว่างมีภาพเข้า MediaMTX · `streamPath` ดูจาก `/cameras/status`) — บันทึกใน pod แล้ว copy ออก:

```bash
kubectl -n uat exec -i deploy/ai-service -- python - room-4303 p01_front_light 5 <<'EOF'
import os, sys, time, cv2
path, label, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
os.makedirs("/tmp/frames", exist_ok=True)
cap = cv2.VideoCapture(f"{os.environ['MEDIAMTX_RTSP_URL']}/{path}", cv2.CAP_FFMPEG)
for i in range(n):
    for _ in range(15): cap.grab()          # ทิ้งเฟรมเก่าใน buffer
    ok, f = cap.retrieve()
    if ok: cv2.imwrite(f"/tmp/frames/{label}_{i:02d}.jpg", f)
    time.sleep(1)
print(sorted(os.listdir("/tmp/frames")))
EOF
```
```bash
POD=$(kubectl -n uat get pod -l app=ai-service -o jsonpath='{.items[0].metadata.name}')
kubectl -n uat cp "$POD:/tmp/frames" ~/eval/frames && kubectl -n uat exec "$POD" -- rm -rf /tmp/frames
```
(`room-4303` = streamPath จริงของกล้อง · แยกไฟล์ลง `probe/pXX/` ตามป้ายชื่อ)

### 2. รันการวัด (บน VM ใน pod — ได้ความเร็วของ CPU จริง · ทำตอนไม่มีคาบ กล้องจะไม่แย่ง CPU)

```bash
POD=$(kubectl -n uat get pod -l app=ai-service -o jsonpath='{.items[0].metadata.name}')
kubectl -n uat cp ~/eval "$POD:/tmp/eval"
kubectl -n uat exec "$POD" -- python tools/eval_recognition.py --models /models \
  --enroll /tmp/eval/enroll --probe /tmp/eval/probe --spoof /tmp/eval/spoof --out /tmp/eval/report.md
kubectl -n uat cp "$POD:/tmp/eval/report.md" ~/eval/report.md && kubectl -n uat exec "$POD" -- rm -rf /tmp/eval
```
ในเครื่องตัวเอง (ต้องมีโมเดล: `MODELS_DIR=~/models ./devops/models/fetch-models.sh`): `pip install -r requirements.txt` แล้ว `python tools/eval_recognition.py --models ~/models --enroll … --probe … --spoof …` · ตัวเลือก `--thresholds`, `--margin`, `--min-face-px`, `--min-conf`, `--liveness-thresholds`

### 3. ผลที่ได้ (รูปแบบตาราง · ตัวเลขมาจากการรันจริงเท่านั้น)

| ตาราง | ใช้ตอบ |
|---|---|
| ข้อมูลที่ใช้วัด | จำนวนคน / ภาพ · อัตราตรวจพบใบหน้า · Rank-1 |
| ผลตาม threshold: `threshold \| TAR \| FAR \| ระบบ: ถูกคน \| ผิดคน \| ไม่จับคู่ \| คนแปลกหน้าถูกจับคู่ \| Accuracy` | "แม่นแค่ไหน" (เช่น ถูกคน 45/50 = 90%) · เลือก `MATCH_THRESHOLD` |
| Anti-spoofing: `ANTISPOOF_MIN_REAL \| BPCER \| APCER \| APCER ต่อชนิด` | คนจริงถูกปฏิเสธ / ภาพหลอกผ่าน (print / phone / …) · เลือก `ANTISPOOF_MIN_REAL` ก่อนเปลี่ยนเป็น `on` |
| ความเร็ว: YOLO ต่อภาพ / SFace ต่อหน้า / anti-spoofing ต่อหน้า / รวมต่อ 1 หน้า (mean · p50 · p95 ms) | "กี่วินาทีต่อหนึ่งใบหน้า" (ร่วมกับ `/metrics` ตอนใช้งานจริง) |

## Anti-spoofing: จากโหมด log → on

1. deploy ด้วย `ANTISPOOF=log` (ค่าใน `devops/k8s/base/ai-service.yaml`) · ตรวจ `/metrics` → `antispoof.ready = true`, `error = null` · log ตอน start: `anti-spoofing: พร้อม · โหมด log: …`
2. ลงทะเบียนจริง + ยืนหน้ากล้องห้อง 4303 (คนจริง) และยกรูปพิมพ์ / มือถือ / แท็บเล็ต / วิดีโอ หน้ากล้อง (ทั้งตอนลงทะเบียนและกล้องห้อง) · ดูคะแนนใน log:
   `kubectl -n uat logs deploy/ai-service | grep -E 'liveness|ปฏิเสธ'` (ลงทะเบียน: `embedding: … liveness 0.xx` · กล้อง: `student xxxxxxxx… score … · liveness เฉลี่ย 0.xx`)
3. ถ้าเก็บภาพไว้: `tools/eval_recognition.py --spoof …` ได้ตาราง BPCER / APCER ต่อเกณฑ์ → เลือก `ANTISPOOF_MIN_REAL` ที่ BPCER ต่ำพอ (คนจริงไม่โดนปฏิเสธ) และ APCER ต่ำที่สุด
4. แก้ `ANTISPOOF` เป็น `"on"` (+ `ANTISPOOF_MIN_REAL` ถ้าเปลี่ยน) ใน `devops/k8s/base/ai-service.yaml` → push `Dev` → CD deploy uat · ชั่วคราว: `kubectl -n uat set env deploy/ai-service ANTISPOOF=on` (CD ครั้งถัดไปกลับเป็นค่าใน git)
- MiniFASNetV2 เป็นโมเดล 2D (ภาพเดียว) — กันรูปพิมพ์ / จอได้บางส่วน ไม่ใช่ 100% (จอคมชัดใกล้กล้อง / วิดีโอ อาจผ่าน) · ภาพสุ่ม (noise) ได้คะแนน "จริง" ~0.97 → คะแนนมีความหมายเฉพาะกับใบหน้าจริง ต้องวัดกับภาพจริงในห้อง

## ทดสอบ (ไม่ต้องมีโมเดล)

```bash
cd ai-service
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest tests
```

## บันทึกการทำงาน (Changelog)

### 2026-11-27 — MiniFASNetV2 (anti-spoofing) ทั้งลงทะเบียน + กล้องห้อง (โหมด log) + torch CPU-only
- **ทำอะไร:**
  - `vision.py`: MiniFASNetV2 ผ่าน ONNX Runtime CPU (session เดียว · intra/inter 1 thread · sequential · graph opt ทั้งหมด) · preprocessing ตรวจกับโค้ดต้นฉบับ Silent-Face-Anti-Spoofing + uniface: ขยายกรอบ ×2.7 → 80×80 · BGR · 0–255 ไม่ normalize · NCHW · output logits 3 class → softmax · index 1 = จริง (0 / 2 = ภาพหลอก ต้นฉบับไม่ระบุชนิด → รายงานแค่ "หลอก") · ตรวจรูปแบบ input/output ตอนโหลด · ตรวจไม่ได้ = `AntiSpoofError` (ไม่ถือว่าจริง) · `ANTISPOOF` = `log` (ค่าเริ่มต้น) / `on` / `off` · `on` แต่โหลดไม่ได้ = ปฏิเสธทุกภาพ (fail closed) · `TORCH_THREADS` (ไม่ตั้ง = เดิม)
  - `quality.py` (ใหม่): เบลอ + ความสว่างของกรอบหน้า (view ไม่ copy ทั้งเฟรม)
  - `main.py`: `/embedding` = YOLO → 1 ใบหน้า → คุณภาพ (+ ความสว่าง `MIN_BRIGHTNESS` 40 / `MAX_BRIGHTNESS` 230) → liveness ก่อน SFace (โหมด on: คะแนน < 0.7 / ตรวจไม่ได้ → 422) → SFace → คนเดียวกัน · `items[].liveness` = คะแนนจริง (รูปแบบ response เดิม) · `/metrics` เพิ่ม `antispoof` (mode/ready/enforce/minReal/model/error) + `process` (cpuPercent/cpuSec/rssMB/threads) · log สถานะ anti-spoofing ตอน start
  - `camera_worker.py`: ต่อใบหน้า YOLO → คุณภาพ (`CAMERA_MIN/MAX_BRIGHTNESS` 30/235 · `CAMERA_MIN_BLUR` 0 = ไม่ตรวจ) → MiniFASNetV2 → SFace · โหมด on: หน้าที่ไม่ผ่าน / ตรวจไม่ได้ไม่ถึง SFace และไม่นับ hit → ต้องผ่านทุกเฟรมที่นับ (`MIN_HITS` 2 ใน 10 วินาที) · โหมด log: ส่งตามปกติ + log liveness เฉลี่ยต่อ sighting + นับ `sightings_low_liveness` · embedding เสีย 1 หน้าข้ามแค่หน้านั้น (เดิมทั้งเฟรม) · นับ `frames_dropped` · body ที่ส่ง backend ไม่เปลี่ยน · ไม่มี face tracking (เหตุผลในหัวข้อตัวอ่านกล้อง)
  - `Dockerfile`: ติดตั้ง torch / torchvision จาก `download.pytorch.org/whl/cpu` ก่อน requirements · `requirements.txt` เพิ่ม `onnxruntime`
  - `tools/eval_recognition.py`: `--spoof DIR/<ชนิด>/` + `--liveness-thresholds` → BPCER / APCER รวม + แยกตามชนิด · เวลา anti-spoofing ต่อหน้า
  - ไฟล์ deploy (ได้รับอนุญาตให้ AI แก้): `devops/k8s/base/ai-service.yaml` env `ANTISPOOF=log`, `ANTISPOOF_MIN_REAL=0.7`, `ANTISPOOF_MODEL`, `ANTISPOOF_THREADS=1` · `devops/models/fetch-models.sh` เพิ่ม `2.7_80x80_MiniFASNetV2.onnx` (URL + SHA-256 เดียวกับ `minifasnet_v2.onnx` เดิม · pin commit)
- **ไฟล์ที่เกี่ยวข้อง:** `ai-service/vision.py`, `ai-service/quality.py`, `ai-service/main.py`, `ai-service/camera_worker.py`, `ai-service/Dockerfile`, `ai-service/requirements.txt`, `ai-service/tools/eval_recognition.py`, `ai-service/tests/test_embedding.py`, `ai-service/tests/test_camera_worker.py`, `ai-service/README.md`, `devops/k8s/base/ai-service.yaml`, `devops/models/fetch-models.sh`
- **เหตุผล:** ผู้ใช้สั่งเพิ่ม anti-spoofing ทั้งลงทะเบียนและกล้องห้อง เริ่มโหมด log (เก็บคะแนนจริงก่อนปฏิเสธ) และอนุญาต torch CPU-only (ลดขนาด image · ดิสก์ VM เต็มจน deploy ล้ม)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:**
  - ผลทดสอบ: pytest 42 ข้อผ่าน · โหลด MiniFASNetV2 จริงผ่าน ONNX Runtime 1.32 + SFace จริง (Mac 8 core): liveness p50 1.70 ms / หน้า · SFace p50 5.41 ms / หน้า · ORT 1 / 2 / 4 thread = p50 1.72 / 1.04 / 1.12 ms (เลือก 1: ต่างไม่ถึง 1 ms และไม่แย่ง YOLO บน 4 vCPU) · ORT กับ cv2.dnn ให้ logits ตรงกัน (ต่างสูงสุด 3.6e-07) · `fetch-models.sh` ดาวน์โหลด + ตรวจ hash ผ่าน · torch: wheel CPU 200 MB ไม่มี nvidia · wheel PyPI 514 MB + `nvidia-cudnn-cu13` ฯลฯ บน Linux
  - **ยังไม่ได้วัด:** YOLO จริง, docker build, CPU / RAM ก่อน–หลังบน VM, การกันรูปพิมพ์ / จอ / วิดีโอจริง (ต้องทำที่ห้อง 4303 ตามหัวข้อ "Anti-spoofing: จากโหมด log → on") · ภาพ noise ได้คะแนน "จริง" 0.97 → ห้ามสรุปความแม่นยำจนกว่าจะวัดกับภาพจริง
  - บน VM: รัน `fetch-models.sh` ใหม่ (ได้ไฟล์ `2.7_80x80_MiniFASNetV2.onnx`) ก่อนหรือหลัง deploy ก็ได้ (โหมด log ไม่มีไฟล์ = ไม่ตรวจ แต่ยังใช้งานได้) · ไฟล์ `minifasnet_v2.onnx` / `minifasnet_v1se.onnx` เดิมลบได้
  - DevOps: อัปเดตตารางโมเดล + env ใน `devops/README.md` (เพิ่ม MiniFASNetV2 กลับ)

### 2026-11-27 — เอา MiniFASNet (anti-spoofing / liveness) ออกจาก ai-service
- **ทำอะไร:** `vision.py` ลบ MiniFASNet V2 + V1SE ทั้งหมด (`ANTISPOOF*` env, `_load_antispoof`, `cv2.dnn` net, `SPOOF_LOCK`, `spoof_crop()`, `liveness()`, `ANTISPOOF_READY/ENFORCE`) · เหลือ YOLO face-pose (`detect`) + SFace (`feature` L2 normalize) เหมือนเดิม · `feature` ตรวจ embedding เสีย (ว่าง / ไม่ใช่ 128 / norm 0 / NaN) → `EmbeddingError` แทนการหารด้วย 0 · `main.py` ลบ `ANTISPOOF_MIN_REAL`, `check_liveness`, log สถานะ anti-spoofing ตอน start, `antispoof` ใน `/metrics` · `/embedding` = decode → YOLO → 1 ใบหน้า → คุณภาพ → SFace (`EmbeddingError` → 422 "สร้างข้อมูลใบหน้าไม่ได้") → ทุกรูปเป็นคนเดียวกัน (`SAME_PERSON_MIN` 0.363 เดิม) · response คง `items[].liveness` เป็น `null` เสมอ (ไม่มีคะแนนปลอม · backend `FaceDtos.EmbeddingItem` อ่านแค่ `embedding`, `quality`) · `tools/eval_recognition.py` ลบ `--spoof`, `--liveness-thresholds`, ตาราง APCER/BPCER และเวลา anti-spoofing (การวัด TAR/FAR/rank-1/ความเร็วคงเดิม) · tests: ลบ test MiniFASNet 5 ข้อ เพิ่ม regression: import `vision.py` โดยไม่มีไฟล์ MiniFASNet และไม่เรียก `cv2.dnn`, `detect` (รูปแบบแถว / ไม่มีหน้า / ไม่มี keypoints / ไม่ใช่ 5 จุด), `feature` (L2 · embedding เสีย 4 แบบ), รูปแบบ response + `liveness: null`, จำนวนใบหน้า / ไฟล์เสีย, `check_quality` ตัวจริง (conf / เล็ก / นอกกรอบ / เบลอ), คนละคน, `/metrics` · ตัวอ่านกล้องไม่ได้แก้
- **ไฟล์ที่เกี่ยวข้อง:** `ai-service/vision.py`, `ai-service/main.py`, `ai-service/metrics.py` (docstring), `ai-service/tools/eval_recognition.py`, `ai-service/tests/test_embedding.py`, `ai-service/README.md`
- **เหตุผล:** ผู้ใช้สั่งเอา anti-spoofing ออกทั้งหมด (ไม่ใส่โมเดลอื่นแทน) · ฝั่ง deploy (env `ANTISPOOF*` ใน `ai-service.yaml`, MiniFASNet ใน `fetch-models.sh`, devops/README) DevOps เอาออกแล้วใน `2680423`
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** pytest 29 ข้อผ่าน · `py_compile` + pyflakes ผ่าน · import `vision.py` กับ SFace ของจริง (ไม่มีไฟล์ MiniFASNet) ได้ embedding 128 มิติ norm 1 · **ยังไม่ได้ทดสอบ YOLO จริงและ docker build** (เครื่องที่แก้ไม่มี ultralytics / docker) — CD จะ build ตอน push ขึ้น `Dev` · `requirements.txt` / `Dockerfile` ไม่ต้องแก้ (MiniFASNet ใช้ `cv2.dnn` ที่มากับ opencv อยู่แล้ว · โมเดลไม่อยู่ใน image) · หลัง deploy: log ไม่มีบรรทัด `anti-spoofing` แล้ว และ `/metrics` ไม่มี `antispoof` · ไฟล์ `minifasnet_*.onnx` บน VM ลบเองได้: `rm /opt/bangmod/models/minifasnet_v2.onnx /opt/bangmod/models/minifasnet_v1se.onnx` · ระบบกันรูปถ่าย / หน้าจอไม่ได้แล้ว (ต้องพึ่งมาตรการอื่น เช่น อาจารย์คุมตอนลงทะเบียน)

### 2026-11-27 — log สถานะ anti-spoofing ตอน start ให้เห็นทุกครั้ง
- **ทำอะไร:** `main.py` ตั้ง `logging.basicConfig` ก่อน `import vision` + log `anti-spoofing: พร้อม · …` / `anti-spoofing: ปิด …` ตอน start · เพิ่ม test (pytest 24 ข้อผ่าน)
- **ไฟล์ที่เกี่ยวข้อง:** `main.py`, `tests/test_embedding.py`
- **เหตุผล:** บน uat (pod `2bszb`) `grep anti-spoofing` ไม่เจออะไร — log INFO ของ `vision.py` ตอน import หายเพราะยังไม่ได้ตั้ง logging (เห็นแค่ WARN ผ่าน lastResort) จึงตรวจไม่ได้ว่าเปิด anti-spoofing แล้วหรือยัง
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** pod ai-service 2 ตัวที่ค้างสถานะ Error บน uat (45 ชั่วโมง) ถูก evict เพราะดิสก์ VM เต็ม (`ephemeral-storage` available 0 · 25 พ.ย. 03:49) — แจ้ง DevOps · ทางลดขนาด image ai-service: ใช้ torch แบบ CPU (ยังไม่ได้ทำ)

### 2026-11-26 — คู่มือวัดผลห้อง 4303 + `/metrics` บน uat + เครื่องมือวัดผลใน image + pytest ใน `dev.sh test`
- **ทำอะไร:** เพิ่มหัวข้อ "ดูเวลาจริงบน uat (`/metrics`)" และ "วัดผลด้วยข้อมูลจริง" (จำนวนคน / ตำแหน่ง / แสง / ภาพหลอก, ดึงเฟรมจากกล้อง Pi ใน pod, รัน `eval_recognition.py` ใน pod, ตารางผล, PDPA) · ตารางตั้งค่าเพิ่ม `ANTISPOOF*`, `SAME_PERSON_MIN`, `METRICS_LOG_INTERVAL` · `Dockerfile` copy `tools/` + เพิ่ม `.dockerignore` · `dev.sh test` รัน pytest ของ ai-service (ข้ามพร้อมข้อความถ้าเครื่องไม่มี python deps)
- **ไฟล์ที่เกี่ยวข้อง:** `README.md`, `Dockerfile`, `.dockerignore`, `../dev.sh`
- **เหตุผล:** ต้องได้ตัวเลขความแม่นยำ / ความเร็วจริงก่อนนำเสนอ 30 พ.ย. · รัน eval บน VM ได้โดยไม่ต้องติดตั้งอะไรเพิ่ม
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ยังไม่ได้ build image จริง (เครื่องนี้ไม่ได้รัน docker) · ยังไม่ได้เก็บข้อมูลจริง

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
