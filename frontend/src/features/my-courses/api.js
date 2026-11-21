import { getJson } from '@/shared/api/http'

/**
 * วิชาของนักศึกษา (5.3 — Backend กรองเฉพาะกลุ่มที่ลงทะเบียน) + ประวัติรายวิชา (5.9)
 * ฟีเจอร์ห้าม import กันเอง จึงประกาศ 5.9 ซ้ำที่นี่ (my-attendance ก็มีของตัวเอง)
 */
export function getMyCourses() {
  return getJson('/courses')
}

/** @returns {Promise<import('@/features/my-attendance/api').MyAttendance>} */
export function getCourseAttendance(courseId, studentId) {
  return getJson(`/courses/${courseId}/students/${studentId}/attendance`)
}

/**
 * วิชา + ผลการเข้าเรียนของแต่ละวิชา (โหลดพร้อมกัน · วิชาที่โหลดไม่ได้ได้ attendance = null)
 * @param {string} studentId
 */
export async function getMyCoursesWithAttendance(studentId) {
  const courses = await getMyCourses()
  const attendance = await Promise.all(
    courses.map((c) => getCourseAttendance(c.courseId, studentId).catch(() => null)),
  )
  return courses.map((c, i) => ({ ...c, attendance: attendance[i] }))
}
