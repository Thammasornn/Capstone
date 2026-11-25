<script setup>
/**
 * รายงานรายคาบ (5.7–5.8) + กรองสถานะ + เช็คชื่อด้วยมือ (B8)
 * คาบ completed ไม่เกิน 7 วัน → "แก้เวลาคาบ" (เริ่มนับเวลาใหม่ / ปรับช่วงเช็คชื่อ + Preview & Confirm)
 * route นี้เข้าได้เฉพาะ staff · เกิน 7 วัน backend ตอบ 409 จึงซ่อนปุ่มและบอกให้เช็คชื่อด้วยมือแทน
 * คาบเริ่มแล้ว (ไม่ใช่ scheduled / cancelled) → "นำเข้าผลเช็คชื่อ" (AttendanceImportWizard) + ประวัติการนำเข้า
 */
import { computed, ref, watch } from 'vue'
import BaseSelect from '@/shared/components/BaseSelect.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import RefreshButton from '@/shared/components/RefreshButton.vue'
import { useAsyncState } from '@/shared/composables/useAsyncState'
import { useToast } from '@/shared/composables/useToast'
import { formatThaiDate, isSessionInFuture, parseLocalDate } from '@/shared/utils/datetime'
import { describeAdjustResult } from '@/entities/attendance/adjust-summary'
import SessionAdjustMenu from '@/entities/attendance/components/SessionAdjustMenu.vue'
import AttendanceImportWizard from '@/entities/attendance/components/AttendanceImportWizard.vue'
import { describeImportResult } from '@/entities/attendance/import'
import BaseButton from '@/shared/components/BaseButton.vue'
import ManualAttendanceDialog from '@/entities/attendance/components/ManualAttendanceDialog.vue'
import { ATTENDANCE_STATUS, ATTENDANCE_STATUS_ORDER } from '@/entities/attendance/status'
import { useCourseStore } from '@/entities/course/store'
import { getSessionAttendance, getSessionSummary } from '../api'
import AttendanceRosterTable from '../components/AttendanceRosterTable.vue'
import ImportHistory from '../components/ImportHistory.vue'
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
// ---------- แก้เวลาคาบ (ภายใน 7 วันหลังวันเรียน — ตรงกับ backend) ----------
const EDIT_DAYS = 7
const completed = computed(() => report.data.value?.summary.sessionStatus === 'completed')
const withinEditDays = computed(() => {
  const d = parseLocalDate(report.data.value?.summary.sessionDate)
  if (!d) return false
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return Math.round((today.getTime() - d.getTime()) / 86_400_000) <= EDIT_DAYS
})
const adjustLabel = computed(() => {
  const s = report.data.value?.summary
  return s ? `${courseLabel.value} กลุ่ม ${s.sectionCode} · ${formatThaiDate(s.sessionDate)}` : ''
})
async function onAdjusted({ result, mode }) {
  toast.show(describeAdjustResult(result, mode), { tone: 'success' })
  await report.run()
}

// ---------- นำเข้าผลเช็คชื่อ ----------
const importOpen = ref(false)
const importReload = ref(0)
async function onImported(result) {
  toast.show(describeImportResult(result), { tone: 'success' })
  importReload.value++
  await report.run()
}

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

      <div
        v-if="completed"
        class="flex flex-wrap items-center justify-between gap-3 rounded-field border border-line px-4 py-3"
      >
        <template v-if="withinEditDays">
          <p class="text-sm text-muted">
            เวลาคาบไม่ตรงกับที่สอนจริง (เช่น อาจารย์เข้าสาย) แก้เวลาแล้วระบบคิดผลเช็คชื่อใหม่ให้
          </p>
          <SessionAdjustMenu
            :session-id="sessionId"
            :times="report.data.value.summary"
            :in-progress="false"
            :label="adjustLabel"
            button-label="แก้เวลาคาบ"
            :modes="['reanchor', 'window']"
            @applied="onAdjusted"
          />
        </template>
        <p v-else class="text-sm text-muted">
          คาบนี้ผ่านมาเกิน {{ EDIT_DAYS }} วันแล้ว แก้เวลาคาบไม่ได้ — แก้รายคนด้วยการเช็คชื่อด้วยมือ
        </p>
      </div>

      <div class="flex flex-wrap items-end justify-between gap-3">
        <div class="w-48">
          <BaseSelect
            v-model="filter"
            label="กรองสถานะ"
            :options="filterOptions"
            placeholder="ทั้งหมด"
          />
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <BaseButton v-if="started" variant="secondary" @click="importOpen = true">
            นำเข้าผลเช็คชื่อ
          </BaseButton>
          <RefreshButton
            :loading="report.loading.value"
            :updated-at="updatedAt"
            @refresh="report.run"
          />
        </div>
      </div>

      <AttendanceRosterTable :rows="rows" :editable="started" @edit="openEdit" />

      <ImportHistory v-if="started" :session-id="sessionId" :reload-key="importReload" />

      <AttendanceImportWizard
        v-if="started"
        v-model:open="importOpen"
        :session-id="sessionId"
        :label="adjustLabel"
        @imported="onImported"
      />

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
