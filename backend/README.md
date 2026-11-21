# backend

Spring Boot 4 · Java · PostgreSQL ([`db/`](db/)) · แบบฐานข้อมูล: [`docs/database/database-design.md`](../docs/database/database-design.md)

## ตั้งค่า (environment)

| ตัวแปร | ใช้ทำอะไร | ค่าเริ่มต้น |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | PostgreSQL | `jdbc:postgresql://localhost:5432/bangmod_attendance` / `postgres` / `postgres` |
| `JWT_SECRET` | ลงชื่อ token (ยาว ≥ 32 ตัวอักษร) | มีค่า dev ใน `application.yml` — **ต้องเปลี่ยนใน UAT/Prod** |
| `FACE_EMBEDDING_KEY` | AES-256 เข้ารหัส `face_embeddings` (`openssl rand -base64 32`) | ไม่มี = เปิดระบบไม่ได้ |
| `INTERNAL_API_KEY` | กุญแจร่วมกับ AI Service (header `X-Internal-Key`) ตั้งค่าเดียวกันทั้งสองฝั่ง | ไม่มี = ปิด `/api/internal/**` และแจ้ง AI ตอนถอนความยินยอมไม่ได้ |
| `AI_SERVICE_URL` | AI Service | `http://ai-service:8080` |
| `MEDIA_BASE_URL` | MediaMTX (WHEP) ที่เบราว์เซอร์เปิดภาพ | `http://localhost:8889` |
| `CORS_ORIGINS` | โดเมนของ FE คั่นด้วย `,` | `http://localhost:5173` |

```bash
mvn spring-boot:run
```

## API (ทุกเส้นขึ้นต้นด้วย `/api` · ต้องแนบ `Authorization: Bearer <token>` ยกเว้น login และ internal)

| เส้น | ข้อ | สิทธิ์ |
|---|---|---|
| `POST /auth/login`, `GET /me` | 5.1–5.2 | ทุกคน |
| `GET /courses` (+ ตารางเรียนของ section ตาม B5) | 5.3 | ตาม role |
| `GET /courses/{id}/attendance/summary · sessions · students` | 5.4–5.6 | admin, อาจารย์ผู้สอน |
| `GET /class-sessions/{id}/attendance/summary`, `GET /class-sessions/{id}/attendance` (+ `source`) | 5.7–5.8 | admin, อาจารย์ผู้สอน |
| `GET /courses/{courseId}/students/{studentId}/attendance` | — | admin, อาจารย์ผู้สอน, นักศึกษาเอง |
| `POST /students/{id}/face/register`, `GET .../face/status` | 5.10–5.11 | นักศึกษาเอง / admin (ดูสถานะ: + อาจารย์ผู้สอน) |
| `DELETE /students/{id}/face` | B7 | นักศึกษาเอง / admin · แจ้ง AI Service ให้ลบ embedding (`aiNotified`) |
| `GET /rooms`, `GET /rooms/{id}` | B1, B4 | admin, lecturer (เฉพาะห้องที่มีกลุ่มของตน) |
| `GET /rooms/{id}/stream` | B2 | เหมือน B1 |
| `GET /rooms/{id}/events?access_token=` (SSE: `camera`, `faces`, `check_in`, `session`) | B3 | เหมือน B1 |
| `GET/POST /courses`, `GET/PUT/DELETE /courses/{id}` | B5 | admin, lecturer (แก้ได้เฉพาะกลุ่มที่สอน) · ลบ = soft delete |
| `PATCH /sections/{id}/camera-attendance` | B6 | admin, อาจารย์ผู้สอน |
| `PUT /class-sessions/{sessionId}/attendance/{studentId}` (`status`, `reason` บังคับ, `checkInTime`) | B8 | admin, อาจารย์ผู้สอน |

### AI Service → backend (`X-Internal-Key`, ไม่เปิดออกนอก cluster)

| เส้น | ใช้ทำอะไร |
|---|---|
| `POST /internal/cameras/{cameraCode}/heartbeat` `{status}` | อัปเดต `cameras.status` + event `camera` |
| `POST /internal/rooms/{roomId}/faces` `{detected, at?}` | event `faces` (R1 ไม่เก็บลง DB) |
| `POST /internal/rooms/{roomId}/sightings` `{studentId, matchScore, seenAt?}` | เช็คชื่อตามกติกากล้อง → `checked_in` / `seen` / `ignored` + event `check_in` |

### Job (ทุก 1 นาที · ปิดด้วย `app.jobs.enabled=false`)

- เลื่อน `class_sessions.session_status`: scheduled → in_progress → completed (+ event `session`)
- ปิดช่วงเช็คชื่อแล้ว → INSERT absent (`source = system`) ให้คนที่ยังไม่มีแถว

> SSE และ event อยู่ในหน่วยความจำของ instance เดียว ถ้ารันหลาย replica ต้องเปลี่ยน `RoomEventHub` ไปใช้ pub/sub กลาง

## บันทึกการทำงาน (Changelog)

### 2026-11-21 — Dockerfile + รองรับการอยู่หลัง proxy
- **ทำอะไร:** เพิ่ม `Dockerfile` (Maven build → JRE 25, ไม่รันด้วย root) และ `.dockerignore` · ตั้ง `server.forward-headers-strategy: native` ให้ใช้ IP จาก `X-Forwarded-For` ของ proxy ภายใน
- **ไฟล์ที่เกี่ยวข้อง:** `Dockerfile`, `.dockerignore`, `src/main/resources/application.yml`
- **เหตุผล:** deploy บน K3s และให้ `audit_logs` / `consent_records` บันทึก IP จริงของผู้ใช้ ไม่ใช่ IP ของ NGINX
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบผ่าน NGINX config จริงแล้ว IP ถูกต้อง · ยังไม่ได้ build image (เครื่องไม่มี docker)

### 2026-11-21 — API B1–B8, แจ้ง AI Service, job สถานะคาบ
- **ทำอะไร:**
  - B1–B4 `room/`: รายการห้อง + คาบปัจจุบัน, URL ภาพ MediaMTX, SSE `/rooms/{id}/events` (รับ token ทาง `?access_token=` เฉพาะ path นี้)
  - B5–B6 `course/`: สร้าง/แก้/ลบ (soft delete) รายวิชา ตรวจห้องชน สร้าง `class_sessions` ตามวันเรียนจนสิ้นภาค (D2) และสวิตช์กล้องต่อ section
  - B8 `attendance/`: เช็คชื่อด้วยมือ (บังคับเหตุผล + `audit_logs`) และรับผลกล้องตามกติกาใน database-design.md
  - `internal/`: `/api/internal/**` ให้ AI Service ส่ง heartbeat กล้อง, จำนวนใบหน้า, ผลจับคู่ใบหน้า (ตรวจ `X-Internal-Key`)
  - `AttendanceJobs`: ทุก 1 นาที เลื่อน `session_status` และ INSERT absent (`source = system`) เมื่อปิดช่วงเช็คชื่อ
  - B7: หลังถอนความยินยอม เรียก AI Service `DELETE /students/{id}/embeddings` (ผลใน `aiNotified`)
  - 5.8 เพิ่ม `source` · `GET /courses` เพิ่มตารางเรียนของ section
  - `AccessService`: `requireFaceView` นับเฉพาะ enrollment ที่ active, `requireSession` ไม่นับ section ที่ถูกลบ
  - แก้ `application.yml`: Spring Boot 4 ใช้ `spring.web.error.include-message` (ชื่อเดิมไม่มีผล ทำให้ error ไม่มีข้อความ)
- **ไฟล์ที่เกี่ยวข้อง:** `src/main/java/com/faculty/attendance/{room,course,attendance,internal,common}/`, `auth/AccessService.java`, `auth/SecurityConfig.java`, `face/AiServiceClient.java`, `face/FaceRegistrationService.java`, `dashboard/*`, `src/main/resources/application.yml`
- **เหตุผล:** FE ยัง mock B1–B8 อยู่ (`frontend/src/mocks/handlers/pending.js`) และ PDPA ต้องลบข้อมูลใบหน้าฝั่ง AI ด้วย
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:**
  - ตั้ง env ใหม่ `INTERNAL_API_KEY` (ค่าเดียวกับ AI Service) และ `MEDIA_BASE_URL` ใน UAT/Prod · เปลี่ยน `JWT_SECRET` ด้วย
  - ทดสอบกับ PostgreSQL + seed (`db/02`, `db/03`) แล้ว ยังไม่ได้ทดสอบร่วมกับ FE จริง
  - SSE อยู่ในหน่วยความจำ instance เดียว · `v_student_section_attendance` ยังนับเฉพาะคาบ `completed`
