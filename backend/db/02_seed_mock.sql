-- =====================================================================
-- Bangmod Smart Attendance (CP26MS3) — 02: small mock data for real testing (local / UAT)
--   psql -U postgres -d bangmod_attendance -f 02_seed_mock.sql
--
-- * 1 admin, 1 lecturer, the 3 project-team students — password Password@123 for everyone
-- * 1 course / 1 section (INT201-001) taught in room 4303 = the room with the real camera (Pi)
-- * Dates follow the day you run it: the semester runs from 8 weeks ago to 10 weeks ahead,
--   class every Tuesday 13:00–16:00 starting 4 weeks ago → the 4 past classes are completed
--   with attendance (for the dashboard), the rest are scheduled.
--   Need a class right now for the camera? run 03_demo_live_session.sql
-- * The team has NOT agreed to consent or registered a face — do that in the web app, so the
--   camera can match real faces (no fake embeddings in this file)
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

-- Deterministic helper (exists only for this session)
CREATE FUNCTION pg_temp.uid(text) RETURNS uuid LANGUAGE sql IMMUTABLE AS $$ SELECT md5($1)::uuid $$;

-- =====================================================================
-- 1. Users — 1 admin, 1 lecturer, 3 students (project team)
-- =====================================================================

INSERT INTO users (user_id, email, password_hash, title, first_name, last_name, role, created_at)
SELECT pg_temp.uid('user:' || v.email), v.email, crypt('Password@123', gen_salt('bf', 10)),
       v.title, v.first_name, v.last_name, v.role, now() - interval '60 days'
FROM (VALUES
  ('admin@sit.kmutt.ac.th',       'นาย',   'วิทยา',  'ศรีสุข',     'admin'),
  ('supawadee.c@sit.kmutt.ac.th', 'อ.ดร.', 'สุภาวดี', 'จันทร์เพ็ญ',  'lecturer')
) AS v(email, title, first_name, last_name, role);

CREATE TEMP TABLE mock_students (student_code text, title text, first_name text, last_name text)
ON COMMIT DROP;
INSERT INTO mock_students VALUES
  ('66130500013', 'นาย', 'จักรพงษ์', 'สุขสมบรูณ์'),
  ('66130500036', 'นาย', 'ทัตพล',   'สีล้ง'),
  ('66130500041', 'นาย', 'ธรรมสรณ์', 'ฉัตรชยานุสรณ');

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
-- 3. Semester, course, section, timetable
-- =====================================================================

CREATE TEMP TABLE d ON COMMIT DROP AS
SELECT (date_trunc('week', current_date) - interval '8 weeks')::date AS sem_start,     -- a Monday
       (date_trunc('week', current_date) + interval '10 weeks' + interval '4 days')::date AS sem_end,
       -- the 4 Tuesdays before today = completed classes
       (current_date - ((extract(isodow FROM current_date)::int + 4) % 7 + 1))::date AS last_class;

INSERT INTO semesters (semester_id, academic_year, term, start_date, end_date)
SELECT pg_temp.uid('semester:2569/1'), 2569, 1, sem_start, sem_end FROM d;

INSERT INTO courses (course_id, course_code, course_name, created_by, created_at)
VALUES (pg_temp.uid('course:INT201'), 'INT201', 'Client-Side Web Programming',
        pg_temp.uid('user:admin@sit.kmutt.ac.th'), now() - interval '60 days');

INSERT INTO sections (section_id, course_id, semester_id, section_code, room_id, start_time, end_time,
                      check_in_opens, check_in_closes, on_time_minutes, late_until,
                      camera_attendance_enabled, created_at)
VALUES (pg_temp.uid('section:INT201-001'), pg_temp.uid('course:INT201'), pg_temp.uid('semester:2569/1'),
        '001', pg_temp.uid('room:4303'), '13:00', '16:00', '13:00', '13:30', 15, NULL, true,
        now() - interval '60 days');

INSERT INTO section_class_days (section_id, day_of_week) VALUES (pg_temp.uid('section:INT201-001'), 2);  -- Tuesday

INSERT INTO section_lecturers (section_id, lecturer_id, is_primary, created_at)
VALUES (pg_temp.uid('section:INT201-001'), pg_temp.uid('user:supawadee.c@sit.kmutt.ac.th'), true,
        now() - interval '60 days');

-- =====================================================================
-- 4. Enrollments — the whole team
-- =====================================================================

INSERT INTO enrollments (section_id, student_id, status, enrolled_at)
SELECT pg_temp.uid('section:INT201-001'), pg_temp.uid('student:' || s.student_code), 'active',
       now() - interval '55 days'
FROM mock_students s;

-- =====================================================================
-- 5. Class sessions — every Tuesday from 4 weeks ago to the end of the semester
-- =====================================================================

INSERT INTO class_sessions (session_id, section_id, session_no, session_date, room_id,
                            start_time, end_time, check_in_opens, check_in_closes,
                            on_time_until, late_until, session_status, created_at)
SELECT pg_temp.uid('session:INT201-001:' || g.t::date),
       s.section_id, row_number() OVER (ORDER BY g.t), g.t::date, s.room_id,
       s.start_time, s.end_time, s.check_in_opens, s.check_in_closes,
       s.check_in_opens + make_interval(mins => s.on_time_minutes), coalesce(s.late_until, s.check_in_closes),
       CASE WHEN g.t::date <= d.last_class THEN 'completed' ELSE 'scheduled' END,
       now() - interval '55 days'
FROM sections s, d,
     generate_series(d.last_class - 21, d.sem_end, interval '7 days') AS g(t);

-- =====================================================================
-- 6. Consent — only the current text; nobody has agreed yet (register in the app)
-- =====================================================================

INSERT INTO consent_versions (version, content_sha256, effective_from, is_current) VALUES
  -- replace the hash with sha256 of the exact text in consent-text.js before go-live
  ('v1', encode(digest('consent-text-v1 (claude/pdpa-consent-v1.md)', 'sha256'), 'hex'),
   '2026-08-01 00:00', true);

-- =====================================================================
-- 7. Attendance for the 4 completed classes — the lecturer checked everyone in by hand
--    (no faces registered yet) · absent = system · a small mix so the dashboard has something to show:
--    66130500013 low risk · 66130500036 one absence · 66130500041 two absences (medium risk)
-- =====================================================================

CREATE TEMP TABLE mock_attendance (student_code text, statuses text[]) ON COMMIT DROP;
INSERT INTO mock_attendance VALUES       -- oldest → newest class
  ('66130500013', '{on_time,on_time,late,on_time}'),
  ('66130500036', '{on_time,absent,on_time,late}'),
  ('66130500041', '{absent,late,absent,on_time}');

INSERT INTO attendance_records (attendance_id, session_id, student_id, status, check_in_time,
                                source, recorded_by, reason, created_at, updated_at)
SELECT pg_temp.uid('attendance:' || cs.session_id || ':' || ma.student_code),
       cs.session_id, pg_temp.uid('student:' || ma.student_code), st.status,
       CASE st.status WHEN 'on_time' THEN (cs.session_date + time '13:05')::timestamptz
                      WHEN 'late'    THEN (cs.session_date + time '13:20')::timestamptz END,
       CASE WHEN st.status = 'absent' THEN 'system' ELSE 'manual' END,
       CASE WHEN st.status <> 'absent' THEN pg_temp.uid('user:supawadee.c@sit.kmutt.ac.th') END,
       CASE WHEN st.status <> 'absent' THEN 'ข้อมูลทดสอบ — อาจารย์เช็คชื่อเอง (ยังไม่ได้ลงทะเบียนใบหน้า)' END,
       (cs.session_date + time '13:30')::timestamptz, (cs.session_date + time '13:30')::timestamptz
FROM mock_attendance ma
JOIN class_sessions cs ON cs.session_status = 'completed'
CROSS JOIN LATERAL (SELECT ma.statuses[cs.session_no]::text AS status) st;

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
