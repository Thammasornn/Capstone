import { describe, expect, it } from 'vitest'
import {
  CUSTOM_PRESET,
  DEFAULT_LATE_PRESET,
  LATE_PRESETS,
  computeCheckInWindow,
  detectLatePreset,
} from '@/entities/attendance/check-in-presets'
import { timeToMinutes } from '@/shared/utils/datetime'

describe('กติกาเวลาสาย (F1) — คำนวณช่วงเช็คชื่อจากเวลาเริ่มเรียน', () => {
  it('ค่าเริ่มต้นคือสาย 15 นาที และมี preset 10 / 15 / 30', () => {
    expect(DEFAULT_LATE_PRESET).toBe(15)
    expect(LATE_PRESETS.map((p) => p.lateAfterMinutes)).toEqual([10, 15, 30])
  })

  it('สาย 15: เปิด = เริ่ม · ตรงเวลาถึง +15 · สายถึง / ปิด +45', () => {
    expect(computeCheckInWindow('09:00', '12:00', 15)).toEqual({
      checkInOpens: '09:00',
      onTimeUntil: '09:15',
      lateUntil: '09:45',
      checkInCloses: '09:45',
      onTimeMinutes: 15,
    })
  })

  it('สาย 10 และ สาย 30 ใช้ค่าจาก LATE_PRESETS', () => {
    expect(computeCheckInWindow('13:00', '16:00', 10)).toMatchObject({
      onTimeUntil: '13:10',
      lateUntil: '13:30',
    })
    expect(computeCheckInWindow('13:00', '16:00', 30)).toMatchObject({
      onTimeUntil: '13:30',
      lateUntil: '14:00',
      checkInCloses: '14:00',
    })
  })

  it('seconds: true คืน "HH:mm:ss" (ฟอร์มรายวิชา)', () => {
    expect(computeCheckInWindow('09:00:00', '12:00:00', 15, { seconds: true })).toMatchObject({
      checkInOpens: '09:00:00',
      checkInCloses: '09:45:00',
    })
  })

  it('คาบสั้นกว่าช่วงสาย: ตัดไม่ให้เกินเวลาเลิกเรียน และยังเรียงลำดับถูก', () => {
    const w = computeCheckInWindow('09:00', '09:40', 30)
    expect(w).toMatchObject({ onTimeUntil: '09:30', lateUntil: '09:40', checkInCloses: '09:40' })
    const tiny = computeCheckInWindow('09:00', '09:05', 15)
    expect(tiny).toMatchObject({ onTimeUntil: '09:05', lateUntil: '09:05', onTimeMinutes: 5 })
  })

  it('ผ่านกติกา backend ทุก preset: เริ่ม ≤ เปิด ≤ ตรงเวลา ≤ สาย ≤ ปิด ≤ เลิก และ สาย > เปิด', () => {
    for (const p of LATE_PRESETS) {
      for (const [s, e] of [
        ['08:00', '11:00'],
        ['13:30', '14:00'],
        ['18:00', '18:20'],
      ]) {
        const w = computeCheckInWindow(s, e, p.lateAfterMinutes)
        const [S, E, o, ot, l, c] = [
          s,
          e,
          w.checkInOpens,
          w.onTimeUntil,
          w.lateUntil,
          w.checkInCloses,
        ].map(timeToMinutes)
        expect(S <= o && o <= ot && ot <= l && l <= c && c <= E).toBe(true)
        expect(l > o).toBe(true)
        expect(w.onTimeMinutes).toBe(ot - o)
      }
    }
  })

  it('เวลาเรียนผิด หรือ preset ไม่มี → null', () => {
    expect(computeCheckInWindow('12:00', '09:00', 15)).toBeNull()
    expect(computeCheckInWindow('', '09:00', 15)).toBeNull()
    expect(computeCheckInWindow('09:00', '12:00', 20)).toBeNull()
    expect(computeCheckInWindow('09:00', '12:00', CUSTOM_PRESET)).toBeNull()
  })

  it('ตรวจว่าค่าเดิมตรง preset ไหน (คาบใช้ onTimeUntil · กลุ่มเรียนใช้ onTimeMinutes)', () => {
    expect(
      detectLatePreset({
        startTime: '13:00',
        endTime: '16:00',
        checkInOpens: '13:00',
        onTimeUntil: '13:10',
        lateUntil: '13:30',
        checkInCloses: '13:30',
      }),
    ).toBe(10)
    expect(
      detectLatePreset({
        startTime: '09:00:00',
        endTime: '12:00:00',
        checkInOpens: '09:00:00',
        checkInCloses: '10:00:00',
        onTimeMinutes: 30,
        lateUntil: null,
      }),
    ).toBe(30)
  })

  it('ค่าที่ไม่ตรง preset ใด → custom', () => {
    expect(
      detectLatePreset({
        startTime: '09:00:00',
        endTime: '12:00:00',
        checkInOpens: '09:00:00',
        checkInCloses: '09:30:00',
        onTimeMinutes: 15,
      }),
    ).toBe(CUSTOM_PRESET)
    expect(
      detectLatePreset({
        startTime: '09:00',
        endTime: '12:00',
        checkInOpens: '09:00',
        onTimeUntil: '09:15',
        lateUntil: '09:40',
        checkInCloses: '09:45',
      }),
    ).toBe(CUSTOM_PRESET)
  })
})
