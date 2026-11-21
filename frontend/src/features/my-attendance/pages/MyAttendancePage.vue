<script setup>
/** ประวัติการเข้าเรียนรายวิชา (5.9) · เลือกวิชาได้ (?courseId= จากหน้าวิชาของฉัน) */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BaseSelect from '@/shared/components/BaseSelect.vue'
import EmptyState from '@/shared/components/EmptyState.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import StatCard from '@/shared/components/StatCard.vue'
import { useAsyncState } from '@/shared/composables/useAsyncState'
import { formatRate } from '@/shared/utils/format'
import { useAuthStore } from '@/features/auth/store'
import { getMyAttendance, getMyCourses } from '../api'
import AttendanceHistoryList from '../components/AttendanceHistoryList.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const courses = useAsyncState(getMyCourses)
const courseId = ref(
  /** @type {string | null} */ (
    typeof route.query.courseId === 'string' ? route.query.courseId : null
  ),
)
watch(courses.data, (list) => {
  if (list?.length && !list.some((c) => c.courseId === courseId.value))
    courseId.value = list[0].courseId
})
watch(courseId, (id) => router.replace({ query: id ? { courseId: id } : {} }))

const history = useAsyncState(
  () => (courseId.value ? getMyAttendance(courseId.value, auth.studentId) : null),
  { immediate: false },
)
watch(courseId, () => history.run(), { immediate: true })

const options = computed(() =>
  (courses.data.value ?? []).map((c) => ({
    value: c.courseId,
    label: `${c.courseCode} ${c.courseName}`,
  })),
)
</script>

<template>
  <section class="flex flex-col gap-6">
    <h1 class="text-[28px] font-bold">ประวัติการเข้าเรียน</h1>
    <ErrorState
      v-if="courses.error.value"
      :message="courses.error.value.message"
      @retry="courses.run"
    />
    <LoadingSkeleton v-else-if="!courses.data.value" :lines="3" />
    <EmptyState v-else-if="!courses.data.value.length" title="ยังไม่มีรายวิชาที่ลงทะเบียน" />
    <template v-else>
      <div class="w-full max-w-xl">
        <BaseSelect v-model="courseId" label="รายวิชา" :options="options" />
      </div>
      <ErrorState
        v-if="history.error.value"
        :message="history.error.value.message"
        @retry="history.run"
      />
      <LoadingSkeleton v-else-if="!history.data.value || history.loading.value" :lines="5" />
      <template v-else>
        <div class="grid grid-cols-2 gap-3 lg:grid-cols-4">
          <StatCard
            label="อัตราเข้าเรียน"
            :value="
              formatRate(history.data.value.attendanceRate, {
                totalSessions: history.data.value.items.length,
              })
            "
            tone="primary"
            :hint="`${history.data.value.items.length} คาบ`"
          />
          <StatCard label="ตรงเวลา" :value="history.data.value.onTime" tone="success" />
          <StatCard label="สาย" :value="history.data.value.late" tone="warning" />
          <StatCard label="ขาด" :value="history.data.value.absent" tone="danger" />
        </div>
        <AttendanceHistoryList :items="history.data.value.items" />
      </template>
    </template>
  </section>
</template>
