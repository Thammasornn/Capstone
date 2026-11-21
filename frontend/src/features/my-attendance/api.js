import { getJson } from '@/shared/api/http'

/**
 * @typedef {object} MyAttendanceItem
 * @property {string} sessionId
 * @property {string} sessionDate
 * @property {string | null} checkInTime
 * @property {string | null} checkOutTime   ส่วนใหญ่เป็น null จนถึง Release 2
 * @property {import('@/entities/attendance/status').AttendanceStatus} status
 *
 * @typedef {object} MyAttendance  (API 5.9)
 * @property {string} courseId
 * @property {string} courseCode
 * @property {string} courseName
 * @property {string} studentId
 * @property {string} studentCode
 * @property {string} studentName
 * @property {number} onTime
 * @property {number} late
 * @property {number} absent
 * @property {number} attendanceRate
 * @property {MyAttendanceItem[]} items   เฉพาะคาบที่เริ่มแล้ว เรียงตามวันที่
 */

/**
 * GET /courses/{courseId}/students/{studentId}/attendance
 * studentId ต้องตรงกับ user.student.studentId ไม่เช่นนั้น 403
 * @returns {Promise<MyAttendance>}
 */
export function getMyAttendance(courseId, studentId) {
  return getJson(`/courses/${courseId}/students/${studentId}/attendance`)
}

/** วิชาที่ลงทะเบียน (5.3) ใช้ทำตัวเลือกวิชา */
export function getMyCourses() {
  return getJson('/courses')
}
