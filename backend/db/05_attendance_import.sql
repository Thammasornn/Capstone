-- =====================================================================
-- Bangmod Smart Attendance (CP26MS3) — 05: attendance import from a file (update an existing DB)
-- For a database created with 01_schema.sql BEFORE 2026-11-25 (uat, prod). A fresh 01 already has all of this.
--   psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 -f 05_attendance_import.sql
-- * new tables attendance_imports (1 row per import) + attendance_import_rows (every row read from the file)
-- * attendance_records: source may be 'import' · new column import_id (required when source = 'import')
-- * schema only — deletes / changes no data · re-runnable (IF NOT EXISTS / checks pg_constraint first)
-- * safe for Production, but run it on uat first; prod: run before the next prod deploy
-- =====================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS attendance_imports (
  import_id        uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id       uuid         NOT NULL REFERENCES class_sessions (session_id),
  mode             varchar(10)  NOT NULL CHECK (mode IN ('template', 'mapped')),
  method           varchar(15)  NOT NULL
                   CHECK (method IN ('template', 'teams', 'zoom', 'google_form', 'paper', 'other')),
  file_name        varchar(255) NOT NULL,
  file_sha256      char(64)     NOT NULL CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
  mapping          jsonb,
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
CREATE INDEX IF NOT EXISTS idx_attendance_imports_session ON attendance_imports (session_id, imported_at DESC);

CREATE TABLE IF NOT EXISTS attendance_import_rows (
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
  applied           boolean      NOT NULL DEFAULT false,
  PRIMARY KEY (import_id, row_no),
  CHECK ((action = 'unmatched') = (unmatched_reason IS NOT NULL)),
  CHECK (action = 'unmatched' OR student_id IS NOT NULL)
);

ALTER TABLE attendance_records
  ADD COLUMN IF NOT EXISTS import_id uuid REFERENCES attendance_imports (import_id);

-- The source CHECK in the old 01 has no explicit name: find it by its definition
-- (source = ANY (ARRAY[...])), drop it unless it already allows 'import', then add the named one
DO $$
DECLARE
  r     record;
  found boolean := false;
BEGIN
  FOR r IN
    SELECT conname, pg_get_constraintdef(oid) AS def
    FROM pg_constraint
    WHERE conrelid = 'attendance_records'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) ~ '\(source\)::text = ANY'
  LOOP
    IF r.conname = 'attendance_records_source_check' AND r.def LIKE '%''import''%' THEN
      found := true;
    ELSE
      EXECUTE format('ALTER TABLE attendance_records DROP CONSTRAINT %I', r.conname);
      RAISE NOTICE 'dropped %: %', r.conname, r.def;
    END IF;
  END LOOP;

  IF NOT found THEN
    ALTER TABLE attendance_records ADD CONSTRAINT attendance_records_source_check
      CHECK (source IN ('camera', 'manual', 'system', 'import'));
  END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conrelid = 'attendance_records'::regclass
                   AND conname = 'attendance_records_import_check') THEN
    ALTER TABLE attendance_records ADD CONSTRAINT attendance_records_import_check
      CHECK ((source = 'import') = (import_id IS NOT NULL));
  END IF;
END $$;

COMMIT;
