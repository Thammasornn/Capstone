/**
 * วันที่/เวลาจาก API เป็นเวลาไทยไม่มี timezone (คู่มือข้อ 2.2)
 *   วันที่ "2026-11-16" · เวลา "09:00:00" · วันที่และเวลา "2026-11-16T09:05:00"
 * ห้ามใช้ new Date("...") ตรง ๆ และห้ามต่อท้าย Z — สร้าง Date จากตัวเลขแต่ละส่วนแทน
 * ผลคือเวลาบนหน้าจอตรงกับที่ Backend ส่งมาเสมอ ไม่ว่าเครื่องผู้ใช้ตั้ง timezone อะไร
 */

const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/
const TIME_RE = /^(\d{2}):(\d{2})(?::(\d{2}))?$/
// Backend อาจส่งเศษวินาทีมาด้วย ("2026-11-16T09:05:12.345678") → ตัดทิ้ง
const DATETIME_RE = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?$/

/** @param {string | null | undefined} value @returns {Date | null} */
export function parseLocalDate(value) {
  const m = typeof value === 'string' ? DATE_RE.exec(value) : null
  if (!m) return null
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
}

/** @param {string | null | undefined} value @returns {Date | null} */
export function parseLocalDateTime(value) {
  const m = typeof value === 'string' ? DATETIME_RE.exec(value) : null
  if (!m) return null
  return new Date(
    Number(m[1]),
    Number(m[2]) - 1,
    Number(m[3]),
    Number(m[4]),
    Number(m[5]),
    Number(m[6] ?? 0),
  )
}

/** "09:05:00" → นาทีนับจากเที่ยงคืน (545) @param {string | null | undefined} value */
export function timeToMinutes(value) {
  const m = typeof value === 'string' ? TIME_RE.exec(value) : null
  if (!m) return null
  return Number(m[1]) * 60 + Number(m[2])
}

/** 545 → "09:05:00" @param {number} minutes */
export function minutesToTime(minutes) {
  const h = Math.floor(minutes / 60)
  const mm = minutes % 60
  return `${String(h).padStart(2, '0')}:${String(mm).padStart(2, '0')}:00`
}

/** ระยะเวลาเป็นนาที (end − start) — null ถ้าเวลาไม่ถูกต้องหรือ end ไม่มากกว่า start */
export function durationMinutes(start, end) {
  const s = timeToMinutes(start)
  const e = timeToMinutes(end)
  if (s === null || e === null || e <= s) return null
  return e - s
}

/** "09:05:00" → "09:05" @param {string | null | undefined} value */
export function formatTime(value) {
  if (typeof value !== 'string') return '-'
  const m = TIME_RE.exec(value)
  return m ? `${m[1]}:${m[2]}` : '-'
}

/** "2026-11-16T09:05:00" → "09:05" (null → "-") */
export function formatTimeOfDateTime(value) {
  const d = parseLocalDateTime(value)
  if (!d) return '-'
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const thaiDate = new Intl.DateTimeFormat('th-TH', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
})

/** "2026-11-16" → "16 พ.ย. 2569" (ปี พ.ศ. ตาม locale ไทย) */
export function formatThaiDate(value) {
  const d = parseLocalDate(value)
  return d ? thaiDate.format(d) : '-'
}

/** "2026-11-16T14:20:11" → "16 พ.ย. 2569 14:20" */
export function formatThaiDateTime(value) {
  const d = parseLocalDateTime(value)
  if (!d) return '-'
  return `${thaiDate.format(d)} ${formatTimeOfDateTime(value)}`
}

/**
 * คาบ (วันที่ + เวลาเริ่ม) ยังไม่ถึงหรือไม่ — ใช้ซ่อน/เตือนสรุปรายคาบในอนาคต (คู่มือข้อ 5.7)
 * @param {string} sessionDate @param {string} startTime @param {Date} [now]
 */
export function isSessionInFuture(sessionDate, startTime, now = new Date()) {
  const d = parseLocalDateTime(`${sessionDate}T${startTime}`)
  return d ? d.getTime() > now.getTime() : false
}
