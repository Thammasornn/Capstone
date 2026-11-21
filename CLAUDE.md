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
15. `http://cp26ms3.sit.kmutt.ac.th/<ns>/` ได้ 200 (โดเมนของ VM · IP `10.4.56.69`) · login ผ่าน `/<ns>/api/auth/login` ได้ token · `/<ns>/api/internal/...` ต้องได้ 404
16. สรุปเป็นตาราง ผ่าน / ไม่ผ่าน + URL ที่ผู้ใช้เปิดใน browser ได้ · ขั้นตอนใน README ไม่ตรงกับของจริง ให้จดแล้วบอกตอนจบ

### prod ครั้งแรก
17. prod ไม่มีข้อมูลเริ่มต้น ต้องมี admin (`users`), `semesters`, `consent_versions` (`is_current = true`) — เตรียม SQL ให้ผู้ใช้ตรวจก่อน รหัสผ่าน admin ให้ผู้ใช้ตั้งเอง ห้ามรันเองโดยไม่ถาม

### เมื่อเกิดปัญหา
18. หาสาเหตุจาก log (`kubectl -n <ns> logs` / `describe`) แล้วรายงานพร้อมวิธีแก้ที่เสนอ อย่าเดาแก้ซ้ำ ๆ และอย่าลบแล้วสร้างใหม่เพื่อแก้ปัญหา
