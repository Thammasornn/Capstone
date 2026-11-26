# devops

Deploy บน Faculty VM ด้วย **K3s** · 2 namespace: `prod` → `http://cp26ms3.sit.kmutt.ac.th/prod/` และ `uat` → `http://cp26ms3.sit.kmutt.ac.th/uat/` · เปิดใน browser ผ่าน HTTPS ของคณะ: `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/uat/` (ดู "forward จาก bscit")

โดเมน `cp26ms3.sit.kmutt.ac.th` ชี้ไปที่ VM `10.4.56.69` (DNS ของคณะ) · Ingress ไม่ผูก host จึงเข้าด้วย IP ได้เหมือนกัน

```
Browser ──▶ Traefik (Ingress K3s) ──/prod/*──▶ frontend (NGINX)
                                                 ├─ /prod/api/*  ──▶ backend:8081 /api/*  ──▶ postgres:5432
                                                 ├─ /prod/media/* ──▶ MediaMTX (WHEP ภาพสด)
                                                 └─ อื่น ๆ        ──▶ ไฟล์ Vue
Pi Camera ──RTSPS (Tailscale)──▶ MediaMTX (namespace media) ──RTSP──▶ ai-service (ตัวอ่านกล้อง)
AI Service ──/api/internal/** (X-Internal-Key)──▶ backend   (ภายใน cluster เท่านั้น)
backend ──DELETE /students/{id}/embeddings──▶ ai-service:8080
```

FE เรียก API แบบ relative (`/prod/api`) แล้ว NGINX ใน pod frontend ส่งต่อไป backend จึงไม่ต้องใช้ CORS preflight (เครื่อง local ใช้แบบเดียวกันผ่าน Vite proxy · ดู `frontend/README.md`)
แต่ backend ยังตรวจ header `Origin` — ถ้าเบราว์เซอร์เปิดจากโดเมนอื่นที่ไม่ใช่ host ที่ backend เห็น (เช่น bscit) ต้องใส่โดเมนนั้นใน `CORS_ORIGINS` ของ overlay ไม่งั้น login / POST ได้ 403 "Invalid CORS request"

| เปิดจาก | `CORS_ORIGINS` |
|---|---|
| `http://cp26ms3.sit.kmutt.ac.th/<ns>/` ตรงที่ VM · `curl` ที่ไม่ส่ง `Origin` | ไม่ต้องตั้ง |
| bscit (`https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/<ns>/`) | `https://bscit.sit.kmutt.ac.th` · uat ตั้งแล้ว · **prod ยังเป็น `http://localhost:5173` ต้องเพิ่มเมื่อเปิด prod ผ่าน bscit** |
| HTTPS ตรงที่ VM (ถ้าเปิดใช้) | เพิ่ม `https://cp26ms3.sit.kmutt.ac.th` |

ตรวจ: `curl -H 'Origin: https://bscit.sit.kmutt.ac.th' http://cp26ms3.sit.kmutt.ac.th/uat/api/me` ต้องได้ 401 (ได้ 403 = `CORS_ORIGINS` ยังไม่ถูก)

## ไฟล์

| ไฟล์ | ทำอะไร |
|---|---|
| `k8s/base/` | postgres (StatefulSet + PVC 5Gi), backend, frontend, ai-service, Ingress |
| `k8s/overlays/prod`, `k8s/overlays/uat` | namespace, path ของ Ingress, tag image FE, ConfigMap `app-config`, Secret `app-secret` |
| `k8s/overlays/*/secret.env.example` | แบบไฟล์ความลับ → copy เป็น `secret.env` (อยู่ใน `.gitignore`) |
| `k8s/build-images.sh` | build image ทั้ง 4 ตัวแล้ว import เข้า K3s (ไม่ต้องมี registry) |
| `k8s/init-db.sh` | ใส่ schema ครั้งแรก (`uat --seed` = + ข้อมูล mock) |
| `k8s/mediamtx/` | MediaMTX (namespace `media`, ใช้ร่วม uat + prod) ดึงภาพกล้องผ่าน RTSPS ทาง Tailscale · apply เอง ไม่อยู่ใน CD · `mediamtx.yml.example` → `mediamtx.yml` (อยู่ใน `.gitignore`) |
| `k8s/sql/uat-reset-passwords.sql` | uat: ตั้งรหัสผ่านทุกบัญชีเป็น `Password@123` (ค่าที่หน้า Login ของ uat กรอกให้) · ไม่มีข้อมูล mock = หยุด |
| `k8s/sql/prod-first-admin.sql` | prod: สร้าง admin คนแรก รหัสผ่านพิมพ์ตอนรัน (ไม่อยู่ใน git / history) · คำสั่งอยู่หัวไฟล์ |
| `k8s/sql/prod-first-setup.sql` | prod: ภาคเรียน + ข้อความยินยอม (hash จาก `consent-text.js` ของ `origin/main`) + ห้องเรียน + กล้อง 4303 (ไม่บังคับ) · ไม่มีบัญชี / mock · รันซ้ำได้ · ใช้เปลี่ยน consent เป็น v2 ได้ · คำสั่งอยู่หัวไฟล์ |
| `k8s/sql/prod-add-lecturer.sql` | prod: เพิ่มอาจารย์ 1 คน รหัสผ่านพิมพ์ตอนรัน (stdin) · มีอยู่แล้ว = ไม่แก้ · คำสั่งอยู่หัวไฟล์ |
| `k8s/sql/prod-add-students.sql` | prod: สร้างบัญชีนักศึกษาจาก CSV (หัวเดียวกับ template E2) รหัสผ่านชั่วคราวชุดเดียวพิมพ์ตอนรัน · `DRY=true` ลองก่อน · แถวผิด = ไม่บันทึก · มีอยู่แล้ว = ข้าม · ไม่ลงกลุ่มเรียน · คำสั่งอยู่หัวไฟล์ |
| `cd/deploy.sh` | **CD**: build → push เข้า registry ในเครื่อง → apply → ตรวจผล → rollback ถ้าพัง |
| `cd/setup.sh` | ติดตั้ง CD บน VM ครั้งแรก (registry, clone แยก, cron) |
| `pi/mediamtx.service` | systemd unit ของ MediaMTX บน Raspberry Pi (เปิดเองตอนบูต) · ติดตั้งตามหัวข้อ "MediaMTX บน Pi เป็น systemd" |
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
| prod | https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/prod/ (browser · ยังไม่ deploy) · http://cp26ms3.sit.kmutt.ac.th/prod/api/... (เรียก API ตรง) |

## forward จาก bscit (HTTPS ของคณะ)

NGINX ของคณะที่ `bscit.sit.kmutt.ac.th` (เราแก้เองไม่ได้) ส่ง `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/<x>` มาที่ VM เป็น `http://<VM>/<x>` — **ตัด `/capstone26/cp26ms3` ออก** (ตรวจ 2026-11-24) · HTTPS จบที่ bscit จึงใช้กล้องสดในหน้าลงทะเบียนใบหน้าได้

| | เบราว์เซอร์ | มาถึง VM |
|---|---|---|
| uat | `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/uat/` | `/uat/` |
| prod (ยังไม่ deploy) | `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/prod/` | `/prod/` (bscit ส่งต่อแล้ว — ตรวจ 2026-11-26: ได้ 503 `no available server` จาก Traefik ของ VM ไม่ใช่ 404 ของ bscit) |

- `frontend/.env.<mode>`: `VITE_BASE` = path ที่เบราว์เซอร์เห็น, `APP_PATH` = path ที่มาถึง VM (ไม่ใส่ = เท่ากับ `VITE_BASE`) · `APP_PATH` ต้องตรงกับ path ของ Ingress ใน `k8s/overlays/<env>` (`deploy.sh` หยุดก่อน apply ถ้าไม่ตรง)
- เปิด `http://cp26ms3.sit.kmutt.ac.th/uat/` ตรง ๆ ใน browser หน้าจะขาว (assets อ้าง `/capstone26/cp26ms3/uat/...`) — API ยังเรียกตรงได้ (`/uat/api/...`) และ CD ใช้ตรวจผล
- prod: overlay ตั้ง `CORS_ORIGINS` / `MEDIA_BASE_URL` ของ bscit แล้ว (2026-11-26) · `frontend/.env.production` ยังต้องตั้ง `VITE_BASE=/capstone26/cp26ms3/prod/`, `VITE_API_BASE_URL=/capstone26/cp26ms3/prod/api`, `APP_PATH=/prod/` (งานของ frontend) ก่อน `deploy.sh prod`
- ภาพสด: `MEDIA_BASE_URL` = `<VITE_BASE>media` (ไม่ใส่โดเมน) → WHEP วิ่งผ่าน bscit → NGINX ของ frontend → MediaMTX จึงไม่โดนบล็อกเป็น mixed content · ตัวภาพ (UDP 8189) ยังวิ่งตรงไป IP ของ VM = ดูได้เฉพาะในคณะ / VPN
- `CORS_ORIGINS` ของ overlay ต้องมี `https://bscit.sit.kmutt.ac.th` ไม่งั้น login / POST ทุกตัวผ่าน bscit ไม่ได้ (ดูตาราง "ปัญหาที่เจอตอน deploy")
- อัปโหลดรูปใบหน้า (สูงสุด 25MB) และ SSE ผ่าน NGINX ของคณะ — ถ้าได้ 413 / SSE ขาด ต้องขอคณะปรับ `client_max_body_size` / `proxy_buffering off`

## ประวัติการ deploy

| วันที่ | namespace | commit | ผล | หมายเหตุ |
|---|---|---|---|---|
| 2026-11-24 | uat | `66c6337` (Dev · CD) | ✅ ตรวจผ่าน bscit (HTTPS) ด้วย header `Origin` แบบเบราว์เซอร์: หน้าเว็บ 200, login admin / อาจารย์ / ทีม 200, `/api/me` 401, `/api/internal` 404, Origin อื่น 403 · หน้า Login แสดง 52 บัญชีจาก DB · `isSecureContext` = true (กล้องสดใช้ได้) | เปิดที่ `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/uat/` (bscit ตัด prefix → VM `/uat/`, `VITE_BASE` + `APP_PATH`) · deploy ต่อเนื่อง 6 commit (`483fc7d` → `66c6337`) ผ่าน CD ทุกรอบ · login ผ่าน bscit ใช้ไม่ได้จนแก้ `CORS_ORIGINS` (`66c6337`) · ยังไม่ได้ทดสอบอัปโหลดรูปใบหน้า / SSE ผ่าน bscit |
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
#    แก้ค่าใน secret.env (MEDIA_BASE_URL ตั้งเป็น <VITE_BASE>media ใน overlays แล้ว เช่น /capstone26/cp26ms3/uat/media — ไม่ต้องแก้)

# 3) apply + ใส่ schema
kubectl apply -k devops/k8s/overlays/uat
./devops/k8s/init-db.sh uat --seed        # prod: ./devops/k8s/init-db.sh prod (ไม่มี --seed)

# 4) ตรวจ
kubectl -n uat get pods
curl http://cp26ms3.sit.kmutt.ac.th/uat/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@sit.kmutt.ac.th","password":"Password@123"}'
```

อัปเดตเวอร์ชัน: ใช้ CD (หัวข้อ "CD: deploy อัตโนมัติ") · ถ้ายังไม่ได้ติดตั้ง CD: `./devops/k8s/build-images.sh` แล้ว `kubectl -n <ns> rollout restart deployment/backend deployment/frontend`

## ลำดับหลัง push ชุด E2 + anti-spoofing + seed เดโม ขึ้น `Dev` (uat · ผู้ใช้รันเองบน VM ทีละขั้น · ไม่ต้อง sudo)

ชุดนี้: `7239079` (BE E2 `NEW_STUDENT_PASSWORD`), `e16d4d0` (AI anti-spoofing + โมเดลใหม่), `2eef1ff` (seed เดโม 5 วิชา — แก้ `backend/db/` → **CD หยุด (blocked)** จนกว่าจะทำข้อ d) · ทำตามลำดับ ข้อไหนไม่ผ่านให้หยุดแล้วส่ง output มา

**a) `NEW_STUDENT_PASSWORD` ลง `secrets/uat.env`** — คำสั่งในหัวข้อ "ข้อควรรู้" (ไม่แสดงค่า · ไม่เขียนทับ) · หยุดก่อนบรรทัด `deploy.sh uat` (ทำในข้อ d ทีเดียว)

**b) ~~ดาวน์โหลดโมเดล MiniFASNet~~ — ไม่ต้องทำแล้ว (เลิกใช้ 2026-11-27)** · คำสั่งด้านล่างยังใช้ตรวจ / ดาวน์โหลด YOLO + SFace ได้ (ไฟล์ที่ hash ตรงจะข้าม)
```bash
git -C ~/bangmod-cd/repo fetch -q origin && git -C ~/bangmod-cd/repo log --oneline -1 origin/Dev   # ต้องเป็น commit ล่าสุดที่ push
rm -rf /tmp/models-up && mkdir -p /tmp/models-up \
  && git -C ~/bangmod-cd/repo archive origin/Dev devops/models/fetch-models.sh | tar -x -C /tmp/models-up \
  && bash /tmp/models-up/devops/models/fetch-models.sh
ls -l /opt/bangmod/models          # ต้องมี yolov8n-face-pose.pt และ face_recognition_sface_2021dec.onnx
```

**c) reset DB uat เป็น seed เดโมชุดใหม่** — ทำตาม `backend/db/README.md` หัวข้อ "reset uat ให้เป็นข้อมูลชุดนี้" (สำรอง `pg_dump` → ล้าง schema → `init-db.sh uat --seed`) · ⚠️ ลบข้อมูล uat ทั้งหมด **รวมใบหน้าที่ลงทะเบียนไว้** · เก็บชื่อไฟล์สำรอง (`$F`) ไว้ · ข้ามบรรทัด `rollout restart` / `deploy.sh` ในหัวข้อนั้นได้ (ทำในข้อ d)

**d) deploy** (backend + ai-service รุ่นใหม่ · ใช้ secret ข้อ a · ai-service โหลดโมเดลข้อ b)
```bash
ALLOW_DB_CHANGE=1 ~/bangmod-cd/bin/deploy.sh uat
~/bangmod-cd/bin/deploy.sh status          # ok ที่ commit ล่าสุดของ Dev
```

**e) ตรวจผล**
```bash
kubectl -n uat get pods                                                                  # ทุกตัว Running
kubectl -n uat logs deploy/backend --tail=300 | grep -E 'ERROR|โปรไฟล์ local|Started'    # Started · ไม่มี ERROR / "โปรไฟล์ local"
kubectl -n uat exec deploy/backend -- sh -c 'test -n "$NEW_STUDENT_PASSWORD" && echo set || echo missing'   # set
kubectl -n uat logs deploy/ai-service | grep -E 'Uvicorn|ERROR'                        # Uvicorn running · ไม่มี ERROR
kubectl -n uat exec deploy/ai-service -- python -c "import urllib.request as u;print(u.urlopen('http://localhost:8080/cameras/status').read().decode())"   # room-4303 live เมื่อ Pi ต่ออยู่
O='Origin: https://bscit.sit.kmutt.ac.th'; B=https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/uat
curl -s -o /dev/null -w 'web %{http_code}\n' $B/                              # 200
curl -s -o /dev/null -w 'me %{http_code}\n' -H "$O" $B/api/me                  # 401
curl -s -o /dev/null -w 'internal %{http_code}\n' -X POST $B/api/internal/x    # 404
curl -s -H "$O" $B/api/test-accounts | grep -o '"email"' | wc -l               # จำนวนบัญชีตาม seed ใหม่ (backend/db/README.md)
```
แล้วใน browser (`https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/uat/`): login admin / อาจารย์ / ทีม → **ลงทะเบียนใบหน้าทีมใหม่** (ข้อ c ลบของเดิม) → หน้าห้อง 4303 เห็นภาพ → เริ่มคาบ → ยืนหน้ากล้องแล้วขึ้นเช็คชื่อ
- ย้อน DB: `pg_restore` จากไฟล์ข้อ c ตาม `backend/db/README.md`

## Deploy prod ครั้งแรก (ผ่าน CD · ผู้ใช้รันเองทุกขั้น)

ทำ **หลัง uat เดโมเสถียรและผู้ใช้ยืนยันแล้วเท่านั้น** (CLAUDE.md ข้อ 4–5) · ทุกคำสั่งรันบน VM เป็น `adminstudent` · ไม่มีขั้นไหนใช้ sudo

**0. ก่อนเริ่ม (ใน repo หลัก ไม่ใช่บน VM)**
- `deploy.sh prod` ใช้ **`origin/main`** → ต้อง merge PR `Dev` → `main` ก่อน (ผู้ใช้สั่งเปิด PR เอง) และใน `main` ต้องมี
  - overlay prod ที่ตั้ง `CORS_ORIGINS=https://bscit.sit.kmutt.ac.th` + `MEDIA_BASE_URL=/capstone26/cp26ms3/prod/media`
  - `frontend/.env.production`: `VITE_BASE=/capstone26/cp26ms3/prod/`, `APP_PATH=/prod/`, `VITE_API_BASE_URL=/capstone26/cp26ms3/prod/api` (ไม่มี `APP_PATH` = `deploy.sh` หยุดเพราะ path ไม่ตรง Ingress `/prod`)
- SQL ข้อมูลเริ่มต้นของ prod (`semesters`, `consent_versions` ที่ `is_current = true`) จาก DB — ผู้ใช้ตรวจก่อน

**1. ตรวจเครื่อง (ส่งผลให้ Claude สรุป)**
```bash
kubectl get nodes -o wide
docker info --format '{{.ServerVersion}}' && kubectl -n kube-system get pods | grep traefik
df -h / ~ && du -sh ~/bangmod-cd/registry
ls -l /opt/bangmod/models
~/bangmod-cd/bin/deploy.sh status
kubectl get ns && kubectl -n prod get all,ingress,pvc,secret 2>&1 | head -40   # bscit /prod/ ได้ 503 = อาจมี Ingress prod ค้างอยู่ → ดูก่อน ห้ามลบเอง
```

**2. ความลับของ prod (สร้างใหม่ ไม่ซ้ำ uat · ไม่แสดงค่า · ไม่เขียนทับไฟล์ที่มีอยู่)**
```bash
f=~/bangmod-cd/secrets/prod.env
if [ -e "$f" ]; then echo "มี $f อยู่แล้ว — ไม่เขียนทับ"; else
  ( umask 077; printf 'DB_PASSWORD=%s\nJWT_SECRET=%s\nFACE_EMBEDDING_KEY=%s\nINTERNAL_API_KEY=%s\n' \
      "$(openssl rand -hex 24)" "$(openssl rand -base64 48 | tr -d '\n')" "$(openssl rand -base64 32)" "$(openssl rand -hex 32)" > "$f" )
fi
chmod 600 "$f" && ls -l "$f" && cut -d= -f1 "$f"                 # แสดงแค่ชื่อ key
for k in DB_PASSWORD JWT_SECRET FACE_EMBEDDING_KEY INTERNAL_API_KEY; do   # ต้องไม่มีบรรทัด "ซ้ำ"
  [ "$(grep "^$k=" "$f")" = "$(grep "^$k=" ~/bangmod-cd/secrets/uat.env)" ] && echo "$k ซ้ำกับ uat!"; done
```
⚠️ **สำรอง `prod.env` ไว้นอก VM ทันที** (password manager ของทีม) — `FACE_EMBEDDING_KEY` หายหรือเปลี่ยนหลังมีใบหน้า = ถอดรหัส embedding เดิมไม่ได้ · `DB_PASSWORD` ถูกใช้ตอนสร้าง DB ครั้งแรก เปลี่ยนทีหลังต้องเปลี่ยนใน PostgreSQL ด้วย

**3. namespace + deploy (พิมพ์ `prod` ยืนยัน)**
```bash
kubectl apply -f ~/bangmod-cd/repo/devops/k8s/overlays/prod/namespace.yaml   # deploy.sh ต้องมี namespace ก่อน · สร้างแค่ namespace ไม่ใช่ apply -k
~/bangmod-cd/bin/deploy.sh prod                                              # build จาก origin/main → apply → ตรวจ (ครั้งแรกไม่ rollback)
```

**4. schema (ไม่มี `--seed` · ห้าม 02 / 03 / 04 กับ prod)** — `01_schema.sql` ชุดปัจจุบันมีของ 05 / 06 อยู่แล้ว
```bash
R=~/bangmod-cd/repo; W=~/bangmod-cd/work/prod-init               # worktree ชั่วคราวของ main (ไม่แตะ ~/Capstone และ clone ของ CD)
git -C $R fetch -q origin && git -C $R worktree add --detach $W origin/main \
  && $W/devops/k8s/init-db.sh prod; git -C $R worktree remove --force $W
```

**5. ข้อมูลเริ่มต้น:** (ก) admin คนแรกตามหัวไฟล์ `k8s/sql/prod-first-admin.sql` (ผู้ใช้ตั้งรหัสเอง ≥ 12 ตัว) · (ข) ภาคเรียน / ข้อความยินยอม / ห้อง ด้วย `k8s/sql/prod-first-setup.sql` — ค่าเริ่มต้น 2569/1 2026-08-03 → 2026-12-18 (ตรวจกับปฏิทิน มจธ.) · เปลี่ยนโดยเพิ่ม `\\set sem_start '…'\n` ใน printf · จะเดโมกล้องบน prod เพิ่ม `\\set with_camera true\n` (เพิ่มกล้อง `CAM-4303-A` → `room-4303` · MediaMTX ใช้ร่วมกัน · ai-service ของ uat และ prod อ่านกล้องเดียวกันได้ แต่ใช้ CPU 2 เท่า)
```bash
R=~/bangmod-cd/repo; git -C $R fetch -q origin; C=frontend/src/features/face-registration
SHA=$(git -C $R show origin/main:$C/consent-text.js | sha256sum | cut -c1-64)
VER=$(git -C $R show origin/main:$C/constants.js | sed -n "s/.*CONSENT_VERSION = '\(v[0-9]*\)'.*/\1/p"); echo "consent $VER $SHA"
{ printf "\\set consent_version '%s'\n\\set consent_sha '%s'\n" "$VER" "$SHA"; git -C $R show origin/main:devops/k8s/sql/prod-first-setup.sql; } \
  | kubectl -n prod exec -i statefulset/postgres -- psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1
```
⚠️ ข้อความ consent v1 ยังเป็นร่าง (มีช่อง `[ ... ]` · ยังไม่ผ่าน DPO) ใช้กับเดโมเท่านั้น · ข้อความจริงมา: แก้ `consent-text.js` + `CONSENT_VERSION = 'v2'` → merge main → deploy prod → รันขั้น (ข) ซ้ำ (v2 เป็นฉบับปัจจุบัน · v1 ไม่ถูกลบ · คนที่ยินยอม v1 ถูกขอยินยอมใหม่)
(ค) อาจารย์: คำสั่งหัวไฟล์ `k8s/sql/prod-add-lecturer.sql` (ทีละคน · รหัสผ่าน ≥ 12 ตัว พิมพ์ตอนรัน) — ต้องมีก่อน admin สร้างรายวิชา (B5 บังคับเลือกอาจารย์)
(ง) นักศึกษา: เตรียม CSV `student_code,title,first_name,last_name,email` (template ของหน้า "รายชื่อนักศึกษาในกลุ่มเรียน" ใช้ได้เลย) บน VM แล้วรันคำสั่งหัวไฟล์ `k8s/sql/prod-add-students.sql` ด้วย `DRY=true` ก่อน → ถูกแล้วรันซ้ำด้วย `DRY=false` · รหัสผ่านชั่วคราวชุดเดียว แจกเอง (ยังไม่มีหน้าเปลี่ยนรหัสผ่าน / flag บังคับเปลี่ยน)
(จ) ลงกลุ่มเรียน: admin สร้างรายวิชาในหน้าเว็บ → อาจารย์ / admin เพิ่มนักศึกษาเข้ากลุ่มที่หน้า "รายชื่อนักศึกษาในกลุ่มเรียน" (เพิ่มเอง หรือนำเข้า CSV เดียวกัน)
⚠️ ข้อมูลบน prod ของโครงงานนี้ = dummy เท่านั้น (PDPA ตามอาจารย์ที่ปรึกษา) ทั้งอาจารย์และนักศึกษา

**6. ตรวจผล (ข้อ 13–16)**
```bash
kubectl -n prod get pods
kubectl -n prod logs deploy/backend --tail=300 | grep -E 'ERROR|โปรไฟล์ local|Started'   # ต้องมี Started · ไม่มี ERROR / "โปรไฟล์ local"
O='Origin: https://bscit.sit.kmutt.ac.th'; B=https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/prod
curl -s -o /dev/null -w 'web %{http_code}\n' $B/                                 # 200
curl -s -o /dev/null -w 'me %{http_code}\n' -H "$O" $B/api/me                     # 401 (403 = CORS ผิด)
curl -s -o /dev/null -w 'internal %{http_code}\n' -X POST $B/api/internal/x       # 404
curl -s -o /dev/null -w 'test-accounts %{http_code}\n' $B/api/test-accounts       # ต้องไม่ใช่ 200 (prod ไม่เปิดบัญชีทดสอบ)
```
login admin ใน browser ที่ `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/prod/` · ผ่านแล้วเตรียมแถว "ประวัติการ deploy" (ห้ามมีความลับ)

## ติดตั้งโมเดล AI (ครั้งเดียว · ใช้ร่วมกันทั้ง uat และ prod)

โมเดลไม่อยู่ใน git · pod `ai-service` อ่านจาก `/opt/bangmod/models` บน VM (hostPath) · ไม่มีโมเดล = ai-service ไม่ขึ้น และลงทะเบียนใบหน้าได้ 502 (ส่วนอื่นใช้ได้)

| ไฟล์ (ชื่อต้องตรง) | โมเดล | ที่มา · สัญญาอนุญาต | SHA-256 |
|---|---|---|---|
| `yolov8n-face-pose.pt` (6.1 MB) | YOLOv8n **PoseModel** 1 class (ใบหน้า) + **5 จุด** (ตา 2, จมูก, มุมปาก 2) · เทรนด้วย ultralytics 8.0.66 บน WIDER FACE | [derronqi/yolov8-face](https://github.com/derronqi/yolov8-face) (ไฟล์ yolov8n ใน Google Drive) · **GPL-3.0** | `d545bf1a…e626ca` |
| `face_recognition_sface_2021dec.onnx` (36.9 MB) | SFace → embedding 128 มิติ | [OpenCV Zoo](https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface) · Apache-2.0 | `0ba9fbfa…c34e79` |

ใช้แค่ 2 ไฟล์นี้ · MiniFASNet (`minifasnet_v2.onnx`, `minifasnet_v1se.onnx` · anti-spoofing) **เลิกใช้แล้ว 2026-11-27** — deploy ไม่ต้องมี และ `fetch-models.sh` ไม่ดาวน์โหลดแล้ว · ไฟล์เดิมใน `/opt/bangmod/models` บน VM ลบได้เมื่อ ai-service รุ่นที่ไม่มี anti-spoofing ขึ้นแล้ว (ดู Changelog 2026-11-27)

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

ทดสอบจากหน้าเว็บ: เปิด uat ผ่าน bscit (HTTPS) → login สมาชิกทีม เช่น `66130500013@st.sit.kmutt.ac.th` (ยังไม่เคยลงทะเบียน) → ลงทะเบียนใบหน้า → **"เปิดกล้อง"** หรือ "เลือกรูปจากเครื่อง" 3 รูป (ปุ่มเปิดกล้องใช้ได้เฉพาะ HTTPS / localhost — เปิดตรงที่ VM แบบ HTTP ต้องเลือกรูปจากเครื่อง) → ต้องได้ "ลงทะเบียนใบหน้าสำเร็จ" · รูปไม่ผ่าน (เบลอ / ใบหน้าเล็ก / หลายคน) จะได้ข้อความบอกเหตุผล

**ถ้าดาวน์โหลดจาก Google Drive ไม่ได้** (โควตาเต็ม / ถูกบล็อก): เปิดลิงก์ yolov8n ใน [README ของ derronqi](https://github.com/derronqi/yolov8-face) ด้วย browser → ดาวน์โหลด → `scp` ไฟล์ขึ้น VM เป็น `/opt/bangmod/models/yolov8n-face-pose.pt` → ตรวจ `sha256sum /opt/bangmod/models/yolov8n-face-pose.pt` ให้ตรงกับใน `models/fetch-models.sh` แล้วทำข้อ 3

> โมเดลชุดเดียวกันใช้ทั้ง **การลงทะเบียนใบหน้า** (`POST /embedding`) และ **ตัวอ่านกล้อง realtime** (`CAMERA_WORKER=true`: อ่าน RTSP จาก MediaMTX → จับคู่ → ส่ง `/api/internal/rooms/{roomId}/sightings`) · รายละเอียดใน `ai-service/README.md` หัวข้อ "ตัวอ่านกล้อง"

## MediaMTX + RTSPS ผ่าน Tailscale (กล้องอยู่คนละ network)

```
กล้อง (RTSPS :322) ──Tailscale──▶ VM: tailscale0 ──▶ pod mediamtx (namespace media) ──┬─ WHEP: เบราว์เซอร์ → <VITE_BASE>media/ → NGINX ของ frontend → :8889 · ภาพ: UDP :8189 (hostPort) ──▶ เบราว์เซอร์
   (หรือ subnet router ฝั่งกล้อง)                                                        └─ RTSP :8554 (ใน cluster) ──▶ ai-service
```

- MediaMTX **ดึง** ภาพจากกล้องเองด้วย `rtsps://` (กล้องต้องรองรับ RTSPS) · ตรวจใบรับรองกล้องด้วย `sourceFingerprint` (กล้องส่วนใหญ่ใช้ใบที่ออกเอง)
- Tailscale ติดตั้งบน VM (host) · pod ส่งออกทาง route ของ host ไม่ต้องมี Tailscale ใน pod และไม่ต้องเก็บ auth key ใน K3s
- MediaMTX ตัวเดียวใช้ทั้ง uat และ prod (hostPort ซ้ำกันบน node เดียวไม่ได้ · กล้องไม่ต้องถูกดึง 2 รอบ) · ai-service อ่านที่ `rtsp://mediamtx.media.svc.cluster.local:8554/<stream_path>`
- ชื่อ path ใน `mediamtx.yml` = `cameras.stream_path` ใน DB (เช่น `room-4301`) · backend สร้าง URL WHEP = `MEDIA_BASE_URL/<stream_path>/whep` · `MEDIA_BASE_URL` = `<VITE_BASE>media` (uat `/capstone26/cp26ms3/uat/media`, prod `/prod/media`) → `frontend/nginx.conf` ส่ง `<APP_PATH>media/*` ไป `MEDIAMTX_URL` (ค่าเริ่มต้น `http://mediamtx.media.svc.cluster.local:8889`)
- WHEP (`POST …/whep`) ห้องที่ไม่มี path ใน `mediamtx.yml` → 400 `path '…' is not configured` · มี path แต่ยังต่อกล้องไม่ได้ → 404 `no stream is available` (ตรวจผ่าน bscit 2026-11-24) · ไม่มี namespace `media` → NGINX ตอบ 502 (หน้าเว็บอื่นใช้ได้ปกติ)

**1. Tailscale บน VM (ใช้ sudo — ผู้ใช้รันเอง)**
```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up --accept-routes        # --accept-routes: รับ subnet ของกล้องที่ subnet router ฝั่งกล้องประกาศ
tailscale ip -4                          # IP 100.x ของ VM
```
- กล้องลง Tailscale เองไม่ได้ (ส่วนใหญ่) → ใช้เครื่องหนึ่งใน network ของกล้องเป็น subnet router: `sudo tailscale up --advertise-routes=<subnet กล้อง เช่น 192.168.10.0/24>` แล้วกด approve route ใน admin console ของ Tailscale
- subnet ของกล้อง **ห้ามซ้อน** กับ `10.42.0.0/16` (pod), `10.43.0.0/16` (service) ของ K3s และ network ของคณะ (`10.4.x.x`)
- ACL ของ Tailscale ต้องให้ VM ต่อไป IP กล้อง port RTSPS ได้

**2. ตรวจว่า VM ต่อกล้องได้ + หา fingerprint**
```bash
nc -vz <IP กล้อง> 322                     # port RTSPS ของกล้อง (ดูคู่มือ · มาตรฐาน 322)
openssl s_client -connect <IP กล้อง>:322 </dev/null 2>/dev/null | openssl x509 -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':'
kubectl run nettest --rm -it --restart=Never --image=busybox:1.36 -- nc -vz -w 5 <IP กล้อง> 322   # จาก pod (ต้องผ่านเหมือนบน host)
```

**3. config + apply (ไม่ต้อง sudo)**
```bash
cp devops/k8s/mediamtx/mediamtx.yml.example devops/k8s/mediamtx/mediamtx.yml && chmod 600 devops/k8s/mediamtx/mediamtx.yml
# แก้ paths: 1 กล้อง = 1 path (source = rtsps://user:pass@IP:port/path, sourceFingerprint = ค่าจากข้อ 2)
kubectl apply -k devops/k8s/mediamtx
kubectl -n media get pods
kubectl -n media logs deploy/mediamtx | grep -iE 'room-|error'   # ต้องเห็น "[path room-4301] [RTSP source] ready"
```
- เปิด firewall ของ VM: `8889/tcp` และ `8189/udp` (ใช้ sudo — ผู้ใช้ทำเอง)
- แก้กล้อง / config: แก้ `mediamtx.yml` แล้ว `kubectl apply -k devops/k8s/mediamtx` อีกครั้ง (ชื่อ Secret เปลี่ยน → pod restart เอง)
- `mediamtx.yml` มีรหัสกล้อง: อยู่บน VM เท่านั้น ห้าม commit · สำรองไว้นอก VM เหมือน `secret.env`
- อาการ: pod `CrashLoopBackOff` + log `credential contains unsupported characters` = `pass` ใน `authInternalUsers` มีอักขระที่ MediaMTX ไม่รับ → ใช้ `openssl rand -hex 12` (ตัวอักษร + ตัวเลขล้วน)
- อาการ: pod `CrashLoopBackOff` + log `json: cannot unmarshal number into Go value of type string` = ค่าใน `mediamtx.yml` เป็นตัวเลขล้วน (เช่น fingerprint) → ใส่ `"..."` · `tls: failed to verify certificate` = `sourceFingerprint` ผิด / กล้องเปลี่ยนใบ · `i/o timeout` = route ของ Tailscale / ACL / subnet ซ้อน · WHEP ได้ 201 แต่จอดำ = UDP 8189 ถูกบล็อก หรือ `webrtcAdditionalHosts` ไม่ใช่ IP ของ VM

**กล้อง Raspberry Pi (ทดสอบ · ห้อง 4303 · 2026-11-25)** — Pi ลง Tailscale เอง (ไม่ต้องมี subnet router) แล้วรัน MediaMTX ของตัวเองอ่านกล้อง (`source: rpiCamera`) และเปิด RTSPS ให้ VM ดึง
- Pi: Raspberry Pi OS 32-bit (`armv7l`) → `mediamtx_v1.21.4_linux_armv7.tar.gz` · กล้อง `ov5647` (Camera Module v1) · 1280x720 15 fps, hardware H264
- Pi `~/mediamtx/mediamtx.yml`: `rtspEncryption: "optional"` + `rtspServerKey/Cert` (ใบที่ออกเอง `openssl req -x509 …`) · user `vm` (รหัส `openssl rand -hex 12`) อ่านได้เฉพาะ path `cam` · ดูใน LAN: `http://<IP ของ Pi>:8889/cam`
- VM `mediamtx.yml`: `room-4303: source: "rtsps://vm:<รหัส>@<IP Tailscale ของ Pi>:8322/cam"` + `sourceFingerprint` = fingerprint ของ `server.crt` บน Pi
- รหัสใน `authInternalUsers` ใช้ได้แค่ตัวอักษร / ตัวเลข / สัญลักษณ์บางตัว (`openssl rand -hex` ปลอดภัย) · ไฟล์สำรอง `mediamtx.yml.*` มีรหัส — อยู่ใน `.gitignore` แต่ควรลบหลังใช้

**MediaMTX บน Pi เป็น systemd (เปิดเองตอนบูต · ใช้ sudo — ผู้ใช้รันเองบน Pi)** — ไฟล์ `pi/mediamtx.service` · binary + `mediamtx.yml` อยู่ที่ `~/mediamtx/`
```bash
scp devops/pi/mediamtx.service <user>@<IP ของ Pi>:/tmp/          # จากเครื่องที่มี repo
# บน Pi: หยุดตัวที่รันด้วยมือก่อน (Ctrl+C ใน terminal นั้น หรือ pkill -x mediamtx)
sed "s/__USER__/$USER/g" /tmp/mediamtx.service | sudo tee /etc/systemd/system/mediamtx.service >/dev/null
sudo systemctl daemon-reload && sudo systemctl enable --now mediamtx && sudo systemctl enable tailscaled
systemctl status mediamtx --no-pager && journalctl -u mediamtx -n 30 --no-pager   # ต้องเห็น "[RTSP] listener opened on :8322"
sudo reboot   # แล้วตรวจ: systemctl is-active mediamtx tailscaled → active active
```

**เช็คลิสต์ก่อนเดโมกล้อง (ทาง Tailscale — ทางที่ใช้ได้แล้ว)** · ทำวันก่อนเดโมที่ห้องจริง และอีกครั้ง 30 นาทีก่อนเดโม
1. Tailscale admin console → Machines → Pi และ VM → **Disable key expiry** (key หมดอายุ = หลุดวันเดโม)
2. Pi เสียบไฟ + สายกล้อง → ต่อเน็ต (Wi-Fi ห้อง หรือ hotspot มือถือ) → `ping -c3 1.1.1.1`
3. Pi: `tailscale status` เห็น VM `cp26ms3` · `tailscale ip -4` = IP ใน `source` ของ `room-4303` ใน `mediamtx.yml` บน VM
4. Pi: `systemctl is-active mediamtx` = active · `ss -lntp | grep 8322` มี listener
5. VM: `tailscale ping <IP 100.x ของ Pi>` ได้ pong · `nc -vz -w5 <IP 100.x ของ Pi> 8322` succeeded
6. VM: `kubectl -n media logs deploy/mediamtx --since=10m | grep room-4303` เห็น `[RTSP source] ready` (ยังไม่ขึ้น: MediaMTX ลองต่อใหม่เอง · รอ 30 วินาที หรือ `kubectl -n media rollout restart deploy/mediamtx`)
7. VM: `kubectl -n uat exec deploy/ai-service -- python -c "import urllib.request as u;print(u.urlopen('http://localhost:8080/cameras/status').read().decode())"` → `room-4303` เป็น live (ไม่ใช่ `no_signal`)
8. เครื่องที่ใช้เปิดหน้าเว็บ **ต้องอยู่ในเครือข่ายคณะ / VPN** (ภาพ UDP 8189 ไปตรง `10.4.56.69`) → หน้าห้อง 4303 เห็นภาพ · ได้ WHEP แต่จอดำ = เครือข่ายของเครื่องที่ดูบล็อก UDP 8189 → เปลี่ยนเครือข่ายของเครื่องที่ดู (ไม่ใช่ของ Pi)
9. กด "เริ่มคาบตอนนี้" → ยืนหน้ากล้อง → ขึ้นเช็คชื่อ

ทางสำรอง: Wi-Fi ห้องใช้ไม่ได้ / บล็อก Tailscale → ให้ Pi ต่อ **hotspot มือถือ** (Tailscale ผ่าน NAT ได้เอง · IP 100.x ไม่เปลี่ยน ไม่ต้องแก้ `mediamtx.yml`) · 720p 15 fps ใช้เน็ตราว 2–3 Mbps · ตั้งชื่อ / รหัส hotspot ไว้ใน Pi ล่วงหน้า (`sudo nmcli dev wifi connect <SSID> password <รหัส>` ทำครั้งเดียว ครั้งต่อไปต่อเอง)

**ต่อแบบ "วงเดียวกัน" (ไม่ผ่าน Tailscale) ไม่ติด — สาเหตุที่น่าจะเป็น**
- VM อยู่ในวง server ของคณะ (`10.4.56.x`) แต่ Pi ต่อ Wi-Fi ของมหาลัย ซึ่งมักเป็นคนละ VLAN และมี client isolation / firewall กันการต่อ**เข้า**เครื่อง Wi-Fi → VM (MediaMTX เป็นฝั่ง**ดึง**) ต่อ `<IP Wi-Fi ของ Pi>:8322` ไม่ถึง · แก้เองไม่ได้ ต้องขอฝ่าย IT
- Wi-Fi มหาลัยมักต้อง login (captive portal / WPA2-Enterprise) → Pi ไม่มีจอ login ไม่ได้ = ไม่มีเน็ตเลย
- IP Wi-Fi ของ Pi ได้จาก DHCP เปลี่ยนทุกครั้ง → `source` ใน `mediamtx.yml` เก่า · และถ้า IP อยู่ใน `10.42.x.x` / `10.43.x.x` จะชนกับ pod / service ของ K3s
- ใช้ชื่อ (`raspberrypi.local`) ใน `source` → pod resolve mDNS ไม่ได้ ต้องใช้ IP
- ถ้าที่ไม่ติดคือ "เบราว์เซอร์ดูภาพ" ไม่ใช่ Pi → UDP 8189 จาก Wi-Fi ไป VM ถูกบล็อก (WHEP 201 แต่จอดำ)

คำสั่งหาว่าติดที่ไหน:
```bash
# Pi
hostname -I; ip route; tailscale status; ss -lntup | grep -E '8322|8554|8889'; systemctl status mediamtx --no-pager
# VM (แทน <PI_LAN> ด้วย IP Wi-Fi ของ Pi)
ip route get <PI_LAN>                         # ต้องออก eth / ens ของ VM ไม่ใช่ cni0 / flannel (ชน K3s)
ping -c3 <PI_LAN>; nc -vz -w5 <PI_LAN> 8322   # timeout = เครือข่ายกั้น (VLAN / isolation) · refused = ถึง Pi แต่ MediaMTX ไม่ฟัง
kubectl run nettest --rm -it --restart=Never --image=busybox:1.36 -- nc -vz -w 5 <PI_LAN> 8322   # จาก pod (host ผ่านแต่ pod ไม่ผ่าน = ชน subnet K3s)
```
สรุป: วันเดโม **ใช้ Tailscale** (ทำงานแล้ว ไม่ขึ้นกับว่าอยู่วงไหน) · วงเดียวกันเป็นงานหลังเดโม

**ตัวอ่านกล้องของ ai-service** (`CAMERA_WORKER=true` ใน `k8s/base/ai-service.yaml`): อ่าน `rtsp://mediamtx.media.svc.cluster.local:8554/<stream_path>` ของทุกกล้องใน DB → เช็คชื่อผ่าน `/api/internal/**` + heartbeat (ป้าย "กล้องออนไลน์" เป็นสถานะจริงแล้ว: กล้องที่ไม่มี path ใน MediaMTX = `no_signal`) · ต้องมี `FACE_EMBEDDING_KEY` ใน `secret.env` (ค่าเดียวกับ backend) · ดูสถานะ:
```bash
kubectl -n uat exec deploy/ai-service -- python -c "import urllib.request as u;print(u.urlopen('http://localhost:8080/cameras/status').read().decode())"
kubectl -n uat logs deploy/ai-service | grep -E 'camera|gallery'
```
- ลองเช็คชื่อด้วยกล้อง Pi บน uat (ข้อมูล mock ชุดเล็ก: INT201-001 เรียนห้อง 4303): (1) `mediamtx.yml` มี `room-4303` ดึงจาก Pi (2) อาจารย์ / admin เปิดหน้าห้อง 4303 แล้วกด "เริ่มคาบตอนนี้" (หรือ `03_demo_live_session.sql`) (3) ลงทะเบียนใบหน้าจริงด้วย 66130500013 / 36 / 41 (4) ยืนหน้ากล้อง → หน้าห้อง 4303 ขึ้นเช็คชื่อ
- embedding ใน `02_seed_mock.sql` เป็นไบต์สุ่ม → ถอดไม่ได้ (log WARN) จับคู่ได้เฉพาะคนที่ลงทะเบียนจริง

**ยังไม่ได้ทำ:** ภาพ (UDP 8189) ไปถึงเฉพาะเครื่องในคณะ / VPN (คนนอกคณะต้องใช้ HLS fallback)

## ปัญหาที่เจอตอน deploy และวิธีแก้

| อาการ | สาเหตุ | แก้ |
|---|---|---|
| หน้าห้องผ่าน bscit ค้าง "กำลังเชื่อมต่อ" · รายชื่อ / เช็คชื่อไม่อัปเดตเอง | NGINX ของ bscit กัก SSE ไว้จน stream จบ (ตั้งค่าเริ่มต้น: HTTP/1.0 ไป upstream / gzip) | `frontend/nginx.conf` ส่ง `X-Accel-Buffering: no` ในส่วน SSE (แก้แล้ว 2026-11-25) · หน้าห้อง poll รายชื่อทุก 15 วินาทีเมื่อ SSE ไม่ต่อ |
| เปิดผ่าน bscit หน้าเว็บขึ้นแต่ login ไม่ได้ (POST ได้ 403 / 400) · `curl` ไม่ใส่ `Origin` login ได้ | เบราว์เซอร์ส่ง `Origin: https://bscit.sit.kmutt.ac.th` แต่ backend เห็น request เป็น HTTP host ของ VM → Spring ตัดสินว่าข้ามโดเมนแล้วปฏิเสธ (GET หน้าเว็บไม่ส่ง `Origin` จึงไม่โดน) | ใส่ `https://bscit.sit.kmutt.ac.th` ใน `CORS_ORIGINS` ของ overlay · ตรวจ: `curl -H 'Origin: https://bscit.sit.kmutt.ac.th' .../api/me` ต้องได้ 401 ไม่ใช่ 403 |
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

- **prod ไม่มีข้อมูลเริ่มต้น** (ไม่มี seed) ต้องเพิ่มเองก่อนใช้: บัญชี admin (`users`), ภาคเรียน (`semesters`), ข้อความยินยอม (`consent_versions` ที่ `is_current = true`) · admin คนแรก: `k8s/sql/prod-first-admin.sql` · prod ไม่มีบัญชีทดสอบ — ผู้ใช้พิมพ์อีเมล / รหัสผ่านเอง ห้ามใช้ `Password@123`
- `FACE_EMBEDDING_KEY` ห้ามเปลี่ยนหลังมีข้อมูลใบหน้า (ถอดรหัส embedding เดิมไม่ได้) · **สำรอง `secret.env` ของแต่ละ namespace ไว้นอก VM** (เช่น password manager ของทีม) ห้ามใส่ใน git หรือ README
- `NEW_STUDENT_PASSWORD` (E2 · รหัสผ่านเริ่มต้นของบัญชีนักศึกษาที่ backend สร้างตอนเพิ่มเข้ากลุ่ม): **uat เท่านั้น** อ่านจาก `secret.env` (optional — ไม่มี key = สร้างบัญชีใหม่ไม่ได้) · prod ห้ามตั้ง (`deploy.sh prod` หยุดถ้ามี) · เพิ่มใน uat บน VM (ไม่แสดงค่า · ไม่เขียนทับ):
  ```bash
  f=~/bangmod-cd/secrets/uat.env
  if grep -q '^NEW_STUDENT_PASSWORD=' "$f"; then echo "มีอยู่แล้ว — ไม่แก้"; else
    read -rsp 'NEW_STUDENT_PASSWORD (uat): ' P; echo
    [ -n "$(tail -c1 "$f")" ] && echo >> "$f"; [ -n "$P" ] && printf 'NEW_STUDENT_PASSWORD=%s\n' "$P" >> "$f"; unset P
  fi; chmod 600 "$f"; grep -c '^NEW_STUDENT_PASSWORD=.' "$f"          # ต้องได้ 1
  ~/bangmod-cd/bin/deploy.sh uat                                       # Secret ใน cluster สร้างตอน apply → rollout restart อย่างเดียวไม่ได้ค่าใหม่
  kubectl -n uat exec deploy/backend -- sh -c 'test -n "$NEW_STUDENT_PASSWORD" && echo set || echo missing'
  ```
- backend ต้องมี replica เดียว (SSE อยู่ในหน่วยความจำ) · Deployment ตั้ง `strategy: Recreate` ไว้แล้ว
- MediaMTX อยู่ใน `k8s/mediamtx/` แยกจาก overlays (namespace `media` · apply เอง ไม่อยู่ใน CD) · ภาพสด (B2) ใช้ `MEDIA_BASE_URL` = `<VITE_BASE>media` ผ่าน NGINX ของ frontend (hostPort 8889 ไว้ตรวจจากนอก cluster)
- HTTPS ที่ผู้ใช้เห็นมาจาก bscit (ใบ GlobalSign ของคณะ) · Traefik บน VM รับ HTTP จาก bscit — กล้องในเบราว์เซอร์ (หน้าลงทะเบียนใบหน้า) ต้องใช้ HTTPS ยกเว้น localhost
- HTTPS ตรงที่ VM (`https://cp26ms3.sit.kmutt.ac.th/capstone26/cp26ms3/<ns>/` · ใบจาก SITCertAuthCA) เตรียมไว้แล้วแต่ยังไม่เปิด — ขั้นตอนอยู่ในคอมเมนต์ของ `k8s/base/ingress.yaml` · ใช้ได้เฉพาะในคณะ / VPN และเครื่องที่ไม่ได้ติดตั้ง SITCertAuthCA จะขึ้นเตือน

## บันทึกการทำงาน (Changelog)

### 2026-11-27 — เอา MiniFASNet (anti-spoofing) ออกจาก deploy
- **ทำอะไร:** `k8s/base/ai-service.yaml` ลบ env `ANTISPOOF`, `ANTISPOOF_MIN_REAL`, `ANTISPOOF_MODEL_V2`, `ANTISPOOF_MODEL_V1SE` (env อื่น, volume `models` → hostPath `/opt/bangmod/models`, probe, resources คงเดิม) · `models/fetch-models.sh` ไม่ดาวน์โหลด `minifasnet_v2.onnx` / `minifasnet_v1se.onnx` แล้ว (เหลือ YOLO + SFace) · README: ตารางโมเดลเหลือ 2 ไฟล์, ลำดับหลัง push ข้อ b / e ไม่อ้าง MiniFASNet
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/base/ai-service.yaml`, `models/fetch-models.sh`, `README.md`
- **เหตุผล:** ai-service เลิกใช้ liveness / anti-spoofing (เหลือ YOLO face, SFace, ตรวจคุณภาพภาพ, ตรวจว่าเป็นคนเดียวกัน, ตัวอ่านกล้อง)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ไม่มี MiniFASNet ใน Dockerfile (โมเดลไม่อยู่ใน image), GitHub Actions (`database.yml`, `frontend.yml`), `build-images.sh`, `cd/*.sh`, overlay uat / prod — ไม่ต้องแก้ · **โค้ด ai-service (`vision.py` / `main.py`) ณ commit `f46e527` ยังมี anti-spoofing อยู่ และค่าเริ่มต้นคือ `ANTISPOOF=on` + path `/models/minifasnet_*.onnx`** → ถ้า deploy manifest นี้ก่อนโค้ด AI ถูกแก้ และไฟล์ยังอยู่บน VM = anti-spoofing ยังทำงาน (ถ้าไม่มีไฟล์ = ปิดเอง pod ยังขึ้น) · ตรวจด้วย PyYAML + `bash -n` (เครื่องที่แก้ไม่มี kubectl / kustomize) · ไฟล์ MiniFASNet บน VM ลบเองหลังโค้ด AI ใหม่ขึ้น: `rm /opt/bangmod/models/minifasnet_v2.onnx /opt/bangmod/models/minifasnet_v1se.onnx`

### 2026-11-26 — SQL เพิ่มอาจารย์ / นักศึกษาบน prod
- **ทำอะไร:** เพิ่ม `k8s/sql/prod-add-lecturer.sql` (แบบเดียวกับ `prod-first-admin.sql`: ค่าผ่าน `\set`, รหัสผ่าน stdin ≥ 12 ตัว ห้าม `Password@123`, BCrypt cost 10 · อีเมลเป็นอาจารย์อยู่แล้ว = ไม่แก้ · เป็น role อื่น = error) และ `k8s/sql/prod-add-students.sql` (CSV ส่งเป็น base64 ใน `\set` · หัวเดียวกับ template E2 เรียงคอลัมน์แบบไหนก็ได้ · ตรวจรหัส 11 หลัก / email ตรงรหัส / ชื่อ–นามสกุล / ความยาว / ซ้ำในไฟล์ · รหัส+อีเมลมีอยู่แล้ว = ข้าม · รหัสหรืออีเมลชนบัญชีอื่น = error · มี error = ไม่บันทึกทั้งไฟล์ · `dry_run` · สรุป insert / skip / error · faculty + entry_year จากรหัส · ไม่ลงกลุ่มเรียน) · ทั้งสองไฟล์หยุดเมื่อไม่มี schema / DB มีข้อมูล mock · ขั้น 5 ของ "Deploy prod ครั้งแรก" เพิ่ม (ค)–(จ)
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/sql/prod-add-lecturer.sql`, `k8s/sql/prod-add-students.sql`, `README.md`
- **เหตุผล:** prod ไม่มี API สร้างบัญชีอาจารย์ / นักศึกษา (E2 สร้างได้เฉพาะ local / uat) → admin สร้างรายวิชาไม่ได้และไม่มีนักศึกษาให้ลงกลุ่ม · ผู้ใช้เลือกให้ทำเป็น SQL
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบกับ PostgreSQL ชั่วคราวแบบ prod (`01` + `prod-first-setup` + `prod-first-admin`) + backend จริง: login admin / อาจารย์ / นักศึกษาที่สร้างได้ · admin สร้างรายวิชาโดยเลือกอาจารย์ที่สร้างได้ · อาจารย์เพิ่มนักศึกษาที่ import เข้ากลุ่มผ่าน E2 ได้ (`insert`) · CSV มี BOM / CRLF / ช่องมี `,` ในเครื่องหมายคำพูด / เรียงคอลัมน์ต่าง / แถวว่าง ถูกต้อง · แถวผิด 6 แบบถูกจับ · รันซ้ำ = ข้าม · ยังไม่มีหน้าเปลี่ยนรหัสผ่าน — รหัสชั่วคราวใช้ได้ตลอด

### 2026-11-26 — SQL ข้อมูลเริ่มต้นของ prod (ภาคเรียน / consent / ห้อง)
- **ทำอะไร:** เพิ่ม `k8s/sql/prod-first-setup.sql`: ภาคเรียนปัจจุบัน (ค่าใน `\set` · ตรวจว่าวันสิ้นภาคยังไม่ผ่าน), `consent_versions` ฉบับปัจจุบัน (ชื่อฉบับ + sha256 ของ `consent-text.js` จาก `origin/main` ส่งเข้ามาตอนรัน · ฉบับเดิมเลิกเป็นปัจจุบันแต่ไม่ลบ · ชื่อฉบับซ้ำแต่ hash ต่าง = error), ห้อง 4301–4305, กล้อง 4303 (`with_camera` ไม่บังคับ) · หยุดเมื่อไม่มี schema / เป็น DB ที่มีข้อมูล mock · รันซ้ำได้ · ตารางไฟล์ + ขั้น 5 ของ "Deploy prod ครั้งแรก" อัปเดตตาม
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/sql/prod-first-setup.sql`, `README.md`
- **เหตุผล:** CLAUDE.md ข้อ 17 — prod ไม่มี seed ต้องมีภาคเรียน + consent ก่อนใช้ และไม่มีหน้าเว็บเพิ่มห้อง / ภาคเรียน · ไฟล์อยู่นอก `backend/db/` จึงไม่ทำให้ CD หยุด
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบกับ PostgreSQL ชั่วคราว (`01` อย่างเดียวแบบ prod): รันครั้งแรก / ซ้ำ / hash ต่าง (error) / เปลี่ยนเป็น v2 / เปิดกล้อง / ลืมส่งค่า (error) / DB mock (error) · `prod-first-admin.sql` ยังตรง schema (ทดสอบแล้ว) · วันเปิด–ปิดภาคเริ่มต้น (2026-08-03 → 2026-12-18) เป็นค่าประมาณ ต้องตรวจกับปฏิทินจริง · consent v1 ยังเป็นร่าง ใช้กับเดโมเท่านั้น · prod ยังสร้างบัญชีอาจารย์ / นักศึกษาไม่ได้

### 2026-11-26 — ตารางโมเดล MiniFASNet + ลำดับอัปเดต uat หลัง push ชุด E2 / anti-spoofing / seed เดโม
- **ทำอะไร:** ตารางโมเดลเพิ่ม `minifasnet_v2.onnx`, `minifasnet_v1se.onnx` (ที่มา / สัญญาอนุญาต / SHA-256 จาก `models/fetch-models.sh`) + คำอธิบาย env `ANTISPOOF` (on / log / off), `ANTISPOOF_MIN_REAL` 0.7 จาก `k8s/base/ai-service.yaml` · หัวข้อใหม่ "ลำดับหลัง push ชุด E2 + anti-spoofing + seed เดโม": a) `NEW_STUDENT_PASSWORD` b) โมเดลด้วย `git archive` → `fetch-models.sh` c) reset DB uat ตาม `backend/db/README.md` d) `ALLOW_DB_CHANGE=1 deploy.sh uat` e) ตรวจผล
- **ไฟล์ที่เกี่ยวข้อง:** `README.md`
- **เหตุผล:** commit `7239079`, `e16d4d0`, `2eef1ff` ต้องทำบน VM ก่อน uat ใช้ได้ครบ (`2eef1ff` แก้ `backend/db/` → CD หยุด)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ข้อความ log ที่ใช้ตรวจอ่านจาก `ai-service/vision.py` / `main.py` · ยังไม่ได้รันบน VM · reset DB ลบใบหน้าเดิม ต้องลงทะเบียนทีมใหม่ก่อนเดโมกล้อง

### 2026-11-26 — `NEW_STUDENT_PASSWORD` จาก secret (uat เท่านั้น)
- **ทำอะไร:** backend อ่าน `NEW_STUDENT_PASSWORD` จาก `app-secret` แบบ `optional: true` · `secret.env.example` ของ uat มีบรรทัดตัวอย่าง (ไม่มีค่า) · prod example เขียนว่าห้ามใส่ · `deploy.sh prod` หยุดถ้า `secrets/prod.env` มีค่านี้ · คำสั่งเพิ่ม key บน VM ใน "ข้อควรรู้"
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/base/backend.yaml`, `k8s/overlays/uat/secret.env.example`, `k8s/overlays/prod/secret.env.example`, `cd/deploy.sh`, `README.md`
- **เหตุผล:** BE E2 สร้างบัญชีนักศึกษาใหม่ได้เมื่อตั้งค่านี้ (ดู `backend/README.md`) · เป็นรหัสผ่านจึงไม่ใส่ใน ConfigMap · prod ไม่มีค่า = ปลอดภัย
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ตรวจ YAML ด้วย PyYAML + `bash -n` + ทดสอบ pattern ของ grep (มีค่า = หยุด · ว่าง / คอมเมนต์ / ไม่มี = ผ่าน) · ยังไม่ได้รัน kustomize (ไม่มีในเครื่อง) · ผู้ใช้ต้องเพิ่ม key ใน `~/bangmod-cd/secrets/uat.env` บน VM เอง แล้ว `deploy.sh uat` (หรือเพิ่มก่อน push commit นี้ CD จะใช้ค่าใหม่เอง)

### 2026-11-26 — เตรียม prod ผ่าน bscit + MediaMTX บน Pi เป็น systemd + เช็คลิสต์กล้องวันเดโม
- **ทำอะไร:** overlay prod: `CORS_ORIGINS=https://bscit.sit.kmutt.ac.th` (เอา localhost ออก) + `MEDIA_BASE_URL=/capstone26/cp26ms3/prod/media` + คอมเมนต์ CORS ที่ค้าง · เพิ่ม `pi/mediamtx.service` · README: หัวข้อ "Deploy prod ครั้งแรก (ผ่าน CD)", "MediaMTX บน Pi เป็น systemd", "เช็คลิสต์ก่อนเดโมกล้อง", สาเหตุที่ต่อแบบวงเดียวกันไม่ติด + คำสั่งตรวจ
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/overlays/prod/kustomization.yaml`, `pi/mediamtx.service`, `README.md`
- **เหตุผล:** เดโม 30 พ.ย. บน VM คณะ ต้องขึ้น prod ได้จริง · กล้องต้องเปิดเองหลังบูต
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ตรวจ YAML ด้วย PyYAML เท่านั้น (เครื่องที่แก้ไม่มี kubectl / kustomize) · `frontend/.env.production` ยังเป็น `VITE_BASE=/prod/` ต้องแก้ (งาน frontend) ก่อน `deploy.sh prod` · `deploy.sh prod` ใช้ `origin/main` → ต้อง merge `Dev` → `main` ก่อน · bscit `/capstone26/cp26ms3/prod/` ได้ 503 `no available server` (Traefik) = อาจมี Ingress prod ค้างใน cluster ต้องดูบน VM · unit systemd ยังไม่ได้ทดสอบบน Pi จริง

### 2026-11-25 — ปรับ README ให้ตรงกับโค้ดปัจจุบัน
- **ทำอะไร:** แผนภาพเพิ่ม `<base>media/*` → MediaMTX และ Pi → MediaMTX → ai-service · Deploy ครั้งแรกข้อ 2: `MEDIA_BASE_URL` เป็น `<VITE_BASE>media` (ไม่ใช่ `:8889`) · ทดสอบลงทะเบียนใบหน้าใช้บัญชีทีม + เปิดผ่าน bscit (HTTPS) ปุ่มเปิดกล้องใช้ได้ · หมายเหตุโมเดล: ตัวอ่านกล้อง realtime ทำแล้ว
- **ไฟล์ที่เกี่ยวข้อง:** `README.md`
- **เหตุผล:** ข้อความเดิมล้าสมัย (บัญชี `69130500011` ไม่มีใน seed ชุดใหม่ · ai-service จับคู่ realtime แล้ว)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** หัวเอกสารแก้ "ไม่ต้องตั้ง CORS" เป็นคำอธิบาย `CORS_ORIGINS` (ไม่มี preflight แต่ backend ตรวจ `Origin` → เปิดผ่าน bscit ต้องใส่โดเมน bscit) + ตารางว่าเปิดจากไหนต้องตั้งอะไร + คำสั่งตรวจ (ยืนยันจากโค้ดโดย DevOps) · คอมเมนต์ใน `k8s/overlays/prod/kustomization.yaml` DevOps แก้แยก

### 2026-11-25 — ขั้นตอนทดสอบกล้อง: เริ่มคาบจากหน้าเว็บ
- **ทำอะไร:** ขั้นตอน "ลองเช็คชื่อด้วยกล้อง Pi บน uat" ใช้ปุ่ม "เริ่มคาบตอนนี้" ในหน้าห้องแทนการรัน SQL
- **ไฟล์ที่เกี่ยวข้อง:** `README.md`
- **เหตุผล:** มีปุ่มเริ่ม / จบคาบแล้ว (ดู `backend/README.md`, `frontend/README.md`)

### 2026-11-25 — ขั้นตอนทดสอบกล้อง Pi ตามข้อมูล mock ชุดใหม่
- **ทำอะไร:** แก้ขั้นตอน "ลองเช็คชื่อด้วยกล้อง Pi บน uat" ให้ใช้ห้อง 4303 ตรง ๆ (ไม่ต้องเพิ่ม `room-4302` ใน MediaMTX แล้ว)
- **ไฟล์ที่เกี่ยวข้อง:** `README.md`
- **เหตุผล:** `02_seed_mock.sql` ชุดใหม่ย้าย INT201-001 ไปห้อง 4303 (ดู `backend/db/README.md`)

### 2026-11-25 — แถวแก้ปัญหา: SSE ค้างที่ bscit
- **ทำอะไร:** เพิ่มแถวในตาราง "ปัญหาที่เจอตอน deploy" (อาการ / สาเหตุ / วิธีแก้ ของหน้าห้องค้าง "กำลังเชื่อมต่อ")
- **ไฟล์ที่เกี่ยวข้อง:** `README.md`
- **เหตุผล:** คู่กับการแก้ใน `frontend/nginx.conf` (ดู `frontend/README.md`)

### 2026-11-25 — ai-service อ่านกล้องจาก MediaMTX (manifest)
- **ทำอะไร:** `k8s/base/ai-service.yaml` เพิ่ม env `CAMERA_WORKER=true`, `FACE_EMBEDDING_KEY` (จาก `app-secret`), `BACKEND_URL`, `MEDIAMTX_RTSP_URL`, `OPENCV_FFMPEG_CAPTURE_OPTIONS=rtsp_transport;tcp` · หัวข้อ MediaMTX เพิ่มวิธีดูสถานะตัวอ่านกล้อง และขั้นตอนลองเช็คชื่อด้วยกล้อง Pi บน uat
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/base/ai-service.yaml`, `README.md`
- **เหตุผล:** เปิดตัวอ่านกล้องของ ai-service (ดู `ai-service/README.md`)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** push ขึ้น Dev → CD build ai-service / backend ใหม่ · uat มีกล้อง mock 5 ตัว → ตัวอ่านจะพยายามเปิด 5 path (ไม่มีใน MediaMTX = `no_signal` และลองใหม่ทุก ≤ 30 วินาที) · prod ไม่มีกล้องใน DB = ตัวอ่านว่าง · ai-service ใช้ CPU เพิ่มตอนมีคาบ (YOLO 1 เฟรม/วินาที/กล้อง)

### 2026-11-25 — ภาพสดจากกล้อง Raspberry Pi ผ่าน RTSPS + Tailscale ใช้ได้ (ห้อง 4303)
- **ทำอะไร:** บันทึกวิธีตั้งกล้อง Pi (MediaMTX บน Pi `rpiCamera` + RTSPS → VM ดึงผ่าน Tailscale) ในหัวข้อ MediaMTX · `.gitignore` ครอบ `mediamtx.yml*` (ไฟล์สำรอง `.bak` / `.broken` มีรหัส) ยกเว้น `.example` · ตารางแก้ปัญหาเพิ่มอาการ `credential contains unsupported characters`
- **ไฟล์ที่เกี่ยวข้อง:** `README.md`, `../.gitignore`
- **เหตุผล:** ทดสอบต่อกล้องจริงจาก network อื่นครั้งแรก · ระหว่างทาง pod `CrashLoopBackOff` 2 รอบ (รหัส webcam มีอักขระที่ MediaMTX ไม่รับ, ไฟล์ `.bak` มี config ที่พังอยู่แล้ว) → สร้าง `mediamtx.yml` ใหม่จาก `.example`
- **ผลที่ตรวจ:** Pi `stream is available and online, 1 track (H264)` · VM `room-4303` มี stream (WHEP ด้วย SDP ปลอมได้ 400 `failed to unmarshal SDP` แทน 404) · WHEP จริงจากเบราว์เซอร์ผ่าน bscit (Mac ในเครือข่ายคณะ): 201, ICE `connected` ใน 131 ms ผ่าน `10.4.56.69:8189/udp`, รับภาพ 1280x720 ถอดรหัสได้ 166 เฟรมใน ~8 วินาที
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** MediaMTX บน Pi ยังรันด้วยมือ (ตั้ง systemd ให้เปิดเองตอนบูต) · ภาพดูได้เฉพาะในคณะ / VPN · ห้องอื่นยังไม่มีกล้อง · ป้าย "กล้องออนไลน์" ยังมาจากข้อมูล mock (ai-service ยังไม่ส่ง heartbeat)

### 2026-11-24 — ภาพสด (WHEP) ผ่าน NGINX ของ frontend แทน `http://…:8889`
- **ทำอะไร:** overlay uat / prod เปลี่ยน `MEDIA_BASE_URL` เป็น `/capstone26/cp26ms3/uat/media` และ `/prod/media` (ไม่ใส่โดเมน) · คอมเมนต์ใน `k8s/mediamtx/` ว่า hostPort 8889 ไว้ตรวจจากนอก cluster · หัวข้อ MediaMTX / forward จาก bscit / ข้อควรรู้ อัปเดตตาม (คู่กับ `frontend/nginx.conf`)
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/overlays/uat/kustomization.yaml`, `k8s/overlays/prod/kustomization.yaml`, `k8s/mediamtx/mediamtx.yaml`, `k8s/mediamtx/mediamtx.yml.example`, `README.md`
- **เหตุผล:** ผู้ใช้เปิดหน้าห้อง 4303 ผ่าน bscit แล้วขึ้น "เชื่อมต่อเซิร์ฟเวอร์ภาพ (MediaMTX) ไม่ได้" — หน้า HTTPS เรียก WHEP ที่ `http://cp26ms3…:8889` ไม่ได้ (mixed content) · นอกจากนี้ `room-4303` ยังไม่มีใน `mediamtx.yml` (ตอบ 500) และยังไม่มีกล้องจริง (ป้าย "กล้องออนไลน์" มาจากข้อมูล mock)
- **ผลบน uat (`dbdb3e5`):** POST WHEP จริงจากเบราว์เซอร์ผ่าน bscit ถึง MediaMTX แล้ว — `room-4303` 400 `path 'room-4303' is not configured`, `room-4301` 404 `no stream is available` (ไม่ใช่ mixed content แล้ว)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** prod มีผลเมื่อผู้ใช้รัน `deploy.sh prod` · ถ้าย้าย prod ไป bscit ต้องเปลี่ยนเป็น `/capstone26/cp26ms3/prod/media` พร้อม `VITE_BASE` · ยังต้องเพิ่ม path ของแต่ละห้องใน `mediamtx.yml` และต่อกล้องจริง

### 2026-11-24 — แก้ MediaMTX ไม่ขึ้นเพราะ fingerprint ตัวอย่างเป็นตัวเลข + apply ครั้งแรก
- **ทำอะไร:** `mediamtx.yml.example` ใส่ `"..."` ให้ `source` / `sourceFingerprint` และเตือนในคอมเมนต์ · เพิ่มอาการนี้ในหัวข้อ "MediaMTX + RTSPS ผ่าน Tailscale"
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/mediamtx/mediamtx.yml.example`, `k8s/mediamtx/mediamtx.yaml`, `README.md`
- **เหตุผล:** apply บน VM ครั้งแรก pod เป็น `CrashLoopBackOff` log `json: cannot unmarshal number into Go value of type string` — fingerprint ตัวอย่าง `0000…` เป็นตัวเลขล้วน YAML จึงอ่านเป็น number (ตรวจด้วย PyYAML: ได้ `0`)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** `mediamtx.yml` บน VM ต้องแก้เอง (ไม่อยู่ใน git) แล้ว apply ใหม่ · หลังแก้: pod `Running 1/1`, log `MediaMTX v1.21.4`, listener `:8554` / `:8889` / `:8189 (UDP/ICE)` · จากนอก VM `:8889/room-4301/` ได้ 200, WHEP `OPTIONS` 204 (firewall TCP 8889 เปิดแล้ว · UDP 8189 ยังไม่ได้ตรวจ) · กล้องยังเป็นค่าตัวอย่าง → `i/o timeout` ตามคาด
- **เพิ่มเติม:** readinessProbe ย้ายจาก port rtsp ไป whep — เดิม probe ทำให้ log `[RTSP] conn ... closed: EOF` ทุก 10 วินาที (`mediamtx.yaml`)

### 2026-11-24 — MediaMTX + ดึงภาพกล้องผ่าน RTSPS ทาง Tailscale
- **ทำอะไร:** เพิ่ม `k8s/mediamtx/` (namespace `media`): Deployment `bluenviron/mediamtx:1.21.4` (hostPort 8889 WHEP + 8189/udp, `Recreate`), Service (RTSP 8554 ใน cluster), config จาก Secret ที่สร้างจาก `mediamtx.yml` (ไม่อยู่ใน git) · `mediamtx.yml.example`: ดึงกล้องด้วย `rtsps://` + `sourceFingerprint`, `rtspTransport: tcp`, ไม่มีใคร publish ได้, ปิด RTMP / HLS / SRT / MoQ / API, `webrtcAdditionalHosts: [10.4.56.69]` · เพิ่มหัวข้อ "MediaMTX + RTSPS ผ่าน Tailscale" (ติดตั้ง Tailscale บน host, subnet router, ตรวจ fingerprint, apply, อาการเสีย)
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/mediamtx/kustomization.yaml`, `k8s/mediamtx/namespace.yaml`, `k8s/mediamtx/mediamtx.yaml`, `k8s/mediamtx/mediamtx.yml.example`, `../.gitignore`, `README.md`
- **เหตุผล:** กล้องอยู่คนละ network กับ VM → เชื่อมด้วย Tailscale และให้ MediaMTX ดึงภาพแบบเข้ารหัส (RTSPS) · ผู้ใช้เลือก: MediaMTX ดึงจากกล้อง, Tailscale บน host, กล้องรองรับ RTSPS · ตัวเดียวใช้ร่วม uat / prod เพราะ hostPort ซ้ำบน node เดียวไม่ได้
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ตรวจ key ทุกตัวใน `mediamtx.yml.example` กับ `mediamtx.yml` ของ tag v1.21.4 แล้ว (ไม่มี key ที่ไม่รู้จัก) · parse manifest ด้วย PyYAML · **ยังไม่ได้ apply บน VM / ยังไม่ได้ต่อกล้องจริง** (เครื่องที่แก้ไม่มี kubectl) · ยังไม่ได้ทำ WHEP ผ่าน NGINX ของ frontend (ดูผ่าน bscit จะโดน mixed content) และ ai-service ยังไม่อ่าน RTSP · ผู้ใช้ต้องติดตั้ง Tailscale และเปิด firewall 8889/tcp, 8189/udp เอง (sudo)

### 2026-11-24 — รหัสผ่านบัญชีทดสอบ uat และ admin คนแรกของ prod
- **ทำอะไร:** เพิ่ม `k8s/sql/uat-reset-passwords.sql` (ตั้งรหัสผ่านทุกบัญชีใน uat เป็น `Password@123` เฉพาะบัญชีที่ยังไม่ใช่ · แสดงรายชื่อที่เปลี่ยน · รันซ้ำได้ · ไม่มีข้อมูล mock = หยุด) และ `k8s/sql/prod-first-admin.sql` (สร้าง admin คนแรก · รหัสผ่านพิมพ์ตอนรันแล้วส่งทาง stdin · หยุดเมื่อมี admin แล้ว / อีเมลซ้ำ / รหัสสั้นกว่า 12 ตัว / ใช้ `Password@123`) · เพิ่มในตาราง "ไฟล์" และ "ข้อควรรู้"
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/sql/uat-reset-passwords.sql`, `k8s/sql/prod-first-admin.sql`, `README.md`
- **เหตุผล:** ผู้ใช้ต้องการให้ทุกบัญชีใน uat ใช้ `Password@123` (รวมบัญชีที่เพิ่ม / แก้รหัสเองหลัง seed) ส่วน prod ให้ผู้ใช้ตั้งรหัสและพิมพ์ login เอง (CLAUDE.md ข้อ 17) · ไม่วางใน `backend/db/` เพราะ CD จะหยุด (ข้อ 25)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบกับ Postgres ชั่วคราวในเครื่อง (schema + `02_seed_mock.sql`): uat เปลี่ยน 2/52 บัญชีที่ทำให้ผิดไว้ → 52/52 ใช้ `Password@123`, รันซ้ำเปลี่ยน 0, DB ที่ไม่มี mock หยุด · prod: ทดสอบ wrapper ใน header ทุกกรณี (รหัสไม่ตรง, มี `'`, สั้น, `Password@123`, สำเร็จพร้อมอักขระพิเศษ, มี admin แล้ว) · **ยังไม่ได้รันบน VM** — ผู้ใช้ต้องรันเอง (แก้ข้อมูลใน DB) · prod ยังต้องมี `semesters` / `consent_versions`

### 2026-11-24 — เตรียม HTTPS ตรงที่ VM (ทางที่ 2) ใน `ingress.yaml` (ยังไม่เปิดใช้)
- **ทำอะไร:** เพิ่มคู่มือขั้น 0–6 และ manifest ที่คอมเมนต์ไว้ใน `k8s/base/ingress.yaml`: Ingress `web-https` (websecure, host `cp26ms3.sit.kmutt.ac.th`, TLS Secret `web-tls`) + Middleware `strip-bscit-prefix` ตัด `/capstone26/cp26ms3` ให้เหลือ `/uat/` เหมือนที่ bscit ส่งมา · คู่มือครอบคลุมตรวจใบ / key, สร้าง Secret (sudo), patch ของ overlay uat, `CORS_ORIGINS`, คำสั่งตรวจผล · แก้ "ข้อควรรู้" เรื่อง HTTPS ให้ตรงกับปัจจุบัน
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/base/ingress.yaml`, `README.md`
- **เหตุผล:** ผู้ใช้มีใบรับรองของ `cp26ms3.sit.kmutt.ac.th` (ออกโดย SITCertAuthCA) และอยากเปิดได้ทั้งผ่าน bscit และ HTTPS ตรงที่ VM ด้วย build เดียว — ใช้ path ในเบราว์เซอร์เดียวกับ bscit จึงไม่ต้องแก้ `VITE_BASE` / `APP_PATH` · Ingress `web` เดิมไม่แตะ (ใส่ host / redirect แล้ว bscit จะเข้าไม่ได้)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ส่วน manifest ที่ทำงานจริงไม่เปลี่ยน (ตรวจด้วย PyYAML เทียบกับ HEAD และจำลองการเปิดกรอบ A/B + patch ของ uat) · ยังไม่ได้รัน `kubectl kustomize` (เครื่องที่แก้ไม่มี kubectl) · SITCertAuthCA ไม่ใช่ CA สาธารณะ — ไม่อยู่ใน root ของ macOS · ยังไม่ได้ทำ redirect HTTP→HTTPS ของทาง 2 และ MediaMTX

### 2026-11-24 — บันทึกประวัติ deploy uat ผ่าน bscit (`66c6337`)
- **ทำอะไร:** เพิ่มแถว 2026-11-24 ในตาราง "ประวัติการ deploy"
- **ไฟล์ที่เกี่ยวข้อง:** `README.md`
- **เหตุผล:** ผู้ใช้สั่งให้บันทึกหลังแก้ login ผ่าน bscit ได้ (กฎ CLAUDE.md ข้อ 21)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ยังไม่ได้ทดสอบอัปโหลดรูปใบหน้าและ SSE ผ่าน bscit · `backend/db/README.md` ยังอ้างรายชื่อบัญชีใน `test-accounts.js` (แก้พร้อม commit ที่เปลี่ยน DB ครั้งถัดไป)

### 2026-11-24 — แก้ login ผ่าน bscit ไม่ได้ (CORS)
- **ทำอะไร:** uat `CORS_ORIGINS` เพิ่ม `https://bscit.sit.kmutt.ac.th` · เพิ่มแถวในตาราง "ปัญหาที่เจอตอน deploy" และหมายเหตุในหัวข้อ "forward จาก bscit"
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/overlays/uat/kustomization.yaml`, `README.md`
- **เหตุผล:** ผู้ใช้ login ที่ `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/uat/login` ไม่ได้ — เบราว์เซอร์ส่ง `Origin` ของ bscit ตอน POST แต่ backend เห็น request เป็น HTTP host ของ VM จึงปฏิเสธเป็น CORS (ตรวจ: ใส่ `Origin` แล้ว `/api/me` ได้ 403, login ผ่าน bscit ได้ 400) · `curl` ที่ใช้ตรวจก่อนหน้านี้ไม่ส่ง `Origin` จึงไม่เห็นปัญหา
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** prod ต้องใส่ค่าเดียวกันตอนเปิดผ่าน bscit · `deploy.sh` ยังตรวจผลแบบไม่ส่ง `Origin`

### 2026-11-24 — uat เปิด `TEST_ACCOUNTS_ENABLED`
- **ทำอะไร:** เพิ่ม `TEST_ACCOUNTS_ENABLED=true` ใน configMap ของ `k8s/overlays/uat` (prod ไม่ตั้ง = ปิด)
- **ไฟล์ที่เกี่ยวข้อง:** `k8s/overlays/uat/kustomization.yaml`
- **เหตุผล:** หน้า Login ของ uat โหลดบัญชีทดสอบจาก DB ผ่าน `GET /api/test-accounts`
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ห้ามใส่ใน overlay prod

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
