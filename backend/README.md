# backend

Spring Boot REST + SSE · PostgreSQL

| โฟลเดอร์ | เนื้อหา | สถานะ |
|---|---|---|
| [`db/`](db/) | สคริปต์สร้างฐานข้อมูล `bangmod_attendance` (20 ตาราง + 4 view) และข้อมูล Mock-Up ภาค 1/2569 | พร้อมใช้ (Release 1) |
| [`src/main/java/.../auth`](src/main/java/com/faculty/attendance/auth) | Login (JWT), `/api/me`, ตรวจสิทธิ์ admin / lecturer / student | ตรง schema แล้ว |
| [`src/main/java/.../dashboard`](src/main/java/com/faculty/attendance/dashboard) | API 5.4–5.9 (Dashboard, Session Report, ประวัติรายคน) | ตรง schema แล้ว |
| [`src/main/java/.../face`](src/main/java/com/faculty/attendance/face) | ลงทะเบียนใบหน้า, สถานะ, ถอนความยินยอม (B7) | ตรง schema แล้ว |
| [`src/main/java/.../audit`](src/main/java/com/faculty/attendance/audit) | เขียน `audit_logs` | ใช้กับ B7 |

เริ่มจาก [`db/README.md`](db/README.md): วิธีรัน, บัญชี mock, view สำหรับ API และสิ่งที่ Backend ต้องทำเอง

## รันบนเครื่อง

1. สร้างฐานข้อมูลตาม [`db/README.md`](db/README.md) (`00_create_database.sql` → `01_schema.sql` → `02_seed_mock.sql`)
2. ตั้ง environment variable

   | ตัวแปร | ค่า | บังคับ |
   |---|---|---|
   | `DB_URL` | ค่าเริ่มต้น `jdbc:postgresql://localhost:5432/bangmod_attendance` | |
   | `DB_USER` / `DB_PASSWORD` | ค่าเริ่มต้น `postgres` / `postgres` | |
   | `JWT_SECRET` | ยาว ≥ 32 ตัวอักษร (`openssl rand -base64 48`) | ✓ (prod) |
   | `FACE_EMBEDDING_KEY` | base64 ของ 32 ไบต์ (`openssl rand -base64 32`) — ไม่ตั้งแอปจะไม่ start | ✓ |
   | `AI_SERVICE_URL` | ค่าเริ่มต้น `http://ai-service:8080` | |
   | `FLYWAY_ENABLED` | ค่าเริ่มต้น `false` (schema มาจาก `db/*.sql`) | |

3. `mvn spring-boot:run` → `http://localhost:8081`

## API ที่มีตอนนี้

| Method | Path | หน้าที่ |
|---|---|---|
| POST | `/api/auth/login` | Login ด้วยอีเมล + รหัสผ่าน |
| GET | `/api/me` | โปรไฟล์ผู้ใช้ + สถานะใบหน้า (นักศึกษา) |
| GET | `/api/courses` | รายวิชา/กลุ่มเรียนตามสิทธิ์ |
| GET | `/api/courses/{courseId}/attendance/summary` · `/sessions` · `/students` | Dashboard รายวิชา |
| GET | `/api/class-sessions/{sessionId}/attendance/summary` · `/attendance` | Session Report |
| GET | `/api/courses/{courseId}/students/{studentId}/attendance` | ประวัติรายคน |
| POST | `/api/students/{studentId}/face/register` | ลงทะเบียนใบหน้า 3–5 ภาพ (multipart) |
| GET | `/api/students/{studentId}/face/status` | สถานะใบหน้า + ความยินยอม |
| DELETE | `/api/students/{studentId}/face` | B7 ถอนความยินยอม + ลบใบหน้าทันที |

## บันทึกการทำงาน (Changelog)

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
