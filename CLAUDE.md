# CLAUDE.md — กฎการทำงานใน repo นี้

## กฎหลัก: บันทึกรายงานการทำงานใน README.md ทุกครั้ง

ทุกครั้งที่ Claude ทำงานใน repo นี้ (เขียนโค้ด แก้ไข ลบ ย้ายไฟล์ ตั้งค่า หรืองานอื่น ๆ)
**ต้องเขียนรายงานสิ่งที่ทำไว้ใน `README.md` ของ Folder ที่เกี่ยวข้องเสมอ**

1. ถ้าแก้ไฟล์ใน Folder ใด ให้อัปเดต `README.md` ของ Folder นั้น
   (เช่น แก้ `backend/...` → อัปเดต `backend/README.md`)
   - ถ้า Folder ย่อยมี `README.md` ของตัวเองอยู่แล้ว (เช่น `frontend/tests/e2e/README.md`) ให้เขียนที่ไฟล์นั้น
   - ถ้า Folder หลักยังไม่มี `README.md` ให้สร้างขึ้นใหม่
2. งานที่กระทบหลาย Folder ให้เขียนใน `README.md` ของทุก Folder ที่เกี่ยวข้อง
   และสรุปภาพรวมไว้ใน `README.md` ที่ root
3. รายงานแต่ละครั้งให้เขียนไว้ใต้หัวข้อ `## บันทึกการทำงาน (Changelog)` ในรูปแบบ:

   ```markdown
   ### YYYY-MM-DD — หัวข้อสั้น ๆ ของงาน
   - **ทำอะไร:** อธิบายสิ่งที่ทำ
   - **ไฟล์ที่เกี่ยวข้อง:** `path/to/file`
   - **เหตุผล:** ทำไปเพื่ออะไร
   - **หมายเหตุ / สิ่งที่ต้องทำต่อ:** (ถ้ามี)
   ```

4. รายการใหม่ให้อยู่บนสุด (เรียงจากใหม่ไปเก่า)
5. อัปเดต README.md ใน commit เดียวกับการเปลี่ยนแปลงโค้ด
6. เขียนรายงานเป็นภาษาไทย ชื่อไฟล์ ชื่อฟังก์ชัน และคำศัพท์เทคนิคใช้ภาษาอังกฤษได้

## โครงสร้าง Folder หลัก

| Folder | README |
|---|---|
| `/` (root) | `README.md` |
| `frontend/` | `frontend/README.md` |
| `backend/` | `backend/README.md` |
| `ai-service/` | `ai-service/README.md` |
| `devops/` | `devops/README.md` |
| `docs/` | `docs/README.md` |

## กฎ git: push ขึ้น branch `Dev` เท่านั้น

ใช้ทุกครั้งที่จะ commit / push งานขึ้น GitHub (เริ่ม 2026-11-22)

1. **push ได้เฉพาะ branch `Dev`** — ห้าม push ขึ้น `main` และห้ามสร้าง branch ใหม่บน remote (เช่น `feat/...`, `docs/...`)
2. ก่อนเริ่มงาน: `git fetch origin` แล้ว `git switch Dev && git pull --ff-only`
   (ยังไม่มี `Dev` ในเครื่อง: `git switch -c Dev --track origin/Dev`)
3. commit ลง `Dev` ตรง ๆ · ถ้า `main` มี commit ที่ `Dev` ยังไม่มี ให้ `git merge origin/main` เข้า `Dev` ก่อน — มี conflict ให้สรุปให้ผู้ใช้ดูก่อนแก้
4. **ห้าม force push** (`--force`, `--force-with-lease`) และห้าม rebase commit ที่ push ไปแล้ว
5. เอาขึ้น `main` ผ่าน PR จาก `Dev` → `main` เท่านั้น และเปิด PR เมื่อผู้ใช้สั่ง
6. ข้อนี้ไม่ยกเว้นข้อห้ามบน VM — **บน VM ยังห้าม commit / push** (กฎการ deploy ข้อ 8)

## กฎการ deploy (K3s บน VM) — อ่านทุกครั้งก่อน deploy

ใช้เมื่อถูกขอให้ deploy, build image, `kubectl apply`, `init-db.sh`, rollout หรือแก้อะไรใน cluster
ขั้นตอนเต็มอยู่ใน `devops/README.md` — อ่านไฟล์นั้น และ `devops/k8s/**` ให้ครบก่อนลงมือทุกครั้ง

### ก่อนเริ่ม
1. ตรวจเครื่องก่อน แล้วสรุปให้ผู้ใช้ดู: K3s (`sudo k3s kubectl get nodes`), docker, Traefik, พื้นที่ดิสก์, host/IP ของ VM, โมเดลที่ `/opt/bangmod/models`
2. ขาดอะไรให้บอกวิธีแก้แล้ว**รอผู้ใช้ตอบ** — ห้ามติดตั้งโปรแกรม แก้ firewall หรือแก้การตั้งค่า K3s / ระบบเอง
3. ใช้โค้ดจาก `main` ล่าสุด และบอก commit ที่ใช้ deploy

### ลำดับ
4. **uat ก่อนเสมอ** → ตรวจผล → **หยุดรอผู้ใช้ยืนยัน** แล้วค่อยทำ prod
5. prod ทำเฉพาะเมื่อผู้ใช้สั่งชัด ๆ ในข้อความล่าสุด (การยืนยันครั้งก่อนไม่นับ)

### ข้อห้าม
6. **ห้าม `init-db.sh prod --seed`** และห้ามรัน `02_seed_mock.sql` / `03_demo_live_session.sql` กับ prod (ไฟล์ 02 เริ่มด้วย TRUNCATE ทุกตาราง)
7. ห้าม `kubectl delete` (namespace, PVC, pod, deployment ฯลฯ) ห้ามลบ volume หรือข้อมูลใน DB โดยไม่ถามก่อน — บอกผลกระทบทุกครั้งที่ถาม
8. ห้าม commit / push จาก VM · ถ้าต้องแก้ไฟล์ใน repo ให้รายงานว่าแก้อะไร เพื่อไปแก้ใน repo หลัก
9. ห้ามตั้ง `SPRING_PROFILES_ACTIVE=local` บน server (key ใน `application-local.yml` อยู่ใน git)

### ความลับ (`devops/k8s/overlays/<env>/secret.env`)
10. สร้างค่าใหม่ด้วย `openssl` ตามคำอธิบายใน `secret.env.example` · `chmod 600` · prod กับ uat ต้องคนละชุด
11. ห้ามแสดงค่าความลับในแชท / log / commit · ห้ามเขียนทับ `secret.env` ที่มีอยู่แล้ว
12. **`FACE_EMBEDDING_KEY` ห้ามเปลี่ยนหลังมีข้อมูลใบหน้า** (ถอดรหัส embedding เดิมไม่ได้) — เตือนผู้ใช้ให้สำรองไฟล์ไว้นอก VM

### ตรวจผลหลัง deploy (ทุก namespace)
13. `kubectl -n <ns> get pods` ทุกตัว Running (ai-service ไม่ขึ้นเพราะไม่มีโมเดล = ปกติ แค่รายงาน)
14. log backend ไม่มี error และ**ต้องไม่มี** WARN "โปรไฟล์ local"
15. `http://cp26ms3.sit.kmutt.ac.th/<ns>/` ได้ 200 (โดเมนของ VM · IP `10.4.56.69`) · login ผ่าน `/<ns>/api/auth/login` ได้ token · `/<ns>/api/internal/...` ต้องได้ 404 · uat เปิดใน browser ที่ `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/uat/` (เปิดตรงที่ VM ใน browser หน้าจะขาว — assets อ้าง path ของ bscit)
16. สรุปเป็นตาราง ผ่าน / ไม่ผ่าน + URL ที่ผู้ใช้เปิดใน browser ได้ · ขั้นตอนใน README ไม่ตรงกับของจริง ให้จดแล้วบอกตอนจบ

### prod ครั้งแรก
17. prod ไม่มีข้อมูลเริ่มต้น ต้องมี admin (`users`), `semesters`, `consent_versions` (`is_current = true`) — เตรียม SQL ให้ผู้ใช้ตรวจก่อน รหัสผ่าน admin ให้ผู้ใช้ตั้งเอง ห้ามรันเองโดยไม่ถาม

### เมื่อเกิดปัญหา
18. หาสาเหตุจาก log (`kubectl -n <ns> logs` / `describe`) แล้วรายงานพร้อมวิธีแก้ที่เสนอ อย่าเดาแก้ซ้ำ ๆ และอย่าลบแล้วสร้างใหม่เพื่อแก้ปัญหา
19. เทียบอาการกับตาราง "ปัญหาที่เจอตอน deploy และวิธีแก้" ใน `devops/README.md` ก่อน

### sudo
20. คำสั่งที่ต้องใช้ `sudo` (เช่น import image: `IMPORT_ONLY=1 ./devops/k8s/build-images.sh`) ให้หยุดแล้วส่งคำสั่งให้ผู้ใช้รันเอง — ห้ามขอรหัสผ่าน และห้ามหาทางเลี่ยง sudo

### บันทึกหลัง deploy
21. เมื่อผู้ใช้ยืนยันว่า deploy สำเร็จ ให้เตรียมแถวใหม่ของตาราง "ประวัติการ deploy" ใน `devops/README.md` (วันที่, namespace, commit, ผล, หมายเหตุ) ส่งให้ผู้ใช้ไปเพิ่มใน repo หลัก (ห้าม commit จาก VM) — ห้ามมีค่าความลับ

### CD (deploy อัตโนมัติ)
22. uat deploy เองทุกครั้งที่ push ขึ้น `Dev` (cron บน VM → `~/bangmod-cd/bin/deploy.sh uat --if-changed`) — **push ขึ้น `Dev` = ขึ้น uat** ต้องผ่าน `./dev.sh test` ก่อน push
23. เมื่อติดตั้ง CD แล้ว deploy ผ่าน `~/bangmod-cd/bin/deploy.sh <uat|prod>` เท่านั้น ห้าม `kubectl apply -k devops/k8s/overlays/<ns>` ตรง ๆ (จะเปลี่ยน image กลับเป็นตัวที่ import เอง)
24. prod: `deploy.sh prod` ต้องให้ผู้ใช้รันเองใน terminal (สคริปต์ให้พิมพ์ `prod` ยืนยัน) — ห้ามใส่ prod ใน cron
25. commit ที่แก้ `backend/db/` CD จะหยุด (blocked) — เตรียมคำสั่งอัปเดต DB ให้ผู้ใช้ตรวจ แล้วผู้ใช้สั่ง `ALLOW_DB_CHANGE=1 deploy.sh <ns>` เอง
26. ดูผล: `deploy.sh status` และ log ที่ `~/bangmod-cd/logs/` · deploy พังแล้ว rollback ให้รายงานสาเหตุจาก log ไม่ต้อง deploy ซ้ำวน
