import { getJson } from '@/shared/api/http'

/**
 * นักศึกษาที่ยังไม่ลงทะเบียนใบหน้า — API 5.6 กรอง faceRegistered=false
 * @param {string} courseId
 * @param {string | null} [sectionId]
 * @param {boolean | null} [faceRegistered=false]  null = ทุกคน
 */
export function getRegistrationStatus(courseId, sectionId = null, faceRegistered = false) {
  return getJson(`/courses/${courseId}/attendance/students`, {
    params: {
      ...(sectionId ? { sectionId } : {}),
      ...(faceRegistered === null ? {} : { faceRegistered }),
    },
  })
}
