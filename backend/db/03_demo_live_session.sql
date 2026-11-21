-- =====================================================================
-- Bangmod Smart Attendance (CP26MS3) — 03 (optional): a class running right now
-- Adds a make-up session of INT201-001 (its 13 Oct class was cancelled) in room 4302,
-- starting 20 minutes before "now", so Room Selection / Room Live have live data.
-- Re-run any time to move it to the current time. Run after 02_seed_mock.sql. Not for Production.
--   psql -U postgres -d bangmod_attendance -f 03_demo_live_session.sql
-- =====================================================================

BEGIN;
SET LOCAL timezone = 'Asia/Bangkok';

DO $$
DECLARE
  v_section  uuid := md5('section:INT201-001')::uuid;
  v_room     uuid := md5('room:4302')::uuid;
  v_start    time := date_trunc('minute', localtime - interval '20 minutes')::time;
  v_end      time;
  v_session  uuid;
BEGIN
  IF localtime < time '00:30' OR localtime > time '20:30' THEN
    -- too close to midnight for a 3-hour class: use 09:00–12:00 today instead
    RAISE NOTICE 'Outside 00:30–20:30, the demo session uses 09:00–12:00 today';
    v_start := time '09:00';
  END IF;
  v_end := v_start + interval '3 hours';

  -- remove the previous demo session of today, if any
  DELETE FROM attendance_records WHERE session_id IN
    (SELECT session_id FROM class_sessions WHERE section_id = v_section AND session_date = current_date);
  DELETE FROM class_sessions WHERE section_id = v_section AND session_date = current_date;

  INSERT INTO class_sessions (section_id, session_no, session_date, room_id, start_time, end_time,
                              check_in_opens, check_in_closes, on_time_until, late_until, session_status)
  SELECT v_section, max(session_no) + 1, current_date, v_room, v_start, v_end,
         v_start, v_start + interval '30 minutes', v_start + interval '15 minutes',
         v_start + interval '30 minutes', 'in_progress'
  FROM class_sessions WHERE section_id = v_section
  RETURNING session_id INTO v_session;

  -- about 3 in 4 registered students have already been seen by the camera
  INSERT INTO attendance_records (session_id, student_id, status, check_in_time, last_seen_at, source, match_score)
  SELECT v_session, e.student_id,
         CASE WHEN t.check_in < current_date + v_start + interval '15 minutes' THEN 'on_time' ELSE 'late' END,
         t.check_in, greatest(now(), t.check_in), 'camera', round(0.45 + random()::numeric * 0.25, 4)
  FROM enrollments e
  JOIN v_face_registration f ON f.student_id = e.student_id AND f.face_registered
  CROSS JOIN LATERAL (
    SELECT (current_date + v_start + make_interval(secs => random() * 1150))::timestamptz AS check_in
  ) t
  WHERE e.section_id = v_section AND e.status = 'active' AND random() < 0.75;

  RAISE NOTICE 'Demo session % in room 4302, % – %', v_session, v_start, v_end;
END $$;

COMMIT;

SELECT room_code, camera_status, course_code, section_code, start_time, end_time, checked_in, total_students
FROM v_room_status WHERE session_id IS NOT NULL;
