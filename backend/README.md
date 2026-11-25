# backend

Spring Boot 4 · Java · PostgreSQL ([`db/`](db/)) · แบบฐานข้อมูล: [`docs/database/database-design.md`](../docs/database/database-design.md)

## โครงสร้าง

| โฟลเดอร์ | เนื้อหา |
|---|---|
| [`db/`](db/) | สร้างฐานข้อมูล `bangmod_attendance` (22 ตาราง + 4 view) และข้อมูล Mock-Up ภาค 1/2569 — เริ่มที่ [`db/README.md`](db/README.md) |
| `src/main/java/.../auth` | Login (JWT), `/api/me`, ตรวจสิทธิ์ admin / lecturer / student |
| `src/main/java/.../dashboard` | API 5.3–5.9 (Dashboard, Session Report, ประวัติรายคน) |
| `src/main/java/.../face` | ลงทะเบียนใบหน้า, สถานะ, ถอนความยินยอม (B7) |
| `src/main/java/.../room` | B1–B4 ห้อง, ภาพสด, SSE |
| `src/main/java/.../course` | B5–B6 จัดการรายวิชา, สวิตช์กล้อง |
| `src/main/java/.../attendance` | B8 เช็คชื่อด้วยมือ, รับผลกล้อง, job สถานะคาบ / absent |
| `src/main/java/.../internal` | `/api/internal/**` สำหรับ AI Service |
| `src/main/java/.../audit`, `common` | `audit_logs`, เวลาไทย, error |

## ตั้งค่า (environment)

**บนเครื่องนักพัฒนาไม่ต้องตั้งอะไร** — ถ้าไม่ได้ตั้ง `SPRING_PROFILES_ACTIVE` จะใช้โปรไฟล์ `local`
([`application-local.yml`](src/main/resources/application-local.yml)) ที่มี key ทดสอบให้ครบ
แค่ `mvn spring-boot:run` หรือกด Run ที่ `AttendanceApplication` ใน IDE (หรือใช้ `./dev.sh` ที่ root)

- ค่าส่วนตัวของเครื่อง (เช่น DB คนละ port) ใส่ใน `backend/.env.local` (ไม่ถูก commit) รูปแบบ `ชื่อ=ค่า` ทีละบรรทัด ใช้ได้กับทุกตัวแปรในตารางด้านล่าง
- key ในโปรไฟล์ `local` อยู่ใน git — ใช้กับข้อมูล mock เท่านั้น ตอนรันจะมี WARN ใน log เตือนทุกครั้ง
- Docker image และ K3s ตั้ง `SPRING_PROFILES_ACTIVE=prod` → ไม่โหลดโปรไฟล์ `local` และ **ต้องตั้ง** `JWT_SECRET`, `FACE_EMBEDDING_KEY` เอง (ไม่ตั้ง = start ไม่ขึ้น)

| ตัวแปร | ใช้ทำอะไร | ค่าเริ่มต้น (`prod`) | โปรไฟล์ `local` |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | เลือกโปรไฟล์ | ไม่ตั้ง = `local` | — |
| `SERVER_PORT` | port ของ backend | `8081` | `8081` |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | PostgreSQL | `jdbc:postgresql://localhost:5432/bangmod_attendance` / `postgres` / `postgres` | เหมือนกัน |
| `JWT_SECRET` | ลงชื่อ token (ยาว ≥ 32 ตัวอักษร, `openssl rand -base64 48`) | **บังคับตั้ง** | ค่าทดสอบ |
| `FACE_EMBEDDING_KEY` | AES-256 เข้ารหัส `face_embeddings` (`openssl rand -base64 32`) | **บังคับตั้ง** | ค่าทดสอบ |
| `INTERNAL_API_KEY` | กุญแจร่วมกับ AI Service (header `X-Internal-Key`) ตั้งค่าเดียวกันทั้งสองฝั่ง | ไม่มี = ปิด `/api/internal/**` | `local-test-key` |
| `AI_SERVICE_URL` | AI Service | `http://ai-service:8080` | `http://localhost:8080` |
| `MEDIA_BASE_URL` | MediaMTX (WHEP) ที่เบราว์เซอร์เปิดภาพ | `http://localhost:8889` | เหมือนกัน |
| `CORS_ORIGINS` | โดเมนของ FE คั่นด้วย `,` | `http://localhost:5173` | เหมือนกัน |
| `FLYWAY_ENABLED` | ให้ Flyway ดูแล schema (ปกติใช้ `db/*.sql`) | `false` | เหมือนกัน |
| `FORWARD_HEADERS_STRATEGY` | เชื่อ `X-Forwarded-For` จาก proxy ภายใน | `native` | เหมือนกัน |
| `TEST_ACCOUNTS_ENABLED` | เปิด `GET /api/test-accounts` (ไม่ต้อง login) ให้หน้า Login แสดงบัญชีใน DB · **ห้ามเปิดใน prod** | `false` (= 404) | `true` |

สร้างฐานข้อมูลด้วย `./dev.sh db` ที่ root (หรือตาม [`db/README.md`](db/README.md): `00` → `01` → `02`) แล้ว:

```bash
mvn spring-boot:run        # http://localhost:8081
```

## API (ทุกเส้นขึ้นต้นด้วย `/api` · ต้องแนบ `Authorization: Bearer <token>` ยกเว้น login และ internal)

| เส้น | ข้อ | สิทธิ์ |
|---|---|---|
| `POST /auth/login`, `GET /me` | 5.1–5.2 | ทุกคน |
| `GET /test-accounts` (อีเมล ชื่อ role สถานะใบหน้า / ความยินยอม จำนวนวิชา · ไม่มีรหัสผ่าน) | หน้า Login (local / uat) | ไม่ต้อง login · มีเฉพาะเมื่อ `TEST_ACCOUNTS_ENABLED=true` |
| `GET /courses` (+ ตารางเรียนของ section ตาม B5) | 5.3 | ตาม role |
| `GET /courses/{id}/attendance/summary · sessions · students` | 5.4–5.6 | admin, อาจารย์ผู้สอน |
| `GET /class-sessions/{id}/attendance/summary` (+ `checkInOpens`, `onTimeUntil`, `lateUntil`, `checkInCloses` `"HH:mm"` ของคาบ), `GET /class-sessions/{id}/attendance` (+ `source`) | 5.7–5.8 | admin, อาจารย์ผู้สอน |
| `GET /courses/{courseId}/students/{studentId}/attendance` | — | admin, อาจารย์ผู้สอน, นักศึกษาเอง |
| `POST /students/{id}/face/register`, `GET .../face/status` | 5.10–5.11 | นักศึกษาเอง / admin (ดูสถานะ: + อาจารย์ผู้สอน) |
| `DELETE /students/{id}/face` | B7 | นักศึกษาเอง / admin · แจ้ง AI Service ให้ลบ embedding (`aiNotified`) |
| `GET /rooms`, `GET /rooms/{id}` (`currentSession` มี `checkInOpens`, `onTimeUntil`, `lateUntil`, `checkInCloses` `"HH:mm"` ของคาบ · ไม่มีคาบ = `currentSession: null`) | B1, B4 | admin, lecturer (เฉพาะห้องที่มีกลุ่มของตน) |
| `GET /rooms/{id}/stream` | B2 | เหมือน B1 |
| `GET /rooms/{id}/events?access_token=` (SSE: `camera`, `faces`, `check_in`, `session`) | B3 | เหมือน B1 |
| `GET/POST /courses`, `GET/PUT/DELETE /courses/{id}` | B5 | admin, lecturer (แก้ได้เฉพาะกลุ่มที่สอน) · ลบ = soft delete |
| `PATCH /sections/{id}/camera-attendance` | B6 | admin, อาจารย์ผู้สอน |
| `GET /rooms/options`, `GET /lecturers` | ตัวเลือกฟอร์มรายวิชา | admin, lecturer |
| `PUT /class-sessions/{sessionId}/attendance/{studentId}` (`status`, `reason` บังคับ, `checkInTime`) | B8 | admin, อาจารย์ผู้สอน |
| `POST /rooms/{roomId}/sessions` `{sectionId}` — เริ่มคาบของกลุ่มเรียนนี้ในห้องนี้ "ตอนนี้" (เปิดเช็คชื่อทันที · ความยาว / ช่วงตรงเวลา / สายตามกลุ่มเรียน · 409 = ห้องมีคาบอื่นเวลาทับ / กลุ่มเรียนมีคาบที่กำลังเรียนหรือจบแล้ววันนี้ · audit `session.start_now` + event `session`) | ปุ่มเริ่มคาบ (Room Live) | admin, อาจารย์ผู้สอน |
| `POST /class-sessions/{sessionId}/end` — จบคาบที่กำลังเรียน "ตอนนี้" (ช่วงเช็คชื่อที่ยังเปิดถูกตัด → คนที่ยังไม่มีผล = absent `system` · audit `session.end_now` + event `session`) | ปุ่มจบคาบ (Room Live) | admin, อาจารย์ผู้สอน |

### ปรับเวลาคาบที่เริ่มแล้ว (คาบเดียว · ไม่แตะตารางเรียนของกลุ่มเรียน)

สิทธิ์ admin / อาจารย์ผู้สอน (`requireSectionStaff`) · `reason` บังคับ (≤ 500 ตัวอักษร) · เวลาเป็น `"HH:mm"` · `dryRun: true` = ดูผลก่อน ไม่เขียน DB (Preview & Confirm)

| เส้น | ใช้เมื่อ | body |
|---|---|---|
| `POST /class-sessions/{id}/reanchor` | อาจารย์เข้าสาย / เริ่มจริงช้ากว่าตาราง → เลื่อนเวลาเริ่ม + ช่วงเช็คชื่อทั้งชุดเท่ากัน (เวลาเลิกคงเดิม · ช่วงเช็คชื่อที่เกินเวลาเลิกถูกตัด) | `{actualStart, reason, dryRun, applyDowngrades}` |
| `POST /class-sessions/{id}/extend` | เลิกช้า / ปรับเวลาจบ (≥ ตอนนี้ + 1 นาที, ≥ ปิดเช็คชื่อ · ห้องชนคาบอื่น = 409) | `{endTime, reason, dryRun}` |
| `POST /class-sessions/{id}/check-in-window` | ขยาย / ปรับช่วงเช็คชื่อ (ฝนตก รถติด) · `check_in_opens` คงเดิม | `{onTimeUntil, lateUntil, checkInCloses, reason, dryRun, applyDowngrades}` |

| สถานะคาบ | reanchor | extend | check-in-window |
|---|---|---|---|
| scheduled (ยังไม่ถึงเวลาเริ่ม) | 409 | 409 | 409 |
| in_progress | ✅ (`actualStart` ≥ เริ่มเดิม − 60 นาที และ ≤ ตอนนี้) | ✅ | ✅ |
| completed | ✅ ภายใน 7 วัน | 409 | ✅ ภายใน 7 วัน |
| cancelled | 409 | 409 | 409 |

- สถานะดูจากเวลาจริง (job อัปเดต `session_status` ช้าได้ ≤ 1 นาที): scheduled ที่ถึงเวลาเริ่มแล้ว = in_progress, in_progress ที่เลยเวลาเลิก = completed
- ตรวจเวลาก่อนเขียน DB (400): start < end, start ≤ เปิด ≤ ตรงเวลา ≤ สาย ≤ ปิด ≤ end
- คิดผลเช็คชื่อใหม่ (reanchor / check-in-window) แบบ "ไม่ลงโทษย้อนหลัง": `manual` ไม่เปลี่ยน · `camera` คิดใหม่จาก `check_in_time` (ดีขึ้น = upgrade ใช้เลย, แย่ลง = downgrade ใช้เมื่อ `applyDowngrades: true` เท่านั้น · หลัง `late_until` ใหม่ = คงเดิม) · `system` (absent) ถ้าคาบยังเรียนอยู่และปิดเช็คชื่อใหม่ยังไม่ถึง → ลบแถว (reopen) ให้กล้องเช็คชื่อได้อีก
- ตอบ `{sessionId, dryRun, before, after, changes: [{studentId, studentCode, studentName, source, checkInTime, fromStatus, toStatus, kind: upgrade|downgrade|reopen, applied}], summary: {upgrades, downgrades, downgradesApplied, reopened, unchanged}}`
- บันทึก (ไม่ใช่ dryRun): transaction เดียว ล็อก `class_sessions` + `attendance_records` (FOR UPDATE) · audit `session.reanchor` / `session.extend` / `session.check_in_window` (+ reason, summary) และ `attendance.recalculated` ต่อแถว · หลัง commit ส่ง event `session` + `check_in` ของแถวที่เปลี่ยน
- **ข้อจำกัด:** คาบ completed — คนที่กล้องเห็นหลังปิดเช็คชื่อเดิมไม่ได้ถูกเก็บไว้ (`recordSighting` คืน `check_in_closed` โดยไม่บันทึก) จึงกู้ไม่ได้ ต้องใช้เช็คชื่อด้วยมือ (B8) หรือ import
- `PUT /courses/{id}` ตอบ `startedSessionsNotChanged: [{sessionId, sessionDate, sectionCode, sessionStatus, startTime, endTime}]` = คาบตั้งแต่วันนี้ของกลุ่มที่แก้ตารางเรียน แต่ระบบไม่ได้แก้ตาม (เริ่มแล้ว / มีข้อมูลแล้ว / เวลาใหม่ย้อนไปก่อนตอนนี้) ให้ FE แจ้งผู้ใช้และปรับด้วยเส้นด้านบน · GET / POST = `[]`

### นำเข้าผลเช็คชื่อจากไฟล์ (คลาสออนไซต์)

สิทธิ์ admin / อาจารย์ผู้สอน (`requireSession`) · ไม่พบคาบ 404 · คาบยกเลิก / ยังไม่ถึงเวลาเริ่ม 409 (ยกเว้นดาวน์โหลด template) · ไฟล์ ≤ 2 MB, ≤ 500 แถวข้อมูล, CSV UTF-8 (มี / ไม่มี BOM) คั่นด้วย comma หรือ XLSX (ชีตแรก), หัวตารางอยู่แถวแรก · อ่านใน memory แล้วทิ้ง ไม่เก็บไฟล์ (เก็บแค่ชื่อไฟล์ + SHA-256 + ข้อมูลแต่ละแถวใน `attendance_imports` / `attendance_import_rows`) · ไม่เดาคอลัมน์ / encoding / ตัวคั่น / รูปแบบเวลา / ความหมายของค่าสถานะ

| เส้น | request | response |
|---|---|---|
| `GET /class-sessions/{id}/attendance/import-template?format=xlsx\|csv` | — | ไฟล์ `attendance_<courseCode>-<sectionCode>_<sessionDate>.<ext>` คอลัมน์ `student_code, student_name, status, check_in_time` (กรอกรหัส / ชื่อให้แล้ว) · XLSX มี dropdown `on_time, late, absent` + ชีต "วิธีกรอก" · CSV มี BOM |
| `POST /class-sessions/{id}/attendance/import/inspect` | multipart `file` | `{headers, sampleRows (5 แถวแรก), distinctValues (คอลัมน์ที่มีค่าไม่ซ้ำ ≤ 20), rowCount}` · ไม่บันทึก |
| `POST /class-sessions/{id}/attendance/import` | multipart `file`, `mode` (`template`\|`mapped`), `method` (`template`\|`teams`\|`zoom`\|`google_form`\|`paper`\|`other` · template ↔ template), `reason` (บังคับ ≤ 300), `dryRun` (ค่าเริ่มต้น `true`), `overrideCamera`, `overrideManual` (ค่าเริ่มต้น `false`), `mapping` (JSON string บังคับเมื่อ mapped) | `{dryRun, mode, importId, duplicateOf, sessionWindow, rows: [{rowNo, studentId, studentCode, studentName, fromSource, fromStatus, toStatus, checkInTime, action, applied}], unmatched: [{rowNo, value, reason}], summary: {totalRows, insert, replace, skip, conflict, unmatched, applied}}` |
| `GET /class-sessions/{id}/attendance/imports` | — | `[{importId, importedAt, importedById, importedBy, mode, method, fileName, reason, overrideCamera, overrideManual, totalRows, insert, replace, skip, conflict, unmatched}]` ใหม่ → เก่า |
| `GET /class-sessions/{id}/attendance/imports/{importId}` | — | `{summary (เหมือนรายการด้านบน), mapping, rows: [{rowNo, rawIdentifier, rawJoinTime, rawStatus, studentId, studentCode, studentName, status, checkInTime, action, unmatchedReason, applied}]}` · import ของคาบอื่น = 404 |

- **template:** หัวตารางต้องตรงทุกตัวตามลำดับ · `student_code` 11 หลัก · `status` ว่าง / `on_time` / `late` / `absent` (ว่าง = ข้าม ไม่นับใน `totalRows`) · `check_in_time` ว่าง / `HH:mm` · absent ห้ามมีเวลา · มีเวลาแต่ status ว่าง = error
- **mapped** `mapping = {identifierColumn, identifierType: email|student_code, joinTimeColumn?, timeFormat?, statusColumn?, statusValues?: {"มา": "on_time"}, defaultStatus?: on_time|late}` · คอลัมน์ที่อ้างต้องมีจริง · มี `joinTimeColumn` ต้องมี `timeFormat` จาก `HH:mm`, `HH:mm:ss`, `d/M/yyyy HH:mm:ss`, `M/d/yyyy`, `h:mm:ss a`, `yyyy-MM-dd HH:mm:ss` เท่านั้น (เวลาอย่างเดียวใช้วันที่ของคาบ · `M/d/yyyy` มีแต่วันที่ = ถือว่าไม่มีเวลาเข้า) · มี `statusColumn` ต้องมี `statusValues` ครบทุกค่าที่ไม่ว่าง · แถวที่ไม่มีทั้งเวลาและสถานะใช้ `defaultStatus` (ไม่กำหนด = error) · email ต้องเป็น `<รหัส 11 หลัก>@...` · XLSX อ่านค่าตามที่ Excel แสดง (ตรงกับ `inspect`)
- **ไฟล์ผิด → 400 ไม่บันทึกอะไร:** `{timestamp, status: 400, error, message: "ไฟล์มีข้อผิดพลาด 3 แถว", path, errors: [{rowNo, column, value, message}]}` (ปัญหาทั้งไฟล์ / mapping: `rowNo = null`) · error ของ API อื่นรูปแบบเดิม
- **จับคู่:** รหัส 11 หลัก → นักศึกษา active ในกลุ่มเรียน · ไม่พบ `not_found` / มีในระบบแต่ไม่อยู่ในกลุ่ม `not_enrolled` / ซ้ำในไฟล์ (ใช้แถวแรก) `duplicate` → อยู่ใน `unmatched` (ไม่ใช่ 400)
- **สถานะ:** จากไฟล์ / `statusValues` → ไม่มีใช้เวลาเข้าเทียบ `onTimeUntil` / `lateUntil` ของคาบ (เกิน `lateUntil` = late ไม่ลงโทษเป็น absent) → ไม่มีทั้งคู่ใช้ `defaultStatus` · `checkInTime` = เวลาจากไฟล์ถ้าอยู่ใน (เริ่มคาบ − 1 ชม.)–เลิกคาบ ไม่งั้นเวลาเริ่มคาบ · absent = null
- **กติกาการทับผลเดิม** (ลำดับปกติ manual > import > camera > system · override = ผู้ใช้ยืนยันเองว่าไฟล์ถูกกว่า บันทึก flag ไว้ใน `attendance_imports`):

| ผลเดิม | action | เขียนลง `attendance_records` |
|---|---|---|
| ไม่มีแถว | `insert` | ✅ |
| `system` (absent อัตโนมัติ) | `replace` | ✅ |
| `import` (ไฟล์ครั้งก่อน) | `replace` | ✅ ไฟล์ล่าสุดชนะ |
| `camera` สถานะเหมือนไฟล์ | `skip` | — |
| `camera` สถานะต่าง | `conflict` | เฉพาะ `overrideCamera = true` |
| `manual` (อาจารย์แก้เอง) | `conflict` (แม้สถานะเหมือนกัน) | เฉพาะ `overrideManual = true` |
| ไม่อยู่ในไฟล์ / status ว่าง (template) | — | ไม่แตะ |

- `applied` = เขียนจริงแล้ว (dryRun = `false` ทุกแถว, `summary.applied = 0`) · ไฟล์ SHA-256 เดียวกับที่เคยนำเข้าในคาบนี้ → `duplicateOf: {importId, importedAt, importedBy, fileName}` (เตือน ไม่บล็อก)
- **บันทึก (`dryRun = false`):** transaction เดียว ล็อกคาบ + แถวเช็คชื่อ คำนวณใหม่ด้วยข้อมูลที่ล็อกแล้ว → `attendance_imports` + ทุกแถวของไฟล์ใน `attendance_import_rows` (รวม skip / conflict / unmatched) → เขียน `source = import` เฉพาะแถวที่ apply → audit `attendance.import` (สรุป) · คาบกำลังเรียน → SSE `check_in` (`source: "import"`) ของคนที่เปลี่ยน
- หลังนำเข้า: อาจารย์แก้ทับด้วย B8 ได้ (`import_id` ถูกล้าง) · กล้องไม่ทับแถว import · reanchor / check-in-window ไม่เปลี่ยนแถว import (เหมือน manual)

### AI Service → backend (`X-Internal-Key`, ไม่เปิดออกนอก cluster)

| เส้น | ใช้ทำอะไร |
|---|---|
| `GET /internal/cameras` | กล้องที่เปิดใช้ `[{cameraCode, roomId, streamPath}]` · AI Service อ่านภาพจาก MediaMTX path = `streamPath` |
| `GET /internal/rooms/{roomId}/gallery` | `{sessionId, cameraEnabled, students: [{studentId, embeddings: [base64]}]}` ของคาบที่อยู่ในห้องตอนนี้ (`check_in_opens`–`end_time`) · เฉพาะคนที่ลงทะเบียน + consent `granted` + บัญชีเปิด · embedding ยังเข้ารหัสอยู่ (AI Service ถอดเอง) · ไม่มีคาบ = `sessionId: null` |
| `POST /internal/cameras/{cameraCode}/heartbeat` `{status}` | อัปเดต `cameras.status` + event `camera` |
| `POST /internal/rooms/{roomId}/faces` `{detected, at?}` | event `faces` (R1 ไม่เก็บลง DB) |
| `POST /internal/rooms/{roomId}/sightings` `{studentId, matchScore, seenAt?}` | เช็คชื่อตามกติกากล้อง → `checked_in` / `seen` / `ignored` + event `check_in` |

### Job (ทุก 1 นาที · ปิดด้วย `app.jobs.enabled=false`)

- เลื่อน `class_sessions.session_status`: scheduled → in_progress → completed (+ event `session`)
- ปิดช่วงเช็คชื่อแล้ว → INSERT absent (`source = system`) ให้คนที่ยังไม่มีแถว

> SSE และ event อยู่ในหน่วยความจำของ instance เดียว ถ้ารันหลาย replica ต้องเปลี่ยน `RoomEventHub` ไปใช้ pub/sub กลาง

## บันทึกการทำงาน (Changelog)

### 2026-11-25 — คาบเรียน (จัดการรายคาบ): data layer (D1)
- **ทำอะไร:** `CourseRepository.regenerateSessions` ไม่ลบคาบที่ `is_overridden` และคาบที่มีคาบชดเชยอ้างถึง (ไม่งั้นชน FK) · ไม่สร้างคาบในวันที่ตรงกับ `original_date` ของคาบที่ถูกเลื่อน · แยก `renumberSessions(sectionId)` ออกมาใช้ร่วม · เพิ่ม `attendance/ClassSessionRepository.java` (`listBySection` + จำนวน on_time / late / absent / ยังไม่มีผล · `lock` · `existsOnDate` · `roomConflict` · `hasAttendance` · `reschedule` · `cancel` · `restore` · `insertExtra`) · ไม่มี endpoint ใหม่ (รอ D2)
- **ไฟล์ที่เกี่ยวข้อง:** `src/main/java/.../course/CourseRepository.java`, `src/main/java/.../attendance/ClassSessionRepository.java`, `db/` (ดู `db/README.md`)
- **เหตุผล:** เตรียม data layer ให้ D2 · grep แล้ว: สถิติ (`STARTED` ใน `DashboardRepository`), view, `advanceSessionStatuses`, `insertSystemAbsents`, `sessionInRoomAt`, `RoomRepository` ข้ามคาบ `cancelled` อยู่แล้ว ไม่ต้องแก้
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** **ต้องรัน `db/06_class_session_management.sql` ก่อน deploy backend รุ่นนี้** (บันทึกรายวิชาจะ error ถ้าไม่มีคอลัมน์ใหม่) · ทดสอบผ่าน `PUT /courses/{id}` จริง: คาบตามแม่แบบเปลี่ยนเวลา คาบที่เลื่อน / คาบเพิ่มไม่ถูกแตะ ไม่มีคาบซ้ำในวันเดิม · เรื่องให้ D2 ตัดสิน: (1) `sessionsNotMatchingSection` → `startedSessionsNotChanged` จะรวมคาบที่ `is_overridden` ด้วย (ตั้งใจให้ต่างจากแม่แบบ) อาจต้องกรองออกหรือแสดงแยก (2) `SessionControlRepository.insertInProgress` (เริ่มคาบจากหน้าห้องในวันที่ไม่มีคาบ) ยังสร้างเป็นคาบปกติ ไม่ใช่ `is_extra` (3) เริ่มคาบบนแถว `cancelled` (`moveToNow`) ยังไม่ล้าง `cancel_reason`

### 2026-11-25 — นำเข้าผลเช็คชื่อจากไฟล์: API (C2)
- **ทำอะไร:** เพิ่ม `AttendanceImportController` / `AttendanceImportService` / `ImportFileReader` / `ImportTemplates` / `ImportDtos`: ดาวน์โหลด template (XLSX มี dropdown + ชีตวิธีกรอก / CSV มี BOM), `inspect` (หัวตาราง + ตัวอย่าง + ค่าที่ไม่ซ้ำ), `import` แบบ `template` / `mapped` พร้อม `dryRun` (ค่าเริ่มต้น), ประวัติ และรายละเอียดรายแถว · ตรวจไฟล์แบบไม่เดา (UTF-8 เข้ม, comma เท่านั้น, รูปแบบเวลา 6 แบบที่กำหนด, `statusValues` ต้องครบ) · error รายแถวด้วย `ImportValidationException` + handler ใน `ApiErrors` (API อื่นรูปแบบเดิม) · กติกาการทับผลเดิมตามตารางในหัวข้อ API · `AttendanceImportRepository.existingStudentCodes` แยก `not_found` / `not_enrolled` · เพิ่ม dependency `commons-csv 1.14.1`, `poi-ooxml 5.5.1` (ออกแล้ว > 2 สัปดาห์)
- **ไฟล์ที่เกี่ยวข้อง:** `pom.xml`, `src/main/java/com/faculty/attendance/attendance/{AttendanceImportController,AttendanceImportService,ImportFileReader,ImportTemplates,ImportDtos}.java` (ใหม่), `attendance/AttendanceImportRepository.java`, `attendance/SessionAdjustService.java` (comment), `common/ImportValidationException.java` (ใหม่), `common/ApiErrors.java`, `README.md`
- **เหตุผล:** ให้อาจารย์นำผลเช็คชื่อจากใบเซ็นชื่อ / Teams / Zoom / Google Form เข้าระบบได้โดยเห็นผลก่อนบันทึก และย้อนดูได้ว่าไฟล์ไหนเปลี่ยนอะไร
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** กำหนดกติกาการทับให้ชัด (DB ฝากไว้): manual = conflict เสมอแม้สถานะเหมือนกัน, camera ที่สถานะเหมือนไฟล์ = skip · ตามสเปก email ที่ไม่ใช่ `<รหัส 11 หลัก>@` = 400 ทั้งไฟล์ (เช่นอีเมลอาจารย์ใน Teams) ผู้ใช้ต้องลบแถวนั้นก่อน · ทดสอบกับ backend จริง + Postgres ชั่วคราว (01 ใหม่ + 02 + 03): template XLSX / CSV ดาวน์โหลดได้ (ชื่อไฟล์ถูก, BOM, dropdown, ชีต 2), template ผิด 3 แถว → 400 ครบ, ไม่มี reason / mode ↔ method ผิด 400, dryRun ไม่เขียน, ของจริง: camera conflict ถูกทับเมื่อ `overrideCamera`, manual conflict ไม่ถูกทับ, insert absent · DB มี import + 3 แถว + audit, ไฟล์เดิมซ้ำ → `duplicateOf` · mapped CSV (Teams): `statusValues` ไม่ครบ / อีเมลผิด → 400, not_found / not_enrolled / duplicate → unmatched, เวลาเกิน lateUntil = late, `overrideManual` ทับ manual, `defaultStatus` ใช้ได้, mapping อ้างคอลัมน์ไม่มี / timeFormat ไม่รองรับ → 400, CSV คั่น `;` / ไม่ใช่ UTF-8 / `.txt` → 400 · ประวัติ + รายละเอียด (import ของคาบอื่น 404) · B8 ทับแถว import ได้ (`import_id` ว่าง) · check-in-window ไม่แตะแถว import · คาบที่ยังไม่เริ่ม 409 (template 200) · นักศึกษา 403 · 5.8 `importedFrom` · `./dev.sh test` และ `./dev.sh smoke` (15 ข้อ) ผ่าน · ยังไม่ได้ทดสอบ SSE `check_in` ของ import ผ่านเบราว์เซอร์ และยังไม่ได้ทดสอบเส้นกล้องเจอแถว import ใน C2 (seed ชั่วคราวไม่มี consent — C1 ทดสอบแล้ว) · FE (C3) ยังไม่ได้ทำ

### 2026-11-25 — นำเข้าผลเช็คชื่อจากไฟล์: data layer (C1)
- **ทำอะไร:** เพิ่ม `attendance/AttendanceImportRepository.java` (`findSession` / `lockSession` · `activeStudentsByCode` · `currentRecords` · `findImportBySha` · `insertImport` · `insertRows` แบบ batch · `upsertImported` → `source = import` · `history` · `rows` · `sessionOfImport`) · `AttendanceRepository.upsertManual` ล้าง `import_id` เมื่ออาจารย์แก้ทับแถว import · `AttendanceService.recordSighting` กล้องไม่ทับแถว import (ignored `import_record`) · `GET /class-sessions/{id}/attendance` คืน `importedFrom` (method ของการนำเข้า หรือ null) และ `source` เป็น `import` ได้แล้ว · ไม่มี endpoint ใหม่ (รอ C2)
- **ไฟล์ที่เกี่ยวข้อง:** `src/main/java/.../attendance/AttendanceImportRepository.java`, `AttendanceRepository.java`, `AttendanceService.java`, `AttendanceDtos.java`, `src/main/java/.../dashboard/DashboardRepository.java`, `DashboardDtos.java`, `db/` (ดู `db/README.md`)
- **เหตุผล:** เตรียม data layer ให้ C2 (service / controller ของการนำเข้า) · ตรวจที่อื่นที่เช็ค `source` แล้ว: `SessionAdjustService` ปรับเฉพาะแถว camera / system → แถว import นับเป็น "ไม่เปลี่ยน" เหมือน manual (ถูกแล้ว) · view ใน `01` และ dashboard นับตาม `status` จึงนับแถว import เป็นผลเช็คชื่อปกติ
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** **ต้องรัน `db/05_attendance_import.sql` กับ DB ก่อน deploy backend รุ่นนี้** (uat: CD จะหยุด · prod: ก่อน deploy prod ครั้งถัดไป) ไม่งั้น session attendance error · `./dev.sh test` + `./dev.sh smoke` ผ่าน · ทดสอบ `importedFrom` ผ่าน API จริงและ `upsertImported` → `upsertManual` ด้วย SQL แล้ว

### 2026-11-25 — คืนช่วงเวลาเช็คชื่อของคาบใน B1 / B4 / 5.7
- **ทำอะไร:** เพิ่ม `checkInOpens`, `onTimeUntil`, `lateUntil`, `checkInCloses` (string `"HH:mm"` จาก `class_sessions` ไม่ใช่ของกลุ่มเรียน) ใน `currentSession` ของ `GET /api/rooms` และ `GET /api/rooms/{id}` และใน `GET /api/class-sessions/{id}/attendance/summary`
- **ไฟล์ที่เกี่ยวข้อง:** `src/main/java/com/faculty/attendance/room/RoomDtos.java`, `room/RoomRepository.java`, `dashboard/DashboardDtos.java`, `dashboard/DashboardRepository.java`, `dashboard/DashboardService.java`, `README.md`
- **เหตุผล:** FE ต้องแสดงค่าปัจจุบัน / ตั้งค่าเริ่มต้นในฟอร์มปรับเวลาคาบ (reanchor / extend / check-in-window) แต่ไม่มี GET ไหนคืนช่วงเช็คชื่อของคาบ
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ไม่แก้ schema (คอลัมน์มีครบใน `class_sessions`) · 4 field ใหม่เป็น `"HH:mm"` แต่ `startTime` / `endTime` เดิมยังเป็น `"HH:mm:ss"` · ห้องที่ไม่มีคาบ `currentSession` = `null` ทั้งก้อน · ทดสอบกับ Postgres ชั่วคราว (seed 02 + 03): ห้อง 4303 มีค่าครบ, ห้องอื่น null, summary คาบที่กำลังเรียน / จบแล้วมีค่า · `./dev.sh smoke` ผ่าน 15 ข้อ · `./dev.sh test`: backend ผ่าน แต่ `frontend: format` ไม่ผ่านจากไฟล์ FE ที่ยังไม่ commit (`SessionAdjustDialog.vue`, `SessionAdjustPreview.vue`) ไม่เกี่ยวกับงานนี้

### 2026-11-25 — ปรับเวลาคาบที่เริ่มแล้ว (reanchor / extend / check-in-window)
- **ทำอะไร:** เพิ่ม `SessionAdjustController` / `Service` / `Repository`: `POST /api/class-sessions/{id}/reanchor` (เริ่มจริงช้ากว่าตาราง เลื่อนช่วงเช็คชื่อตาม), `/extend` (ปรับเวลาจบ ตรวจห้องชน), `/check-in-window` (ปรับตรงเวลา / สาย / ปิดเช็คชื่อ) · บังคับ reason · `dryRun` · คิดผลเช็คชื่อใหม่แบบไม่ลงโทษย้อนหลัง (upgrade ใช้เลย, downgrade ต้อง `applyDowngrades`, absent ของระบบถูกเปิดใหม่ถ้าคาบยังเรียนอยู่) · audit ต่อคาบ + ต่อแถว · SSE `session` / `check_in` · แก้บั๊ก `CourseRepository.regenerateSessions`: คาบวันนี้ที่ยังไม่เริ่มแต่เวลาเริ่มใหม่ย้อนไปก่อนตอนนี้ถูกลบโดยไม่ถูกสร้างคืน (หายเงียบ) → ตอนนี้ลบเฉพาะเมื่อเวลาใหม่ยังอยู่ในอนาคต · `PUT /api/courses/{id}` เพิ่ม `startedSessionsNotChanged` (`CourseDetail` มี field นี้ทุกเส้น ค่าเป็น `[]` ใน GET / POST)
- **ไฟล์ที่เกี่ยวข้อง:** `src/main/java/com/faculty/attendance/attendance/SessionAdjust*.java` (ใหม่), `course/CourseRepository.java`, `course/CourseService.java`, `course/CourseDtos.java`, `README.md`
- **เหตุผล:** เดิมแก้เวลาคาบที่เริ่มแล้วไม่ได้ — `PUT /courses/{id}` แก้แค่ตารางเรียนของกลุ่ม คาบวันนี้ที่เริ่มแล้วคงเวลาเดิม แต่ API ตอบ 200 เงียบ ๆ
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ไม่แก้ schema · ทดสอบกับ backend จริง + Postgres ชั่วคราว (seed 02 + 03): นักศึกษา 403, ไม่มีเหตุผล / ลำดับเวลาผิด / รูปแบบผิด 400, scheduled 409, completed เกิน 7 วัน 409, extend คาบ completed 409, check-in-window dryRun ไม่เขียน DB, ของจริง: late→on_time + ลบ absent ของระบบ (reopen) + audit 3 แถว, downgrade ไม่ใช้จนกว่า `applyDowngrades: true`, reanchor คาบที่กำลังเรียน + คาบ completed เมื่อวาน (absent ของระบบคงเดิม), extend ชนคาบอื่นในห้อง 409, PUT รายวิชาที่เลื่อนเวลาเริ่มย้อนไปก่อนตอนนี้: คาบวันนี้คงอยู่ + อยู่ใน `startedSessionsNotChanged`, PUT ซ้ำไม่เปลี่ยน = `[]` · `./dev.sh test` และ `./dev.sh smoke` (15 ข้อ) ผ่าน · FE ยังไม่มีหน้าจอเรียกเส้นเหล่านี้ / ยังไม่แสดง `startedSessionsNotChanged`

### 2026-11-25 — ปรับ README ให้ตรงกับโค้ดปัจจุบัน
- **ทำอะไร:** ย้าย `POST /rooms/{roomId}/sessions` และ `POST /class-sessions/{sessionId}/end` จากตาราง "AI Service → backend (`X-Internal-Key`)" ไปตาราง API หลัก (ผู้ใช้เรียกด้วย JWT · ตรวจสิทธิ์ `requireSectionStaff`)
- **ไฟล์ที่เกี่ยวข้อง:** `README.md`
- **เหตุผล:** เดิมอยู่ผิดตาราง ทำให้เข้าใจว่าต้องใช้ `X-Internal-Key`

### 2026-11-25 — เริ่ม / จบคาบจากหน้าเว็บ
- **ทำอะไร:** เพิ่ม `SessionControlController` / `Service` / `Repository`: `POST /api/rooms/{roomId}/sessions` เริ่มคาบตอนนี้ (สร้างคาบใหม่ หรือย้ายคาบวันนี้ที่ยังไม่ถึงเวลา / ถูกยกเลิกมาเริ่มตอนนี้ · 409 เมื่อกลุ่มเรียนมีคาบที่กำลังเรียน / จบแล้ววันนี้ หรือห้องมีคาบอื่นเวลาทับ · จบอย่างช้า 23:59) และ `POST /api/class-sessions/{sessionId}/end` จบคาบ (end_time = ตอนนี้ อย่างน้อย 1 นาทีหลังเริ่ม · ตัดช่วงเช็คชื่อ · INSERT absent ทันที) · สิทธิ์ `requireSectionStaff` · audit + SSE `session` · คาบปัจจุบันของห้อง (`RoomRepository`) และคาบที่กล้องใช้ (`sessionInRoomAt`) ไม่นับคาบ `completed` แล้ว (จบคาบมีผลทันที)
- **ไฟล์ที่เกี่ยวข้อง:** `src/main/java/com/faculty/attendance/attendance/SessionControl*.java`, `attendance/AttendanceRepository.java`, `room/RoomRepository.java`
- **เหตุผล:** ผู้ใช้ต้องการเริ่มคาบจากหน้าเว็บ ไม่ใช่รัน `03_demo_live_session.sql` เองใน command line
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบกับ backend จริง + Postgres ชั่วคราว: นักศึกษา 403, ไม่ส่ง sectionId 400, อาจารย์เริ่ม 201 (คาบ 3 ชม. ตรงเวลา 15 นาที สาย 30 นาที), เริ่มซ้ำ 409, ห้องแสดงคาบ + gallery เห็นคาบ, จบ 200, จบซ้ำ 409, เริ่มหลังจบในวันเดียวกัน 409, audit 2 แถว · 1 กลุ่มเรียนมีได้ 1 คาบต่อวัน (unique ใน DB) — เริ่มใหม่วันเดียวกันหลังจบไม่ได้

### 2026-11-25 — แก้ gallery ไม่ส่งใบหน้าที่ลงทะเบียนจริง
- **ทำอะไร:** `CameraFeedRepository.embeddingsForSection` เทียบ `lower(model_name) = 'sface'` แทน `model_name = 'sface'`
- **ไฟล์ที่เกี่ยวข้อง:** `src/main/java/com/faculty/attendance/internal/CameraFeedRepository.java`
- **เหตุผล:** ทดสอบบน uat: 66130500041 ลงทะเบียนใบหน้าแล้ว (`face_registered = t`) แต่ ai-service ได้ gallery 0 คน — การลงทะเบียนเก็บ `model_name` ตามที่ AI Service ตอบ (`"SFace"`) ส่วน mock / ทดสอบเดิมใช้ `'sface'` ตัวเล็ก จึงไม่เห็นปัญหา
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบด้วย SQL เดียวกันกับแถว `SFace`: แบบเดิมได้ 0 แบบใหม่ได้ 1 · ไม่ต้องแก้ข้อมูลใน DB

### 2026-11-25 — `/api/internal` สำหรับตัวอ่านกล้องของ AI Service
- **ทำอะไร:** เพิ่ม `GET /api/internal/cameras` (กล้องที่เปิดใช้ในห้องที่เปิดใช้) และ `GET /api/internal/rooms/{roomId}/gallery` (embedding ของนักศึกษาในคาบที่อยู่ในห้องตอนนี้ ใช้ `sessionInRoomAt` และเงื่อนไขเดียวกับ `recordSighting`: enrollment active + consent ล่าสุด granted + `users.is_active`) · คาบปิดเช็คชื่อด้วยกล้อง → `cameraEnabled: false` และ `students` ว่าง · ตรวจ `X-Internal-Key` เหมือนเส้นอื่น
- **ไฟล์ที่เกี่ยวข้อง:** `src/main/java/com/faculty/attendance/internal/InternalController.java`, `internal/CameraFeedRepository.java`
- **เหตุผล:** ให้ AI Service อ่านภาพกล้องจาก MediaMTX แล้วเช็คชื่อได้จริง (เดิมมีแค่เส้นรับผล heartbeat / faces / sightings แต่ไม่มีทางรู้ว่าต้องอ่านกล้องไหน และต้องจับคู่กับใคร)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** embedding ส่งออกแบบเข้ารหัส (ตาม `EmbeddingCipher`: AI Service ถอดด้วย `FACE_EMBEDDING_KEY`) · ตรวจกับ Postgres ชั่วคราว + `02_seed_mock.sql` + `03_demo_live_session.sql`: cameras 5 ตัว, gallery ห้อง 4302 = 9 คน 29 ชุด, ห้อง 4303 (ไม่มีคาบ) = ว่าง, ไม่มี key 401, ห้องไม่มี 404 · ตรวจว่า Python ถอดรหัส embedding ที่ `EmbeddingCipher` (Java จริง) เข้ารหัสได้ และ AAD คนละคน = ถอดไม่ได้

### 2026-11-24 — `GET /api/test-accounts` ให้หน้า Login แสดงบัญชีจาก DB
- **ทำอะไร:** เพิ่ม `TestAccountsController` คืนรายชื่อ users ทั้งหมด (อีเมล ชื่อ role is_active รหัสนักศึกษา มีใบหน้าหรือไม่ ความยินยอมล่าสุด ต้องยินยอมใหม่ จำนวนวิชาเทอมนี้ / วิชาที่สอน) · ไม่มีรหัสผ่าน / hash / embedding · มี bean เฉพาะเมื่อ `app.test-accounts.enabled=true` (`TEST_ACCOUNTS_ENABLED`, ค่าเริ่มต้น `false` = 404, โปรไฟล์ `local` = `true`) · `SecurityConfig` ให้ GET path นี้ไม่ต้องมี token · เปิดอยู่จะมี WARN ใน log
- **ไฟล์ที่เกี่ยวข้อง:** `src/main/java/com/faculty/attendance/auth/TestAccountsController.java`, `src/main/java/com/faculty/attendance/auth/SecurityConfig.java`, `src/main/resources/application.yml`, `src/main/resources/application-local.yml`, `README.md`
- **เหตุผล:** หน้า Login เคยใช้รายชื่อที่เขียนตายตัวใน frontend (ต้องแก้ตาม seed ทุกครั้ง) — ตอนนี้แสดงข้อมูลที่อยู่ใน DB จริง
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบกับ DB ในเครื่อง: เปิด → 200 (44 บัญชี), ปิด → 404 · ห้ามตั้ง `TEST_ACCOUNTS_ENABLED=true` ใน prod (เปิดเผยรายชื่อผู้ใช้โดยไม่ต้อง login) · `db/README.md` ยังอ้างว่าปุ่มในหน้า Login มาจาก `test-accounts.js` — ไม่ได้แก้ใน commit นี้เพราะแก้ไฟล์ใน `backend/db/` จะทำให้ CD หยุด (แก้พร้อม commit ที่เปลี่ยน DB ครั้งถัดไป)

### 2026-11-22 — ตัวเลือกห้อง / อาจารย์สำหรับฟอร์มรายวิชา
- **ทำอะไร:** เพิ่ม `GET /api/rooms/options` (ทุกห้องที่ใช้งานได้) และ `GET /api/lecturers` (อาจารย์ที่ใช้งานได้) สำหรับฟอร์มเพิ่ม/แก้ไขรายวิชาของ FE · staff เท่านั้น
- **ไฟล์ที่เกี่ยวข้อง:** `src/main/java/com/faculty/attendance/course/LookupController.java` (ใหม่)
- **เหตุผล:** `GET /rooms` (B1) กรองเฉพาะห้องที่อาจารย์มีกลุ่มเรียน ใช้เลือกห้องตอนสร้างวิชาใหม่ไม่ได้ และ admin ต้องเลือกผู้สอนให้กลุ่มเรียน
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** backend ที่เปิดค้างไว้ต้อง restart ถึงจะมี 2 เส้นนี้

### 2026-11-22 — โปรไฟล์ `local`: รันบนเครื่องได้ทันทีโดยไม่ต้องตั้ง env
- **ทำอะไร:** เพิ่ม `application-local.yml` (key ทดสอบ: JWT, `FACE_EMBEDDING_KEY`, `INTERNAL_API_KEY=local-test-key`, AI ที่ `localhost:8080`) และตั้ง `spring.profiles.default: local` · อ่านค่าส่วนตัวจาก `backend/.env.local` (`spring.config.import`) · `server.port` อ่าน `SERVER_PORT` · ย้ายค่า JWT dev ออกจาก `application.yml` (โปรไฟล์ `prod` บังคับตั้ง) · `LocalProfileWarning` เขียน WARN ทุกครั้งที่รันโปรไฟล์ `local` · Dockerfile ตั้ง `SPRING_PROFILES_ACTIVE=prod`
- **ไฟล์ที่เกี่ยวข้อง:** `src/main/resources/application.yml`, `src/main/resources/application-local.yml` (ใหม่), `src/main/java/com/faculty/attendance/common/LocalProfileWarning.java` (ใหม่), `Dockerfile`, `README.md`
- **เหตุผล:** คนอื่นในทีม clone แล้วรันได้เลย ไม่เจอ error `ต้องตั้ง FACE_EMBEDDING_KEY` และค่าทดสอบไม่มีทางหลุดไปใช้บน server
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบแล้ว: รันโปรไฟล์ `local` โดยไม่ตั้ง env ใช้ได้, `.env.local` ทับ `DB_URL` / `SERVER_PORT` ได้, โปรไฟล์ `prod` ที่ไม่ตั้ง key ไม่ยอม start · key ในโปรไฟล์ `local` ห้ามใช้กับข้อมูลใบหน้าจริง

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

### 2026-11-21 — B7 ถอนความยินยอม + ลบข้อมูลใบหน้า
- **ทำอะไร:** เพิ่ม `DELETE /api/students/{studentId}/face` ทำตามขั้น PDPA ข้อ 2 ใน `docs/database/database-design.md` ใน transaction เดียว: INSERT `consent_records` (`withdrawn`, อ้างฉบับเดิม) → DELETE `face_embeddings` → INSERT `data_deletion_jobs` (`reason = withdrawn`, `completed_at = now()`, `deleted_count`) → INSERT `audit_logs` (`face.consent_withdrawn`) · ตอบ `{ studentId, registered: false, withdrawnAt, deletedCount }` ตามที่ FE (`withdrawConsent`) รอ · ถ้ายังไม่ได้ให้ / ถอนไปแล้ว ตอบ 409 · ทำได้เฉพาะนักศึกษาเจ้าของบัญชีหรือ admin · ประวัติเช็คชื่อไม่ลบ
- **ไฟล์ที่เกี่ยวข้อง:** `face/FaceController.java`, `face/FaceRegistrationService.java`, `face/StudentFaceRepository.java`, `face/FaceDtos.java`, `audit/AuditLogRepository.java` (ใหม่)
- **เหตุผล:** PDPA กำหนดให้ถอนความยินยอมได้ง่ายเท่ากับตอนให้ และต้องลบข้อมูลชีวภาพจริง
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ยังไม่ได้แจ้ง AI Service (ยังไม่มี endpoint ฝั่ง AI) — มี TODO ใน `FaceRegistrationService.withdraw`

### 2026-11-21 — แก้โค้ด Back-End ให้ตรงกับ schema ใน `db/01_schema.sql`
- **ทำอะไร:**
  - เปลี่ยนชื่อตาราง/คอลัมน์ใน SQL ทั้งหมดให้ตรง schema: `students` → `student_profiles`, `lecturers` (ไม่มีแล้ว ใช้ `users.role`), `course_sections` → `sections` + `semesters` + `section_lecturers`, `course_enrollments` → `enrollments` (`status = 'active'`), `biometric_*` / `student_face_embeddings` → `consent_records` + `face_embeddings`, PK `id` → `user_id` / `course_id` / `section_id` / `session_id`, `users.status` → `is_active`, `class_sessions.status` → `session_status`, `check_out_time` → `last_seen_at`
  - สิทธิ์อาจารย์ตรวจจาก `section_lecturers` (1 section หลายอาจารย์ได้) · `lecturerId` ใน JWT = `users.user_id`
  - ซ่อนวิชา/กลุ่มเรียนที่ soft delete (`deleted_at`) · คาบ `cancelled` ไม่นับเป็นขาด
  - ลงทะเบียนใบหน้า: INSERT `consent_records` (granted) → **ลบ** embedding เดิมจริง → INSERT ชุดใหม่ ใน transaction เดียว (เดิมแค่ `is_active = false` ซึ่งผิดข้อความยินยอม v1 และชน trigger จำกัด 5 ชุด) · ตรวจว่าข้อความยินยอมเป็นฉบับปัจจุบัน (`consent_versions.is_current`) · รับ `ageConfirmed`
  - เข้ารหัส embedding ด้วย AES-256-GCM ก่อนเก็บ (`EmbeddingCipher`) แทนการเก็บ pgvector แบบ plaintext
  - `/face/status` เพิ่ม `consentStatus`, `consentVersion`, `consentedAt`, `needsReconsent`
  - JSON ของ API คงชื่อ field เดิมให้ FE (`department` = `program`, `yearLevel` คำนวณจาก `entry_year`) และเพิ่ม `entryYear`, `title`
  - เวลา timestamptz แปลงเป็นเวลาไทยเสมอ ไม่ขึ้นกับ timezone ของ JVM
  - `application.yml`: DB เริ่มต้น `bangmod_attendance`, ปิด Flyway เป็นค่าเริ่มต้น, เพิ่ม `app.face.embedding-key`
- **ไฟล์ที่เกี่ยวข้อง:** `auth/AuthRepository.java`, `auth/AuthService.java`, `auth/AuthDtos.java`, `auth/AccessService.java`, `dashboard/DashboardRepository.java`, `face/StudentFaceRepository.java`, `face/FaceRegistrationService.java`, `face/FaceController.java`, `face/FaceDtos.java`, `face/EmbeddingCipher.java` (ใหม่), `src/main/resources/application.yml`
- **เหตุผล:** โค้ดเดิมเขียนตาม schema ชุดเก่า (Flyway V1–V9 ที่ไม่อยู่ใน repo) ทำให้ query พังเกือบทุก endpoint เมื่อรันกับ `db/01_schema.sql`
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:**
  - ทดสอบ SQL ทุกตัวกับ PostgreSQL 16 + `02_seed_mock.sql` แล้ว แต่ยังไม่ได้ `mvn compile` (ดาวน์โหลด dependency ในเครื่องที่ทดสอบไม่ได้) — ต้อง build อีกรอบ
  - ต้องตั้ง `FACE_EMBEDDING_KEY` · AI Service ต้องถอดรหัสแบบเดียวกัน (nonce 12 ไบต์ + ciphertext + tag 16 ไบต์, AAD = `student_id`, float32 little-endian)
  - FE ควรส่ง `ageConfirmed=true` ตอนลงทะเบียน (ตอนนี้ยังไม่ส่ง → บันทึกเป็น `false`)
  - ยังไม่ได้ทำ B1–B6, B8, B9
