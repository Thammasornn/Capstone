-- =====================================================================
-- Bangmod Smart Attendance (CP26MS3) — 01: schema
-- PostgreSQL 16+  ·  connect to bangmod_attendance first:
--   psql -U postgres -d bangmod_attendance -f 01_schema.sql
-- Source: "Database Design (Idea) — Bangmod Smart Attendance R1" (revised 2026-11-21)
--   Release 1 : 17 tables   ·   Release 2–3 : 3 tables (created empty-ready)
--   + attendance import (2026-11-25): 2 tables — DBs created before that: run 05_attendance_import.sql
--   + class session management (2026-11-25): columns on class_sessions — older DBs: run 06_class_session_management.sql
-- Conventions: UUID PKs · snake_case · enums = VARCHAR + CHECK (JPA @Enumerated(STRING))
-- =====================================================================

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid(), crypt(), gen_random_bytes()

-- ---------------------------------------------------------------------
-- Helper functions
-- ---------------------------------------------------------------------

-- Keeps updated_at current on every UPDATE
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END $$;

-- assert_user_role('<column>', '<role>'): the user referenced by <column> must have <role>
CREATE OR REPLACE FUNCTION assert_user_role() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_user_id uuid := (to_jsonb(NEW) ->> TG_ARGV[0])::uuid;
  v_role    varchar;
BEGIN
  SELECT role INTO v_role FROM users WHERE user_id = v_user_id;
  IF v_role IS DISTINCT FROM TG_ARGV[1] THEN
    RAISE EXCEPTION '%.%: user % must have role %, has %',
      TG_TABLE_NAME, TG_ARGV[0], v_user_id, TG_ARGV[1], coalesce(v_role, '(none)');
  END IF;
  RETURN NEW;
END $$;

-- =====================================================================
-- Group 1 — Users
-- =====================================================================

CREATE TABLE users (
  user_id        uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  email          varchar(255) NOT NULL UNIQUE CHECK (email = lower(email)),
  password_hash  varchar(255) NOT NULL,                     -- BCrypt ($2a$…)
  title          varchar(20),                               -- นาย, นางสาว, อ., ผศ.ดร. …
  first_name     varchar(100) NOT NULL,
  last_name      varchar(100) NOT NULL,
  role           varchar(10)  NOT NULL CHECK (role IN ('student', 'lecturer', 'admin')),
  is_active      boolean      NOT NULL DEFAULT true,
  last_login_at  timestamptz,
  created_at     timestamptz  NOT NULL DEFAULT now(),
  updated_at     timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_users_role ON users (role);

CREATE TABLE student_profiles (
  student_id         uuid         PRIMARY KEY REFERENCES users (user_id),   -- = studentId in the API
  student_code       varchar(11)  NOT NULL UNIQUE CHECK (student_code ~ '^[0-9]{11}$'),
  faculty            varchar(150),
  program            varchar(50),
  entry_year         smallint     CHECK (entry_year BETWEEN 2500 AND 3000),  -- พ.ศ.
  is_adult           boolean,                                -- B9 — waits for the DPO; no birth date stored
  academic_status    varchar(10)  NOT NULL DEFAULT 'active'
                     CHECK (academic_status IN ('active', 'graduated', 'withdrawn')),
  status_changed_at  timestamptz,
  created_at         timestamptz  NOT NULL DEFAULT now(),
  updated_at         timestamptz  NOT NULL DEFAULT now()
);

-- =====================================================================
-- Group 2 — Rooms and cameras
-- =====================================================================

CREATE TABLE rooms (
  room_id     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  room_code   varchar(20) NOT NULL UNIQUE,
  building    varchar(50),
  capacity    smallint    CHECK (capacity > 0),
  is_active   boolean     NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE cameras (
  camera_id     uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  room_id       uuid         NOT NULL UNIQUE REFERENCES rooms (room_id),  -- 1 camera per room (scope)
  camera_code   varchar(30)  NOT NULL UNIQUE,
  stream_path   varchar(100) NOT NULL,      -- MediaMTX path; RTSP credentials live in a K3s Secret
  status        varchar(10)  NOT NULL DEFAULT 'no_signal' CHECK (status IN ('live', 'no_signal')),
  last_seen_at  timestamptz,                -- heartbeat from the AI Service
  is_active     boolean      NOT NULL DEFAULT true,
  created_at    timestamptz  NOT NULL DEFAULT now(),
  updated_at    timestamptz  NOT NULL DEFAULT now()
);

-- =====================================================================
-- Group 3 — Courses and timetable
-- =====================================================================

CREATE TABLE semesters (
  semester_id    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  academic_year  smallint    NOT NULL CHECK (academic_year BETWEEN 2500 AND 3000),
  term           smallint    NOT NULL CHECK (term BETWEEN 1 AND 3),          -- 3 = summer
  start_date     date        NOT NULL,
  end_date       date        NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (academic_year, term),
  CHECK (end_date > start_date)
);

CREATE TABLE courses (
  course_id    uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  course_code  varchar(10)  NOT NULL CHECK (course_code ~ '^[A-Z]{3}[0-9]{3}$'),  -- e.g. INT101
  course_name  varchar(200) NOT NULL,
  created_by   uuid         REFERENCES users (user_id),
  deleted_at   timestamptz,                                -- soft delete (Q5)
  created_at   timestamptz  NOT NULL DEFAULT now(),
  updated_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_courses_code_active ON courses (course_code) WHERE deleted_at IS NULL;

CREATE TABLE sections (
  section_id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  course_id                  uuid        NOT NULL REFERENCES courses (course_id),
  semester_id                uuid        NOT NULL REFERENCES semesters (semester_id),
  section_code               varchar(10) NOT NULL,
  room_id                    uuid        NOT NULL REFERENCES rooms (room_id),
  start_time                 time        NOT NULL,
  end_time                   time        NOT NULL,
  check_in_opens             time        NOT NULL,   -- camera works only inside this window (D3)
  check_in_closes            time        NOT NULL,
  on_time_minutes            smallint    NOT NULL DEFAULT 15 CHECK (on_time_minutes >= 0),  -- D4
  late_until                 time,                   -- R2 rule; NULL = late until check_in_closes
  camera_attendance_enabled  boolean     NOT NULL DEFAULT true,                              -- B6
  deleted_at                 timestamptz,
  created_at                 timestamptz NOT NULL DEFAULT now(),
  updated_at                 timestamptz NOT NULL DEFAULT now(),
  UNIQUE (course_id, semester_id, section_code),
  CHECK (end_time > start_time),
  CHECK (start_time <= check_in_opens AND check_in_opens < check_in_closes AND check_in_closes <= end_time),
  CHECK (check_in_opens + make_interval(mins => on_time_minutes) <= coalesce(late_until, check_in_closes)),
  CHECK (late_until IS NULL OR (late_until > check_in_opens AND late_until <= check_in_closes))
);
CREATE INDEX idx_sections_semester ON sections (semester_id);
CREATE INDEX idx_sections_room     ON sections (room_id);

CREATE TABLE section_class_days (
  section_id   uuid     NOT NULL REFERENCES sections (section_id) ON DELETE CASCADE,
  day_of_week  smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),   -- ISO: 1 = Monday
  PRIMARY KEY (section_id, day_of_week)
);

CREATE TABLE section_lecturers (
  section_id   uuid        NOT NULL REFERENCES sections (section_id) ON DELETE CASCADE,
  lecturer_id  uuid        NOT NULL REFERENCES users (user_id),
  is_primary   boolean     NOT NULL DEFAULT true,
  created_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (section_id, lecturer_id)
);
CREATE INDEX idx_section_lecturers_lecturer ON section_lecturers (lecturer_id);

CREATE TABLE enrollments (
  section_id   uuid        NOT NULL REFERENCES sections (section_id),
  student_id   uuid        NOT NULL REFERENCES student_profiles (student_id),
  status       varchar(10) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'dropped')),
  enrolled_at  timestamptz NOT NULL DEFAULT now(),
  dropped_at   timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (section_id, student_id),
  CHECK ((status = 'dropped') = (dropped_at IS NOT NULL))
);
CREATE INDEX idx_enrollments_student ON enrollments (student_id);

CREATE TABLE class_sessions (
  session_id       uuid        PRIMARY KEY DEFAULT gen_random_uuid(),   -- = sessionId in the API
  section_id       uuid        NOT NULL REFERENCES sections (section_id),
  session_no       smallint    NOT NULL CHECK (session_no > 0),
  session_date     date        NOT NULL,
  room_id          uuid        NOT NULL REFERENCES rooms (room_id),     -- snapshot; may move for one session
  start_time       time        NOT NULL,                                 -- snapshots of the section (D2)
  end_time         time        NOT NULL,
  check_in_opens   time        NOT NULL,
  check_in_closes  time        NOT NULL,
  on_time_until    time        NOT NULL,
  late_until       time        NOT NULL,
  session_status   varchar(15) NOT NULL DEFAULT 'scheduled'
                   CHECK (session_status IN ('scheduled', 'in_progress', 'completed', 'cancelled')),  -- Q8
  -- per-session management: sections = template, class_sessions = the real classes
  is_overridden          boolean     NOT NULL DEFAULT false,   -- edited per session → regenerateSessions leaves it alone
  original_date          date,                                 -- date in the template, set the first time it is moved
  makeup_for_session_id  uuid        REFERENCES class_sessions (session_id),   -- make-up class for that session
  is_extra               boolean     NOT NULL DEFAULT false,   -- added by hand (make-up / extra), not from the template
  cancel_reason          text,                                 -- NULL allowed (rows cancelled by deleting a course)
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (section_id, session_date),
  UNIQUE (section_id, session_no),
  CHECK (end_time > start_time),
  CHECK (check_in_opens <= on_time_until AND on_time_until <= late_until AND late_until <= check_in_closes),
  CONSTRAINT class_sessions_makeup_not_self_check CHECK (makeup_for_session_id <> session_id),
  CONSTRAINT class_sessions_extra_overridden_check CHECK (NOT is_extra OR is_overridden)
);
CREATE INDEX idx_class_sessions_room_date ON class_sessions (room_id, session_date);   -- B1, B4
CREATE INDEX idx_class_sessions_original_date ON class_sessions (section_id, original_date)
  WHERE original_date IS NOT NULL;                                                      -- regenerateSessions

-- =====================================================================
-- Group 4 — Attendance
-- =====================================================================

-- One row per file import into one session (onsite classes only) · the file itself is NOT stored
CREATE TABLE attendance_imports (
  import_id        uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id       uuid         NOT NULL REFERENCES class_sessions (session_id),
  mode             varchar(10)  NOT NULL CHECK (mode IN ('template', 'mapped')),
  method           varchar(15)  NOT NULL
                   CHECK (method IN ('template', 'teams', 'zoom', 'google_form', 'paper', 'other')),
  file_name        varchar(255) NOT NULL,                        -- name only, the file is not kept
  file_sha256      char(64)     NOT NULL CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),   -- same file uploaded again?
  mapping          jsonb,                                        -- columns / timeFormat / statusValues / defaultStatus
  reason           text         NOT NULL,
  override_camera  boolean      NOT NULL DEFAULT false,
  override_manual  boolean      NOT NULL DEFAULT false,
  total_rows       smallint     NOT NULL CHECK (total_rows BETWEEN 1 AND 500),
  inserted_count   smallint     NOT NULL CHECK (inserted_count >= 0),
  replaced_count   smallint     NOT NULL CHECK (replaced_count >= 0),
  skipped_count    smallint     NOT NULL CHECK (skipped_count >= 0),
  conflict_count   smallint     NOT NULL CHECK (conflict_count >= 0),
  unmatched_count  smallint     NOT NULL CHECK (unmatched_count >= 0),
  imported_by      uuid         NOT NULL REFERENCES users (user_id),
  imported_at      timestamptz  NOT NULL DEFAULT now(),
  CHECK ((mode = 'template') = (method = 'template')),
  CHECK ((mode = 'mapped') = (mapping IS NOT NULL))
);
CREATE INDEX idx_attendance_imports_session ON attendance_imports (session_id, imported_at DESC);

-- Every row read from the imported file (raw values cut to 255 chars) and what was done with it
-- PDPA: raw_identifier of an unmatched row may be an outsider's e-mail — staff only
CREATE TABLE attendance_import_rows (
  import_id         uuid         NOT NULL REFERENCES attendance_imports (import_id) ON DELETE CASCADE,
  row_no            smallint     NOT NULL CHECK (row_no > 0),
  raw_identifier    varchar(255),
  raw_join_time     varchar(255),
  raw_status        varchar(255),
  student_id        uuid         REFERENCES student_profiles (student_id),
  status            varchar(10)  CHECK (status IN ('on_time', 'late', 'absent')),
  check_in_time     timestamptz,
  action            varchar(10)  NOT NULL
                    CHECK (action IN ('insert', 'replace', 'skip', 'conflict', 'unmatched')),
  unmatched_reason  varchar(15)  CHECK (unmatched_reason IN ('not_found', 'not_enrolled', 'duplicate')),
  applied           boolean      NOT NULL DEFAULT false,         -- true = written to attendance_records
  PRIMARY KEY (import_id, row_no),
  CHECK ((action = 'unmatched') = (unmatched_reason IS NOT NULL)),
  CHECK (action = 'unmatched' OR student_id IS NOT NULL)
);

CREATE TABLE attendance_records (
  attendance_id  uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id     uuid         NOT NULL REFERENCES class_sessions (session_id),
  student_id     uuid         NOT NULL REFERENCES student_profiles (student_id),
  status         varchar(10)  NOT NULL CHECK (status IN ('on_time', 'late', 'absent')),
  check_in_time  timestamptz,                -- first time the camera (or lecturer) saw the student
  last_seen_at   timestamptz,                -- last sighting: check-out / stayed until the end
  source         varchar(10)  NOT NULL
                 CONSTRAINT attendance_records_source_check
                 CHECK (source IN ('camera', 'manual', 'system', 'import')),
  match_score    numeric(5,4) CHECK (match_score BETWEEN -1 AND 1),   -- SFace cosine similarity
  recorded_by    uuid         REFERENCES users (user_id),            -- required when source = manual (B8)
  reason         text,
  import_id      uuid         REFERENCES attendance_imports (import_id),   -- source = import (who / why: the import)
  created_at     timestamptz  NOT NULL DEFAULT now(),
  updated_at     timestamptz  NOT NULL DEFAULT now(),
  UNIQUE (session_id, student_id),
  CONSTRAINT attendance_records_import_check CHECK ((source = 'import') = (import_id IS NOT NULL)),
  CHECK ((source = 'manual') = (recorded_by IS NOT NULL)),
  CHECK (source <> 'manual' OR reason IS NOT NULL),
  CHECK ((status = 'absent') = (check_in_time IS NULL)),
  CHECK (source <> 'camera' OR match_score IS NOT NULL),
  CHECK (source <> 'system' OR status = 'absent'),
  CHECK (last_seen_at IS NULL OR last_seen_at >= check_in_time)
);
CREATE INDEX idx_attendance_student ON attendance_records (student_id);

-- =====================================================================
-- Group 5 — Faces and PDPA
-- =====================================================================

CREATE TABLE consent_versions (
  version          varchar(10) PRIMARY KEY,                          -- v1
  content_sha256   char(64)    NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
  effective_from   timestamptz NOT NULL,
  is_current       boolean     NOT NULL DEFAULT false,
  created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_consent_versions_current ON consent_versions (is_current) WHERE is_current;

-- Append-only evidence of consent given / withdrawn (D5)
CREATE TABLE consent_records (
  consent_id     uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  student_id     uuid         NOT NULL REFERENCES student_profiles (student_id),
  version        varchar(10)  NOT NULL REFERENCES consent_versions (version),
  action         varchar(10)  NOT NULL CHECK (action IN ('granted', 'withdrawn')),
  age_confirmed  boolean      NOT NULL,      -- checkbox 2 on the consent screen
  ip_address     inet         NOT NULL,
  user_agent     varchar(255),
  created_at     timestamptz  NOT NULL DEFAULT now(),
  UNIQUE (consent_id, student_id)            -- target of the composite FK below
);
CREATE INDEX idx_consent_records_student ON consent_records (student_id, created_at DESC);

CREATE OR REPLACE FUNCTION forbid_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$;

CREATE TRIGGER trg_consent_records_append_only
  BEFORE UPDATE OR DELETE ON consent_records
  FOR EACH ROW EXECUTE FUNCTION forbid_change();

-- Face vectors only — no photo column. Ciphertext = 12-byte nonce + 512-byte AES-GCM data + 16-byte tag
CREATE TABLE face_embeddings (
  embedding_id   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  student_id     uuid        NOT NULL,
  consent_id     uuid        NOT NULL,
  embedding      bytea       NOT NULL CHECK (octet_length(embedding) BETWEEN 512 AND 1024),
  model_name     varchar(30) NOT NULL DEFAULT 'sface',
  model_version  varchar(30) NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (student_id) REFERENCES student_profiles (student_id) ON DELETE CASCADE,
  FOREIGN KEY (consent_id, student_id) REFERENCES consent_records (consent_id, student_id)
);
CREATE INDEX idx_face_embeddings_student ON face_embeddings (student_id);

-- An embedding needs a 'granted' consent row, and a student keeps at most 5 vectors
CREATE OR REPLACE FUNCTION check_face_embedding() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF (SELECT action FROM consent_records WHERE consent_id = NEW.consent_id) <> 'granted' THEN
    RAISE EXCEPTION 'face_embeddings: consent % is not a granted consent', NEW.consent_id;
  END IF;
  IF (SELECT count(*) FROM face_embeddings WHERE student_id = NEW.student_id) >= 5 THEN
    RAISE EXCEPTION 'face_embeddings: student % already has 5 embeddings', NEW.student_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER trg_face_embeddings_check
  BEFORE INSERT ON face_embeddings
  FOR EACH ROW EXECUTE FUNCTION check_face_embedding();

CREATE TABLE data_deletion_jobs (
  job_id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  student_id     uuid        NOT NULL REFERENCES student_profiles (student_id),
  reason         varchar(15) NOT NULL CHECK (reason IN ('withdrawn', 'graduated', 'system_end')),
  requested_at   timestamptz NOT NULL DEFAULT now(),
  due_at         timestamptz NOT NULL,                -- requested_at + 30 days (waits for the DPO)
  completed_at   timestamptz,
  deleted_count  smallint    CHECK (deleted_count >= 0),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CHECK (due_at >= requested_at),
  CHECK (completed_at IS NULL OR completed_at >= requested_at)
);
CREATE INDEX idx_data_deletion_jobs_pending ON data_deletion_jobs (due_at) WHERE completed_at IS NULL;

-- =====================================================================
-- Group 6 — System
-- =====================================================================

CREATE TABLE audit_logs (
  log_id       uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  actor_id     uuid        REFERENCES users (user_id),     -- NULL = system
  action       varchar(50) NOT NULL,                       -- attendance.manual_set, section.camera_toggle …
  entity_type  varchar(30) NOT NULL,
  entity_id    uuid,                                       -- polymorphic, no FK
  before_data  jsonb,                                      -- never put embeddings here
  after_data   jsonb,
  ip_address   inet,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_logs_entity  ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_logs_created ON audit_logs (created_at);

-- =====================================================================
-- Release 2–3 tables (built on Release 1 keys)
-- =====================================================================

-- R2 · "Follow up" button on the At-Risk page — tied to an enrollment
CREATE TABLE follow_ups (
  follow_up_id  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  section_id    uuid        NOT NULL,
  student_id    uuid        NOT NULL,
  created_by    uuid        NOT NULL REFERENCES users (user_id),
  note          text        NOT NULL,
  status        varchar(10) NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
  closed_at     timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (section_id, student_id) REFERENCES enrollments (section_id, student_id),
  CHECK ((status = 'closed') = (closed_at IS NOT NULL))
);
CREATE INDEX idx_follow_ups_student ON follow_ups (student_id);

-- R2 · Discrepancy check — face counts sampled during a session (R1 only streams them over SSE)
CREATE TABLE occupancy_snapshots (
  snapshot_id       bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,   -- high-volume log
  session_id        uuid        NOT NULL REFERENCES class_sessions (session_id),
  camera_id         uuid        NOT NULL REFERENCES cameras (camera_id),
  captured_at       timestamptz NOT NULL,
  faces_detected    smallint    NOT NULL CHECK (faces_detected >= 0),
  faces_recognized  smallint    NOT NULL CHECK (faces_recognized >= 0),
  unknown_faces     smallint    NOT NULL CHECK (unknown_faces >= 0),
  CHECK (faces_recognized + unknown_faces = faces_detected)
);
CREATE INDEX idx_occupancy_session ON occupancy_snapshots (session_id, captured_at);

-- R3 · Notifications (face-registration reminder, class reminder, cancelled / rescheduled class …)
CREATE TABLE notifications (
  notification_id  uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          uuid         NOT NULL REFERENCES users (user_id),
  type             varchar(30)  NOT NULL CHECK (type IN (
                     'face_registration_reminder', 'class_reminder', 'class_cancelled',
                     'class_rescheduled', 'class_mode_changed', 'late_alert')),
  title            varchar(200) NOT NULL,
  body             text,
  session_id       uuid         REFERENCES class_sessions (session_id),
  read_at          timestamptz,
  created_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user_unread ON notifications (user_id, created_at DESC) WHERE read_at IS NULL;

-- =====================================================================
-- Triggers
-- =====================================================================

CREATE TRIGGER trg_student_profiles_role  BEFORE INSERT OR UPDATE ON student_profiles
  FOR EACH ROW EXECUTE FUNCTION assert_user_role('student_id', 'student');
CREATE TRIGGER trg_section_lecturers_role BEFORE INSERT OR UPDATE ON section_lecturers
  FOR EACH ROW EXECUTE FUNCTION assert_user_role('lecturer_id', 'lecturer');

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['users', 'student_profiles', 'rooms', 'cameras', 'semesters', 'courses',
                           'sections', 'enrollments', 'class_sessions', 'attendance_records',
                           'data_deletion_jobs', 'follow_ups']
  LOOP
    EXECUTE format('CREATE TRIGGER trg_%1$s_updated_at BEFORE UPDATE ON %1$I
                    FOR EACH ROW EXECUTE FUNCTION set_updated_at()', t);
  END LOOP;
END $$;

-- =====================================================================
-- Views (read models for the API — computed, never stored)
-- =====================================================================

-- Latest consent row per student
CREATE VIEW v_current_consent AS
SELECT DISTINCT ON (cr.student_id)
       cr.student_id, cr.consent_id, cr.version, cr.action, cr.created_at AS action_at
FROM consent_records cr
ORDER BY cr.student_id, cr.created_at DESC;

-- GET /students/{id}/face/status  ·  needsReconsent (D6)
CREATE VIEW v_face_registration AS
SELECT sp.student_id,
       sp.student_code,
       count(fe.embedding_id) > 0                       AS face_registered,
       count(fe.embedding_id)::int                      AS embedding_count,
       cc.action                                        AS consent_status,
       cc.version                                       AS consent_version,
       cc.action_at                                     AS consent_changed_at,
       (cc.action = 'granted' AND cc.version <> cv.version) AS needs_reconsent
FROM student_profiles sp
LEFT JOIN face_embeddings  fe ON fe.student_id = sp.student_id
LEFT JOIN v_current_consent cc ON cc.student_id = sp.student_id
CROSS JOIN (SELECT version FROM consent_versions WHERE is_current) cv
GROUP BY sp.student_id, sp.student_code, cc.action, cc.version, cc.action_at, cv.version;

-- API 5.6 per student per section · attendanceRate counts late as present ·
-- risk thresholds mirror the front end (src/entities/attendance/risk.js): 0–1 low, 2 medium, 3+ high
CREATE VIEW v_student_section_attendance AS
SELECT e.section_id,
       e.student_id,
       count(cs.session_id)                                           AS total_sessions,
       count(*) FILTER (WHERE ar.status = 'on_time')                  AS on_time,
       count(*) FILTER (WHERE ar.status = 'late')                     AS late,
       count(*) FILTER (WHERE ar.status = 'absent')                   AS absent,
       CASE WHEN count(cs.session_id) = 0 THEN NULL
            ELSE round(100.0 * count(*) FILTER (WHERE ar.status IN ('on_time', 'late'))
                       / count(cs.session_id), 2) END                 AS attendance_rate,
       CASE WHEN count(*) FILTER (WHERE ar.status = 'absent') >= 3 THEN 'high'
            WHEN count(*) FILTER (WHERE ar.status = 'absent') = 2  THEN 'medium'
            ELSE 'low' END                                            AS risk_level
FROM enrollments e
LEFT JOIN class_sessions cs
       ON cs.section_id = e.section_id AND cs.session_status = 'completed'
LEFT JOIN attendance_records ar
       ON ar.session_id = cs.session_id AND ar.student_id = e.student_id
WHERE e.status = 'active'
GROUP BY e.section_id, e.student_id;

-- B1 · Room Selection: camera status + the session running right now (if any)
CREATE VIEW v_room_status AS
SELECT r.room_id, r.room_code, c.camera_code, c.status AS camera_status, c.last_seen_at,
       cs.session_id, co.course_code, co.course_name, s.section_code,
       cs.start_time, cs.end_time,
       (SELECT count(*) FROM attendance_records ar
         WHERE ar.session_id = cs.session_id AND ar.status IN ('on_time', 'late'))       AS checked_in,
       (SELECT count(*) FROM enrollments e
         WHERE e.section_id = cs.section_id AND e.status = 'active')                    AS total_students
FROM rooms r
LEFT JOIN cameras c ON c.room_id = r.room_id
LEFT JOIN class_sessions cs
       ON cs.room_id = r.room_id
      AND cs.session_date = current_date
      AND localtime BETWEEN cs.start_time AND cs.end_time
      AND cs.session_status <> 'cancelled'
LEFT JOIN sections s  ON s.section_id = cs.section_id
LEFT JOIN courses  co ON co.course_id = s.course_id
WHERE r.is_active;

COMMIT;
