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

  -- mock data volumes: 1 admin + 1 lecturer + 3 team students · 1 course / section · camera only in 4303
  IF (SELECT count(*) FROM users WHERE role = 'admin')    <> 1 THEN RAISE EXCEPTION 'admins';           END IF;
  IF (SELECT count(*) FROM users WHERE role = 'lecturer') <> 1 THEN RAISE EXCEPTION 'lecturers';        END IF;
  IF (SELECT count(*) FROM student_profiles)              <> 3 THEN RAISE EXCEPTION 'student_profiles'; END IF;
  IF (SELECT count(*) FROM sections)                      <> 1 THEN RAISE EXCEPTION 'sections';         END IF;
  IF (SELECT count(*) FROM cameras)                       <> 1 THEN RAISE EXCEPTION 'cameras';          END IF;
  IF NOT EXISTS (SELECT 1 FROM cameras c JOIN rooms r USING (room_id)
                  WHERE r.room_code = '4303' AND c.stream_path = 'room-4303') THEN
    RAISE EXCEPTION 'camera must be in room 4303 with stream_path room-4303';
  END IF;
  IF (SELECT count(*) FROM class_sessions WHERE session_status = 'completed') <> 4 THEN
    RAISE EXCEPTION 'expected 4 completed sessions';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM class_sessions WHERE session_status = 'scheduled' AND session_date > current_date) THEN
    RAISE EXCEPTION 'expected future scheduled sessions';
  END IF;
  IF EXISTS (SELECT 1 FROM class_sessions cs JOIN rooms r USING (room_id) WHERE r.room_code <> '4303') THEN
    RAISE EXCEPTION 'every session must be in room 4303 (the camera room)';
  END IF;

  -- every enrolled student has one row per completed session (3 × 4)
  SELECT count(*) INTO n
  FROM class_sessions cs
  JOIN enrollments e ON e.section_id = cs.section_id AND e.status = 'active'
  LEFT JOIN attendance_records ar ON ar.session_id = cs.session_id AND ar.student_id = e.student_id
  WHERE cs.session_status = 'completed' AND ar.attendance_id IS NULL;
  IF n > 0 THEN RAISE EXCEPTION '% completed session/student pairs have no attendance row', n; END IF;
  IF (SELECT count(*) FROM attendance_records ar JOIN class_sessions cs USING (session_id)
       WHERE cs.session_status = 'completed') <> 12 THEN
    RAISE EXCEPTION 'expected 12 attendance rows on completed sessions';
  END IF;

  -- no attendance on cancelled or future sessions
  IF EXISTS (SELECT 1 FROM attendance_records ar JOIN class_sessions cs USING (session_id)
              WHERE cs.session_status IN ('cancelled', 'scheduled')) THEN
    RAISE EXCEPTION 'attendance found on cancelled/scheduled sessions';
  END IF;

  -- dashboard spread: one medium-risk student (2 absences), the rest low
  IF (SELECT count(*) FROM v_student_section_attendance WHERE risk_level = 'medium') <> 1 THEN
    RAISE EXCEPTION 'expected exactly 1 medium-risk row';
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
