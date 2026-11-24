# docs

| เอกสาร | เนื้อหา |
|---|---|
| [`database/database-design.md`](database/database-design.md) | แบบฐานข้อมูล Release 1 — ER Diagram (Mermaid), ตาราง, กติกา, หน้าจอ → ตาราง |
| [`database/db-design-comparison.md`](database/db-design-comparison.md) | เปรียบเทียบแบบ Opus vs Sonnet และสิ่งที่แก้ |

TODO: ย้ายเอกสารอื่นเข้ามา (API guide, PDPA ฯลฯ)

## บันทึกการทำงาน (Changelog)

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
