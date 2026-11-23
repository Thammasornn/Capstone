-- =====================================================================
-- Checks the schema and the mock data after 01 + 02 (+ 03). Used by CI.
--   psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 -f test/assertions.sql
-- Any failed check raises an exception, so psql exits non-zero.
-- =====================================================================

DO $$
DECLARE
  n int;
BEGIN
  -- 20 tables, 4 views
  SELECT count(*) INTO n FROM information_schema.tables
   WHERE table_schema = 'public' AND table_type = 'BASE TABLE';
  IF n <> 20 THEN RAISE EXCEPTION 'expected 20 tables, found %', n; END IF;

  SELECT count(*) INTO n FROM information_schema.views WHERE table_schema = 'public';
  IF n <> 4 THEN RAISE EXCEPTION 'expected 4 views, found %', n; END IF;

  -- mock data volumes
  IF (SELECT count(*) FROM users)            <> 52  THEN RAISE EXCEPTION 'users';            END IF;
  IF (SELECT count(*) FROM student_profiles) <> 45  THEN RAISE EXCEPTION 'student_profiles'; END IF;
  IF (SELECT count(*) FROM sections)         <> 8   THEN RAISE EXCEPTION 'sections';         END IF;
  IF (SELECT count(*) FROM class_sessions                       -- excludes the 03 demo session
       WHERE session_date <= '2026-11-27' AND session_status <> 'in_progress') <> 150
                                                    THEN RAISE EXCEPTION 'class_sessions';   END IF;
  IF (SELECT count(*) FROM courses WHERE course_code !~ '^INT[0-9]{3}$') > 0
                                                    THEN RAISE EXCEPTION 'course codes';     END IF;

  -- every enrolled student has one row per completed session
  SELECT count(*) INTO n
  FROM class_sessions cs
  JOIN enrollments e ON e.section_id = cs.section_id
                    AND (e.status = 'active' OR cs.session_date < e.dropped_at::date)
  LEFT JOIN attendance_records ar ON ar.session_id = cs.session_id AND ar.student_id = e.student_id
  WHERE cs.session_status = 'completed' AND ar.attendance_id IS NULL;
  IF n > 0 THEN RAISE EXCEPTION '% completed session/student pairs have no attendance row', n; END IF;

  -- no attendance on cancelled or future sessions
  IF EXISTS (SELECT 1 FROM attendance_records ar JOIN class_sessions cs USING (session_id)
              WHERE cs.session_status IN ('cancelled', 'scheduled')) THEN
    RAISE EXCEPTION 'attendance found on cancelled/scheduled sessions';
  END IF;

  -- PDPA: no embeddings without a current 'granted' consent; the withdrawn student has none
  IF EXISTS (SELECT 1 FROM face_embeddings fe JOIN v_current_consent c USING (student_id)
              WHERE c.action <> 'granted') THEN
    RAISE EXCEPTION 'embeddings exist for a student whose consent is withdrawn';
  END IF;

  -- camera rows only for registered students in camera-enabled sections
  IF EXISTS (SELECT 1 FROM attendance_records ar
               JOIN class_sessions cs USING (session_id)
               JOIN sections s ON s.section_id = cs.section_id
              WHERE ar.source = 'camera' AND NOT s.camera_attendance_enabled) THEN
    RAISE EXCEPTION 'camera attendance in a section with the camera switched off';
  END IF;

  -- at-risk spread used by the front end demo
  IF (SELECT count(*) FROM v_student_section_attendance WHERE risk_level = 'high')   < 5 THEN
    RAISE EXCEPTION 'too few high-risk rows';
  END IF;
  IF (SELECT count(*) FROM v_student_section_attendance WHERE risk_level = 'medium') < 5 THEN
    RAISE EXCEPTION 'too few medium-risk rows';
  END IF;

  -- student test personas end up in the state their name promises (README "บัญชีทดสอบ")
  IF NOT EXISTS (SELECT 1 FROM v_face_registration
                  WHERE student_code = '69130599001' AND face_registered AND needs_reconsent) THEN
    RAISE EXCEPTION 'persona reconsent: expected registered + needs_reconsent';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM v_face_registration
                  WHERE student_code = '69130599002' AND NOT face_registered AND consent_status = 'granted') THEN
    RAISE EXCEPTION 'persona consentonly: expected granted consent without embeddings';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM student_profiles sp LEFT JOIN v_current_consent c USING (student_id)
                  WHERE sp.student_code = '69130599003' AND sp.is_adult = false AND c.student_id IS NULL) THEN
    RAISE EXCEPTION 'persona minor: expected is_adult = false and no consent';
  END IF;
  IF EXISTS (SELECT 1 FROM enrollments e JOIN student_profiles sp USING (student_id)
              WHERE sp.student_code = '66130599004') THEN
    RAISE EXCEPTION 'persona nocourse: expected no enrollments';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM users u JOIN student_profiles sp ON sp.student_id = u.user_id
                  WHERE sp.student_code = '66130599005' AND NOT u.is_active AND sp.academic_status = 'withdrawn') THEN
    RAISE EXCEPTION 'persona resigned: expected inactive account with academic_status withdrawn';
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

  -- consent_records is append-only
  BEGIN
    UPDATE consent_records SET action = action WHERE false;   -- statement-level no-op passes
    DELETE FROM consent_records WHERE consent_id = (SELECT consent_id FROM consent_records LIMIT 1);
    RAISE EXCEPTION 'consent_records accepted a DELETE';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE '%append-only%' THEN RAISE; END IF;
  END;

  RAISE NOTICE 'All database checks passed';
END $$;
