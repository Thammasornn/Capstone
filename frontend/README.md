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

## หน้าจอ → API

| หน้า | route | API |
|---|---|---|
| เลือกห้องเรียน | `/classrooms` | B1 (รีเฟรชทุก 30 วินาที) |
| Room Live | `/classrooms/:roomId` | B4, B2 (ภาพสด WHEP จาก MediaMTX), B3 SSE (`camera` / `faces` / `check_in` / `session`), 5.8, B8 |
| วิเคราะห์การเข้าเรียน | `/courses/analytics` | 5.4–5.6 (การ์ด, กราฟรายคาบ, ตารางคาบ, ตารางนักศึกษา + ความเสี่ยง) |
| นักศึกษากลุ่มเสี่ยง | `/courses/at-risk` | 5.6 |
| สถานะลงทะเบียนใบหน้า | `/courses/registration` | 5.6 (`faceRegistered`) |
| จัดการรายวิชา | `/courses/manage` | 5.3, B6 (สวิตช์กล้อง), B5 DELETE |
| เพิ่ม / แก้ไขรายวิชา | `/courses/new`, `/courses/:id/edit` | B5, `GET /rooms/options`, `GET /lecturers` |
| รายงานรายคาบ | `/reports/sessions/:sessionId` | 5.7–5.8, B8 |
| หน้าแรกนักศึกษา / วิชาของฉัน | `/student`, `/student/courses` | 5.3 + 5.9 ของทุกวิชา |
| ประวัติการเข้าเรียน | `/student/attendance` | 5.9 |
| ลงทะเบียนใบหน้า | `/student/face` | 5.10 (+ `ageConfirmed`), `/me` |
| จัดการข้อมูลใบหน้า | `/student/face/manage` | 5.11, B7 |

## ชั้นของโค้ด

`app → features → entities → shared` — ฟีเจอร์ห้าม import ฟีเจอร์อื่น (ESLint ตรวจให้ที่ `shared/` และ `entities/`)

## บันทึกการทำงาน (Changelog)

### 2026-11-22 — ทุกหน้าเรียก API จริง (แทนหน้า "กำลังพัฒนา")
- **ทำอะไร:**
  - component กลาง: `DataTable` (sort), `StatCard`, `BaseSelect`, `BaseToggle` (role=switch), `BaseDialog` (`<dialog>`), `RefreshButton`, `TextField` (ใหม่) · composable `useAsyncState`, `usePolling` (หยุดเมื่อซ่อนแท็บ) · `CourseSectionSelect` ผูก `useCourseStore`
  - หน้าอาจารย์ / admin: เลือกห้องเรียน, Room Live (SSE อัปเดตรายชื่อสด + toast, ภาพสด WHEP, เช็คชื่อด้วยมือ), วิเคราะห์การเข้าเรียน (Chart.js), กลุ่มเสี่ยง, สถานะลงทะเบียนใบหน้า, จัดการรายวิชา + ฟอร์มเพิ่ม/แก้ไข (ตรวจข้อมูลเหมือน Backend, timeline ช่วงเช็คชื่อ), รายงานรายคาบ
  - หน้านักศึกษา: หน้าแรก (ภาพรวม + เรียนวันนี้), วิชาของฉัน, ประวัติการเข้าเรียน, ลงทะเบียนใบหน้า (ยินยอม → ถ่ายด้วยกล้อง / เลือกรูป → ส่ง), จัดการข้อมูลใบหน้า (ถอนความยินยอม)
  - `ManualAttendanceDialog` (B8) อยู่ใน `entities/attendance` เพราะใช้ทั้ง Room Live และรายงานรายคาบ
  - `registerFace` ส่ง `ageConfirmed` · `datetime.js` รับเวลาที่มีเศษวินาทีจาก Backend
  - `vite.config.js`: proxy คง `Host` เดิม (`changeOrigin: false`) — เดิมเปลี่ยน port ของ FE แล้ว login ไม่ได้ (Spring ตอบ 403 CORS)
  - test ใหม่ `tests/unit/course-form.test.js` (ตรวจฟอร์มรายวิชา)
- **ไฟล์ที่เกี่ยวข้อง:** `src/shared/**`, `src/entities/**`, `src/features/**`, `vite.config.js`, `tests/unit/*`
- **เหตุผล:** หน้าจอทั้งหมดยังเป็นโครงเปล่าจาก scaffold รอบแรก ทำให้ข้อมูลไม่แสดงแม้ FE ต่อ BE ได้แล้ว
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:**
  - ทดสอบใน browser กับ Backend จริง + ข้อมูล mock ครบทุกหน้า ทั้ง 3 role (lecturer / admin / student) รวม SSE check-in สด, B5 สร้าง/แก้/ลบ, B6, B7, B8 · unit test 51 ข้อผ่าน
  - ยังไม่ได้ทดสอบ: ภาพสดจริง (ต้องมี MediaMTX) และลงทะเบียนใบหน้าสำเร็จจริง (ต้องมี AI Service + โมเดล) — ตอนนี้ตรวจแค่ว่าแสดง error ถูกต้อง
  - หน้าจอยังไม่ได้เทียบกับ Figma (ไม่มีสิทธิ์เข้าไฟล์) ใช้ design token เดิมใน `main.css`
  - feature ใช้ `useAuthStore` จาก `features/auth` (ข้อมูล session ที่ทุกหน้าต้องใช้)

### 2026-11-21 — ลบ mock B1–B8 และเชื่อม Backend จริงผ่าน proxy `/api`
- **ทำอะไร:** ลบ `src/mocks/handlers/pending.js` + โหมด `missing` · dev ค่าเริ่มต้นต่อ Backend จริง · เพิ่ม Vite proxy (`DEV_BACKEND_URL`) และ NGINX proxy `<base>api/` → `BACKEND_URL` (รองรับ SSE, อัปโหลด 25MB, ปิด `/api/internal/`) · ลบ test ของ mock B1/B6/B7
- **ไฟล์ที่เกี่ยวข้อง:** `.env.development`, `.env.uat`, `.env.production`, `vite.config.js`, `nginx.conf`, `Dockerfile`, `src/main.js`, `src/mocks/*`, `tests/unit/mock-api.test.js`
- **เหตุผล:** Backend ทำ B1–B8 ครบแล้ว และต้องใช้ได้ทั้งเครื่อง local และบน VM (K3s) โดยไม่ต้อง build ใหม่ตาม server
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบแล้ว: unit test 46 ข้อ, lint, build ทั้ง 2 mode, login / B1 / SSE / error ผ่าน Vite proxy และ NGINX config จริงกับ Backend · หน้าจอ (Room, Course form ฯลฯ) ยังเป็นโครงเปล่า ต้องทำ UI ต่อ
