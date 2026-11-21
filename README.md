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
บัญชีทดสอบ (รหัสผ่าน `Password@123`): `admin@sit.kmutt.ac.th`, `supawadee.c@sit.kmutt.ac.th` (lecturer, มีคาบกำลังเรียนในห้อง 4302), `69130500001@st.sit.kmutt.ac.th` (student)

| คำสั่ง | ทำอะไร |
|---|---|
| `./dev.sh db --reset` | ลบฐานข้อมูลแล้วสร้างใหม่ทั้งหมด (เมื่อ schema เปลี่ยน · ถามยืนยันก่อน) |
| `./dev.sh backend` / `./dev.sh frontend` | รันทีละตัว (เช่นรัน backend จาก IDE แล้วใช้ `./dev.sh frontend`) |
| `BACKEND_PORT=8082 FRONTEND_PORT=5174 ./dev.sh` | port ชนกับโปรแกรมอื่น |
| `PGPORT=5433 ./dev.sh db` | PostgreSQL ไม่ได้อยู่ที่ `localhost:5432` (แล้วใส่ `DB_URL` ใน `backend/.env.local`) |
| `cd frontend && npm test` | unit test ของ FE |

ไม่รัน backend เลย: ใส่ `VITE_MOCK_MODE=all` ใน `frontend/.env.development.local` (mock เฉพาะ API 5.1–5.11 · ดู `frontend/README.md`)

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
