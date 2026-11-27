# บันทึกงานและปัญหาหลังแต่ละ push (summary conflict)

ไฟล์นี้เป็นบันทึกการทำงานของทั้งโปรเจกต์ หลังทุกครั้งที่ push ขึ้น `Dev`
Main Chat (coordinator) จะส่งสรุปมาให้ sub-chat "summary conflict" จดลงที่นี่ 1 รายการต่อ 1 push
เพื่อให้ย้อนดูได้ว่าแต่ละรอบแก้อะไร (แยกตาม area: DB / BE / FE / DevOps / docs)
เจอปัญหาหรือ conflict อะไร แก้อย่างไร และยังมีอะไรค้างอยู่

- รายการใหม่อยู่บนสุด (เรียงจากใหม่ไปเก่า)
- ช่วง commit เขียนแบบ `<commit ก่อนรอบนี้>..<commit สุดท้ายของรอบ>`
- ห้ามมีค่าความลับ (รหัสผ่าน, key, ค่าใน `secret.env`)

รูปแบบ:

```markdown
### YYYY-MM-DD — <หัวข้อ> (`<commit แรก>..<commit สุดท้าย>`)
- **แก้อะไร:** สรุปสั้น ๆ แยกตาม area พร้อม commit hash
- **ปัญหา / conflict ที่เจอ:** สิ่งที่พังหรือขัดกัน และแก้อย่างไร · ไม่มี = "ไม่มี"
- **ยังค้าง / ต้องทำต่อ:** เช่น SQL ที่ผู้ใช้ต้องรันบน VM, สิ่งที่ยังไม่ได้ทดสอบ
```

## บันทึก

### 2026-11-27 — [สั่งแก้] เปิดคาบเดิมของวันนี้อีกครั้ง / ย้ายห้อง (BE + FE) (ต่อจาก `864805d` · FE `6091484` · BE ยังทำอยู่)
> คำสั่งแก้ · ผู้ใช้สั่ง coordinator → ส่ง BE + FE เมื่อ 2026-11-27 ~23:30 · FE commit แล้ว BE ยังไม่ commit · ยังไม่ push (จะเติมช่วงทีหลัง)
- **commit แล้ว:**
  - FE `6091484`: `StartSessionDialog` ยืนยันเปิดคาบเดิม / ย้ายห้อง + เตือนเมื่อกดเริ่มนอกวัน / เวลาเรียน (±60 นาที) + toast ตาม `startMode` · 6 ไฟล์ frontend · Vitest 12 ข้อ · `./dev.sh test` ผ่าน · FE เพิ่มเอง: คาบ cancelled ที่มีคาบชดเชยแล้ว = ปิดปุ่ม · cancelled ที่ไม่มีคาบชดเชย = บอกว่าจะคืนคาบ
- **แก้อะไร (ที่สั่ง):**
  - BE: start-now เปิดคาบเดิมของวันนี้อีกครั้ง (ลบเฉพาะผล "ขาด" ที่ `source = system` · เก็บ camera / manual / import) + ย้ายคาบที่กำลังเรียนจากห้องอื่นมาได้ + response เพิ่ม `startMode` / `previousRoomCode`
  - FE: dialog ยืนยันเปิดคาบซ้ำ / ย้ายห้อง + เตือนเมื่อกดเริ่มนอกวัน / เวลาเรียน
  - ไม่แตะ schema → CD ไม่หยุด
- **ปัญหา / conflict ที่เจอ:**
  - หน้าห้องขึ้น "ไม่มีคาบเรียนตอนนี้" ทั้งที่มีนักศึกษาในห้อง · สาเหตุ (ตรวจบน uat): INT201-001 มีคาบ 27 พ.ย. 00:38–02:33 (กดเริ่มคาบหลังเที่ยงคืน) สถานะ completed ขาด 12 คน → หน้าห้องไม่แสดงคาบ completed · start-now ได้ 409 เพราะ UNIQUE (`section_id`, `session_date`) + กฎ 1 กลุ่ม 1 คาบต่อวัน · extend คาบที่จบแล้วไม่ได้
  - เลือกทาง 2 + ข้อ 4 (เปิดคาบเดิมซ้ำ + ย้ายห้อง) · ทางที่ไม่เลือก: ปลด UNIQUE ให้มีหลายคาบต่อวัน (ใหญ่เกินก่อน deadline 30 พ.ย.)
- **ยังค้าง / ต้องทำต่อ:**
  - รอ BE commit (ยังทำอยู่) และ push → เติมช่วง commit
  - FE ยังทดสอบกดจริงไม่ได้ เพราะ BE ยังไม่เสร็จ (ตอนนี้ยังได้ 409) → ทดสอบรวมหลัง BE commit
  - ยังไม่ตรวจ: `GET /api/courses` ของ admin บน uat คืนแค่ INT201

### 2026-11-27 — ใส่ anti-spoofing (MiniFASNetV2) กลับ ทั้งลงทะเบียน + กล้องห้อง (โหมด log) + torch CPU-only (`3ee286f..864805d`)
> จดอัตโนมัติจาก commit แล้วเติมจากรายงาน coordinator · push แล้ว: `origin/Dev` `3ee286f` → `864805d` (รวม `15f5992`, `28dfa11` ของรายการก่อนหน้า) · ผู้ใช้สั่ง AI โดยตรง (ไม่ผ่าน coordinator) และ AI push เอง
- **แก้อะไร:**
  - AI `864805d` (แตะ `devops/` ด้วย):
    - MiniFASNetV2 รันบน ONNX Runtime CPU (1 session ใช้ร่วมกัน, 1 thread) · preprocessing ตามต้นฉบับ (crop ×2.7, 80×80, BGR, softmax class 1 = จริง) · error ตอนตรวจ → `AntiSpoofError` ไม่ถือว่าเป็นหน้าจริง
    - `ANTISPOOF` = `log` (ค่าเริ่มต้น) / `on` / `off` · log = ให้คะแนนและบันทึกแต่ไม่ปฏิเสธ · on = ปฏิเสธ และถ้าโหลดโมเดลไม่ได้จะปฏิเสธทุกภาพ (fail closed)
    - ลงทะเบียน: คุณภาพ (เพิ่มความสว่าง) → liveness → SFace → ตรวจว่าเป็นคนเดียวกัน · `items[].liveness` มีคะแนนจริงแล้ว (รูปแบบ response เดิม)
    - กล้องห้อง: YOLO → ตรวจคุณภาพ (`CAMERA_MIN/MAX_BRIGHTNESS` 30/235) → liveness → SFace · โหมด on: หน้าที่ไม่ผ่านไม่ถึง SFace และไม่นับ hit · โหมด log: ส่งตามปกติ + นับ `sightings_low_liveness` · body ที่ส่ง backend ไม่เปลี่ยน
    - `/metrics` เพิ่ม antispoof + process (CPU / RSS) · เครื่องมือ eval รายงาน BPCER / APCER แยกตามชนิดภาพหลอก
    - `Dockerfile` ติดตั้ง torch แบบ CPU (wheel 200 MB ไม่มี nvidia) แทน wheel PyPI 514 MB + ไลบรารี CUDA
    - DevOps (ไฟล์ใน commit เดียวกัน): `k8s/base/ai-service.yaml` ตั้ง `ANTISPOOF=log` · `models/fetch-models.sh` เพิ่ม `2.7_80x80_MiniFASNetV2.onnx` (pin hash)
  - pytest 42 ข้อผ่าน · liveness p50 ~1.7 ms / หน้า บน Mac
- **ปัญหา / conflict ที่เจอ:**
  - กลับทิศภายในวันเดียวกัน (ไม่ใช่ git conflict): `3ee286f` เพิ่งเอา anti-spoofing ออกทั้งหมด → รอบนี้ผู้ใช้สั่งใส่กลับทั้งลงทะเบียนและกล้องห้อง เริ่มโหมด log เพื่อเก็บคะแนนจริงก่อนเปิดปฏิเสธ (ใช้ไฟล์โมเดลใหม่ `2.7_80x80_MiniFASNetV2.onnx` ไม่ใช่ `minifasnet_v2.onnx` / `minifasnet_v1se.onnx` เดิม)
  - ดิสก์ VM เต็มจน deploy ล้ม → ผู้ใช้อนุญาตเปลี่ยน torch เป็นแบบ CPU (ลดขนาด image) — ปิดเรื่อง "torch แบบ CPU" ที่ค้างจากรายการก่อน
  - ภาพ noise ได้คะแนน "จริง" 0.97 → ห้ามสรุปความแม่นยำจนกว่าจะวัดกับภาพจริง
  - commit ของ AI แก้ไฟล์ใน `devops/` เอง และ `devops/README.md` ยังไม่ได้อัปเดตตาม (ตารางโมเดลยังเขียนว่า MiniFASNet เลิกใช้แล้ว)
- **ยังค้าง / ต้องทำต่อ:**
  - บน VM: รัน `fetch-models.sh` ใหม่เพื่อให้ได้ `2.7_80x80_MiniFASNetV2.onnx` (โหมด log ไม่มีไฟล์ = ไม่ตรวจ แต่ใช้งานได้) · ไฟล์ `minifasnet_v2.onnx` / `minifasnet_v1se.onnx` เดิมลบได้ (ผู้ใช้ตัดสิน)
  - ยังไม่ได้วัด: YOLO จริง, docker build, CPU / RAM ก่อน–หลังบน VM, การกันรูปพิมพ์ / จอ / วิดีโอจริงที่ห้อง 4303 (ตามหัวข้อ "Anti-spoofing: จากโหมด log → on" ใน `ai-service/README.md`)
  - DevOps / AI: อัปเดตตารางโมเดล + env ใน `devops/README.md` (เพิ่ม MiniFASNetV2 กลับ)
  - **ยังไม่ตรวจ:** CD / uat หลัง deploy `864805d`, image ใหม่ build ผ่านไหม, พื้นที่ดิสก์ VM ตอนนี้ (coordinator ยังไม่ได้ดูผลบน VM)
  - ดิสก์ VM: งานกันดิสก์เต็มของ DevOps (`devops/cd/deploy.sh`, `devops/cd/setup.sh`) ยังค้างไม่ commit รอผู้ใช้ตัดสิน · pod ai-service เคยถูก Evict (ephemeral-storage) วันที่ 25 พ.ย.

### 2026-11-27 — เอา anti-spoofing (MiniFASNet) ออกทั้งหมด (`39d446b..3ee286f`)
> จดอัตโนมัติจาก commit แล้วเติมจากรายงาน coordinator · push แล้ว: `origin/Dev` `8375493` → `3ee286f` (รวม `a113d09`–`f46e527` ของรายการก่อนหน้า) · ผู้ใช้สั่ง AI และ DevOps โดยตรง (ไม่ผ่าน coordinator) และ coordinator ไม่ได้เป็นคน push รอบนี้ · ไม่แตะ `backend/db/` → CD ไม่ block
- **แก้อะไร:**
  - DevOps `2680423`: `k8s/base/ai-service.yaml` ลบ env `ANTISPOOF`, `ANTISPOOF_MIN_REAL`, `ANTISPOOF_MODEL_V2`, `ANTISPOOF_MODEL_V1SE` · `models/fetch-models.sh` ไม่ดาวน์โหลด MiniFASNet แล้ว (เหลือ YOLO + SFace) · README: ตารางโมเดลเหลือ 2 ไฟล์ ลำดับหลัง push ไม่อ้าง MiniFASNet
  - AI `3ee286f`: `vision.py` / `main.py` ลบ MiniFASNet ทั้งหมด (รวม log สถานะ anti-spoofing ตอน start จาก `39d446b`) · ลงทะเบียน = decode → YOLO face-pose → 1 ใบหน้า → ตรวจคุณภาพ → SFace → ตรวจว่าเป็นคนเดียวกัน · embedding เสีย (ว่าง / ไม่ใช่ 128 / 0 / NaN) → `EmbeddingError` → 422 · `items[].liveness` คงไว้แต่เป็น `null` เสมอ · `/metrics` และเครื่องมือ eval ตัดส่วน anti-spoofing (APCER / BPCER) ออก · ตัวอ่านกล้องไม่เปลี่ยน · pytest 29 ข้อผ่าน
- **ปัญหา / conflict ที่เจอ:**
  - ต้นเหตุ: ผู้ใช้แก้เกณฑ์ `ANTISPOOF_MIN_REAL` บน VM แล้วไม่มีผลกับ pod เพราะ CD ใช้ค่าจาก `ai-service.yaml` บน `origin/Dev` (= 0.7) เท่านั้น → ผู้ใช้สั่งเอา anti-spoofing ออกทั้งหมด (ไม่ใส่โมเดลอื่นแทน) หลังเพิ่งทำ `e16d4d0` และ `39d446b` → งานวัดความแม่นยำ anti-spoofing และการดู log "anti-spoofing พร้อม" ในรายการก่อน ๆ ไม่ต้องทำแล้ว
  - ผลกระทบ: ความเสี่ยง "ใช้รูปถ่ายหลอกตอนลงทะเบียน" กลับมาบางส่วน — เหลือแค่ตรวจว่า 3–5 รูปตอนลงทะเบียนเป็นคนเดียวกัน (`SAME_PERSON_MIN` 0.363) ซึ่งกันได้แค่รูปปนหลายคน · response ยังมี `items[].liveness` = `null` เสมอ (backend ไม่อ่าน) · camera worker ไม่เปลี่ยน
  - ลำดับ deploy: manifest ใหม่ (`2680423`) ถ้าขึ้นก่อนโค้ด AI ใหม่ และไฟล์ MiniFASNet ยังอยู่บน VM → anti-spoofing ยังทำงาน (ค่าเริ่มต้น `ANTISPOOF=on`) · push ทั้งสองพร้อมกันแล้ว
- **ยังค้าง / ต้องทำต่อ:**
  - CD / uat หลัง deploy ยังไม่มีใครตรวจ · ยังไม่ได้ทดสอบ YOLO จริงและ docker build (เครื่องที่แก้ไม่มี ultralytics / docker) → ตรวจ `kubectl -n uat logs deploy/ai-service` ว่า Uvicorn ขึ้นและไม่มี ERROR
  - ไฟล์ `minifasnet_v2.onnx`, `minifasnet_v1se.onnx` ใน `/opt/bangmod/models` บน VM ไม่ถูกใช้แล้ว → ลบได้ถ้าต้องการพื้นที่ (ผู้ใช้ตัดสิน และลบเองหลัง ai-service รุ่นใหม่ขึ้น)
  - ยังค้างจากรายการก่อน (ไม่เกี่ยวกับรอบนี้): ตรวจดิสก์ VM / แผนเคลียร์ · เรื่อง torch แบบ CPU · `devops/cd/deploy.sh`, `devops/cd/setup.sh` แก้ค้างใน working tree (งานกันดิสก์เต็มของ DevOps ที่หยุดกลางทาง — รอผู้ใช้ตัดสิน)

### 2026-11-27 — ai-service log anti-spoofing ตอน start + pod ai-service ถูก Evict เพราะดิสก์เต็ม (`372a2e4..39d446b`)
> รายงาน coordinator · push แล้วใน `8375493..3ee286f` · log anti-spoofing ถูกเอาออกใน `3ee286f` (รายการบน)
- **แก้อะไร:**
  - AI `39d446b`: ai-service log สถานะ anti-spoofing ทุกครั้งตอน start (เดิม log INFO "anti-spoofing พร้อม" ไม่ออก เพราะ `logging.basicConfig` ถูกเรียกหลัง import `vision`) · pytest 24/24
- **ปัญหา / conflict ที่เจอ:**
  - ผู้ใช้ตรวจ uat เจอ pod ai-service ค้างสถานะ Error 2 ตัว — ถูก Evict เพราะ ephemeral-storage ของ node เหลือ 0 (25 พ.ย. 03:49) → ดิสก์ VM เคยเต็ม
  - สาเหตุที่สงสัย: registry ของ CD เก็บ image ai-service ทุก commit (torch แบบ CUDA ใหญ่หลาย GB) + docker build cache · coordinator ส่ง DevOps ทำคำสั่งตรวจแบบอ่านอย่างเดียว + แผนเคลียร์ + วิธีกันไม่ให้เกิดซ้ำ
  - รายการ "ตรวจ uat หลัง deploy 8375493" ที่ให้ดู log "anti-spoofing พร้อม" ตรวจไม่ได้จนกว่าจะ deploy `39d446b`
- **ยังค้าง / ต้องทำต่อ:**
  - ผู้ใช้รันคำสั่งตรวจดิสก์บน VM แล้วเลือกแผนเคลียร์ (ห้ามลบเองโดยไม่ถาม)
  - ตัดสินใจเรื่องเปลี่ยน torch เป็นแบบ CPU ใน Dockerfile ของ ai-service (AI เสนอ)
  - หลัง push / deploy `39d446b`: ตรวจ log anti-spoofing ของ ai-service บน uat

### 2026-11-27 — ตรวจ uat หลัง deploy 8375493 (`8375493..8375493`)
> ไม่มี commit โค้ดใหม่ (มีแค่ `a113d09` แก้รายการ 2026-11-26 รอ push) · DevOps ตรวจจากภายนอกผ่าน bscit โดยไม่ login หลังผู้ใช้ทำขั้นบน VM
- **แก้อะไร:** ไม่มีงานโค้ด · ผลตรวจ uat:
  - ✅ หน้าเว็บได้ 200 และเป็น bundle ใหม่ (มี F3 `SectionRosterPage`, ข้อความ preset ของ F1)
  - ✅ `/api/me` พร้อม Origin bscit = 401 · `/api/internal/cameras` = 404
  - ✅ `/api/test-accounts` ได้ **19 บัญชี** (admin 1, lecturer 1, student 17 = ทีม 3 + `66130500099` + `66130500288` + `66139900001`–`012`) — ตรวจซ้ำหลังผู้ใช้ reset DB uat ใหม่ (รอบแรกได้ 5 บัญชีชุดเดิม ❌)
- **ปัญหา / conflict ที่เจอ:**
  - ตรวจรอบแรก frontend ขึ้นรุ่นใหม่แล้ว แต่ DB ยังเป็นข้อมูลชุดเก่า (5 บัญชี) · สาเหตุ: ยังไม่ได้ reset DB เป็น seed ใหม่ (`2eef1ff`) → ผู้ใช้ reset แล้ว ตรวจซ้ำผ่าน
  - ยืนยันจากภายนอกไม่ได้: image tag ของ backend / ai-service, `NEW_STUDENT_PASSWORD`, anti-spoofing, โมเดลครบ 4 ไฟล์, `deploy.sh status` → ต้องตรวจบน VM
- **ยังค้าง / ต้องทำต่อ:**
  - ผู้ใช้ตรวจบน VM: `deploy.sh status`, image tag, ตั้ง `NEW_STUDENT_PASSWORD` แล้วหรือยัง, log anti-spoofing, โมเดลครบ 4 ไฟล์
  - ลงทะเบียนใบหน้าทีมใหม่ (reset ลบใบหน้าเดิม) → ทดสอบกล้องห้อง 4303 → เก็บตัวเลขความแม่นยำ / ความเร็ว

### 2026-11-26 — E2 / F1–F3 จัดการนักศึกษาในกลุ่ม, seed เดโม, AI anti-spoofing, เตรียม prod (`cd04b8b..8375493`)
> push แล้ว (15 commit) · ก่อน push `./dev.sh test` ผ่านทั้งชุด รวม ai-service pytest 23 ข้อ · `main` ไม่มี commit ที่ `Dev` ขาด
- **แก้อะไร:**
  - BE `7239079` (E2): API เพิ่ม / ถอน / นำเข้านักศึกษาในกลุ่มเรียน (`/api/sections/{id}/enrollments`, import csv/txt/xlsx + `dryRun`, template) · action `insert` / `reactivate` / `create` / `skip` / `unmatched` · สร้างบัญชีนักศึกษาใหม่ได้เมื่อตั้ง `NEW_STUDENT_PASSWORD` (local / uat เท่านั้น) · audit ทุกการเพิ่ม / ถอน / นำเข้า
  - FE `dc727ec` (F1): ตัวเลือก "สาย 10 / 15 / 30 นาที" + กำหนดเองในฟอร์มรายวิชาและฟอร์มคาบ (ค่าเริ่มต้น 15)
  - FE `e153f32` (F2): prod อยู่ใต้ path ของ bscit (`VITE_BASE=/capstone26/cp26ms3/prod/`, `APP_PATH=/prod/`)
  - FE `1a86443` (F3): หน้ารายชื่อนักศึกษาในกลุ่มเรียน — เพิ่มทีละหลายคน / วาง, wizard นำเข้าไฟล์, ถอน + เหตุผล, เพิ่มกลับคนที่ถอนแล้ว
  - DB `2eef1ff`: seed เดโม 5 วิชา (INT201–INT205) นักศึกษา 17 คน กติกาสาย 2 แบบ ห้อง 4303 ใช้ร่วมกันไม่ชนเวลา · `03` ข้ามคาบเดโมถ้าห้อง 4303 ไม่ว่าง
  - AI `e16d4d0`, `6d16098`: วัดเวลาจริง (`/metrics`), เครื่องมือวัดผลการจดจำใบหน้า, anti-spoofing (MiniFASNet) ตอนลงทะเบียน, คู่มือเก็บข้อมูลภาคสนาม, pytest อยู่ใน `./dev.sh test`
  - DevOps `4f0c21e`: overlay prod หลัง bscit (`CORS_ORIGINS`, `MEDIA_BASE_URL`, แก้คอมเมนต์ CORS ที่ค้างจากรอบ docs sync), MediaMTX บน Pi เป็น systemd, checklist กล้องเดโม
  - DevOps `7d1468b`: `NEW_STUDENT_PASSWORD` อ่านจาก secret ของ uat ไม่ตั้งใน prod
  - DevOps `f739ba9`, `79240fc`: SQL prod ครั้งแรก (ภาคเรียน, `consent_versions`, ห้อง 4301–4305) · เพิ่มอาจารย์ · นำเข้านักศึกษาจาก CSV (มี dry run, ปฏิเสธ DB mock)
  - docs `35908d1`, `3c92bbe`: README root (seed เดโม, E2, F1, metrics, หัวข้อบัญชีทดสอบ) · โมเดล MiniFASNet + ลำดับอัปเดต uat หลัง push นี้
  - docs `e7db958`, `8375493`: เริ่มไฟล์บันทึกนี้ + รายการนี้
- **ปัญหา / conflict ที่เจอ:**
  - **git index ใช้ร่วมกันหลาย session:** DevOps `git add` 3 ไฟล์ทิ้งไว้ (`devops/README.md`, `devops/k8s/overlays/prod/kustomization.yaml`, `devops/pi/mediamtx.service`) → ไปติดใน commit ของ FE (F1) และต่อมาของ BE (E2 `f219ccb`) · FE และ BE `reset --soft` เฉพาะ commit ของตัวเอง (ยังไม่ push) แล้ว commit ใหม่ → DevOps commit ไฟล์ของตัวเองเป็น `4f0c21e` · ไม่มีอะไรหาย · **กฎใหม่: commit ด้วย pathspec ในคำสั่งเดียว (`git commit -m ... -- <ไฟล์>`) ห้าม `git add -A` / `git add .` / `git commit -a`**
  - `./dev.sh test` ล้มที่ frontend format ชั่วคราว เพราะไฟล์ F3 ที่ยังไม่ commit อยู่ใน working tree (ไม่ใช่ความผิดของ commit ที่รัน) · smoke B2 ล้มชั่วคราวช่วงที่ seed ของ DB ยังไม่ commit → หายหลัง commit ครบ
  - sub-chat FE / AI / DB ชน usage limit 5 ชั่วโมงกลางงาน → coordinator สั่งให้ทำต่อหลัง limit reset
  - prod ไม่มีทางสร้างบัญชีอาจารย์ / นักศึกษา → ผู้ใช้เลือกใช้ SQL (`79240fc`) · ข้อจำกัด: ไม่มีบังคับเปลี่ยนรหัสผ่านครั้งแรก และไม่มีหน้าเปลี่ยนรหัสผ่าน
  - `2eef1ff` แก้ `backend/db/` → push แล้ว **CD จะหยุด (blocked)** จนกว่าผู้ใช้ทำตามลำดับใน `devops/README.md` แล้วสั่ง `ALLOW_DB_CHANGE=1 deploy.sh uat`
  - เปิด bscit `/prod/` ได้ 503 `no available server` จาก Traefik ของ VM (ไม่ใช่ 404 ของ bscit) → อาจมี Ingress prod ค้าง ต้องตรวจก่อน ห้ามลบเอง
  - DevOps เตรียม prod แล้วพบว่า `frontend/.env.production` ยังเป็น `VITE_BASE=/prod/` → ส่งต่อให้ FE แก้ใน F2 (`e153f32`)
  - สเปกไม่ระบุกรณีนักศึกษาที่ยังไม่มีบัญชี → BE ตัดสินเอง: สร้างบัญชีให้เมื่อส่งชื่อ-นามสกุลและตั้ง `NEW_STUDENT_PASSWORD`
  - reset DB ตาม seed ใหม่ลบใบหน้าเดิม → ทีมต้องลงทะเบียนใบหน้าใหม่ก่อนเดโมกล้อง
- **ยังค้าง / ต้องทำต่อ:**
  - uat: รัน SQL ตามลำดับใน `devops/README.md` + `ALLOW_DB_CHANGE=1 deploy.sh uat` · ตั้ง `NEW_STUDENT_PASSWORD` ใน secret ของ uat
  - prod ยังไม่ deploy: merge PR `Dev` → `main` ก่อน (รอผู้ใช้สั่ง) · ตรวจ 503 · แล้วผู้ใช้รัน `deploy.sh prod` เอง + SQL ครั้งแรก
  - วันภาคเรียน 2569/1 ใน `prod-first-setup.sql` (2026-08-03 → 2026-12-18) เป็นค่าประมาณ ต้องตรวจกับปฏิทิน มจธ.
  - ข้อความ consent v1 ยังเป็นร่าง (ยังไม่ผ่าน DPO) ใช้กับเดโมเท่านั้น
  - ชื่อของ `66130500288` / `66130500099` ยังเป็น placeholder รอยืนยัน
  - ค่า "ขาดหลัง" 30 / 45 / 60 นาที FE เสนอเอง รอยืนยันกับระเบียบจริง
  - ยังไม่ได้วัดความแม่นยำ anti-spoofing กับหน้าจริง · ยังไม่ได้ build image AI จริง · ยังไม่ได้รัน kustomize / ทดสอบบน VM
  - F3 ยังไม่ได้ทดสอบด้วยบัญชี lecturer และไฟล์ `.xlsx` จริง

### 2026-11-25 — ข้อความ 409 "มีคาบอยู่แล้ว" บอกวันที่จริง (`296bb1d..cd04b8b`)
- **แก้อะไร:**
  - BE `cd04b8b`: 409 "วันนี้กลุ่มเรียนนี้มีคาบอยู่แล้ว" เดิมขึ้นคำว่า "วันนี้" แม้วันที่ที่ขอเป็นวันอื่น → ตอนนี้บอกวันที่จริง (`yyyy-MM-dd`) · กรณีเริ่มคาบทันที (start-now) ยังใช้ "วันนี้" เหมือนเดิม
- **ปัญหา / conflict ที่เจอ:** ไม่มี (FE เจอข้อความผิดตอนทำ D3 แล้วแจ้ง BE แก้)
- **ยังค้าง / ต้องทำต่อ:** ไม่มี

### 2026-11-25 — D1–D3 จัดการคาบเรียนรายคาบ (`2861543..296bb1d`)
- **แก้อะไร:**
  - DB `7f91a32` (D1): `class_sessions` เพิ่มคอลัมน์ `is_overridden`, `original_date`, `makeup_for_session_id`, `is_extra`, `cancel_reason` + ไฟล์อัปเดต `06_class_session_management.sql` + `ClassSessionRepository`
  - BE `f0b1bb2` (D2): API ดูรายการคาบ / เลื่อนคาบ / ยกเลิก + คาบชดเชย / เพิ่มคาบ / คืนคาบ, field `allowedActions`, `overriddenSessionsKept`
  - FE `296bb1d` (D3): หน้าจัดการคาบ + dialog ของแต่ละ action
- **ปัญหา / conflict ที่เจอ:**
  - ข้อความสวนกัน: contract D2 จาก coordinator ไปถึง FE หลัง FE ทำ D3 เสร็จแล้ว (FE อ่านจากโค้ด BE เอง) → เทียบแล้วตรงกัน ไม่ต้องแก้ซ้ำ
  - BE ตัดสินกติกาเองที่สเปกไม่ได้ระบุ: 1 คาบชดเชยได้ 1 ครั้ง · ยกเลิกคาบที่กำลังสอนอยู่ → เก็บผลเช็คชื่อไว้แต่ไม่นับในสถิติ
- **ยังค้าง / ต้องทำต่อ:**
  - ผู้ใช้ต้องรัน `06_class_session_management.sql` บน uat แล้วสั่ง `ALLOW_DB_CHANGE=1 deploy.sh uat` (CD blocked เพราะแก้ `backend/db/`)
  - prod ต้องรันทั้ง `05` และ `06`
  - ยังไม่ได้ทดสอบด้วยบัญชีอาจารย์ และกรณีห้องชนกันข้ามกลุ่มเรียน

### 2026-11-25 — C1–C3 นำเข้าผลเช็คชื่อจากไฟล์ (`19cac9c..2861543`)
- **แก้อะไร:**
  - DB `428de98` (C1): ตาราง `attendance_imports`, `attendance_import_rows`, `attendance_records.source = 'import'` + `import_id`, ไฟล์อัปเดต `05_attendance_import.sql`
  - BE `6dd98e8` (C2): API นำเข้าไฟล์ + กติกาการเขียนทับ (override)
  - FE `7f1020d`, `2861543` (C3): wizard นำเข้าไฟล์ + คำเตือนให้ลบแถวที่ไม่ใช่นักศึกษาก่อนนำเข้า
- **ปัญหา / conflict ที่เจอ:**
  - BE ถาม coordinator ขอ C1 ทั้งที่ DB commit ไปแล้ว (ข้อความไม่ตรงกับสถานะจริง)
  - สเปกตีความได้สองแบบ: มีทั้งลำดับความสำคัญ (priority) และ flag override → C2 เลือกแบบนี้: ต่างจากผลกล้อง → conflict เว้นแต่ส่ง `overrideCamera` · ทับผล manual → conflict เสมอ เว้นแต่ส่ง `overrideManual`
  - ไฟล์ Teams ที่มีอีเมลคนที่ไม่ใช่นักศึกษา → 400 ทั้งไฟล์ (ตามสเปก) → FE เพิ่มคำเตือน (`2861543`)
  - ดิสก์ของ DB เต็มระหว่างทำงาน (เหลือ ~121MB) แล้วกลับมาปกติ
- **ยังค้าง / ต้องทำต่อ:**
  - `05` บน uat: ผู้ใช้รันแล้ว
  - ยังไม่ได้ทดสอบ: ไฟล์ XLSX, `duplicateOf`, หน้าจอมือถือ, การนำเข้าระหว่างที่ SSE ทำงาน

### 2026-11-25 — ปรับเวลาคาบที่เริ่มแล้ว + field ช่วงเวลาเช็คชื่อ (`696c2bf..19cac9c`)
- **แก้อะไร:**
  - BE `22f5ad1`: เพิ่ม 4 field แบบ `"HH:mm"` ใน `currentSession` ของ B1 / B4 และ 5.7
  - FE `19cac9c`: จัดการเวลาของคาบที่เริ่มแล้วจากหน้าห้องและหน้ารายงาน
- **ปัญหา / conflict ที่เจอ:**
  - `./dev.sh test` ไม่ผ่านที่ format ของ frontend เพราะไฟล์ FE ยังไม่ commit ระหว่างแก้ → รัน prettier ก่อน commit แล้วผ่าน
  - รูปแบบเวลาไม่เหมือนกัน: `startTime` / `endTime` ยังเป็น `"HH:mm:ss"` ส่วน field ใหม่เป็น `"HH:mm"`
  - FE push รอบนี้เอง ไม่ได้ผ่าน coordinator
- **ยังค้าง / ต้องทำต่อ:** ไม่มี

### 2026-11-25 — docs sync README ให้ตรงกับโค้ด (`fc31b5e..8a9fb0b`)
- **แก้อะไร:**
  - docs `e2f3586`: แก้ README 9 จุดที่เก่า / ขัดกัน — ตารางสถานะที่ root, endpoint เริ่มคาบอยู่ผิดตารางใน `backend/README.md`, `MEDIA_BASE_URL`, realtime matcher, ลิงก์ `claude/frontend-structure.md` ที่หายไป, CORS
  - `8a9fb0b`: merge `origin/main` เข้า `Dev`
- **ปัญหา / conflict ที่เจอ:**
  - README เขียนว่า "ไม่ต้องตั้ง CORS" แต่ uat ที่อยู่หลัง bscit ต้องตั้ง `CORS_ORIGINS=https://bscit.sit.kmutt.ac.th` → DevOps ยืนยันจากโค้ดแล้วแก้ README
  - `main` มี merge commit ของ PR ที่ `Dev` ยังไม่มี → merge `origin/main` เข้า `Dev` ก่อน (เนื้อหาไม่เปลี่ยน)
- **ยังค้าง / ต้องทำต่อ:** comment เรื่อง CORS ใน `devops/k8s/overlays/prod/kustomization.yaml:26` ยังไม่ได้แก้
