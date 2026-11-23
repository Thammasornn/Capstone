# backend/db — PostgreSQL schema + mock data (Release 1)

ฐานข้อมูล `bangmod_attendance` สำหรับ Spring Boot · PostgreSQL 16+ · 20 ตาราง (R1 17 + R2–R3 3) และ 4 view
แบบที่ใช้: [`docs/database/database-design.md`](../../docs/database/database-design.md)

| ไฟล์ | ทำอะไร | รันที่ |
|---|---|---|
| `00_create_database.sql` | สร้าง DB `bangmod_attendance` + ตั้ง timezone `Asia/Bangkok` | ทุก env (ครั้งเดียว) |
| `01_schema.sql` | ตาราง, constraint, index, trigger, view | ทุก env |
| `02_seed_mock.sql` | ข้อมูล Mock-Up ภาค 1/2569 (ล้างทุกตารางก่อนใส่) | **local / UAT เท่านั้น** |
| `03_demo_live_session.sql` | สร้างคาบที่ "กำลังเรียนอยู่ตอนนี้" ไว้เดโม Room Live | local / UAT (ไม่บังคับ) |
| `04_add_team_students.sql` | เพิ่มบัญชีสมาชิกทีม 3 คน (ยังไม่ลงทะเบียนใบหน้า) เข้า DB ที่มี mock อยู่แล้ว **โดยไม่ล้างข้อมูล** · รันซ้ำได้ | local / UAT (ไม่บังคับ · ห้าม Production) |
| `test/assertions.sql` | ตรวจ schema + ข้อมูล mock (CI ใช้) | local / CI |

> ⚠️ `02_seed_mock.sql` เริ่มด้วย `TRUNCATE` ทุกตาราง — **ห้ามรันบน Production**

## รัน

```bash
psql -U postgres -f 00_create_database.sql
psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 -f 01_schema.sql
psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 -f 02_seed_mock.sql
psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 -f 03_demo_live_session.sql   # ไม่บังคับ
psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 -f test/assertions.sql        # ตรวจ
```

ด้วย Docker (ไม่ต้องลง PostgreSQL ในเครื่อง):

```bash
docker run -d --name attendance-db -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16
for f in 00_create_database.sql; do docker exec -i attendance-db psql -U postgres -v ON_ERROR_STOP=1 < $f; done
for f in 01_schema.sql 02_seed_mock.sql 03_demo_live_session.sql test/assertions.sql; do
  docker exec -i attendance-db psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 < $f
done
```

`02` และ `03` รันซ้ำได้ — `02` คืนข้อมูลกลับเป็นชุดเดิมทุกครั้ง, `03` ย้ายคาบเดโมมาที่เวลาปัจจุบัน

**เพิ่มบัญชีทีมเข้า uat ที่มีข้อมูลอยู่แล้ว** (ไม่อยากรัน `02` ใหม่เพราะจะลบใบหน้าที่ลงทะเบียนไว้) — `02` มีบัญชีทีมอยู่แล้ว ใช้ `04` เฉพาะ DB ที่ seed ไว้ก่อนหน้านี้:

```bash
sudo k3s kubectl -n uat exec -i statefulset/postgres -- \
  psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 < backend/db/04_add_team_students.sql
```

`04` ตรวจก่อนว่ามีกลุ่มเรียน mock `INT201-001` — ไม่มี (เช่น Production) จะ error และไม่เพิ่มอะไร

## บัญชี Mock (รหัสผ่านทุกบัญชี `Password@123`)

| role | อีเมล | หมายเหตุ |
|---|---|---|
| admin | `admin@sit.kmutt.ac.th` | |
| admin | `piyanuch.k@sit.kmutt.ac.th` | |
| lecturer | `thanakorn.w@sit.kmutt.ac.th` | INT101-001, INT101-002, INT301-001 (ผู้สอนร่วม) |
| lecturer | `supawadee.c@sit.kmutt.ac.th` | INT102-001, INT201-001 |
| lecturer | `nattapon.r@sit.kmutt.ac.th` | INT103-001 |
| lecturer | `kamonwan.s@sit.kmutt.ac.th` | INT202-001, INT302-001 |
| lecturer | `pakorn.l@sit.kmutt.ac.th` | INT301-001 (ผู้สอนหลัก) |
| student | `<รหัสนักศึกษา>@st.sit.kmutt.ac.th` | เช่น `69130500001@st.sit.kmutt.ac.th` · บัญชีตามสถานะดูตารางด้านล่าง |

**นักศึกษาตามสถานะ** (ตรงกับปุ่มบัญชีทดสอบในหน้า Login · `frontend/src/features/auth/test-accounts.js`)

| สถานะที่ต้องการทดสอบ | อีเมล (`@st.sit.kmutt.ac.th`) | ใช้ทดสอบอะไร |
|---|---|---|
| ลงทะเบียนใบหน้าแล้ว (ปกติ) | `69130500001` | ทุกหน้า · ปี 1 · 3 วิชา |
| ยังไม่เคยลงทะเบียนใบหน้า | `69130500011` | แถบเตือนหน้าแรก · ลงทะเบียนครั้งแรก · อาจารย์เช็คชื่อด้วยมือ |
| ถอนความยินยอมแล้ว | `68130500005` | สถานะ "ถอนความยินยอมแล้ว" · ลงทะเบียนใหม่ |
| ต้องยินยอมใหม่ (ยินยอมฉบับเก่า `v0`) | `69130599001` | คำเตือน `needsReconsent` ในหน้าจัดการข้อมูลใบหน้า |
| ยินยอมแล้วแต่ยังไม่มีใบหน้า | `69130599002` | ส่งรูปไม่สำเร็จ · ถอนความยินยอมทั้งที่ไม่มีใบหน้า |
| อายุไม่ถึง 20 ปี (`is_adult = false`, B9) | `69130599003` | ยังไม่ได้ยินยอม · เช็คชื่อด้วยมือ (หน้ายินยอมไม่ถามอายุแล้ว — ค่า `is_adult` มีแค่ในฐานข้อมูล) |
| กลุ่มเสี่ยงสูง (ขาด 3–5 ครั้ง) | `69130500004` | นักศึกษาต้องไม่เห็นระดับความเสี่ยง · หน้า At-Risk ของอาจารย์ |
| กลุ่มเสี่ยงปานกลาง (ขาด 2 ครั้ง) | `69130500009` | เหมือนข้างบน |
| ถอนรายวิชา | `68130500012` | ถอน INT201 → เหลือ 1 วิชา |
| ไม่มีวิชาเทอมนี้ (ปี 4 ฝึกงาน) | `66130599004` | หน้าว่าง ("ยังไม่มีข้อมูล" / "ยังไม่มีรายวิชา") |
| จบการศึกษา | `65130500017` | login ไม่ได้ (บัญชีถูกปิด) |
| ลาออก | `66130599005` | login ไม่ได้ (บัญชีถูกปิด) |
| สมาชิกทีม (ยังไม่ลงทะเบียนใบหน้า) | `66130500013`, `66130500036`, `66130500041` | ลงทะเบียนใบหน้าด้วยรูปตัวเองจริง · อยู่ใน INT201-001 |


รหัสผ่านเก็บเป็น BCrypt (`$2a$`, cost 10) จาก `pgcrypto` — ใช้กับ `BCryptPasswordEncoder` ของ Spring Security ได้ตรงๆ
ชื่อคนเป็นชื่อสมมติ ยกเว้นสมาชิกทีม 3 คน (ชื่อจริงจาก README ที่ root · ไม่มีข้อมูลใบหน้าใน seed) · embedding เป็น byte สุ่ม ไม่ใช่ใบหน้าจริง

## ข้อมูล Mock-Up

ภาค 1/2569 (10 ส.ค. – 11 ธ.ค. 2026) เขียน "ณ วันเสาร์ 21 พ.ย. 2026": คาบก่อนหน้านั้น `completed` และมีผลเช็คชื่อ, หลังจากนั้น `scheduled`
ไม่มีเรียนสัปดาห์สอบกลางภาค (28 ก.ย. – 2 ต.ค.) · วันหยุด 12 ส.ค. และ 13 ต.ค. เป็น `cancelled`

| กลุ่มเรียน | วัน / เวลา | ห้อง | ช่วงเช็คชื่อ | นักศึกษา |
|---|---|---|---|---|
| INT101-001 Information Technology Fundamentals | จ. 09:00–12:00 | 4301 | 09:00–09:30 (ตรงเวลา 15 นาที) | ปี 1 (6) + persona ปี 1 (3) |
| INT101-002 | จ. 13:00–16:00 | 4301 | 13:00–13:30 | ปี 1 (6) |
| INT102-001 Web Technology | พ. 09:00–12:00 | 4302 | 09:00–09:30 | ปี 1 (12) + persona (3) |
| INT103-001 Programming Fundamentals | อ. + พฤ. 09:00–10:30 | 4303 | 09:00–09:20 (ตรงเวลา 10 นาที) | ปี 1 (12) + persona (3) |
| INT201-001 Client-Side Web Programming | อ. 13:00–16:00 | 4302 | 13:00–13:30 | ปี 2 (12, ถอน 1) + สมาชิกทีม 3 |
| INT202-001 Database Management Systems | พ. 13:00–16:00 | 4303 | **ปิดกล้อง** — อาจารย์เช็คชื่อเอง | ปี 2 (12) + ปี 3 ลงซ้ำ 2 |
| INT301-001 Software Engineering | จ. + พ. 13:00–14:30 | 4304 | 13:00–14:30, ตรงเวลา 20 นาที, **สายถึง 13:30** (กฎแบบ R2) | ปี 3 (12) |
| INT302-001 Information Security | พฤ. 13:00–16:00 | 4305 (กล้อง `no_signal`) | 13:00–13:30 | ปี 3 (12) |

INT299 เป็นวิชาที่ถูก soft delete (`deleted_at`) ไว้เป็นตัวอย่าง

**กรณีทดสอบที่ใส่ไว้**

| นักศึกษา | กรณี |
|---|---|
| 69130500004, 68130500003, 67130500002 | ขาด 3–5 ครั้งต่อวิชา → **High** |
| 69130500009, 68130500007, 67130500006, 67130500008 | ขาด 2 ครั้งต่อวิชา → **Medium** |
| 69130500011, 68130500010, 67130500011 | ไม่เคยลงทะเบียนใบหน้า → อาจารย์เช็คชื่อด้วยมือ (`source = manual`) + ได้แจ้งเตือน |
| 68130500005 | ถอนความยินยอม 20 ต.ค. → embedding ถูกลบ, มีงานลบที่ `completed`, หลังวันนั้นเช็คชื่อด้วยมือ |
| 68130500012 | ถอน INT201 วันที่ 7 ก.ย. (มีผลเช็คชื่อก่อนวันถอนเท่านั้น) |
| 65130500017 | จบการศึกษา → งานลบข้อมูลใบหน้ารอคิว (`due_at` 15 ธ.ค.) |
| 69130599001 | **persona** ยินยอมข้อความฉบับเก่า `v0` (ลงทะเบียนใบหน้าแล้ว) → `needs_reconsent = true` |
| 69130599002 | **persona** ยินยอม `v1` แล้วแต่ยังไม่มีใบหน้า (ส่งรูปไม่สำเร็จ) → เช็คชื่อด้วยมือ |
| 69130599003 | **persona** อายุไม่ถึง 20 (`is_adult = false`, B9) ยังไม่ได้ยินยอม → เช็คชื่อด้วยมือ |
| 66130599004 | **persona** ปี 4 ฝึกงาน ไม่มีวิชาเทอมนี้ (ลงทะเบียนใบหน้าแล้ว) |
| 66130599005 | **persona** ลาออก (`academic_status = withdrawn`) → บัญชีถูกปิด login ไม่ได้ · งานลบใบหน้ารอคิว |
| 66130500013, 66130500036, 66130500041 | **สมาชิกทีม** (behaviour `noface`) ยังไม่ยินยอม / ไม่มีใบหน้า · INT201-001 → เช็คชื่อด้วยมือ + แจ้งเตือนลงทะเบียน |

| ตาราง | แถว | ตาราง | แถว |
|---|---|---|---|
| users | 52 | attendance_records | 1,670 |
| student_profiles | 45 | consent_records | 39 |
| rooms / cameras | 5 / 5 | face_embeddings | 122 |
| semesters | 2 | data_deletion_jobs | 3 |
| courses | 8 | audit_logs | 429 |
| sections | 8 | follow_ups (R2) | 8 |
| enrollments | 98 | occupancy_snapshots (R2) | 135 |
| class_sessions | 150 | notifications (R3) | 113 |
| consent_versions | 2 (`v0` เก่า, `v1` ปัจจุบัน) | | |

**ID คงที่ทุก env** — คำนวณจาก `md5('<key>')::uuid` ใช้ใน Postman / mock ฝั่ง Front-End ได้

```sql
md5('student:69130500001')::uuid     -- นักศึกษา (= user_id = student_id)
md5('user:admin@sit.kmutt.ac.th')::uuid
md5('course:INT301')::uuid
md5('section:INT301-001')::uuid
md5('room:4302')::uuid
```

## View สำหรับ API

| View | ใช้กับ |
|---|---|
| `v_student_section_attendance` | API 5.6 — `totalSessions`, `onTime`, `late`, `absent`, `attendanceRate` (นับสายเป็นมา, `NULL` เมื่อยังไม่มีคาบ), `risk_level` (0–1 low · 2 medium · 3+ high ตรงกับ `risk.js`) |
| `v_face_registration` | `GET /students/{id}/face/status` + `needs_reconsent` (D6) + Registration Status |
| `v_room_status` | B1 `GET /rooms` — สถานะกล้อง + คาบที่กำลังเรียน + `checked_in` / `total_students` |
| `v_current_consent` | สถานะความยินยอมล่าสุดของนักศึกษาแต่ละคน |

## สิ่งที่ DB บังคับให้แล้ว (Backend ไม่ต้องเขียนซ้ำ แต่ต้องจับ error ให้เป็นข้อความไทย)

- `consent_records` แก้/ลบไม่ได้ (append-only trigger)
- `face_embeddings` ต้องอ้าง consent ที่เป็น `granted` ของนักศึกษาคนเดียวกัน และไม่เกิน 5 ชุดต่อคน
- `section_lecturers.lecturer_id` ต้องเป็น role `lecturer` · `student_profiles` ต้องเป็น role `student`
- `attendance_records`: 1 แถวต่อนักศึกษาต่อคาบ · `manual` ต้องมี `recorded_by` + `reason` · `camera` ต้องมี `match_score` · `system` ใช้กับ `absent` เท่านั้น · `absent` ไม่มี `check_in_time`
- `sections`: เวลาจบ > เริ่ม · ช่วงเช็คชื่ออยู่ในเวลาเรียน · `late_until` อยู่ในช่วงเช็คชื่อ
- `courses.course_code` รูปแบบ `ABC123` (เช่น INT101) และไม่ซ้ำกับวิชาที่ยังไม่ถูกลบ

## สิ่งที่ Backend ต้องทำเอง

- ห้องเดียวกันเวลาชนกัน (B5) — ตรวจใน service แล้วตอบ 400
- สร้าง `class_sessions` เมื่อบันทึกตารางเรียน (D2) และอัปเดตเฉพาะคาบที่ยังไม่เริ่ม
- job ตอนปิดช่วงเช็คชื่อ: ใส่ `absent` (`source = system`) ให้นักศึกษาที่ยังไม่มีแถว
- job รายวัน: ทำ `data_deletion_jobs` ที่ถึง `due_at` (ลบ embedding + ใส่ `completed_at`)
- เข้ารหัส / ถอดรหัส `face_embeddings.embedding` (AES-GCM, กุญแจอยู่ใน K3s Secret) ก่อนส่งให้ AI Service
- บันทึก `audit_logs` ทุกครั้งที่เช็คชื่อด้วยมือ, เปิด-ปิดกล้อง, ลบใบหน้า, ลบวิชา
- สิทธิ์: lecturer เห็นเฉพาะ section ใน `section_lecturers` · lecturer ไม่เห็น `face_embeddings` · student ไม่เห็นกลุ่มเสี่ยง

## Spring Boot

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/bangmod_attendance
spring.jpa.hibernate.ddl-auto=validate          # schema มาจากไฟล์ SQL ไม่ให้ Hibernate สร้างเอง
spring.jpa.properties.hibernate.jdbc.time_zone=Asia/Bangkok
spring.jackson.time-zone=Asia/Bangkok
```

- PK เป็น `UUID` → `@Id @GeneratedValue(strategy = GenerationType.UUID) UUID id` (หรือปล่อยให้ `DEFAULT gen_random_uuid()`)
- enum เป็น `VARCHAR + CHECK` → `@Enumerated(EnumType.STRING)` + ค่าตัวเล็กให้ตรง (`on_time`, `late`, `absent`)
- `updated_at` มี trigger อัปเดตให้แล้ว
- ถ้าจะใช้ Flyway: copy `01_schema.sql` เป็น `db/migration/V1__init_schema.sql` (ตัด `BEGIN;` / `COMMIT;` ออก) และวาง `02_seed_mock.sql` ไว้ใน location ที่เปิดเฉพาะ profile `local` / `uat`

## บันทึกการทำงาน (Changelog)

### 2026-11-23 — หน้ายินยอมไม่ถามอายุแล้ว (อัปเดตเอกสารเท่านั้น)
- **ทำอะไร:** แก้คำอธิบาย persona `69130599003` ในตารางบัญชี — หน้ายินยอมไม่มีข้อยืนยันอายุ 20 ปีแล้ว `is_adult` จึงมีแค่ในฐานข้อมูล · ไม่ได้แก้ SQL
- **ไฟล์ที่เกี่ยวข้อง:** `README.md`
- **เหตุผล:** ให้ตรงกับหน้าลงทะเบียนใบหน้าที่เอาข้อยืนยันอายุออก (ดู `frontend/README.md`)

### 2026-11-23 — บัญชีสมาชิกทีม 3 คน (ยังไม่ลงทะเบียนใบหน้า)
- **ทำอะไร:** เพิ่ม `66130500013` จักรพงษ์, `66130500036` ทัตพล, `66130500041` ธรรมสรณ์ ใน `mock_students` (behaviour `noface`, ปี 2566, active) และลงทะเบียน INT201-001 · รหัสผ่าน `Password@123` เหมือนบัญชีอื่น · เพิ่ม `04_add_team_students.sql` สำหรับเพิ่มบัญชีเดียวกันเข้า DB ที่ seed ไว้แล้วโดยไม่ TRUNCATE (ON CONFLICT DO NOTHING, คาบที่จบแล้วได้ `on_time` แบบ manual, ไม่มีวิชา mock = error) · `assertions.sql`: users 52, student_profiles 45 และตรวจว่า 3 บัญชี active / ยังไม่ลงทะเบียน / อยู่ใน INT201-001 · อัปเดตตารางจำนวนแถว บัญชี และกรณีทดสอบ
- **ไฟล์ที่เกี่ยวข้อง:** `02_seed_mock.sql`, `04_add_team_students.sql`, `test/assertions.sql`, `README.md`
- **เหตุผล:** ให้สมาชิกทีมทดสอบลงทะเบียนใบหน้าด้วยหน้าตัวเองบน uat
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบแล้วบน PostgreSQL ชั่วคราว: `04` เพิ่มเข้า DB เดิมได้ / รันซ้ำไม่ซ้ำ / ปฏิเสธ DB ที่ไม่มี mock · commit นี้แก้ `backend/db/` → CD บน VM จะหยุด ต้องรัน `04` บน uat แล้ว `ALLOW_DB_CHANGE=1 ~/bangmod-cd/bin/deploy.sh uat` เอง

### 2026-11-22 — นักศึกษาทดสอบครบทุกสถานะ (test personas)
- **ทำอะไร:** เพิ่ม 5 คนใน `mock_students` (behaviour ใหม่): `69130599001` ยินยอม `v0` (เพิ่ม `consent_versions` v0 ที่ไม่ใช่ฉบับปัจจุบัน) → `needs_reconsent`, `69130599002` ยินยอม v1 แต่ไม่มี embedding, `69130599003` `is_adult = false` ยังไม่ยินยอม, `66130599004` ปี 4 ไม่มีวิชา, `66130599005` ลาออก (`academic_status = withdrawn`, บัญชีปิด, งานลบใบหน้ารอคิว) · คนที่ไม่มีใบหน้าได้เช็คชื่อแบบ manual + แจ้งเตือนลงทะเบียน · `assertions.sql`: users 49, student_profiles 42 และตรวจว่าแต่ละ persona อยู่ในสถานะที่ตั้งใจ · อัปเดตตารางจำนวนแถวและกรณีทดสอบ
- **ไฟล์ที่เกี่ยวข้อง:** `02_seed_mock.sql`, `test/assertions.sql`, `README.md`
- **เหตุผล:** ทดสอบฟีเจอร์ student ได้ครบทุกกรณี (เดิมไม่มีคนที่ต้องยินยอมใหม่ / ยินยอมแต่ไม่มีใบหน้า / อายุไม่ถึง 20 / ไม่มีวิชา / ลาออก)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** นักศึกษาเดิม 37 คนไม่เปลี่ยน · persona ปี 1 อยู่ใน INT101-001, INT102, INT103 · รัน 02 ซ้ำได้ และ assertions ผ่าน (PostgreSQL 18) · data_deletion_jobs ไม่มี reason "ลาออก" จึงใช้ `graduated` (พ้นสภาพ)

