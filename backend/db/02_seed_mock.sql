-- =====================================================================
-- Bangmod Smart Attendance (CP26MS3) — 02: mock data for demos and real testing (local / UAT)
--   psql -U postgres -d bangmod_attendance -f 02_seed_mock.sql
--
-- * 1 admin, 1 lecturer, 17 students — password Password@123 for everyone
--   - the 3 project-team students (66130500013 / 036 / 041) + 66130500288, 66130500099 (names = placeholders)
--   - 12 dummy students 661399000xx (fictional names · program code "99" does not exist → no real person)
-- * 5 courses (1 section each, all taught by supawadee.c), 9–13 students enrolled in each:
--     INT201-001  4303 (camera)  Tue 13:00–16:00  on time 15 min · check-in closes 13:30
--     INT202-001  4303 (camera)  Fri 09:00–12:00  on time 10 min · closes 09:40   ← strict
--     INT203-001  4303 (camera)  Mon 09:00–12:00  on time 30 min · closes 10:00   ← lenient
--     INT204-001  4301           Wed 09:00–12:00  on time 15 min · closes 09:30
--     INT205-001  4302           Thu 13:00–16:00  on time 15 min · closes 13:45
--   INT202 vs INT203: the same check-in at +15 min is "late" in INT202 and "on time" in INT203
--   (66130500288 does exactly that in the latest class of both).
-- * Dates follow the day you run it: the semester runs from 8 weeks ago to 10 weeks ahead · each course
--   has its 4 latest weekly classes before today completed with attendance (for the dashboard), the rest
--   scheduled · a class earlier today that has already ended is not created.
--   Need a class right now for the camera? run 03_demo_live_session.sql
-- * Attendance: entered by hand by the lecturer (no faces registered) · absent = system · the status
--   comes from the check-in time and that course's own rule (like the camera would)
-- * Nobody has agreed to consent or registered a face — do that in the web app (no fake embeddings)
-- * IDs are deterministic (md5 of a readable key) and stay the same on every environment
-- * Re-runnable: it truncates every table first. NEVER run it on Production.
-- =====================================================================

BEGIN;
SET LOCAL timezone = 'Asia/Bangkok';

TRUNCATE notifications, occupancy_snapshots, follow_ups, audit_logs, data_deletion_jobs,
         face_embeddings, consent_records, consent_versions, attendance_records,
         attendance_import_rows, attendance_imports, class_sessions,
         enrollments, section_lecturers, section_class_days, sections, courses, semesters,
         cameras, rooms, student_profiles, users;

-- Deterministic helpers (exist only for this session)
CREATE FUNCTION pg_temp.uid(text) RETURNS uuid LANGUAGE sql IMMUTABLE AS $$ SELECT md5($1)::uuid $$;
CREATE FUNCTION pg_temp.pick(text, int) RETURNS int LANGUAGE sql IMMUTABLE   -- 0 … n-1 from a key
  AS $$ SELECT (('x' || substr(md5($1), 1, 8))::bit(32)::bigint % $2)::int $$;

-- =====================================================================
-- 1. Users — 1 admin, 1 lecturer, 17 students
-- =====================================================================

INSERT INTO users (user_id, email, password_hash, title, first_name, last_name, role, created_at)
SELECT pg_temp.uid('user:' || v.email), v.email, crypt('Password@123', gen_salt('bf', 10)),
       v.title, v.first_name, v.last_name, v.role, now() - interval '60 days'
FROM (VALUES
  ('admin@sit.kmutt.ac.th',       'นาย',   'วิทยา',  'ศรีสุข',     'admin'),
  ('supawadee.c@sit.kmutt.ac.th', 'อ.ดร.', 'สุภาวดี', 'จันทร์เพ็ญ',  'lecturer')
) AS v(email, title, first_name, last_name, role);

-- n = short handle used below (t1–t3 team, e1–e2 extra, d1–d12 dummy)
CREATE TEMP TABLE mock_students (n text, student_code text, title text, first_name text, last_name text)
ON COMMIT DROP;
INSERT INTO mock_students VALUES
  ('t1',  '66130500013', 'นาย',    'จักรพงษ์',   'สุขสมบรูณ์'),
  ('t2',  '66130500036', 'นาย',    'ทัตพล',     'สีล้ง'),
  ('t3',  '66130500041', 'นาย',    'ธรรมสรณ์',   'ฉัตรชยานุสรณ'),
  ('e1',  '66130500288', NULL,     'นักศึกษา',   'รหัส 288 (รอยืนยันชื่อ)'),
  ('e2',  '66130500099', NULL,     'นักศึกษา',   'รหัส 099 (รอยืนยันชื่อ)'),
  ('d1',  '66139900001', 'นาย',    'สมชาย',     'ใจดี'),
  ('d2',  '66139900002', 'นางสาว', 'สมหญิง',    'รักเรียน'),
  ('d3',  '66139900003', 'นาย',    'ณัฐพล',     'มั่นคง'),
  ('d4',  '66139900004', 'นางสาว', 'กมลชนก',    'แสงทอง'),
  ('d5',  '66139900005', 'นาย',    'ปิยะพงษ์',   'ศรีสวัสดิ์'),
  ('d6',  '66139900006', 'นางสาว', 'วรรณา',     'บุญมา'),
  ('d7',  '66139900007', 'นาย',    'ธนกร',      'วงศ์ใหญ่'),
  ('d8',  '66139900008', 'นางสาว', 'อรอุมา',    'พรหมมา'),
  ('d9',  '66139900009', 'นาย',    'ภูมิพัฒน์',  'ทองดี'),
  ('d10', '66139900010', 'นางสาว', 'ศิริพร',    'คำแก้ว'),
  ('d11', '66139900011', 'นาย',    'กิตติพัฒน์', 'เพชรรัตน์'),
  ('d12', '66139900012', 'นางสาว', 'ชนิดา',     'สุขเจริญ');

INSERT INTO users (user_id, email, password_hash, title, first_name, last_name, role, created_at)
SELECT pg_temp.uid('student:' || s.student_code), s.student_code || '@st.sit.kmutt.ac.th',
       crypt('Password@123', gen_salt('bf', 10)), s.title, s.first_name, s.last_name, 'student',
       now() - interval '60 days'
FROM mock_students s;

INSERT INTO student_profiles (student_id, student_code, faculty, program, entry_year, academic_status)
SELECT pg_temp.uid('student:' || s.student_code), s.student_code, 'คณะเทคโนโลยีสารสนเทศ', 'IT', 2566, 'active'
FROM mock_students s;

-- =====================================================================
-- 2. Rooms — camera only in 4303 (MediaMTX path room-4303 · status comes from the AI Service)
-- =====================================================================

INSERT INTO rooms (room_id, room_code, building, capacity)
SELECT pg_temp.uid('room:' || v.code), v.code, 'อาคาร SIT', v.cap
FROM (VALUES ('4301', 60), ('4302', 50), ('4303', 45), ('4304', 40), ('4305', 40)) v(code, cap);

INSERT INTO cameras (camera_id, room_id, camera_code, stream_path, status)
VALUES (pg_temp.uid('camera:4303'), pg_temp.uid('room:4303'), 'CAM-4303-A', 'room-4303', 'no_signal');

-- =====================================================================
-- 3. Semester, courses, sections, timetable (room 4303 has no overlapping slots)
-- =====================================================================

CREATE TEMP TABLE d ON COMMIT DROP AS
SELECT (date_trunc('week', current_date) - interval '8 weeks')::date AS sem_start,     -- a Monday
       (date_trunc('week', current_date) + interval '10 weeks' + interval '4 days')::date AS sem_end;

INSERT INTO semesters (semester_id, academic_year, term, start_date, end_date)
SELECT pg_temp.uid('semester:2569/1'), 2569, 1, sem_start, sem_end FROM d;

CREATE TEMP TABLE mock_sections (
  course_code text, course_name text, room text, dow int,                -- ISO day: 1 = Monday
  start_time time, end_time time, opens time, closes time, on_time int, late_until time
) ON COMMIT DROP;
INSERT INTO mock_sections VALUES
  ('INT201', 'Client-Side Web Programming', '4303', 2, '13:00', '16:00', '13:00', '13:30', 15, NULL),
  ('INT202', 'Server-Side Web Programming', '4303', 5, '09:00', '12:00', '09:00', '09:40', 10, NULL),
  ('INT203', 'Database Systems',            '4303', 1, '09:00', '12:00', '09:00', '10:00', 30, NULL),
  ('INT204', 'Software Engineering',        '4301', 3, '09:00', '12:00', '09:00', '09:30', 15, NULL),
  ('INT205', 'Information Security',        '4302', 4, '13:00', '16:00', '13:00', '13:45', 15, NULL);

INSERT INTO courses (course_id, course_code, course_name, created_by, created_at)
SELECT pg_temp.uid('course:' || m.course_code), m.course_code, m.course_name,
       pg_temp.uid('user:admin@sit.kmutt.ac.th'), now() - interval '60 days'
FROM mock_sections m;

INSERT INTO sections (section_id, course_id, semester_id, section_code, room_id, start_time, end_time,
                      check_in_opens, check_in_closes, on_time_minutes, late_until,
                      camera_attendance_enabled, created_at)
SELECT pg_temp.uid('section:' || m.course_code || '-001'), pg_temp.uid('course:' || m.course_code),
       pg_temp.uid('semester:2569/1'), '001', pg_temp.uid('room:' || m.room),
       m.start_time, m.end_time, m.opens, m.closes, m.on_time, m.late_until, true, now() - interval '60 days'
FROM mock_sections m;

INSERT INTO section_class_days (section_id, day_of_week)
SELECT pg_temp.uid('section:' || m.course_code || '-001'), m.dow FROM mock_sections m;

INSERT INTO section_lecturers (section_id, lecturer_id, is_primary, created_at)
SELECT pg_temp.uid('section:' || m.course_code || '-001'), pg_temp.uid('user:supawadee.c@sit.kmutt.ac.th'),
       true, now() - interval '60 days'
FROM mock_sections m;

-- =====================================================================
-- 4. Enrollments (9–13 per course · the team is in INT201)
-- =====================================================================

CREATE TEMP TABLE mock_enrollments (course_code text, members text[]) ON COMMIT DROP;
INSERT INTO mock_enrollments VALUES
  ('INT201', '{t1,t2,t3,e1,e2,d1,d2,d3,d4,d5,d6,d7}'),
  ('INT202', '{t1,t2,t3,e1,e2,d3,d4,d5,d6,d7,d8,d9,d10}'),
  ('INT203', '{t1,t2,t3,e1,e2,d5,d6,d7,d8,d9,d10,d11,d12}'),
  ('INT204', '{t2,t3,e1,d1,d2,d3,d4,d9,d10,d11,d12}'),
  ('INT205', '{t1,e2,d1,d2,d6,d7,d8,d9,d10}');

INSERT INTO enrollments (section_id, student_id, status, enrolled_at)
SELECT pg_temp.uid('section:' || me.course_code || '-001'), pg_temp.uid('student:' || s.student_code),
       'active', now() - interval '55 days'
FROM mock_enrollments me
CROSS JOIN LATERAL unnest(me.members) AS m(n)
JOIN mock_students s ON s.n = m.n;

-- =====================================================================
-- 5. Class sessions — weekly, from the 4th-latest class before today to the end of the semester
-- =====================================================================

INSERT INTO class_sessions (session_id, section_id, session_no, session_date, room_id,
                            start_time, end_time, check_in_opens, check_in_closes,
                            on_time_until, late_until, session_status, created_at)
SELECT pg_temp.uid('session:' || x.section_key || ':' || x.day),
       x.section_id, row_number() OVER (PARTITION BY x.section_id ORDER BY x.day), x.day, x.room_id,
       x.start_time, x.end_time, x.check_in_opens, x.check_in_closes,
       x.check_in_opens + make_interval(mins => x.on_time_minutes), coalesce(x.late_until, x.check_in_closes),
       CASE WHEN x.day <= x.last_class THEN 'completed' ELSE 'scheduled' END,
       now() - interval '55 days'
FROM (SELECT m.course_code || '-001' AS section_key, s.*, g.t::date AS day,
             -- the latest class day strictly before today
             current_date - ((extract(isodow FROM current_date)::int - m.dow + 6) % 7 + 1) AS last_class
      FROM mock_sections m
      JOIN sections s ON s.section_id = pg_temp.uid('section:' || m.course_code || '-001')
      CROSS JOIN d
      CROSS JOIN LATERAL generate_series(
        current_date - ((extract(isodow FROM current_date)::int - m.dow + 6) % 7 + 1) - 21,
        d.sem_end, interval '7 days') AS g(t)) x
-- a class earlier today that has already ended: not created (it would turn into "everyone absent")
WHERE NOT (x.day = current_date AND x.day + x.end_time <= localtimestamp);

-- =====================================================================
-- 6. Consent — only the current text; nobody has agreed yet (register in the app)
-- =====================================================================

INSERT INTO consent_versions (version, content_sha256, effective_from, is_current) VALUES
  -- replace the hash with sha256 of the exact text in consent-text.js before go-live
  ('v1', encode(digest('consent-text-v1 (claude/pdpa-consent-v1.md)', 'sha256'), 'hex'),
   '2026-08-01 00:00', true);

-- =====================================================================
-- 7. Attendance for the completed classes — the lecturer checked everyone in by hand (no faces yet)
--    Each row gets a check-in minute (after check-in opens) or "absent"; the status then follows the
--    course's own rule: ≤ on_time_until = on_time · ≤ late_until = late.
--    Fixed rows (session_no 1–4 = oldest → newest completed class) keep the dashboard predictable:
--      INT201: 66130500013 low risk · 66130500036 one absence · 66130500041 two absences (medium)
--              66139900007 three absences (high)
--      66130500288 checks in at +15 min in the latest INT202 (late) and INT203 (on time) class
--    Everyone else: ~75 % on time, ~15 % late, ~10 % absent (deterministic from the IDs)
-- =====================================================================

-- minute after check-in opens · NULL = absent
CREATE TEMP TABLE mock_fixed (course_code text, n text, minutes int[]) ON COMMIT DROP;
INSERT INTO mock_fixed VALUES
  ('INT201', 't1', '{5,5,20,5}'),               -- on_time, on_time, late, on_time
  ('INT201', 't2', '{5,NULL,5,20}'),            -- on_time, absent, on_time, late
  ('INT201', 't3', '{NULL,20,NULL,5}'),         -- absent, late, absent, on_time
  ('INT201', 'd7', '{NULL,NULL,8,NULL}'),       -- 3 absences
  ('INT202', 'e1', '{3,4,2,15}'),               -- latest: +15 min > 10 min → late
  ('INT203', 'e1', '{6,3,5,15}');               -- latest: +15 min ≤ 30 min → on time

CREATE TEMP TABLE mock_checkins ON COMMIT DROP AS
SELECT cs.session_id, cs.session_date, cs.check_in_opens, cs.on_time_until, cs.late_until, cs.check_in_closes,
       sp.student_id, s.student_code,
       CASE WHEN f.n IS NOT NULL THEN f.minutes[cs.session_no]
            ELSE (SELECT CASE WHEN r < 75 THEN pg_temp.pick(k || ':m', greatest(w_on, 1))
                              WHEN r < 90 THEN w_on + 1 + pg_temp.pick(k || ':l', greatest(w_late - w_on, 1))
                         END
                  FROM (SELECT pg_temp.pick(k, 100) AS r) rr)
       END AS minutes
FROM class_sessions cs
JOIN sections sec ON sec.section_id = cs.section_id
JOIN courses co ON co.course_id = sec.course_id
JOIN enrollments e ON e.section_id = cs.section_id AND e.status = 'active'
JOIN student_profiles sp ON sp.student_id = e.student_id
JOIN mock_students s ON s.student_code = sp.student_code
LEFT JOIN mock_fixed f ON f.course_code = co.course_code AND f.n = s.n
CROSS JOIN LATERAL (SELECT cs.session_id::text || ':' || s.student_code AS k,
                           (extract(epoch FROM cs.on_time_until - cs.check_in_opens) / 60)::int AS w_on,
                           (extract(epoch FROM cs.late_until - cs.check_in_opens) / 60)::int AS w_late) kk
WHERE cs.session_status = 'completed';

INSERT INTO attendance_records (attendance_id, session_id, student_id, status, check_in_time,
                                source, recorded_by, reason, created_at, updated_at)
SELECT pg_temp.uid('attendance:' || c.session_id || ':' || c.student_code),
       c.session_id, c.student_id, st.status,
       CASE WHEN st.status <> 'absent' THEN st.at::timestamptz END,
       CASE WHEN st.status = 'absent' THEN 'system' ELSE 'manual' END,
       CASE WHEN st.status <> 'absent' THEN pg_temp.uid('user:supawadee.c@sit.kmutt.ac.th') END,
       CASE WHEN st.status <> 'absent' THEN 'ข้อมูลทดสอบ — อาจารย์เช็คชื่อเอง (ยังไม่ได้ลงทะเบียนใบหน้า)' END,
       (c.session_date + c.check_in_closes)::timestamptz, (c.session_date + c.check_in_closes)::timestamptz
FROM mock_checkins c
CROSS JOIN LATERAL (
  SELECT c.session_date + c.check_in_opens + make_interval(mins => c.minutes) AS at,
         CASE WHEN c.minutes IS NULL THEN 'absent'
              WHEN c.check_in_opens + make_interval(mins => c.minutes) <= c.on_time_until THEN 'on_time'
              WHEN c.check_in_opens + make_interval(mins => c.minutes) <= c.late_until THEN 'late'
              ELSE 'absent' END AS status) st;

-- Every manual check-in leaves an audit row
INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, after_data, ip_address, created_at)
SELECT ar.recorded_by, 'attendance.manual_set', 'attendance_record', ar.attendance_id,
       jsonb_build_object('status', ar.status, 'check_in_time', ar.check_in_time, 'reason', ar.reason),
       '10.10.2.15', ar.check_in_time + interval '5 minutes'
FROM attendance_records ar
WHERE ar.source = 'manual';

-- =====================================================================
-- 8. Notification — reminder to register a face
-- =====================================================================

INSERT INTO notifications (user_id, type, title, body, created_at)
SELECT pg_temp.uid('student:' || s.student_code), 'face_registration_reminder', 'ยังไม่ได้ลงทะเบียนใบหน้า',
       'ลงทะเบียนใบหน้าเพื่อให้กล้องเช็คชื่อให้อัตโนมัติ หรือแจ้งอาจารย์เพื่อเช็คชื่อด้วยวิธีอื่น',
       now() - interval '50 days'
FROM mock_students s;

COMMIT;
