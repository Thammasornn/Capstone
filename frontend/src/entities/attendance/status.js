/**
 * สถานะการเข้าเรียนจาก API (คู่มือข้อ 2.2): on_time | late | absent
 * ที่เดียวที่ map สถานะ → ข้อความ + โทนสี ใช้ร่วมกันทั้ง badge, ตาราง และกราฟ
 * ข้อความไม่สื่อด้วยสีอย่างเดียว: badge ต้องแสดง label เสมอ
 *
 * @typedef {'on_time'|'late'|'absent'} AttendanceStatus
 * @typedef {'success'|'warning'|'danger'|'neutral'|'info'} Tone
 * @typedef {object} StatusMeta
 * @property {string} label     ภาษาไทย
 * @property {string} labelEn   ตามข้อความใน Figma
 * @property {Tone} tone        ส่งต่อให้ shared StatusBadge
 * @property {string} cssVar    ตัวแปรสีจาก @theme (ให้ Chart.js อ่านค่าจริง)
 */

/** @type {Readonly<Record<AttendanceStatus, StatusMeta>>} */
export const ATTENDANCE_STATUS = Object.freeze({
  on_time: { label: 'ตรงเวลา', labelEn: 'On time', tone: 'success', cssVar: '--color-success' },
  late: { label: 'สาย', labelEn: 'Late', tone: 'warning', cssVar: '--color-warning' },
  absent: { label: 'ขาด', labelEn: 'Absent', tone: 'danger', cssVar: '--color-danger' },
})

/** ลำดับที่ใช้แสดงในการ์ด/กราฟ/ตัวกรอง */
export const ATTENDANCE_STATUS_ORDER = Object.freeze(
  /** @type {AttendanceStatus[]} */ (['on_time', 'late', 'absent']),
)

/** @param {unknown} value @returns {value is AttendanceStatus} */
export function isAttendanceStatus(value) {
  return value === 'on_time' || value === 'late' || value === 'absent'
}

/**
 * ค่าที่ไม่รู้จักยังแสดงได้ (ไม่ทำหน้าพัง) เป็นโทนกลาง
 * @param {string | null | undefined} status
 * @returns {StatusMeta}
 */
export function getStatusMeta(status) {
  if (isAttendanceStatus(status)) return ATTENDANCE_STATUS[status]
  return {
    label: status ? String(status) : '-',
    labelEn: status ?? '-',
    tone: 'neutral',
    cssVar: '--color-muted',
  }
}

/**
 * สีจริงจาก CSS variable สำหรับ Chart.js (canvas ใช้ class Tailwind ไม่ได้)
 * @param {AttendanceStatus} status
 * @param {HTMLElement} [element]
 */
export function resolveStatusColor(status, element = document.documentElement) {
  return getComputedStyle(element).getPropertyValue(getStatusMeta(status).cssVar).trim()
}

/**
 * sessionStatus มาจาก Backend (ข้อมูลทดสอบมี completed, scheduled) — แสดงข้อความเท่านั้น ห้ามแตก logic ตามค่า
 * @param {string | null | undefined} sessionStatus
 */
export function formatSessionStatus(sessionStatus) {
  const known = { completed: 'จบแล้ว', scheduled: 'ตามตาราง', in_progress: 'กำลังเรียน' }
  if (!sessionStatus) return '-'
  return known[sessionStatus] ?? sessionStatus
}
