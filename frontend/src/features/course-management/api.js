import { getJson, http } from '@/shared/api/http'

/**
 * ⚠️ B5–B6 ยังไม่มีใน API v1.0 — ใช้ mock ตาม shape ในเอกสารแจ้ง Back-End
 * ตารางเรียน ห้อง ช่วงเช็คชื่อ และสวิตช์กล้อง ผูกกับ section (ไม่ใช่ course)
 *
 * @typedef {object} SectionInput
 * @property {string} [sectionId]           ไม่มี = สร้างกลุ่มใหม่
 * @property {string} sectionCode
 * @property {string} roomId
 * @property {string} [lecturerId]
 * @property {('MON'|'TUE'|'WED'|'THU'|'FRI'|'SAT'|'SUN')[]} classDays
 * @property {string} startTime             "HH:mm:ss"
 * @property {string} endTime
 * @property {string} checkInOpens
 * @property {string} checkInCloses
 * @property {boolean} cameraAttendanceEnabled
 *
 * @typedef {object} CourseInput
 * @property {string} courseCode
 * @property {string} courseName
 * @property {SectionInput[]} sections
 */

/** GET /courses (5.3 + field ตารางเรียนที่ขอใน B5) @returns {Promise<import('@/entities/course/api').Course[]>} */
export function listCourses() {
  return getJson('/courses')
}

/** B5 GET /courses/{courseId} @returns {Promise<import('@/entities/course/api').Course>} */
export function getCourse(courseId) {
  return getJson(`/courses/${courseId}`)
}

/** B5 POST /courses @param {CourseInput} body */
export async function createCourse(body) {
  const { data } = await http.post('/courses', body)
  return data
}

/** B5 PUT /courses/{courseId} @param {string} courseId @param {CourseInput} body */
export async function updateCourse(courseId, body) {
  const { data } = await http.put(`/courses/${courseId}`, body)
  return data
}

/** B5 DELETE /courses/{courseId} — 409 เมื่อมีประวัติเช็คชื่อแล้ว (รอ Q5) */
export async function deleteCourse(courseId) {
  await http.delete(`/courses/${courseId}`)
}

/**
 * B6 PATCH /sections/{sectionId}/camera-attendance
 * @returns {Promise<{ sectionId: string, cameraAttendanceEnabled: boolean }>}
 */
export async function setCameraAttendance(sectionId, enabled) {
  const { data } = await http.patch(`/sections/${sectionId}/camera-attendance`, { enabled })
  return data
}
