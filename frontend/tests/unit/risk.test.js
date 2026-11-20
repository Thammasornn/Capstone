import { describe, expect, it } from 'vitest'
import { canSeeRisk, getRiskLevel, isAtRisk, withRiskLevel } from '@/entities/attendance/risk'

describe('getRiskLevel — ขาด 0–1 Low, 2 Medium, 3+ High', () => {
  it.each([
    [0, 'low'],
    [1, 'low'],
    [2, 'medium'],
    [3, 'high'],
    [10, 'high'],
  ])('ขาด %i ครั้ง → %s', (absent, level) => {
    expect(getRiskLevel(absent)).toBe(level)
  })

  it('ไม่มีข้อมูล → null', () => {
    expect(getRiskLevel(null)).toBeNull()
    expect(getRiskLevel(undefined)).toBeNull()
    expect(getRiskLevel(-1)).toBeNull()
    expect(getRiskLevel(Number.NaN)).toBeNull()
  })

  it('แท็บ At-Risk รวมเฉพาะ medium/high', () => {
    expect(isAtRisk('high')).toBe(true)
    expect(isAtRisk('medium')).toBe(true)
    expect(isAtRisk('low')).toBe(false)
    expect(isAtRisk(null)).toBe(false)
  })

  it('student ห้ามเห็นระดับความเสี่ยง', () => {
    expect(canSeeRisk('student')).toBe(false)
    expect(canSeeRisk('lecturer')).toBe(true)
    expect(canSeeRisk('admin')).toBe(true)
  })

  it('withRiskLevel เรียงเสี่ยงมากก่อน แล้วตามรหัสนักศึกษา', () => {
    const rows = withRiskLevel([
      { studentCode: 'MS67003', absent: 0 },
      { studentCode: 'MS67002', absent: 3 },
      { studentCode: 'MS67001', absent: 2 },
      { studentCode: 'MS67004', absent: 4 },
    ])
    expect(rows.map((r) => [r.studentCode, r.riskLevel])).toEqual([
      ['MS67004', 'high'],
      ['MS67002', 'high'],
      ['MS67001', 'medium'],
      ['MS67003', 'low'],
    ])
  })
})
