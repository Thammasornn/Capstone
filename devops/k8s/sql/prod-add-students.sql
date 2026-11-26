-- =====================================================================
-- prod: สร้างบัญชีนักศึกษาจาก CSV (users + student_profiles) · ไม่ลงทะเบียนเข้ากลุ่มเรียน
-- ⚠️ ข้อมูลบน prod ของโครงงานนี้ต้องเป็น dummy เท่านั้น (PDPA · ตามอาจารย์ที่ปรึกษา) — ห้ามใช้รายชื่อนักศึกษาจริง
--
-- CSV หัวเดียวกับ template ของหน้า "รายชื่อนักศึกษาในกลุ่มเรียน" (E2): student_code,title,first_name,last_name,email
--   * student_code (11 หลัก), first_name, last_name ต้องมี · title ว่างได้
--   * email ว่าง = <student_code>@st.sit.kmutt.ac.th · ถ้าใส่ ต้องเป็น <student_code>@...
--   * UTF-8 (มี BOM ได้) · ช่องที่มี , ให้ครอบด้วย "..." แบบ CSV ปกติ · ไม่เกิน 500 แถว
-- รหัสผ่านแรกเข้า: รหัสชั่วคราวชุดเดียวกันทุกคน พิมพ์ตอนรัน (ไม่แสดง · ≥ 12 ตัว) แล้วแจกเอง
--   ⚠️ ระบบยังไม่มีหน้าเปลี่ยนรหัสผ่าน / ไม่มี flag บังคับเปลี่ยน → รหัสชั่วคราวใช้ได้ไปเรื่อย ๆ
--
-- รันบน VM หลัง prod-first-setup.sql · ลอง DRY=true ก่อน (แสดงผล ไม่บันทึก) แล้วค่อย DRY=false
--
--   CSV=~/students.csv; DRY=true
--   read -rsp 'รหัสผ่านชั่วคราว: ' PW; echo; read -rsp 'พิมพ์อีกครั้ง: ' PW2; echo
--   if [ "$PW" != "$PW2" ]; then echo 'รหัสผ่านไม่ตรงกัน'
--   elif case "$PW" in *\'*|*\\*) true;; *) false;; esac; then echo "ห้ามมี ' หรือ \\"
--   else
--     { printf "\\set csv_b64 '%s'\n\\set pw '%s'\n\\set dry_run %s\n" "$(base64 -w0 "$CSV")" "$PW" "$DRY"
--       git -C ~/bangmod-cd/repo show origin/main:devops/k8s/sql/prod-add-students.sql; } \
--       | kubectl -n prod exec -i statefulset/postgres -- psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1
--   fi; unset PW PW2
--
-- * transaction เดียว · มีแถวผิดแม้แถวเดียว = แสดงรายการ error แล้วไม่บันทึกอะไร (แก้ไฟล์แล้วรันใหม่)
-- * รหัส / อีเมลมีบัญชีอยู่แล้ว = ข้าม (ไม่แก้ชื่อ / รหัสผ่าน) · ซ้ำกันในไฟล์ = error · รันซ้ำได้
-- * เพิ่มเข้ากลุ่มเรียน: อาจารย์ / admin ใช้หน้า "รายชื่อนักศึกษาในกลุ่มเรียน" (เพิ่มเอง / นำเข้าไฟล์เดียวกันนี้)
-- * หยุดเมื่อ: รหัสผ่านสั้นกว่า 12 ตัว · Password@123 · DB มีข้อมูล mock (uat) · ไม่มี schema
-- =====================================================================

\if :{?dry_run} \else \set dry_run false \endif

BEGIN;

SELECT set_config('add_students.pw_len',  length(:'pw')::text, true),
       set_config('add_students.is_test', (:'pw' = 'Password@123')::text, true),
       set_config('add_students.dry_run', (:'dry_run')::boolean::text, true)
\gset _

DO $$
BEGIN
  IF to_regclass('public.student_profiles') IS NULL THEN RAISE EXCEPTION 'ยังไม่มี schema — รัน init-db.sh prod ก่อน'; END IF;
  IF EXISTS (SELECT 1 FROM sections WHERE section_id = md5('section:INT201-001')::uuid) THEN
    RAISE EXCEPTION 'DB นี้มีข้อมูล mock (uat) — ไฟล์นี้สำหรับ prod เท่านั้น ไม่ได้แก้อะไร';
  END IF;
  IF current_setting('add_students.pw_len')::int < 12 THEN RAISE EXCEPTION 'รหัสผ่านต้องยาวอย่างน้อย 12 ตัวอักษร'; END IF;
  IF current_setting('add_students.is_test')::boolean THEN RAISE EXCEPTION 'ห้ามใช้ Password@123 กับ prod (อยู่ใน git)'; END IF;
END $$;

-- ---------- อ่าน CSV (base64 → บรรทัด → ช่อง) ----------
CREATE TEMP TABLE csv_lines ON COMMIT DROP AS
SELECT t.n AS line_no, t.line
FROM regexp_split_to_table(
       replace(regexp_replace(convert_from(decode(:'csv_b64', 'base64'), 'UTF8'), '^﻿', ''), E'\r', ''),
       E'\n') WITH ORDINALITY AS t(line, n)
WHERE btrim(t.line) <> '';

CREATE FUNCTION pg_temp.csv_fields(text) RETURNS text[] LANGUAGE sql IMMUTABLE AS $$
  SELECT array_agg(CASE WHEN m[1] LIKE '"%' THEN replace(substr(m[1], 2, length(m[1]) - 2), '""', '"')
                        ELSE btrim(m[1]) END ORDER BY ord)
  FROM regexp_matches($1, '(?:^|,)("(?:[^"]|"")*"|[^,]*)', 'g') WITH ORDINALITY AS r(m, ord)
$$;

CREATE TEMP TABLE csv_header ON COMMIT DROP AS
SELECT lower(btrim(h)) AS name, i AS pos
FROM csv_lines l, unnest(pg_temp.csv_fields(l.line)) WITH ORDINALITY AS u(h, i)
WHERE l.line_no = (SELECT min(line_no) FROM csv_lines);

DO $$
DECLARE bad text;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM csv_lines) THEN RAISE EXCEPTION 'ไฟล์ CSV ว่าง'; END IF;
  SELECT string_agg(name, ', ') INTO bad FROM csv_header
   WHERE name NOT IN ('student_code', 'title', 'first_name', 'last_name', 'email');
  IF bad IS NOT NULL THEN
    RAISE EXCEPTION 'ไม่รู้จักคอลัมน์: % (ใช้ได้: student_code, title, first_name, last_name, email)', bad;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM csv_header WHERE name = 'student_code') THEN
    RAISE EXCEPTION 'ต้องมีคอลัมน์ student_code';
  END IF;
  IF (SELECT count(*) FROM csv_lines) - 1 > 500 THEN RAISE EXCEPTION 'ไม่เกิน 500 แถวต่อครั้ง'; END IF;
END $$;

CREATE TEMP TABLE csv_rows ON COMMIT DROP AS
SELECT l.line_no,
       nullif(btrim(f[(SELECT pos FROM csv_header WHERE name = 'student_code')]), '') AS student_code,
       nullif(btrim(f[(SELECT pos FROM csv_header WHERE name = 'title')]), '')        AS title,
       nullif(btrim(f[(SELECT pos FROM csv_header WHERE name = 'first_name')]), '')   AS first_name,
       nullif(btrim(f[(SELECT pos FROM csv_header WHERE name = 'last_name')]), '')    AS last_name,
       lower(nullif(btrim(f[(SELECT pos FROM csv_header WHERE name = 'email')]), '')) AS email
FROM csv_lines l
CROSS JOIN LATERAL (SELECT pg_temp.csv_fields(l.line) AS f) ff
WHERE l.line_no > (SELECT min(line_no) FROM csv_lines);

UPDATE csv_rows SET email = student_code || '@st.sit.kmutt.ac.th' WHERE email IS NULL AND student_code IS NOT NULL;

-- ---------- ตรวจ (กติกาเดียวกับ E2) และวางแผน ----------
CREATE TEMP TABLE plan ON COMMIT DROP AS
SELECT r.*,
       CASE
         WHEN r.student_code IS NULL OR r.student_code !~ '^[0-9]{11}$' THEN 'error: student_code ต้องเป็นตัวเลข 11 หลัก'
         WHEN r.email !~ ('^' || r.student_code || '@[^@\s]+\.[^@\s]+$') THEN 'error: email ต้องเป็น <student_code>@...'
         WHEN r.first_name IS NULL OR r.last_name IS NULL THEN 'error: ต้องมี first_name และ last_name'
         WHEN length(r.first_name) > 100 OR length(r.last_name) > 100 THEN 'error: ชื่อ / นามสกุลยาวเกิน 100 ตัวอักษร'
         WHEN length(r.title) > 20 THEN 'error: คำนำหน้ายาวเกิน 20 ตัวอักษร'
         WHEN count(*) OVER (PARTITION BY r.student_code) > 1 THEN 'error: student_code ซ้ำในไฟล์'
         WHEN count(*) OVER (PARTITION BY r.email) > 1 THEN 'error: email ซ้ำในไฟล์'
         WHEN EXISTS (SELECT 1 FROM student_profiles sp JOIN users u ON u.user_id = sp.student_id
                       WHERE sp.student_code = r.student_code AND u.email = r.email) THEN 'skip: มีบัญชีอยู่แล้ว'
         WHEN EXISTS (SELECT 1 FROM student_profiles sp WHERE sp.student_code = r.student_code)
              THEN 'error: รหัสนี้มีบัญชีอยู่แล้วแต่อีเมลต่างกัน'
         WHEN EXISTS (SELECT 1 FROM users u WHERE u.email = r.email) THEN 'error: อีเมลนี้เป็นของบัญชีอื่นอยู่แล้ว'
         ELSE 'insert'
       END AS action
FROM csv_rows r;

SELECT split_part(action, ':', 1) AS result, count(*) AS rows FROM plan GROUP BY 1 ORDER BY 1;
SELECT line_no AS csv_line, student_code, email, action FROM plan WHERE action LIKE 'error%' ORDER BY line_no;

DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM plan WHERE action LIKE 'error%';
  IF n > 0 THEN RAISE EXCEPTION 'มี % แถวผิด (ดูรายการด้านบน) — ไม่ได้บันทึกอะไร', n; END IF;
END $$;

-- ---------- บันทึก ----------
CREATE TEMP TABLE created ON COMMIT DROP AS
WITH u AS (
  INSERT INTO users (email, password_hash, title, first_name, last_name, role)
  SELECT p.email, crypt(:'pw', gen_salt('bf', 10)), p.title, p.first_name, p.last_name, 'student'
  FROM plan p WHERE p.action = 'insert'
  RETURNING user_id, email)
SELECT u.user_id, u.email, p.student_code FROM u JOIN plan p ON p.email = u.email;

-- ปีที่เข้า = 25 + 2 หลักแรกของรหัส (66… → 2566)
INSERT INTO student_profiles (student_id, student_code, faculty, entry_year)
SELECT user_id, student_code, 'คณะเทคโนโลยีสารสนเทศ', 2500 + left(student_code, 2)::int FROM created;

SELECT count(*) AS created FROM created;
SELECT CASE WHEN current_setting('add_students.dry_run')::boolean
            THEN 'dry_run: ยกเลิกทั้งหมด (ไม่บันทึก) — รันใหม่ด้วย DRY=false'
            ELSE 'บันทึกแล้ว' END AS result;

SELECT CASE WHEN current_setting('add_students.dry_run')::boolean THEN 'ROLLBACK' ELSE 'COMMIT' END AS end_tx
\gset
:end_tx;
