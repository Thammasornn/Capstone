# docs

| เอกสาร | เนื้อหา |
|---|---|
| [`database/database-design.md`](database/database-design.md) | แบบฐานข้อมูล Release 1 — ER Diagram (Mermaid), ตาราง, กติกา, หน้าจอ → ตาราง |
| [`database/db-design-comparison.md`](database/db-design-comparison.md) | เปรียบเทียบแบบ Opus vs Sonnet และสิ่งที่แก้ |
| [`summary-conflict.md`](summary-conflict.md) | บันทึกงานหลังแต่ละ push ขึ้น `Dev`: แก้อะไร, ปัญหา / conflict ที่เจอ, สิ่งที่ค้าง |

TODO: ย้ายเอกสารอื่นเข้ามา (API guide, PDPA ฯลฯ)

## บันทึกการทำงาน (Changelog)

### 2026-11-26 — summary-conflict: รอบ E2 / F1–F3 / seed เดโม / AI / เตรียม prod
- **ทำอะไร:** เพิ่มรายการ `e7db958..79240fc` ใน `summary-conflict.md` (13 commit: แก้อะไรแยก area, ปัญหา เช่น CD blocked จาก `2eef1ff` และ bscit `/prod/` ได้ 503, สิ่งที่ค้าง)
- **ไฟล์ที่เกี่ยวข้อง:** `docs/summary-conflict.md`, `docs/README.md`
- **เหตุผล:** ผู้ใช้สั่งให้จดรอบนี้เลย ก่อน coordinator ส่งรายงาน
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** จดจาก commit message + changelog ใน README · ถ้า coordinator ส่งรายงานรอบนี้มาภายหลัง ให้เติมปัญหา / conflict ระหว่าง sub-chat ที่ยังไม่ได้จด

### 2026-11-26 — เริ่มบันทึกงานและปัญหาหลังแต่ละ push
- **ทำอะไร:** สร้าง `summary-conflict.md` (คำอธิบาย + รูปแบบรายการ + `## บันทึก`) และลงย้อนหลัง 5 รอบ push ของ 2026-11-25 (`fc31b5e..cd04b8b`): docs sync, ปรับเวลาคาบที่เริ่มแล้ว, C1–C3 นำเข้าผลเช็คชื่อ, D1–D3 จัดการรายคาบ, แก้ข้อความ 409 · เพิ่มลิงก์ในตารางเอกสารด้านบน
- **ไฟล์ที่เกี่ยวข้อง:** `docs/summary-conflict.md`, `docs/README.md`
- **เหตุผล:** ผู้ใช้ต้องการบันทึกว่าแต่ละรอบ push แก้อะไรและเจอปัญหาอะไร ให้ย้อนดูได้ในที่เดียว
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** หลังทุก push ขึ้น `Dev` coordinator ส่งสรุปมา แล้วเพิ่ม 1 รายการบนสุด

### 2026-11-25 — แบบฐานข้อมูล: คาบเรียน (จัดการรายคาบ)
- **ทำอะไร:** `database/database-design.md` เพิ่มคอลัมน์ใหม่ของ `class_sessions` ใน ER (+ ความสัมพันธ์คาบชดเชย) และหัวข้อ "แม่แบบ (`sections`) vs คาบจริง (`class_sessions`)": regenerate ไม่แตะคาบที่ `is_overridden`, `original_date` กันสร้างซ้ำ, ยกเลิก / คืนคาบ, คาบเพิ่ม / ชดเชย, คาบที่ยกเลิกไม่นับในสถิติ
- **ไฟล์ที่เกี่ยวข้อง:** `database/database-design.md`
- **เหตุผล:** ให้ตรงกับ `backend/db/01_schema.sql` (D1)

### 2026-11-25 — แบบฐานข้อมูล: นำเข้าผลเช็คชื่อจากไฟล์
- **ทำอะไร:** `database/database-design.md` เพิ่มตาราง `attendance_imports`, `attendance_import_rows` (ER diagram + ตารางกลุ่ม 4) · `attendance_records.import_id` + `source = import` · กติกา: ลำดับความสำคัญ manual > import > camera > system, แถว import ไม่มี `recorded_by` / `reason`, อาจารย์แก้ทับ → manual · หมายเหตุ PDPA (`raw_identifier` อาจเป็นอีเมลบุคคลภายนอก → staff เท่านั้น, นโยบายลบไว้ R2) · จำนวนตาราง 20 → 22
- **ไฟล์ที่เกี่ยวข้อง:** `database/database-design.md`
- **เหตุผล:** ให้ตรงกับ `backend/db/01_schema.sql` (C1)

### 2026-11-25 — ปรับ README ให้ตรงกับโค้ดปัจจุบัน
- **ทำอะไร:** ตาราง "หน้าจอ / API → ตาราง" แถว Room Live เพิ่มเส้นเริ่ม / จบคาบ (+ `audit_logs`)
- **ไฟล์ที่เกี่ยวข้อง:** `database/database-design.md`
- **เหตุผล:** backend เพิ่มเส้นนี้แล้วแต่เอกสาร DB ยังไม่มี
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ต้นฉบับบน claude.ai ยังไม่ได้แก้ตาม

### 2026-11-21 — ปรับ database-design.md ให้ตรงกับโค้ด backend
- **ทำอะไร:** แก้ลำดับขั้นตอนลงทะเบียนใบหน้า (เรียก AI ก่อนบันทึก consent), ขั้นตอนถอน (แจ้ง AI หลัง commit), แถว Analytics ในตารางหน้าจอ → ตาราง และเพิ่มคำถามเรื่อง `lecturerCode` / `department` ของอาจารย์
- **ไฟล์ที่เกี่ยวข้อง:** `database/database-design.md`
- **เหตุผล:** เทียบ docs กับ backend แล้วพบจุดที่ไม่ตรงกัน
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ต้นฉบับบน claude.ai (ลิงก์ในหัวเอกสาร) ยังไม่ได้แก้ตาม
