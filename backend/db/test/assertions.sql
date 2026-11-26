-- =====================================================================
-- Checks the schema and the (small) mock data after 01 + 02 (+ 03). Used by CI / ./dev.sh db.
--   psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 -f test/assertions.sql
-- Any failed check raises an exception, so psql exits non-zero.
-- =====================================================================

DO $$
DECLARE
  n int;
BEGIN
  -- 22 tables, 4 views
  SELECT count(*) INTO n FROM information_schema.tables
   WHERE table_schema = 'public' AND table_type = 'BASE TABLE';
  IF n <> 22 THEN RAISE EXCEPTION 'expected 22 tables, found %', n; END IF;

  SELECT count(*) INTO n FROM information_schema.views WHERE table_schema = 'public';
  IF n <> 4 THEN RAISE EXCEPTION 'expected 4 views, found %', n; END IF;

  -- per-session management columns on class_sessions (01 or 06)
  SELECT count(*) INTO n FROM information_schema.columns
   WHERE table_schema = 'public' AND table_name = 'class_sessions'
     AND column_name IN ('is_overridden', 'original_date', 'makeup_for_session_id', 'is_extra', 'cancel_reason');
  IF n <> 5 THEN RAISE EXCEPTION 'class_sessions: expected 5 session-management columns, found %', n; END IF;
  IF EXISTS (SELECT 1 FROM class_sessions WHERE is_extra AND NOT is_overridden) THEN
    RAISE EXCEPTION 'class_sessions: an extra session must be overridden';
  END IF;

  -- mock data volumes: 1 admin + 1 lecturer + 17 students · 5 courses / sections · camera only in 4303
  IF (SELECT count(*) FROM users WHERE role = 'admin')    <> 1  THEN RAISE EXCEPTION 'admins';           END IF;
  IF (SELECT count(*) FROM users WHERE role = 'lecturer') <> 1  THEN RAISE EXCEPTION 'lecturers';        END IF;
  IF (SELECT count(*) FROM student_profiles)              <> 17 THEN RAISE EXCEPTION 'student_profiles'; END IF;
  IF (SELECT count(*) FROM courses)                       <> 5  THEN RAISE EXCEPTION 'courses';          END IF;
  IF (SELECT count(*) FROM sections)                      <> 5  THEN RAISE EXCEPTION 'sections';         END IF;
  IF (SELECT count(*) FROM cameras)                       <> 1  THEN RAISE EXCEPTION 'cameras';          END IF;
  IF NOT EXISTS (SELECT 1 FROM cameras c JOIN rooms r USING (room_id)
                  WHERE r.room_code = '4303' AND c.stream_path = 'room-4303') THEN
    RAISE EXCEPTION 'camera must be in room 4303 with stream_path room-4303';
  END IF;
  IF (SELECT count(*) FROM student_profiles WHERE student_code IN ('66130500288', '66130500099')) <> 2 THEN
    RAISE EXCEPTION 'students 66130500288 and 66130500099 must exist';
  END IF;

  -- every course has 9–13 students and exactly 4 completed classes · future classes exist
  SELECT count(*) INTO n FROM sections s
   WHERE (SELECT count(*) FROM enrollments e WHERE e.section_id = s.section_id AND e.status = 'active')
         NOT BETWEEN 9 AND 13;
  IF n > 0 THEN RAISE EXCEPTION '% sections do not have 9–13 active students', n; END IF;
  SELECT count(*) INTO n FROM sections s
   WHERE (SELECT count(*) FROM class_sessions cs
           WHERE cs.section_id = s.section_id AND cs.session_status = 'completed') <> 4;
  IF n > 0 THEN RAISE EXCEPTION '% sections do not have exactly 4 completed sessions', n; END IF;
  IF NOT EXISTS (SELECT 1 FROM class_sessions WHERE session_status = 'scheduled' AND session_date > current_date) THEN
    RAISE EXCEPTION 'expected future scheduled sessions';
  END IF;

  -- at least two courses with different "on time" rules in the camera room (late demo)
  IF (SELECT count(DISTINCT s.on_time_minutes) FROM sections s JOIN rooms r USING (room_id)
       WHERE r.room_code = '4303') < 2 THEN
    RAISE EXCEPTION 'room 4303 needs courses with different on_time_minutes';
  END IF;

  -- no two classes in the same room overlap (timetable and individual sessions)
  IF EXISTS (SELECT 1 FROM sections a JOIN sections b
               ON a.section_id < b.section_id AND a.room_id = b.room_id AND a.semester_id = b.semester_id
              AND a.start_time < b.end_time AND b.start_time < a.end_time
             WHERE EXISTS (SELECT 1 FROM section_class_days x JOIN section_class_days y USING (day_of_week)
                            WHERE x.section_id = a.section_id AND y.section_id = b.section_id)) THEN
    RAISE EXCEPTION 'two sections share a room at the same time';
  END IF;
  IF EXISTS (SELECT 1 FROM class_sessions a JOIN class_sessions b
               ON a.session_id < b.session_id AND a.room_id = b.room_id AND a.session_date = b.session_date
              AND a.start_time < b.end_time AND b.start_time < a.end_time
             WHERE a.session_status <> 'cancelled' AND b.session_status <> 'cancelled') THEN
    RAISE EXCEPTION 'two sessions share a room at the same time';
  END IF;

  -- every enrolled student has one row per completed session (58 enrollments × 4)
  SELECT count(*) INTO n
  FROM class_sessions cs
  JOIN enrollments e ON e.section_id = cs.section_id AND e.status = 'active'
  LEFT JOIN attendance_records ar ON ar.session_id = cs.session_id AND ar.student_id = e.student_id
  WHERE cs.session_status = 'completed' AND ar.attendance_id IS NULL;
  IF n > 0 THEN RAISE EXCEPTION '% completed session/student pairs have no attendance row', n; END IF;
  IF (SELECT count(*) FROM attendance_records ar JOIN class_sessions cs USING (session_id)
       WHERE cs.session_status = 'completed') <> 232 THEN
    RAISE EXCEPTION 'expected 232 attendance rows on completed sessions';
  END IF;

  -- no attendance on cancelled or future sessions
  IF EXISTS (SELECT 1 FROM attendance_records ar JOIN class_sessions cs USING (session_id)
              WHERE cs.session_status IN ('cancelled', 'scheduled')) THEN
    RAISE EXCEPTION 'attendance found on cancelled/scheduled sessions';
  END IF;

  -- status follows the check-in time and the session's own rule
  IF EXISTS (SELECT 1 FROM attendance_records ar JOIN class_sessions cs USING (session_id)
              WHERE ar.status <> 'absent'
                AND ar.status <> CASE WHEN (ar.check_in_time AT TIME ZONE 'Asia/Bangkok')::time <= cs.on_time_until
                                      THEN 'on_time' ELSE 'late' END) THEN
    RAISE EXCEPTION 'an attendance status does not match its check-in time';
  END IF;
  -- late demo: 66130500288 at +15 min = late in INT202 (10 min), on time in INT203 (30 min)
  IF (SELECT string_agg(co.course_code || '=' || ar.status, ',' ORDER BY co.course_code)
        FROM attendance_records ar
        JOIN class_sessions cs USING (session_id)
        JOIN sections s ON s.section_id = cs.section_id
        JOIN courses co ON co.course_id = s.course_id
       WHERE ar.student_id = md5('student:66130500288')::uuid AND co.course_code IN ('INT202', 'INT203')
         AND cs.session_no = 4) IS DISTINCT FROM 'INT202=late,INT203=on_time' THEN
    RAISE EXCEPTION 'late demo rows (66130500288 in INT202 / INT203) are wrong';
  END IF;

  -- dashboard spread in INT201: 013 low · 036 low (1 absence) · 041 medium · 66139900007 high
  IF (SELECT string_agg(sp.student_code || '=' || v.risk_level, ',' ORDER BY sp.student_code)
        FROM v_student_section_attendance v JOIN student_profiles sp USING (student_id)
       WHERE v.section_id = md5('section:INT201-001')::uuid
         AND sp.student_code IN ('66130500013', '66130500036', '66130500041', '66139900007'))
     IS DISTINCT FROM '66130500013=low,66130500036=low,66130500041=medium,66139900007=high' THEN
    RAISE EXCEPTION 'INT201 risk levels are not the expected spread';
  END IF;

  -- PDPA: no embeddings without a current 'granted' consent
  IF EXISTS (SELECT 1 FROM face_embeddings fe LEFT JOIN v_current_consent c USING (student_id)
              WHERE c.action IS DISTINCT FROM 'granted') THEN
    RAISE EXCEPTION 'embeddings exist without a granted consent';
  END IF;

  -- camera rows only in camera-enabled sections
  IF EXISTS (SELECT 1 FROM attendance_records ar
               JOIN class_sessions cs USING (session_id)
               JOIN sections s ON s.section_id = cs.section_id
              WHERE ar.source = 'camera' AND NOT s.camera_attendance_enabled) THEN
    RAISE EXCEPTION 'camera attendance in a section with the camera switched off';
  END IF;

  -- project team accounts: active, not registered, in INT201-001
  IF (SELECT count(*) FROM student_profiles sp
        JOIN users u ON u.user_id = sp.student_id AND u.is_active
        JOIN v_face_registration fr ON fr.student_id = sp.student_id AND NOT fr.face_registered
        JOIN enrollments e ON e.student_id = sp.student_id AND e.status = 'active'
                          AND e.section_id = md5('section:INT201-001')::uuid
       WHERE sp.student_code IN ('66130500013', '66130500036', '66130500041')) <> 3 THEN
    RAISE EXCEPTION 'team accounts: expected 3 active, unregistered, enrolled in INT201-001';
  END IF;

  -- consent_records is append-only (insert one row in a sub-transaction, then try to delete it)
  BEGIN
    INSERT INTO consent_records (student_id, version, action, age_confirmed, ip_address)
    VALUES (md5('student:66130500013')::uuid, 'v1', 'granted', true, '127.0.0.1');
    DELETE FROM consent_records WHERE student_id = md5('student:66130500013')::uuid;
    RAISE EXCEPTION 'consent_records accepted a DELETE';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE '%append-only%' THEN RAISE; END IF;
  END;

  RAISE NOTICE 'All database checks passed';
END $$;
