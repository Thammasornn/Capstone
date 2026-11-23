-- =====================================================================
-- Bangmod Smart Attendance (CP26MS3) — 02: mock data for Release 1
-- (+ mock rows for the Release 2–3 tables that hang off Release 1 data)
--   psql -U postgres -d bangmod_attendance -f 02_seed_mock.sql
--
-- * Semester 1/2569 (10 Aug – 11 Dec 2026). Data is written "as of" Sat 21 Nov 2026:
--   sessions before that date are completed and have attendance, later ones are scheduled.
-- * Every mock account logs in with the password  Password@123
-- * IDs are deterministic (md5 of a readable key), so they stay the same on every
--   environment and can be used in Postman / front-end mocks.
-- * All people are fictional. Face embeddings are random bytes, not real faces.
-- * Re-runnable: it truncates every table first. NEVER run it on Production.
-- =====================================================================

BEGIN;
SET LOCAL timezone = 'Asia/Bangkok';

TRUNCATE notifications, occupancy_snapshots, follow_ups, audit_logs, data_deletion_jobs,
         face_embeddings, consent_records, consent_versions, attendance_records, class_sessions,
         enrollments, section_lecturers, section_class_days, sections, courses, semesters,
         cameras, rooms, student_profiles, users;

-- Deterministic helpers (exist only for this session)
CREATE FUNCTION pg_temp.uid(text) RETURNS uuid LANGUAGE sql IMMUTABLE AS $$ SELECT md5($1)::uuid $$;
CREATE FUNCTION pg_temp.h(text)   RETURNS int  LANGUAGE sql IMMUTABLE AS $$ SELECT abs(hashtext($1)) % 1000 $$;

-- =====================================================================
-- 1. Users — 2 admins, 5 lecturers, 37 students + 5 test personas + 3 team members (45)
-- =====================================================================

-- Staff
INSERT INTO users (user_id, email, password_hash, title, first_name, last_name, role, last_login_at, created_at)
SELECT pg_temp.uid('user:' || v.email), v.email, crypt('Password@123', gen_salt('bf', 10)),
       v.title, v.first_name, v.last_name, v.role, v.last_login::timestamptz, '2026-07-20 09:00'
FROM (VALUES
  ('admin@sit.kmutt.ac.th',          'นาย',     'วิทยา',    'ศรีสุข',       'admin',    '2026-11-20 17:12'),
  ('piyanuch.k@sit.kmutt.ac.th',     'นางสาว',  'ปิยะนุช',   'แก้วมณี',      'admin',    '2026-11-18 10:03'),
  ('thanakorn.w@sit.kmutt.ac.th',    'ผศ.ดร.',  'ธนากร',    'วงศ์ไพบูลย์',   'lecturer', '2026-11-17 08:55'),
  ('supawadee.c@sit.kmutt.ac.th',    'อ.ดร.',   'สุภาวดี',   'จันทร์เพ็ญ',    'lecturer', '2026-11-19 12:40'),
  ('nattapon.r@sit.kmutt.ac.th',     'อ.',      'ณัฐพล',    'รัตนชัย',       'lecturer', '2026-11-19 08:50'),
  ('kamonwan.s@sit.kmutt.ac.th',     'รศ.ดร.',  'กมลวรรณ',  'ศักดิ์สิทธิ์',   'lecturer', '2026-11-20 12:58'),
  ('pakorn.l@sit.kmutt.ac.th',       'อ.',      'ปกรณ์',    'เลิศวิริยะ',    'lecturer', '2026-11-18 12:47')
) AS v(email, title, first_name, last_name, role, last_login);

-- Students: behaviour drives the generated attendance
--   normal ≈ 3 % absent · medium ≈ 13 % · high ≈ 28 %
--   noface    = never registered a face (lecturer checks them in by hand)
--   withdrawn = withdrew face consent on 20 Oct 2026
--   graduated = left the university (face data queued for deletion)
-- Test personas (one per state the student screens must handle — see README "บัญชีทดสอบ"):
--   reconsent   = agreed to an older consent text (v0) → needs_reconsent = true
--   consentonly = agreed to v1 but no face vectors yet (upload failed / never sent photos)
--   minor       = under 20 (is_adult = false, B9) — cannot consent alone, checked in by hand
--   nocourse    = active year-4 student on internship, no classes this term
--   resigned    = left the university (academic_status withdrawn) — account disabled
CREATE TEMP TABLE mock_students (
  student_code text, title text, first_name text, last_name text,
  entry_year smallint, behaviour text, academic_status text
) ON COMMIT DROP;

INSERT INTO mock_students VALUES
  -- Year 1 · entry 2569
  ('69130500001','นาย',   'ธนวัฒน์',    'ศรีสวัสดิ์',    2569,'normal',   'active'),
  ('69130500002','นางสาว','ปวีณา',      'วงศ์สกุล',     2569,'normal',   'active'),
  ('69130500003','นาย',   'ณัฐวุฒิ',     'ทองดี',        2569,'normal',   'active'),
  ('69130500004','นาย',   'กิตติพัฒน์',   'แสงอรุณ',      2569,'high',     'active'),
  ('69130500005','นางสาว','ชนิดาภา',    'บุญมา',        2569,'normal',   'active'),
  ('69130500006','นางสาว','ณัฐธิดา',     'จันทร์หอม',     2569,'normal',   'active'),
  ('69130500007','นาย',   'ภูมิภัทร',     'สุขเจริญ',     2569,'normal',   'active'),
  ('69130500008','นางสาว','พิมพ์ชนก',    'พงษ์พันธ์',     2569,'normal',   'active'),
  ('69130500009','นาย',   'วรเมธ',      'รุ่งเรือง',      2569,'medium',   'active'),
  ('69130500010','นางสาว','กมลชนก',    'ชัยมงคล',      2569,'normal',   'active'),
  ('69130500011','นาย',   'ศุภกร',      'กิตติวงศ์',     2569,'noface',   'active'),
  ('69130500012','นางสาว','ศิริรัตน์',    'มณีรัตน์',      2569,'normal',   'active'),
  -- Year 2 · entry 2568
  ('68130500001','นาย',   'ชยพล',      'ศักดิ์ดี',       2568,'normal',   'active'),
  ('68130500002','นางสาว','ธนพร',      'อินทรประเสริฐ', 2568,'normal',   'active'),
  ('68130500003','นาย',   'อภิสิทธิ์',    'เพชรรัตน์',     2568,'high',     'active'),
  ('68130500004','นางสาว','อรณิชา',     'ธรรมโชติ',     2568,'normal',   'active'),
  ('68130500005','นาย',   'พีรพัฒน์',    'วิเศษสุวรรณ',   2568,'withdrawn','active'),
  ('68130500006','นางสาว','ภัทรวดี',     'นาคสุข',       2568,'normal',   'active'),
  ('68130500007','นาย',   'ธีรภัทร',     'ประเสริฐศรี',   2568,'medium',   'active'),
  ('68130500008','นางสาว','วรรณิศา',    'ทรัพย์มาก',     2568,'normal',   'active'),
  ('68130500009','นาย',   'ณภัทร',      'ศรีสวัสดิ์',    2568,'normal',   'active'),
  ('68130500010','นางสาว','สุพิชญา',     'ทองดี',        2568,'noface',   'active'),
  ('68130500011','นาย',   'กฤตภาส',    'บุญมา',        2568,'normal',   'active'),
  ('68130500012','นางสาว','เบญจมาศ',    'แสงอรุณ',      2568,'normal',   'active'),
  -- Year 3 · entry 2567
  ('67130500001','นาย',   'รัชพล',      'จันทร์หอม',     2567,'normal',   'active'),
  ('67130500002','นาย',   'ปัณณวิชญ์',   'สุขเจริญ',     2567,'high',     'active'),
  ('67130500003','นางสาว','ปภาวรินทร์',   'พงษ์พันธ์',     2567,'normal',   'active'),
  ('67130500004','นางสาว','ชุติกาญจน์',   'รุ่งเรือง',      2567,'normal',   'active'),
  ('67130500005','นาย',   'ศิวกร',      'ชัยมงคล',      2567,'normal',   'active'),
  ('67130500006','นางสาว','นันทิชา',     'กิตติวงศ์',     2567,'medium',   'active'),
  ('67130500007','นาย',   'ภาคิน',      'มณีรัตน์',      2567,'normal',   'active'),
  ('67130500008','นางสาว','กัญญาณัฐ',   'ศักดิ์ดี',       2567,'medium',   'active'),
  ('67130500009','นาย',   'ธนกฤต',     'อินทรประเสริฐ', 2567,'normal',   'active'),
  ('67130500010','นางสาว','ณิชาภัทร',    'เพชรรัตน์',     2567,'normal',   'active'),
  ('67130500011','นาย',   'ศุภวิชญ์',    'ธรรมโชติ',     2567,'noface',   'active'),
  ('67130500012','นางสาว','พัชราภา',    'วิเศษสุวรรณ',   2567,'normal',   'active'),
  -- Graduated (no classes this term)
  ('65130500017','นาย',   'ชนาธิป',     'นาคสุข',       2565,'graduated','graduated'),
  -- Test personas (codes 0599xxx so they are easy to spot)
  ('69130599001','นาย',   'ทดสอบ',      'ยินยอมฉบับเก่า', 2569,'reconsent',  'active'),
  ('69130599002','นางสาว','ทดสอบ',      'ยินยอมแต่ไม่มีใบหน้า', 2569,'consentonly','active'),
  ('69130599003','นาย',   'ทดสอบ',      'อายุไม่ถึง20',  2569,'minor',      'active'),
  ('66130599004','นางสาว','ทดสอบ',      'ไม่มีวิชาเทอมนี้', 2566,'nocourse',   'active'),
  ('66130599005','นาย',   'ทดสอบ',      'ลาออก',        2566,'resigned',   'withdrawn'),
  -- Project team (real members, see README "สมาชิก") — not registered yet, enrolled in INT201-001
  -- so they can test face registration themselves (their real face data is NOT part of this seed)
  ('66130500013','นาย',   'จักรพงษ์',    'สุขสมบรูณ์',    2566,'noface',     'active'),
  ('66130500036','นาย',   'ทัตพล',      'สีล้ง',         2566,'noface',     'active'),
  ('66130500041','นาย',   'ธรรมสรณ์',    'ฉัตรชยานุสรณ',   2566,'noface',     'active');

INSERT INTO users (user_id, email, password_hash, title, first_name, last_name, role, is_active, created_at)
SELECT pg_temp.uid('student:' || s.student_code),
       s.student_code || '@st.sit.kmutt.ac.th',
       crypt('Password@123', gen_salt('bf', 10)),
       s.title, s.first_name, s.last_name, 'student',
       s.academic_status = 'active',
       '2026-07-25 09:00'
FROM mock_students s;

INSERT INTO student_profiles (student_id, student_code, faculty, program, entry_year, is_adult,
                              academic_status, status_changed_at)
SELECT pg_temp.uid('student:' || s.student_code), s.student_code,
       'คณะเทคโนโลยีสารสนเทศ', 'IT', s.entry_year,
       CASE WHEN s.behaviour = 'minor' THEN false END,        -- B9: waiting for the DPO decision (NULL = unknown)
       s.academic_status,
       CASE WHEN s.academic_status IN ('graduated', 'withdrawn') THEN timestamptz '2026-11-15 09:00' END
FROM mock_students s;

-- =====================================================================
-- 2. Rooms and cameras
-- =====================================================================

INSERT INTO rooms (room_id, room_code, building, capacity)
SELECT pg_temp.uid('room:' || v.code), v.code, 'อาคาร SIT', v.cap
FROM (VALUES ('4301', 60), ('4302', 50), ('4303', 45), ('4304', 40), ('4305', 40)) v(code, cap);

INSERT INTO cameras (camera_id, room_id, camera_code, stream_path, status, last_seen_at)
SELECT pg_temp.uid('camera:' || r.room_code), r.room_id,
       'CAM-' || r.room_code || '-A', 'room-' || r.room_code,
       CASE WHEN r.room_code = '4305' THEN 'no_signal' ELSE 'live' END,
       CASE WHEN r.room_code = '4305' THEN timestamptz '2026-11-19 16:02'
            ELSE timestamptz '2026-11-21 15:59' END
FROM rooms r;

-- =====================================================================
-- 3. Semesters, courses, sections, timetable
-- =====================================================================

INSERT INTO semesters (semester_id, academic_year, term, start_date, end_date) VALUES
  (pg_temp.uid('semester:2569/1'), 2569, 1, '2026-08-10', '2026-12-11'),
  (pg_temp.uid('semester:2569/2'), 2569, 2, '2027-01-11', '2027-05-14');

INSERT INTO courses (course_id, course_code, course_name, created_by, created_at, deleted_at)
SELECT pg_temp.uid('course:' || v.code), v.code, v.name,
       pg_temp.uid('user:admin@sit.kmutt.ac.th'), '2026-07-28 10:00', v.deleted_at::timestamptz
FROM (VALUES
  ('INT101', 'Information Technology Fundamentals', NULL),
  ('INT102', 'Web Technology',                      NULL),
  ('INT103', 'Programming Fundamentals',            NULL),
  ('INT201', 'Client-Side Web Programming',         NULL),
  ('INT202', 'Database Management Systems',         NULL),
  ('INT301', 'Software Engineering',                NULL),
  ('INT302', 'Information Security',                NULL),
  ('INT299', 'Special Topics in IT',                '2026-08-01 11:20')   -- soft-deleted example
) v(code, name, deleted_at);

CREATE TEMP TABLE mock_sections (
  course_code text, section_code text, room_code text, days int[],
  start_time time, end_time time, opens time, closes time,
  on_time_minutes smallint, late_until time, camera boolean,
  lecturers text[]                               -- first = primary
) ON COMMIT DROP;

INSERT INTO mock_sections VALUES
  ('INT101','001','4301','{1}',   '09:00','12:00','09:00','09:30',15,NULL,   true, '{thanakorn.w}'),
  ('INT101','002','4301','{1}',   '13:00','16:00','13:00','13:30',15,NULL,   true, '{thanakorn.w}'),
  ('INT102','001','4302','{3}',   '09:00','12:00','09:00','09:30',15,NULL,   true, '{supawadee.c}'),
  ('INT103','001','4303','{2,4}', '09:00','10:30','09:00','09:20',10,NULL,   true, '{nattapon.r}'),
  ('INT201','001','4302','{2}',   '13:00','16:00','13:00','13:30',15,NULL,   true, '{supawadee.c}'),
  ('INT202','001','4303','{3}',   '13:00','16:00','13:00','13:30',15,NULL,   false,'{kamonwan.s}'),   -- camera off
  ('INT301','001','4304','{1,3}', '13:00','14:30','13:00','14:30',20,'13:30',true, '{pakorn.l,thanakorn.w}'),  -- R2 rule
  ('INT302','001','4305','{4}',   '13:00','16:00','13:00','13:30',15,NULL,   true, '{kamonwan.s}');

INSERT INTO sections (section_id, course_id, semester_id, section_code, room_id, start_time, end_time,
                      check_in_opens, check_in_closes, on_time_minutes, late_until,
                      camera_attendance_enabled, created_at)
SELECT pg_temp.uid('section:' || m.course_code || '-' || m.section_code),
       pg_temp.uid('course:' || m.course_code), pg_temp.uid('semester:2569/1'),
       m.section_code, pg_temp.uid('room:' || m.room_code),
       m.start_time, m.end_time, m.opens, m.closes, m.on_time_minutes, m.late_until, m.camera,
       '2026-07-28 10:30'
FROM mock_sections m;

INSERT INTO section_class_days (section_id, day_of_week)
SELECT pg_temp.uid('section:' || m.course_code || '-' || m.section_code), d
FROM mock_sections m, unnest(m.days) d;

INSERT INTO section_lecturers (section_id, lecturer_id, is_primary, created_at)
SELECT pg_temp.uid('section:' || m.course_code || '-' || m.section_code),
       pg_temp.uid('user:' || l.login || '@sit.kmutt.ac.th'), l.ord = 1, '2026-07-28 10:30'
FROM mock_sections m, unnest(m.lecturers) WITH ORDINALITY l(login, ord);

-- =====================================================================
-- 4. Enrollments
-- =====================================================================

INSERT INTO enrollments (section_id, student_id, status, enrolled_at, dropped_at)
SELECT pg_temp.uid('section:' || x.sec), pg_temp.uid('student:' || x.code),
       CASE WHEN x.dropped IS NULL THEN 'active' ELSE 'dropped' END,
       '2026-08-05 10:00', x.dropped::timestamptz
FROM (
  -- Year 1: INT101 split into two sections, INT102 + INT103 everyone
  SELECT CASE WHEN right(student_code, 3)::int <= 6 THEN 'INT101-001' ELSE 'INT101-002' END, student_code, NULL
    FROM mock_students WHERE entry_year = 2569
  UNION ALL SELECT 'INT102-001', student_code, NULL FROM mock_students WHERE entry_year = 2569
  UNION ALL SELECT 'INT103-001', student_code, NULL FROM mock_students WHERE entry_year = 2569
  -- Year 2: INT201 (one drop on 7 Sep) + INT202
  UNION ALL SELECT 'INT201-001', student_code,
                   CASE WHEN student_code = '68130500012' THEN '2026-09-07 11:00' END
    FROM mock_students WHERE entry_year = 2568
  UNION ALL SELECT 'INT202-001', student_code, NULL FROM mock_students WHERE entry_year = 2568
  -- Year 3: INT301 + INT302, two of them re-take INT202
  UNION ALL SELECT 'INT301-001', student_code, NULL FROM mock_students WHERE entry_year = 2567
  UNION ALL SELECT 'INT302-001', student_code, NULL FROM mock_students WHERE entry_year = 2567
  UNION ALL SELECT 'INT202-001', c, NULL FROM unnest(ARRAY['67130500002', '67130500009']) c
  -- Project team: INT201-001 (has the live demo session in room 4302)
  UNION ALL SELECT 'INT201-001', c, NULL FROM unnest(ARRAY['66130500013', '66130500036', '66130500041']) c
) x(sec, code, dropped);

-- =====================================================================
-- 5. Class sessions — every class day of 1/2569 until 27 Nov
--    no classes in midterm week (28 Sep – 2 Oct) · public holidays are cancelled
-- =====================================================================

INSERT INTO class_sessions (session_id, section_id, session_no, session_date, room_id,
                            start_time, end_time, check_in_opens, check_in_closes,
                            on_time_until, late_until, session_status, created_at)
SELECT pg_temp.uid('session:' || s.section_id || ':' || d.day),
       s.section_id,
       row_number() OVER (PARTITION BY s.section_id ORDER BY d.day),
       d.day, s.room_id, s.start_time, s.end_time, s.check_in_opens, s.check_in_closes,
       s.check_in_opens + make_interval(mins => s.on_time_minutes),
       coalesce(s.late_until, s.check_in_closes),
       CASE WHEN d.day IN ('2026-08-12', '2026-10-13', '2026-10-23') THEN 'cancelled'
            WHEN d.day < '2026-11-21' THEN 'completed'
            ELSE 'scheduled' END,
       '2026-08-01 09:00'
FROM sections s
JOIN section_class_days scd ON scd.section_id = s.section_id
JOIN LATERAL (
  SELECT g::date AS day
  FROM generate_series(date '2026-08-10', date '2026-11-27', interval '1 day') g
  WHERE extract(isodow FROM g) = scd.day_of_week
    AND g::date NOT BETWEEN '2026-09-28' AND '2026-10-02'
) d ON true;

-- =====================================================================
-- 6. Consent and face data (PDPA)
-- =====================================================================

INSERT INTO consent_versions (version, content_sha256, effective_from, is_current) VALUES
  -- older text kept for the 'reconsent' persona (D6: agreed to an outdated version)
  ('v0', encode(digest('consent-text-v0 (pilot)', 'sha256'), 'hex'), '2025-08-01 00:00', false),
  -- replace the hash with sha256 of the exact text in consent-text.js before go-live
  ('v1', encode(digest('consent-text-v1 (claude/pdpa-consent-v1.md)', 'sha256'), 'hex'),
   '2026-08-01 00:00', true);

-- Everyone except 'noface' / 'minor' agreed during registration week (3–8 Aug);
-- the graduate, the resigned student and 'reconsent' (on v0) agreed a year earlier
INSERT INTO consent_records (consent_id, student_id, version, action, age_confirmed, ip_address, user_agent, created_at)
SELECT pg_temp.uid('consent:granted:' || s.student_code),
       pg_temp.uid('student:' || s.student_code),
       CASE WHEN s.behaviour = 'reconsent' THEN 'v0' ELSE 'v1' END, 'granted', true,
       ('10.20.' || (pg_temp.h(s.student_code) % 200 + 1) || '.' || (pg_temp.h(s.student_code || 'ip') % 250 + 2))::inet,
       CASE pg_temp.h(s.student_code) % 3
            WHEN 0 THEN 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) Safari/604.1'
            WHEN 1 THEN 'Mozilla/5.0 (Linux; Android 15; SM-A556E) Chrome/131.0 Mobile Safari/537.36'
            ELSE        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0 Safari/537.36' END,
       CASE WHEN s.behaviour IN ('graduated', 'resigned', 'reconsent') THEN timestamptz '2025-08-15 10:00'
            ELSE timestamptz '2026-08-03 09:00'
                 + make_interval(days => pg_temp.h(s.student_code) % 6,
                                 mins => pg_temp.h(s.student_code || 'm') % 600) END
FROM mock_students s
WHERE s.behaviour NOT IN ('noface', 'minor');

-- 68130500005 withdrew consent on 20 Oct (B7)
INSERT INTO consent_records (consent_id, student_id, version, action, age_confirmed, ip_address, user_agent, created_at)
VALUES (pg_temp.uid('consent:withdrawn:68130500005'), pg_temp.uid('student:68130500005'), 'v1', 'withdrawn',
        true, '10.20.77.14', 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) Safari/604.1',
        '2026-10-20 14:05:00');

-- 3 vectors per student (5 for some); none for the student who withdrew (already deleted)
INSERT INTO face_embeddings (embedding_id, student_id, consent_id, embedding, model_name, model_version, created_at)
SELECT pg_temp.uid('embedding:' || s.student_code || ':' || n),
       pg_temp.uid('student:' || s.student_code), cr.consent_id,
       gen_random_bytes(540),                          -- mock ciphertext: 12 nonce + 512 data + 16 tag
       'sface', '2021dec', cr.created_at + interval '40 seconds'
FROM mock_students s
JOIN consent_records cr ON cr.consent_id = pg_temp.uid('consent:granted:' || s.student_code)
CROSS JOIN LATERAL generate_series(1, CASE WHEN pg_temp.h(s.student_code || 'n') % 4 = 0 THEN 5 ELSE 3 END) n
WHERE s.behaviour NOT IN ('noface', 'withdrawn', 'consentonly', 'minor');

INSERT INTO data_deletion_jobs (job_id, student_id, reason, requested_at, due_at, completed_at, deleted_count) VALUES
  (pg_temp.uid('deletion:68130500005'), pg_temp.uid('student:68130500005'), 'withdrawn',
   '2026-10-20 14:05:00', '2026-11-19 14:05:00', '2026-10-20 14:05:02', 3),
  (pg_temp.uid('deletion:65130500017'), pg_temp.uid('student:65130500017'), 'graduated',
   '2026-11-15 09:00:00', '2026-12-15 09:00:00', NULL, NULL),       -- pending: the daily job will delete
  (pg_temp.uid('deletion:66130599005'), pg_temp.uid('student:66130599005'), 'graduated',
   '2026-11-15 09:00:00', '2026-12-15 09:00:00', NULL, NULL);       -- resigned: same 30-day rule (no own reason value)

-- =====================================================================
-- 7. Attendance for every completed session
-- =====================================================================

INSERT INTO attendance_records (attendance_id, session_id, student_id, status, check_in_time, last_seen_at,
                                source, match_score, recorded_by, reason, created_at, updated_at)
WITH base AS (
  SELECT cs.session_id, cs.session_date, cs.check_in_opens, cs.on_time_until, cs.late_until,
         cs.check_in_closes, cs.end_time, s.camera_attendance_enabled, sp.student_id, ms.behaviour,
         (SELECT sl.lecturer_id FROM section_lecturers sl
           WHERE sl.section_id = s.section_id AND sl.is_primary) AS lecturer_id,
         -- sessions this student misses in this section: normal 0–1 · medium 2 · high 3–5
         CASE ms.behaviour
           WHEN 'high'   THEN 3 + pg_temp.h(sp.student_id::text || s.section_id::text) % 3
           WHEN 'medium' THEN 2
           ELSE CASE WHEN pg_temp.h(sp.student_id::text || s.section_id::text) % 4 = 0 THEN 1 ELSE 0 END
         END AS absences,
         row_number() OVER (PARTITION BY s.section_id, sp.student_id
                            ORDER BY pg_temp.h(cs.session_id::text || sp.student_id::text || 'a')) AS miss_rank,
         pg_temp.h(cs.session_id::text || sp.student_id::text || 'a') AS h1,
         pg_temp.h(cs.session_id::text || sp.student_id::text || 'b') AS h2,
         pg_temp.h(cs.session_id::text || sp.student_id::text || 'c') AS h3
  FROM class_sessions cs
  JOIN sections s          ON s.section_id = cs.section_id
  JOIN enrollments e       ON e.section_id = cs.section_id
  JOIN student_profiles sp ON sp.student_id = e.student_id
  JOIN mock_students ms    ON ms.student_code = sp.student_code
  WHERE cs.session_status = 'completed'
    AND (e.status = 'active' OR cs.session_date < e.dropped_at::date)
), outcome AS (
  SELECT b.*,
         CASE WHEN b.miss_rank <= b.absences THEN 'absent'
              WHEN b.h3 < CASE b.behaviour WHEN 'high' THEN 250 WHEN 'medium' THEN 200 ELSE 90 END
                THEN 'late'
              ELSE 'on_time' END AS status,
         CASE WHEN NOT b.camera_attendance_enabled THEN 'camera_off'
              WHEN b.behaviour IN ('noface', 'consentonly', 'minor') THEN 'noface'
              WHEN b.behaviour = 'withdrawn' AND b.session_date >= '2026-10-20' THEN 'withdrawn'
              ELSE 'camera' END AS mode
  FROM base b
), timed AS (
  SELECT o.*,
         CASE o.status
           WHEN 'on_time' THEN (o.session_date + o.check_in_opens)
                + make_interval(secs => o.h2 * extract(epoch FROM o.on_time_until - o.check_in_opens) / 1000)
           WHEN 'late'    THEN (o.session_date + o.on_time_until) + interval '30 seconds'
                + make_interval(secs => o.h2 * (extract(epoch FROM o.late_until - o.on_time_until) - 60) / 1000)
         END::timestamptz AS check_in
  FROM outcome o
)
SELECT pg_temp.uid('attendance:' || t.session_id || ':' || t.student_id),
       t.session_id, t.student_id, t.status,
       date_trunc('second', t.check_in),
       CASE WHEN t.status = 'absent' OR t.mode <> 'camera' THEN NULL
            WHEN t.h2 % 40 = 0 THEN date_trunc('second', t.check_in) + interval '35 minutes'      -- left early
            ELSE (t.session_date + t.end_time - make_interval(mins => t.h1 % 12))::timestamptz END,
       CASE WHEN t.status = 'absent' THEN 'system'
            WHEN t.mode = 'camera'   THEN 'camera'
            ELSE 'manual' END,
       CASE WHEN t.status <> 'absent' AND t.mode = 'camera' THEN round(0.4 + (t.h2 % 320) / 1000.0, 4) END,
       CASE WHEN t.status <> 'absent' AND t.mode <> 'camera' THEN t.lecturer_id END,
       CASE WHEN t.status = 'absent' THEN NULL
            WHEN t.mode = 'camera_off' THEN 'ปิดการเช็คชื่อด้วยกล้องในวิชานี้ — อาจารย์เช็คชื่อเอง'
            WHEN t.mode = 'noface'     THEN 'เช็คชื่อด้วยตนเอง — ไม่ได้ลงทะเบียนใบหน้า'
            WHEN t.mode = 'withdrawn'  THEN 'ถอนความยินยอมข้อมูลใบหน้าแล้ว — อาจารย์เช็คชื่อเอง' END,
       coalesce(date_trunc('second', t.check_in), (t.session_date + t.check_in_closes)::timestamptz),
       coalesce(date_trunc('second', t.check_in), (t.session_date + t.check_in_closes)::timestamptz)
FROM timed t;

-- =====================================================================
-- 8. Audit log
-- =====================================================================

INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, before_data, after_data, ip_address, created_at) VALUES
  (pg_temp.uid('user:admin@sit.kmutt.ac.th'), 'course.delete', 'course', pg_temp.uid('course:INT299'),
   '{"deleted_at": null}', '{"deleted_at": "2026-08-01T11:20:00"}', '10.10.1.5', '2026-08-01 11:20'),
  (pg_temp.uid('user:admin@sit.kmutt.ac.th'), 'section.camera_toggle', 'section', pg_temp.uid('section:INT202-001'),
   '{"camera_attendance_enabled": true}', '{"camera_attendance_enabled": false}', '10.10.1.5', '2026-08-05 15:40'),
  (pg_temp.uid('student:68130500005'), 'consent.withdraw', 'student', pg_temp.uid('student:68130500005'),
   '{"consent": "granted"}', '{"consent": "withdrawn"}', '10.20.77.14', '2026-10-20 14:05:00'),
  (pg_temp.uid('student:68130500005'), 'face.delete', 'student', pg_temp.uid('student:68130500005'),
   '{"embedding_count": 3}', '{"embedding_count": 0}', '10.20.77.14', '2026-10-20 14:05:02'),
  (pg_temp.uid('user:admin@sit.kmutt.ac.th'), 'student.status_change', 'student', pg_temp.uid('student:65130500017'),
   '{"academic_status": "active"}', '{"academic_status": "graduated"}', '10.10.1.5', '2026-11-15 09:00'),
  (pg_temp.uid('user:admin@sit.kmutt.ac.th'), 'student.status_change', 'student', pg_temp.uid('student:66130599005'),
   '{"academic_status": "active"}', '{"academic_status": "withdrawn"}', '10.10.1.5', '2026-11-15 09:05');

-- Every manual check-in leaves an audit row
INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, after_data, ip_address, created_at)
SELECT ar.recorded_by, 'attendance.manual_set', 'attendance_record', ar.attendance_id,
       jsonb_build_object('status', ar.status, 'check_in_time', ar.check_in_time, 'reason', ar.reason),
       ('10.10.2.' || (pg_temp.h(ar.recorded_by::text) % 200 + 10))::inet, ar.check_in_time + interval '5 minutes'
FROM attendance_records ar
WHERE ar.source = 'manual';

-- =====================================================================
-- 9. Release 2 — follow-ups for high-risk students, occupancy samples for the last week
-- =====================================================================

INSERT INTO follow_ups (section_id, student_id, created_by, note, status, closed_at, created_at)
SELECT v.section_id, v.student_id,
       (SELECT lecturer_id FROM section_lecturers sl WHERE sl.section_id = v.section_id AND sl.is_primary),
       'ขาดเรียน ' || v.absent || ' ครั้ง — นัดคุยเรื่องการเข้าเรียนและแผนตามงานที่ขาด',
       CASE WHEN row_number() OVER (ORDER BY v.absent, v.student_id) = 1 THEN 'closed' ELSE 'open' END,
       CASE WHEN row_number() OVER (ORDER BY v.absent, v.student_id) = 1 THEN timestamptz '2026-11-19 16:30' END,
       '2026-11-16 10:00'
FROM v_student_section_attendance v
WHERE v.risk_level = 'high';

-- Every 10 minutes during each camera session of 16–20 Nov
INSERT INTO occupancy_snapshots (session_id, camera_id, captured_at, faces_detected, faces_recognized, unknown_faces)
SELECT x.session_id, x.camera_id, x.t, x.recognized + x.unknown, x.recognized, x.unknown
FROM (
  SELECT cs.session_id, c.camera_id, t,
         (SELECT count(*) FROM attendance_records ar
           WHERE ar.session_id = cs.session_id AND ar.source = 'camera'
             AND ar.check_in_time <= t AND ar.last_seen_at >= t)::smallint AS recognized,
         ((SELECT count(*) FROM attendance_records ar
            WHERE ar.session_id = cs.session_id AND ar.source = 'manual')
          + pg_temp.h(cs.session_id::text || t::text) % 2)::smallint      AS unknown
  FROM class_sessions cs
  JOIN sections s ON s.section_id = cs.section_id AND s.camera_attendance_enabled
  JOIN cameras  c ON c.room_id = cs.room_id
  CROSS JOIN LATERAL generate_series((cs.session_date + cs.check_in_opens)::timestamptz,
                                     (cs.session_date + cs.end_time)::timestamptz,
                                     interval '10 minutes') t
  WHERE cs.session_status = 'completed' AND cs.session_date BETWEEN '2026-11-16' AND '2026-11-20'
) x;

-- =====================================================================
-- 10. Release 3 — notifications
-- =====================================================================

-- Students who never registered a face
INSERT INTO notifications (user_id, type, title, body, created_at)
SELECT pg_temp.uid('student:' || s.student_code), 'face_registration_reminder',
       'ยังไม่ได้ลงทะเบียนใบหน้า',
       'ลงทะเบียนใบหน้าเพื่อให้กล้องเช็คชื่อให้อัตโนมัติ หรือแจ้งอาจารย์เพื่อเช็คชื่อด้วยวิธีอื่น',
       ts
FROM mock_students s,
     unnest(ARRAY[timestamptz '2026-08-09 08:00', timestamptz '2026-08-16 08:00']) ts
WHERE s.behaviour IN ('noface', 'consentonly', 'minor');

-- Cancelled classes (public holidays), sent the day before
INSERT INTO notifications (user_id, type, title, body, session_id, read_at, created_at)
SELECT e.student_id, 'class_cancelled',
       'งดคลาส ' || co.course_code || ' วันที่ ' || to_char(cs.session_date, 'DD/MM/YYYY'),
       'วันหยุดราชการ — งดการเรียนการสอนและไม่มีการเช็คชื่อ',
       cs.session_id,
       CASE WHEN pg_temp.h(e.student_id::text || cs.session_id::text) % 5 <> 0
            THEN (cs.session_date - 1 + time '19:30')::timestamptz END,
       (cs.session_date - 1 + time '12:00')::timestamptz
FROM class_sessions cs
JOIN sections s    ON s.section_id = cs.section_id
JOIN courses co    ON co.course_id = s.course_id
JOIN enrollments e ON e.section_id = cs.section_id
                  AND (e.status = 'active' OR cs.session_date < e.dropped_at::date)
WHERE cs.session_status = 'cancelled';

-- Reminder 30 minutes before each class on Thu 19 – Fri 20 Nov
INSERT INTO notifications (user_id, type, title, body, session_id, read_at, created_at)
SELECT e.student_id, 'class_reminder',
       co.course_code || ' เริ่ม ' || to_char(cs.start_time, 'HH24:MI') || ' ห้อง ' || r.room_code,
       'อีก 30 นาทีคาบเรียนจะเริ่ม กล้องเริ่มเช็คชื่อ ' || to_char(cs.check_in_opens, 'HH24:MI') || ' น.',
       cs.session_id,
       (cs.session_date + cs.start_time - interval '20 minutes')::timestamptz,
       (cs.session_date + cs.start_time - interval '30 minutes')::timestamptz
FROM class_sessions cs
JOIN sections s    ON s.section_id = cs.section_id
JOIN courses co    ON co.course_id = s.course_id
JOIN rooms r       ON r.room_id = cs.room_id
JOIN enrollments e ON e.section_id = cs.section_id AND e.status = 'active'
WHERE cs.session_date BETWEEN '2026-11-19' AND '2026-11-20';

COMMIT;
