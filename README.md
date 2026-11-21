# Capstone — Bangmod Smart Attendance (CP26MS3)

ระบบเช็คชื่อนักศึกษาอัตโนมัติด้วยกล้อง (Face Recognition) · โครงงานจบ IT, SIT KMUTT ปีการศึกษา 2569

## โครงสร้าง repo (monorepo)

| โฟลเดอร์ | เนื้อหา | สถานะ |
|---|---|---|
| [`frontend/`](frontend/) | Vue 3 + Vite (JavaScript) · Tailwind v4 · Pinia · MSW | Release 1 scaffold |
| [`backend/`](backend/) | Spring Boot REST + SSE · PostgreSQL — [`backend/db/`](backend/db/) schema + mock data R1 พร้อมใช้ | API R1 (5.1–5.11, B1–B8) |
| [`ai-service/`](ai-service/) | Python · OpenCV → YOLOv8 → OpenCV SFace | รอย้ายโค้ดเข้ามา |
| [`devops/`](devops/) | K3s manifests (namespace `prod` / `uat`), Ingress, MediaMTX | รอทำ |
| [`docs/`](docs/) | เอกสารประกอบ — [`docs/database/`](docs/database/) แบบฐานข้อมูล + ER Diagram | — |

```
[Raspberry Pi Camera] --RTSPS--> [MediaMTX] --RTSP--> [AI Service]
[Browser] --HTTPS (REST + SSE)--> [NGINX + Vue] --> [Spring Boot] --> [PostgreSQL]
```

Deploy บน Faculty VM ด้วย K3s: `/prod/` และ `/uat/` แยก namespace

## สมาชิก

- นายจักรพงษ์ สุขสมบรูณ์ — 66130500013
- นายทัตพล สีล้ง — 66130500036
- นายธรรมสรณ์ ฉัตรชยานุสรณ — 66130500041

อาจารย์ที่ปรึกษา: อ.มนตรี สุภัททธรรม

## บันทึกการทำงาน (Changelog)

### 2026-11-21 — Backend: API ห้องเรียน / รายวิชา / เช็คชื่อด้วยมือ (B1–B8) + แจ้ง AI ตอนถอนความยินยอม
- **ทำอะไร:** เพิ่ม API ที่ FE ยัง mock อยู่ครบ B1–B8, ช่องทางให้ AI Service ส่งผลกล้องเข้า backend, job สถานะคาบ/ขาด และให้ backend แจ้ง AI Service ลบ embedding เมื่อนักศึกษาถอนความยินยอม
- **ไฟล์ที่เกี่ยวข้อง:** `backend/`, `ai-service/main.py`, `docs/database/database-design.md`
- **เหตุผล:** ให้ FE เลิกใช้ mock ของ B1–B8 ได้ และให้การถอนความยินยอมลบข้อมูลใบหน้าฝั่ง AI ด้วย (PDPA)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** รายละเอียดดู `backend/README.md`, `ai-service/README.md`, `docs/README.md`

### 2026-11-21 — เพิ่มกฎการบันทึกรายงานใน README.md
- **ทำอะไร:** สร้างไฟล์ `CLAUDE.md` ที่ root เพื่อกำหนดกฎว่าทุกครั้งที่ทำงานใน repo ต้องเขียนรายงานสิ่งที่ทำไว้ใน `README.md` ของแต่ละ Folder
- **ไฟล์ที่เกี่ยวข้อง:** `CLAUDE.md`, `README.md`
- **เหตุผล:** ให้มีบันทึกการเปลี่ยนแปลงของโปรเจกต์อย่างต่อเนื่องและตรวจสอบย้อนหลังได้
