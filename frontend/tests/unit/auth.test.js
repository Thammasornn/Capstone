import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { configureHttp, http } from '@/shared/api/http'
import { STORAGE_KEY, useAuthStore } from '@/features/auth/store'

describe('auth (กับ mock API)', () => {
  let auth
  const onUnauthorized = vi.fn()

  beforeEach(() => {
    setActivePinia(createPinia())
    auth = useAuthStore()
    onUnauthorized.mockReset()
    configureHttp({ getToken: () => auth.token, onUnauthorized })
  })

  it('ล็อกอินด้วยบัญชีทดสอบ → เก็บ token + user และอายุ 8 ชม.', async () => {
    const user = await auth.login({ email: 'student01@mock.test', password: 'Passw0rd!' })
    expect(user.role).toBe('student')
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.studentId).toBe(user.student.studentId)
    expect(auth.expiresAt - Date.now()).toBeGreaterThan(7.9 * 3600 * 1000)
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)).token).toBe(auth.token)
  })

  it('รหัสผ่านผิด → unauthorized และไม่ logout อัตโนมัติ', async () => {
    await expect(
      auth.login({ email: 'student01@mock.test', password: 'wrong' }),
    ).rejects.toMatchObject({
      kind: 'unauthorized',
    })
    expect(onUnauthorized).not.toHaveBeenCalled()
    expect(auth.isAuthenticated).toBe(false)
  })

  it('แนบ Bearer token กับทุก request หลังล็อกอิน', async () => {
    await auth.login({ email: 'lecturer01@mock.test', password: 'Passw0rd!' })
    const me = await auth.refreshMe()
    expect(me.email).toBe('lecturer01@mock.test')
  })

  it('401 ระหว่างใช้งาน → เรียก onUnauthorized', async () => {
    auth.token = 'mock.invalid'
    await expect(http.get('/me')).rejects.toMatchObject({ kind: 'unauthorized' })
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('logout ล้าง token และ localStorage', async () => {
    await auth.login({ email: 'admin@mock.test', password: 'Passw0rd!' })
    auth.logout('expired')
    expect(auth.isAuthenticated).toBe(false)
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
    expect(auth.consumeLogoutReason()).toBe('expired')
  })
})
