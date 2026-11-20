import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { routes } from '@/app/router/routes'
import { resolveAccess } from '@/app/router/guards'

const router = createRouter({ history: createMemoryHistory(), routes })
const at = (path) => router.resolve(path)
const session = (role) => ({ isAuthenticated: Boolean(role), isExpired: false, role })

describe('route guard', () => {
  it('ยังไม่ล็อกอิน → /login พร้อม redirect', () => {
    expect(resolveAccess(at('/classrooms'), session(null))).toEqual({
      name: 'login',
      query: { redirect: '/classrooms' },
    })
  })

  it('token หมดอายุ → บอกหน้า Login ว่าเซสชันหมดอายุ', () => {
    const result = resolveAccess(at('/student'), {
      isAuthenticated: false,
      isExpired: true,
      role: null,
    })
    expect(result.query.reason).toBe('expired')
  })

  it('ล็อกอินแล้วเปิด /login → ไปหน้าแรกของ role', () => {
    expect(resolveAccess(at('/login'), session('student'))).toEqual({ name: 'student-home' })
    expect(resolveAccess(at('/login'), session('admin'))).toEqual({ name: 'room-selection' })
  })

  it('student เข้าหน้าอาจารย์ไม่ได้ (รวมแท็บ At-Risk)', () => {
    expect(resolveAccess(at('/courses/at-risk'), session('student'))).toEqual({ name: 'forbidden' })
    expect(resolveAccess(at('/classrooms'), session('student'))).toEqual({ name: 'forbidden' })
  })

  it('lecturer/admin เข้าหน้านักศึกษาไม่ได้', () => {
    expect(resolveAccess(at('/student/face'), session('lecturer'))).toEqual({ name: 'forbidden' })
  })

  it('role ที่ถูกต้องเข้าได้', () => {
    expect(resolveAccess(at('/student/attendance'), session('student'))).toBe(true)
    expect(resolveAccess(at('/courses/manage'), session('lecturer'))).toBe(true)
    expect(resolveAccess(at('/reports/sessions/abc'), session('admin'))).toBe(true)
  })

  it('หน้า 403/404 เปิดได้ทุกคน', () => {
    expect(resolveAccess(at('/403'), session(null))).toBe(true)
    expect(resolveAccess(at('/no/such/page'), session(null))).toBe(true)
  })
})
