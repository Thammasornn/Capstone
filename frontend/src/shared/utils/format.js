/** ค่า null/undefined/สตริงว่าง → "-" (คู่มือข้อ 2.2: field ที่ไม่มีข้อมูลเป็น null) */
export function dash(value) {
  return value === null || value === undefined || value === '' ? '-' : String(value)
}

export const NO_DATA_TEXT = 'ยังไม่มีข้อมูล'

/**
 * attendanceRate (0–100, ทศนิยม 2 ตำแหน่ง) → "80.00%"
 * totalSessions = 0 → "ยังไม่มีข้อมูล" แทน "0%" (คู่มือข้อ 4.6)
 * @param {number | null | undefined} rate
 * @param {{ totalSessions?: number | null }} [context]
 */
export function formatRate(rate, context = {}) {
  if (context.totalSessions === 0) return NO_DATA_TEXT
  if (typeof rate !== 'number' || Number.isNaN(rate)) return '-'
  return `${rate.toFixed(2)}%`
}

/** ชื่อเต็มจาก user ของ /me */
export function fullName(person) {
  if (!person) return '-'
  return [person.firstName, person.lastName].filter(Boolean).join(' ') || '-'
}

/** ขนาดไฟล์อ่านง่าย: 5242880 → "5 MB" */
export function formatBytes(bytes) {
  if (typeof bytes !== 'number' || bytes < 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 ** 2) return `${Math.round(bytes / 1024)} KB`
  return `${Math.round((bytes / 1024 ** 2) * 10) / 10} MB`
}
