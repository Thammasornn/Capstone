import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { configureHttp } from '@/shared/api/http'
import { useAuthStore } from '@/features/auth/store'
import { getCourses } from '@/entities/course/api'
import { getCourseStudents, getCourseSummary } from '@/features/attendance-analytics/api'
import { getRooms } from '@/features/room-monitor/api'
import { setCameraAttendance } from '@/features/course-management/api'
import { withdrawConsent } from '@/features/face-registration/api'

/** ตรวจว่า mock ทำตามกติกาในคู่มือ API — หน้าจอที่ทำต่อจะได้เชื่อตัวเลขได้ */
describe('mock API', () => {
  let auth
  const loginAs = (email) => auth.login({ email, password: 'Passw0rd!' })

  beforeEach(() => {
    setActivePinia(createPinia())
    auth = useAuthStore()
    configureHttp({ getToken: () => auth.token, onUnauthorized: () => {} })
  })

  it('lecturer01 สอน MOCK101-001 และ MOCK103-002 ตามคู่มือ', async () => {
    await loginAs('lecturer01@mock.test')
    const courses = await getCourses()
    expect(
      courses.map((c) => `${c.courseCode}-${c.sections.map((s) => s.sectionCode).join(',')}`),
    ).toEqual(['MOCK101-001', 'MOCK103-002'])
  })

  it('อัตราเข้าเรียน = (on_time + late) ÷ ทั้งหมด × 100', async () => {
    await loginAs('admin@mock.test')
    const [course] = await getCourses()
    const s = await getCourseSummary(course.courseId)
    const expected =
      Math.round(((s.onTime + s.late) / (s.onTime + s.late + s.absent)) * 10000) / 100
    expect(s.attendanceRate).toBe(expected)
    expect(s.onTime + s.late + s.absent).toBeGreaterThan(0)
  })

  it('มีนักศึกษากลุ่มเสี่ยงให้ทดสอบหน้า At-Risk', async () => {
    await loginAs('admin@mock.test')
    const [course] = await getCourses()
    const { items } = await getCourseStudents(course.courseId)
    expect(items.some((r) => r.absent >= 3)).toBe(true)
  })

  it('student เรียกสรุประดับวิชาไม่ได้ (403)', async () => {
    await loginAs('student01@mock.test')
    const [course] = await getCourses()
    await expect(getCourseSummary(course.courseId)).rejects.toMatchObject({ kind: 'forbidden' })
  })

  it('B1 ห้องของ lecturer01 มีคาบกำลังเรียน', async () => {
    await loginAs('lecturer01@mock.test')
    const rooms = await getRooms()
    expect(rooms.map((r) => r.roomCode)).toEqual(['4301'])
    expect(rooms[0].currentSession).toMatchObject({ courseCode: 'MOCK101', sectionCode: '001' })
  })

  it('B6 เปิด/ปิดกล้องของ section', async () => {
    await loginAs('admin@mock.test')
    const [course] = await getCourses()
    const sectionId = course.sections[0].sectionId
    await expect(setCameraAttendance(sectionId, false)).resolves.toEqual({
      sectionId,
      cameraAttendanceEnabled: false,
    })
  })

  it('B7 ถอนความยินยอม → สถานะใน /me เป็นยังไม่ลงทะเบียน', async () => {
    const user = await loginAs('student01@mock.test')
    expect(user.student.faceRegistration.registered).toBe(true)
    await withdrawConsent(user.student.studentId)
    const me = await auth.refreshMe()
    expect(me.student.faceRegistration).toEqual({
      registered: false,
      registeredAt: null,
      activeFaces: 0,
    })
  })
})
