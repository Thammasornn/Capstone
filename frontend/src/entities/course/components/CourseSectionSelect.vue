<script setup>
/**
 * dropdown วิชา + กลุ่มเรียน ผูกกับ useCourseStore (เลือกครั้งเดียวใช้ได้ทุกแท็บ)
 * sectionId = null = ทุกกลุ่มที่มีสิทธิ์เห็น (คู่มือข้อ 4.4) · ซ่อนกลุ่มได้ด้วย hideSection
 */
import { computed, onMounted } from 'vue'
import BaseSelect from '@/shared/components/BaseSelect.vue'
import { useCourseStore } from '../store'

defineProps({ hideSection: { type: Boolean, default: false } })

const store = useCourseStore()
onMounted(() => store.load().catch(() => {}))

const courseOptions = computed(() =>
  store.courses.map((c) => ({ value: c.courseId, label: `${c.courseCode} ${c.courseName}` })),
)
const sectionOptions = computed(() =>
  (store.selectedCourse?.sections ?? []).map((s) => ({
    value: s.sectionId,
    label: `กลุ่ม ${s.sectionCode} · ภาค ${s.semester}/${s.academicYear}`,
  })),
)

const courseId = computed({
  get: () => store.selectedCourseId,
  set: (value) => store.select(value, null),
})
const sectionId = computed({
  get: () => store.selectedSectionId,
  set: (value) => store.select(store.selectedCourseId, value),
})
</script>

<template>
  <div class="grid gap-3 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
    <BaseSelect
      v-model="courseId"
      label="รายวิชา"
      :options="courseOptions"
      :placeholder="store.courses.length ? '' : 'ยังไม่มีรายวิชา'"
      :disabled="!store.courses.length"
    />
    <BaseSelect
      v-if="!hideSection"
      v-model="sectionId"
      label="กลุ่มเรียน"
      :options="sectionOptions"
      placeholder="ทุกกลุ่ม"
      :disabled="!store.selectedCourse"
    />
  </div>
</template>
