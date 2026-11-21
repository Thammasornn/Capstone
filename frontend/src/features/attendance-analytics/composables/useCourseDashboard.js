import { ref, shallowRef, watch } from 'vue'
import { useCourseStore } from '@/entities/course/store'
import { getCourseSessions, getCourseStudents, getCourseSummary } from '../api'

/**
 * โหลด 5.4–5.6 พร้อมกันตามวิชา/กลุ่มที่เลือกใน useCourseStore (โหลดใหม่เมื่อเปลี่ยนตัวเลือก)
 * ผลของการเลือกครั้งก่อนที่ตอบช้ากว่าจะถูกทิ้ง
 */
export function useCourseDashboard() {
  const store = useCourseStore()
  const summary = shallowRef(/** @type {import('../api').CourseSummary | null} */ (null))
  const sessions = shallowRef(/** @type {import('../api').SessionRow[]} */ ([]))
  const students = shallowRef(/** @type {import('../api').StudentRow[]} */ ([]))
  const loading = ref(false)
  const error = ref(/** @type {import('@/shared/api/errors').ApiError | null} */ (null))
  const updatedAt = ref(/** @type {Date | null} */ (null))
  let requestId = 0

  async function load() {
    const courseId = store.selectedCourseId
    if (!courseId) return
    const sectionId = store.selectedSectionId
    const id = ++requestId
    loading.value = true
    error.value = null
    try {
      const [s, ss, st] = await Promise.all([
        getCourseSummary(courseId, sectionId),
        getCourseSessions(courseId, sectionId),
        getCourseStudents(courseId, sectionId),
      ])
      if (id !== requestId) return
      summary.value = s
      sessions.value = ss.items
      students.value = st.items
      updatedAt.value = new Date()
    } catch (e) {
      if (id === requestId) error.value = /** @type {any} */ (e)
    } finally {
      if (id === requestId) loading.value = false
    }
  }

  watch(() => [store.selectedCourseId, store.selectedSectionId], load, { immediate: true })

  return { store, summary, sessions, students, loading, error, updatedAt, load }
}
