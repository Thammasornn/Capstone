/**
 * ตรรกะคำนวณฝั่ง mock — ทำตามกติกาของ Backend ในคู่มือ API v1.0 ข้อ 6
 * (ไม่มีแถว = absent · นับเฉพาะคาบที่เริ่มแล้ว · อัตรา = (on_time+late)/ทั้งหมด×100 ปัด 2 ตำแหน่ง)
 */
import { HttpResponse } from 'msw'
import { db } from './data'

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const TOKEN_PREFIX = 'mock.'

/* ---------- response helpers ---------- */

const nowStamp = () => new Date().toISOString().slice(0, 19)

export function apiError(status, message, request) {
  const errors = {
    400: 'Bad Request',
    401: 'Unauthorized',
    403: 'Forbidden',
    404: 'Not Found',
    409: 'Conflict',
    422: 'Unprocessable Entity',
    502: 'Bad Gateway',
  }
  const path = request ? new URL(request.url).pathname : null
  return HttpResponse.json(
    { timestamp: nowStamp(), status, error: errors[status] ?? 'Error', message, path },
    { status },
  )
}

export const isUuid = (value) => typeof value === 'string' && UUID_RE.test(value)

/* ---------- auth ---------- */

export function issueToken(user) {
  return `${TOKEN_PREFIX}${btoa(user.userId)}.${Date.now()}`
}

/** อ่าน user จาก Bearer token ของ mock — null ถ้าไม่มี/ไม่ใช่ token ของ mock */
export function resolveUser(request) {
  const header = request.headers.get('Authorization') ?? ''
  const queryToken = new URL(request.url).searchParams.get('access_token') // SSE (Q3)
  const token = header.startsWith('Bearer ') ? header.slice(7) : queryToken
  if (!token?.startsWith(TOKEN_PREFIX)) return null
  try {
    const userId = atob(token.slice(TOKEN_PREFIX.length).split('.')[0])
    return db.users.find((u) => u.userId === userId) ?? null
  } catch {
    return null
  }
}

/** user ตามรูป /me (คู่มือข้อ 5.1) */
export function toMe(user) {
  const student = user.studentId ? db.students.find((s) => s.studentId === user.studentId) : null
  const lecturer = user.lecturerId
    ? db.lecturers.find((l) => l.lecturerId === user.lecturerId)
    : null
  return {
    userId: user.userId,
    email: user.email,
    firstName: user.firstName,
    lastName: user.lastName,
    role: user.role,
    student: student
      ? {
          studentId: student.studentId,
          studentCode: student.studentCode,
          faculty: student.faculty,
          department: student.department,
          yearLevel: student.yearLevel,
          faceRegistration: { ...student.faceRegistration },
        }
      : null,
    lecturer: lecturer ? { lecturerId: lecturer.lecturerId, staffCode: lecturer.staffCode } : null,
  }
}

/* ---------- scope ---------- */

/** section ที่ user เห็นได้ (คู่มือข้อ 3) */
export function visibleSections(user) {
  if (!user || user.role === 'admin') return db.sections
  if (user.role === 'lecturer') return db.sections.filter((s) => s.lecturerId === user.lecturerId)
  const mine = new Set(
    db.enrollments.filter((e) => e.studentId === user.studentId).map((e) => e.sectionId),
  )
  return db.sections.filter((s) => mine.has(s.sectionId))
}

export const lecturerName = (lecturerId) => {
  const l = db.lecturers.find((x) => x.lecturerId === lecturerId)
  return l ? `${l.firstName} ${l.lastName}` : null
}
export const roomOf = (roomId) => db.rooms.find((r) => r.roomId === roomId) ?? null
export const studentName = (s) => `${s.firstName} ${s.lastName}`
export const membersOf = (sectionId) =>
  db.enrollments
    .filter((e) => e.sectionId === sectionId)
    .map((e) => db.students.find((s) => s.studentId === e.studentId))

export function isStarted(session, now = new Date()) {
  return new Date(`${session.sessionDate}T${session.startTime}`).getTime() <= now.getTime()
}

const sortSessions = (a, b) =>
  (a.sessionDate + a.startTime).localeCompare(b.sessionDate + b.startTime)

/* ---------- attendance math ---------- */

export function statusOf(sessionId, studentId) {
  return db.attendance.find((a) => a.sessionId === sessionId && a.studentId === studentId) ?? null
}

export function tally(rows) {
  const counts = { onTime: 0, late: 0, absent: 0 }
  rows.forEach((status) => {
    if (status === 'on_time') counts.onTime++
    else if (status === 'late') counts.late++
    else counts.absent++
  })
  const total = counts.onTime + counts.late + counts.absent
  const attendanceRate = total
    ? Math.round(((counts.onTime + counts.late) / total) * 10000) / 100
    : 0
  return { ...counts, attendanceRate }
}

/** สถานะของทุกคนในคาบ (ไม่มีแถว = absent) */
export function sessionStatuses(session) {
  return membersOf(session.sectionId).map(
    (st) => statusOf(session.sessionId, st.studentId)?.status ?? 'absent',
  )
}

/** ตรวจ courseId/sectionId และสิทธิ์สำหรับ API 5.4–5.6 → { sections } หรือ Response */
export function courseScope(request, user, courseId) {
  const url = new URL(request.url)
  const sectionId = url.searchParams.get('sectionId')
  if (!isUuid(courseId) || (sectionId && !isUuid(sectionId)))
    return apiError(400, 'รหัสไม่ถูกต้อง', request)
  if (user?.role === 'student') return apiError(403, 'ไม่มีสิทธิ์เข้าถึงข้อมูลนี้', request)
  const course = db.courses.find((c) => c.courseId === courseId)
  if (!course) return apiError(404, 'ไม่พบรายวิชา', request)
  const courseSections = db.sections.filter((s) => s.courseId === courseId)
  if (sectionId && !courseSections.some((s) => s.sectionId === sectionId)) {
    return apiError(404, 'ไม่พบกลุ่มเรียนในรายวิชานี้', request)
  }
  const allowed = new Set(visibleSections(user).map((s) => s.sectionId))
  let sections = courseSections.filter((s) => allowed.has(s.sectionId))
  if (!sections.length) return apiError(403, 'ไม่มีสิทธิ์เข้าถึงข้อมูลนี้', request)
  if (sectionId) {
    sections = sections.filter((s) => s.sectionId === sectionId)
    if (!sections.length) return apiError(403, 'ไม่มีสิทธิ์เข้าถึงข้อมูลนี้', request)
  }
  return { course, sections, sectionId }
}

export function sessionsOf(sections) {
  const ids = new Set(sections.map((s) => s.sectionId))
  return db.sessions.filter((s) => ids.has(s.sectionId)).sort(sortSessions)
}

/** ตรวจสิทธิ์คาบ (5.7–5.8, B8) → { session, section } หรือ Response */
export function sessionScope(request, user, sessionId) {
  if (!isUuid(sessionId)) return apiError(400, 'รหัสไม่ถูกต้อง', request)
  if (user?.role === 'student') return apiError(403, 'ไม่มีสิทธิ์เข้าถึงข้อมูลนี้', request)
  const session = db.sessions.find((s) => s.sessionId === sessionId)
  if (!session) return apiError(404, 'ไม่พบคาบเรียน', request)
  const section = db.sections.find((s) => s.sectionId === session.sectionId)
  if (!visibleSections(user).includes(section))
    return apiError(403, 'ไม่มีสิทธิ์เข้าถึงข้อมูลนี้', request)
  return { session, section }
}

/** แถวรายวิชาสำหรับ GET /courses (+ field ตารางเรียนที่ขอเพิ่มใน B5) */
export function toCourseList(sections, { withSchedule }) {
  const byCourse = new Map()
  sections.forEach((section) => {
    const course = db.courses.find((c) => c.courseId === section.courseId)
    if (!byCourse.has(course.courseId)) byCourse.set(course.courseId, { ...course, sections: [] })
    const row = {
      sectionId: section.sectionId,
      sectionCode: section.sectionCode,
      semester: section.semester,
      academicYear: section.academicYear,
      lecturerName: lecturerName(section.lecturerId),
    }
    if (withSchedule) Object.assign(row, toSchedule(section))
    byCourse.get(course.courseId).sections.push(row)
  })
  return [...byCourse.values()].sort((a, b) => a.courseCode.localeCompare(b.courseCode))
}

/** field ตารางเรียนของ section ตามที่เสนอใน B5 */
export function toSchedule(section) {
  return {
    lecturerId: section.lecturerId,
    roomId: section.roomId,
    roomCode: roomOf(section.roomId)?.roomCode ?? null,
    classDays: [...section.classDays],
    startTime: section.startTime,
    endTime: section.endTime,
    checkInOpens: section.checkInOpens,
    checkInCloses: section.checkInCloses,
    cameraAttendanceEnabled: section.cameraAttendanceEnabled,
  }
}
