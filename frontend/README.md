# Bangmod Smart Attendance — Front-End

Vue 3 (JavaScript) + Vite · Pinia · Vue Router · Tailwind CSS v4 · Axios · Chart.js (vue-chartjs) · MSW · Vitest

กติกาการแบ่งชั้นของโค้ดดูหัวข้อ [ชั้นของโค้ด](#ชั้นของโค้ด) ด้านล่าง (ESLint ตรวจให้)

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
| `npm run build:uat` | build UAT (base `/capstone26/cp26ms3/uat/`, NGINX บน VM `/uat/`, ไม่มีโค้ด mock) |

## Environment และการเชื่อม Backend

FE เรียก API แบบ **relative path เสมอ** (`<base>api/...`) แล้วมี proxy ส่งต่อไป Spring Boot ที่ `/api/...` จึงไม่ต้องใช้ CORS preflight และใช้ build เดียวได้ทุกเครื่อง · แต่ backend ยังตรวจ header `Origin` — เปิดจากโดเมนอื่นที่ไม่ใช่ host ที่ backend เห็น (เช่น bscit หรือ local คนละ host / port) ต้องใส่โดเมนนั้นใน `CORS_ORIGINS` ของ backend (ดู `devops/README.md`)

| ที่รัน | proxy | ตั้ง Backend ที่ไหน |
|---|---|---|
| `npm run dev` / `npm run preview` | Vite (`vite.config.js`) | `DEV_BACKEND_URL` ใน `.env.development(.local)` |
| container (VM / K3s) | NGINX (`nginx.conf`) | env `BACKEND_URL` ของ container (ค่าเริ่มต้น `http://backend:8081`) |

| ไฟล์ | `VITE_BASE` | `VITE_API_BASE_URL` | `VITE_MOCK_MODE` |
|---|---|---|---|
| `.env.development` | `/` | `/api` | `off` (ใส่ `all` ใน `.local` เพื่อใช้ mock) |
| `.env.uat` (+ `APP_PATH=/uat/`) | `/capstone26/cp26ms3/uat/` | `/capstone26/cp26ms3/uat/api` | `off` |
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
| Room Live | `/classrooms/:roomId` | B4, B2 (ภาพสด WHEP จาก MediaMTX), B3 SSE (`camera` / `faces` / `check_in` / `session`), 5.8, B8, เริ่มคาบ `POST /rooms/{roomId}/sessions` (เลือกกลุ่มจาก 5.3), จบคาบ `POST /class-sessions/{id}/end`, จัดการคาบ (`reanchor` / `extend` / `check-in-window`), นำเข้าผลเช็คชื่อ |
| วิเคราะห์การเข้าเรียน | `/courses/analytics` | 5.4–5.6 (การ์ด, กราฟรายคาบ, ตารางคาบ, ตารางนักศึกษา + ความเสี่ยง) |
| นักศึกษากลุ่มเสี่ยง | `/courses/at-risk` | 5.6 |
| สถานะลงทะเบียนใบหน้า | `/courses/registration` | 5.6 (`faceRegistered`) |
| จัดการรายวิชา | `/courses/manage` | 5.3, B6 (สวิตช์กล้อง), B5 DELETE |
| เพิ่ม / แก้ไขรายวิชา | `/courses/new`, `/courses/:id/edit` | B5, `GET /rooms/options`, `GET /lecturers` |
| รายงานรายคาบ | `/reports/sessions/:sessionId` | 5.7–5.8, B8, แก้เวลาคาบ ≤ 7 วัน (`reanchor` / `check-in-window`), นำเข้าผลเช็คชื่อ (`import-template`, `import/inspect`, `import`, `imports`) |
| หน้าแรกนักศึกษา / วิชาของฉัน | `/student`, `/student/courses` | 5.3 + 5.9 ของทุกวิชา |
| ประวัติการเข้าเรียน | `/student/attendance` | 5.9 |
| ลงทะเบียนใบหน้า | `/student/face` | 5.10, `/me` |
| จัดการข้อมูลใบหน้า | `/student/face/manage` | 5.11, B7 |

## ชั้นของโค้ด

`app → features → entities → shared` — ฟีเจอร์ห้าม import ฟีเจอร์อื่น (ESLint ตรวจให้ที่ `shared/` และ `entities/`)

## บันทึกการทำงาน (Changelog)

### 2026-11-25 — นำเข้าผลเช็คชื่อจากไฟล์ (C3)
- **ทำอะไร:**
  - ปุ่ม "นำเข้าผลเช็คชื่อ" ในหน้าห้อง (มีคาบที่กำลังเรียน) และรายงานคาบ (คาบเริ่มแล้ว ไม่ใช่ scheduled / cancelled) → `AttendanceImportWizard.vue`
    - ขั้น 1 เลือกแบบ (ไม่เลือกให้): template ของระบบ (ปุ่มดาวน์โหลด XLSX / CSV + วิธีกรอก) หรือไฟล์อื่น (เลือกที่มา Teams / Zoom / Google Form / ใบเซ็นชื่อ / อื่น ๆ) · เลือกไฟล์ (ตรวจ .csv / .xlsx และ ≤ 2 MB ก่อนส่ง) · เหตุผลบังคับ ≤ 300 ตัวอักษร · แจ้งข้อจำกัดไฟล์ + วิธีเตรียมรายงาน Teams
    - ขั้น 2 (ไฟล์อื่น) `ImportMappingStep.vue`: ตาราง sampleRows · ทุก dropdown เริ่ม "— เลือก —" · คอลัมน์ตัวระบุ + ชนิด (บังคับ) · คอลัมน์เวลา → ต้องเลือกรูปแบบเวลา (แสดงค่าจริงในไฟล์ให้เทียบ) · คอลัมน์สถานะ → จับคู่ทุกค่าใน `distinctValues` (ไม่มีในนั้น = ค่าเกิน 20 ใช้ไม่ได้ แจ้งเตือน) · ไม่เลือกทั้งคู่ → ต้องเลือกสถานะเริ่มต้น · "ถัดไป" กดได้เมื่อครบ
    - ขั้น 3 `ImportPreviewStep.vue` (`dryRun: true`): เตือน `duplicateOf` · ช่วงเวลาของคาบ · ตัวเลขสรุป · แท็บ จะบันทึก / ขัดแย้ง (ผลเดิม → ผลจากไฟล์ + "จะแทนที่ / คงผลเดิม" ตาม checkbox) / ข้าม / จับคู่ไม่ได้ (เหตุผลภาษาไทย) · checkbox แทนที่ผลจากกล้อง / ผลที่อาจารย์แก้ (ไม่ติ๊กไว้ก่อน) เปลี่ยนแล้วเรียก dryRun ใหม่ · 400 + `errors[]` แสดงตาราง แถว / คอลัมน์ / ค่า / ข้อความ + ปุ่ม "เลือกไฟล์ใหม่" / "แก้การเลือกคอลัมน์"
    - ขั้น 4 "ยืนยันบันทึก" (`dryRun: false`) → toast "บันทึก N คน · ข้าม … · ขัดแย้ง …" → ปิด → โหลดรายชื่อ / สรุปใหม่
  - รายงานคาบ: ส่วน "ประวัติการนำเข้า" (`ImportHistory.vue`: เวลา ผู้นำเข้า ที่มา ไฟล์ จำนวน) กดดูทุกแถวจากไฟล์ (ค่าดิบ → นักศึกษา → สถานะ → ผล · แถวที่บันทึกจริงไฮไลต์)
  - ที่มาของผลเช็คชื่อ `source = import` แสดง "นำเข้า (Teams)" / "นำเข้า (template)" ฯลฯ ตาม `importedFrom` ทั้งตารางรายงานคาบและรายชื่อหน้าห้อง (`attendanceSourceLabel`) · SSE `check_in` เก็บ `importedFrom` ถ้ามี
  - `shared/api/errors.js`: `ApiError.errors` = `errors[]` จาก body (ไม่มี = `null` · หน้าอื่นไม่กระทบ) · `shared/api/http.js`: `downloadFile()` (ผ่าน axios แนบ token → blob → save · ชื่อไฟล์จาก `Content-Disposition` รวม `filename*=UTF-8''`) · `BaseDialog` เพิ่ม prop `wide` · `BaseSelect` ไม่ขยายเกินจอเมื่อตัวเลือกยาว (`w-full min-w-0`)
- **ไฟล์ที่เกี่ยวข้อง:** `src/entities/attendance/import.js`, `src/entities/attendance/components/{AttendanceImportWizard,ImportMappingStep,ImportPreviewStep}.vue`, `src/features/session-report/components/ImportHistory.vue`, `src/features/session-report/components/AttendanceRosterTable.vue`, `src/features/session-report/pages/SessionReportPage.vue`, `src/features/room-monitor/pages/RoomLivePage.vue`, `src/features/room-monitor/components/LiveStudentList.vue`, `src/shared/api/errors.js`, `src/shared/api/http.js`, `src/shared/components/BaseDialog.vue`, `src/shared/components/BaseSelect.vue`
- **เหตุผล:** ใช้ API นำเข้าผลเช็คชื่อของ backend (C2) ให้อาจารย์นำผลจากใบเซ็นชื่อ / Teams / Google Form เข้าระบบ โดยเห็นผลก่อนบันทึกและย้อนดูได้
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** wizard + API อยู่ที่ `entities/attendance` (ไม่ใช่ `features/session-report` ตามที่ prompt ยกตัวอย่าง) เพราะหน้าห้องก็ใช้ และฟีเจอร์ห้าม import กันเอง · ทดสอบใน browser กับ backend จริง + Postgres ชั่วคราว (01 + 02 + 03) บัญชี admin: หน้าห้องบนจอ 375px — ไฟล์ Teams CSV (อีเมล + `h:mm:ss a`) → preview 18:05 ตรงเวลา / 18:30 สาย / อีเมลไม่มีในระบบ = จับคู่ไม่ได้ → บันทึก 2 คน รายชื่อแสดง "นำเข้า (Teams)" · รายงานคาบ: ประวัติ + รายละเอียดรายแถว, template ผิด 3 แถว → ตาราง error, template ที่ขัดแย้งกับผลที่อาจารย์แก้ → ติ๊กแทนที่ → dryRun ใหม่ → บันทึก "นำเข้า (template)", Google Form คอลัมน์สถานะจับคู่ 2 ค่า → preview ถูก · ดาวน์โหลด template ตรวจด้วย curl (header `Content-Disposition`) + ทดสอบตัวแยกชื่อไฟล์ (ไม่ได้กดดาวน์โหลดใน browser เพื่อไม่ save ไฟล์ลงเครื่อง) · ยังไม่ได้ทดสอบใน browser: แถบเตือน `duplicateOf`, ไฟล์ XLSX จริง, มือถือจริง

### 2026-11-25 — เมนู "จัดการคาบ" + Preview & Confirm (ปรับเวลาคาบที่เริ่มแล้ว)
- **ทำอะไร:**
  - `entities/attendance/api.js` เพิ่ม `reanchorSession`, `extendSession`, `adjustCheckInWindow` (`POST /class-sessions/{id}/reanchor | extend | check-in-window`)
  - component ใหม่ใน `entities/attendance/components/` (ใช้ทั้งหน้าห้องและรายงานคาบ): `SessionAdjustMenu.vue` (ปุ่ม + เมนู ปิดเมื่อกดนอกเมนู / Esc, ปุ่มสูง 48px กดบนมือถือได้), `SessionAdjustDialog.vue` (ฟอร์ม + เวลาปัจจุบันของคาบ + เหตุผลบังคับ · "ดูผลกระทบ" = `dryRun: true` → "ยืนยัน" = เรียกซ้ำ `dryRun: false` · ยืดเวลาจบบันทึกเลยไม่มี preview · error 400/403/409 แสดงข้อความ backend ใน dialog ผ่าน `normalizeError`), `SessionAdjustPreview.vue` (เวลา เดิม → ใหม่ + รายชื่อ 3 กลุ่ม: ผลดีขึ้น / ผลแย่ลง มี checkbox "ใช้ผลที่แย่ลงด้วย" = `applyDowngrades` ค่าเริ่มต้นไม่ติ๊ก / เปิดให้เช็คชื่อใหม่ · "ผลที่ลงด้วยมือจะไม่เปลี่ยน") · `adjust-summary.js` ข้อความ toast สรุปผล
  - `RoomLivePage.vue`: มีคาบ → เมนู "จัดการคาบ" ข้างปุ่ม "จบคาบ" (เริ่มนับเวลาใหม่ / ยืดเวลาจบ / ปรับช่วงเช็คชื่อ) · บันทึกแล้ว toast + โหลดห้อง / รายชื่อใหม่ (SSE ส่ง event ให้ด้วย · SSE หลุดมี poll 15 วินาที)
  - `SessionReportPage.vue`: คาบ `completed` ที่ `sessionDate` ไม่เกิน 7 วัน → ปุ่ม "แก้เวลาคาบ" (เริ่มนับเวลาใหม่ / ปรับช่วงเช็คชื่อ) · เกิน 7 วันแสดง "แก้รายคนด้วยการเช็คชื่อด้วยมือ"
  - ฟอร์มรายวิชา: แก้บั๊ก `toPayload` ไม่ส่ง `lateUntil` (backend เขียน `late_until = NULL` ทุกครั้งที่แก้) → ส่งค่าที่โหลดมากลับไปถ้ายังอยู่ในช่วง [เปิด + ตรงเวลา, ปิดเช็คชื่อ] · หลังบันทึกถ้า `startedSessionsNotChanged` ไม่ว่าง แสดงกล่องแจ้งรายคาบ ("คาบวันที่ … (กลุ่ม …) เริ่มไปแล้ว จึงไม่ถูกแก้") + ลิงก์ไปรายงานคาบ / หน้าห้อง แทนการกลับหน้ารายการเงียบ ๆ
- **ไฟล์ที่เกี่ยวข้อง:** `src/entities/attendance/api.js`, `src/entities/attendance/adjust-summary.js`, `src/entities/attendance/components/SessionAdjustMenu.vue`, `src/entities/attendance/components/SessionAdjustDialog.vue`, `src/entities/attendance/components/SessionAdjustPreview.vue`, `src/features/room-monitor/pages/RoomLivePage.vue`, `src/features/session-report/pages/SessionReportPage.vue`, `src/features/course-management/composables/useCourseForm.js`, `src/features/course-management/pages/CourseFormPage.vue`
- **เหตุผล:** backend เพิ่มเส้นปรับเวลาคาบที่เริ่มแล้ว (อาจารย์เข้าสาย เลิกช้า ฝนตก) · เดิมแก้รายวิชาแล้วคาบวันนี้ไม่เปลี่ยนแต่หน้าจอไม่บอก
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** เวลาปัจจุบันของคาบ (before) และค่าเริ่มต้นของฟอร์มอ่านจาก `checkInOpens` / `onTimeUntil` / `lateUntil` / `checkInCloses` ที่ backend เพิ่มใน B4 `currentSession` และ 5.7 summary (commit `22f5ad1`) · ทดสอบใน browser กับ backend จริง + Postgres ชั่วคราว (seed 02 + 03) บัญชี admin: หน้าห้องบนจอมือถือ 375px — เมนูไม่ล้นจอ, reanchor 400 แสดงข้อความ backend ใน dialog, preview late → on_time แล้วยืนยัน หัวข้อ / ตัวนับอัปเดต, ปรับช่วงเช็คชื่อ: ค่าเริ่มต้นตรงกับคาบ + downgrade ไม่ติ๊ก = คงผลเดิม (toast "แย่ลง 1 คน (คงผลเดิม)"), ยืดเวลาจบ 20:12 → 20:30 · รายงานคาบเมื่อวานมีปุ่ม + preview, คาบ 8 วันก่อนแสดงข้อความเช็คชื่อด้วยมือ · แก้เวลาเลิกของ INT201 → กล่องแจ้งคาบวันนี้ที่ไม่ถูกแก้ และ `late_until` คงเดิม · ข้อสังเกต: หลังปรับเวลา SSE `check_in` ของแถวที่ถูกคิดใหม่ทำให้มี toast "… เข้าเรียนแล้ว" ซ้ำกับ toast สรุป · ไม่มี MSW mock (หน้าห้อง / รายงานคาบใช้ backend จริง)

### 2026-11-25 — ปรับ README ให้ตรงกับโค้ดปัจจุบัน
- **ทำอะไร:** ตารางหน้าจอ → API ของ Room Live เพิ่มเส้นเริ่ม / จบคาบ · แก้ "ไม่ต้องตั้ง CORS" เป็นคำอธิบาย `CORS_ORIGINS` (เปิดผ่าน bscit ต้องตั้ง) · เอาลิงก์ `claude/frontend-structure.md` (ไม่มีใน repo) ออก ชี้ไปหัวข้อ "ชั้นของโค้ด" แทน · `tests/e2e/README.md` เอาการอ้างถึงไฟล์นั้นออก
- **ไฟล์ที่เกี่ยวข้อง:** `README.md`, `tests/e2e/README.md`
- **เหตุผล:** เอกสารไม่ตรงกับโค้ดและอ้างไฟล์ที่ไม่มี

### 2026-11-25 — ปุ่มเริ่มคาบ / จบคาบในหน้าห้อง
- **ทำอะไร:** `RoomLivePage.vue` ไม่มีคาบ → ปุ่ม "เริ่มคาบตอนนี้" เปิด `StartSessionDialog.vue` (เลือกกลุ่มเรียนจาก `GET /courses` กลุ่มที่เรียนห้องนี้ขึ้นก่อน → `POST /rooms/{roomId}/sessions` → toast เวลาตรงเวลา / สาย) · มีคาบ → ปุ่ม "จบคาบ" + กล่องยืนยัน (`POST /class-sessions/{id}/end`) · `room-monitor/api.js` เพิ่ม `getStartableCourses`, `startSessionNow`, `endSessionNow`
- **ไฟล์ที่เกี่ยวข้อง:** `src/features/room-monitor/pages/RoomLivePage.vue`, `src/features/room-monitor/components/StartSessionDialog.vue`, `src/features/room-monitor/api.js`
- **เหตุผล:** เริ่มคาบเพื่อทดสอบกล้องจากหน้าเว็บแทนการรัน SQL เอง
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบใน browser (dev server + backend จริง + Postgres ชั่วคราว) ด้วยบัญชีอาจารย์: เริ่มคาบ → หัวข้อแสดงวิชา / เวลา, รายชื่อ 3 คน, ปุ่มเปลี่ยนเป็น "จบคาบ" · จบคาบ → กลับเป็น "ยังไม่มีคาบ" ทันที · ไม่มี MSW mock (หน้าห้องต้องใช้ backend จริงอยู่แล้ว)

### 2026-11-25 — หน้าห้องอัปเดตข้อมูลจริงเมื่อเปิดผ่าน bscit (SSE)
- **ทำอะไร:** `nginx.conf` ส่วน SSE (`<base>api/rooms/{id}/events`) เพิ่ม `add_header X-Accel-Buffering no always` · `RoomLivePage.vue` เมื่อ SSE ยังไม่ `open` (เชื่อมต่อไม่ได้ / หลุด) โหลดรายชื่อใหม่ทุก 15 วินาที (`loadRoster({ silent: true })` ไม่แสดง toast)
- **ไฟล์ที่เกี่ยวข้อง:** `nginx.conf`, `src/features/room-monitor/pages/RoomLivePage.vue`
- **เหตุผล:** ผู้ใช้ทดสอบผ่าน bscit แล้วข้อมูลไม่อัปเดต หน้าห้องค้างป้าย "กำลังเชื่อมต่อ" · รายชื่ออัปเดตผ่าน event `check_in` อย่างเดียว · จำลองด้วย nginx 2 ชั้นในเครื่อง: nginx ข้างหน้าที่ตั้งค่าแบบเริ่มต้น (ต่อ upstream ด้วย HTTP/1.0 หรือเปิด gzip) กัก event ไว้จน stream จบ — ใส่ header แล้ว event มาทีละวินาทีตามจริง (nginx เคารพ `X-Accel-Buffering` จาก upstream)
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ยังไม่ได้ยืนยันกับ bscit จริง (ต้อง login) — หลัง deploy ป้ายต้องเปลี่ยนเป็น "เชื่อมต่อแล้ว" · ถ้ายังค้าง รายชื่อจะอัปเดตทุก 15 วินาทีจาก poll แทน · faces / camera ยังมาจาก SSE (สถานะกล้อง poll กับข้อมูลห้องทุก 30 วินาทีอยู่แล้ว)

### 2026-11-24 — NGINX ส่ง `<base>media/*` ไป MediaMTX (ภาพสด WHEP)
- **ทำอะไร:** `nginx.conf` เพิ่ม `location ${APP_BASE}media/` → `${MEDIAMTX_URL}` (ตัด prefix ด้วย rewrite) ใช้ `resolver ${NGINX_LOCAL_RESOLVERS}` + ตัวแปร ให้หาชื่อ service ตอนมี request · `Dockerfile` เพิ่ม `ENV MEDIAMTX_URL=http://mediamtx.media.svc.cluster.local:8889` และ `NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1` (entrypoint ของ nginx image อ่าน nameserver จาก `/etc/resolv.conf`)
- **ไฟล์ที่เกี่ยวข้อง:** `nginx.conf`, `Dockerfile`
- **เหตุผล:** หน้าเว็บเปิดผ่าน HTTPS ของ bscit แต่ WHEP อยู่ที่ `http://cp26ms3…:8889` → เบราว์เซอร์บล็อก (mixed content) ภาพสดขึ้น "เชื่อมต่อเซิร์ฟเวอร์ภาพ (MediaMTX) ไม่ได้" · backend ตั้ง `MEDIA_BASE_URL=<VITE_BASE>media` แล้ว (overlay) → เรียกใน origin เดียวกับหน้าเว็บ · ไม่ resolve ตอน start = ไม่มี MediaMTX ก็ไม่ทำให้ frontend ล่ม
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ตรวจด้วย nginx ในเครื่อง (render template แบบเดียวกับ Dockerfile + envsubst): `nginx -t` ผ่าน · ส่งต่อไป MediaMTX จริงบน VM ได้ (`GET /uat/media/room-4301/` 200, WHEP `OPTIONS` 204, `POST` ได้คำตอบของ MediaMTX) · ชื่อที่ resolve ไม่ได้ → 502 และ nginx ยัง start ได้ · `/uat/api/internal/x` ยัง 404 · ยังไม่ได้ build image จริง (เครื่องที่แก้ไม่มี docker) — `15-local-resolvers.envsh` มีใน nginx image ตั้งแต่ 2023 · ตัวภาพ (UDP 8189) ยังวิ่งตรงไป VM

### 2026-11-24 — ปุ่มบัญชีทดสอบในหน้า Login ใช้ข้อมูลจาก DB
- **ทำอะไร:** โหมด Backend จริง (local / uat) โหลดรายชื่อจาก `GET /test-accounts` ตอนเปิดหน้า แล้ว `groupTestAccounts()` จัดกลุ่ม: เจ้าหน้าที่ (admin + อาจารย์ พร้อมวิชาที่สอน) แล้วนักศึกษาตามสถานะ (ยังไม่ลงทะเบียนใบหน้า → ยินยอมแล้วแต่ยังไม่มีใบหน้า → ต้องยินยอมใหม่ → ถอนความยินยอม → ลงทะเบียนแล้ว → บัญชีถูกปิด) · กลุ่มเกิน 8 บัญชีพับไว้ (`<details>`) · โหลดไม่ได้แสดงข้อความแทน · ลบรายชื่อที่เขียนตายตัว (โหมด MSW ยังใช้รายชื่อ mock เดิม)
- **ไฟล์ที่เกี่ยวข้อง:** `src/features/auth/test-accounts.js`, `src/features/auth/api.js`, `src/features/auth/pages/LoginPage.vue`, `tests/unit/test-accounts.test.js`
- **เหตุผล:** ผู้ใช้ขอให้เอารายชื่อ mock ที่เขียนไว้ใน frontend ออกและใช้ข้อมูลที่มีอยู่ใน DB จริง
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ต้องเปิด `TEST_ACCOUNTS_ENABLED=true` ที่ backend ด้วย (uat ตั้งใน overlay แล้ว) · ป้าย "กลุ่มเสี่ยงสูง / ปานกลาง" แบบเดิมไม่มีแล้ว (ไม่ได้คำนวณจาก DB)

### 2026-11-24 — uat: `VITE_BASE=/capstone26/cp26ms3/uat/`, `APP_PATH=/uat/`
- **ทำอะไร:** `.env.uat` ตั้ง path ที่เบราว์เซอร์เห็นเป็น `/capstone26/cp26ms3/uat/` และ path บน VM (`APP_PATH`) เป็น `/uat/` · อัปเดต comment ใน `vite.config.js`, `nginx.conf`, `router/index.js`, `mocks/browser.js`, `shared/api/sse.js`
- **ไฟล์ที่เกี่ยวข้อง:** `.env.uat`, `vite.config.js`, `nginx.conf`, `src/app/router/index.js`, `src/mocks/browser.js`, `src/shared/api/sse.js`, `README.md`
- **เหตุผล:** เว็บ uat อยู่ที่ `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/uat/` (bscit ตัด `/capstone26/cp26ms3` ออกก่อนส่งมา VM) · HTTPS → กล้องสดใช้ได้
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** เปิด `http://cp26ms3.sit.kmutt.ac.th/uat/` ตรง ๆ ใน browser หน้าจะขาว (assets อ้าง path ของ bscit)

### 2026-11-24 — แยก path บน VM (`APP_PATH`) ออกจาก path ที่เบราว์เซอร์เห็น (`VITE_BASE`)
- **ทำอะไร:** `Dockerfile` ใช้ `APP_PATH` ใน `.env.<MODE>` เป็น location ของ NGINX ถ้ามี (ไม่มี = ใช้ `VITE_BASE` เหมือนเดิม)
- **ไฟล์ที่เกี่ยวข้อง:** `Dockerfile`
- **เหตุผล:** bscit forward `https://bscit.sit.kmutt.ac.th/capstone26/cp26ms3/*` มาที่ VM โดยตัด `/capstone26/cp26ms3` ออก — เบราว์เซอร์เห็น `/capstone26/cp26ms3/uat/` แต่ VM ได้ `/uat/`
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ยังไม่ได้เปลี่ยนค่าใน `.env.uat` — ทำใน commit ถัดไปหลัง CD อัปเดต `deploy.sh` ตัวเอง

### 2026-11-24 — uat ย้ายไป `/capstone26/uat/`
- **ทำอะไร:** `.env.uat`: `VITE_BASE=/capstone26/uat/`, `VITE_API_BASE_URL=/capstone26/uat/api` · อัปเดต comment ที่อ้าง `/uat/` (`vite.config.js`, `nginx.conf`, `router/index.js`, `mocks/browser.js`, `shared/api/sse.js`)
- **ไฟล์ที่เกี่ยวข้อง:** `.env.uat`, `vite.config.js`, `nginx.conf`, `src/app/router/index.js`, `src/mocks/browser.js`, `src/shared/api/sse.js`, `README.md`
- **เหตุผล:** คณะ forward `https://bscit.sit.kmutt.ac.th/capstone26/` มาที่ VM (ไม่ตัด prefix) → uat เปิดผ่าน HTTPS ได้ กล้องสดในหน้าลงทะเบียนใบหน้าจะใช้ได้
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** path ต้องตรงกับ Ingress ใน `devops/k8s/overlays/uat` · ดูหัวข้อ "forward จาก bscit" ใน `devops/README.md`

### 2026-11-24 — path ของเว็บใน NGINX มาจาก `VITE_BASE` ที่เดียว
- **ทำอะไร:** `Dockerfile` อ่าน `VITE_BASE` จาก `.env.<MODE>` แล้วแทนค่าลงใน `nginx.conf` ตอน build (ตรวจว่าขึ้นต้นและลงท้ายด้วย `/`) · ไม่มี build arg `APP_BASE` แล้ว (ส่งมาก็ไม่มีผล) · `${BACKEND_URL}` ยังแทนค่าตอน container start เหมือนเดิม
- **ไฟล์ที่เกี่ยวข้อง:** `Dockerfile`
- **เหตุผล:** เตรียมย้าย uat ไป `/capstone26/uat/` (forward จาก `https://bscit.sit.kmutt.ac.th/capstone26/`) · เดิมต้องแก้ path ทั้งใน `.env.uat` และ `APP_BASE` ของสคริปต์ build ถ้าไม่ตรงกันหน้าเว็บจะขาว
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ยังไม่ได้เปลี่ยน path (ยังเป็น `/uat/`) — ทำใน commit ถัดไปหลัง CD บน VM อัปเดต `deploy.sh` ตัวเองแล้ว

### 2026-11-23 — เอาข้อยืนยันอายุ 20 ปีออกจากหน้ายินยอม
- **ทำอะไร:** ลบ checkbox "ข้าพเจ้ามีอายุ 20 ปีบริบูรณ์แล้ว หรือบรรลุนิติภาวะแล้ว" และข้อความเตือน "ผู้ที่อายุยังไม่ถึง 20 ปี ต้องให้ผู้ปกครองยินยอมก่อน" ออกจากขั้นยินยอม — กดยินยอมได้เมื่อติ๊กข้อยินยอมข้อเดียว · ลบ `ageConfirmed` ออกจาก `useFaceRegistration` / `FaceRegistrationPage` และ `registerFace` ไม่ส่ง `ageConfirmed` แล้ว (Backend ใช้ค่าเริ่มต้น `false` → `consent_records.age_confirmed = false`) · ตาราง API หน้า `/student/face` ไม่มี `+ ageConfirmed`
- **ไฟล์ที่เกี่ยวข้อง:** `src/features/face-registration/consent-text.js`, `src/features/face-registration/components/ConsentStep.vue`, `src/features/face-registration/composables/useFaceRegistration.js`, `src/features/face-registration/pages/FaceRegistrationPage.vue`, `src/features/face-registration/api.js`, `tests/unit/consent-step.test.js` (ใหม่: มี checkbox ข้อเดียว ไม่มีคำว่า 20 ปี / ผู้ปกครอง, ปุ่มยินยอมกดได้หลังติ๊ก)
- **เหตุผล:** ผู้ใช้ขอเอาเนื้อหาเรื่องอายุ 20 ปีออกจากหน้าลงทะเบียน
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ไม่ได้แก้ Backend / DB (คอลัมน์ `age_confirmed`, `is_adult` ยังอยู่ · Backend ไม่ได้บังคับค่านี้) · ข้อความยินยอมหลักไม่เปลี่ยน จึงยังเป็นฉบับ `v1` (ไม่ต้องให้คนที่ยินยอมแล้วยินยอมใหม่) — ถ้า DPO ต้องการให้ถือเป็นฉบับใหม่ ต้องเพิ่ม `consent_versions` v2 และเปลี่ยน `CONSENT_VERSION`

### 2026-11-23 — หน้าลงทะเบียนใบหน้าใช้ได้บน HTTP (uat) + บัญชีทดสอบสมาชิกทีม
- **ทำอะไร:** เบราว์เซอร์อนุญาต `getUserMedia` เฉพาะ HTTPS / localhost → บน uat (HTTP) ขึ้น "เปิดกล้องไม่ได้" · แก้: `PhotoPicker` มีปุ่ม "ถ่ายรูปด้วยกล้องมือถือ" (`<input capture="user">` เปิดแอปกล้องได้แม้เป็น HTTP · แสดงเฉพาะจอสัมผัส) รูปที่ถ่ายได้ชื่อมุมตามลำดับ (หน้าตรง → หันซ้าย → หันขวา) · `AngleCaptureStep` ถ้าไม่มีกล้องสด (`canUseLiveCamera()`) แสดงคำอธิบายแทนปุ่มเปิดกล้อง (มือถือ / คอมคนละข้อความ) · `prepare-image.js` ใหม่: ย่อรูปทุกรูปเหลือด้านยาว ≤ 1600 px แปลงเป็น JPEG 0.9 หมุนตาม EXIF และตัด EXIF (รวม GPS) ทิ้ง — รูปมือถือมักเกิน 5MB และ iPhone เป็น HEIC · `useFaceRegistration.addFiles` เป็น async + `preparing` (ปุ่มปิดระหว่างเตรียมรูป) · หน้า Login เพิ่มกลุ่ม "สมาชิกทีม" 3 บัญชี
- **ไฟล์ที่เกี่ยวข้อง:** `src/features/face-registration/prepare-image.js`, `src/features/face-registration/components/PhotoPicker.vue`, `src/features/face-registration/components/AngleCaptureStep.vue`, `src/features/face-registration/composables/useFaceRegistration.js`, `src/features/face-registration/pages/FaceRegistrationPage.vue`, `src/features/auth/test-accounts.js`
- **เหตุผล:** ให้ลงทะเบียนใบหน้าบน uat ได้ก่อนมี HTTPS
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ทดสอบใน browser (จำลอง HTTP): มือถือเห็นปุ่มถ่ายรูป + รูป 4000×3000 ถูกย่อเป็น 1600×1200 JPEG · คอมเห็นข้อความให้เลือกรูป · localhost (secure) ยังมีปุ่ม "เปิดกล้อง" ตามเดิม · ทดสอบกับมือถือจริงบน uat หลัง deploy · ทางแก้ถาวรคือ HTTPS · ทดสอบบนคอมผ่าน HTTP ได้ด้วย Chrome flag `chrome://flags/#unsafely-treat-insecure-origin-as-secure` ใส่ `http://cp26ms3.sit.kmutt.ac.th`

### 2026-11-22 — บัญชีทดสอบในหน้า Login (local / uat)
- **ทำอะไร:** ย้ายรายการบัญชีทดสอบไป `src/features/auth/test-accounts.js` · หน้า Login แสดงบัญชีจากข้อมูล mock ใน DB (admin, อาจารย์ 2, นักศึกษา 12 สถานะ) เมื่อ `VITE_SHOW_TEST_ACCOUNTS=true` (`.env.development`, `.env.uat`) — กดแล้วกรอกอีเมล + รหัสผ่านให้ · โหมด mock ยังแสดงบัญชี MSW เหมือนเดิม · ช่องอีเมลแสดงตัวอย่าง `รหัสนักศึกษา@st.sit.kmutt.ac.th` แทนบัญชี mock
- **ไฟล์ที่เกี่ยวข้อง:** `src/features/auth/test-accounts.js` (ใหม่), `src/features/auth/pages/LoginPage.vue`, `.env.development`, `.env.uat`, `.env.production`
- **เหตุผล:** เมื่อต่อ Backend จริง หน้า Login ไม่มีบัญชีให้เลือก โดยเฉพาะ student
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** Production (`VITE_SHOW_TEST_ACCOUNTS=false`) ตัดรายชื่อและรหัสผ่านทดสอบออกจาก bundle ทั้งหมด (ตรวจด้วย grep แล้ว) — ข้อมูลอยู่หลังเงื่อนไข `import.meta.env` ห้ามย้ายออกมาเป็นค่าคงที่

### 2026-11-22 — แก้ตารางจัดการรายวิชา: กลุ่มที่ 2 ขึ้นเป็น JSON และไม่มีปุ่ม
- **ทำอะไร:** `CourseTable` แสดงรหัสวิชาและปุ่มแก้ไข / ลบ ทุกแถว (แถวที่ 2+ ของวิชาเดียวกันแสดงรหัสแบบจาง) · `DataTable` ค่าเริ่มต้นของช่องไม่แสดง object ดิบ (แสดง `-` แทน)
- **ไฟล์ที่เกี่ยวข้อง:** `src/features/course-management/components/CourseTable.vue`, `src/shared/components/DataTable.vue`
- **เหตุผล:** เดิมแสดงเฉพาะแถวแรกของวิชา (`v-if`) แถวอื่น slot ว่าง Vue จึงใช้ค่า fallback ของ `DataTable` = `row.course` (object ทั้งก้อน) และ `-` แทนปุ่ม
- **หมายเหตุ / สิ่งที่ต้องทำต่อ:** ตรวจใน browser ด้วยบัญชี admin (INT101 มี 2 กลุ่ม) แล้ว
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
