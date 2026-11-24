-- =====================================================================
-- prod: สร้างบัญชี admin คนแรก · รหัสผ่านผู้ใช้ตั้งเอง (ไม่อยู่ในไฟล์นี้ / git / shell history)
-- prod ไม่มีบัญชีทดสอบและหน้า Login ไม่มีรายชื่อให้กด (VITE_SHOW_TEST_ACCOUNTS=false) — ทุกคนพิมพ์อีเมล + รหัสผ่านเอง
--
-- รันบน VM (จาก root ของ repo) · แก้ 3 ค่าแรกก่อนรัน · รหัสผ่านพิมพ์ตอนรัน (ไม่แสดงบนจอ)
--
--   ADMIN_EMAIL='admin@sit.kmutt.ac.th'; ADMIN_FIRST='ชื่อ'; ADMIN_LAST='นามสกุล'
--   read -rsp 'รหัสผ่าน admin: ' PW; echo; read -rsp 'พิมพ์อีกครั้ง: ' PW2; echo
--   if [ "$PW" != "$PW2" ]; then echo 'รหัสผ่านไม่ตรงกัน'
--   elif case "$PW$ADMIN_EMAIL$ADMIN_FIRST$ADMIN_LAST" in *\'*|*\\*) true;; *) false;; esac; then echo "ห้ามมี ' หรือ \\"
--   else
--     { printf "\\set email '%s'\n\\set first_name '%s'\n\\set last_name '%s'\n\\set pw '%s'\n" \
--         "$ADMIN_EMAIL" "$ADMIN_FIRST" "$ADMIN_LAST" "$PW"; cat devops/k8s/sql/prod-first-admin.sql; } \
--       | kubectl -n prod exec -i statefulset/postgres -- psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1
--   fi; unset PW PW2
--
-- * ส่งรหัสผ่านทาง stdin (ไม่อยู่ใน argument ของคำสั่ง → ไม่โผล่ใน ps / history)
-- * หยุดโดยไม่แก้อะไรเมื่อ: มี admin อยู่แล้ว · อีเมลซ้ำ · รหัสผ่านสั้นกว่า 12 ตัว · ใช้ Password@123
-- * เก็บรหัสไว้ใน password manager ของทีม
-- * prod ยังต้องมี semesters และ consent_versions (is_current = true) ก่อนเปิดใช้ (CLAUDE.md ข้อ 17)
-- * psql ไม่แทนค่า :'ตัวแปร' ใน DO $$ … $$ จึงส่งค่าที่ต้องตรวจผ่าน set_config (มีผลใน transaction นี้เท่านั้น)
-- =====================================================================

BEGIN;

SELECT set_config('first_admin.email',   lower(:'email'), true),
       set_config('first_admin.pw_len',  length(:'pw')::text, true),
       set_config('first_admin.is_test', (:'pw' = 'Password@123')::text, true)
\gset _

DO $$
BEGIN
  IF current_setting('first_admin.pw_len')::int < 12 THEN
    RAISE EXCEPTION 'รหัสผ่านต้องยาวอย่างน้อย 12 ตัวอักษร';
  END IF;
  IF current_setting('first_admin.is_test')::boolean THEN
    RAISE EXCEPTION 'ห้ามใช้ Password@123 กับ prod (อยู่ใน git)';
  END IF;
  IF EXISTS (SELECT 1 FROM users WHERE role = 'admin') THEN
    RAISE EXCEPTION 'มี admin อยู่แล้ว — ไฟล์นี้ใช้สร้างคนแรกเท่านั้น';
  END IF;
  IF EXISTS (SELECT 1 FROM users WHERE email = current_setting('first_admin.email')) THEN
    RAISE EXCEPTION 'อีเมลนี้มีบัญชีอยู่แล้ว';
  END IF;
END $$;

INSERT INTO users (email, password_hash, first_name, last_name, role)
VALUES (lower(:'email'), crypt(:'pw', gen_salt('bf', 10)), :'first_name', :'last_name', 'admin')
RETURNING user_id, email, role, created_at;

COMMIT;
