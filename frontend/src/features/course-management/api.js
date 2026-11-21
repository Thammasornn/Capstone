import { getJson, http } from '@/shared/api/http'

/**
 * B5–B6 จัดการรายวิชา + ตัวเลือกห้อง / อาจารย์สำหรับฟอร์ม
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
 * @property {number} [onTimeMinutes]     ไม่ใส่ = 15 นาที (หรือเท่าช่วงเช็คชื่อถ้าสั้นกว่า)
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

/** B5 DELETE /courses/{courseId} — soft delete (Q5): ประวัติเช็คชื่อยังอยู่ · คาบที่ยังไม่เริ่มถูกยกเลิก */
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

/** ทุกห้องที่ใช้งานได้ (ใช้ในฟอร์ม) @returns {Promise<{ roomId: string, roomCode: string, building: string | null, capacity: number | null }[]>} */
export function getRoomOptions() {
  return getJson('/rooms/options')
}

/** อาจารย์ที่ใช้งานได้ (admin เลือกผู้สอน) @returns {Promise<{ lecturerId: string, name: string, email: string }[]>} */
export function getLecturers() {
  return getJson('/lecturers')
}
