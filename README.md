# Capstone — Bangmod Smart Attendance (CP26MS3)

ระบบเช็คชื่อนักศึกษาอัตโนมัติด้วยกล้อง (Face Recognition) · โครงงานจบ IT, SIT KMUTT ปีการศึกษา 2569

## โครงสร้าง repo (monorepo)

| โฟลเดอร์ | เนื้อหา | สถานะ |
|---|---|---|
| [`frontend/`](frontend/) | Vue 3 + Vite (JavaScript) · Tailwind v4 · Pinia · MSW | Release 1 scaffold |
| [`backend/`](backend/) | Spring Boot REST + SSE · PostgreSQL — [`backend/db/`](backend/db/) schema + mock data R1 พร้อมใช้ | Auth, Dashboard, Face (รวม B7) ตรง schema แล้ว · B1–B6, B8–B9 ยังไม่ทำ |
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

### 2026-11-21 — Back-End ตรงกับ Database Design + B7 ถอนความยินยอม
- **ทำอะไร:** merge `feat/database-r1` (schema + เอกสาร DB) เข้ากับโค้ด Back-End แล้วแก้ SQL ทั้งหมดให้ตรง `backend/db/01_schema.sql` / `docs/database/database-design.md` · เข้ารหัส face embedding (AES-GCM) · ลงทะเบียนใหม่ลบชุดเดิมจริง · เพิ่ม B7 `DELETE /api/students/{studentId}/face`
- **ไฟล์ที่เกี่ยวข้อง:** `backend/src/main/**`, `backend/README.md` (รายละเอียดทั้งหมดอยู่ที่นั่น)
- **เหตุผล:** โค้ดเดิมใช้ schema ชุดเก่า รันกับ DB จริงไม่ได้ และบางจุดขัดข้อความยินยอม PDPA v1
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ตั้ง `FACE_EMBEDDING_KEY` · AI Service ต้องถอดรหัส embedding · FE ส่ง `ageConfirmed` · build ด้วย `mvn compile` อีกรอบ

### 2026-11-21 — เพิ่มกฎการบันทึกรายงานใน README.md
- **ทำอะไร:** สร้างไฟล์ `CLAUDE.md` ที่ root เพื่อกำหนดกฎว่าทุกครั้งที่ทำงานใน repo ต้องเขียนรายงานสิ่งที่ทำไว้ใน `README.md` ของแต่ละ Folder
- **ไฟล์ที่เกี่ยวข้อง:** `CLAUDE.md`, `README.md`
- **เหตุผล:** ให้มีบันทึกการเปลี่ยนแปลงของโปรเจกต์อย่างต่อเนื่องและตรวจสอบย้อนหลังได้
