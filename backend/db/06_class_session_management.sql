-- =====================================================================
-- Bangmod Smart Attendance (CP26MS3) — 06: per-session management "คาบเรียน" (update an existing DB)
-- For a database created with 01_schema.sql BEFORE this change (uat, prod). A fresh 01 already has all of this.
-- Run AFTER 05_attendance_import.sql:
--   psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 -f 06_class_session_management.sql
-- * class_sessions: is_overridden, original_date, makeup_for_session_id, is_extra, cancel_reason
--   + CHECKs (makeup not itself · extra ⇒ overridden) + index (section_id, original_date)
-- * schema only — deletes / changes no data (the defaults fit every existing row) · re-runnable
-- * safe for Production, but run it on uat first; prod: run before the next prod deploy
-- =====================================================================

BEGIN;

ALTER TABLE class_sessions
  ADD COLUMN IF NOT EXISTS is_overridden         boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS original_date         date,
  ADD COLUMN IF NOT EXISTS makeup_for_session_id uuid REFERENCES class_sessions (session_id),
  ADD COLUMN IF NOT EXISTS is_extra              boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS cancel_reason         text;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conrelid = 'class_sessions'::regclass
                   AND conname = 'class_sessions_makeup_not_self_check') THEN
    ALTER TABLE class_sessions ADD CONSTRAINT class_sessions_makeup_not_self_check
      CHECK (makeup_for_session_id <> session_id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conrelid = 'class_sessions'::regclass
                   AND conname = 'class_sessions_extra_overridden_check') THEN
    ALTER TABLE class_sessions ADD CONSTRAINT class_sessions_extra_overridden_check
      CHECK (NOT is_extra OR is_overridden);
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_class_sessions_original_date ON class_sessions (section_id, original_date)
  WHERE original_date IS NOT NULL;

COMMIT;
