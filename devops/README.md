# devops

Deploy บน Faculty VM ด้วย **K3s** · 2 namespace: `prod` → `http://<VM>/prod/` และ `uat` → `http://<VM>/uat/`

```
Browser ──▶ Traefik (Ingress K3s) ──/prod/*──▶ frontend (NGINX)
                                                 ├─ /prod/api/*  ──▶ backend:8081 /api/*  ──▶ postgres:5432
                                                 └─ อื่น ๆ        ──▶ ไฟล์ Vue
AI Service ──/api/internal/** (X-Internal-Key)──▶ backend   (ภายใน cluster เท่านั้น)
backend ──DELETE /students/{id}/embeddings──▶ ai-service:8080
```

FE เรียก API แบบ relative (`/prod/api`) แล้ว NGINX ใน pod frontend ส่งต่อไป backend จึงไม่ต้องตั้ง CORS
และเครื่อง local ใช้แบบเดียวกันผ่าน Vite proxy (ดู `frontend/README.md`)

## ไฟล์

| ไฟล์ | ทำอะไร |
|---|---|
| `k8s/base/` | postgres (StatefulSet + PVC 5Gi), backend, frontend, ai-service, Ingress |
| `k8s/overlays/prod`, `k8s/overlays/uat` | namespace, path ของ Ingress, tag image FE, ConfigMap `app-config`, Secret `app-secret` |
| `k8s/overlays/*/secret.env.example` | แบบไฟล์ความลับ → copy เป็น `secret.env` (อยู่ใน `.gitignore`) |
| `k8s/build-images.sh` | build image ทั้ง 4 ตัวแล้ว import เข้า K3s (ไม่ต้องมี registry) |
| `k8s/init-db.sh` | ใส่ schema ครั้งแรก (`uat --seed` = + ข้อมูล mock) |

## Deploy ครั้งแรก (บน VM)

```bash
# 0) โมเดลของ AI Service (ไม่อยู่ใน git) — ไม่มี = pod ai-service start ไม่ขึ้น ส่วนอื่นยังใช้ได้
sudo mkdir -p /opt/bangmod/models   # ใส่ yolov8n-face-pose.pt และ face_recognition_sface_2021dec.onnx

# 1) build image แล้ว import เข้า K3s
./devops/k8s/build-images.sh

# 2) ความลับ (แยกค่าระหว่าง prod กับ uat)
cp devops/k8s/overlays/uat/secret.env.example devops/k8s/overlays/uat/secret.env
#    แก้ค่าใน secret.env · แก้ MEDIA_BASE_URL ใน overlays/uat/kustomization.yaml เป็น host ของ VM

# 3) apply + ใส่ schema
kubectl apply -k devops/k8s/overlays/uat
./devops/k8s/init-db.sh uat --seed        # prod: ./devops/k8s/init-db.sh prod (ไม่มี --seed)

# 4) ตรวจ
kubectl -n uat get pods
curl http://<VM>/uat/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@sit.kmutt.ac.th","password":"Password@123"}'
```

อัปเดตเวอร์ชัน: `./devops/k8s/build-images.sh` แล้ว `kubectl -n <ns> rollout restart deployment/backend deployment/frontend`

## ข้อควรรู้

- **prod ไม่มีข้อมูลเริ่มต้น** (ไม่มี seed) ต้องเพิ่มเองก่อนใช้: บัญชี admin (`users`), ภาคเรียน (`semesters`), ข้อความยินยอม (`consent_versions` ที่ `is_current = true`)
- `FACE_EMBEDDING_KEY` ห้ามเปลี่ยนหลังมีข้อมูลใบหน้า (ถอดรหัส embedding เดิมไม่ได้)
- backend ต้องมี replica เดียว (SSE อยู่ในหน่วยความจำ) · Deployment ตั้ง `strategy: Recreate` ไว้แล้ว
- ไม่มี MediaMTX ใน manifest ชุดนี้ · ภาพสด (B2) ใช้ `MEDIA_BASE_URL` ชี้ไป MediaMTX ที่ติดตั้งแยก
- ไม่มี HTTPS ใน manifest ชุดนี้ (Traefik รับ HTTP) — กล้องในเบราว์เซอร์ (หน้าลงทะเบียนใบหน้า) ต้องใช้ HTTPS ยกเว้น localhost

## บันทึกการทำงาน (Changelog)

### 2026-11-22 — บังคับโปรไฟล์ `prod` บน server
- **ทำอะไร:** เพิ่ม `SPRING_PROFILES_ACTIVE=prod` ใน ConfigMap `app-config` ของ overlay `prod` และ `uat` (image ของ backend ก็ตั้งค่านี้เป็นค่าเริ่มต้น)
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/overlays/prod/kustomization.yaml`, `k8s/overlays/uat/kustomization.yaml`
- **เหตุผล:** backend ใช้โปรไฟล์ `local` (key ทดสอบที่อยู่ใน git) เมื่อไม่ได้ตั้งโปรไฟล์ — บน server ต้องไม่ใช้
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** `secret.env` ต้องมี `JWT_SECRET` และ `FACE_EMBEDDING_KEY` เสมอ ไม่งั้น backend start ไม่ขึ้น

### 2026-11-21 — K3s manifests prod / uat + สคริปต์ deploy
- **ทำอะไร:** เพิ่ม Kustomize `k8s/base` (postgres, backend, frontend, ai-service, Ingress) และ `k8s/overlays/{prod,uat}`, สคริปต์ `build-images.sh` / `init-db.sh` และแบบไฟล์ความลับ `secret.env.example`
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/**`, `../.gitignore`
- **เหตุผล:** เชื่อม FE ↔ BE บน VM แบบเดียวกับ local (FE เรียก `/api` แบบ relative แล้ว NGINX proxy ไป backend)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ยังไม่ได้ apply บน VM จริง (เครื่องที่เขียนไม่มี kubectl / docker) — ตรวจแค่ YAML · ยังไม่มี MediaMTX, HTTPS และข้อมูลเริ่มต้นของ prod
