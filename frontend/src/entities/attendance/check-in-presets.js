/**
 * กติกาเวลาสายแบบกึ่งอัตโนมัติ (F1) — เลือก "สาย 10 / 15 / 30 นาที" แล้วระบบคำนวณช่วงเช็คชื่อจากเวลาเริ่มเรียน
 * ใช้ทั้งฟอร์มรายวิชา (CheckInWindowPicker) และฟอร์มคาบเรียน (SessionForm)
 *
 * ⚙️ ค่าตั้งต้นอยู่ที่ LATE_PRESETS ที่เดียว — ได้ระเบียบมหาวิทยาลัยจริงแล้วแก้ตรงนี้
 *
 * สูตร (นับจากเวลาเริ่มเรียน S · ไม่เกินเวลาเลิกเรียน E):
 *   เปิดเช็คชื่อ   = S
 *   ตรงเวลาถึง    = S + lateAfterMinutes          (เข้าหลังจากนี้ = สาย)
 *   สายถึง        = S + absentAfterMinutes        (เข้าหลังจากนี้ = ขาด)
 *   ปิดเช็คชื่อ    = สายถึง                         (กล้องหยุดเช็คชื่อ)
 * ทุกค่าถูกตัดไม่ให้เกิน E → ผ่านกติกาของ backend เสมอ (S ≤ เปิด ≤ ตรงเวลา ≤ สาย ≤ ปิด ≤ E)
 */
import { minutesToTime, timeToMinutes } from '@/shared/utils/datetime'

/** @typedef {{ lateAfterMinutes: number, absentAfterMinutes: number }} LatePreset */

/** @type {readonly LatePreset[]} */
export const LATE_PRESETS = Object.freeze([
  { lateAfterMinutes: 10, absentAfterMinutes: 30 },
  { lateAfterMinutes: 15, absentAfterMinutes: 45 },
  { lateAfterMinutes: 30, absentAfterMinutes: 60 },
])

/** ค่าเริ่มต้นของกลุ่มเรียน / คาบใหม่ (นาที) */
export const DEFAULT_LATE_PRESET = 15

/** โหมดแก้เอง */
export const CUSTOM_PRESET = 'custom'

/** @param {number | string} key @returns {LatePreset | null} */
export function findLatePreset(key) {
  return LATE_PRESETS.find((p) => p.lateAfterMinutes === Number(key)) ?? null
}

/**
 * คำนวณช่วงเช็คชื่อจาก preset
 * @param {string} startTime "HH:mm" หรือ "HH:mm:ss"
 * @param {string} endTime
 * @param {number | string} presetKey lateAfterMinutes ของ preset
 * @param {{ seconds?: boolean }} [options] seconds = คืน "HH:mm:ss" (ฟอร์มรายวิชา) · ไม่งั้น "HH:mm"
 * @returns {{ checkInOpens: string, onTimeUntil: string, lateUntil: string, checkInCloses: string, onTimeMinutes: number } | null}
 *   null = เวลาเรียนไม่ถูกต้อง หรือ preset ไม่มี
 */
export function computeCheckInWindow(startTime, endTime, presetKey, { seconds = false } = {}) {
  const preset = findLatePreset(presetKey)
  const s = timeToMinutes(startTime)
  const e = timeToMinutes(endTime)
  if (!preset || s === null || e === null || e <= s) return null
  const onTime = Math.min(s + preset.lateAfterMinutes, e)
  const late = Math.min(s + preset.absentAfterMinutes, e)
  const fmt = (m) => (seconds ? minutesToTime(m) : minutesToTime(m).slice(0, 5))
  return {
    checkInOpens: fmt(s),
    onTimeUntil: fmt(onTime),
    lateUntil: fmt(late),
    checkInCloses: fmt(late),
    onTimeMinutes: onTime - s,
  }
}

/**
 * ค่าที่มีอยู่ตรงกับ preset ไหน (ใช้ตอนโหลดฟอร์ม) · ไม่ตรง = 'custom'
 * รับได้ทั้ง onTimeUntil (คาบ) หรือ onTimeMinutes (กลุ่มเรียน) · lateUntil ว่าง = สายได้จนปิดเช็คชื่อ
 * @param {{ startTime: string, endTime: string, checkInOpens: string, checkInCloses: string,
 *   onTimeUntil?: string | null, onTimeMinutes?: number | string | null, lateUntil?: string | null }} v
 * @returns {number | 'custom'}
 */
export function detectLatePreset(v) {
  const same = (a, b) => a !== null && a === b
  const opens = timeToMinutes(v.checkInOpens)
  const closes = timeToMinutes(v.checkInCloses)
  const late = timeToMinutes(v.lateUntil) ?? closes
  const onTime =
    timeToMinutes(v.onTimeUntil) ??
    (opens !== null &&
    v.onTimeMinutes !== null &&
    v.onTimeMinutes !== undefined &&
    v.onTimeMinutes !== ''
      ? opens + Number(v.onTimeMinutes)
      : null)
  for (const p of LATE_PRESETS) {
    const w = computeCheckInWindow(v.startTime, v.endTime, p.lateAfterMinutes)
    if (!w) continue
    if (
      same(opens, timeToMinutes(w.checkInOpens)) &&
      same(onTime, timeToMinutes(w.onTimeUntil)) &&
      same(late, timeToMinutes(w.lateUntil)) &&
      same(closes, timeToMinutes(w.checkInCloses))
    )
      return p.lateAfterMinutes
  }
  return CUSTOM_PRESET
}
