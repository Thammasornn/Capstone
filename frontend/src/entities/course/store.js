import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getCourses } from './api'

/**
 * วิชา + วิชา/กลุ่มเรียนที่เลือกไว้ ใช้ร่วมกันระหว่างแท็บใน Courses hub (เลือกครั้งเดียว ใช้ได้ทุกแท็บ)
 * sectionId = null หมายถึง "ทุกกลุ่มที่มีสิทธิ์เห็น" (คู่มือข้อ 4.4)
 */
export const useCourseStore = defineStore('course', () => {
  /** @type {import('vue').Ref<import('./api').Course[]>} */
  const courses = ref([])
  const loaded = ref(false)
  /** @type {import('vue').Ref<string | null>} */
  const selectedCourseId = ref(null)
  /** @type {import('vue').Ref<string | null>} */
  const selectedSectionId = ref(null)

  const selectedCourse = computed(
    () => courses.value.find((c) => c.courseId === selectedCourseId.value) ?? null,
  )

  /** @param {{ force?: boolean }} [options] */
  async function load({ force = false } = {}) {
    if (loaded.value && !force) return courses.value
    courses.value = await getCourses()
    loaded.value = true
    if (!selectedCourse.value) select(courses.value[0]?.courseId ?? null)
    return courses.value
  }

  /** @param {string | null} courseId @param {string | null} [sectionId] */
  function select(courseId, sectionId = null) {
    selectedCourseId.value = courseId
    selectedSectionId.value = sectionId
  }

  function reset() {
    courses.value = []
    loaded.value = false
    select(null)
  }

  return {
    courses,
    loaded,
    selectedCourseId,
    selectedSectionId,
    selectedCourse,
    load,
    select,
    reset,
  }
})
