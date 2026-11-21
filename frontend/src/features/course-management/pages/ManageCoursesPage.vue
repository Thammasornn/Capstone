<script setup>
/** จัดการรายวิชา (B5–B6): รายการ + เพิ่ม / แก้ไข / ลบ + สวิตช์กล้อง */
import { ref } from 'vue'
import EmptyState from '@/shared/components/EmptyState.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import RefreshButton from '@/shared/components/RefreshButton.vue'
import { useAsyncState } from '@/shared/composables/useAsyncState'
import { useToast } from '@/shared/composables/useToast'
import { useCourseStore } from '@/entities/course/store'
import { listCourses } from '../api'
import CourseTable from '../components/CourseTable.vue'
import DeleteCourseDialog from '../components/DeleteCourseDialog.vue'

const toast = useToast()
const courseStore = useCourseStore()
const updatedAt = ref(/** @type {Date | null} */ (null))
const {
  data: courses,
  loading,
  error,
  run,
} = useAsyncState(async () => {
  const result = await listCourses()
  updatedAt.value = new Date()
  return result
})

const deleting = ref(null)
const deleteOpen = ref(false)
function askDelete(course) {
  deleting.value = course
  deleteOpen.value = true
}
async function onDeleted(course) {
  toast.show(`ลบ ${course.courseCode} แล้ว`, { tone: 'success' })
  await Promise.all([run(), courseStore.load({ force: true }).catch(() => {})])
}
</script>

<template>
  <section class="flex flex-col gap-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <RefreshButton :loading="loading" :updated-at="updatedAt" @refresh="run" />
      <RouterLink
        :to="{ name: 'course-new' }"
        class="inline-flex min-h-11 items-center rounded-button bg-primary px-4 text-sm font-semibold text-on-dark hover:bg-primary/90"
      >
        + เพิ่มรายวิชา
      </RouterLink>
    </div>

    <ErrorState v-if="error && !courses" :message="error.message" @retry="run" />
    <LoadingSkeleton v-else-if="!courses" :lines="5" />
    <EmptyState
      v-else-if="!courses.length"
      title="ยังไม่มีรายวิชา"
      description="กด “เพิ่มรายวิชา” เพื่อเริ่มต้น"
    />
    <CourseTable v-else :courses="courses" @delete="askDelete" />

    <DeleteCourseDialog v-model:open="deleteOpen" :course="deleting" @deleted="onDeleted" />
  </section>
</template>
