import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { configureHttp } from '@/shared/api/http'
import { useAuthStore } from '@/features/auth/store'
import { getCourses } from '@/entities/course/api'
import { getCourseStudents, getCourseSummary } from '@/features/attendance-analytics/api'

/** ตรวจว่า mock (5.1–5.11) ทำตามกติกาในคู่มือ API · B1–B8 ไม่มี mock แล้ว (ใช้ Backend จริง) */
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
})
