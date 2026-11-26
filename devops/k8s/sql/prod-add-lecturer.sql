-- =====================================================================
-- prod: เพิ่มบัญชีอาจารย์ 1 คน · รหัสผ่านผู้ใช้ตั้งเอง (ไม่อยู่ในไฟล์นี้ / git / shell history)
-- ⚠️ ข้อมูลบน prod ของโครงงานนี้ต้องเป็น dummy เท่านั้น (PDPA · ตามอาจารย์ที่ปรึกษา) — ห้ามใช้ชื่อ / อีเมลคนจริง
--
-- รันบน VM หลัง prod-first-setup.sql · แก้ 4 ค่าแรกก่อนรัน · รหัสผ่านพิมพ์ตอนรัน (ไม่แสดงบนจอ)
--
--   EMAIL='lecturer.demo@sit.kmutt.ac.th'; TITLE='อ.'; FIRST='ชื่อ'; LAST='นามสกุล'
--   read -rsp 'รหัสผ่านอาจารย์: ' PW; echo; read -rsp 'พิมพ์อีกครั้ง: ' PW2; echo
--   if [ "$PW" != "$PW2" ]; then echo 'รหัสผ่านไม่ตรงกัน'
--   elif case "$PW$EMAIL$TITLE$FIRST$LAST" in *\'*|*\\*) true;; *) false;; esac; then echo "ห้ามมี ' หรือ \\"
--   else
--     { printf "\\set email '%s'\n\\set title '%s'\n\\set first_name '%s'\n\\set last_name '%s'\n\\set pw '%s'\n" \
--         "$EMAIL" "$TITLE" "$FIRST" "$LAST" "$PW"; git -C ~/bangmod-cd/repo show origin/main:devops/k8s/sql/prod-add-lecturer.sql; } \
--       | kubectl -n prod exec -i statefulset/postgres -- psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1
--   fi; unset PW PW2
--
-- * รหัสผ่านส่งทาง stdin (ไม่อยู่ใน argument → ไม่โผล่ใน ps / history) · BCrypt cost 10 แบบเดียวกับ backend
-- * รันซ้ำได้: อีเมลนี้เป็นอาจารย์อยู่แล้ว = ไม่แก้อะไร (รหัสผ่านที่พิมพ์รอบนี้ไม่ถูกใช้) · เป็น role อื่น = error
-- * หยุดเมื่อ: รหัสผ่านสั้นกว่า 12 ตัว · ใช้ Password@123 · DB มีข้อมูล mock (uat) · ไม่มี schema
-- * TITLE ว่างได้ ('') · ยังไม่มีหน้าเปลี่ยนรหัสผ่าน — เก็บรหัสไว้ใน password manager ของทีม
-- =====================================================================

BEGIN;

SELECT set_config('add_lecturer.email',   lower(:'email'), true),
       set_config('add_lecturer.first',   :'first_name', true),
       set_config('add_lecturer.last',    :'last_name', true),
       set_config('add_lecturer.pw_len',  length(:'pw')::text, true),
       set_config('add_lecturer.is_test', (:'pw' = 'Password@123')::text, true)
\gset _

DO $$
DECLARE v_role text;
BEGIN
  IF to_regclass('public.users') IS NULL THEN RAISE EXCEPTION 'ยังไม่มี schema — รัน init-db.sh prod ก่อน'; END IF;
  IF EXISTS (SELECT 1 FROM sections WHERE section_id = md5('section:INT201-001')::uuid) THEN
    RAISE EXCEPTION 'DB นี้มีข้อมูล mock (uat) — ไฟล์นี้สำหรับ prod เท่านั้น ไม่ได้แก้อะไร';
  END IF;
  IF current_setting('add_lecturer.email') !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' THEN
    RAISE EXCEPTION 'อีเมลไม่ถูกต้อง';
  END IF;
  IF btrim(current_setting('add_lecturer.first')) = '' OR btrim(current_setting('add_lecturer.last')) = '' THEN
    RAISE EXCEPTION 'ต้องระบุชื่อและนามสกุล';
  END IF;
  IF current_setting('add_lecturer.pw_len')::int < 12 THEN RAISE EXCEPTION 'รหัสผ่านต้องยาวอย่างน้อย 12 ตัวอักษร'; END IF;
  IF current_setting('add_lecturer.is_test')::boolean THEN RAISE EXCEPTION 'ห้ามใช้ Password@123 กับ prod (อยู่ใน git)'; END IF;
  SELECT role INTO v_role FROM users WHERE email = current_setting('add_lecturer.email');
  IF v_role IS NOT NULL AND v_role <> 'lecturer' THEN
    RAISE EXCEPTION 'อีเมลนี้เป็นบัญชี % อยู่แล้ว — ใช้อีเมลอื่น', v_role;
  END IF;
  IF v_role = 'lecturer' THEN
    RAISE NOTICE 'มีอาจารย์อีเมลนี้อยู่แล้ว — ไม่ได้แก้อะไร (รหัสผ่านเดิมยังใช้)';
  END IF;
END $$;

INSERT INTO users (email, password_hash, title, first_name, last_name, role)
SELECT lower(:'email'), crypt(:'pw', gen_salt('bf', 10)), nullif(btrim(:'title'), ''),
       btrim(:'first_name'), btrim(:'last_name'), 'lecturer'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = lower(:'email'));

SELECT email, role, title, first_name, last_name, is_active, created_at
FROM users WHERE email = lower(:'email');

COMMIT;
