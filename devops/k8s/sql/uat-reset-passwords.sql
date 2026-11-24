-- =====================================================================
-- uat เท่านั้น: ตั้งรหัสผ่านของ "ทุกบัญชี" ใน DB เป็น Password@123 (ค่าที่หน้า Login ของ uat กรอกให้)
-- รวมบัญชีที่เพิ่มเองผ่านหน้าเว็บ / แก้รหัสไปแล้ว · บัญชีที่เป็น Password@123 อยู่แล้วไม่แตะ · รันซ้ำได้
--
-- รันบน VM (จาก root ของ repo):
--   kubectl -n uat exec -i statefulset/postgres -- \
--     psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 < devops/k8s/sql/uat-reset-passwords.sql
--
-- * ไม่พบข้อมูล mock (02_seed_mock.sql) = หยุดทันที ไม่แก้อะไร — กันรันผิดกับ prod (รหัสนี้อยู่ใน git)
-- * ไม่ได้อยู่ใน backend/db/ เพราะ commit ที่แก้ backend/db/ จะทำให้ CD หยุด (CLAUDE.md ข้อ 25)
-- * token ที่ login ค้างไว้ยังใช้ได้จนหมดอายุ
-- =====================================================================

BEGIN;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM sections WHERE section_id = md5('section:INT201-001')::uuid) THEN
    RAISE EXCEPTION 'ไม่พบข้อมูล mock (02_seed_mock.sql) — สคริปต์นี้ใช้กับ uat / local เท่านั้น ห้ามรันกับ prod';
  END IF;
END $$;

-- บัญชีที่จะถูกเปลี่ยน (รหัสผ่านปัจจุบันไม่ใช่ Password@123)
CREATE TEMP TABLE reset_targets ON COMMIT DROP AS
SELECT user_id, email, role
FROM users
WHERE password_hash IS DISTINCT FROM crypt('Password@123', password_hash);

SELECT role, email FROM reset_targets ORDER BY role, email;

UPDATE users u
SET password_hash = crypt('Password@123', gen_salt('bf', 10)),
    updated_at    = now()
FROM reset_targets t
WHERE u.user_id = t.user_id;

SELECT (SELECT count(*) FROM reset_targets) AS changed,
       (SELECT count(*) FROM users)         AS total_accounts;

COMMIT;
