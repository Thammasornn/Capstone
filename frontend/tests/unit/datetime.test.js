import { describe, expect, it } from 'vitest'
import {
  durationMinutes,
  formatTime,
  formatTimeOfDateTime,
  isSessionInFuture,
  parseLocalDateTime,
  timeToMinutes,
} from '@/shared/utils/datetime'

describe('datetime — เวลาไทยไม่มี timezone', () => {
  it('parse เป็นเวลาท้องถิ่น ไม่ใช่ UTC', () => {
    const d = parseLocalDateTime('2026-11-16T09:05:00')
    expect(d.getFullYear()).toBe(2026)
    expect(d.getMonth()).toBe(10)
    expect(d.getDate()).toBe(16)
    expect(d.getHours()).toBe(9)
    expect(d.getMinutes()).toBe(5)
  })

  it('รูปแบบผิดหรือมี Z → null', () => {
    expect(parseLocalDateTime('2026-11-16T09:05:00Z')).toBeNull()
    expect(parseLocalDateTime(null)).toBeNull()
  })

  it('แสดงเวลาตามที่ Backend ส่งมา', () => {
    expect(formatTimeOfDateTime('2026-10-26T09:05:00')).toBe('09:05')
    expect(formatTimeOfDateTime(null)).toBe('-')
    expect(formatTime('13:30:00')).toBe('13:30')
  })

  it('คำนวณความยาวคาบ', () => {
    expect(timeToMinutes('09:05:00')).toBe(545)
    expect(durationMinutes('09:00:00', '10:30:00')).toBe(90)
    expect(durationMinutes('10:30:00', '09:00:00')).toBeNull()
  })

  it('คาบในอนาคต', () => {
    const now = new Date(2026, 10, 21, 9, 0)
    expect(isSessionInFuture('2026-11-21', '09:30:00', now)).toBe(true)
    expect(isSessionInFuture('2026-11-21', '08:30:00', now)).toBe(false)
  })
})
