# Database Design — Bangmod Smart Attendance (Release 1)

> สถานะ: **Idea** (ยังไม่ใช่ schema สุดท้าย) · ปรับปรุง 21 พ.ย. 2569 หลังเทียบกับแบบ Sonnet ([comparison](db-design-comparison.md))
> Jira: CP-105 (idea), CP-85 (Conceptual DB Design) · SQL ที่ใช้จริง: [`backend/db/`](../../backend/db/)
> เอกสารต้นฉบับ (แก้ไข/คอมเมนต์ได้): [Database Design (Idea)](https://claude.ai/artifact/85qfBkTYTy2F6RsACQ4K99)

PostgreSQL ของ Release 1 มี **17 ตาราง** ใน 6 กลุ่ม และเตรียมตารางของ R2–R3 ไว้อีก 3 ตาราง (รวม 20) + 4 view

**หลักการออกแบบ**

- ตารางเรียน ห้อง ช่วงเช็คชื่อ และสวิตช์กล้องผูกที่ `sections` ไม่ใช่ `courses` เพราะ 1 วิชามีหลายกลุ่มที่เรียนต่างห้องต่างเวลา (D1)
- `class_sessions` เก็บสำเนาเวลาและกฎ ณ ตอนสร้างคาบ แก้ตารางเรียนแล้วมีผลเฉพาะคาบที่ยังไม่เริ่ม ผลเช็คชื่อย้อนหลังไม่เปลี่ยน (D2)
- **ไม่เก็บรูปใบหน้า** เก็บเฉพาะ embedding (เข้ารหัส) และลบจริงเมื่อถอนความยินยอม — ตรงตามข้อความยินยอม v1
- หลักฐานความยินยอมเป็นแบบเพิ่มแถวอย่างเดียว (ให้ / ถอน / ให้ใหม่) ไม่เขียนทับ (D5)
- กลุ่มเสี่ยงไม่เก็บในตาราง คำนวณจากจำนวนครั้งที่ขาด (0–1 Low, 2 Medium, 3+ High)
- PK เป็น `UUID` · ชื่อ `snake_case` · ค่าแบบ enum ใช้ `VARCHAR + CHECK` · เวลาเป็น `timestamptz` (DB timezone `Asia/Bangkok`)

## ER Diagram

```mermaid
erDiagram
    users ||--o| student_profiles : "is"
    users ||--o{ section_lecturers : "teaches"
    users ||--o{ audit_logs : "acts"
    rooms ||--|| cameras : "has"
    rooms ||--o{ sections : "hosts"
    rooms ||--o{ class_sessions : "hosts"
    semesters ||--o{ sections : "contains"
    courses ||--o{ sections : "offered as"
    sections ||--|{ section_class_days : "meets on"
    sections ||--|{ section_lecturers : "taught by"
    sections ||--o{ enrollments : "has"
    sections ||--o{ class_sessions : "generates"
    student_profiles ||--o{ enrollments : "enrolls"
    class_sessions ||--o{ attendance_records : "records"
    student_profiles ||--o{ attendance_records : "attends"
    student_profiles ||--o{ consent_records : "gives"
    consent_versions ||--o{ consent_records : "version of"
    consent_records ||--o{ face_embeddings : "authorises"
    student_profiles ||--o{ face_embeddings : "owns"
    student_profiles ||--o{ data_deletion_jobs : "queued for"
    enrollments ||--o{ follow_ups : "R2"
    class_sessions ||--o{ occupancy_snapshots : "R2"
    users ||--o{ notifications : "R3"

    users {
        uuid user_id PK
        varchar email UK
        varchar password_hash
        varchar title
        varchar first_name
        varchar last_name
        varchar role "student | lecturer | admin"
        boolean is_active
    }
    student_profiles {
        uuid student_id PK, FK
        varchar student_code UK
        varchar faculty
        varchar program
        smallint entry_year
        boolean is_adult "B9, waits for DPO"
        varchar academic_status "active | graduated | withdrawn"
    }
    rooms {
        uuid room_id PK
        varchar room_code UK
        varchar building
        smallint capacity
    }
    cameras {
        uuid camera_id PK
        uuid room_id FK, UK
        varchar camera_code UK
        varchar stream_path
        varchar status "live | no_signal"
        timestamptz last_seen_at
    }
    semesters {
        uuid semester_id PK
        smallint academic_year
        smallint term
        date start_date
        date end_date
    }
    courses {
        uuid course_id PK
        varchar course_code "INT101"
        varchar course_name
        timestamptz deleted_at
    }
    sections {
        uuid section_id PK
        uuid course_id FK
        uuid semester_id FK
        varchar section_code
        uuid room_id FK
        time start_time
        time end_time
        time check_in_opens
        time check_in_closes
        smallint on_time_minutes
        time late_until "R2 rule"
        boolean camera_attendance_enabled
    }
    section_class_days {
        uuid section_id PK, FK
        smallint day_of_week PK
    }
    section_lecturers {
        uuid section_id PK, FK
        uuid lecturer_id PK, FK
        boolean is_primary
    }
    enrollments {
        uuid section_id PK, FK
        uuid student_id PK, FK
        varchar status "active | dropped"
        timestamptz dropped_at
    }
    class_sessions {
        uuid session_id PK
        uuid section_id FK
        smallint session_no
        date session_date
        uuid room_id FK
        time on_time_until
        time late_until
        varchar session_status
    }
    attendance_records {
        uuid attendance_id PK
        uuid session_id FK
        uuid student_id FK
        varchar status "on_time | late | absent"
        timestamptz check_in_time
        timestamptz last_seen_at
        varchar source "camera | manual | system"
        numeric match_score
        uuid recorded_by FK
    }
    consent_versions {
        varchar version PK
        char content_sha256
        boolean is_current
    }
    consent_records {
        uuid consent_id PK
        uuid student_id FK
        varchar version FK
        varchar action "granted | withdrawn"
        inet ip_address
    }
    face_embeddings {
        uuid embedding_id PK
        uuid student_id FK
        uuid consent_id FK
        bytea embedding "AES-GCM"
        varchar model_version
    }
    data_deletion_jobs {
        uuid job_id PK
        uuid student_id FK
        varchar reason
        timestamptz due_at
        timestamptz completed_at
    }
    audit_logs {
        uuid log_id PK
        uuid actor_id FK
        varchar action
        varchar entity_type
        uuid entity_id
        jsonb before_data
        jsonb after_data
    }
    follow_ups {
        uuid follow_up_id PK
        uuid section_id FK
        uuid student_id FK
        text note
        varchar status
    }
    occupancy_snapshots {
        bigint snapshot_id PK
        uuid session_id FK
        smallint faces_detected
        smallint faces_recognized
        smallint unknown_faces
    }
    notifications {
        uuid notification_id PK
        uuid user_id FK
        varchar type
        uuid session_id FK
        timestamptz read_at
    }
```

คอลัมน์ครบทุกตัว พร้อม constraint และคำอธิบาย อยู่ใน [`backend/db/01_schema.sql`](../../backend/db/01_schema.sql)

## ตารางแต่ละกลุ่ม

| กลุ่ม | ตาราง | หน้าที่ |
|---|---|---|
| 1 ผู้ใช้ | `users` | บัญชีทุก role, login ด้วยอีเมล + BCrypt |
| | `student_profiles` | ข้อมูลนักศึกษา 1:1 กับ users (`student_id` = `user_id`) |
| 2 ห้องและกล้อง | `rooms` | ห้องเรียน |
| | `cameras` | 1 กล้องต่อห้อง, path ใน MediaMTX, สถานะ `live` / `no_signal` |
| 3 รายวิชาและตารางเรียน | `semesters` | ภาคเรียน ใช้กำหนดช่วงสร้างคาบ |
| | `courses` | รายวิชา (soft delete) |
| | `sections` | กลุ่มเรียน: ห้อง เวลา ช่วงเช็คชื่อ กฎตรงเวลา/สาย สวิตช์กล้อง |
| | `section_class_days` | วันเรียนต่อสัปดาห์ (หลายวันได้) |
| | `section_lecturers` | ผู้สอน (ใช้ตรวจสิทธิ์ "เห็นเฉพาะกลุ่มที่สอน") |
| | `enrollments` | นักศึกษาในกลุ่ม + วันถอน |
| | `class_sessions` | คาบแต่ละครั้ง + เลขคาบ + สำเนาเวลา/กฎ + สถานะ |
| 4 การเข้าเรียน | `attendance_records` | ผลเช็คชื่อ 1 แถวต่อคนต่อคาบ แยกที่มา camera / manual / system |
| 5 ใบหน้าและ PDPA | `consent_versions` | ฉบับข้อความยินยอม + hash |
| | `consent_records` | หลักฐานให้ / ถอนความยินยอม (append-only) |
| | `face_embeddings` | เวกเตอร์ใบหน้า 3–5 ชุดต่อคน ไม่มีรูป |
| | `data_deletion_jobs` | หลักฐานการลบใบหน้าตามกำหนด (D7) |
| 6 ระบบ | `audit_logs` | ใครแก้อะไร: เช็คชื่อด้วยมือ เปิด-ปิดกล้อง ลบใบหน้า ลบวิชา |
| R2 | `follow_ups` | ปุ่ม Follow up ในหน้า At-Risk (ผูกกับ enrollment) |
| R2 | `occupancy_snapshots` | จำนวนใบหน้าในเฟรมระหว่างคาบ สำหรับ Discrepancy Check |
| R3 | `notifications` | แจ้งเตือนลงทะเบียนใบหน้า เตือนก่อนคาบ ยกคลาส ฯลฯ |

Attendance Rules ของ R2 ไม่ต้องมีตารางใหม่ ใช้ `sections.on_time_minutes` และ `sections.late_until`

## กติกาทางธุรกิจ

**จัดสถานะตอนกล้องเห็นครั้งแรก** (ตัวอย่าง Proposal: เปิด 08:45 · ตรงเวลาถึง 09:05 · สายถึง 09:30)

| เวลาที่เห็น | สถานะ | เขียนลง DB |
|---|---|---|
| `check_in_opens` – `on_time_until` | on_time | INSERT `source = camera` |
| หลัง `on_time_until` – `late_until` | late | INSERT `source = camera` |
| หลัง `late_until` | — | ไม่เปลี่ยนสถานะ อัปเดตแค่ `last_seen_at` |
| ปิดช่วงเช็คชื่อ | absent | job INSERT `source = system` ให้คนที่ยังไม่มีแถว |

- กล้องเขียนเฉพาะเมื่อ `camera_attendance_enabled = true` และ consent ล่าสุดเป็น `granted`
- กล้องไม่เขียนทับแถว `manual`; อาจารย์แก้ได้ทุกแถวพร้อม `audit_logs`
- `attendanceRate` = (on_time + late) ÷ คาบที่เริ่มแล้ว × 100

**PDPA**

1. ลงทะเบียน: INSERT `consent_records` (granted) → สร้าง embedding → ลบชุดเดิม + INSERT ชุดใหม่ใน transaction เดียว
2. ถอน (B7): INSERT `consent_records` (withdrawn) → DELETE `face_embeddings` → INSERT `data_deletion_jobs` ที่ `completed_at = now()` → แจ้ง AI Service
3. พ้นสภาพ / สิ้นสุดระบบ: INSERT `data_deletion_jobs` ที่ `due_at = +30 วัน` → job รายวันลบ
4. ประวัติเช็คชื่อก่อนถอนไม่ลบ (ไม่ใช่ข้อมูลใบหน้า)

**ลบวิชา** — soft delete (`deleted_at`) เพราะผลเช็คชื่ออ้างถึงคาบของวิชานั้น

## หน้าจอ / API → ตาราง

| หน้าจอ | API | ตาราง / view |
|---|---|---|
| Login, Me | `POST /auth/login`, `GET /me` (+B9) | users, student_profiles, `v_face_registration` |
| ลงทะเบียน / จัดการใบหน้า | 5.10, face/status, B7 | consent_records, face_embeddings, data_deletion_jobs |
| หน้าแรก / วิชาของฉัน / ประวัติ | `GET /courses`, attendance ของนักศึกษา | enrollments, sections, courses, class_sessions, attendance_records |
| Room Selection | B1 | `v_room_status` |
| Room Live | B2–B4, 5.7–5.8 | cameras, class_sessions, attendance_records (จำนวนใบหน้าในเฟรมมาทาง SSE) |
| Manage / Edit Course | B5, B6 | courses, sections, section_class_days, section_lecturers |
| Registration Status | 5.6 `faceRegistered=false` | enrollments + `v_face_registration` |
| Analytics / At-Risk | 5.4–5.6 | `v_student_section_attendance` |
| Session Report + เช็คชื่อด้วยมือ | 5.7–5.8, B8 | class_sessions, attendance_records, audit_logs |

## คำถามที่ยังเปิดอยู่

- [ ] 1 section มีอาจารย์หลายคน (`section_lecturers`) — แต่ B5 ส่ง `lecturerId` ตัวเดียว
- [ ] แต่ละวันเรียนใช้เวลา/ห้องต่างกันได้ไหม (ถ้าได้ → `section_schedules`)
- [ ] Q5 ลบวิชา: soft delete
- [ ] Q7 เข้ารหัส embedding: AES-GCM ระดับแอป (ต้องตรงกับข้อ 8 ของข้อความยินยอม)
- [ ] Q8 ค่า `session_status`: scheduled / in_progress / completed / cancelled
- [ ] สถานะ "ลา" (`excused`) — Sonnet มี แต่ API v1.0 ไม่มี
- [ ] ชื่อตารางแบบ MS_ / TR_ หรือ snake_case ตรงๆ
- [ ] B9 และระยะเก็บข้อมูล 30 วัน — รอ DPO
