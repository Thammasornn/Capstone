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
