/**
 * ค่า role ตามคู่มือ API v1.0 ข้อ 3 (ตัวพิมพ์เล็ก) — Proposal เรียก "Instructor" แต่โค้ดใช้ `lecturer`
 * @typedef {'student'|'lecturer'|'admin'} Role
 */

export const ROLES = Object.freeze({
  STUDENT: 'student',
  LECTURER: 'lecturer',
  ADMIN: 'admin',
})

/** @type {readonly Role[]} */
export const STAFF_ROLES = Object.freeze([ROLES.LECTURER, ROLES.ADMIN])

/** @type {Record<Role, string>} */
export const ROLE_LABELS = Object.freeze({
  student: 'Student',
  lecturer: 'Lecturer',
  admin: 'Admin',
})

/**
 * หน้าแรกหลังล็อกอิน (คู่มือข้อ 4.1: student → หน้านักศึกษา, lecturer/admin → Dashboard)
 * @type {Record<Role, string>}
 */
export const HOME_ROUTE_BY_ROLE = Object.freeze({
  student: 'student-home',
  lecturer: 'room-selection',
  admin: 'room-selection',
})

/** @param {unknown} value @returns {value is Role} */
export function isRole(value) {
  return value === 'student' || value === 'lecturer' || value === 'admin'
}
