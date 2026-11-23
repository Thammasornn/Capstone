# Capstone — Bangmod Smart Attendance (CP26MS3)

ระบบเช็คชื่อนักศึกษาอัตโนมัติด้วยกล้อง (Face Recognition) · โครงงานจบ IT, SIT KMUTT ปีการศึกษา 2569

## โครงสร้าง repo (monorepo)

| โฟลเดอร์ | เนื้อหา | สถานะ |
|---|---|---|
| [`frontend/`](frontend/) | Vue 3 + Vite (JavaScript) · Tailwind v4 · Pinia · MSW | Release 1 scaffold |
| [`backend/`](backend/) | Spring Boot REST + SSE · PostgreSQL — [`backend/db/`](backend/db/) schema + mock data R1 พร้อมใช้ | API R1 (5.1–5.11, B1–B8) |
| [`ai-service/`](ai-service/) | Python · OpenCV → YOLOv8 → OpenCV SFace | รอย้ายโค้ดเข้ามา |
| [`devops/`](devops/) | K3s manifests (namespace `prod` / `uat`), Ingress, MediaMTX | manifests prod / uat (ยังไม่มี MediaMTX) |
| [`docs/`](docs/) | เอกสารประกอบ — [`docs/database/`](docs/database/) แบบฐานข้อมูล + ER Diagram | — |

```
[Raspberry Pi Camera] --RTSPS--> [MediaMTX] --RTSP--> [AI Service]
[Browser] --HTTPS (REST + SSE)--> [NGINX + Vue] --> [Spring Boot] --> [PostgreSQL]
```

Deploy บน Faculty VM ด้วย K3s: `/prod/` และ `/uat/` แยก namespace

## ทดสอบบนเครื่อง (local)

ต้องมี: **PostgreSQL** ที่เปิดอยู่ (macOS แนะนำ [Postgres.app](https://postgresapp.com) แล้วกด Start), **Java 25 + Maven**, **Node.js** 22.18+ หรือ 24.12+

```bash
./dev.sh db     # ครั้งแรก: สร้างฐานข้อมูล + ข้อมูล mock (รันซ้ำ = ล้างข้อมูลกลับเป็นชุดเดิม)
./dev.sh        # รัน backend + frontend พร้อมกัน → http://localhost:5173 (Ctrl+C หยุดทั้งคู่)
```

ไม่ต้องตั้ง key อะไรเลย — backend ใช้โปรไฟล์ `local` ที่มี key ทดสอบให้ (ดู `backend/README.md`)
### บัญชีทดสอบ (รหัสผ่าน `Password@123` · ใช้ได้บนเครื่องและ uat · **ไม่มีใน prod**)

หน้า Login แสดงบัญชีเหล่านี้ให้กดเลือกได้ (`VITE_SHOW_TEST_ACCOUNTS=true` ใน `.env.development` / `.env.uat`)

- เจ้าหน้าที่: `admin@sit.kmutt.ac.th` (admin) · `supawadee.c@sit.kmutt.ac.th` (อาจารย์ INT102 / INT201 มีคาบกำลังเรียนในห้อง 4302) · `thanakorn.w@sit.kmutt.ac.th` (อาจารย์ INT101)
- นักศึกษา — ครบทุกสถานะ:

| สถานะที่ต้องการทดสอบ | อีเมล (`@st.sit.kmutt.ac.th`) | ใช้ทดสอบอะไร |
|---|---|---|
| ลงทะเบียนใบหน้าแล้ว (ปกติ) | `69130500001` | ทุกหน้า · ปี 1 · 3 วิชา |
| ยังไม่เคยลงทะเบียนใบหน้า | `69130500011` | แถบเตือนหน้าแรก · ลงทะเบียนครั้งแรก · อาจารย์เช็คชื่อด้วยมือ |
| ถอนความยินยอมแล้ว | `68130500005` | สถานะ "ถอนความยินยอมแล้ว" · ลงทะเบียนใหม่ |
| ต้องยินยอมใหม่ (ยินยอมฉบับเก่า `v0`) | `69130599001` | คำเตือน `needsReconsent` ในหน้าจัดการข้อมูลใบหน้า |
| ยินยอมแล้วแต่ยังไม่มีใบหน้า | `69130599002` | ส่งรูปไม่สำเร็จ · ถอนความยินยอมทั้งที่ไม่มีใบหน้า |
| อายุไม่ถึง 20 ปี (`is_adult = false`, B9) | `69130599003` | หน้ายินยอม (ต้องไม่ติ๊กข้อ 20 ปี) · เช็คชื่อด้วยมือ |
| กลุ่มเสี่ยงสูง (ขาด 3–5 ครั้ง) | `69130500004` | นักศึกษาต้องไม่เห็นระดับความเสี่ยง · หน้า At-Risk ของอาจารย์ |
| กลุ่มเสี่ยงปานกลาง (ขาด 2 ครั้ง) | `69130500009` | เหมือนข้างบน |
| ถอนรายวิชา | `68130500012` | ถอน INT201 → เหลือ 1 วิชา |
| ไม่มีวิชาเทอมนี้ (ปี 4 ฝึกงาน) | `66130599004` | หน้าว่าง ("ยังไม่มีข้อมูล" / "ยังไม่มีรายวิชา") |
| จบการศึกษา | `65130500017` | login ไม่ได้ (บัญชีถูกปิด) |
| ลาออก | `66130599005` | login ไม่ได้ (บัญชีถูกปิด) |
| สมาชิกทีม (ยังไม่ลงทะเบียนใบหน้า · INT201) | `66130500013`, `66130500036`, `66130500041` | ลงทะเบียนใบหน้าด้วยรูปตัวเอง |


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

- ทุกข้อจบด้วย `✓ ผ่านทั้งหมด` ถ้าไม่ผ่านจะแสดงรายละเอียดและ exit code ≠ 0 (ใช้ใน script / CI ได้)
- backend ยังไม่มี unit test ของตัวเอง — ข้อ 1 ตรวจแค่ว่า compile ได้ ข้อ 3 ตรวจการทำงานจริง
- ทดสอบที่แก้ข้อมูล (B5 สร้าง/แก้/ลบรายวิชา, B6 สวิตช์กล้อง, B7 ถอนความยินยอม, B8 เช็คชื่อด้วยมือ, กล้องส่งผลเข้า `/api/internal/rooms/{roomId}/sightings`) ทำด้วยมือตามตัวอย่าง request ใน `backend/README.md` แล้ว `./dev.sh db` เพื่อล้างกลับ
- บน GitHub: workflow `database` (เมื่อแก้ `backend/db/**`) และ `frontend` (เมื่อแก้ `frontend/**`) รันให้อัตโนมัติทุก PR

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
