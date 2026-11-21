# ai-service

FastAPI · YOLOv8 face-pose + SFace (รายละเอียดโมเดลอยู่ต้นไฟล์ [`main.py`](main.py))

| เส้น | ใช้ทำอะไร |
|---|---|
| `GET /health` | ตรวจว่าพร้อม |
| `POST /embedding` (multipart `files`) | ตรวจภาพ + คืน embedding 128 มิติ ต่อภาพ · ภาพไม่ผ่าน = 422 `{detail}` |
| `DELETE /students/{student_id}/embeddings` | backend เรียกหลังนักศึกษาถอนความยินยอม (B7) → ลบ embedding ของคนนั้นออกจากหน่วยความจำ · ต้องแนบ `X-Internal-Key` |

## ตั้งค่า

| ตัวแปร | ใช้ทำอะไร |
|---|---|
| `INTERNAL_API_KEY` | ต้องตรงกับ `INTERNAL_API_KEY` ของ backend · ไม่ตั้ง = ปฏิเสธ `DELETE /students/...` ทุกครั้ง |
| `YOLO_FACE_MODEL`, `SFACE_MODEL` | path ของโมเดล |
| `MIN_CONF`, `MIN_FACE_PX`, `MIN_BLUR` | เกณฑ์คุณภาพภาพ |

> ตัวจับคู่ใบหน้าจากกล้องแบบ realtime ยังไม่ได้ทำ · เมื่อทำ ให้โหลด embedding เข้า `GALLERY` และส่งผลไปที่ `/api/internal/**` ของ backend

## บันทึกการทำงาน (Changelog)

### 2026-11-21 — endpoint ลบ embedding ตอนถอนความยินยอม
- **ทำอะไร:** เพิ่ม `DELETE /students/{student_id}/embeddings` (ตรวจ `X-Internal-Key` = `INTERNAL_API_KEY`) และ `GALLERY` ไว้เก็บ embedding สำหรับจับคู่ใบหน้าจากกล้อง
- **ไฟล์ที่เกี่ยวข้อง:** `main.py`, `README.md`
- **เหตุผล:** B7 — ถอนความยินยอมแล้วต้องลบข้อมูลใบหน้าออกจาก AI Service ด้วย ไม่ใช่แค่ใน DB
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ตัวจับคู่ใบหน้า realtime ยังไม่ได้ทำ เมื่อทำให้โหลด embedding เข้า `GALLERY` และส่งผลไปที่ `/api/internal/**` ของ backend
