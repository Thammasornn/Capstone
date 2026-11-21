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
