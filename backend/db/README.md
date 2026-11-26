# backend/db — PostgreSQL schema + mock data (Release 1)

ฐานข้อมูล `bangmod_attendance` สำหรับ Spring Boot · PostgreSQL 16+ · 22 ตาราง (R1 17 + R2–R3 3 + นำเข้าผลเช็คชื่อ 2) และ 4 view
แบบที่ใช้: [`docs/database/database-design.md`](../../docs/database/database-design.md)

| ไฟล์ | ทำอะไร | รันที่ |
|---|---|---|
| `00_create_database.sql` | สร้าง DB `bangmod_attendance` + ตั้ง timezone `Asia/Bangkok` | ทุก env (ครั้งเดียว) |
| `01_schema.sql` | ตาราง, constraint, index, trigger, view | ทุก env |
| `02_seed_mock.sql` | ข้อมูลเดโม / ทดสอบจริง: admin 1 · อาจารย์ 1 · นักศึกษา 17 (ทีม 3 + 288 / 099 + dummy 12) · 5 วิชา 9–13 คนต่อวิชา · 3 วิชาในห้องกล้อง 4303 (กติกาสายต่างกัน) (ล้างทุกตารางก่อนใส่) | **local / UAT เท่านั้น** |
| `03_demo_live_session.sql` | สร้างคาบ INT201-001 ที่ "กำลังเรียนอยู่ตอนนี้" ในห้อง 4303 (เริ่ม 5 นาทีที่แล้ว · ยังไม่มีใครเช็คชื่อ) ไว้ทดสอบกล้อง · ห้อง 4303 มีคาบวิชาอื่นช่วงนั้นอยู่แล้ว = ไม่สร้าง (แจ้ง NOTICE ใช้คาบนั้นแทน) | local / UAT (ไม่บังคับ) |
| `04_add_team_students.sql` | (สำหรับ DB ที่ seed ด้วย `02` รุ่นเก่า) เพิ่มบัญชีสมาชิกทีม 3 คนโดยไม่ล้างข้อมูล · กับ `02` ชุดใหม่ไม่ต้องใช้ (มีทีมอยู่แล้ว) | local / UAT (ไม่บังคับ · ห้าม Production) |
| `05_attendance_import.sql` | อัปเดต DB ที่สร้างไว้ก่อน 2026-11-25: ตาราง `attendance_imports` / `attendance_import_rows` + `attendance_records.import_id` + `source = import` · ไม่ลบ / แก้ข้อมูล · รันซ้ำได้ (DB ที่สร้างด้วย `01` ใหม่ไม่ต้องรัน) | uat / prod (ที่มีข้อมูลอยู่แล้ว) |
| `06_class_session_management.sql` | อัปเดต DB เดิม (หลัง `05`): คอลัมน์ "คาบเรียน" ใน `class_sessions` (`is_overridden`, `original_date`, `makeup_for_session_id`, `is_extra`, `cancel_reason`) + CHECK + index · ไม่ลบ / แก้ข้อมูล · รันซ้ำได้ (DB ที่สร้างด้วย `01` ใหม่ไม่ต้องรัน) | uat / prod (ที่มีข้อมูลอยู่แล้ว) |
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

`02` และ `03` รันซ้ำได้ — `02` คืนข้อมูลกลับเป็นชุดเดิมทุกครั้ง (วันที่คำนวณจากวันที่รัน), `03` ย้ายคาบเดโมมาที่เวลาปัจจุบัน

**reset uat ให้เป็นข้อมูลชุดนี้** (ล้างข้อมูล uat ทั้งหมด รวมบัญชีที่เพิ่มเอง ใบหน้า consent รายวิชาที่สร้างเอง) · รันบน VM ไม่ต้อง sudo · สำรองก่อนทุกครั้ง:

```bash
mkdir -p ~/backups && F=~/backups/uat-before-reset-$(date +%F-%H%M).dump
kubectl -n uat exec statefulset/postgres -- pg_dump -U postgres -d bangmod_attendance -Fc > "$F" && chmod 600 "$F" && ls -lh "$F"
git -C ~/bangmod-cd/repo fetch -q origin
rm -rf /tmp/dbup && mkdir -p /tmp/dbup && git -C ~/bangmod-cd/repo archive origin/Dev backend/db devops/k8s/init-db.sh | tar -x -C /tmp/dbup
# ล้าง schema (init-db.sh / 01 ไม่ลบของเดิม) · ไม่มีข้อมูล mock INT201-001 = หยุด ไม่ลบอะไร
kubectl -n uat exec -i statefulset/postgres -- psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;
SET LOCAL lock_timeout = '30s';
DO $$ BEGIN
  IF to_regclass('public.sections') IS NOT NULL THEN   -- schema ว่าง (reset ค้างรอบก่อน) = ไปต่อได้
    IF NOT EXISTS (SELECT 1 FROM public.sections WHERE section_id = md5('section:INT201-001')::uuid) THEN
      RAISE EXCEPTION 'ไม่พบข้อมูล mock INT201-001 — ไม่ใช่ uat? ไม่ได้ลบอะไร';
    END IF;
  END IF;
END $$;
DROP SCHEMA public CASCADE;
CREATE SCHEMA public AUTHORIZATION pg_database_owner;
GRANT USAGE ON SCHEMA public TO PUBLIC;
COMMIT;
SQL
bash /tmp/dbup/devops/k8s/init-db.sh uat --seed     # timezone → 01 → 02 → 03 → assertions
kubectl -n uat rollout restart deployment/backend && kubectl -n uat rollout status deployment/backend --timeout=300s
~/bangmod-cd/bin/deploy.sh status                    # blocked เพราะ commit แก้ backend/db/ → ALLOW_DB_CHANGE=1 ~/bangmod-cd/bin/deploy.sh uat
```

ย้อนกลับ: ล้าง schema แบบเดียวกัน แล้ว `kubectl -n uat exec -i statefulset/postgres -- pg_restore -U postgres -d bangmod_attendance --exit-on-error < "$F"` และ restart backend

ทดสอบกล้องครั้งถัดไป: รัน `03_demo_live_session.sql` อีกครั้ง (คาบใหม่ "ตอนนี้" ห้อง 4303 · ล้างผลเช็คชื่อของวันนี้)

**เพิ่มบัญชีทีมเข้า uat ที่มีข้อมูลอยู่แล้ว** (ไม่อยากรัน `02` ใหม่เพราะจะลบใบหน้าที่ลงทะเบียนไว้) — `02` มีบัญชีทีมอยู่แล้ว ใช้ `04` เฉพาะ DB ที่ seed ไว้ก่อนหน้านี้:

```bash
cd ~/bangmod-cd/repo && git pull --ff-only   # clone ของ CD บน VM (ต้องมี 04)
kubectl -n uat exec -i statefulset/postgres -- \
  psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 < backend/db/04_add_team_students.sql
ALLOW_DB_CHANGE=1 ~/bangmod-cd/bin/deploy.sh uat   # CD หยุดเพราะ commit แก้ backend/db/
```

`kubectl` บน VM ใช้ได้โดยไม่ต้อง sudo (`KUBECONFIG` ตาม `devops/README.md`) · `04` ตรวจก่อนว่ามีกลุ่มเรียน mock `INT201-001` — ไม่มี (เช่น Production) จะ error และไม่เพิ่มอะไร

**อัปเดต uat สำหรับ `05_attendance_import.sql` + `06_class_session_management.sql`** (นำเข้าผลเช็คชื่อ / คาบเรียน · schema อย่างเดียว ไม่ลบข้อมูล · รันซ้ำได้ทั้งคู่ — ถ้ารัน `05` ไปแล้วก็รันซ้ำได้) · รันบน VM ไม่ต้อง sudo · ลำดับ **05 → 06 → `ALLOW_DB_CHANGE` ครั้งเดียว**:

```bash
git -C ~/bangmod-cd/repo fetch -q origin   # clone ของ CD บน VM · ไม่แตะ working tree ของ CD
rm -rf /tmp/dbup && mkdir -p /tmp/dbup && git -C ~/bangmod-cd/repo archive origin/Dev backend/db | tar -x -C /tmp/dbup
for f in 05_attendance_import.sql 06_class_session_management.sql; do
  kubectl -n uat exec -i statefulset/postgres -- \
    psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 < /tmp/dbup/backend/db/$f || break
done
ALLOW_DB_CHANGE=1 ~/bangmod-cd/bin/deploy.sh uat   # CD หยุดเพราะ commit แก้ backend/db/
```

ตรวจหลังรัน:

```bash
kubectl -n uat exec -i statefulset/postgres -- psql -U postgres -d bangmod_attendance -c '\d attendance_imports'
kubectl -n uat exec -i statefulset/postgres -- psql -U postgres -d bangmod_attendance -tAc \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"   # ต้องได้ 22
kubectl -n uat exec -i statefulset/postgres -- psql -U postgres -d bangmod_attendance -tAc \
  "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conname IN ('attendance_records_source_check', 'attendance_records_import_check', 'class_sessions_makeup_not_self_check', 'class_sessions_extra_overridden_check')"   # ต้องมี 4 แถว และ source มี 'import'
kubectl -n uat exec -i statefulset/postgres -- psql -U postgres -d bangmod_attendance -tAc \
  "SELECT column_name FROM information_schema.columns WHERE table_name = 'class_sessions' AND column_name IN ('is_overridden', 'original_date', 'makeup_for_session_id', 'is_extra', 'cancel_reason')"   # ต้องได้ 5 แถว
```

**prod: ยังไม่ต้องรันตอนนี้** — ต้องรัน `05` แล้ว `06` กับ prod (ผู้ใช้รันเอง) **ก่อน** deploy prod ครั้งถัดไปที่มี backend รุ่นนี้ ไม่งั้น `GET /class-sessions/{id}/attendance` (อ้าง `attendance_imports`) และการบันทึกรายวิชา (`regenerateSessions` อ้าง `is_overridden` / `original_date`) จะ error · ทั้งสองไฟล์ไม่มีข้อมูล mock / ไม่ TRUNCATE ใช้กับ prod ได้

## บัญชี Mock (รหัสผ่านทุกบัญชี `Password@123`)

| role | อีเมล | หมายเหตุ |
|---|---|---|
| admin | `admin@sit.kmutt.ac.th` | เห็นทุกห้อง / ทุกวิชา |
| lecturer | `supawadee.c@sit.kmutt.ac.th` | อ.ดร.สุภาวดี · สอนทั้ง 5 วิชา (INT201–INT205) |
| student | `66130500013@st.sit.kmutt.ac.th` | สมาชิกทีม · ยังไม่ยินยอม / ไม่มีใบหน้า · INT201, 202, 203, 205 |
| student | `66130500036@st.sit.kmutt.ac.th` | สมาชิกทีม · INT201–INT204 · INT201 ขาด 1 ครั้ง |
| student | `66130500041@st.sit.kmutt.ac.th` | สมาชิกทีม · INT201–INT204 · INT201 ขาด 2 ครั้ง → เสี่ยงปานกลาง |
| student | `66130500288@st.sit.kmutt.ac.th` | **ชื่อ placeholder (รอยืนยัน)** · INT201–INT204 · คาบล่าสุดเข้า +15 นาที: INT202 = สาย, INT203 = ตรงเวลา |
| student | `66130500099@st.sit.kmutt.ac.th` | **ชื่อ placeholder (รอยืนยัน)** · INT201, 202, 203, 205 |
| student | `66139900001` … `66139900012` `@st.sit.kmutt.ac.th` | dummy 12 คน (ชื่อสมมติ · รหัสสาขา `99` ไม่มีจริง) · `66139900007` ขาด 3 ครั้งใน INT201 → เสี่ยงสูง |

รหัสผ่านเก็บเป็น BCrypt (`$2a$`, cost 10) จาก `pgcrypto` — ใช้กับ `BCryptPasswordEncoder` ของ Spring Security ได้ตรงๆ
สมาชิกทีมใช้ชื่อจริง (README ที่ root) · คนอื่นเป็นข้อมูลสมมติ (PDPA) · **ไม่มี consent / ข้อมูลใบหน้าใน seed** → ลงทะเบียนผ่านหน้าเว็บด้วยหน้าตัวเอง กล้องจึงจับคู่ได้จริง

## ข้อมูล Mock-Up

ชุดเดโมสำหรับนำเสนอ + ทดสอบจริงกับกล้อง · **วันที่คำนวณจากวันที่รัน `02`** (รันวันไหนก็มีคาบอดีต / อนาคตเหมือนกัน)

| วิชา (กลุ่ม 001) | ห้อง | วัน / เวลา | เช็คชื่อ | นักศึกษา |
|---|---|---|---|---|
| INT201 Client-Side Web Programming | **4303** (กล้อง) | อังคาร 13:00–16:00 | 13:00–13:30 · ตรงเวลา 15 นาที | 12 |
| INT202 Server-Side Web Programming | **4303** (กล้อง) | ศุกร์ 09:00–12:00 | 09:00–09:40 · **ตรงเวลา 10 นาที** (เข้มงวด) | 13 |
| INT203 Database Systems | **4303** (กล้อง) | จันทร์ 09:00–12:00 | 09:00–10:00 · **ตรงเวลา 30 นาที** (ผ่อนปรน) | 13 |
| INT204 Software Engineering | 4301 | พุธ 09:00–12:00 | 09:00–09:30 · ตรงเวลา 15 นาที | 11 |
| INT205 Information Security | 4302 | พฤหัส 13:00–16:00 | 13:00–13:45 · ตรงเวลา 15 นาที | 9 |

| อะไร | ค่า |
|---|---|
| ภาคเรียน | 2569/1 · ตั้งแต่ 8 สัปดาห์ก่อน ถึง 10 สัปดาห์ข้างหน้า |
| คาบ | ทุกสัปดาห์ตามวันเรียน · 4 คาบล่าสุดก่อนวันนี้ `completed` มีผลเช็คชื่อ · ที่เหลือ `scheduled` · คาบของวันนี้ที่เลิกไปแล้วตอนรัน `02` ไม่สร้าง (ไม่งั้นจะกลายเป็นขาดทั้งห้อง) · ห้อง 4303 ไม่มีเวลาชนกัน และมีคาบทุกจันทร์ / อังคาร / ศุกร์ |
| ผลเช็คชื่อ | ทุกคนทุกคาบที่จบแล้ว (232 แถว) · มาเรียน = อาจารย์เช็คด้วยมือ (`manual` + audit) · ขาด = `system` · สถานะคิดจากเวลาเข้า + กติกาของวิชานั้น (≤ ตรงเวลา = `on_time` · ≤ ปิดรับ = `late`) เหมือนกล้อง · ประมาณ 75 % ตรง 15 % สาย 10 % ขาด (กำหนดจาก md5 ผลเหมือนเดิมทุกครั้ง) |
| แถวที่ตั้งไว้ (INT201) | 66130500013 ตรง ตรง สาย ตรง · 66130500036 ตรง ขาด ตรง สาย · 66130500041 ขาด สาย ขาด ตรง (เสี่ยงปานกลาง) · 66139900007 ขาด ขาด ตรง ขาด (เสี่ยงสูง) |
| สาธิต "สายจริง" | 66130500288 คาบล่าสุดเข้า 09:15 ทั้ง INT202 (ตรงเวลาถึง 09:10 → **สาย**) และ INT203 (ตรงเวลาถึง 09:30 → **ตรงเวลา**) |
| ห้อง / กล้อง | ห้อง 4301–4305 · **กล้องเฉพาะ 4303** (`CAM-4303-A`, MediaMTX path `room-4303`) · สถานะเริ่มต้น `no_signal` แล้ว ai-service อัปเดตเอง |
| consent | `v1` (ฉบับปัจจุบัน) อย่างเดียว · ยังไม่มีใครยินยอม |
| อื่น ๆ | แจ้งเตือน "ยังไม่ได้ลงทะเบียนใบหน้า" ให้นักศึกษาทุกคน · ไม่มี follow-ups / occupancy / งานลบข้อมูล |

| ตาราง | แถว | ตาราง | แถว |
|---|---|---|---|
| users | 19 | attendance_records | 232 (+ ที่ job / กล้อง / อาจารย์บันทึกภายหลัง) |
| student_profiles | 17 | consent_records / face_embeddings | 0 |
| rooms / cameras | 5 / 1 | audit_logs | ~208 (เท่าจำนวนแถว manual) |
| semesters / courses / sections | 1 / 5 / 5 | notifications | 17 |
| enrollments | 58 | class_sessions | ~72 (ขึ้นกับวันที่รัน) |

**ID คงที่ทุก env** — คำนวณจาก `md5('<key>')::uuid` ใช้ใน Postman / mock ฝั่ง Front-End ได้

```sql
md5('student:66130500013')::uuid     -- นักศึกษา (= user_id = student_id)
md5('user:admin@sit.kmutt.ac.th')::uuid
md5('course:INT201')::uuid
md5('section:INT201-001')::uuid
md5('room:4303')::uuid          -- คาบ: md5('session:INT201-001:<YYYY-MM-DD>')::uuid (INT202-001 … ก็แบบเดียวกัน)
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

### 2026-11-26 — ข้อมูลเดโม 5 วิชา + นักศึกษา 288 / 099
- **ทำอะไร:** เขียน `02_seed_mock.sql` ใหม่ให้มี 5 วิชา (INT201–INT205 กลุ่ม 001 อาจารย์ supawadee.c สอนทั้งหมด) 9–13 คนต่อวิชา · นักศึกษา 17 คน: ทีม 3 + `66130500288` / `66130500099` (ชื่อ placeholder) + dummy `66139900001`–`012` (ชื่อสมมติ) · ห้อง 4303 มี INT201 (อังคาร), INT202 (ศุกร์ ตรงเวลา 10 นาที), INT203 (จันทร์ ตรงเวลา 30 นาที) ไม่ชนกัน · INT204 ห้อง 4301, INT205 ห้อง 4302 · ทุกวิชามี 4 คาบที่จบแล้วพร้อมผลเช็คชื่อ (สถานะคิดจากเวลาเข้า + กติกาวิชา) · คาบวันนี้ที่เลิกไปแล้วตอนรันไม่สร้าง · `03`: ห้อง 4303 มีคาบวิชาอื่นช่วงเวลานั้นอยู่แล้ว → แจ้ง NOTICE ไม่สร้าง (ไม่ error · init-db.sh ไปต่อได้) · `assertions.sql`: จำนวนใหม่ (17 / 5 / 232), 4 คาบ completed ต่อวิชา, 9–13 คนต่อวิชา, ห้องไม่ชน (ตารางเรียน + รายคาบ), สถานะตรงกับเวลาเข้า, แถวสาธิตสาย 288 และระดับเสี่ยงใน INT201 · README: ตารางวิชา / บัญชี / จำนวนแถว / ขั้น reset uat ใหม่ (สำรอง → ล้าง schema → init-db.sh) · `dev.sh`: smoke เลือกห้องที่มีกล้อง (lecturer เห็น 3 ห้องแล้ว) และรายชื่อบัญชีที่พิมพ์ตอนเปิดให้ตรงข้อมูลใหม่
- **ไฟล์ที่เกี่ยวข้อง:** `02_seed_mock.sql`, `03_demo_live_session.sql`, `test/assertions.sql`, `README.md`, `../../dev.sh`
- **เหตุผล:** เตรียมเดโมส่งโครงงาน (30 พ.ย.) — อาจารย์ที่ปรึกษาต้องการให้แต่ละ class มีรายชื่อนักศึกษาจริง ไม่ใช่ record ว่าง และต้องสาธิตว่ากติกา "สาย" ต่างกันตามวิชา · มีคาบห้อง 4303 ทุก จ. / อ. / ศ. ไว้ทดสอบกล้องก่อนส่ง
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ตรวจกับ PostgreSQL ชั่วคราว: `./dev.sh db --reset` + assertions ผ่าน · `./dev.sh test` ผ่าน · backend จริง + `./dev.sh smoke` 15/15 · `/api/test-accounts` 19 บัญชี · ไม่มี ERROR ใน log · **ชื่อของ 288 / 099 เป็น placeholder รอผู้ใช้ยืนยัน** · commit นี้แก้ `backend/db/` → CD บน uat จะหยุด: reset uat ตามขั้นด้านบนแล้ว `ALLOW_DB_CHANGE=1 ~/bangmod-cd/bin/deploy.sh uat`

### 2026-11-25 — คาบเรียน (จัดการรายคาบ): schema (D1)
- **ทำอะไร:** `class_sessions` เพิ่ม `is_overridden`, `original_date`, `makeup_for_session_id` (FK ตัวเอง + CHECK ไม่ชี้ตัวเอง), `is_extra` (CHECK `NOT is_extra OR is_overridden`), `cancel_reason` + index `(section_id, original_date) WHERE original_date IS NOT NULL` · เพิ่ม `06_class_session_management.sql` สำหรับ DB เดิม (รันซ้ำได้ ไม่ลบ / แก้ข้อมูล) · `assertions.sql` ตรวจคอลัมน์ใหม่ 5 ตัว และไม่มีคาบเพิ่มที่ไม่ได้ `is_overridden` (จำนวนตารางยัง 22) · README: ไฟล์ 06 + วิธีอัปเดต uat ลำดับ 05 → 06 → `ALLOW_DB_CHANGE` ครั้งเดียว
- **ไฟล์ที่เกี่ยวข้อง:** `01_schema.sql`, `06_class_session_management.sql`, `test/assertions.sql`, `README.md`
- **เหตุผล:** ฟีเจอร์ "คาบเรียน" (เลื่อน / ยกเลิก / คาบชดเชย) — ถ้าไม่มีคอลัมน์เหล่านี้ แก้รายวิชาทีหลังแล้ว `regenerateSessions` จะลบคาบที่แก้ไว้ และสร้างคาบซ้ำในวันเดิมของคาบที่ถูกเลื่อน
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ตรวจกับ PostgreSQL ชั่วคราว: DB จาก `01` / `02` / `03` ของ commit ก่อนหน้า → รัน `06` สองครั้งติดกันไม่ error · ข้อมูล `class_sessions` เดิมไม่เปลี่ยน (md5) · schema หลัง `06` ตรงกับ `01` ใหม่ (ต่างแค่ลำดับคอลัมน์) · CHECK ใหม่ทำงาน · `./dev.sh db --reset` + `./dev.sh db` ผ่าน · `./dev.sh test` ผ่าน · `./dev.sh smoke` 15/15 · **commit นี้แก้ `backend/db/` → CD บน uat จะหยุด** ต้องรัน `05` (ถ้ายังไม่ได้รัน) และ `06` บน uat แล้ว `ALLOW_DB_CHANGE=1 ~/bangmod-cd/bin/deploy.sh uat` · prod: รัน `05` + `06` ก่อน deploy prod ครั้งถัดไป

### 2026-11-25 — นำเข้าผลเช็คชื่อจากไฟล์: schema (C1)
- **ทำอะไร:** ตารางใหม่ `attendance_imports` (1 แถวต่อการนำเข้า: mode / method / ชื่อไฟล์ + SHA-256 / mapping / เหตุผล / override / จำนวนแต่ละผล / ผู้นำเข้า · ไม่เก็บตัวไฟล์) และ `attendance_import_rows` (ทุกแถวในไฟล์ + action + `applied`) · `attendance_records.source` เพิ่ม `import` + คอลัมน์ `import_id` + CHECK `(source = 'import') = (import_id IS NOT NULL)` (ตั้งชื่อ constraint `attendance_records_source_check`, `attendance_records_import_check`) · เพิ่ม `05_attendance_import.sql` สำหรับ DB ที่มีข้อมูลอยู่แล้ว (รันซ้ำได้ ไม่ลบข้อมูล · หา CHECK เดิมของ source จาก `pg_constraint`) · `02` TRUNCATE ตารางใหม่ด้วย · `assertions.sql` 22 ตาราง · README: ไฟล์ 05 + วิธีอัปเดต uat
- **ไฟล์ที่เกี่ยวข้อง:** `01_schema.sql`, `05_attendance_import.sql`, `02_seed_mock.sql`, `test/assertions.sql`, `README.md`
- **เหตุผล:** ฟีเจอร์นำเข้าผลเช็คชื่อจากไฟล์ (template / Teams / Zoom / Google Form) สำหรับคลาสออนไซต์ — C1 ก่อน C2 (BE) และ C3 (FE)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ตรวจกับ PostgreSQL ชั่วคราว: DB ที่สร้างด้วย `01` / `02` / `03` เดิม → รัน `05` สองครั้งติดกันไม่ error · ข้อมูล `attendance_records` เดิมไม่เปลี่ยน (เทียบ md5) · schema หลัง `05` ตรงกับ `01` ใหม่ (ต่างแค่ลำดับคอลัมน์ `import_id`) · CHECK ใหม่ปฏิเสธข้อมูลผิดครบ · `./dev.sh db --reset` + `./dev.sh db` ผ่าน assertions · `./dev.sh test` ผ่าน · `./dev.sh smoke` 15/15 · **commit นี้แก้ `backend/db/` → CD บน uat จะหยุด** ต้องรัน `05` บน uat แล้ว `ALLOW_DB_CHANGE=1 ~/bangmod-cd/bin/deploy.sh uat` · prod: รัน `05` ก่อน deploy prod ครั้งถัดไป

### 2026-11-25 — ข้อมูล mock ชุดเล็กสำหรับทดสอบจริงกับกล้อง
- **ทำอะไร:** เขียน `02_seed_mock.sql` ใหม่: admin 1 (`admin@sit.kmutt.ac.th`), อาจารย์ 1 (`supawadee.c`), นักศึกษา 3 (ทีม · ไม่มี consent / ใบหน้า), INT201-001 ห้อง 4303 อังคาร 13:00–16:00, กล้องเฉพาะ 4303 (`room-4303`, เริ่ม `no_signal`), ภาคเรียน / คาบคำนวณจากวันที่รัน (4 คาบที่ผ่านมา `completed` + ผลเช็คชื่อ 12 แถว + audit, ที่เหลือ `scheduled`), consent `v1` อย่างเดียว, แจ้งเตือนลงทะเบียน 3 แถว · `03_demo_live_session.sql` ห้อง 4303 เริ่ม 5 นาทีที่แล้ว (ตรงเวลาอีก 10 นาที) ไม่ใส่ผลเช็คชื่อล่วงหน้า และ error ถ้าไม่มีข้อมูล mock · `test/assertions.sql` ตรวจตามข้อมูลชุดใหม่ (การทดสอบ append-only ใส่แถวเองใน sub-transaction) · README: บัญชี / ข้อมูล Mock-Up / วิธีใส่ข้อมูลใน uat
- **ไฟล์ที่เกี่ยวข้อง:** `02_seed_mock.sql`, `03_demo_live_session.sql`, `test/assertions.sql`, `README.md`
- **เหตุผล:** ทดสอบเช็คชื่อด้วยกล้องจริงไม่ได้ เพราะข้อมูลเดิมไม่มีคาบในห้องที่มีกล้อง (ทีมอยู่ INT201-001 ห้อง 4302 · คาบ mock หมด 26 พ.ย.) และข้อมูล mock เยอะเกินไปสำหรับทดสอบ (52 บัญชี 150 คาบ 1,670 แถว)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ตรวจกับ Postgres ชั่วคราว: 01 + 02 + 03 + assertions ผ่าน · สูตรหา "วันอังคารล่าสุดก่อนวันนี้" ถูกทุกวันในสัปดาห์ · backend จริง + `./dev.sh smoke` ผ่าน 15/15 · `/api/internal/cameras` = CAM-4303-A อย่างเดียว · gallery ห้อง 4303 มีคาบ (ยังไม่มีใบหน้า) · `/api/test-accounts` = 5 บัญชี · สรุปรายวิชาตรงกับผลที่ใส่ · `04_add_team_students.sql` ไม่ได้แก้ (กับข้อมูลชุดใหม่เป็น no-op) · ข้อความเก่าที่อ้าง `test-accounts.js` ถูกแทนแล้ว

### 2026-11-23 — แก้คำสั่งรัน 04 บน uat
- **ทำอะไร:** คำสั่งเพิ่มบัญชีทีมบน uat ใช้ `kubectl` (ไม่ต้อง `sudo k3s`) จาก clone ของ CD `~/bangmod-cd/repo` และต่อด้วย `ALLOW_DB_CHANGE=1 ~/bangmod-cd/bin/deploy.sh uat`
- **ไฟล์ที่เกี่ยวข้อง:** `README.md`
- **เหตุผล:** บน VM ตั้ง `KUBECONFIG` ไว้แล้ว และกฎข้อ 20 ห้ามรันคำสั่ง sudo เอง

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

