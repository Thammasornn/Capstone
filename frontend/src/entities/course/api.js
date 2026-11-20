import { getJson } from '@/shared/api/http'

/**
 * GET /courses (คู่มือข้อ 5.3) — ผลขึ้นกับ role: นักศึกษาเห็นกลุ่มที่ลงทะเบียน, อาจารย์เห็นกลุ่มที่สอน, admin เห็นทั้งหมด
 *
 * @typedef {object} Section
 * @property {string} sectionId
 * @property {string} sectionCode
 * @property {string} semester
 * @property {number} academicYear      ปี พ.ศ.
 * @property {string | null} lecturerName
 * // field ที่ขอเพิ่มใน B5 (ยังไม่มีใน v1.0 — มีเฉพาะใน mock):
 * @property {string} [roomId]
 * @property {string | null} [roomCode]
 * @property {string[]} [classDays]     MON–SUN
 * @property {string} [startTime]
 * @property {string} [endTime]
 * @property {string} [checkInOpens]
 * @property {string} [checkInCloses]
 * @property {boolean} [cameraAttendanceEnabled]
 *
 * @typedef {object} Course
 * @property {string} courseId
 * @property {string} courseCode
 * @property {string} courseName
 * @property {Section[]} sections
 */

/** @returns {Promise<Course[]>} */
export function getCourses() {
  return getJson('/courses')
}
