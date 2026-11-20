import { describe, expect, it } from 'vitest'
import { dash, formatRate, fullName } from '@/shared/utils/format'

describe('format', () => {
  it('null → "-"', () => {
    expect(dash(null)).toBe('-')
    expect(dash(0)).toBe('0')
  })

  it('totalSessions = 0 → "ยังไม่มีข้อมูล" ไม่ใช่ 0%', () => {
    expect(formatRate(0, { totalSessions: 0 })).toBe('ยังไม่มีข้อมูล')
    expect(formatRate(80, { totalSessions: 6 })).toBe('80.00%')
    expect(formatRate(null)).toBe('-')
  })

  it('ชื่อเต็ม', () => {
    expect(fullName({ firstName: 'สมชาย', lastName: 'ใจดี' })).toBe('สมชาย ใจดี')
    expect(fullName(null)).toBe('-')
  })
})
