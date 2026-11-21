# devops

Deploy บน Faculty VM ด้วย **K3s** · 2 namespace: `prod` → `http://cp26ms3.sit.kmutt.ac.th/prod/` และ `uat` → `http://cp26ms3.sit.kmutt.ac.th/uat/`

โดเมน `cp26ms3.sit.kmutt.ac.th` ชี้ไปที่ VM `10.4.56.69` (DNS ของคณะ) · Ingress ไม่ผูก host จึงเข้าด้วย IP ได้เหมือนกัน

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

## ข้อมูล VM

| | |
|---|---|
| host | `cp26ms3.sit.kmutt.ac.th` / `10.4.56.69` |
| K3s | v1.37.2+k3s1 · node `cp26ms3` · Traefik รับ port 80 |
| user ที่ deploy | `adminstudent` · repo ที่ `~/Capstone` |
| uat | http://cp26ms3.sit.kmutt.ac.th/uat/ |
| prod | http://cp26ms3.sit.kmutt.ac.th/prod/ (ยังไม่ deploy) |

## ประวัติการ deploy

| วันที่ | namespace | commit | ผล | หมายเหตุ |
|---|---|---|---|---|
| 2026-11-22 | uat | `d0d3dd8` (main) | ✅ ผู้ใช้ทดสอบผ่าน browser แล้ว | ข้อมูล mock (`--seed`) · ยังไม่มีโมเดล AI (ai-service ไม่ขึ้น), MediaMTX และ HTTPS · ใช้โดเมนตั้งแต่ `32ce386` |

เพิ่มแถวใหม่ทุกครั้งที่ deploy (ใหม่อยู่บน) — ห้ามใส่ค่าความลับในตารางนี้

## เตรียม VM (ครั้งเดียว · ต้องใช้รหัส sudo — คนรันเอง)

```bash
# kubectl ใช้ได้โดยไม่ต้อง sudo (ไฟล์เดิม root อ่านได้คนเดียว)
mkdir -p ~/.kube && sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config \
  && sudo chown $(id -u):$(id -g) ~/.kube/config && chmod 600 ~/.kube/config
echo 'export KUBECONFIG=$HOME/.kube/config' >> ~/.bashrc

# docker ใช้ได้โดยไม่ต้อง sudo (group docker = สิทธิ์เท่า root) แล้ว logout / SSH เข้าใหม่
sudo usermod -aG docker "$USER"
```

## Deploy ครั้งแรก (บน VM)

```bash
# 0) โมเดลของ AI Service (ไม่อยู่ใน git) — ไม่มี = pod ai-service start ไม่ขึ้น ส่วนอื่นยังใช้ได้
sudo mkdir -p /opt/bangmod/models   # ใส่ yolov8n-face-pose.pt และ face_recognition_sface_2021dec.onnx

# 1) build image แล้ว import เข้า K3s (ขั้น import ถามรหัส sudo · สคริปต์ตรวจว่าเข้าครบ 4 ตัวให้)
./devops/k8s/build-images.sh
#    ใส่รหัส sudo ผิด → import อย่างเดียวโดยไม่ build ใหม่:  IMPORT_ONLY=1 ./devops/k8s/build-images.sh
#    ให้ Claude build แล้วคนรัน import เอง:  NO_IMPORT=1 ... แล้ว IMPORT_ONLY=1 ...

# 2) ความลับ (แยกค่าระหว่าง prod กับ uat)
cp devops/k8s/overlays/uat/secret.env.example devops/k8s/overlays/uat/secret.env
#    แก้ค่าใน secret.env (MEDIA_BASE_URL ตั้งเป็น http://cp26ms3.sit.kmutt.ac.th:8889 ใน overlays แล้ว ไม่ต้องแก้)

# 3) apply + ใส่ schema
kubectl apply -k devops/k8s/overlays/uat
./devops/k8s/init-db.sh uat --seed        # prod: ./devops/k8s/init-db.sh prod (ไม่มี --seed)

# 4) ตรวจ
kubectl -n uat get pods
curl http://cp26ms3.sit.kmutt.ac.th/uat/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@sit.kmutt.ac.th","password":"Password@123"}'
```

อัปเดตเวอร์ชัน: `./devops/k8s/build-images.sh` แล้ว `kubectl -n <ns> rollout restart deployment/backend deployment/frontend`

## ปัญหาที่เจอตอน deploy และวิธีแก้

| อาการ | สาเหตุ | แก้ |
|---|---|---|
| `Unable to read /etc/rancher/k3s/k3s.yaml` / `permission denied` | ไฟล์ kubeconfig ของ K3s root อ่านได้คนเดียว | ทำ "เตรียม VM" ข้อ kubeconfig |
| `permission denied ... /var/run/docker.sock` | user ไม่อยู่ใน group docker | `sudo usermod -aG docker $USER` แล้ว login ใหม่ หรือ `sudo ./devops/k8s/build-images.sh` |
| pod ขึ้น `ErrImageNeverPull` (เดิม `ErrImagePull` / `pull access denied` จาก docker.io) | image ยังไม่ได้ import เข้า K3s (K3s ไม่ใช้ image ของ docker) | `sudo k3s ctr -n k8s.io images ls -q \| grep bangmod` ต้องเห็น 4 ตัว · ไม่ครบ → `IMPORT_ONLY=1 ./devops/k8s/build-images.sh` แล้ว `kubectl -n <ns> rollout restart deployment/backend deployment/frontend` |
| `sudo: 3 incorrect password attempts` | รหัสผิด — ใช้รหัสของ user ที่ login VM · แป้นพิมพ์ค้างภาษาไทย · จอไม่แสดงตัวอักษรตอนพิมพ์ | พิมพ์ใหม่ด้วยแป้นภาษาอังกฤษ แล้วใช้ `IMPORT_ONLY=1` (ไม่ต้อง build ใหม่) |
| pip เตือน `Running pip as the 'root' user` ตอน build ai-service | เป็นคำเตือนใน container ตอน build ไม่ใช่บน VM | ไม่ต้องทำอะไร |
| Claude Code บน VM ใช้ sudo ไม่ได้ | sudo ถามรหัสผ่าน (ตั้งใจ — Claude ไม่ควรถือรหัส) | ขั้นที่ใช้ sudo ให้คนรันเอง แล้วบอก Claude ให้ทำต่อ |

## ข้อควรรู้

- **prod ไม่มีข้อมูลเริ่มต้น** (ไม่มี seed) ต้องเพิ่มเองก่อนใช้: บัญชี admin (`users`), ภาคเรียน (`semesters`), ข้อความยินยอม (`consent_versions` ที่ `is_current = true`)
- `FACE_EMBEDDING_KEY` ห้ามเปลี่ยนหลังมีข้อมูลใบหน้า (ถอดรหัส embedding เดิมไม่ได้) · **สำรอง `secret.env` ของแต่ละ namespace ไว้นอก VM** (เช่น password manager ของทีม) ห้ามใส่ใน git หรือ README
- backend ต้องมี replica เดียว (SSE อยู่ในหน่วยความจำ) · Deployment ตั้ง `strategy: Recreate` ไว้แล้ว
- ไม่มี MediaMTX ใน manifest ชุดนี้ · ภาพสด (B2) ใช้ `MEDIA_BASE_URL` ชี้ไป MediaMTX ที่ติดตั้งแยก
- ไม่มี HTTPS ใน manifest ชุดนี้ (Traefik รับ HTTP) — กล้องในเบราว์เซอร์ (หน้าลงทะเบียนใบหน้า) ต้องใช้ HTTPS ยกเว้น localhost

## บันทึกการทำงาน (Changelog)

### 2026-11-22 — บันทึก deploy uat ครั้งแรก + แก้ปัญหาที่เจอ
- **ทำอะไร:**
  - เพิ่มหัวข้อ "ข้อมูล VM", "ประวัติการ deploy" (uat 2026-11-22 สำเร็จ ผู้ใช้ทดสอบเอง), "เตรียม VM" (kubeconfig, group docker) และ "ปัญหาที่เจอตอน deploy และวิธีแก้"
  - `build-images.sh`: เช็คสิทธิ์ docker ตั้งแต่ต้น (หยุดพร้อมวิธีแก้), โหมด `IMPORT_ONLY=1` (import ใหม่โดยไม่ build), import ผ่านไฟล์ชั่วคราวลง namespace `k8s.io` แล้วตรวจว่าเข้า K3s ครบ 4 image
  - `base/{backend,frontend,ai-service}.yaml`: `imagePullPolicy: Never` — ลืม import จะขึ้น `ErrImageNeverPull` แทนการไปดึงจาก docker.io แล้วได้ `pull access denied`
  - เน้นให้สำรอง `secret.env` ไว้นอก VM
- **ไฟล์ที่เกี่ยวข้อง:** `README.md`, `k8s/build-images.sh`, `k8s/base/backend.yaml`, `k8s/base/frontend.yaml`, `k8s/base/ai-service.yaml`, `../CLAUDE.md`
- **เหตุผล:** deploy uat ครั้งแรกติด 4 จุดที่ README ไม่ได้บอก (kubeconfig อ่านไม่ได้, docker permission denied, image ไม่ได้ import → ErrImagePull, ใส่รหัส sudo ผิดแล้วต้อง build ใหม่)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** `build-images.sh` ฉบับใหม่ยังไม่ได้รันจริงบน VM (เครื่องที่แก้ไม่มี docker / K3s — ตรวจ syntax และ logic การเช็ค image แล้ว) · `imagePullPolicy` มีผลเมื่อ `kubectl apply -k` ครั้งถัดไป · ยังไม่มีโมเดล AI, MediaMTX, HTTPS และ prod

### 2026-11-22 — ใช้โดเมน `cp26ms3.sit.kmutt.ac.th` แทน IP
- **ทำอะไร:** ตั้ง `MEDIA_BASE_URL=http://cp26ms3.sit.kmutt.ac.th:8889` ใน overlay `prod` และ `uat` (เดิมเป็น `CHANGE-ME-VM-HOST` ที่ต้องแก้เองบน VM) · เปลี่ยนตัวอย่าง URL ใน README, คอมเมนต์ overlay และรายการตรวจใน `CLAUDE.md` เป็นโดเมน
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/overlays/prod/kustomization.yaml`, `k8s/overlays/uat/kustomization.yaml`, `README.md`, `../CLAUDE.md`
- **เหตุผล:** DNS ของคณะชี้ `cp26ms3.sit.kmutt.ac.th` ไปที่ VM `10.4.56.69` แล้ว ใช้โดเมนแทน IP และ VM ไม่ต้องมีไฟล์ที่แก้ค้างไว้โดยไม่ commit
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** Ingress ยังไม่ผูก host (เข้าด้วย IP ได้ด้วย) · ยังเป็น HTTP — หน้าลงทะเบียนใบหน้าต้องใช้ HTTPS (ต้องขอ certificate ของ `cp26ms3.sit.kmutt.ac.th` จากคณะ)

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
