/**
 * Mock ของ API ที่ขอเพิ่มจากทีม Back-End (B1–B8) — shape ตามเอกสาร
 * "แจ้งทีม Back-End: API ที่ต้องเพิ่มสำหรับ Release 1" (https://claude.ai/artifact/3HtjyBM6iwRA816wud5ks4)
 * ทำงานทั้งโหมด all และ missing · เมื่อ Backend ทำข้อไหนเสร็จ ให้ลบ handler ของข้อนั้นออก
 * B9 (isAdult ใน /me) รอ DPO ตัดสิน จึงยังไม่ mock
 */
import { HttpResponse, delay, http, sse } from 'msw'
import { db, mockUuid } from '../data'
import {
  apiError,
  hasAnyToken,
  isStarted,
  isUuid,
  lecturerName,
  membersOf,
  resolveUser,
  roomOf,
  sessionScope,
  statusOf,
  studentName,
  toSchedule,
  visibleSections,
} from '../queries'
import { durationMinutes, timeToMinutes } from '@/shared/utils/datetime'

const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']

/**
 * โหมด all: ต้องเป็น token ของ mock · โหมด missing: token มาจาก Backend จริง (mock ถอดไม่ได้)
 * จึงยอมรับ Bearer ใดก็ได้และถือว่าเป็น admin
 */
const authed = (mode, resolver) => async (info) => {
  let user = resolveUser(info.request)
  if (!user && mode === 'missing' && hasAnyToken(info.request)) user = { role: 'admin' }
  if (!user) return new HttpResponse(null, { status: 401 })
  return resolver({ ...info, user })
}

const staffOnly = (user, request) =>
  user.role === 'student' ? apiError(403, 'ไม่มีสิทธิ์เข้าถึงข้อมูลนี้', request) : null

/* ---------- B1 / B4: ห้อง + คาบปัจจุบัน ---------- */

function currentSessionOf(room) {
  const sectionIds = db.sections.filter((s) => s.roomId === room.roomId).map((s) => s.sectionId)
  const live = db.sessions.find(
    (s) => sectionIds.includes(s.sectionId) && s.sessionStatus === 'in_progress',
  )
  if (!live) return null
  const section = db.sections.find((s) => s.sectionId === live.sectionId)
  const course = db.courses.find((c) => c.courseId === section.courseId)
  const members = membersOf(section.sectionId)
  return {
    sessionId: live.sessionId,
    courseCode: course.courseCode,
    courseName: course.courseName,
    sectionCode: section.sectionCode,
    startTime: live.startTime,
    endTime: live.endTime,
    checkedIn: members.filter((m) => statusOf(live.sessionId, m.studentId)).length,
    totalStudents: members.length,
  }
}

const toRoom = (room) => ({ ...room, currentSession: currentSessionOf(room) })

function visibleRooms(user) {
  if (user.role === 'admin') return db.rooms
  const roomIds = new Set(visibleSections(user).map((s) => s.roomId))
  return db.rooms.filter((r) => roomIds.has(r.roomId))
}

/* ---------- B5: validate ฟอร์มรายวิชา ---------- */

function validateCourse(body, { courseId } = {}) {
  if (!body?.courseCode?.trim() || !body?.courseName?.trim()) return 'กรุณากรอกรหัสและชื่อรายวิชา'
  if (db.courses.some((c) => c.courseCode === body.courseCode.trim() && c.courseId !== courseId)) {
    return `รหัสวิชา ${body.courseCode} มีอยู่แล้ว`
  }
  if (!Array.isArray(body.sections) || !body.sections.length) return 'ต้องมีอย่างน้อย 1 กลุ่มเรียน'
  for (const s of body.sections) {
    const label = `กลุ่ม ${s.sectionCode || '-'}`
    if (!s.sectionCode) return 'กรุณากรอกรหัสกลุ่มเรียน'
    if (!roomOf(s.roomId)) return `${label}: ไม่พบห้องเรียน`
    if (
      !Array.isArray(s.classDays) ||
      !s.classDays.length ||
      s.classDays.some((d) => !DAYS.includes(d))
    ) {
      return `${label}: กรุณาเลือกวันเรียน`
    }
    if (durationMinutes(s.startTime, s.endTime) === null)
      return `${label}: เวลาเลิกเรียนต้องหลังเวลาเริ่ม`
    const open = timeToMinutes(s.checkInOpens)
    const close = timeToMinutes(s.checkInCloses)
    if (open === null || close === null || close <= open) return `${label}: ช่วงเช็คชื่อไม่ถูกต้อง`
    if (open < timeToMinutes(s.startTime) || close > timeToMinutes(s.endTime)) {
      return `${label}: ช่วงเช็คชื่อต้องอยู่ในเวลาเรียน`
    }
    // ห้องเดียวกัน วันเดียวกัน เวลาห้ามชน (เทียบกับ section ของวิชาอื่น)
    const clash = db.sections.find(
      (o) =>
        o.roomId === s.roomId &&
        o.sectionId !== s.sectionId &&
        o.courseId !== courseId &&
        o.classDays.some((d) => s.classDays.includes(d)) &&
        timeToMinutes(o.startTime) < timeToMinutes(s.endTime) &&
        timeToMinutes(s.startTime) < timeToMinutes(o.endTime),
    )
    if (clash) return `${label}: ห้อง ${roomOf(s.roomId).roomCode} มีวิชาอื่นใช้ในช่วงเวลานี้แล้ว`
  }
  return null
}

function toCourseDetail(course) {
  return {
    ...course,
    sections: db.sections
      .filter((s) => s.courseId === course.courseId)
      .map((s) => ({
        sectionId: s.sectionId,
        sectionCode: s.sectionCode,
        semester: s.semester,
        academicYear: s.academicYear,
        lecturerName: lecturerName(s.lecturerId),
        ...toSchedule(s),
      })),
  }
}

let idCounter = 900
const nextId = (prefix) => mockUuid(prefix, ++idCounter)

/**
 * @param {(path: string) => string} api
 * @param {'all'|'missing'} mode
 */
export function pendingHandlers(api, mode) {
  const handlers = [
    // B1
    http.get(
      api('/rooms'),
      authed(
        mode,
        ({ request, user }) =>
          staffOnly(user, request) ?? HttpResponse.json(visibleRooms(user).map(toRoom)),
      ),
    ),

    // B4
    http.get(
      api('/rooms/:roomId'),
      authed(mode, ({ request, params, user }) => {
        const denied = staffOnly(user, request)
        if (denied) return denied
        const room = visibleRooms(user).find((r) => r.roomId === params.roomId)
        return room ? HttpResponse.json(toRoom(room)) : apiError(404, 'ไม่พบห้องเรียน', request)
      }),
    ),

    // B2 — mock ไม่มีภาพจริง: url ชี้ไปที่ที่ MediaMTX จะเปิด ผู้เล่นจะแสดง "ไม่มีสัญญาณ"
    http.get(
      api('/rooms/:roomId/stream'),
      authed(mode, ({ request, params, user }) => {
        const denied = staffOnly(user, request)
        if (denied) return denied
        const room = visibleRooms(user).find((r) => r.roomId === params.roomId)
        if (!room) return apiError(404, 'ไม่พบห้องเรียน', request)
        const end = new Date(Date.now() + 90 * 60_000)
        const local = new Date(end.getTime() - end.getTimezoneOffset() * 60_000)
          .toISOString()
          .slice(0, 19)
        return HttpResponse.json({
          protocol: 'whep',
          url: `/mock/stream/${room.roomCode}/whep`,
          expiresAt: local,
        })
      }),
    ),

    // B5 — รายละเอียดวิชา
    http.get(
      api('/courses/:courseId'),
      authed(mode, ({ request, params, user }) => {
        const denied = staffOnly(user, request)
        if (denied) return denied
        if (!isUuid(params.courseId)) return apiError(400, 'รหัสไม่ถูกต้อง', request)
        const course = db.courses.find((c) => c.courseId === params.courseId)
        if (!course) return apiError(404, 'ไม่พบรายวิชา', request)
        return HttpResponse.json(toCourseDetail(course))
      }),
    ),

    http.post(
      api('/courses'),
      authed(mode, async ({ request, user }) => {
        const denied = staffOnly(user, request)
        if (denied) return denied
        const body = await request.json().catch(() => null)
        const problem = validateCourse(body)
        if (problem) return apiError(400, problem, request)
        await delay(300)
        const course = {
          courseId: nextId('a4'),
          courseCode: body.courseCode.trim(),
          courseName: body.courseName.trim(),
        }
        db.courses.push(course)
        body.sections.forEach((s) =>
          db.sections.push({
            sectionId: nextId('a5'),
            courseId: course.courseId,
            sectionCode: s.sectionCode,
            semester: '1',
            academicYear: 2569,
            lecturerId: s.lecturerId ?? user.lecturerId ?? null,
            roomId: s.roomId,
            classDays: s.classDays,
            startTime: s.startTime,
            endTime: s.endTime,
            checkInOpens: s.checkInOpens,
            checkInCloses: s.checkInCloses,
            cameraAttendanceEnabled: s.cameraAttendanceEnabled ?? true,
          }),
        )
        return HttpResponse.json(toCourseDetail(course), { status: 201 })
      }),
    ),

    http.put(
      api('/courses/:courseId'),
      authed(mode, async ({ request, params, user }) => {
        const denied = staffOnly(user, request)
        if (denied) return denied
        const course = db.courses.find((c) => c.courseId === params.courseId)
        if (!course) return apiError(404, 'ไม่พบรายวิชา', request)
        const body = await request.json().catch(() => null)
        const problem = validateCourse(body, { courseId: course.courseId })
        if (problem) return apiError(400, problem, request)
        await delay(300)
        course.courseCode = body.courseCode.trim()
        course.courseName = body.courseName.trim()
        body.sections.forEach((s) => {
          const existing = db.sections.find(
            (x) => x.sectionId === s.sectionId && x.courseId === course.courseId,
          )
          const fields = {
            sectionCode: s.sectionCode,
            roomId: s.roomId,
            classDays: s.classDays,
            startTime: s.startTime,
            endTime: s.endTime,
            checkInOpens: s.checkInOpens,
            checkInCloses: s.checkInCloses,
            cameraAttendanceEnabled: s.cameraAttendanceEnabled ?? true,
          }
          if (existing)
            Object.assign(existing, fields, s.lecturerId ? { lecturerId: s.lecturerId } : {})
          else
            db.sections.push({
              sectionId: nextId('a5'),
              courseId: course.courseId,
              semester: '1',
              academicYear: 2569,
              lecturerId: s.lecturerId ?? null,
              ...fields,
            })
        })
        return HttpResponse.json(toCourseDetail(course))
      }),
    ),

    // B5 — ลบ: มีประวัติเช็คชื่อแล้ว → 409 (รอ Q5 ว่าจะใช้ soft delete หรือไม่)
    http.delete(
      api('/courses/:courseId'),
      authed(mode, ({ request, params, user }) => {
        const denied = staffOnly(user, request)
        if (denied) return denied
        const course = db.courses.find((c) => c.courseId === params.courseId)
        if (!course) return apiError(404, 'ไม่พบรายวิชา', request)
        const sectionIds = new Set(
          db.sections.filter((s) => s.courseId === course.courseId).map((s) => s.sectionId),
        )
        const hasHistory = db.sessions.some((s) => sectionIds.has(s.sectionId) && isStarted(s))
        if (hasHistory)
          return apiError(409, 'ลบไม่ได้ เพราะรายวิชานี้มีประวัติการเช็คชื่อแล้ว', request)
        db.courses = db.courses.filter((c) => c !== course)
        db.sections = db.sections.filter((s) => !sectionIds.has(s.sectionId))
        return new HttpResponse(null, { status: 204 })
      }),
    ),

    // B6
    http.patch(
      api('/sections/:sectionId/camera-attendance'),
      authed(mode, async ({ request, params, user }) => {
        const denied = staffOnly(user, request)
        if (denied) return denied
        const section = db.sections.find((s) => s.sectionId === params.sectionId)
        if (!section) return apiError(404, 'ไม่พบกลุ่มเรียน', request)
        if (user.role === 'lecturer' && section.lecturerId !== user.lecturerId) {
          return apiError(403, 'ไม่มีสิทธิ์แก้ไขกลุ่มเรียนนี้', request)
        }
        const body = await request.json().catch(() => ({}))
        if (typeof body.enabled !== 'boolean')
          return apiError(400, 'ค่า enabled ต้องเป็น true หรือ false', request)
        await delay(250)
        section.cameraAttendanceEnabled = body.enabled
        return HttpResponse.json({
          sectionId: section.sectionId,
          cameraAttendanceEnabled: section.cameraAttendanceEnabled,
        })
      }),
    ),

    // B7 — ถอนความยินยอม + ลบ embedding
    http.delete(
      api('/students/:studentId/face'),
      authed(mode, async ({ request, params, user }) => {
        if (
          user.role === 'lecturer' ||
          (user.role === 'student' && user.studentId !== params.studentId)
        ) {
          return apiError(403, 'ไม่มีสิทธิ์ลบข้อมูลใบหน้าของผู้อื่น', request)
        }
        const student = db.students.find((s) => s.studentId === params.studentId)
        if (!student) return apiError(404, 'ไม่พบนักศึกษา', request)
        await delay(400)
        const d = new Date()
        const withdrawnAt = new Date(d.getTime() - d.getTimezoneOffset() * 60_000)
          .toISOString()
          .slice(0, 19)
        student.faceRegistration = { registered: false, registeredAt: null, activeFaces: 0 }
        student.consent = null
        return HttpResponse.json({ studentId: student.studentId, registered: false, withdrawnAt })
      }),
    ),

    // B8 — อาจารย์เช็คชื่อด้วยมือ
    http.put(
      api('/class-sessions/:sessionId/attendance/:studentId'),
      authed(mode, async ({ request, params, user }) => {
        const scope = sessionScope(request, user, params.sessionId)
        if (scope instanceof Response) return scope
        const student = membersOf(scope.section.sectionId).find(
          (m) => m.studentId === params.studentId,
        )
        if (!student) return apiError(404, 'นักศึกษาไม่ได้อยู่ในกลุ่มเรียนนี้', request)
        const body = await request.json().catch(() => ({}))
        if (!['on_time', 'late', 'absent'].includes(body.status))
          return apiError(400, 'ค่า status ไม่ถูกต้อง', request)
        db.attendance = db.attendance.filter(
          (a) => !(a.sessionId === params.sessionId && a.studentId === params.studentId),
        )
        const row = {
          sessionId: params.sessionId,
          studentId: params.studentId,
          status: body.status,
          checkInTime: body.status === 'absent' ? null : (body.checkInTime ?? null),
          checkOutTime: null,
          source: 'manual',
          reason: body.reason ?? null,
        }
        if (body.status !== 'absent') db.attendance.push(row)
        return HttpResponse.json({
          ...row,
          studentCode: student.studentCode,
          studentName: studentName(student),
        })
      }),
    ),
  ]

  // B3 — SSE ทำงานเฉพาะในเบราว์เซอร์ (Node/Vitest ไม่มี EventSource)
  if (typeof globalThis.EventSource !== 'undefined') {
    handlers.push(
      // faces ทุก 3 วินาที, check_in เป็นระยะ, camera ตอนเชื่อมต่อ
      sse(api('/rooms/:roomId/events'), ({ client, request, params }) => {
        const user =
          resolveUser(request) ??
          (mode === 'missing' && hasAnyToken(request) ? { role: 'admin' } : null)
        const room = db.rooms.find((r) => r.roomId === params.roomId)
        if (!user || !room) {
          client.error()
          return
        }
        const send = (event, data) => client.send({ event, data: JSON.stringify(data) })
        const stamp = () => {
          const d = new Date()
          return new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 19)
        }

        send('camera', { cameraStatus: room.cameraStatus })
        const current = currentSessionOf(room)
        let tick = 0
        const timer = setInterval(() => {
          try {
            tick++
            if (room.cameraStatus !== 'live') return
            const base = current?.checkedIn ?? 0
            send('faces', { detected: Math.max(0, base + ((tick % 5) - 2)), at: stamp() })
            // ทุก 4 รอบ: มีคนที่ยังไม่เช็คชื่อเดินเข้ากล้อง
            if (current && tick % 4 === 0) {
              const session = db.sessions.find((s) => s.sessionId === current.sessionId)
              const waiting = membersOf(session.sectionId).find(
                (m) => !statusOf(session.sessionId, m.studentId) && m.faceRegistration.registered,
              )
              if (waiting) {
                const row = {
                  sessionId: session.sessionId,
                  studentId: waiting.studentId,
                  status: 'late',
                  checkInTime: stamp(),
                  checkOutTime: null,
                  source: 'camera',
                }
                db.attendance.push(row)
                send('check_in', {
                  sessionId: row.sessionId,
                  studentId: row.studentId,
                  studentCode: waiting.studentCode,
                  checkInTime: row.checkInTime,
                  status: row.status,
                })
              }
            }
          } catch {
            clearInterval(timer)
          }
        }, 3000)
        request.signal?.addEventListener('abort', () => clearInterval(timer))
      }),
    )
  }
  return handlers
}
