# Bangmod Smart Attendance — Front-End

Vue 3 (JavaScript) + Vite · Pinia · Vue Router · Tailwind CSS v4 · Axios · Chart.js (vue-chartjs) · MSW · Vitest

โครงสร้างและกติกาทั้งหมดอยู่ใน Project doc `claude/frontend-structure.md` และสกิล `senior-frontend-dev`

## เริ่มใช้งาน

```bash
npm ci
npm run dev        # http://localhost:5173 · /api ถูก proxy ไป Backend (DEV_BACKEND_URL)
```

ค่าเริ่มต้นต่อ **Backend จริง** ที่ `http://localhost:8081` (รัน `mvn spring-boot:run` ใน `backend/` ก่อน · บัญชีทดสอบดู `backend/db/README.md`)

- ต่อ Backend เครื่องอื่น / บน VM: สร้าง `.env.development.local` (ไม่ถูก commit) ใส่ `DEV_BACKEND_URL=http://<host>:8081`
- ไม่รัน Backend: ใส่ `VITE_MOCK_MODE=all` ใน `.env.development.local` → mock API v1.0 (5.1–5.11) บัญชี mock (รหัสผ่าน `Passw0rd!`):
  `student01@mock.test` – `student10@mock.test`, `lecturer01@mock.test` – `lecturer05@mock.test`, `admin@mock.test`
  (หน้า Room / จัดการรายวิชา / เช็คชื่อด้วยมือ ไม่มี mock แล้ว ต้องใช้ Backend จริง)

| คำสั่ง | ทำอะไร |
|---|---|
| `npm run lint` | oxlint + ESLint (รวมกฎห้าม import ข้ามชั้น) |
| `npm run fix` | แก้ lint อัตโนมัติ |
| `npm test` | Vitest (ใช้ MSW ของ API v1.0) |
| `npm run build` | build Production (base `/prod/`, ไม่มีโค้ด mock) |
| `npm run build:uat` | build UAT (base `/uat/`, ไม่มีโค้ด mock) |

## Environment และการเชื่อม Backend

FE เรียก API แบบ **relative path เสมอ** (`<base>api/...`) แล้วมี proxy ส่งต่อไป Spring Boot ที่ `/api/...` จึงไม่ต้องตั้ง CORS และใช้ build เดียวได้ทุกเครื่อง

| ที่รัน | proxy | ตั้ง Backend ที่ไหน |
|---|---|---|
| `npm run dev` / `npm run preview` | Vite (`vite.config.js`) | `DEV_BACKEND_URL` ใน `.env.development(.local)` |
| container (VM / K3s) | NGINX (`nginx.conf`) | env `BACKEND_URL` ของ container (ค่าเริ่มต้น `http://backend:8081`) |

| ไฟล์ | `VITE_BASE` | `VITE_API_BASE_URL` | `VITE_MOCK_MODE` |
|---|---|---|---|
| `.env.development` | `/` | `/api` | `off` (ใส่ `all` ใน `.local` เพื่อใช้ mock) |
| `.env.uat` | `/uat/` | `/uat/api` | `off` |
| `.env.production` | `/prod/` | `/prod/api` | `off` |
| `.env.test` | `/` | `http://localhost:8081/api` | `all` (Vitest) |

```bash
docker build -t bangmod-frontend:prod .
docker run -p 8080:80 -e BACKEND_URL=http://10.0.0.5:8081 bangmod-frontend:prod   # เปิด http://localhost:8080/prod/
```

## Mock (MSW)

- `src/mocks/handlers/v1.js` — API v1.0 (5.1–5.11) ใช้ตอน `VITE_MOCK_MODE=all` และใน Vitest
- B1–B8 ไม่มี mock แล้ว (Backend ทำครบ) · ลบ `pending.js` และโหมด `missing` ออกแล้ว
- ทดสอบ error การลงทะเบียนใบหน้า: ตั้งชื่อไฟล์มี `noface` → 422, มี `aidown` → 502

## ชั้นของโค้ด

`app → features → entities → shared` — ฟีเจอร์ห้าม import ฟีเจอร์อื่น (ESLint ตรวจให้ที่ `shared/` และ `entities/`)

## บันทึกการทำงาน (Changelog)

### 2026-11-21 — ลบ mock B1–B8 และเชื่อม Backend จริงผ่าน proxy `/api`
- **ทำอะไร:** ลบ `src/mocks/handlers/pending.js` + โหมด `missing` · dev ค่าเริ่มต้นต่อ Backend จริง · เพิ่ม Vite proxy (`DEV_BACKEND_URL`) และ NGINX proxy `<base>api/` → `BACKEND_URL` (รองรับ SSE, อัปโหลด 25MB, ปิด `/api/internal/`) · ลบ test ของ mock B1/B6/B7
- **ไฟล์ที่เกี่ยวข้อง:** `.env.development`, `.env.uat`, `.env.production`, `vite.config.js`, `nginx.conf`, `Dockerfile`, `src/main.js`, `src/mocks/*`, `tests/unit/mock-api.test.js`
- **เหตุผล:** Backend ทำ B1–B8 ครบแล้ว และต้องใช้ได้ทั้งเครื่อง local และบน VM (K3s) โดยไม่ต้อง build ใหม่ตาม server
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบแล้ว: unit test 46 ข้อ, lint, build ทั้ง 2 mode, login / B1 / SSE / error ผ่าน Vite proxy และ NGINX config จริงกับ Backend · หน้าจอ (Room, Course form ฯลฯ) ยังเป็นโครงเปล่า ต้องทำ UI ต่อ
