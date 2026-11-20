/**
 * ฐานข้อมูลจำลองในหน่วยความจำ — เลียนแบบชุดทดสอบของ Backend (คู่มือ API v1.0 ข้อ 8)
 *   นักศึกษา 10 · อาจารย์ 5 · admin 1 · รายวิชา MOCK101–MOCK105 วิชาละ 2 กลุ่ม กลุ่มละ 5 คน
 *   แต่ละกลุ่มมี 3 คาบที่ผ่านแล้ว + 1 คาบในอนาคต (วันที่อิงวันที่เปิดแอป)
 * เพิ่มเติมสำหรับ B1–B8 (ยังไม่มีใน API v1.0): ห้องเรียน/กล้อง 6 ห้องตาม Figma, ตารางเรียนต่อ section,
 *   และคาบ "กำลังเรียนอยู่" วันนี้ 4 คาบ ให้หน้า Room Selection / Room Live มีข้อมูล
 * แก้ไขได้ระหว่างใช้งาน (POST/PUT/DELETE) และรีเซ็ตเมื่อรีเฟรชหน้า
 */

export const MOCK_PASSWORD = 'Passw0rd!'

/** UUID แบบอ่านง่ายตามรูปแบบในคู่มือ เช่น a2000000-0000-4000-8000-000000000001 */
export function mockUuid(prefix, n) {
  return `${prefix}000000-0000-4000-8000-${String(n).padStart(12, '0')}`
}

const pad = (n) => String(n).padStart(2, '0')
const toDate = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const toTime = (d) => `${pad(d.getHours())}:${pad(d.getMinutes())}:00`
const addMinutes = (time, minutes) => {
  const [h, m] = time.split(':').map(Number)
  const total = h * 60 + m + minutes
  return `${pad(Math.floor(total / 60) % 24)}:${pad(total % 60)}:00`
}
const shiftDays = (base, days) => {
  const d = new Date(base)
  d.setDate(d.getDate() + days)
  return d
}

const STUDENT_NAMES = [
  ['สมชาย', 'ใจดี'],
  ['สุดา', 'แสงทอง'],
  ['ธนากร', 'มั่นคง'],
  ['พิมพ์ชนก', 'ศรีสุข'],
  ['กิตติพัฒน์', 'วงศ์ใหญ่'],
  ['ณัฐธิดา', 'บุญมา'],
  ['ปกรณ์', 'เจริญผล'],
  ['วริศรา', 'ทองคำ'],
  ['อนุชา', 'พงษ์ไพร'],
  ['ชลธิชา', 'รุ่งเรือง'],
]
const LECTURER_NAMES = [
  ['ประเสริฐ', 'ทองดี'],
  ['ทัชพล', 'ศรีวงศ์'],
  ['มนตรี', 'สุภัทร'],
  ['กมลวรรณ', 'จันทร์งาม'],
  ['วีระ', 'อินทร์แก้ว'],
]
const COURSES = [
  ['MOCK101', 'Introduction to Programming'],
  ['MOCK102', 'Data Structures and Algorithms'],
  ['MOCK103', 'Database Systems'],
  ['MOCK104', 'Computer Networks'],
  ['MOCK105', 'Software Engineering'],
]
/** ห้องตาม Figma "Room Selection" — 2105 และ 1108 กล้องไม่มีสัญญาณ */
const ROOMS = [
  ['4301', 'live'],
  ['4302', 'live'],
  ['3201', 'live'],
  ['2105', 'no_signal'],
  ['5410', 'live'],
  ['1108', 'no_signal'],
]
/** section index → [lecturer index, room index, classDays, startTime, endTime] */
const SECTION_PLAN = [
  [0, 0, ['MON', 'WED'], '09:00:00', '10:30:00'], // MOCK101-001 lecturer01
  [1, 1, ['TUE', 'THU'], '09:00:00', '10:30:00'], // MOCK101-002
  [1, 2, ['MON'], '13:00:00', '16:00:00'], // MOCK102-001
  [2, 3, ['FRI'], '09:00:00', '12:00:00'], // MOCK102-002
  [2, 5, ['TUE'], '13:00:00', '16:00:00'], // MOCK103-001
  [0, 0, ['THU'], '13:00:00', '16:00:00'], // MOCK103-002 lecturer01 (ตามคู่มือ)
  [3, 3, ['WED'], '09:00:00', '12:00:00'], // MOCK104-001
  [3, 1, ['FRI'], '13:00:00', '16:00:00'], // MOCK104-002
  [4, 4, ['MON', 'THU'], '10:30:00', '12:00:00'], // MOCK105-001
  [4, 4, ['TUE', 'FRI'], '10:30:00', '12:00:00'], // MOCK105-002
]
/** section ที่มีคาบ "กำลังเรียน" วันนี้ (ให้ห้อง 4301, 4302, 3201, 5410 เป็น Live + มีคนเช็คชื่อ) */
const LIVE_TODAY_SECTIONS = [0, 1, 2, 8]

/** สถานะแบบ deterministic — บางคนขาด 2–3 ครั้งเพื่อให้เห็นกลุ่มเสี่ยง Medium/High */
function pastStatus(studentIdx, sessionIdx, sectionIdx) {
  if (studentIdx === 9) return 'absent' // student10: ขาดทุกคาบ → High
  if (studentIdx === 7 && sessionIdx < 2) return 'absent' // student08: ขาด 2 → Medium
  const code = (studentIdx * 7 + sessionIdx * 3 + sectionIdx) % 10
  if (code <= 6) return 'on_time'
  if (code <= 8) return 'late'
  return 'absent'
}

export function createMockDb(now = new Date()) {
  const users = []
  const students = STUDENT_NAMES.map(([firstName, lastName], i) => {
    const n = i + 1
    const registered = n <= 7 // student01–07 ลงทะเบียนแล้ว, 08–10 ยังไม่ลงทะเบียน
    const student = {
      studentId: mockUuid('a2', n),
      studentCode: `MS670${pad(n)}`,
      firstName,
      lastName,
      faculty: 'เทคโนโลยีสารสนเทศ',
      department: 'เทคโนโลยีสารสนเทศ',
      yearLevel: 2 + (i % 3),
      faceRegistration: {
        registered,
        registeredAt: registered ? `${toDate(shiftDays(now, -10 - i))}T14:20:11` : null,
        activeFaces: registered ? 3 : 0,
      },
      consent: registered
        ? { version: 'v1', consentedAt: `${toDate(shiftDays(now, -10 - i))}T14:20:05` }
        : null,
    }
    users.push({
      userId: mockUuid('a1', n),
      email: `student${pad(n)}@mock.test`,
      firstName,
      lastName,
      role: 'student',
      studentId: student.studentId,
      lecturerId: null,
    })
    return student
  })

  const lecturers = LECTURER_NAMES.map(([firstName, lastName], i) => {
    const n = i + 1
    const lecturer = { lecturerId: mockUuid('a3', n), firstName, lastName, staffCode: `LEC0${n}` }
    users.push({
      userId: mockUuid('a1', 100 + n),
      email: `lecturer${pad(n)}@mock.test`,
      firstName,
      lastName,
      role: 'lecturer',
      studentId: null,
      lecturerId: lecturer.lecturerId,
    })
    return lecturer
  })

  users.push({
    userId: mockUuid('a1', 999),
    email: 'admin@mock.test',
    firstName: 'ผู้ดูแล',
    lastName: 'ระบบ',
    role: 'admin',
    studentId: null,
    lecturerId: null,
  })

  const rooms = ROOMS.map(([roomCode, cameraStatus], i) => ({
    roomId: mockUuid('a7', i + 1),
    roomCode,
    cameraCode: `CAM-${roomCode}-A`,
    cameraStatus,
  }))

  const courses = COURSES.map(([courseCode, courseName], i) => ({
    courseId: mockUuid('a4', i + 1),
    courseCode,
    courseName,
  }))

  const sections = []
  const enrollments = [] // { sectionId, studentId }
  const sessions = []
  const attendance = [] // { sessionId, studentId, status, checkInTime, checkOutTime, source }

  courses.forEach((course, c) => {
    for (let s = 0; s < 2; s++) {
      const idx = c * 2 + s
      const [lecturerIdx, roomIdx, classDays, startTime, endTime] = SECTION_PLAN[idx]
      const section = {
        sectionId: mockUuid('a5', idx + 1),
        courseId: course.courseId,
        sectionCode: `00${s + 1}`,
        semester: '1',
        academicYear: 2569,
        lecturerId: lecturers[lecturerIdx].lecturerId,
        roomId: rooms[roomIdx].roomId,
        classDays,
        startTime,
        endTime,
        checkInOpens: startTime,
        checkInCloses: addMinutes(startTime, 30),
        cameraAttendanceEnabled: rooms[roomIdx].cameraStatus === 'live',
      }
      sections.push(section)

      // นักศึกษาอยู่กลุ่มเดียวต่อวิชา: แบ่งคู่/คี่สลับตามวิชา → กลุ่มละ 5 คน
      const members = students.filter((_, i) => (i + c) % 2 === s)
      members.forEach((st) =>
        enrollments.push({ sectionId: section.sectionId, studentId: st.studentId }),
      )

      const plan = [-21, -14, -7, 7].map((days) => ({
        date: toDate(shiftDays(now, days)),
        startTime,
        endTime,
      }))
      if (LIVE_TODAY_SECTIONS.includes(idx)) {
        const start = new Date(now.getTime() - 20 * 60_000)
        start.setSeconds(0, 0)
        const liveStart = toTime(start)
        plan.splice(3, 0, {
          date: toDate(now),
          startTime: liveStart,
          endTime: addMinutes(liveStart, 90),
          live: true,
        })
      }

      plan.forEach((p, sessionIdx) => {
        const started = new Date(`${p.date}T${p.startTime}`).getTime() <= now.getTime()
        const session = {
          sessionId: mockUuid('a6', idx * 10 + sessionIdx + 1),
          sectionId: section.sectionId,
          sessionDate: p.date,
          startTime: p.startTime,
          endTime: p.endTime,
          sessionStatus: p.live ? 'in_progress' : started ? 'completed' : 'scheduled',
        }
        sessions.push(session)
        if (!started) return

        members.forEach((st) => {
          const studentIdx = students.indexOf(st)
          let status = pastStatus(studentIdx, sessionIdx, idx)
          if (p.live && studentIdx >= 7) status = 'absent' // ยังไม่ลงทะเบียนใบหน้า → กล้องจำไม่ได้
          if (status === 'absent') return // คนขาดไม่มีแถวในฐานข้อมูล (คู่มือข้อ 6)
          const offset = status === 'on_time' ? 2 + (studentIdx % 5) : 18 + (studentIdx % 6)
          attendance.push({
            sessionId: session.sessionId,
            studentId: st.studentId,
            status,
            checkInTime: `${p.date}T${addMinutes(p.startTime, offset)}`,
            checkOutTime: null,
            source: 'camera',
          })
        })
      })
    }
  })

  return { users, students, lecturers, rooms, courses, sections, enrollments, sessions, attendance }
}

/** ฐานข้อมูลที่ handler ทุกตัวใช้ร่วมกัน */
export let db = createMockDb()

/** ให้ test เริ่มจากข้อมูลชุดใหม่ */
export function resetDb(now) {
  db = createMockDb(now)
  return db
}
