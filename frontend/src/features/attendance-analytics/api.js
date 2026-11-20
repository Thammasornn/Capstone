import { getJson } from '@/shared/api/http'

/**
 * Dashboard ระดับวิชา (คู่มือข้อ 4.4, API 5.4–5.6) — lecturer เห็นเฉพาะกลุ่มที่สอน
 * sectionId = null → รวมทุกกลุ่มที่มีสิทธิ์เห็น
 *
 * @typedef {object} CourseSummary  (5.4)
 * @property {string} courseId
 * @property {string} courseCode
 * @property {string} courseName
 * @property {string | null} sectionId
 * @property {number} totalSessions   0 = ยังไม่มีข้อมูล (ห้ามแสดง 0%)
 * @property {number} totalStudents
 * @property {number} onTime          นับเป็น "ครั้ง" ไม่ใช่จำนวนคน
 * @property {number} late
 * @property {number} absent
 * @property {number} attendanceRate
 *
 * @typedef {object} SessionRow  (5.5)
 * @property {string} sessionId
 * @property {string} sectionId
 * @property {string} sectionCode
 * @property {string} sessionDate
 * @property {string} startTime
 * @property {string} endTime
 * @property {string} sessionStatus   แสดงเป็นข้อความ ห้ามแตก logic
 * @property {boolean} started        false → ข้ามในกราฟ แสดง "ยังไม่ถึงคาบ"
 * @property {number} totalStudents
 * @property {number} onTime
 * @property {number} late
 * @property {number} absent
 * @property {number} attendanceRate
 *
 * @typedef {object} StudentRow  (5.6)
 * @property {string} studentId
 * @property {string} studentCode
 * @property {string} studentName
 * @property {number} onTime
 * @property {number} late
 * @property {number} absent          ใช้คำนวณกลุ่มเสี่ยง (entities/attendance/risk.js)
 * @property {number} attendanceRate
 * @property {boolean} faceRegistered
 */

const params = (sectionId, extra = {}) => ({
  params: { ...(sectionId ? { sectionId } : {}), ...extra },
})

/** @returns {Promise<CourseSummary>} */
export function getCourseSummary(courseId, sectionId = null) {
  return getJson(`/courses/${courseId}/attendance/summary`, params(sectionId))
}

/** @returns {Promise<{ courseId: string, items: SessionRow[] }>} */
export function getCourseSessions(courseId, sectionId = null) {
  return getJson(`/courses/${courseId}/attendance/sessions`, params(sectionId))
}

/** @returns {Promise<{ courseId: string, items: StudentRow[] }>} */
export function getCourseStudents(courseId, sectionId = null) {
  return getJson(`/courses/${courseId}/attendance/students`, params(sectionId))
}
