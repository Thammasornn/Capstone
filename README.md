# Capstone — Bangmod Smart Attendance (CP26MS3)

ระบบเช็คชื่อนักศึกษาอัตโนมัติด้วยกล้อง (Face Recognition) · โครงงานจบ IT, SIT KMUTT ปีการศึกษา 2569

## โครงสร้าง repo (monorepo)

| โฟลเดอร์ | เนื้อหา | สถานะ |
|---|---|---|
| [`frontend/`](frontend/) | Vue 3 + Vite (JavaScript) · Tailwind v4 · Pinia · MSW | R1 ทุกหน้าเรียก API จริง (รวมเริ่ม / จบคาบจากหน้าห้อง) |
| [`backend/`](backend/) | Spring Boot REST + SSE · PostgreSQL — [`backend/db/`](backend/db/) schema + mock data R1 พร้อมใช้ | API R1 (5.1–5.11, B1–B8) |
| [`ai-service/`](ai-service/) | Python (FastAPI) · YOLOv8 face-pose → OpenCV SFace | ลงทะเบียนใบหน้า + ตัวอ่านกล้อง realtime (MediaMTX → จับคู่ → เช็คชื่อ) |
| [`devops/`](devops/) | K3s manifests (namespace `prod` / `uat`), Ingress, MediaMTX | manifests prod / uat · MediaMTX (namespace `media`) · CD: push `Dev` → uat |
| [`docs/`](docs/) | เอกสารประกอบ — [`docs/database/`](docs/database/) แบบฐานข้อมูล + ER Diagram | — |

```
[Raspberry Pi Camera] --RTSPS--> [MediaMTX] --RTSP--> [AI Service]
[Browser] --HTTPS (REST + SSE)--> [NGINX + Vue] --> [Spring Boot] --> [PostgreSQL]
```

Deploy บน Faculty VM ด้วย K3s: `/prod/` และ `/uat/` แยก namespace · เปิด uat ที่ `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/uat/` (NGINX ของคณะ forward มาที่ VM)

## ทดสอบบนเครื่อง (local)

ต้องมี: **PostgreSQL** ที่เปิดอยู่ (macOS แนะนำ [Postgres.app](https://postgresapp.com) แล้วกด Start), **Java 25 + Maven**, **Node.js** 22.18+ หรือ 24.12+

```bash
./dev.sh db     # ครั้งแรก: สร้างฐานข้อมูล + ข้อมูล mock (รันซ้ำ = ล้างข้อมูลกลับเป็นชุดเดิม)
./dev.sh        # รัน backend + frontend พร้อมกัน → http://localhost:5173 (Ctrl+C หยุดทั้งคู่)
```

ไม่ต้องตั้ง key อะไรเลย — backend ใช้โปรไฟล์ `local` ที่มี key ทดสอบให้ (ดู `backend/README.md`)
### บัญชีทดสอบ (รหัสผ่าน `Password@123` · ใช้ได้บนเครื่องและ uat · **ไม่มีใน prod**)

หน้า Login แสดงบัญชี**ทั้งหมดที่มีใน DB** ให้กดเลือกได้ จัดกลุ่มตามสถานะใบหน้า / ความยินยอม (frontend `VITE_SHOW_TEST_ACCOUNTS=true` + backend `TEST_ACCOUNTS_ENABLED=true` · local และ uat เท่านั้น)

- เจ้าหน้าที่: `admin@sit.kmutt.ac.th` (admin) · `supawadee.c@sit.kmutt.ac.th` (อ.ดร.สุภาวดี · สอน INT201-001)
- นักศึกษา: สมาชิกทีม `66130500013`, `66130500036`, `66130500041` (`@st.sit.kmutt.ac.th`) · ลงทะเบียน INT201-001 · **ยังไม่ยินยอม / ไม่มีใบหน้า** → ลงทะเบียนใบหน้าด้วยหน้าตัวเองก่อนทดสอบกล้อง
- INT201-001 เรียน**ห้อง 4303 (ห้องที่มีกล้อง)** ทุกวันอังคาร 13:00–16:00 · 4 คาบที่ผ่านมามีผลเช็คชื่อให้ dashboard · อยากได้คาบ "ตอนนี้" ไว้ทดสอบกล้อง: login อาจารย์ / admin → หน้าห้อง 4303 → ปุ่ม **"เริ่มคาบตอนนี้"** (จบด้วยปุ่ม "จบคาบ") · หรือรัน `backend/db/03_demo_live_session.sql` (`./dev.sh db` รันให้แล้ว)
- รายละเอียดข้อมูล: `backend/db/README.md`


| คำสั่ง | ทำอะไร |
|---|---|
| `./dev.sh db --reset` | ลบฐานข้อมูลแล้วสร้างใหม่ทั้งหมด (เมื่อ schema เปลี่ยน · ถามยืนยันก่อน) |
| `./dev.sh backend` / `./dev.sh frontend` | รันทีละตัว (เช่นรัน backend จาก IDE แล้วใช้ `./dev.sh frontend`) |
| `BACKEND_PORT=8082 FRONTEND_PORT=5174 ./dev.sh` | port ชนกับโปรแกรมอื่น |
| `PGPORT=5433 ./dev.sh db` | PostgreSQL ไม่ได้อยู่ที่ `localhost:5432` (แล้วใส่ `DB_URL` ใน `backend/.env.local`) |

ไม่รัน backend เลย: ใส่ `VITE_MOCK_MODE=all` ใน `frontend/.env.development.local` (mock เฉพาะ API 5.1–5.11 · ดู `frontend/README.md`)

### วิธีรัน test

| ลำดับ | คำสั่ง | ตรวจอะไร | ต้องเปิดอะไรไว้ |
|---|---|---|---|
| 1 | `./dev.sh test` | backend compile · frontend unit test (Vitest) · lint · format · build ทั้ง prod / uat | ไม่ต้อง |
| 2 | `./dev.sh db` | schema + ข้อมูล mock ถูกต้อง (`backend/db/test/assertions.sql`) — **ล้างข้อมูลกลับเป็นชุดเดิม** | PostgreSQL |
| 3 | `./dev.sh` (terminal 1) แล้ว `./dev.sh smoke` (terminal 2) | ยิง API จริง 15 ข้อ: login, สิทธิ์ (401 / 403), Dashboard, Room (B1–B4) รวม SSE, `/api/internal` · อ่านอย่างเดียว ไม่แก้ข้อมูล | PostgreSQL + backend |
| 4 | เปิด `http://localhost:5173` | ลองใช้ผ่านหน้าเว็บด้วยบัญชีทดสอบด้านบน | PostgreSQL + backend + frontend |
| 5 | `cd ai-service && .venv/bin/python -m pytest tests` (ติดตั้งครั้งแรกดู `ai-service/README.md`) | ai-service: ถอดรหัส embedding, จับคู่, ตัวอ่านกล้อง (ใช้โมเดลปลอม) | Python 3.12 · ไม่ต้องมีโมเดล |

- ข้อ 1–3 จบด้วย `✓ ผ่านทั้งหมด` ถ้าไม่ผ่านจะแสดงรายละเอียดและ exit code ≠ 0 (ใช้ใน script / CI ได้)
- backend ยังไม่มี unit test ของตัวเอง — ข้อ 1 ตรวจแค่ว่า compile ได้ ข้อ 3 ตรวจการทำงานจริง
- ทดสอบที่แก้ข้อมูล (B5 สร้าง/แก้/ลบรายวิชา, B6 สวิตช์กล้อง, B7 ถอนความยินยอม, B8 เช็คชื่อด้วยมือ, กล้องส่งผลเข้า `/api/internal/rooms/{roomId}/sightings`) ทำด้วยมือตามตัวอย่าง request ใน `backend/README.md` แล้ว `./dev.sh db` เพื่อล้างกลับ
- บน GitHub: workflow `database` (เมื่อแก้ `backend/db/**`) และ `frontend` (เมื่อแก้ `frontend/**`) รันให้อัตโนมัติทุก PR · ai-service ยังไม่มี workflow และ `./dev.sh test` ยังไม่รัน pytest (ต้องรันข้อ 5 เอง)

### สร้างฐานข้อมูลด้วย DBeaver แทน `./dev.sh db`

1. **Preferences → Editors → SQL Editor → SQL Processing** → *Blank line is statement delimiter* = **Never** และเปิด Auto-commit
2. connection PostgreSQL `localhost:5432` database `postgres` user `postgres` → เปิด `backend/db/00_create_database.sql` → **Alt+X** (Execute SQL Script)
3. สร้าง connection ใหม่ database **`bangmod_attendance`** → รันทีละไฟล์ด้วย **Alt+X**: `01_schema.sql` → `02_seed_mock.sql` → `03_demo_live_session.sql` → `test/assertions.sql` (ต้องเห็น `All database checks passed` ในแท็บ Output)

| อาการ | วิธีแก้ |
|---|---|
| `CREATE DATABASE cannot run inside a transaction block` | เปิด Auto-commit |
| `unterminated dollar-quoted string` | ข้อ 1 ยังไม่ได้ตั้ง *Never* |
| `relation "users" does not exist` | รันใน connection `postgres` แทน `bangmod_attendance` |

## สมาชิก

- นายจักรพงษ์ สุขสมบรูณ์ — 66130500013
- นายทัตพล สีล้ง — 66130500036
- นายธรรมสรณ์ ฉัตรชยานุสรณ — 66130500041

อาจารย์ที่ปรึกษา: อ.มนตรี สุภัททธรรม

## บันทึกการทำงาน (Changelog)

### 2026-11-26 — ข้อมูลเดโม 5 วิชา (DB)
- **ทำอะไร:** `backend/db/02_seed_mock.sql` มี 5 วิชา (INT201–INT205) 9–13 คนต่อวิชา · นักศึกษา 17 คน (ทีม 3 + `66130500288` / `66130500099` ชื่อ placeholder + dummy 12 คน `661399000xx`) · ห้องกล้อง 4303 มี INT201 อังคาร / INT202 ศุกร์ (ตรงเวลา 10 นาที) / INT203 จันทร์ (ตรงเวลา 30 นาที) ไม่ชนกัน · `03` ไม่สร้างคาบเดโมถ้าห้อง 4303 มีคาบวิชาอื่นอยู่ · `dev.sh`: smoke เลือกห้องที่มีกล้อง และรายชื่อบัญชีตอนเปิดตรงข้อมูลใหม่
- **ไฟล์ที่เกี่ยวข้อง:** `backend/db/02_seed_mock.sql`, `backend/db/03_demo_live_session.sql`, `backend/db/test/assertions.sql`, `backend/db/README.md`, `dev.sh`, `README.md`
- **เหตุผล:** เตรียมเดโมส่งโครงงาน — แต่ละวิชามีรายชื่อนักศึกษา และสาธิตกติกาสายที่ต่างกันตามวิชาได้
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** หัวข้อ "บัญชีทดสอบ" ด้านบน (ทีม / INT201-001) ยังเป็นข้อมูลชุดเก่า — ส่งข้อความที่เสนอให้ docs ผ่าน coordinator แล้ว · รายละเอียดอยู่ใน `backend/db/README.md` · commit แก้ `backend/db/` → CD uat หยุดจนกว่าจะ reset uat แล้ว `ALLOW_DB_CHANGE=1`

### 2026-11-26 — backend: เพิ่ม / ถอนนักศึกษาในกลุ่มเรียน (E2)
- **ทำอะไร:** API รายชื่อนักศึกษาในกลุ่ม, เพิ่มเอง (รหัส / email · สร้างบัญชีให้ได้ใน local / uat), ถอน (soft), นำเข้าไฟล์ CSV / TXT / XLSX พร้อม template และ dryRun
- **ไฟล์ที่เกี่ยวข้อง:** `backend/src/main/java/com/faculty/attendance/enrollment/*`, `backend/src/main/resources/application*.yml`, `backend/README.md`
- **เหตุผล:** ป้อนข้อมูลได้ 2 วิธี (ป้อนเอง + นำเข้าไฟล์) ตามที่อาจารย์ที่ปรึกษาขอ
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ไม่แก้ DB · uat ต้องตั้ง env `NEW_STUDENT_PASSWORD` ถึงจะสร้างบัญชีใหม่ได้ (ห้ามตั้งใน prod) · FE ยังไม่ได้ทำหน้าจอ

### 2026-11-25 — frontend: หน้า "คาบเรียน" (D3)
- **ทำอะไร:** หน้าใหม่ "คาบเรียน" ต่อกลุ่มเรียน (`/courses/:courseId/sections/:sectionId/sessions`) — รายการคาบจริง + ตัวกรอง + ป้าย (เลื่อนจาก / ชดเชยคาบ / คาบพิเศษ / ยกเลิก) + ปุ่มตาม `allowedActions`: แก้ / เลื่อน, ยกเลิก (+ สร้างคาบชดเชยใน dialog เดียว), คืนคาบ, เพิ่มคาบชดเชย / คาบพิเศษ, ลิงก์ไปปรับเวลาที่หน้าห้อง / รายงานคาบ · หน้าแก้ไขรายวิชาบอกขอบเขตของการแก้ตาราง + แสดง `overriddenSessionsKept` · หน้าห้องแสดง "คาบนี้ถูกยกเลิก" เมื่อคาบถูกยกเลิกระหว่างเรียน (SSE) · เมนูจัดการคาบไม่ล้นจอ
- **ไฟล์ที่เกี่ยวข้อง:** `frontend/src/features/course-management/**`, `frontend/src/features/room-monitor/pages/RoomLivePage.vue`, `frontend/src/entities/attendance/components/SessionAdjustMenu.vue`, `frontend/src/app/router/routes.js`, `frontend/README.md`
- **เหตุผล:** ส่วน FE ของฟีเจอร์ "คาบเรียน" (ต่อจาก D1 DB / D2 BE)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** push พร้อม D1 / D2 · ต้องรัน `06_class_session_management.sql` บน uat ก่อน (ดูรายการ D1)

### 2026-11-25 — backend: API คาบเรียน (D2)
- **ทำอะไร:** รายการคาบของกลุ่มเรียน + ปุ่มที่ทำได้ (`allowedActions`), เลื่อน / แก้คาบที่ยังไม่เริ่ม, ยกเลิก (+ ชดเชย), เพิ่มคาบชดเชย / คาบพิเศษ, คืนคาบที่ยกเลิก · แก้ตารางรายวิชาแล้วคาบที่แก้รายคาบไม่หาย / ไม่ซ้ำ (`overriddenSessionsKept`)
- **ไฟล์ที่เกี่ยวข้อง:** `backend/src/main/java/com/faculty/attendance/attendance/ClassSession*.java`, `backend/src/main/java/com/faculty/attendance/attendance/SessionControl*.java`, `backend/src/main/java/com/faculty/attendance/course/*`, `backend/README.md`
- **เหตุผล:** แยกคาบจริงรายวันออกจากแม่แบบตารางเรียน (ต่อจาก D1)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** push พร้อม D1 / D3 · CD บน uat จะหยุด (D1 แก้ `backend/db/`) → รัน SQL บน uat ตาม `backend/db/README.md` แล้ว `ALLOW_DB_CHANGE=1 ~/bangmod-cd/bin/deploy.sh uat`

### 2026-11-25 — คาบเรียน (จัดการรายคาบ): DB + data layer (D1)
- **ทำอะไร:** `class_sessions` มีคอลัมน์สำหรับแก้รายคาบ (`is_overridden`, `original_date`, `makeup_for_session_id`, `is_extra`, `cancel_reason`) · สคริปต์อัปเดต DB เดิม `backend/db/06_class_session_management.sql` · `regenerateSessions` ไม่ลบ / สร้างซ้ำคาบที่แก้รายคาบแล้ว · `ClassSessionRepository` ใหม่สำหรับ D2 · เอกสาร DB อัปเดตแล้ว
- **ไฟล์ที่เกี่ยวข้อง:** `backend/db/`, `backend/src/main/java/.../course/CourseRepository.java`, `backend/src/main/java/.../attendance/ClassSessionRepository.java`, `docs/database/database-design.md`
- **เหตุผล:** ส่วนแรก (D1) ของฟีเจอร์ "คาบเรียน" (เลื่อน / ยกเลิก / คาบชดเชย) — ต่อด้วย D2 (BE) และ D3 (FE)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ⚠️ **commit นี้แก้ `backend/db/` → CD บน uat จะหยุด** จนกว่าจะรัน `05` (ถ้ายังไม่ได้รัน) และ `06` บน uat แล้ว `ALLOW_DB_CHANGE=1 ~/bangmod-cd/bin/deploy.sh uat` (คำสั่งใน `backend/db/README.md`) · prod: รัน `05` + `06` ก่อน deploy prod ครั้งถัดไป · ยังไม่ push (รอ push พร้อม D2)

### 2026-11-25 — frontend: นำเข้าผลเช็คชื่อจากไฟล์ (C3)
- **ทำอะไร:** ปุ่ม "นำเข้าผลเช็คชื่อ" ในหน้าห้องและรายงานคาบ → wizard 4 ขั้น (เลือกแบบ template / ไฟล์อื่น → เลือกคอลัมน์เอง ไม่เดาให้ → ดูผลก่อน `dryRun` พร้อมตาราง error รายแถว / ขัดแย้ง / จับคู่ไม่ได้ → ยืนยัน) · ประวัติการนำเข้า + รายละเอียดรายแถวในรายงานคาบ · ที่มา "นำเข้า (Teams)" ฯลฯ ในรายชื่อ
- **ไฟล์ที่เกี่ยวข้อง:** `frontend/src/entities/attendance/**`, `frontend/src/features/session-report/**`, `frontend/src/features/room-monitor/**`, `frontend/src/shared/api/*`, `frontend/src/shared/components/{BaseDialog,BaseSelect}.vue`, `frontend/README.md`
- **เหตุผล:** ใช้ API นำเข้าผลเช็คชื่อของ backend (C2)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** รายละเอียดและผลทดสอบใน `frontend/README.md`

### 2026-11-25 — backend: API นำเข้าผลเช็คชื่อจากไฟล์ (C2)
- **ทำอะไร:** ดาวน์โหลด template, ตรวจหัวตาราง (`inspect`), นำเข้าแบบ `template` / `mapped` (Teams / Zoom / Google Form / ใบเซ็นชื่อ) พร้อมดูผลก่อนบันทึก (`dryRun`) และ conflict กับผลของกล้อง / อาจารย์ · ประวัติการนำเข้า + รายละเอียดรายแถว
- **ไฟล์ที่เกี่ยวข้อง:** `backend/pom.xml`, `backend/src/main/java/com/faculty/attendance/attendance/*Import*`, `backend/src/main/java/com/faculty/attendance/common/*`, `backend/README.md`
- **เหตุผล:** ให้นำผลเช็คชื่อจากแหล่งอื่นเข้าระบบได้โดยตรวจได้ย้อนหลัง (ต่อจาก C1 ที่เพิ่มตาราง)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** push พร้อม C1 แล้ว CD บน uat จะหยุด (C1 แก้ `backend/db/`) — รัน `05_attendance_import.sql` บน uat ตาม `backend/db/README.md` แล้ว `ALLOW_DB_CHANGE=1 ~/bangmod-cd/bin/deploy.sh uat` · FE (C3) ยังไม่ได้ทำ

### 2026-11-25 — นำเข้าผลเช็คชื่อจากไฟล์: DB + data layer (C1)
- **ทำอะไร:** ตารางใหม่ `attendance_imports` / `attendance_import_rows` + `attendance_records.source = import` / `import_id` · สคริปต์อัปเดต DB เดิม `backend/db/05_attendance_import.sql` (รันซ้ำได้ ไม่ลบข้อมูล) · backend: `AttendanceImportRepository` + แถว import ไม่ถูกกล้องทับ / อาจารย์แก้ทับได้ · session attendance คืน `importedFrom` · เอกสาร DB อัปเดตแล้ว
- **ไฟล์ที่เกี่ยวข้อง:** `backend/db/`, `backend/src/main/java/.../attendance/`, `backend/src/main/java/.../dashboard/`, `docs/database/database-design.md`
- **เหตุผล:** ส่วนแรก (C1) ของฟีเจอร์นำเข้าผลเช็คชื่อ — ต่อด้วย C2 (BE) และ C3 (FE)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ⚠️ **commit นี้แก้ `backend/db/` → CD บน uat จะหยุด** จนกว่าจะรัน `05_attendance_import.sql` บน uat แล้วสั่ง `ALLOW_DB_CHANGE=1 ~/bangmod-cd/bin/deploy.sh uat` (คำสั่งใน `backend/db/README.md`) · prod: รัน `05` ก่อน deploy prod ครั้งถัดไป · ยังไม่ push (รอ push พร้อม C2)

### 2026-11-25 — frontend: เมนู "จัดการคาบ" + Preview & Confirm
- **ทำอะไร:** หน้าห้องมีเมนู "จัดการคาบ" (เริ่มนับเวลาใหม่ / ยืดเวลาจบ / ปรับช่วงเช็คชื่อ) · รายงานคาบที่จบไม่เกิน 7 วันมีปุ่ม "แก้เวลาคาบ" · ดูผลกระทบก่อนยืนยัน (`dryRun`) แยกผลดีขึ้น / แย่ลง (เลือกได้) / เปิดให้เช็คชื่อใหม่ · ฟอร์มรายวิชาส่ง `lateUntil` เดิมกลับไป (เดิมถูกล้างเป็น NULL ทุกครั้งที่แก้) และแจ้งคาบที่ไม่ถูกแก้ตามตารางใหม่ (`startedSessionsNotChanged`)
- **ไฟล์ที่เกี่ยวข้อง:** `frontend/src/entities/attendance/**`, `frontend/src/features/room-monitor/pages/RoomLivePage.vue`, `frontend/src/features/session-report/pages/SessionReportPage.vue`, `frontend/src/features/course-management/**`, `frontend/README.md`
- **เหตุผล:** ใช้เส้นปรับเวลาคาบที่ backend เพิ่ม (อาจารย์เข้าสาย เลิกช้า ฝนตก)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** รายละเอียดและผลทดสอบใน `frontend/README.md`

### 2026-11-25 — backend: ปรับเวลาคาบที่เริ่มแล้ว
- **ทำอะไร:** เพิ่ม `POST /api/class-sessions/{id}/reanchor` · `/extend` · `/check-in-window` (บังคับเหตุผล, `dryRun` ดูผลก่อน, คิดผลเช็คชื่อใหม่แบบไม่ลงโทษย้อนหลัง) · แก้บั๊กแก้รายวิชาแล้วคาบวันนี้หายเงียบ · `PUT /api/courses/{id}` บอกคาบที่ไม่ได้เปลี่ยนตาม (`startedSessionsNotChanged`)
- **ไฟล์ที่เกี่ยวข้อง:** `backend/src/main/java/com/faculty/attendance/attendance/SessionAdjust*.java`, `backend/src/main/java/com/faculty/attendance/course/*`, `backend/README.md`
- **เหตุผล:** admin / อาจารย์แก้เวลาคาบที่เริ่มไปแล้วไม่ได้ (อาจารย์เข้าสาย เลิกช้า ฝนตก)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ไม่แก้ DB (CD deploy uat ได้ปกติ) · FE ยังต้องทำหน้าจอเรียกเส้นเหล่านี้ · รายละเอียดใน `backend/README.md`

### 2026-11-25 — ปรับ README ทุกส่วนให้ตรงกับโค้ดปัจจุบัน
- **ทำอะไร:** ตารางโครงสร้าง repo อัปเดตสถานะ frontend / ai-service / devops · บัญชีทดสอบบอกวิธีเริ่มคาบจากหน้าห้อง · วิธีรัน test เพิ่ม pytest ของ ai-service (ข้อ 5) · รวมหัวข้อ changelog 2026-11-25 ที่ซ้ำกัน · backend ย้ายเส้นเริ่ม / จบคาบไปตาราง API หลัก (ใช้ JWT ไม่ใช่ `X-Internal-Key`) · frontend / database-design เพิ่มเส้นเริ่ม / จบคาบในตารางหน้าจอ · devops แก้ `MEDIA_BASE_URL`, แผนภาพ, ข้อความที่บอกว่ายังไม่มีตัวจับคู่ realtime / กล้องใช้ไม่ได้บน HTTP
- **ไฟล์ที่เกี่ยวข้อง:** `README.md`, `backend/README.md`, `frontend/README.md`, `frontend/tests/e2e/README.md`, `devops/README.md`, `docs/README.md`, `docs/database/database-design.md`
- **เหตุผล:** README หลายส่วนล้าสมัยหรือขัดกันเอง (เช่น ai-service "รอย้ายโค้ดเข้ามา" ทั้งที่มีโค้ดแล้ว)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** devops / frontend แก้ "ไม่ต้องตั้ง CORS" เป็นคำอธิบาย `CORS_ORIGINS` (ไม่มี preflight แต่ backend ตรวจ `Origin` → เปิดผ่าน bscit ต้องใส่โดเมน bscit · prod ยังไม่ได้ตั้ง) · แก้เฉพาะเอกสาร ไม่แตะโค้ด

### 2026-11-25 — เริ่ม / จบคาบจากหน้าห้อง
- **ทำอะไร:** หน้าห้องมีปุ่ม "เริ่มคาบตอนนี้" (เลือกกลุ่มเรียน) และ "จบคาบ" · backend เพิ่ม `POST /api/rooms/{roomId}/sessions` และ `POST /api/class-sessions/{id}/end` · ไม่ต้องรัน `03_demo_live_session.sql` เองแล้ว
- **ไฟล์ที่เกี่ยวข้อง:** `backend/src/main/java/com/faculty/attendance/attendance/SessionControl*.java`, `backend/.../room/RoomRepository.java`, `backend/.../attendance/AttendanceRepository.java`, `frontend/src/features/room-monitor/**`
- **เหตุผล:** ผู้ใช้ต้องการเริ่มคาบจากหน้าเว็บ

### 2026-11-25 — แก้กล้องไม่เห็นใบหน้าที่ลงทะเบียนจริง
- **ทำอะไร:** backend gallery เทียบ `model_name` แบบไม่สนตัวพิมพ์ · ai-service ล้าง `lastError` หลังเปิดภาพได้
- **ไฟล์ที่เกี่ยวข้อง:** `backend/src/main/java/com/faculty/attendance/internal/CameraFeedRepository.java`, `ai-service/camera_worker.py`, `ai-service/tests/test_camera_worker.py`
- **เหตุผล:** ทดสอบบน uat: ลงทะเบียนใบหน้าแล้วแต่ gallery 0 คน (เก็บ `"SFace"` แต่ค้นหา `'sface'`) กล้องจึงไม่เคยเช็คชื่อ

### 2026-11-25 — ข้อมูล mock ชุดเล็กสำหรับทดสอบจริง
- **ทำอะไร:** `backend/db/02_seed_mock.sql` เหลือ admin 1 · อาจารย์ 1 · ทีม 3 คน · INT201-001 ห้อง 4303 (ห้องกล้อง) · 4 คาบที่ผ่านมามีผลเช็คชื่อ (วันที่คำนวณจากวันที่รัน) · `03_demo_live_session.sql` สร้างคาบ "ตอนนี้" ในห้อง 4303 โดยยังไม่มีใครเช็คชื่อ · `dev.sh smoke` login นักศึกษาด้วย 66130500013 · หัวข้อ "บัญชีทดสอบ" ด้านบนอัปเดตตาม
- **ไฟล์ที่เกี่ยวข้อง:** `backend/db/02_seed_mock.sql`, `backend/db/03_demo_live_session.sql`, `backend/db/test/assertions.sql`, `backend/db/README.md`, `dev.sh`, `README.md`, `devops/README.md`
- **เหตุผล:** ผู้ใช้ต้องการทดสอบจริง: ลด mock เหลือบัญชีทีม + admin + อาจารย์ และลดข้อมูล dashboard · ข้อมูลเดิมไม่มีคาบในห้องกล้อง (ทีมอยู่ห้อง 4302, คาบ mock หมด 26 พ.ย.) กล้องจึงไม่เคยเช็คชื่อ
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** CD จะหยุด (แก้ `backend/db/`) — ผู้ใช้ต้องใส่ข้อมูลใน uat เองแล้วสั่ง `ALLOW_DB_CHANGE=1 deploy.sh uat` (คำสั่งอยู่ใน `backend/db/README.md`) · **ล้างข้อมูล uat ทั้งหมด** รวมใบหน้าที่ลงทะเบียนไว้ · prod ไม่เกี่ยว

### 2026-11-25 — หน้าห้องอัปเดตข้อมูลจริงผ่าน bscit
- **ทำอะไร:** NGINX ของ frontend ส่ง `X-Accel-Buffering: no` ใน SSE ให้ bscit ไม่กัก event · หน้าห้อง poll รายชื่อทุก 15 วินาทีเมื่อ SSE ไม่ต่อ
- **ไฟล์ที่เกี่ยวข้อง:** `frontend/nginx.conf`, `frontend/src/features/room-monitor/pages/RoomLivePage.vue`, `devops/README.md`
- **เหตุผล:** ทดสอบผ่าน bscit แล้วข้อมูลไม่ dynamic (ป้ายค้าง "กำลังเชื่อมต่อ")
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ยังไม่ได้ยืนยันบน bscit จริง · เช็คชื่อด้วยกล้องยังต้องมีคาบในห้องกล้อง (4303) ที่ทีมลงทะเบียน — mock data ปัจจุบันไม่มี (ทีมอยู่ INT201-001 ห้อง 4302, คาบ mock หมด 26 พ.ย.)

### 2026-11-25 — เช็คชื่อด้วยกล้องจริง (ai-service อ่าน RTSP จาก MediaMTX)
- **ทำอะไร:** backend เพิ่ม `GET /api/internal/cameras` และ `/rooms/{roomId}/gallery` · ai-service เพิ่มตัวอ่านกล้อง (`camera_worker.py`, `gallery.py`, `vision.py`) ส่ง heartbeat / จำนวนใบหน้า / ผลจับคู่ไป backend · K3s เปิด `CAMERA_WORKER=true` และส่ง `FACE_EMBEDDING_KEY` ให้ ai-service
- **ไฟล์ที่เกี่ยวข้อง:** `backend/src/main/java/com/faculty/attendance/internal/*`, `ai-service/*`, `devops/k8s/base/ai-service.yaml`
- **เหตุผล:** ภาพกล้อง Pi มาถึงระบบแล้ว ต้องใช้เช็คชื่อและสถานะกล้องจริง
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบครบสายในเครื่องด้วยโมเดลปลอม (ได้เช็คชื่อ on_time จริงใน DB) · ยังต้องทดสอบโมเดลจริง + RTSP จริงบน uat

### 2026-11-24 — ภาพสดผ่าน HTTPS (WHEP ผ่าน NGINX ของ frontend)
- **ทำอะไร:** `frontend/nginx.conf` ส่ง `<base>media/*` ไป MediaMTX · overlay uat / prod ตั้ง `MEDIA_BASE_URL=<VITE_BASE>media`
- **ไฟล์ที่เกี่ยวข้อง:** `frontend/nginx.conf`, `frontend/Dockerfile`, `devops/k8s/overlays/*/kustomization.yaml`, `devops/k8s/mediamtx/*`
- **เหตุผล:** หน้าเว็บผ่าน bscit (HTTPS) เรียก WHEP แบบ HTTP ไม่ได้ (mixed content)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ยังไม่มีกล้องจริง · ตัวภาพ (UDP) ดูได้เฉพาะในคณะ / VPN

### 2026-11-24 — MediaMTX + RTSPS ผ่าน Tailscale
- **ทำอะไร:** เพิ่ม MediaMTX ใน `devops/k8s/mediamtx/` (namespace `media` ใช้ร่วม uat / prod) ดึงภาพกล้องที่อยู่คนละ network ด้วย `rtsps://` ผ่าน Tailscale บน VM · `.gitignore` เพิ่ม `devops/k8s/mediamtx/mediamtx.yml` (มีรหัสกล้อง)
- **ไฟล์ที่เกี่ยวข้อง:** `devops/k8s/mediamtx/*`, `.gitignore`, `devops/README.md`
- **เหตุผล:** เชื่อมกล้องจาก network อื่นแบบเข้ารหัส
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ยังไม่ได้ apply บน VM · ขั้นตอนอยู่ใน `devops/README.md` หัวข้อ "MediaMTX + RTSPS ผ่าน Tailscale"

### 2026-11-24 — หน้า Login แสดงบัญชีทดสอบจาก DB แทนรายชื่อที่เขียนตายตัว
- **ทำอะไร:** backend เพิ่ม `GET /api/test-accounts` (เปิดด้วย `TEST_ACCOUNTS_ENABLED`, local + uat เท่านั้น) · frontend โหลดรายชื่อนี้แล้วจัดกลุ่มตามสถานะใบหน้า / ความยินยอม · uat overlay เปิด flag
- **ไฟล์ที่เกี่ยวข้อง:** `backend/src/main/java/com/faculty/attendance/auth/TestAccountsController.java`, `backend/src/main/java/com/faculty/attendance/auth/SecurityConfig.java`, `backend/src/main/resources/application*.yml`, `frontend/src/features/auth/*`, `devops/k8s/overlays/uat/kustomization.yaml`
- **เหตุผล:** ผู้ใช้ขอให้เอารายชื่อ mock ออกจากหน้า Login และใช้ข้อมูลใน DB จริง
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** prod ไม่เปิด endpoint นี้ (404) และ frontend prod ไม่แสดงแผงนี้อยู่แล้ว

### 2026-11-24 — uat เปิดผ่าน HTTPS ของคณะที่ `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/uat/`
- **ทำอะไร:** แยก path ที่เบราว์เซอร์เห็น (`VITE_BASE`) กับ path ที่มาถึง VM (`APP_PATH`) ใน `frontend/.env.<mode>` · `frontend/Dockerfile` และ `devops/cd/deploy.sh` ใช้ `APP_PATH` · uat: เบราว์เซอร์ `/capstone26/cp26ms3/uat/`, VM `/uat/` (แทน `/capstone26/uat/` ที่ย้ายไปก่อนหน้า)
- **ไฟล์ที่เกี่ยวข้อง:** `frontend/.env.uat`, `frontend/Dockerfile`, `devops/cd/deploy.sh`, `devops/k8s/overlays/uat/kustomization.yaml`, `devops/README.md`, `frontend/README.md`, `CLAUDE.md`
- **เหตุผล:** คณะ forward `/capstone26/cp26ms3/*` มาที่ VM แบบตัด prefix — HTTPS ทำให้กล้องสดในหน้าลงทะเบียนใบหน้าใช้ได้
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** เปิดตรงที่ VM ใน browser หน้าจะขาว ต้องเปิดผ่าน bscit · prod ตั้งค่าตามหัวข้อ "forward จาก bscit" ใน `devops/README.md` ตอน deploy ครั้งแรก

### 2026-11-24 — uat ย้ายไป `/capstone26/uat/` เพื่อรับ forward จาก bscit (HTTPS)
- **ทำอะไร:** (1) path ของเว็บมาจาก `VITE_BASE` ใน `frontend/.env.<mode>` ที่เดียว — `frontend/Dockerfile` ใส่ลง `nginx.conf` เอง, `devops/cd/deploy.sh` ใช้ตรวจผลและหยุดก่อน apply ถ้า Ingress ไม่ตรง (2) uat ย้ายจาก `/uat/` ไป `/capstone26/uat/`
- **ไฟล์ที่เกี่ยวข้อง:** `frontend/.env.uat`, `frontend/Dockerfile`, `devops/cd/deploy.sh`, `devops/k8s/overlays/uat/kustomization.yaml`, `devops/k8s/build-images.sh`, `devops/README.md`, `frontend/README.md`, `CLAUDE.md`
- **เหตุผล:** ให้เปิด uat ที่ `https://bscit.sit.kmutt.ac.th/capstone26/uat/` — HTTPS ทำให้กล้องสดในเบราว์เซอร์ใช้ได้
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** push 2 รอบ (รอบแรกให้ CD บน VM อัปเดต `deploy.sh` ตัวเองก่อน) · URL เดิม `/uat/` ใช้ไม่ได้แล้ว · ต้องขอผู้ดูแล bscit ตั้ง forward (ตัวอย่างใน `devops/README.md`)

### 2026-11-23 — เอาข้อยืนยันอายุ 20 ปีออกจากหน้ายินยอม (ลงทะเบียนใบหน้า)
- **ทำอะไร:** หน้ายินยอมเหลือ checkbox ยินยอมข้อเดียว ไม่ถามอายุ 20 ปี / บรรลุนิติภาวะ และไม่มีข้อความเตือนเรื่องผู้ปกครอง · FE ไม่ส่ง `ageConfirmed` (บันทึกเป็น `false`) · อัปเดตตารางบัญชีทดสอบ persona อายุไม่ถึง 20
- **ไฟล์ที่เกี่ยวข้อง:** `frontend/src/features/face-registration/**`, `README.md`, `frontend/README.md`, `backend/db/README.md`
- **เหตุผล:** ผู้ใช้ขอเอาเนื้อหาเรื่องอายุออกจากหน้าลงทะเบียน
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** แก้เฉพาะ Front-End (ไม่แตะ `backend/db/` → CD deploy uat เองได้) · ข้อความยินยอมยังเป็น `v1`

### 2026-11-23 — บัญชีสมาชิกทีม + แก้หน้าลงทะเบียนใบหน้าเปิดกล้องไม่ได้บน HTTP
- **ทำอะไร:** (1) เพิ่มบัญชีนักศึกษาของสมาชิกทีม 3 คน (`66130500013`, `66130500036`, `66130500041` · `Password@123` · ยังไม่ลงทะเบียนใบหน้า · INT201-001) ใน seed + สคริปต์ `04_add_team_students.sql` สำหรับ uat ที่มีข้อมูลอยู่แล้ว · (2) หน้าลงทะเบียนใบหน้า: uat เป็น HTTP เบราว์เซอร์จึงไม่ให้เปิดกล้องสด → บนมือถือมีปุ่ม "ถ่ายรูปด้วยกล้องมือถือ" (เปิดแอปกล้องได้แม้เป็น HTTP) บนคอมแสดงคำอธิบายให้เลือกรูปจากเครื่อง และย่อรูปทุกรูปเหลือ ≤ 1600 px JPEG (ตัด EXIF / GPS) ก่อนส่ง
- **ไฟล์ที่เกี่ยวข้อง:** `backend/db/02_seed_mock.sql`, `backend/db/04_add_team_students.sql`, `backend/db/test/assertions.sql`, `frontend/src/features/face-registration/**`, `frontend/src/features/auth/test-accounts.js`, `README.md`, `backend/db/README.md`, `frontend/README.md`
- **เหตุผล:** ให้ทีมทดสอบลงทะเบียนใบหน้าด้วยหน้าตัวเองบน uat ซึ่งยังไม่มี HTTPS
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทางแก้ถาวรคือเปิด HTTPS ให้ `cp26ms3.sit.kmutt.ac.th` (ขอใบรับรองจากฝ่าย IT คณะ) แล้วกล้องสดในหน้าเว็บจะใช้ได้ทั้งคอมและมือถือ · commit นี้แก้ `backend/db/` → CD หยุด: รัน `04` บน uat แล้ว `ALLOW_DB_CHANGE=1 ~/bangmod-cd/bin/deploy.sh uat` (ขั้นตอนใน `backend/db/README.md`) · การลงทะเบียนใบหน้าจริงต้องติดตั้งโมเดล AI บน VM ก่อน

### 2026-11-22 — ติดตั้งโมเดล AI
- **ทำอะไร:** คู่มือติดตั้งโมเดลใน `devops/README.md` และสคริปต์ `devops/models/fetch-models.sh` (ดาวน์โหลด + ตรวจ SHA-256) · เลือกโมเดล YOLO face ที่มี 5 จุดบนหน้า และทดสอบกับโค้ด `ai-service` จริงแล้ว
- **ไฟล์ที่เกี่ยวข้อง:** `devops/README.md`, `devops/models/fetch-models.sh`
- **เหตุผล:** ai-service บน VM ยังไม่ขึ้นเพราะไม่มีโมเดล → ลงทะเบียนใบหน้าไม่ได้
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ต้องรันบน VM (ใช้ sudo 1 คำสั่งสร้างโฟลเดอร์) · ตัวจับคู่ใบหน้าจากกล้องยังไม่ได้ทำ

### 2026-11-22 — CD: push ขึ้น `Dev` แล้ว uat deploy เอง
- **ทำอะไร:** เพิ่มระบบ deploy อัตโนมัติบน VM (`devops/cd/`): cron ดึง `Dev` ทุก 3 นาที → build → registry ในเครื่อง → apply → ตรวจผล → rollback ถ้าพัง · prod สั่งเองพร้อมยืนยัน · กฎใน `CLAUDE.md` หัวข้อ CD
- **ไฟล์ที่เกี่ยวข้อง:** `devops/cd/*`, `devops/README.md`, `CLAUDE.md`, `.gitignore`
- **เหตุผล:** ไม่ต้อง deploy uat ด้วยมือทุกครั้ง
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ติดตั้งบน VM ตาม `devops/README.md` › "CD: deploy อัตโนมัติ" · ยังไม่ได้รันบน VM จริง

### 2026-11-22 — กฎ git: push ขึ้น branch `Dev` เท่านั้น
- **ทำอะไร:** สร้าง branch `Dev` จาก `main` (`ed23377`) และเพิ่มหัวข้อ "กฎ git" ใน `CLAUDE.md`: push ได้เฉพาะ `Dev`, ห้าม push `main` / ห้ามสร้าง branch ใหม่บน remote, merge `main` เข้า `Dev` เมื่อตามไม่ทัน, ห้าม force push, ขึ้น `main` ผ่าน PR `Dev` → `main` เมื่อผู้ใช้สั่ง, บน VM ยังห้าม commit / push
- **ไฟล์ที่เกี่ยวข้อง:** `CLAUDE.md`, `README.md`
- **เหตุผล:** ให้งานทุกชิ้นรวมอยู่ที่ `Dev` ก่อนขึ้น `main` และไม่มี branch งานกระจายบน remote
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** branch งานเก่าบน remote (`feat/database-r1`, `docs/deploy-log`, `feat/student-test-accounts`, `claude/amazing-cannon-taa1h3`) merge เข้า `main` แล้ว ลบได้ถ้าไม่ใช้ · ถ้าต้องการกัน push `main` จริง ให้ตั้ง branch protection บน GitHub
### 2026-11-22 — บัญชีทดสอบนักศึกษาครบทุกสถานะ
- **ทำอะไร:** เพิ่มนักศึกษาทดสอบ 5 คน (ต้องยินยอมใหม่, ยินยอมแต่ไม่มีใบหน้า, อายุไม่ถึง 20, ไม่มีวิชาเทอมนี้, ลาออก) ในข้อมูล mock · หน้า Login แสดงบัญชีทดสอบให้กดเลือกบนเครื่องและ uat (ไม่มีใน prod) · ตารางบัญชีทดสอบในหัวข้อ "ทดสอบบนเครื่อง"
- **ไฟล์ที่เกี่ยวข้อง:** `backend/db/02_seed_mock.sql`, `backend/db/test/assertions.sql`, `frontend/src/features/auth/*`, `frontend/.env.*`
- **เหตุผล:** เดิมทดสอบฟีเจอร์ student ได้ไม่ครบทุกกรณี และหน้า Login ไม่มีบัญชี student ให้เลือกเมื่อต่อ Backend จริง
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ต้อง `./dev.sh db` ใหม่ (local) และ `./devops/k8s/init-db.sh` / รัน 02 ใหม่บน uat ถึงจะมีบัญชีใหม่ · รายละเอียดใน `backend/db/README.md`, `frontend/README.md`

### 2026-11-22 — บันทึกการ deploy uat บน VM
- **ทำอะไร:** บันทึกว่า deploy uat สำเร็จ (http://cp26ms3.sit.kmutt.ac.th/uat/) พร้อมข้อมูล VM, ปัญหาที่เจอและวิธีแก้ · ปรับ `build-images.sh` ให้เช็คสิทธิ์ docker และผล import · `imagePullPolicy: Never` · กฎใน `CLAUDE.md` เรื่อง sudo และการบันทึกหลัง deploy
- **ไฟล์ที่เกี่ยวข้อง:** `devops/README.md`, `devops/k8s/build-images.sh`, `devops/k8s/base/*.yaml`, `CLAUDE.md`
- **เหตุผล:** คนที่ deploy ครั้งต่อไป (รวม prod) จะได้ไม่ติดปัญหาเดิม
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** รายละเอียดใน `devops/README.md`

### 2026-11-22 — ใช้โดเมน `cp26ms3.sit.kmutt.ac.th` บน VM
- **ทำอะไร:** ตั้ง URL ภาพสด (`MEDIA_BASE_URL`) และเอกสาร deploy ให้ใช้โดเมน `cp26ms3.sit.kmutt.ac.th` แทน IP / ค่าที่ต้องแก้เอง
- **ไฟล์ที่เกี่ยวข้อง:** `devops/k8s/overlays/*/kustomization.yaml`, `devops/README.md`, `CLAUDE.md`
- **เหตุผล:** DNS ของคณะชี้โดเมนนี้ไปที่ VM แล้ว
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** เว็บ uat: `http://cp26ms3.sit.kmutt.ac.th/uat/` · HTTPS ยังไม่มี (ดู `devops/README.md`)

### 2026-11-22 — กฎการ deploy ใน `CLAUDE.md`
- **ทำอะไร:** เพิ่มหัวข้อ "กฎการ deploy (K3s บน VM)" ใน `CLAUDE.md`: ตรวจเครื่องก่อน, uat ก่อนแล้วรอยืนยันค่อยทำ prod, ห้าม seed prod, ห้ามลบ namespace / PVC / ข้อมูลโดยไม่ถาม, ห้าม commit จาก VM, จัดการ `secret.env` (โดยเฉพาะ `FACE_EMBEDDING_KEY`), รายการตรวจหลัง deploy และข้อมูลเริ่มต้นของ prod
- **ไฟล์ที่เกี่ยวข้อง:** `CLAUDE.md`, `README.md`
- **เหตุผล:** ให้ Claude Code ทุกเครื่อง (รวม VM) ทำตามขั้นตอนเดียวกันทุกครั้งก่อน deploy และไม่ทำสิ่งที่ย้อนกลับไม่ได้
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ขั้นตอนละเอียดยังอยู่ที่ `devops/README.md` · ถ้าขั้นตอน deploy เปลี่ยน ให้แก้ทั้งสองไฟล์ให้ตรงกัน
### 2026-11-22 — FE ทุกหน้าเรียก API จริง
- **ทำอะไร:** ทำหน้าจอ FE ที่เหลือทั้งหมด (อาจารย์ / admin / นักศึกษา) ให้ดึงข้อมูลจาก Backend จริง รวม SSE, กราฟ, ฟอร์มรายวิชา, ลงทะเบียน/ถอนใบหน้า · Backend เพิ่ม `GET /rooms/options`, `GET /lecturers` · แก้ Vite proxy ที่ทำให้ login ไม่ได้เมื่อเปลี่ยน port
- **ไฟล์ที่เกี่ยวข้อง:** `frontend/src/**`, `frontend/vite.config.js`, `backend/.../course/LookupController.java`
- **เหตุผล:** เดิมทุกหน้ายกเว้น Login เป็น "กำลังพัฒนา" ข้อมูลจึงไม่แสดง
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** รายละเอียดใน `frontend/README.md` (ตาราง หน้าจอ → API) · ภาพสดและลงทะเบียนใบหน้าจริงต้องมี MediaMTX / AI Service

### 2026-11-22 — `./dev.sh test` / `./dev.sh smoke` + คู่มือวิธีรัน test
- **ทำอะไร:** เพิ่มคำสั่ง `./dev.sh test` (backend compile + frontend unit test / lint / format / build ทั้ง 2 mode ในคำสั่งเดียว) และ `./dev.sh smoke` (ยิง API จริง 15 ข้อกับ backend ที่รันอยู่: login, 401 / 403, Dashboard, B1–B4 + SSE, `/api/internal` — อ่านอย่างเดียว) · เพิ่มหัวข้อ "วิธีรัน test" ใน README
- **ไฟล์ที่เกี่ยวข้อง:** `dev.sh`, `README.md`
- **เหตุผล:** ให้ทุกคนในทีมตรวจงานก่อน push ได้ด้วยคำสั่งเดียว และรู้ว่าต้องรัน test อะไรตามลำดับไหน
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบแล้ว: `./dev.sh test` ผ่านครบ 7 ขั้น, `./dev.sh smoke` ผ่าน 15/15 กับ backend + ข้อมูล mock และแจ้งเตือนถูกต้องเมื่อ backend ไม่ได้รัน · backend ยังไม่มี unit test (JUnit) ควรเพิ่มต่อ · smoke ยังไม่ครอบคลุม API ที่แก้ข้อมูล (B5–B8)

### 2026-11-22 — `./dev.sh` + โปรไฟล์ `local` ให้ทุกคนทดสอบบนเครื่องได้ง่าย
- **ทำอะไร:** เพิ่ม `dev.sh` (สร้างฐานข้อมูล + ข้อมูล mock, รัน backend + frontend พร้อมกัน, เปลี่ยน port ได้) · backend มีโปรไฟล์ `local` เป็นค่าเริ่มต้นพร้อม key ทดสอบ จึงไม่ต้องตั้ง env · บน server บังคับโปรไฟล์ `prod` · เพิ่มคู่มือ "ทดสอบบนเครื่อง (local)" รวมวิธีใช้ DBeaver
- **ไฟล์ที่เกี่ยวข้อง:** `dev.sh` (ใหม่), `.gitignore`, `README.md`, `backend/` (ดู `backend/README.md`), `devops/k8s/overlays/*/kustomization.yaml`
- **เหตุผล:** เดิมต้องตั้ง `FACE_EMBEDDING_KEY` เองก่อนรัน ไม่งั้น backend start ไม่ขึ้น และต้องรันหลายคำสั่งกว่าจะทดสอบได้
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบ `./dev.sh db` (สร้างใหม่ / รันซ้ำ / `--reset` / DB ไม่เปิด) และ `./dev.sh` (login ผ่าน FE, Ctrl+C หยุดทั้งคู่) บน macOS แล้ว · ยังไม่ได้ลองบน Windows (ต้องใช้ Git Bash หรือ WSL)

### 2026-11-21 — ลบ mock B1–B8 และเชื่อม FE ↔ BE (local + K3s)
- **ทำอะไร:** FE ลบ mock B1–B8 แล้วต่อ Backend จริงเป็นค่าเริ่มต้น · FE เรียก `/api` แบบ relative ผ่าน proxy (Vite ตอน dev, NGINX ใน container บน VM) · เพิ่ม Dockerfile ของ backend / ai-service และ K3s manifests (`devops/k8s`, namespace prod / uat)
- **ไฟล์ที่เกี่ยวข้อง:** `frontend/`, `backend/Dockerfile`, `backend/src/main/resources/application.yml`, `ai-service/Dockerfile`, `devops/k8s/`, `.gitignore`
- **เหตุผล:** Backend ทำ B1–B8 ครบแล้ว และต้องใช้ได้ทั้งเครื่อง local และบน server VM โดยไม่ต้องแก้โค้ดตามเครื่อง
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** วิธี deploy ดู `devops/README.md` · ยังไม่ได้ apply บน VM จริง

### 2026-11-21 — Backend: API ห้องเรียน / รายวิชา / เช็คชื่อด้วยมือ (B1–B8) + แจ้ง AI ตอนถอนความยินยอม
- **ทำอะไร:** เพิ่ม API ที่ FE ยัง mock อยู่ครบ B1–B8, ช่องทางให้ AI Service ส่งผลกล้องเข้า backend, job สถานะคาบ/ขาด และให้ backend แจ้ง AI Service ลบ embedding เมื่อนักศึกษาถอนความยินยอม
- **ไฟล์ที่เกี่ยวข้อง:** `backend/`, `ai-service/main.py`, `docs/database/database-design.md`
- **เหตุผล:** ให้ FE เลิกใช้ mock ของ B1–B8 ได้ และให้การถอนความยินยอมลบข้อมูลใบหน้าฝั่ง AI ด้วย (PDPA)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** รายละเอียดดู `backend/README.md`, `ai-service/README.md`, `docs/README.md`

### 2026-11-21 — Back-End ตรงกับ Database Design + B7 ถอนความยินยอม
- **ทำอะไร:** merge `feat/database-r1` (schema + เอกสาร DB) เข้ากับโค้ด Back-End แล้วแก้ SQL ทั้งหมดให้ตรง `backend/db/01_schema.sql` / `docs/database/database-design.md` · เข้ารหัส face embedding (AES-GCM) · ลงทะเบียนใหม่ลบชุดเดิมจริง · เพิ่ม B7 `DELETE /api/students/{studentId}/face`
- **ไฟล์ที่เกี่ยวข้อง:** `backend/src/main/**`, `backend/README.md` (รายละเอียดทั้งหมดอยู่ที่นั่น)
- **เหตุผล:** โค้ดเดิมใช้ schema ชุดเก่า รันกับ DB จริงไม่ได้ และบางจุดขัดข้อความยินยอม PDPA v1
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ตั้ง `FACE_EMBEDDING_KEY` · AI Service ต้องถอดรหัส embedding · FE ส่ง `ageConfirmed` · build ด้วย `mvn compile` อีกรอบ

### 2026-11-21 — เพิ่มกฎการบันทึกรายงานใน README.md
- **ทำอะไร:** สร้างไฟล์ `CLAUDE.md` ที่ root เพื่อกำหนดกฎว่าทุกครั้งที่ทำงานใน repo ต้องเขียนรายงานสิ่งที่ทำไว้ใน `README.md` ของแต่ละ Folder
- **ไฟล์ที่เกี่ยวข้อง:** `CLAUDE.md`, `README.md`
- **เหตุผล:** ให้มีบันทึกการเปลี่ยนแปลงของโปรเจกต์อย่างต่อเนื่องและตรวจสอบย้อนหลังได้
