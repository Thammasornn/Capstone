-- =====================================================================
-- prod: ข้อมูลเริ่มต้นที่ระบบต้องมี (CLAUDE.md ข้อ 17) · ไม่มีบัญชี / รหัสผ่าน / ข้อมูล mock
--   1. semesters        ภาคเรียนปัจจุบัน (ไม่มี = สร้างรายวิชาไม่ได้)
--   2. consent_versions ฉบับข้อความยินยอมที่มีผล (ไม่มี = ลงทะเบียนใบหน้าได้ 503)
--   3. rooms            ห้องเรียน (ไม่มี = สร้างรายวิชาไม่ได้ · ยังไม่มีหน้าเว็บเพิ่มห้อง)
--   4. cameras          (ไม่บังคับ) กล้องห้อง 4303 → MediaMTX path room-4303 ไว้เดโมกล้องบน prod
-- admin คนแรกใช้ prod-first-admin.sql (รันก่อนหรือหลังไฟล์นี้ก็ได้)
--
-- รันบน VM หลัง init-db.sh prod · ค่าภาคเรียน / ห้อง / กล้อง ดูด้านล่าง (ส่งค่าอื่นทาง printf ได้) · รันซ้ำได้ (มีอยู่แล้ว = ไม่แก้)
--
--   R=~/bangmod-cd/repo; git -C $R fetch -q origin
--   C=frontend/src/features/face-registration
--   SHA=$(git -C $R show origin/main:$C/consent-text.js | sha256sum | cut -c1-64)
--   VER=$(git -C $R show origin/main:$C/constants.js | sed -n "s/.*CONSENT_VERSION = '\(v[0-9]*\)'.*/\1/p")
--   echo "consent $VER $SHA"          # ใช้ commit เดียวกับที่ deploy prod (origin/main)
--   { printf "\\set consent_version '%s'\n\\set consent_sha '%s'\n" "$VER" "$SHA"
--     git -C $R show origin/main:devops/k8s/sql/prod-first-setup.sql; } \
--     | kubectl -n prod exec -i statefulset/postgres -- psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1
--
-- * consent: backend เทียบแค่ชื่อฉบับ (version) กับที่หน้าเว็บส่งมา · content_sha256 = หลักฐานว่าฉบับนี้คือข้อความไหน
--   = sha256 ของไฟล์ consent-text.js ณ commit ที่ deploy
-- * ⚠️ ข้อความ consent v1 ยังเป็นร่าง (มีช่อง [ ... ] และยังไม่ผ่าน DPO มจธ.) — ใช้ได้กับเดโมโครงงานเท่านั้น
--   ห้ามใช้เก็บใบหน้านักศึกษาจริงจนกว่าข้อความจะผ่าน DPO
--   ข้อความจริงมาเมื่อไร: แก้ consent-text.js + CONSENT_VERSION = 'v2' (constants.js) → merge main → deploy prod
--   → รันไฟล์นี้อีกครั้งด้วย VER / SHA ใหม่ (ขั้นด้านบน): เพิ่ม v2 เป็นฉบับปัจจุบัน v1 เลิกเป็นปัจจุบัน
--   (ไม่ลบ v1 · consent_records เดิมอ้าง v1 ต่อ · คนที่ยินยอม v1 จะถูกขอยินยอมใหม่ = needs_reconsent)
-- * ไม่พบ schema / เป็น DB ที่มีข้อมูล mock (uat) = หยุดทันที ไม่แก้อะไร
-- =====================================================================

-- ===== ค่าเริ่มต้น (ตรวจกับปฏิทินการศึกษา มจธ.) · เปลี่ยนได้โดยส่ง \set ชื่อเดียวกันมาก่อนไฟล์
--   เช่น เพิ่มใน printf: \set sem_start '2026-08-10'\n\set with_camera true\n
\if :{?academic_year} \else \set academic_year 2569 \endif
\if :{?term} \else \set term 1 \endif
\if :{?sem_start} \else \set sem_start 2026-08-03 \endif
\if :{?sem_end} \else \set sem_end 2026-12-18 \endif
-- ห้องเรียน (room_code · ห้องที่มีอยู่แล้วไม่แตะ)
\if :{?rooms} \else \set rooms '{4301,4302,4303,4304,4305}' \endif
\if :{?room_capacity} \else \set room_capacity 45 \endif
\if :{?building} \else \set building 'อาคาร SIT' \endif
-- กล้องห้อง 4303 สำหรับเดโม: true = เพิ่ม (ai-service ของ prod จะดึง room-4303 ด้วย → ใช้ CPU เพิ่ม)
\if :{?with_camera} \else \set with_camera false \endif

BEGIN;
SET LOCAL timezone = 'Asia/Bangkok';

SELECT set_config('prod_setup.version', :'consent_version', true),
       set_config('prod_setup.sha',     lower(:'consent_sha'), true),
       set_config('prod_setup.start',   :'sem_start', true),
       set_config('prod_setup.end',     :'sem_end', true)
\gset _

DO $$
BEGIN
  IF to_regclass('public.consent_versions') IS NULL THEN
    RAISE EXCEPTION 'ยังไม่มี schema — รัน init-db.sh prod ก่อน';
  END IF;
  IF EXISTS (SELECT 1 FROM sections WHERE section_id = md5('section:INT201-001')::uuid) THEN
    RAISE EXCEPTION 'DB นี้มีข้อมูล mock (uat) — ไฟล์นี้สำหรับ prod เท่านั้น ไม่ได้แก้อะไร';
  END IF;
  IF current_setting('prod_setup.version') !~ '^v[0-9]+$' THEN
    RAISE EXCEPTION 'consent_version ต้องเป็นแบบ v1 (ได้ "%") — ดูขั้นอ่าน constants.js หัวไฟล์', current_setting('prod_setup.version');
  END IF;
  IF current_setting('prod_setup.sha') !~ '^[0-9a-f]{64}$' THEN
    RAISE EXCEPTION 'consent_sha ต้องเป็น sha256 64 ตัว (hex)';
  END IF;
  IF current_setting('prod_setup.end')::date < current_date THEN
    RAISE EXCEPTION 'วันสิ้นภาค % ผ่านไปแล้ว — ระบบจะหาภาคปัจจุบันไม่เจอ', current_setting('prod_setup.end');
  END IF;
  -- ฉบับนี้มีอยู่แล้วแต่ hash ต่าง = ข้อความถูกแก้โดยไม่เปลี่ยนชื่อฉบับ
  IF EXISTS (SELECT 1 FROM consent_versions WHERE version = current_setting('prod_setup.version')
                AND content_sha256 <> current_setting('prod_setup.sha')) THEN
    RAISE EXCEPTION 'consent % มีอยู่แล้วแต่ข้อความ (hash) ต่างกัน — แก้ข้อความต้องเปลี่ยน CONSENT_VERSION เป็นฉบับใหม่',
      current_setting('prod_setup.version');
  END IF;
END $$;

-- 1. ภาคเรียน (มีอยู่แล้ว = ไม่แก้วันที่ · ดูผลด้านล่าง)
INSERT INTO semesters (academic_year, term, start_date, end_date)
VALUES (:'academic_year', :'term', :'sem_start', :'sem_end')
ON CONFLICT (academic_year, term) DO NOTHING;

-- 2. ข้อความยินยอม: ฉบับใหม่เป็นฉบับปัจจุบัน ฉบับอื่นเลิกเป็นปัจจุบัน (ไม่ลบ)
UPDATE consent_versions SET is_current = false
WHERE is_current AND version <> :'consent_version';
INSERT INTO consent_versions (version, content_sha256, effective_from, is_current)
VALUES (:'consent_version', lower(:'consent_sha'), now(), true)
ON CONFLICT (version) DO UPDATE SET is_current = true;

-- 3. ห้องเรียน
INSERT INTO rooms (room_id, room_code, building, capacity)
SELECT md5('room:' || r)::uuid, r, :'building', :'room_capacity'::smallint
FROM unnest(:'rooms'::text[]) AS r
ON CONFLICT (room_code) DO NOTHING;

-- 4. กล้อง (ไม่บังคับ)
INSERT INTO cameras (camera_id, room_id, camera_code, stream_path, status)
SELECT md5('camera:4303')::uuid, r.room_id, 'CAM-4303-A', 'room-4303', 'no_signal'
FROM rooms r
WHERE :'with_camera'::boolean AND r.room_code = '4303'
ON CONFLICT DO NOTHING;

-- ===== ตรวจหลัง =====
SELECT academic_year || '/' || term AS semester, start_date, end_date,
       end_date >= current_date AS usable_now
FROM semesters ORDER BY start_date;
SELECT version, content_sha256, effective_from, is_current FROM consent_versions ORDER BY effective_from;
SELECT r.room_code, r.capacity, c.camera_code, c.stream_path
FROM rooms r LEFT JOIN cameras c ON c.room_id = r.room_id ORDER BY 1;
SELECT (SELECT count(*) FROM users) AS users, (SELECT count(*) FROM users WHERE role = 'admin') AS admins;

COMMIT;
