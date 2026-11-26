-- =====================================================================
-- Bangmod Smart Attendance (CP26MS3) — 03 (optional): a class running right now in room 4303
-- Adds a make-up session of INT201-001 in room 4303 (the room with the real camera) that started
-- 5 minutes ago: on time for 10 more minutes, late for 15 after that, ends in 3 hours.
-- Nobody is checked in yet, so the camera (or the lecturer) does it live.
-- Re-run any time to move it to the current time (today's attendance of INT201-001 is cleared).
-- If another course already uses room 4303 at that time today, nothing is created (notice only).
-- Run after 02_seed_mock.sql. Not for Production.
--   psql -U postgres -d bangmod_attendance -f 03_demo_live_session.sql
-- =====================================================================

BEGIN;
SET LOCAL timezone = 'Asia/Bangkok';

DO $$
DECLARE
  v_section  uuid := md5('section:INT201-001')::uuid;
  v_room     uuid := md5('room:4303')::uuid;
  v_start    time := date_trunc('minute', localtime - interval '5 minutes')::time;
  v_end      time;
  v_session  uuid;
  v_clash    text;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM sections WHERE section_id = v_section) THEN
    RAISE EXCEPTION 'mock data (02_seed_mock.sql) not found — this script is for local / uat only';
  END IF;
  IF localtime < time '00:10' OR localtime > time '20:50' THEN
    -- too close to midnight for a 3-hour class: use 09:00–12:00 today instead
    RAISE NOTICE 'Outside 00:10–20:50, the demo session uses 09:00–12:00 today';
    v_start := time '09:00';
  END IF;
  v_end := v_start + interval '3 hours';

  -- another course already has a class in room 4303 at that time today (02: INT202 Fri / INT203 Mon
  -- 09:00–12:00) → skip, do not fail: that class can be used for the camera test instead
  SELECT co.course_code || '-' || s.section_code || ' ' || cs.start_time || '–' || cs.end_time INTO v_clash
  FROM class_sessions cs
  JOIN sections s ON s.section_id = cs.section_id
  JOIN courses co ON co.course_id = s.course_id
  WHERE cs.room_id = v_room AND cs.session_date = current_date AND cs.section_id <> v_section
    AND cs.session_status <> 'cancelled' AND cs.start_time < v_end AND v_start < cs.end_time
  LIMIT 1;
  IF v_clash IS NOT NULL THEN
    RAISE NOTICE 'Room 4303 already has % today — no demo session created (use that class for the camera)', v_clash;
    RETURN;
  END IF;

  -- remove today's session of this section (scheduled or a previous demo), if any
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

  RAISE NOTICE 'Demo session % in room 4303, % – % (on time until %, late until %)',
    v_session, v_start, v_end, v_start + interval '15 minutes', v_start + interval '30 minutes';
END $$;

COMMIT;

SELECT room_code, camera_status, course_code, section_code, start_time, end_time, checked_in, total_students
FROM v_room_status WHERE session_id IS NOT NULL;
