<script setup>
/** รายงานรายคาบ (5.7–5.8) + กรองสถานะ + เช็คชื่อด้วยมือ (B8) */
import { computed, ref, watch } from 'vue'
import BaseSelect from '@/shared/components/BaseSelect.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import RefreshButton from '@/shared/components/RefreshButton.vue'
import { useAsyncState } from '@/shared/composables/useAsyncState'
import { useToast } from '@/shared/composables/useToast'
import { isSessionInFuture } from '@/shared/utils/datetime'
import ManualAttendanceDialog from '@/entities/attendance/components/ManualAttendanceDialog.vue'
import { ATTENDANCE_STATUS, ATTENDANCE_STATUS_ORDER } from '@/entities/attendance/status'
import { useCourseStore } from '@/entities/course/store'
import { getSessionAttendance, getSessionSummary } from '../api'
import AttendanceRosterTable from '../components/AttendanceRosterTable.vue'
import SessionHeader from '../components/SessionHeader.vue'

const props = defineProps({ sessionId: { type: String, required: true } })
const toast = useToast()
const courses = useCourseStore()
courses.load().catch(() => {})

const filter = ref(/** @type {string | null} */ (null))
const updatedAt = ref(/** @type {Date | null} */ (null))
const report = useAsyncState(
  async () => {
    const [summary, roster] = await Promise.all([
      getSessionSummary(props.sessionId),
      getSessionAttendance(props.sessionId),
    ])
    updatedAt.value = new Date()
    return { summary, roster }
  },
  { immediate: false },
)
watch(
  () => props.sessionId,
  () => report.run(),
  { immediate: true },
)

const courseLabel = computed(() => {
  const c = courses.courses.find((x) => x.courseId === report.data.value?.summary.courseId)
  return c ? `${c.courseCode} ${c.courseName}` : ''
})
const rows = computed(() =>
  (report.data.value?.roster.items ?? []).filter((r) => !filter.value || r.status === filter.value),
)
const started = computed(() => {
  const s = report.data.value?.summary
  return s
    ? !isSessionInFuture(s.sessionDate, s.startTime) && s.sessionStatus !== 'cancelled'
    : false
})
const filterOptions = ATTENDANCE_STATUS_ORDER.map((s) => ({
  value: s,
  label: ATTENDANCE_STATUS[s].label,
}))

const editing = ref(null)
const dialogOpen = ref(false)
function openEdit(row) {
  editing.value = row
  dialogOpen.value = true
}
async function onSaved() {
  toast.show('บันทึกการเช็คชื่อแล้ว', { tone: 'success' })
  await report.run()
}
</script>

<template>
  <section class="flex flex-col gap-6">
    <ErrorState
      v-if="report.error.value && !report.data.value"
      :message="report.error.value.message"
      @retry="report.run"
    />
    <LoadingSkeleton v-else-if="!report.data.value" :lines="6" />
    <template v-else>
      <SessionHeader :summary="report.data.value.summary" :course-label="courseLabel" />

      <div class="flex flex-wrap items-end justify-between gap-3">
        <div class="w-48">
          <BaseSelect
            v-model="filter"
            label="กรองสถานะ"
            :options="filterOptions"
            placeholder="ทั้งหมด"
          />
        </div>
        <RefreshButton
          :loading="report.loading.value"
          :updated-at="updatedAt"
          @refresh="report.run"
        />
      </div>

      <AttendanceRosterTable :rows="rows" :editable="started" @edit="openEdit" />

      <ManualAttendanceDialog
        v-model:open="dialogOpen"
        :session-id="sessionId"
        :session-date="report.data.value.summary.sessionDate"
        :student="editing"
        @saved="onSaved"
      />
    </template>
  </section>
</template>
