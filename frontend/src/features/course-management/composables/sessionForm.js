import { minutesToTime, parseLocalDateTime, timeToMinutes } from '@/shared/utils/datetime'

/**
 * ค่าในฟอร์มคาบเรียน (SessionForm.vue) — ใช้ทั้งแก้ / เลื่อนคาบ และคาบชดเชย / คาบพิเศษ
 * เก็บเวลาแบบ "HH:mm" หรือ "HH:mm:ss" ก็ได้ (TimeRangeField ให้ "HH:mm:ss") · ส่ง API เป็น "HH:mm" เสมอ
 */

export const SESSION_REASON_MAX = 500

const hhmm = (v) => (typeof v === 'string' && v.length >= 5 ? v.slice(0, 5) : '')

/** ฟอร์มจากคาบที่มีอยู่ (SessionView) */
export function valuesFromSession(s) {
  return {
    sessionDate: s.sessionDate,
    startTime: hhmm(s.startTime),
    endTime: hhmm(s.endTime),
    checkInOpens: hhmm(s.checkInOpens),
    onTimeUntil: hhmm(s.onTimeUntil),
    lateUntil: hhmm(s.lateUntil),
    checkInCloses: hhmm(s.checkInCloses),
    roomId: s.roomId,
  }
}

/**
 * ค่าเริ่มต้นของคาบชดเชย / คาบพิเศษ = เวลา / ห้อง / ช่วงเช็คชื่อตามตารางปกติของกลุ่ม
 * ตรงเวลาถึง = เปิด + onTimeMinutes (ไม่เกินปิด) · สายถึง = lateUntil ของกลุ่ม หรือปิดเช็คชื่อ
 * @param {object | null} section SectionDetail จาก GET /courses/{id}
 */
export function valuesFromSection(section, sessionDate = '') {
  const opens = timeToMinutes(section?.checkInOpens)
  const closes = timeToMinutes(section?.checkInCloses)
  let onTime = ''
  if (opens !== null && closes !== null) {
    onTime = hhmm(minutesToTime(Math.min(opens + Number(section.onTimeMinutes ?? 15), closes)))
  }
  return {
    sessionDate,
    startTime: hhmm(section?.startTime),
    endTime: hhmm(section?.endTime),
    checkInOpens: hhmm(section?.checkInOpens),
    onTimeUntil: onTime,
    lateUntil: hhmm(section?.lateUntil) || hhmm(section?.checkInCloses),
    checkInCloses: hhmm(section?.checkInCloses),
    roomId: section?.roomId ?? null,
  }
}

/**
 * ตรวจก่อนส่ง (กติกาเดียวกับ Backend — Backend ตรวจซ้ำ + ตรวจห้องชน / 1 คาบต่อวัน)
 * @param {{ semesterStart?: string, semesterEnd?: string, now?: Date }} [options]
 * @returns {Record<string, string>} key: sessionDate | time | window | roomId
 */
export function validateSessionValues(v, { semesterStart, semesterEnd, now = new Date() } = {}) {
  /** @type {Record<string, string>} */
  const errors = {}
  const [s, e, o, ot, l, c] = [
    v.startTime,
    v.endTime,
    v.checkInOpens,
    v.onTimeUntil,
    v.lateUntil,
    v.checkInCloses,
  ].map(timeToMinutes)

  if (!v.sessionDate) errors.sessionDate = 'กรุณาเลือกวันที่'
  else if (
    semesterStart &&
    semesterEnd &&
    (v.sessionDate < semesterStart || v.sessionDate > semesterEnd)
  )
    errors.sessionDate = 'วันที่ต้องอยู่ในภาคเรียนของกลุ่มเรียน'

  if (s === null || e === null) errors.time = 'กรุณากรอกเวลาเริ่มและเวลาเลิกเรียน'
  else if (e <= s) errors.time = 'เวลาเลิกเรียนต้องหลังเวลาเริ่ม'
  else if (v.sessionDate && !errors.sessionDate) {
    const start = parseLocalDateTime(`${v.sessionDate}T${hhmm(v.startTime)}`)
    if (start && start.getTime() <= now.getTime())
      errors.time = 'เวลาเริ่มต้องอยู่ในอนาคต (ถ้าจะเริ่มตอนนี้ใช้ปุ่มเริ่มคาบที่หน้าห้อง)'
  }

  if ([o, ot, l, c].some((x) => x === null)) errors.window = 'กรุณากรอกช่วงเช็คชื่อให้ครบ'
  else if (!errors.time) {
    if (o < s) errors.window = 'เปิดเช็คชื่อต้องไม่ก่อนเวลาเริ่มเรียน'
    else if (ot < o) errors.window = 'ตรงเวลาถึงต้องไม่ก่อนเวลาเปิดเช็คชื่อ'
    else if (l < ot) errors.window = 'สายถึงต้องไม่ก่อนตรงเวลาถึง'
    else if (c < l) errors.window = 'ปิดเช็คชื่อต้องไม่ก่อนสายถึง'
    else if (c > e) errors.window = 'ปิดเช็คชื่อต้องไม่เกินเวลาเลิกเรียน'
  }

  if (!v.roomId) errors.roomId = 'กรุณาเลือกห้อง'
  return errors
}

/** เหตุผล (บังคับ ≤ 500) → ข้อความ error หรือ '' */
export function validateReason(reason) {
  const r = (reason ?? '').trim()
  if (!r) return 'กรุณาระบุเหตุผล'
  if (r.length > SESSION_REASON_MAX) return `เหตุผลยาวได้ไม่เกิน ${SESSION_REASON_MAX} ตัวอักษร`
  return ''
}

/** ฟอร์ม → body ของ API (เวลา "HH:mm") */
export function toSessionBody(v) {
  return {
    sessionDate: v.sessionDate,
    startTime: hhmm(v.startTime),
    endTime: hhmm(v.endTime),
    checkInOpens: hhmm(v.checkInOpens),
    onTimeUntil: hhmm(v.onTimeUntil),
    lateUntil: hhmm(v.lateUntil),
    checkInCloses: hhmm(v.checkInCloses),
    roomId: v.roomId,
  }
}

/** "2026-12-01" → "1/12" */
export function shortDate(value) {
  const m = typeof value === 'string' ? /^\d{4}-(\d{2})-(\d{2})$/.exec(value) : null
  return m ? `${Number(m[2])}/${Number(m[1])}` : '-'
}

export const SESSION_STATUS = {
  scheduled: { label: 'ยังไม่เริ่ม', tone: 'neutral' },
  in_progress: { label: 'กำลังเรียน', tone: 'info' },
  completed: { label: 'จบแล้ว', tone: 'success' },
  cancelled: { label: 'ยกเลิก', tone: 'danger' },
}
