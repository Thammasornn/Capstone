# เปรียบเทียบ DB Design: Opus (High) vs Sonnet (Medium)

> 21 พ.ย. 2569 · เอกสารต้นฉบับ: [เปรียบเทียบ DB Design](https://claude.ai/artifact/NnoZ5FfdjEGbbGQ2ZJ7X4q)
> แบบ Sonnet: [FigJam Conceptual - Design](https://www.figma.com/board/dAYf1zRhO0J8ajr29UcNtJ/Conceptual---Design?node-id=0-1) · แบบ Opus: [database-design.md](database-design.md)

**ใช้แบบ Opus เป็นฐาน และรับ 4 จุดที่ Sonnet ทำได้ดีกว่าเข้ามาแล้ว** แบบ Sonnet (10 ตาราง) ครอบคลุมนักศึกษา รายวิชา คาบเรียน และผลเช็คชื่อ แต่ขาดอาจารย์ผู้สอน ห้องและกล้อง embedding และหลักฐานความยินยอม PDPA และยังเก็บลิงก์รูปใบหน้าซึ่งขัดกับข้อความยินยอม v1

| | Opus (High) | Sonnet (Medium) |
|---|---|---|
| จำนวนตาราง | 17 (+3 เผื่อ R2–R3) | 10 |
| ชนิดข้อมูล | PostgreSQL (ตรง stack) | MySQL / MariaDB (`BigInt(20)`, `TinyInt(1)`) |
| ชนิด PK | UUID (ตรง API v1.0) | BIGINT |
| ตั้งชื่อ | snake_case ตามกลุ่ม | นำหน้า MS_ / TR_ |
| ความสัมพันธ์ในภาพ | crow's foot | ลูกศร + ป้าย "1 : N" |
| รองรับหน้าจอ R1 | ครบ 9/9 | ครบ 3/9 · บางส่วน 1 · ไม่รองรับ 5 |

## เทียบตารางทีละตัว

| Opus | Sonnet | ต่างกันอย่างไร |
|---|---|---|
| users | MS_Admin | Sonnet มีแค่ admin/เจ้าหน้าที่ ไม่มีรหัสผ่าน ไม่มี role |
| student_profiles | MS_Student | Sonnet แยกจาก admin ทั้งหมด · มี Title, First/Last name, Faculty, Entry_Year |
| face_embeddings | MS_Student_Picture | Sonnet เก็บ `Picture_URL` (รูป) · Opus เก็บ embedding เข้ารหัส |
| consent_records | MS_Student_Register | Sonnet มีแค่ธง Registered + วันที่ |
| courses | MS_Courses | ใกล้เคียงกัน |
| sections + section_class_days + semesters | MS_Section | Sonnet เรียนได้ 1 วันต่อสัปดาห์ · ห้องเป็นข้อความ |
| enrollments | MS_Enrollment | Opus มี status / dropped_at |
| class_sessions | MS_Courses_Session | Sonnet มี `Session_No` · Opus มีสถานะ ห้อง ช่วงเช็คชื่อ |
| คอลัมน์ใน sections | MS_Attendance_Time | Sonnet ผูกกฎที่วิชา 1:1 · Opus ผูกที่กลุ่มเรียน |
| attendance_records | TR_Attendance | Sonnet ไม่มี source / ผู้บันทึก / เหตุผล · มีสถานะ "ลา" |
| rooms, cameras, section_lecturers, consent_versions, data_deletion_jobs, audit_logs | — | Sonnet ไม่มี |

## จุดผิดพลาดของแบบ Sonnet (แก้แล้วในแบบ Opus)

| # | จุดที่ผิด | ระดับ | แก้ด้วย |
|---|---|---|---|
| S1 | `Picture_URL` เก็บรูปใบหน้า — ขัดข้อความยินยอม v1 | ร้ายแรง | face_embeddings (ไม่มีคอลัมน์รูป) |
| S2 | ไม่มีที่เก็บ embedding — AI ไม่มีอะไรให้เทียบ | ร้ายแรง | face_embeddings |
| S3 | ไม่มีหลักฐานความยินยอมและการถอน | ร้ายแรง | consent_versions, consent_records, data_deletion_jobs |
| S4 | ไม่มีอาจารย์ รหัสผ่าน role | ร้ายแรง | users, section_lecturers |
| S5 | ห้องเป็นข้อความ ไม่มีกล้อง | สูง | rooms, cameras |
| S6 | ไม่มีช่วงเช็คชื่อและสวิตช์กล้อง | สูง | sections.check_in_*, camera_attendance_enabled |
| S7 | PK เป็น BIGINT แต่ API ใช้ UUID | สูง | UUID |
| S8 | ชนิดข้อมูลของ MySQL | กลาง | BOOLEAN, SMALLINT, TIMESTAMPTZ |
| S9 | `Day_Of_Week` ค่าเดียว | กลาง | section_class_days |
| S10 | ไม่แยก camera / manual, ไม่มี UNIQUE (session, student) | กลาง | attendance_records.source + UNIQUE |
| S11 | กฎเวลาผูกที่วิชา | ต่ำ | on_time_minutes, late_until ใน sections |
| S12 | ER ใช้ลูกศร ไม่ใช่สัญลักษณ์มาตรฐาน | ต่ำ | crow's foot |

## จุดผิดพลาดของแบบ Opus (แก้แล้ว)

| # | จุดที่ผิด | แก้เป็น |
|---|---|---|
| O1 | `year_level` ต้องอัปเดตทุกปี | `entry_year` |
| O2 | `full_name` ช่องเดียว | `title`, `first_name`, `last_name` |
| O3 | ไม่มีคณะ | `student_profiles.faculty` |
| O4 | ไม่มีเลขคาบ | `class_sessions.session_no` + UNIQUE |

## ยังต้องตัดสินใจ

- [ ] สถานะ "ลา" (`excused`) — ต้องตกลงว่านับใน attendanceRate และกลุ่มเสี่ยงอย่างไร
- [ ] ตั้งชื่อตารางแบบ MS_ / TR_ หรือ snake_case ตรงๆ
- [ ] เก็บตาราง semesters หรือย้ายปี/เทอมไปไว้ใน sections
- [ ] 1 คนหลาย role (ต้องมี user_roles)
- [ ] FigJam ของ Sonnet: เก็บเป็นฉบับเดิม หรือวาดทับด้วยแบบที่รวมแล้ว
