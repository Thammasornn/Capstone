import { describe, expect, it } from 'vitest'
import { formatSessionStatus, getStatusMeta } from '@/entities/attendance/status'

describe('attendance status', () => {
  it('map สถานะ → ข้อความไทย + tone', () => {
    expect(getStatusMeta('on_time')).toMatchObject({ label: 'ตรงเวลา', tone: 'success' })
    expect(getStatusMeta('late')).toMatchObject({ label: 'สาย', tone: 'warning' })
    expect(getStatusMeta('absent')).toMatchObject({ label: 'ขาด', tone: 'danger' })
  })

  it('ค่าที่ไม่รู้จักยังแสดงได้เป็นโทนกลาง', () => {
    expect(getStatusMeta('excused')).toMatchObject({ label: 'excused', tone: 'neutral' })
    expect(getStatusMeta(null)).toMatchObject({ label: '-', tone: 'neutral' })
  })

  it('sessionStatus ที่ไม่รู้จักแสดงค่าเดิม ไม่พัง', () => {
    expect(formatSessionStatus('completed')).toBe('จบแล้ว')
    expect(formatSessionStatus('cancelled')).toBe('cancelled')
  })
})
