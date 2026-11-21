/**
 * Mock ของ API ที่มีแล้วใน "คู่มือ Attendance API v1.0" (5.1–5.11)
 * ใช้เฉพาะ VITE_MOCK_MODE=all (dev ไม่รัน Backend) และ Vitest
 */
import { HttpResponse, delay, http } from 'msw'
import { MOCK_PASSWORD, db } from '../data'
import {
  apiError,
  courseScope,
  isStarted,
  isUuid,
  issueToken,
  membersOf,
  resolveUser,
  sessionScope,
  sessionStatuses,
  sessionsOf,
  statusOf,
  studentName,
  tally,
  toCourseList,
  toMe,
  visibleSections,
} from '../queries'

const MAX_FILE_BYTES = 5 * 1024 * 1024
const MAX_TOTAL_BYTES = 25 * 1024 * 1024
const ALLOWED_TYPES = ['image/jpeg', 'image/png']

/** ครอบ handler ที่ต้องล็อกอิน: ไม่มี/ผิด token → 401 ไม่มี body (คู่มือข้อ 2.3) */
const authed = (resolver) => async (info) => {
  const user = resolveUser(info.request)
  if (!user) return new HttpResponse(null, { status: 401 })
  return resolver({ ...info, user })
}

/** @param {(path: string) => string} api */
export function v1Handlers(api) {
  return [
    // 5.1
    http.post(api('/auth/login'), async ({ request }) => {
      await delay(300)
      const body = await request.json().catch(() => ({}))
      if (!body.email || !body.password) return apiError(400, 'กรุณากรอกอีเมลและรหัสผ่าน', request)
      const user = db.users.find((u) => u.email === String(body.email).toLowerCase())
      if (!user || body.password !== MOCK_PASSWORD) {
        return apiError(401, 'อีเมลหรือรหัสผ่านไม่ถูกต้อง', request)
      }
      return HttpResponse.json({
        accessToken: issueToken(user),
        tokenType: 'Bearer',
        expiresIn: 28800,
        user: toMe(user),
      })
    }),

    // 5.2
    http.get(
      api('/me'),
      authed(({ user }) => HttpResponse.json(toMe(user))),
    ),

    // 5.3 — ในโหมด mock ใส่ field ตารางเรียนที่ขอเพิ่มใน B5 มาด้วย
    http.get(
      api('/courses'),
      authed(({ user }) =>
        HttpResponse.json(toCourseList(visibleSections(user), { withSchedule: true })),
      ),
    ),

    // 5.4
    http.get(
      api('/courses/:courseId/attendance/summary'),
      authed(({ request, params, user }) => {
        const scope = courseScope(request, user, params.courseId)
        if (scope instanceof Response) return scope
        const started = sessionsOf(scope.sections).filter((s) => isStarted(s))
        const statuses = started.flatMap(sessionStatuses)
        const totalStudents = new Set(
          scope.sections.flatMap((s) => membersOf(s.sectionId).map((m) => m.studentId)),
        ).size
        return HttpResponse.json({
          courseId: scope.course.courseId,
          courseCode: scope.course.courseCode,
          courseName: scope.course.courseName,
          sectionId: scope.sectionId,
          totalSessions: started.length,
          totalStudents,
          ...tally(statuses),
        })
      }),
    ),

    // 5.5
    http.get(
      api('/courses/:courseId/attendance/sessions'),
      authed(({ request, params, user }) => {
        const scope = courseScope(request, user, params.courseId)
        if (scope instanceof Response) return scope
        const items = sessionsOf(scope.sections).map((session) => {
          const section = scope.sections.find((s) => s.sectionId === session.sectionId)
          const started = isStarted(session)
          const totals = started
            ? tally(sessionStatuses(session))
            : { onTime: 0, late: 0, absent: 0, attendanceRate: 0 }
          return {
            sessionId: session.sessionId,
            sectionId: session.sectionId,
            sectionCode: section.sectionCode,
            sessionDate: session.sessionDate,
            startTime: session.startTime,
            endTime: session.endTime,
            sessionStatus: session.sessionStatus,
            started,
            totalStudents: membersOf(session.sectionId).length,
            ...totals,
          }
        })
        return HttpResponse.json({ courseId: scope.course.courseId, items })
      }),
    ),

    // 5.6
    http.get(
      api('/courses/:courseId/attendance/students'),
      authed(({ request, params, user }) => {
        const scope = courseScope(request, user, params.courseId)
        if (scope instanceof Response) return scope
        const faceFilter = new URL(request.url).searchParams.get('faceRegistered')
        const items = scope.sections
          .flatMap((section) => {
            const started = db.sessions.filter(
              (s) => s.sectionId === section.sectionId && isStarted(s),
            )
            return membersOf(section.sectionId).map((st) => ({
              studentId: st.studentId,
              studentCode: st.studentCode,
              studentName: studentName(st),
              ...tally(started.map((s) => statusOf(s.sessionId, st.studentId)?.status ?? 'absent')),
              faceRegistered: st.faceRegistration.registered,
            }))
          })
          .filter((row) => faceFilter === null || String(row.faceRegistered) === faceFilter)
          .sort((a, b) => a.studentCode.localeCompare(b.studentCode))
        return HttpResponse.json({ courseId: scope.course.courseId, items })
      }),
    ),

    // 5.7 — ไม่กรองเวลา (คาบอนาคตทุกคนเป็น absent ตามพฤติกรรมจริงของ Backend)
    http.get(
      api('/class-sessions/:sessionId/attendance/summary'),
      authed(({ request, params, user }) => {
        const scope = sessionScope(request, user, params.sessionId)
        if (scope instanceof Response) return scope
        const { session, section } = scope
        return HttpResponse.json({
          sessionId: session.sessionId,
          sectionId: section.sectionId,
          sectionCode: section.sectionCode,
          courseId: section.courseId,
          sessionDate: session.sessionDate,
          startTime: session.startTime,
          endTime: session.endTime,
          sessionStatus: session.sessionStatus,
          totalStudents: membersOf(section.sectionId).length,
          ...tally(sessionStatuses(session)),
        })
      }),
    ),

    // 5.8 — mock ใส่ `source` (camera | manual) ที่ขอเพิ่มใน B8 มาด้วย
    http.get(
      api('/class-sessions/:sessionId/attendance'),
      authed(({ request, params, user }) => {
        const scope = sessionScope(request, user, params.sessionId)
        if (scope instanceof Response) return scope
        const statusFilter = new URL(request.url).searchParams.get('status')
        if (statusFilter && !['on_time', 'late', 'absent'].includes(statusFilter)) {
          return apiError(400, 'ค่า status ไม่ถูกต้อง', request)
        }
        const items = membersOf(scope.section.sectionId)
          .map((st) => {
            const row = statusOf(scope.session.sessionId, st.studentId)
            return {
              studentId: st.studentId,
              studentCode: st.studentCode,
              studentName: studentName(st),
              checkInTime: row?.checkInTime ?? null,
              checkOutTime: row?.checkOutTime ?? null,
              status: row?.status ?? 'absent',
              source: row?.source ?? null,
            }
          })
          .filter((r) => !statusFilter || r.status === statusFilter)
          .sort((a, b) => a.studentCode.localeCompare(b.studentCode))
        return HttpResponse.json({
          sessionId: scope.session.sessionId,
          sessionDate: scope.session.sessionDate,
          items,
        })
      }),
    ),

    // 5.9
    http.get(
      api('/courses/:courseId/students/:studentId/attendance'),
      authed(({ request, params, user }) => {
        const { courseId, studentId } = params
        if (!isUuid(courseId) || !isUuid(studentId)) return apiError(400, 'รหัสไม่ถูกต้อง', request)
        if (user.role === 'student' && user.studentId !== studentId) {
          return apiError(403, 'ไม่มีสิทธิ์เข้าถึงข้อมูลนี้', request)
        }
        const course = db.courses.find((c) => c.courseId === courseId)
        const student = db.students.find((s) => s.studentId === studentId)
        const section = db.sections.find(
          (s) =>
            s.courseId === courseId &&
            db.enrollments.some((e) => e.sectionId === s.sectionId && e.studentId === studentId),
        )
        if (!course || !student || !section)
          return apiError(404, 'ไม่พบรายวิชา หรือนักศึกษาไม่ได้ลงทะเบียนวิชานี้', request)
        if (user.role === 'lecturer' && section.lecturerId !== user.lecturerId) {
          return apiError(403, 'ไม่มีสิทธิ์เข้าถึงข้อมูลนี้', request)
        }
        const items = sessionsOf([section])
          .filter((s) => isStarted(s))
          .map((s) => {
            const row = statusOf(s.sessionId, studentId)
            return {
              sessionId: s.sessionId,
              sessionDate: s.sessionDate,
              checkInTime: row?.checkInTime ?? null,
              checkOutTime: row?.checkOutTime ?? null,
              status: row?.status ?? 'absent',
            }
          })
        return HttpResponse.json({
          courseId: course.courseId,
          courseCode: course.courseCode,
          courseName: course.courseName,
          studentId: student.studentId,
          studentCode: student.studentCode,
          studentName: studentName(student),
          ...tally(items.map((i) => i.status)),
          items,
        })
      }),
    ),

    // 5.10
    http.post(
      api('/students/:studentId/face/register'),
      authed(async ({ request, params, user }) => {
        const { studentId } = params
        if (!isUuid(studentId)) return apiError(400, 'รหัสไม่ถูกต้อง', request)
        if (user.role === 'lecturer' || (user.role === 'student' && user.studentId !== studentId)) {
          return apiError(403, 'ไม่มีสิทธิ์ลงทะเบียนใบหน้าให้ผู้อื่น', request)
        }
        const student = db.students.find((s) => s.studentId === studentId)
        if (!student) return apiError(404, 'ไม่พบนักศึกษา', request)

        const form = await request.formData()
        const files = form.getAll('files').filter((f) => typeof f === 'object')
        if (form.get('consent') !== 'true')
          return apiError(400, 'กรุณายินยอมให้เก็บข้อมูลใบหน้าก่อนลงทะเบียน', request)
        if (files.length < 3 || files.length > 5)
          return apiError(400, 'ต้องส่งรูป 3–5 รูป', request)
        if (files.some((f) => !ALLOWED_TYPES.includes(f.type)))
          return apiError(400, 'รองรับเฉพาะไฟล์ JPEG หรือ PNG', request)
        if (files.some((f) => f.size > MAX_FILE_BYTES))
          return apiError(400, 'รูปต้องมีขนาดไม่เกิน 5MB ต่อรูป', request)
        if (files.reduce((sum, f) => sum + f.size, 0) > MAX_TOTAL_BYTES)
          return apiError(400, 'รูปรวมกันต้องไม่เกิน 25MB', request)

        await delay(1500) // จำลองเวลา AI Service
        // ตั้งชื่อไฟล์มีคำว่า "noface" เพื่อทดสอบ 422 / "aidown" เพื่อทดสอบ 502
        if (files.some((f) => /aidown/i.test(f.name)))
          return apiError(502, 'AI Service ไม่พร้อมใช้งาน', request)
        const bad = files.find((f) => /noface/i.test(f.name))
        if (bad)
          return apiError(
            422,
            `ไม่พบใบหน้าในรูป ${bad.name} กรุณาถ่ายใหม่ให้เห็นใบหน้าชัดเจน`,
            request,
          )

        const stamp = new Date()
        const local = new Date(stamp.getTime() - stamp.getTimezoneOffset() * 60_000)
          .toISOString()
          .slice(0, 19)
        student.faceRegistration = {
          registered: true,
          registeredAt: local,
          activeFaces: files.length,
        }
        if (!student.consent)
          student.consent = { version: form.get('consentTextVersion') || 'v1', consentedAt: local }
        return HttpResponse.json({ studentId, savedCount: files.length, model: 'sface' })
      }),
    ),

    // 5.11 (+ consentVersion / consentedAt ที่ขอเพิ่มใน B7)
    http.get(
      api('/students/:studentId/face/status'),
      authed(({ request, params, user }) => {
        const { studentId } = params
        if (!isUuid(studentId)) return apiError(400, 'รหัสไม่ถูกต้อง', request)
        if (user.role === 'student' && user.studentId !== studentId)
          return apiError(403, 'ไม่มีสิทธิ์เข้าถึงข้อมูลนี้', request)
        const student = db.students.find((s) => s.studentId === studentId)
        if (!student) return apiError(404, 'ไม่พบนักศึกษา', request)
        return HttpResponse.json({
          studentId,
          ...student.faceRegistration,
          consentVersion: student.consent?.version ?? null,
          consentedAt: student.consent?.consentedAt ?? null,
        })
      }),
    ),
  ]
}
