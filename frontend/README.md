# Bangmod Smart Attendance — Front-End

Vue 3 (JavaScript) + Vite · Pinia · Vue Router · Tailwind CSS v4 · Axios · Chart.js (vue-chartjs) · MSW · Vitest

โครงสร้างและกติกาทั้งหมดอยู่ใน Project doc `claude/frontend-structure.md` และสกิล `senior-frontend-dev`

## เริ่มใช้งาน

```bash
npm ci
npm run dev        # http://localhost:5173 (พอร์ตนี้เท่านั้น เพราะ Backend เปิด CORS ให้พอร์ตนี้)
```

โหมด dev ใช้ mock ทุก endpoint ไม่ต้องรัน Backend · บัญชีทดสอบ (รหัสผ่าน `Passw0rd!`):
`student01@mock.test` – `student10@mock.test` (01–07 ลงทะเบียนใบหน้าแล้ว), `lecturer01@mock.test` – `lecturer05@mock.test`, `admin@mock.test`

| คำสั่ง | ทำอะไร |
|---|---|
| `npm run lint` | oxlint + ESLint (รวมกฎห้าม import ข้ามชั้น) |
| `npm run fix` | แก้ lint อัตโนมัติ |
| `npm test` | Vitest (ใช้ MSW ชุดเดียวกับเบราว์เซอร์) |
| `npm run build` | build Production (base `/prod/`, ไม่มีโค้ด mock) |
| `npm run build:uat` | build UAT (base `/uat/`, mock เฉพาะ B1–B8) |

## Environment

| ไฟล์ | `VITE_BASE` | `VITE_API_BASE_URL` | `VITE_MOCK_MODE` |
|---|---|---|---|
| `.env.development` | `/` | `http://localhost:8081/api` | `all` — mock ทุก endpoint |
| `.env.uat` | `/uat/` | `/uat/api` | `missing` — mock เฉพาะ API ที่ Backend ยังไม่มี |
| `.env.production` | `/prod/` | `/prod/api` | `off` |

ต่อ Backend จริงตอน dev: แก้ `VITE_MOCK_MODE=missing` ใน `.env.development.local` (ไม่ถูก commit)

## Mock (MSW)

- `src/mocks/handlers/v1.js` — API ที่มีแล้วในคู่มือ v1.0 (5.1–5.11)
- `src/mocks/handlers/pending.js` — API ที่ขอเพิ่ม B1–B8 · **Backend ทำข้อไหนเสร็จ ให้ลบ handler ข้อนั้น**
- ทดสอบ error การลงทะเบียนใบหน้า: ตั้งชื่อไฟล์มี `noface` → 422, มี `aidown` → 502

## ชั้นของโค้ด

`app → features → entities → shared` — ฟีเจอร์ห้าม import ฟีเจอร์อื่น (ESLint ตรวจให้ที่ `shared/` และ `entities/`)
