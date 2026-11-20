import { describe, expect, it } from 'vitest'
import { normalizeError } from '@/shared/api/errors'

const axiosError = (status, data) => ({ response: { status, data }, config: {} })

describe('normalizeError — ตามคู่มือข้อ 2.3', () => {
  it('ใช้ message ภาษาไทยจาก Backend', () => {
    const e = normalizeError(axiosError(400, { message: 'ต้องส่งรูป 3–5 รูป', path: '/api/x' }))
    expect(e).toMatchObject({
      kind: 'validation',
      status: 400,
      message: 'ต้องส่งรูป 3–5 รูป',
      action: 'show_message',
    })
  })

  it('401 ไม่มี body ยังตัดสินจาก status ได้', () => {
    expect(normalizeError(axiosError(401, ''))).toMatchObject({
      kind: 'unauthorized',
      action: 'logout',
    })
  })

  it.each([
    [403, 'forbidden', 'forbidden_page'],
    [404, 'not_found', 'not_found_page'],
    [422, 'unprocessable', 'pick_new_photos'],
  ])('%i → %s', (status, kind, action) => {
    expect(normalizeError(axiosError(status, {}))).toMatchObject({ kind, action })
  })

  it('502 ใช้ข้อความกลางเสมอ', () => {
    const e = normalizeError(axiosError(502, { message: 'connection refused to ai:8080' }))
    expect(e.message).toBe('ระบบไม่พร้อมใช้งาน ลองใหม่ภายหลัง')
  })

  it('timeout / network', () => {
    expect(normalizeError({ code: 'ECONNABORTED' }).kind).toBe('timeout')
    expect(normalizeError({ request: {} }).kind).toBe('network')
  })
})
