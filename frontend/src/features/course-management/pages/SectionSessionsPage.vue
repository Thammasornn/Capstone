<script setup>
/**
 * คาบเรียน (D3) — คาบจริงแต่ละวันของ 1 กลุ่มเรียน (แยกจากรายวิชา = แม่แบบตารางเรียน)
 *  - GET /sections/{id}/sessions · ปุ่มต่อคาบตาม allowedActions เท่านั้น
 *  - แก้ / เลื่อน (PATCH) · ยกเลิก (+ ชดเชยในคำขอเดียว) · คืนคาบ · เพิ่มคาบชดเชย / คาบพิเศษ
 *  - คาบที่กำลังเรียน / จบแล้ว ปรับเวลาที่หน้าห้อง / Session Report (Prompt B)
 *  - จอใหญ่ = ตาราง · มือถือ = card
 */
import { computed, ref } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseCard from '@/shared/components/BaseCard.vue'
import BaseDialog from '@/shared/components/BaseDialog.vue'
import DataTable from '@/shared/components/DataTable.vue'
import EmptyState from '@/shared/components/EmptyState.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import RefreshButton from '@/shared/components/RefreshButton.vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import TextField from '@/shared/components/TextField.vue'
import { normalizeError } from '@/shared/api/errors'
import { useAsyncState } from '@/shared/composables/useAsyncState'
import { useToast } from '@/shared/composables/useToast'
import { formatThaiDate, formatTime } from '@/shared/utils/datetime'
import { getCourse, getRoomOptions, listSectionSessions, restoreSession } from '../api'
import { SESSION_REASON_MAX, SESSION_STATUS, validateReason } from '../composables/sessionForm'
import SessionActions from '../components/SessionActions.vue'
import SessionBadges from '../components/SessionBadges.vue'
import SessionCancelDialog from '../components/SessionCancelDialog.vue'
import SessionEditDialog from '../components/SessionEditDialog.vue'

const props = defineProps({
  courseId: { type: String, required: true },
  sectionId: { type: String, required: true },
})
const toast = useToast()

const course = useAsyncState(() => getCourse(props.courseId))
const rooms = useAsyncState(getRoomOptions)
const updatedAt = ref(/** @type {Date | null} */ (null))
const list = useAsyncState(async () => {
  const r = await listSectionSessions(props.sectionId)
  updatedAt.value = new Date()
  return r
})

const section = computed(
  () => course.data.value?.sections.find((s) => s.sectionId === props.sectionId) ?? null,
)
const semester = computed(() => ({
  start: list.data.value?.semesterStart,
  end: list.data.value?.semesterEnd,
}))
const roomOptions = computed(() =>
  (rooms.data.value ?? []).map((r) => ({
    value: r.roomId,
    label: `${r.roomCode}${r.building ? ` · ${r.building}` : ''}${r.capacity ? ` (${r.capacity} ที่)` : ''}`,
  })),
)
const DAY = { MON: 'จ.', TUE: 'อ.', WED: 'พ.', THU: 'พฤ.', FRI: 'ศ.', SAT: 'ส.', SUN: 'อา.' }

// ---------- ตัวกรอง ----------
const sessions = computed(() => list.data.value?.sessions ?? [])
const FILTERS = [
  {
    key: 'upcoming',
    label: 'กำลังจะถึง',
    test: (s) => s.sessionStatus === 'scheduled' || s.sessionStatus === 'in_progress',
  },
  { key: 'past', label: 'ผ่านไปแล้ว', test: (s) => s.sessionStatus === 'completed' },
  { key: 'cancelled', label: 'ยกเลิก', test: (s) => s.sessionStatus === 'cancelled' },
  { key: 'all', label: 'ทั้งหมด', test: () => true },
]
const filter = ref('upcoming')
const counts = computed(() =>
  Object.fromEntries(FILTERS.map((f) => [f.key, sessions.value.filter(f.test).length])),
)
const shown = computed(() => {
  const f = FILTERS.find((x) => x.key === filter.value)
  const rows = sessions.value.filter(f.test)
  return filter.value === 'past' ? [...rows].reverse() : rows // ผ่านไปแล้ว: ล่าสุดก่อน
})
const makeupCandidates = computed(() =>
  sessions.value.filter((s) => s.sessionStatus === 'cancelled' && !s.makeupSessionId),
)

const columns = [
  { key: 'sessionNo', label: 'คาบที่' },
  { key: 'sessionDate', label: 'วันที่' },
  { key: 'time', label: 'เวลา' },
  { key: 'roomCode', label: 'ห้อง' },
  { key: 'status', label: 'สถานะ' },
  { key: 'attendance', label: 'ผลเช็คชื่อ' },
  { key: 'actions', label: '' },
]
const statusMeta = (s) =>
  SESSION_STATUS[s.sessionStatus] ?? { label: s.sessionStatus, tone: 'neutral' }
function attendanceText(s) {
  const a = s.attendance
  if (s.sessionStatus === 'scheduled') return '-'
  if (s.sessionStatus === 'cancelled' && !(a.onTime + a.late + a.absent)) return '-'
  const parts = [`ตรงเวลา ${a.onTime}`, `สาย ${a.late}`, `ขาด ${a.absent}`]
  if (a.noRecord && s.sessionStatus === 'in_progress') parts.push(`ยังไม่มีผล ${a.noRecord}`)
  return parts.join(' · ')
}

// ---------- dialogs ----------
const target = ref(/** @type {any} */ (null))
const editOpen = ref(false)
const editMode = ref('reschedule')
const cancelOpen = ref(false)
function openReschedule(s) {
  target.value = s
  editMode.value = 'reschedule'
  editOpen.value = true
}
function openCreate() {
  target.value = null
  editMode.value = 'create'
  editOpen.value = true
}
function openCancel(s) {
  target.value = s
  cancelOpen.value = true
}

async function onSaved({ result, mode, makeup }) {
  const when = `${formatThaiDate(result.sessionDate)} ${result.startTime}–${result.endTime}`
  toast.show(
    mode === 'reschedule'
      ? `แก้คาบที่ ${result.sessionNo} แล้ว (${when})`
      : `เพิ่ม${makeup ? 'คาบชดเชย' : 'คาบพิเศษ'}แล้ว (${when})`,
    { tone: 'success' },
  )
  await list.run()
}
async function onCancelled(result) {
  const parts = [`ยกเลิกคาบที่ ${result.session.sessionNo} แล้ว`]
  if (result.keptAttendanceCount)
    parts.push(`เก็บผลเช็คชื่อ ${result.keptAttendanceCount} คนไว้ (ไม่นับในสถิติ)`)
  if (result.makeup)
    parts.push(`คาบชดเชย ${formatThaiDate(result.makeup.sessionDate)} ${result.makeup.startTime}`)
  toast.show(parts.join(' · '), { tone: 'success' })
  await list.run()
}

// คืนคาบ: ยืนยัน + เหตุผล (backend บังคับ)
const restoreOpen = ref(false)
const restoreReason = ref('')
const restoreError = ref('')
const restoring = ref(false)
function openRestore(s) {
  target.value = s
  restoreReason.value = ''
  restoreError.value = ''
  restoreOpen.value = true
}
async function doRestore() {
  restoreError.value = validateReason(restoreReason.value)
  if (restoreError.value) return
  restoring.value = true
  try {
    const r = await restoreSession(target.value.sessionId, restoreReason.value.trim())
    restoreOpen.value = false
    toast.show(`คืนคาบที่ ${r.sessionNo} (${formatThaiDate(r.sessionDate)}) แล้ว`, {
      tone: 'success',
    })
    await list.run()
  } catch (e) {
    restoreError.value = normalizeError(e).message
  } finally {
    restoring.value = false
  }
}
</script>

<template>
  <section class="mx-auto flex max-w-6xl flex-col gap-6">
    <div>
      <RouterLink :to="{ name: 'courses-manage' }" class="text-sm text-muted hover:text-ink"
        >← รายวิชา</RouterLink
      >
      <div class="mt-2 flex flex-wrap items-start justify-between gap-3">
        <div class="min-w-0">
          <h1 class="text-[28px] font-bold">
            คาบเรียน
            <template v-if="course.data.value && section">
              {{ course.data.value.courseCode }}-{{ section.sectionCode }}
            </template>
          </h1>
          <p v-if="course.data.value" class="text-sm text-muted">
            {{ course.data.value.courseName }}
          </p>
          <p v-if="section" class="mt-1 text-sm">
            ตารางปกติ: {{ (section.classDays ?? []).map((d) => DAY[d]).join(' ') }}
            {{ formatTime(section.startTime) }}–{{ formatTime(section.endTime) }} · ห้อง
            {{ section.roomCode ?? '-' }} · เช็คชื่อ {{ formatTime(section.checkInOpens) }}–{{
              formatTime(section.checkInCloses)
            }}
          </p>
          <p class="mt-1 text-xs text-muted">
            แก้เฉพาะคาบที่เลือก ตารางเรียนของรายวิชาไม่เปลี่ยน ·
            <RouterLink
              :to="{ name: 'section-students', params: { courseId, sectionId } }"
              class="font-semibold text-primary hover:underline"
              >รายชื่อนักศึกษา</RouterLink
            >
            ·
            <RouterLink
              :to="{ name: 'course-edit', params: { courseId } }"
              class="font-semibold text-primary hover:underline"
              >แก้ตารางเรียนของรายวิชา</RouterLink
            >
          </p>
        </div>
        <BaseButton :disabled="!section || !list.data.value" @click="openCreate">
          + เพิ่มคาบชดเชย / คาบพิเศษ
        </BaseButton>
      </div>
    </div>

    <ErrorState
      v-if="course.error.value && !course.data.value"
      :message="course.error.value.message"
      @retry="course.run"
    />
    <EmptyState
      v-else-if="course.data.value && !section"
      title="ไม่พบกลุ่มเรียนนี้ในรายวิชา"
      description="กลับไปเลือกกลุ่มเรียนจากหน้ารายวิชา"
    />
    <ErrorState
      v-else-if="list.error.value && !list.data.value"
      :message="list.error.value.message"
      @retry="list.run"
    />
    <LoadingSkeleton v-else-if="!list.data.value" :lines="6" />

    <template v-else>
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div role="tablist" class="-mx-1 flex gap-2 overflow-x-auto px-1 py-1">
          <button
            v-for="f in FILTERS"
            :key="f.key"
            type="button"
            role="tab"
            :aria-selected="filter === f.key ? 'true' : 'false'"
            class="inline-flex shrink-0 items-center gap-1 rounded-button border px-3 py-1.5 text-[13px] font-medium"
            :class="
              filter === f.key
                ? 'border-primary bg-primary text-on-dark'
                : 'border-line bg-surface text-ink hover:border-primary/40'
            "
            @click="filter = f.key"
          >
            {{ f.label }} <span class="opacity-80">{{ counts[f.key] }}</span>
          </button>
        </div>
        <RefreshButton :loading="list.loading.value" :updated-at="updatedAt" @refresh="list.run" />
      </div>

      <!-- จอใหญ่: ตาราง -->
      <div class="hidden md:block">
        <DataTable
          :columns="columns"
          :rows="shown"
          row-key="sessionId"
          caption="คาบเรียนของกลุ่มเรียน"
          empty-text="ไม่มีคาบในตัวกรองนี้"
        >
          <template #cell-sessionDate="{ row }">
            <span class="whitespace-nowrap">{{ formatThaiDate(row.sessionDate) }}</span>
            <SessionBadges :session="row" class="mt-1 max-w-64" />
          </template>
          <template #cell-time="{ row }">
            <span class="whitespace-nowrap">{{ row.startTime }}–{{ row.endTime }}</span>
          </template>
          <template #cell-status="{ row }">
            <StatusBadge :tone="statusMeta(row).tone" :label="statusMeta(row).label" />
          </template>
          <template #cell-attendance="{ row }">
            <span class="text-xs">{{ attendanceText(row) }}</span>
          </template>
          <template #cell-actions="{ row }">
            <SessionActions
              :session="row"
              @reschedule="openReschedule(row)"
              @cancel="openCancel(row)"
              @restore="openRestore(row)"
            />
          </template>
        </DataTable>
      </div>

      <!-- มือถือ: card -->
      <ul class="flex flex-col gap-3 md:hidden">
        <li v-if="!shown.length" class="py-8 text-center text-sm text-muted">
          ไม่มีคาบในตัวกรองนี้
        </li>
        <li v-for="s in shown" :key="s.sessionId">
          <BaseCard class="flex flex-col gap-2">
            <div class="flex items-start justify-between gap-2">
              <div class="min-w-0">
                <p class="font-semibold">
                  คาบที่ {{ s.sessionNo }} · {{ formatThaiDate(s.sessionDate) }}
                </p>
                <p class="text-sm text-muted">
                  {{ s.startTime }}–{{ s.endTime }} · ห้อง {{ s.roomCode }}
                </p>
              </div>
              <StatusBadge :tone="statusMeta(s).tone" :label="statusMeta(s).label" />
            </div>
            <SessionBadges :session="s" />
            <p v-if="s.sessionStatus !== 'scheduled'" class="text-xs text-muted">
              {{ attendanceText(s) }}
            </p>
            <SessionActions
              :session="s"
              class="-mx-2.5 border-t border-line pt-1"
              @reschedule="openReschedule(s)"
              @cancel="openCancel(s)"
              @restore="openRestore(s)"
            />
          </BaseCard>
        </li>
      </ul>
    </template>

    <SessionEditDialog
      v-model:open="editOpen"
      :mode="editMode"
      :section-id="sectionId"
      :session="target"
      :section="section"
      :room-options="roomOptions"
      :semester="semester"
      :makeup-candidates="makeupCandidates"
      @saved="onSaved"
    />
    <SessionCancelDialog
      v-model:open="cancelOpen"
      :session="target"
      :section="section"
      :room-options="roomOptions"
      :semester="semester"
      @cancelled="onCancelled"
    />
    <BaseDialog
      v-model:open="restoreOpen"
      :title="`คืนคาบที่ ${target?.sessionNo ?? ''}?`"
      :description="
        target
          ? `${formatThaiDate(target.sessionDate)} · ${target.startTime}–${target.endTime} · ห้อง ${target.roomCode} — คาบจะกลับมาเปิดเช็คชื่อตามเวลาเดิม`
          : ''
      "
    >
      <TextField
        v-model="restoreReason"
        label="เหตุผล"
        multiline
        required
        :maxlength="SESSION_REASON_MAX"
        :error="restoreError"
      />
      <template #actions>
        <BaseButton variant="secondary" :disabled="restoring" @click="restoreOpen = false"
          >ไม่คืน</BaseButton
        >
        <BaseButton :loading="restoring" @click="doRestore">คืนคาบ</BaseButton>
      </template>
    </BaseDialog>
  </section>
</template>
