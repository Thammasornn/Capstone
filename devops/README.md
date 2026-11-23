# devops

Deploy บน Faculty VM ด้วย **K3s** · 2 namespace: `prod` → `http://cp26ms3.sit.kmutt.ac.th/prod/` และ `uat` → `http://cp26ms3.sit.kmutt.ac.th/uat/` · เปิดใน browser ผ่าน HTTPS ของคณะ: `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/uat/` (ดู "forward จาก bscit")

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
| `cd/deploy.sh` | **CD**: build → push เข้า registry ในเครื่อง → apply → ตรวจผล → rollback ถ้าพัง |
| `cd/setup.sh` | ติดตั้ง CD บน VM ครั้งแรก (registry, clone แยก, cron) |
| `cd/registries.yaml` | ให้ K3s ดึง image จาก `localhost:5000` (copy ไป `/etc/rancher/k3s/` ด้วย sudo) |

## CD: deploy อัตโนมัติ

**push ขึ้น `Dev` → uat อัปเดตเองภายใน ~3 นาที + เวลา build** · prod ต้องสั่งเองเสมอ

```
cron ทุก 3 นาที → ~/bangmod-cd/bin/deploy.sh uat --if-changed
  └ origin/Dev มี commit ใหม่? ─ไม่─▶ จบเงียบ ๆ
        └ใช่─▶ แก้ backend/db/? ─ใช่─▶ หยุด รอคนอัปเดต DB (blocked)
                 └ไม่─▶ build image (tag = commit) → push localhost:5000 → kubectl apply
                          → รอ backend/frontend → ตรวจ (หน้าเว็บ 200, /api/me 401, /api/internal 404, ไม่ใช้โปรไฟล์ local)
                          (ตรวจซ้ำได้ ~90 วินาที)
                          ─ผ่าน─▶ ok   ─ไม่ผ่าน─▶ เก็บ pod + log backend ลง log → กลับไปใช้ image ของ deploy ที่สำเร็จครั้งล่าสุด → failed
                                                 (ยังไม่เคยสำเร็จผ่าน CD = ไม่ rollback ปล่อยเวอร์ชันใหม่ไว้)
```

- VM **ดึงเอง** (ไม่ใช้ GitHub Actions runner) — repo เป็น public จึงไม่เปิดให้ GitHub สั่งรันโค้ดบน VM
- image อยู่ใน registry ในเครื่อง (`docker` container `bangmod-registry` ฟังเฉพาะ `127.0.0.1:5000`) → **ไม่ต้องใช้ sudo** ตอน deploy · tag = commit จึงย้อนเวอร์ชันได้
- commit ที่ deploy ไม่ผ่าน / ถูกกัน จะไม่ถูกลองซ้ำทุก 3 นาที — แก้แล้ว push commit ใหม่ หรือสั่ง `deploy.sh uat` เอง
- **ไม่แตะฐานข้อมูล** — commit ที่แก้ `backend/db/` ต้องอัปเดต DB เอง แล้ว `ALLOW_DB_CHANGE=1 ~/bangmod-cd/bin/deploy.sh uat`
- สคริปต์อัปเดตตัวเองเป็นเวอร์ชันของ commit ที่ deploy uat ผ่าน

### ติดตั้ง (ครั้งเดียว · หลัง deploy uat ด้วยมือสำเร็จแล้ว)

```bash
cd ~/Capstone && git pull && ./devops/cd/setup.sh       # ไม่ต้อง sudo · ทำ registry, clone แยก, cron
# setup.sh จะพิมพ์คำสั่ง sudo สำหรับ registries.yaml ของ K3s — รันเอง (k3s restart ไม่ทำให้ pod หยุด)
~/bangmod-cd/bin/deploy.sh uat                          # deploy ครั้งแรกด้วยมือเพื่อดูผล
```

| งาน | คำสั่ง |
|---|---|
| ดูสถานะล่าสุด | `~/bangmod-cd/bin/deploy.sh status` |
| ดู log | `ls -t ~/bangmod-cd/logs/` แล้ว `less ~/bangmod-cd/logs/<ไฟล์>` |
| deploy uat เดี๋ยวนี้ (แม้ commit เดิม / เคยพัง) | `~/bangmod-cd/bin/deploy.sh uat` |
| deploy prod (main ล่าสุด หรือระบุ commit) | `~/bangmod-cd/bin/deploy.sh prod` · `... prod <commit>` — ต้องพิมพ์ `prod` ยืนยัน |
| ย้อนเวอร์ชัน | `kubectl -n <ns> rollout undo deployment/backend deployment/frontend` หรือ `deploy.sh <ns> <commit เก่า>` |
| ปิด CD ชั่วคราว | `crontab -e` แล้วใส่ `#` หน้าบรรทัด `bangmod CD` |

ไฟล์บน VM: `~/bangmod-cd/` — `repo/` (clone แยก), `bin/deploy.sh`, `secrets/<env>.env` (chmod 600 · สำรองไว้นอก VM), `config.env` (`PUBLIC_URL`, ไม่มีความลับ), `state/`, `logs/`, `registry/` (ข้อมูล image)

> เมื่อเปิด CD แล้ว **อย่า `kubectl apply -k devops/k8s/overlays/<ns>` ตรง ๆ** — จะเปลี่ยน image กลับเป็นตัวที่ import เอง (`imagePullPolicy: Never`) ให้ใช้ `deploy.sh` แทน
> registry โตขึ้นทุก commit (layer ที่ซ้ำไม่กินที่เพิ่ม) · ดูขนาด: `du -sh ~/bangmod-cd/registry` · ถ้าดิสก์ใกล้เต็ม ล้าง registry ทั้งหมดแล้ว deploy ใหม่
> (pod ที่รันอยู่ไม่กระทบ แต่จะย้อนไป commit เก่าด้วย `deploy.sh <ns> <commit>` ไม่ได้จนกว่าจะ build ใหม่):
> `docker rm -f bangmod-registry && rm -rf ~/bangmod-cd/registry/* && ./devops/cd/setup.sh && ~/bangmod-cd/bin/deploy.sh uat`

## ข้อมูล VM

| | |
|---|---|
| host | `cp26ms3.sit.kmutt.ac.th` / `10.4.56.69` |
| K3s | v1.37.2+k3s1 · node `cp26ms3` · Traefik รับ port 80 |
| user ที่ deploy | `adminstudent` · repo ที่ `~/Capstone` |
| uat | https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/uat/ (browser) · http://cp26ms3.sit.kmutt.ac.th/uat/api/... (เรียก API ตรง) |
| prod | http://cp26ms3.sit.kmutt.ac.th/prod/ (ยังไม่ deploy) |

## forward จาก bscit (HTTPS ของคณะ)

NGINX ของคณะที่ `bscit.sit.kmutt.ac.th` (เราแก้เองไม่ได้) ส่ง `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/<x>` มาที่ VM เป็น `http://<VM>/<x>` — **ตัด `/capstone26/cp26ms3` ออก** (ตรวจ 2026-11-24) · HTTPS จบที่ bscit จึงใช้กล้องสดในหน้าลงทะเบียนใบหน้าได้

| | เบราว์เซอร์ | มาถึง VM |
|---|---|---|
| uat | `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/uat/` | `/uat/` |
| prod (ยังไม่ deploy) | `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/prod/` | `/prod/` |

- `frontend/.env.<mode>`: `VITE_BASE` = path ที่เบราว์เซอร์เห็น, `APP_PATH` = path ที่มาถึง VM (ไม่ใส่ = เท่ากับ `VITE_BASE`) · `APP_PATH` ต้องตรงกับ path ของ Ingress ใน `k8s/overlays/<env>` (`deploy.sh` หยุดก่อน apply ถ้าไม่ตรง)
- เปิด `http://cp26ms3.sit.kmutt.ac.th/uat/` ตรง ๆ ใน browser หน้าจะขาว (assets อ้าง `/capstone26/cp26ms3/uat/...`) — API ยังเรียกตรงได้ (`/uat/api/...`) และ CD ใช้ตรวจผล
- prod: ตอน deploy ครั้งแรก ตั้ง `VITE_BASE=/capstone26/cp26ms3/prod/`, `VITE_API_BASE_URL=/capstone26/cp26ms3/prod/api`, `APP_PATH=/prod/` ใน `.env.production`
- ภาพสดจาก MediaMTX (`MEDIA_BASE_URL` = `http://...:8889`) จะถูกเบราว์เซอร์บล็อกบนหน้า HTTPS — ต้องขอคณะ forward path ของภาพด้วยเมื่อติดตั้ง MediaMTX
- อัปโหลดรูปใบหน้า (สูงสุด 25MB) และ SSE ผ่าน NGINX ของคณะ — ถ้าได้ 413 / SSE ขาด ต้องขอคณะปรับ `client_max_body_size` / `proxy_buffering off`

## ประวัติการ deploy

| วันที่ | namespace | commit | ผล | หมายเหตุ |
|---|---|---|---|---|
| 2026-11-23 | uat | `c14d9e9` (Dev · CD) | ✅ ตรวจจากภายนอก: หน้าเว็บตัวใหม่, login ทุกบัญชีทดสอบตามที่ควร, `/api/me` 401, `/api/internal` 404 | ติดตั้ง CD ครั้งแรก · reseed 02 + 03 (users 52 · สำรองที่ `~/backups/uat-before-reseed-2026-11-23-1743.dump`) · deploy `a9c53be` รอบแรกตรวจไม่ผ่าน → rollback ทำ backend ล่มประมาณ 14 นาที → แก้ `deploy.sh` (`c14d9e9`) · โมเดล AI ติดตั้งแล้ว ai-service Running |
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
# 0) โมเดลของ AI Service (ไม่อยู่ใน git) — ดูหัวข้อ "ติดตั้งโมเดล AI" (ทำทีหลังได้ ไม่มี = ai-service ไม่ขึ้น ส่วนอื่นใช้ได้)
sudo mkdir -p /opt/bangmod/models && sudo chown $USER /opt/bangmod/models && ./devops/models/fetch-models.sh

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

อัปเดตเวอร์ชัน: ใช้ CD (หัวข้อ "CD: deploy อัตโนมัติ") · ถ้ายังไม่ได้ติดตั้ง CD: `./devops/k8s/build-images.sh` แล้ว `kubectl -n <ns> rollout restart deployment/backend deployment/frontend`

## ติดตั้งโมเดล AI (ครั้งเดียว · ใช้ร่วมกันทั้ง uat และ prod)

โมเดลไม่อยู่ใน git · pod `ai-service` อ่านจาก `/opt/bangmod/models` บน VM (hostPath) · ไม่มีโมเดล = ai-service ไม่ขึ้น และลงทะเบียนใบหน้าได้ 502 (ส่วนอื่นใช้ได้)

| ไฟล์ (ชื่อต้องตรง) | โมเดล | ที่มา · สัญญาอนุญาต | SHA-256 |
|---|---|---|---|
| `yolov8n-face-pose.pt` (6.1 MB) | YOLOv8n **PoseModel** 1 class (ใบหน้า) + **5 จุด** (ตา 2, จมูก, มุมปาก 2) · เทรนด้วย ultralytics 8.0.66 บน WIDER FACE | [derronqi/yolov8-face](https://github.com/derronqi/yolov8-face) (ไฟล์ yolov8n ใน Google Drive) · **GPL-3.0** | `d545bf1a…e626ca` |
| `face_recognition_sface_2021dec.onnx` (36.9 MB) | SFace → embedding 128 มิติ | [OpenCV Zoo](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface) · Apache-2.0 | `0ba9fbfa…c34e79` |

hash เต็มอยู่ใน `models/fetch-models.sh` · ตรวจแล้วว่าเป็น PoseModel ที่ `kpt_shape` = 5 จุด (อ่านจาก metadata ในไฟล์)
**ใช้ YOLO face แบบ detection อย่างเดียวไม่ได้** (เช่น `lindevs/yolov8-face` เป็น `DetectionModel` ไม่มีจุดบนหน้า) — `ai-service` จะ error "YOLO model ไม่มี keypoints" เพราะ SFace ต้องใช้ 5 จุดจัดหน้า (alignCrop)
ultralytics เป็น AGPL-3.0 และโมเดล YOLO face เป็น GPL-3.0 — ถ้าจะใช้งานจริงนอกโครงงาน ให้ตรวจสัญญาอนุญาตกับอาจารย์ที่ปรึกษาก่อน

```bash
# 1) สร้างโฟลเดอร์ให้ user ที่ deploy เขียนได้ (ต้องใช้รหัส sudo · คนรันเอง)
sudo mkdir -p /opt/bangmod/models && sudo chown $USER /opt/bangmod/models

# 2) ดาวน์โหลด + ตรวจ SHA-256 (ไม่ต้อง sudo · รันซ้ำได้ · hash ไม่ตรง = ไม่ใช้ไฟล์)
cd ~/Capstone && ./devops/models/fetch-models.sh

# 3) ให้ ai-service โหลดโมเดล (ทุก namespace ที่ deploy แล้ว)
kubectl -n uat rollout restart deployment/ai-service && kubectl -n uat rollout status deployment/ai-service --timeout=300s

# 4) ตรวจ
kubectl -n uat logs deployment/ai-service --tail=20             # ต้องเห็น Uvicorn running on http://0.0.0.0:8080
kubectl -n uat port-forward deployment/ai-service 18080:8080 &  # แล้ว:
curl -s localhost:18080/health && kill %1                       # {"status":"ok"}
```

ทดสอบจากหน้าเว็บ: login `69130500011@st.sit.kmutt.ac.th` (ยังไม่เคยลงทะเบียน) → ลงทะเบียนใบหน้า → **"เลือกรูปจากเครื่อง"** 3 รูป (ปุ่มเปิดกล้องใช้ไม่ได้บน HTTP) → ต้องได้ "ลงทะเบียนใบหน้าสำเร็จ" · รูปไม่ผ่าน (เบลอ / ใบหน้าเล็ก / หลายคน) จะได้ข้อความบอกเหตุผล

**ถ้าดาวน์โหลดจาก Google Drive ไม่ได้** (โควตาเต็ม / ถูกบล็อก): เปิดลิงก์ yolov8n ใน [README ของ derronqi](https://github.com/derronqi/yolov8-face) ด้วย browser → ดาวน์โหลด → `scp` ไฟล์ขึ้น VM เป็น `/opt/bangmod/models/yolov8n-face-pose.pt` → ตรวจ `sha256sum /opt/bangmod/models/yolov8n-face-pose.pt` ให้ตรงกับใน `models/fetch-models.sh` แล้วทำข้อ 3

> โมเดลชุดนี้ใช้กับ **การลงทะเบียนใบหน้า** (`POST /embedding`) · ตัวจับคู่ใบหน้าจากกล้องแบบ realtime (อ่าน RTSP → จับคู่ → ส่ง `/api/internal/.../sightings`) และ MediaMTX ยังไม่ได้ทำ

## ปัญหาที่เจอตอน deploy และวิธีแก้

| อาการ | สาเหตุ | แก้ |
|---|---|---|
| `Unable to read /etc/rancher/k3s/k3s.yaml` / `permission denied` | ไฟล์ kubeconfig ของ K3s root อ่านได้คนเดียว | ทำ "เตรียม VM" ข้อ kubeconfig |
| `permission denied ... /var/run/docker.sock` | user ไม่อยู่ใน group docker | `sudo usermod -aG docker $USER` แล้ว login ใหม่ หรือ `sudo ./devops/k8s/build-images.sh` |
| pod ขึ้น `ErrImageNeverPull` (เดิม `ErrImagePull` / `pull access denied` จาก docker.io) | image ยังไม่ได้ import เข้า K3s (K3s ไม่ใช้ image ของ docker) | `sudo k3s ctr -n k8s.io images ls -q \| grep bangmod` ต้องเห็น 4 ตัว · ไม่ครบ → `IMPORT_ONLY=1 ./devops/k8s/build-images.sh` แล้ว `kubectl -n <ns> rollout restart deployment/backend deployment/frontend` |
| `sudo: 3 incorrect password attempts` | รหัสผิด — ใช้รหัสของ user ที่ login VM · แป้นพิมพ์ค้างภาษาไทย · จอไม่แสดงตัวอักษรตอนพิมพ์ | พิมพ์ใหม่ด้วยแป้นภาษาอังกฤษ แล้วใช้ `IMPORT_ONLY=1` (ไม่ต้อง build ใหม่) |
| pip เตือน `Running pip as the 'root' user` ตอน build ai-service | เป็นคำเตือนใน container ตอน build ไม่ใช่บน VM | ไม่ต้องทำอะไร |
| pod `ai-service` ไม่ขึ้น (`CrashLoopBackOff` / mount `/opt/bangmod/models` ไม่ได้) | ยังไม่ได้ติดตั้งโมเดล หรือชื่อไฟล์ไม่ตรง | หัวข้อ "ติดตั้งโมเดล AI" · `ls -l /opt/bangmod/models` ต้องมี 2 ไฟล์ชื่อตามตาราง |
| ai-service log: `YOLO model ไม่มี keypoints (ต้องเป็นรุ่น face-pose)` | ใช้ YOLO face แบบ detection อย่างเดียว | ใช้ไฟล์ของ derronqi ตาม `models/fetch-models.sh` (ตรวจ SHA-256) |
| Claude Code บน VM ใช้ sudo ไม่ได้ | sudo ถามรหัสผ่าน (ตั้งใจ — Claude ไม่ควรถือรหัส) | ขั้นที่ใช้ sudo ให้คนรันเอง แล้วบอก Claude ให้ทำต่อ |
| `deploy.sh status`: `/uat/api/me ไม่ได้ 401 (backend ไม่ตอบ) (rollback แล้ว)` แล้ว backend `ImagePullBackOff` · `docker.io/library/bangmod-backend:latest` `pull access denied` (2026-11-23) | deploy ผ่าน CD ครั้งแรก: ตรวจ `/api/me` ครั้งเดียวไม่ลองซ้ำ แล้ว `rollout undo` กลับไป Deployment เดิมที่ใช้ image import เอง (`:latest` ไม่มี `imagePullPolicy` → K3s ไปดึงจาก docker.io) | `deploy.sh` ฉบับ 2026-11-23 ตรวจซ้ำ ~90 วินาที และครั้งแรกไม่ rollback · อัปเดตสคริปต์แล้ว deploy ใหม่: `cd ~/Capstone && git pull && ./devops/cd/setup.sh && ~/bangmod-cd/bin/deploy.sh uat` (setup.sh รันซ้ำได้ — copy `deploy.sh` ล่าสุดไป `~/bangmod-cd/bin/`) |

## ข้อควรรู้

- **prod ไม่มีข้อมูลเริ่มต้น** (ไม่มี seed) ต้องเพิ่มเองก่อนใช้: บัญชี admin (`users`), ภาคเรียน (`semesters`), ข้อความยินยอม (`consent_versions` ที่ `is_current = true`)
- `FACE_EMBEDDING_KEY` ห้ามเปลี่ยนหลังมีข้อมูลใบหน้า (ถอดรหัส embedding เดิมไม่ได้) · **สำรอง `secret.env` ของแต่ละ namespace ไว้นอก VM** (เช่น password manager ของทีม) ห้ามใส่ใน git หรือ README
- backend ต้องมี replica เดียว (SSE อยู่ในหน่วยความจำ) · Deployment ตั้ง `strategy: Recreate` ไว้แล้ว
- ไม่มี MediaMTX ใน manifest ชุดนี้ · ภาพสด (B2) ใช้ `MEDIA_BASE_URL` ชี้ไป MediaMTX ที่ติดตั้งแยก
- ไม่มี HTTPS ใน manifest ชุดนี้ (Traefik รับ HTTP) — กล้องในเบราว์เซอร์ (หน้าลงทะเบียนใบหน้า) ต้องใช้ HTTPS ยกเว้น localhost

## บันทึกการทำงาน (Changelog)

### 2026-11-24 — uat เปิดที่ `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/uat/`
- **ทำอะไร:** Ingress ของ uat กลับเป็น `/uat` · `.env.uat` ตั้ง `VITE_BASE=/capstone26/cp26ms3/uat/` + `APP_PATH=/uat/` · เขียนหัวข้อ "forward จาก bscit" ใหม่ตามของจริง · อัปเดต URL ในเอกสารและกฎข้อ 15 ใน `CLAUDE.md`
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/overlays/uat/kustomization.yaml`, `k8s/base/ingress.yaml`, `README.md`, `../frontend/.env.uat`, `../CLAUDE.md`
- **เหตุผล:** คณะตั้ง forward ไว้แล้วที่ `/capstone26/cp26ms3/` และตัด prefix ก่อนส่งมา VM (ไม่ใช่แบบไม่ตัดที่เตรียมไว้ใน commit ก่อน) · `/capstone26/uat/` ที่ย้ายไปก่อนหน้าจึงไม่ใช้แล้ว
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** เปิดตรงที่ VM ใน browser หน้าจะขาว ต้องเปิดผ่าน bscit · ยังไม่ได้ทดสอบอัปโหลดรูปใบหน้าและ SSE ผ่าน bscit

### 2026-11-24 — CD รองรับ `APP_PATH` (path บน VM ต่างจาก path ที่เบราว์เซอร์เห็น)
- **ทำอะไร:** `cd/deploy.sh` ใช้ `APP_PATH` (ไม่มี = `VITE_BASE`) จาก `frontend/.env.<mode>` สำหรับตรวจ Ingress และตรวจผลหลัง deploy · log บอกทั้ง 2 path
- **ไฟล์ที่เกี่ยวข้อง:** `cd/deploy.sh`, `../frontend/Dockerfile`
- **เหตุผล:** bscit ตัด prefix `/capstone26/cp26ms3` ก่อนส่งมา VM (ตรวจแล้ว: `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/capstone26/uat/` ได้หน้าเว็บของเรา) · push ก่อนเปลี่ยน path เพื่อให้สคริปต์บน VM อัปเดตตัวเอง
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ยังไม่เปลี่ยนพฤติกรรม (ยังไม่มี `APP_PATH` ใน `.env`)

### 2026-11-24 — ย้าย uat ไป `/capstone26/uat/` (รับ forward จาก bscit)
- **ทำอะไร:** Ingress ของ uat เป็น `/capstone26/uat` · เพิ่มหัวข้อ "forward จาก bscit" (ตัวอย่าง NGINX ที่ต้องขอคณะ) · อัปเดต URL ในเอกสารและกฎข้อ 15 ใน `CLAUDE.md`
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/overlays/uat/kustomization.yaml`, `k8s/base/ingress.yaml`, `README.md`, `../frontend/.env.uat`, `../CLAUDE.md`
- **เหตุผล:** ให้เปิด uat จาก `https://bscit.sit.kmutt.ac.th/capstone26/uat/` ได้ (HTTPS ของคณะ → กล้องสดใช้ได้) · path ใน VM ตรงกับ path ภายนอก จึงไม่ต้องตัด prefix
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** URL เดิม `http://cp26ms3.sit.kmutt.ac.th/uat/` ใช้ไม่ได้แล้ว (404) · ต้องขอผู้ดูแล bscit ตั้ง forward ตามตัวอย่าง — ตอนนี้ bscit ยังได้ 404 · ยังไม่รู้ว่า bscit ต่อถึง `10.4.56.69` ได้หรือไม่

### 2026-11-24 — CD อ่าน path ของเว็บจาก commit ที่ deploy
- **ทำอะไร:** `cd/deploy.sh` อ่าน `VITE_BASE` จาก `frontend/.env.<mode>` ของ commit ที่ deploy แล้วใช้ตรวจผลหลัง deploy (แทน `/<namespace>/` ที่เขียนตายตัว) · ก่อน `kubectl apply` ตรวจว่า path ของ Ingress ตรงกับ `VITE_BASE` ถ้าไม่ตรงหยุดโดยยังไม่ apply อะไร · ไม่ส่ง `APP_BASE` ตอน build frontend แล้ว (`Dockerfile` อ่านเอง) · `k8s/build-images.sh` เหมือนกัน
- **ไฟล์ที่เกี่ยวข้อง:** `cd/deploy.sh`, `k8s/build-images.sh`, `../frontend/Dockerfile`
- **เหตุผล:** เตรียมย้าย uat ไป `/capstone26/uat/` · cron รัน `deploy.sh` สำเนาใน `~/bangmod-cd/bin/` ซึ่งอัปเดตตัวเองหลัง deploy uat สำเร็จเท่านั้น จึงต้อง push ส่วนนี้ก่อน (path ยังเป็น `/uat/`) ให้ CD อัปเดตตัวเอง แล้วค่อย push commit ที่เปลี่ยน path — ไม่งั้นสคริปต์เก่าจะตรวจ `/uat/` ไม่ผ่านแล้ว rollback
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบด้วย kubectl / docker / curl จำลองบนเครื่องที่แก้ (ไม่มี K3s จริง)

### 2026-11-23 — บันทึกประวัติ deploy uat ผ่าน CD (`c14d9e9`)
- **ทำอะไร:** เพิ่มแถวในตาราง "ประวัติการ deploy" สำหรับ uat ที่ `c14d9e9` (deploy ครั้งแรกที่ผ่านผ่าน CD)
- **ไฟล์ที่เกี่ยวข้อง:** `README.md`
- **เหตุผล:** ผู้ใช้ยืนยันว่า deploy สำเร็จ และตรวจจากภายนอกแล้ว (หน้าเว็บตัวใหม่ไม่มีข้อยืนยันอายุ 20 ปี มีปุ่มกล้องมือถือและบัญชีทีม · login 16 บัญชีได้ 200 · บัญชีปิดได้ 401 · `/api/me` 401 · `/api/internal` 404) ตามกฎ CLAUDE.md ข้อ 21
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ยังไม่ทราบสาเหตุแน่ชัดที่ตรวจ `/api/me` ไม่ผ่านใน deploy `a9c53be` (น่าจะตรวจเร็วเกินไป) — ถ้าเกิดอีก `deploy.sh` จะเก็บ pods + log backend ไว้ · ยังไม่ได้ทดสอบลงทะเบียนใบหน้าบนมือถือจริง · สำรองไฟล์ dump และ `~/bangmod-cd/secrets/uat.env` ไว้นอก VM

### 2026-11-23 — แก้ CD: deploy ครั้งแรกพังแล้ว rollback ทำให้ backend ล่ม
- **ทำอะไร:** `cd/deploy.sh` (1) ตรวจผลหลัง rollout ลองซ้ำได้ถึง ~90 วินาที (`CHECK_TRIES` × 3 วินาที, curl `--max-time 10`) และบอก HTTP code ที่ได้ในข้อความ (2) ก่อน rollback เก็บ `kubectl get pods` + log backend 80 บรรทัดลง log ของรอบนั้น (pod ใหม่จะหายหลัง rollback) (3) rollback ใช้ `kubectl set image` กลับเป็น image ของ deploy ที่สำเร็จครั้งล่าสุด (`state/<env>.deployed`) แทน `rollout undo` · ถ้ายังไม่เคยสำเร็จผ่าน CD จะไม่ rollback · แผนภาพ CD + แถวใหม่ในตารางปัญหา
- **ไฟล์ที่เกี่ยวข้อง:** `cd/deploy.sh`, `README.md`
- **เหตุผล:** ติดตั้ง CD บน VM ครั้งแรก (2026-11-23) แล้ว deploy `a9c53be` ไม่ผ่านการตรวจ `/uat/api/me` (ตรวจครั้งเดียวทันทีหลัง rollout) → `rollout undo` กลับไป Deployment ที่ใช้ `bangmod-backend:latest` ซึ่ง K3s ไปดึงจาก docker.io ไม่ได้ → backend `ImagePullBackOff` · uat ใช้ API ไม่ได้
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบด้วยคำสั่งจำลอง (docker / kubectl / curl) 3 กรณี: ครั้งแรก backend ตอบช้า (000, 000 แล้ว 401) → ok · ครั้งแรก backend ไม่ตอบเลย → failed ไม่ rollback · เคยสำเร็จแล้วพัง → `set image` กลับเป็น tag ของ commit ก่อนหน้า · ยังไม่รู้แน่ว่าทำไม `/api/me` ไม่ได้ 401 ตอนนั้น (log ของ pod ใหม่หายไปกับ rollback) — รอบหน้าถ้าพังจะมี log backend ในไฟล์ log · ต้องรัน `setup.sh` ซ้ำบน VM เพื่อให้ `~/bangmod-cd/bin/deploy.sh` เป็นฉบับใหม่

### 2026-11-22 — คู่มือ + สคริปต์ติดตั้งโมเดล AI
- **ทำอะไร:** เพิ่มหัวข้อ "ติดตั้งโมเดล AI" (ที่มา, สัญญาอนุญาต, ขั้นตอน, ตรวจผล, ทางเลือกเมื่อ Google Drive ใช้ไม่ได้) และ `models/fetch-models.sh` (ดาวน์โหลดลง `/opt/bangmod/models` + ตรวจ SHA-256 · รันซ้ำได้ · hash ไม่ตรงไม่ใช้) · เพิ่ม 2 แถวในตารางปัญหา (ai-service ไม่ขึ้น, "YOLO model ไม่มี keypoints") · ขั้น 0 ของ Deploy ครั้งแรกชี้มาที่สคริปต์
- **ไฟล์ที่เกี่ยวข้อง:** `models/fetch-models.sh` (ใหม่), `README.md`
- **เหตุผล:** ai-service ยังไม่ขึ้นบน VM เพราะไม่มีโมเดล · โค้ดต้องการ YOLO face ที่มี 5 จุดบนหน้า ซึ่งไม่ใช่โมเดลมาตรฐานของ Ultralytics
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:**
  - เลือก `derronqi/yolov8-face` yolov8n (ตรวจ metadata ในไฟล์: PoseModel, 1 class, `kpt_shape` 5 จุด, ultralytics 8.0.66) · ไม่ใช้ `lindevs/yolov8-face` เพราะเป็น DetectionModel (ไม่มีจุด)
  - ทดสอบ `ai-service/main.py` จริงกับโมเดลทั้ง 2 บน ultralytics 8.4 / OpenCV 5.1: โหลดได้, ตรวจพบใบหน้าพร้อม 5 จุด, รูปหลายคนถูกปฏิเสธ, SFace จัดหน้าเป็น 112×112 → embedding 128 มิติ (คนละคน cosine 0.05), endpoint ลบข้อมูลตรวจ key ถูกต้อง · ยังไม่ได้ทดสอบรูป selfie ใบหน้าเดียวที่ผ่านเกณฑ์คุณภาพจนได้ 200 (ไม่มีรูปทดสอบที่เหมาะสม)
  - ยังไม่ได้รันบน VM · ลิงก์ Google Drive อาจเปลี่ยน / ติดโควตา (มีวิธีดาวน์โหลดเองในคู่มือ) · ไฟล์ YOLO เป็น GPL-3.0

### 2026-11-22 — CD: push `Dev` → uat อัปเดตเอง
- **ทำอะไร:** เพิ่ม `cd/deploy.sh` (cron ทุก 3 นาที: เจอ commit ใหม่บน `Dev` → build image tag = commit → push เข้า registry ในเครื่อง `localhost:5000` → `kubectl apply` ผ่าน kustomization ที่สร้างตอน deploy → รอ rollout → ตรวจหน้าเว็บ 200 / `/api/me` 401 / `/api/internal` 404 / ไม่ใช้โปรไฟล์ local → พังแล้ว `rollout undo`) · `prod` สั่งเองจาก terminal + พิมพ์ยืนยัน · commit ที่แก้ `backend/db/` หยุดรอคน (`ALLOW_DB_CHANGE=1`) · ไม่ลองซ้ำ commit ที่พัง · log / สถานะที่ `~/bangmod-cd/` · อัปเดตตัวเองหลัง uat ผ่าน · `cd/setup.sh` ติดตั้งครั้งแรก (registry container, clone แยก, secret, crontab) · `cd/registries.yaml` สำหรับ K3s
- **ไฟล์ที่เกี่ยวข้อง:** `cd/deploy.sh`, `cd/setup.sh`, `cd/registries.yaml`, `README.md`, `../CLAUDE.md`, `../.gitignore`
- **เหตุผล:** ให้ uat อัปเดตเองเมื่อ push โดยไม่ต้อง SSH / sudo ทุกครั้ง · VM อยู่ในเครือข่ายภายในและ repo เป็น public จึงให้ VM ดึงเอง (ไม่ใช้ self-hosted runner) · registry ทำให้ไม่ต้อง sudo import และย้อนเวอร์ชันได้
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบด้วยคำสั่งจำลอง (docker / kubectl / curl) + git จริง ครบ 9 กรณี (deploy ปกติ, ไม่มี commit ใหม่, กัน backend/db + ALLOW_DB_CHANGE, backend ไม่ขึ้น → rollback, โปรไฟล์ local → rollback, registry ไม่ตอบ, build พัง, prod จาก cron ถูกปฏิเสธ, setup ซ้ำไม่เพิ่ม cron) · **ยังไม่ได้รันบน VM จริง** และยังไม่ได้ทดสอบการพิมพ์ยืนยัน prod ใน terminal จริง · ต้องรัน `setup.sh` + คำสั่ง sudo ของ registries.yaml บน VM

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
