# บันทึกงานและปัญหาหลังแต่ละ push (summary conflict)

ไฟล์นี้เป็นบันทึกการทำงานของทั้งโปรเจกต์ หลังทุกครั้งที่ push ขึ้น `Dev`
Main Chat (coordinator) จะส่งสรุปมาให้ sub-chat "summary conflict" จดลงที่นี่ 1 รายการต่อ 1 push
เพื่อให้ย้อนดูได้ว่าแต่ละรอบแก้อะไร (แยกตาม area: DB / BE / FE / DevOps / docs)
เจอปัญหาหรือ conflict อะไร แก้อย่างไร และยังมีอะไรค้างอยู่

- รายการใหม่อยู่บนสุด (เรียงจากใหม่ไปเก่า)
- ช่วง commit เขียนแบบ `<commit ก่อนรอบนี้>..<commit สุดท้ายของรอบ>`
- ห้ามมีค่าความลับ (รหัสผ่าน, key, ค่าใน `secret.env`)

รูปแบบ:

```markdown
### YYYY-MM-DD — <หัวข้อ> (`<commit แรก>..<commit สุดท้าย>`)
- **แก้อะไร:** สรุปสั้น ๆ แยกตาม area พร้อม commit hash
- **ปัญหา / conflict ที่เจอ:** สิ่งที่พังหรือขัดกัน และแก้อย่างไร · ไม่มี = "ไม่มี"
- **ยังค้าง / ต้องทำต่อ:** เช่น SQL ที่ผู้ใช้ต้องรันบน VM, สิ่งที่ยังไม่ได้ทดสอบ
```

## บันทึก

### 2026-11-25 — ข้อความ 409 "มีคาบอยู่แล้ว" บอกวันที่จริง (`296bb1d..cd04b8b`)
- **แก้อะไร:**
  - BE `cd04b8b`: 409 "วันนี้กลุ่มเรียนนี้มีคาบอยู่แล้ว" เดิมขึ้นคำว่า "วันนี้" แม้วันที่ที่ขอเป็นวันอื่น → ตอนนี้บอกวันที่จริง (`yyyy-MM-dd`) · กรณีเริ่มคาบทันที (start-now) ยังใช้ "วันนี้" เหมือนเดิม
- **ปัญหา / conflict ที่เจอ:** ไม่มี (FE เจอข้อความผิดตอนทำ D3 แล้วแจ้ง BE แก้)
- **ยังค้าง / ต้องทำต่อ:** ไม่มี

### 2026-11-25 — D1–D3 จัดการคาบเรียนรายคาบ (`2861543..296bb1d`)
- **แก้อะไร:**
  - DB `7f91a32` (D1): `class_sessions` เพิ่มคอลัมน์ `is_overridden`, `original_date`, `makeup_for_session_id`, `is_extra`, `cancel_reason` + ไฟล์อัปเดต `06_class_session_management.sql` + `ClassSessionRepository`
  - BE `f0b1bb2` (D2): API ดูรายการคาบ / เลื่อนคาบ / ยกเลิก + คาบชดเชย / เพิ่มคาบ / คืนคาบ, field `allowedActions`, `overriddenSessionsKept`
  - FE `296bb1d` (D3): หน้าจัดการคาบ + dialog ของแต่ละ action
- **ปัญหา / conflict ที่เจอ:**
  - ข้อความสวนกัน: contract D2 จาก coordinator ไปถึง FE หลัง FE ทำ D3 เสร็จแล้ว (FE อ่านจากโค้ด BE เอง) → เทียบแล้วตรงกัน ไม่ต้องแก้ซ้ำ
  - BE ตัดสินกติกาเองที่สเปกไม่ได้ระบุ: 1 คาบชดเชยได้ 1 ครั้ง · ยกเลิกคาบที่กำลังสอนอยู่ → เก็บผลเช็คชื่อไว้แต่ไม่นับในสถิติ
- **ยังค้าง / ต้องทำต่อ:**
  - ผู้ใช้ต้องรัน `06_class_session_management.sql` บน uat แล้วสั่ง `ALLOW_DB_CHANGE=1 deploy.sh uat` (CD blocked เพราะแก้ `backend/db/`)
  - prod ต้องรันทั้ง `05` และ `06`
  - ยังไม่ได้ทดสอบด้วยบัญชีอาจารย์ และกรณีห้องชนกันข้ามกลุ่มเรียน

### 2026-11-25 — C1–C3 นำเข้าผลเช็คชื่อจากไฟล์ (`19cac9c..2861543`)
- **แก้อะไร:**
  - DB `428de98` (C1): ตาราง `attendance_imports`, `attendance_import_rows`, `attendance_records.source = 'import'` + `import_id`, ไฟล์อัปเดต `05_attendance_import.sql`
  - BE `6dd98e8` (C2): API นำเข้าไฟล์ + กติกาการเขียนทับ (override)
  - FE `7f1020d`, `2861543` (C3): wizard นำเข้าไฟล์ + คำเตือนให้ลบแถวที่ไม่ใช่นักศึกษาก่อนนำเข้า
- **ปัญหา / conflict ที่เจอ:**
  - BE ถาม coordinator ขอ C1 ทั้งที่ DB commit ไปแล้ว (ข้อความไม่ตรงกับสถานะจริง)
  - สเปกตีความได้สองแบบ: มีทั้งลำดับความสำคัญ (priority) และ flag override → C2 เลือกแบบนี้: ต่างจากผลกล้อง → conflict เว้นแต่ส่ง `overrideCamera` · ทับผล manual → conflict เสมอ เว้นแต่ส่ง `overrideManual`
  - ไฟล์ Teams ที่มีอีเมลคนที่ไม่ใช่นักศึกษา → 400 ทั้งไฟล์ (ตามสเปก) → FE เพิ่มคำเตือน (`2861543`)
  - ดิสก์ของ DB เต็มระหว่างทำงาน (เหลือ ~121MB) แล้วกลับมาปกติ
- **ยังค้าง / ต้องทำต่อ:**
  - `05` บน uat: ผู้ใช้รันแล้ว
  - ยังไม่ได้ทดสอบ: ไฟล์ XLSX, `duplicateOf`, หน้าจอมือถือ, การนำเข้าระหว่างที่ SSE ทำงาน

### 2026-11-25 — ปรับเวลาคาบที่เริ่มแล้ว + field ช่วงเวลาเช็คชื่อ (`696c2bf..19cac9c`)
- **แก้อะไร:**
  - BE `22f5ad1`: เพิ่ม 4 field แบบ `"HH:mm"` ใน `currentSession` ของ B1 / B4 และ 5.7
  - FE `19cac9c`: จัดการเวลาของคาบที่เริ่มแล้วจากหน้าห้องและหน้ารายงาน
- **ปัญหา / conflict ที่เจอ:**
  - `./dev.sh test` ไม่ผ่านที่ format ของ frontend เพราะไฟล์ FE ยังไม่ commit ระหว่างแก้ → รัน prettier ก่อน commit แล้วผ่าน
  - รูปแบบเวลาไม่เหมือนกัน: `startTime` / `endTime` ยังเป็น `"HH:mm:ss"` ส่วน field ใหม่เป็น `"HH:mm"`
  - FE push รอบนี้เอง ไม่ได้ผ่าน coordinator
- **ยังค้าง / ต้องทำต่อ:** ไม่มี

### 2026-11-25 — docs sync README ให้ตรงกับโค้ด (`fc31b5e..8a9fb0b`)
- **แก้อะไร:**
  - docs `e2f3586`: แก้ README 9 จุดที่เก่า / ขัดกัน — ตารางสถานะที่ root, endpoint เริ่มคาบอยู่ผิดตารางใน `backend/README.md`, `MEDIA_BASE_URL`, realtime matcher, ลิงก์ `claude/frontend-structure.md` ที่หายไป, CORS
  - `8a9fb0b`: merge `origin/main` เข้า `Dev`
- **ปัญหา / conflict ที่เจอ:**
  - README เขียนว่า "ไม่ต้องตั้ง CORS" แต่ uat ที่อยู่หลัง bscit ต้องตั้ง `CORS_ORIGINS=https://bscit.sit.kmutt.ac.th` → DevOps ยืนยันจากโค้ดแล้วแก้ README
  - `main` มี merge commit ของ PR ที่ `Dev` ยังไม่มี → merge `origin/main` เข้า `Dev` ก่อน (เนื้อหาไม่เปลี่ยน)
- **ยังค้าง / ต้องทำต่อ:** comment เรื่อง CORS ใน `devops/k8s/overlays/prod/kustomization.yaml:26` ยังไม่ได้แก้
