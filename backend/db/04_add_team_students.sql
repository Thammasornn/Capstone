-- =====================================================================
-- Bangmod Smart Attendance (CP26MS3) — 04 (optional): add the project team as test students
-- to a database that already has the mock data, WITHOUT wiping it (02 truncates every table).
--   psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 -f 04_add_team_students.sql
-- * 66130500013, 66130500036, 66130500041 · password Password@123 · not registered (no consent, no face)
-- * enrolled in INT201-001; completed sessions get a manual "on_time" row like other unregistered students
-- * re-runnable (ON CONFLICT DO NOTHING) · 02_seed_mock.sql already contains the same rows
-- * refuses to run unless the mock data is present — NEVER for Production (known password)
-- =====================================================================

BEGIN;
SET LOCAL timezone = 'Asia/Bangkok';

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM sections WHERE section_id = md5('section:INT201-001')::uuid) THEN
    RAISE EXCEPTION 'mock data (02_seed_mock.sql) not found — this script is for local / uat only';
  END IF;
END $$;

CREATE TEMP TABLE team (code text, title text, first_name text, last_name text) ON COMMIT DROP;
INSERT INTO team VALUES
  ('66130500013', 'นาย', 'จักรพงษ์', 'สุขสมบรูณ์'),
  ('66130500036', 'นาย', 'ทัตพล',   'สีล้ง'),
  ('66130500041', 'นาย', 'ธรรมสรณ์', 'ฉัตรชยานุสรณ');

INSERT INTO users (user_id, email, password_hash, title, first_name, last_name, role, is_active, created_at)
SELECT md5('student:' || t.code)::uuid, t.code || '@st.sit.kmutt.ac.th',
       crypt('Password@123', gen_salt('bf', 10)), t.title, t.first_name, t.last_name, 'student', true, now()
FROM team t
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO student_profiles (student_id, student_code, faculty, program, entry_year, academic_status)
SELECT md5('student:' || t.code)::uuid, t.code, 'คณะเทคโนโลยีสารสนเทศ', 'IT', 2566, 'active'
FROM team t
ON CONFLICT (student_id) DO NOTHING;

INSERT INTO enrollments (section_id, student_id, status, enrolled_at)
SELECT md5('section:INT201-001')::uuid, md5('student:' || t.code)::uuid, 'active', now()
FROM team t
ON CONFLICT (section_id, student_id) DO NOTHING;

-- sessions that already happened: checked in by hand, like the other unregistered students
INSERT INTO attendance_records (session_id, student_id, status, check_in_time, source, recorded_by, reason)
SELECT cs.session_id, md5('student:' || t.code)::uuid, 'on_time',
       (cs.session_date + cs.check_in_opens + interval '5 minutes')::timestamptz, 'manual',
       (SELECT sl.lecturer_id FROM section_lecturers sl WHERE sl.section_id = cs.section_id AND sl.is_primary),
       'เพิ่มเข้ากลุ่มทีหลัง — บันทึกย้อนหลัง (ยังไม่ได้ลงทะเบียนใบหน้า)'
FROM class_sessions cs, team t
WHERE cs.section_id = md5('section:INT201-001')::uuid AND cs.session_status = 'completed'
ON CONFLICT (session_id, student_id) DO NOTHING;

SELECT sp.student_code, u.email, u.is_active,
       (SELECT count(*) FROM attendance_records ar WHERE ar.student_id = sp.student_id) AS attendance_rows
FROM student_profiles sp JOIN users u ON u.user_id = sp.student_id
WHERE sp.student_code IN (SELECT code FROM team)
ORDER BY 1;

COMMIT;
