<script setup>
/**
 * Room Live (B2–B4 + 5.7–5.8 + B8)
 *  - ข้อมูลห้อง + คาบปัจจุบันจาก B4 (รีเฟรชทุก 30 วินาที และเมื่อมี event session)
 *  - SSE (B3): camera → สถานะกล้อง · faces → จำนวนใบหน้า · check_in → อัปเดตรายชื่อทันที
 *  - SSE ยังไม่ต่อ / หลุด (เช่น proxy ข้างหน้า buffer) → โหลดรายชื่อใหม่ทุก 15 วินาทีแทน ข้อมูลยังเดิน
 *  - เช็คชื่อด้วยมือ (B8) ผ่าน ManualAttendanceDialog
 *  - ไม่มีคาบ → "เริ่มคาบตอนนี้" (เลือกกลุ่มเรียน) · มีคาบ → "จบคาบ" (คนที่ยังไม่มีผล = ขาด)
 *  - มีคาบ → เมนู "จัดการคาบ" (เริ่มนับเวลาใหม่ / ยืดเวลาจบ / ปรับช่วงเช็คชื่อ + Preview & Confirm)
 *    route นี้เข้าได้เฉพาะ staff · currentSession ของ B4 คือคาบที่กำลังเรียน
 *  - มีคาบ → "นำเข้าผลเช็คชื่อ" (AttendanceImportWizard) เช่น กล้องเสียแล้วใช้ใบเซ็นชื่อ
 *  - ?manage=1 (ลิงก์จากหน้าคาบเรียน) → เปิดเมนู "จัดการคาบ" ไว้ให้
 *  - SSE session sessionStatus = cancelled ของคาบที่แสดงอยู่ → "คาบนี้ถูกยกเลิก" แทนสถานะกำลังเรียน
 */
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseCard from '@/shared/components/BaseCard.vue'
import BaseDialog from '@/shared/components/BaseDialog.vue'
import EmptyState from '@/shared/components/EmptyState.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import { useAsyncState } from '@/shared/composables/useAsyncState'
import { usePolling } from '@/shared/composables/usePolling'
import { useToast } from '@/shared/composables/useToast'
import { formatTime } from '@/shared/utils/datetime'
import { describeAdjustResult } from '@/entities/attendance/adjust-summary'
import ManualAttendanceDialog from '@/entities/attendance/components/ManualAttendanceDialog.vue'
import SessionAdjustMenu from '@/entities/attendance/components/SessionAdjustMenu.vue'
import AttendanceImportWizard from '@/entities/attendance/components/AttendanceImportWizard.vue'
import { describeImportResult } from '@/entities/attendance/import'
import { endSessionNow, getLiveRoster, getRoom, getRooms } from '../api'
import { useRoomEvents } from '../composables/useRoomEvents'
import CameraStatusBadge from '../components/CameraStatusBadge.vue'
import LiveCameraPlayer from '../components/LiveCameraPlayer.vue'
import LiveStudentList from '../components/LiveStudentList.vue'
import PresenceCounters from '../components/PresenceCounters.vue'
import RoomSwitcher from '../components/RoomSwitcher.vue'
import StartSessionDialog from '../components/StartSessionDialog.vue'

const props = defineProps({ roomId: { type: String, required: true } })
const ROSTER_FALLBACK_MS = 15_000
const toast = useToast()
const route = useRoute()
const openManageMenu = computed(() => route.query.manage === '1')

const room = useAsyncState(() => getRoom(props.roomId), { immediate: false })
const rooms = useAsyncState(getRooms)
const roster = ref(/** @type {any[]} */ ([]))
const sessionDate = ref('')
const cameraStatus = ref(/** @type {string | null} */ (null))
const facesDetected = ref(/** @type {number | null} */ (null))

const session = computed(() => room.data.value?.currentSession ?? null)

async function loadRoom() {
  const r = await room.run()
  if (r) cameraStatus.value = r.cameraStatus
}
/** @param {{ silent?: boolean }} [options] silent = โหลดเบื้องหลัง (poll) ไม่แสดง toast เมื่อพลาด */
async function loadRoster({ silent = false } = {}) {
  if (!session.value) {
    roster.value = []
    return
  }
  try {
    const result = await getLiveRoster(session.value.sessionId)
    roster.value = result.items
    sessionDate.value = result.sessionDate
  } catch (e) {
    if (!silent) toast.show(e?.message ?? 'โหลดรายชื่อไม่สำเร็จ', { tone: 'danger' })
  }
}

watch(
  () => props.roomId,
  async () => {
    facesDetected.value = null
    await loadRoom()
    await loadRoster()
  },
  { immediate: true },
)
watch(
  () => session.value?.sessionId,
  () => loadRoster(),
)
usePolling(loadRoom, 30_000)

const { status: streamStatus } = useRoomEvents(() => props.roomId, {
  camera: (data) => (cameraStatus.value = data.cameraStatus),
  faces: (data) => (facesDetected.value = data.detected),
  check_in: (data) => {
    if (data.sessionId !== session.value?.sessionId) return
    roster.value = roster.value.map((r) =>
      r.studentId === data.studentId
        ? {
            ...r,
            status: data.status,
            checkInTime: data.checkInTime,
            source: data.source,
            importedFrom: data.importedFrom ?? (data.source === 'import' ? r.importedFrom : null),
          }
        : r,
    )
    if (data.source === 'camera' && data.status !== 'absent') {
      const who = roster.value.find((r) => r.studentId === data.studentId)
      toast.show(`${who?.studentName ?? data.studentCode} เข้าเรียนแล้ว`, { tone: 'success' })
    }
  },
  session: (data) => {
    // คาบที่แสดงอยู่ถูกยกเลิก (จากหน้าคาบเรียน) → แจ้งแทนสถานะกำลังเรียน
    if (data?.sessionStatus === 'cancelled' && data.sessionId === session.value?.sessionId) {
      cancelledSession.value = { ...session.value }
    }
    loadRoom()
  },
})

/** คาบที่เพิ่งถูกยกเลิกระหว่างเรียน (แสดงจนกว่าจะมีคาบใหม่ในห้อง) */
const cancelledSession = ref(/** @type {any} */ (null))
watch(
  () => session.value?.sessionId,
  (id) => {
    if (id) cancelledSession.value = null
  },
)

// SSE ยังไม่ open = ไม่ได้รับ check_in → โหลดรายชื่อเองทุก 15 วินาที (SSE ใช้ได้แล้วจะหยุดเอง)
usePolling(async () => {
  if (streamStatus.value !== 'open') await loadRoster({ silent: true })
}, ROSTER_FALLBACK_MS)

const counts = computed(() => {
  const c = { on_time: 0, late: 0, absent: 0 }
  for (const r of roster.value) c[r.status] = (c[r.status] ?? 0) + 1
  return c
})

const editing = ref(/** @type {any} */ (null))
const dialogOpen = ref(false)
function openEdit(row) {
  editing.value = row
  dialogOpen.value = true
}
function onSaved(result) {
  roster.value = roster.value.map((r) =>
    r.studentId === result.studentId
      ? { ...r, status: result.status, checkInTime: result.checkInTime, source: result.source }
      : r,
  )
  toast.show('บันทึกการเช็คชื่อแล้ว', { tone: 'success' })
}

// ---------- เริ่ม / จบคาบ ----------
const startOpen = ref(false)
const endOpen = ref(false)
const ending = ref(false)

/** @param {import('../api').ClassSessionInfo} info */
async function onStarted(info) {
  toast.show(
    `เริ่มคาบแล้ว · ตรงเวลาถึง ${formatTime(info.onTimeUntil)} · สายถึง ${formatTime(info.lateUntil)}`,
    { tone: 'success' },
  )
  await loadRoom()
}

async function endSession() {
  if (!session.value) return
  ending.value = true
  try {
    await endSessionNow(session.value.sessionId)
    endOpen.value = false
    toast.show('จบคาบแล้ว · คนที่ยังไม่ได้เช็คชื่อถูกบันทึกว่าขาด', { tone: 'success' })
    await loadRoom()
  } catch (e) {
    toast.show(e?.message ?? 'จบคาบไม่สำเร็จ', { tone: 'danger' })
  } finally {
    ending.value = false
  }
}

// ---------- จัดการคาบ (ปรับเวลา) ----------
const sessionLabel = computed(() =>
  session.value
    ? `${session.value.courseCode}-${session.value.sectionCode} ${session.value.courseName}`
    : '',
)
async function onAdjusted({ result, mode }) {
  toast.show(describeAdjustResult(result, mode), { tone: 'success' })
  // SSE ส่ง session / check_in ให้อยู่แล้ว · โหลดเองด้วยเผื่อ SSE หลุด (มี poll 15 วินาทีสำรอง)
  await loadRoom()
  await loadRoster({ silent: true })
}

// ---------- นำเข้าผลเช็คชื่อ ----------
const importOpen = ref(false)
async function onImported(result) {
  toast.show(describeImportResult(result), { tone: 'success' })
  await loadRoster({ silent: true })
}

const liveLabel = computed(
  () =>
    ({
      open: { tone: 'success', label: 'เชื่อมต่อสด' },
      reconnecting: { tone: 'warning', label: 'กำลังเชื่อมต่อใหม่' },
      failed: { tone: 'danger', label: 'ขาดการเชื่อมต่อ' },
    })[streamStatus.value] ?? { tone: 'neutral', label: 'กำลังเชื่อมต่อ' },
)
</script>

<template>
  <section class="flex flex-col gap-6">
    <ErrorState
      v-if="room.error.value && !room.data.value"
      :message="room.error.value.message"
      @retry="loadRoom"
    />
    <LoadingSkeleton v-else-if="!room.data.value" :lines="6" />

    <template v-else>
      <div class="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div class="flex flex-wrap items-center gap-2">
            <h1 class="text-[28px] font-bold">ห้อง {{ room.data.value.roomCode }}</h1>
            <CameraStatusBadge :status="cameraStatus" />
            <StatusBadge :tone="liveLabel.tone" :label="liveLabel.label" dot />
          </div>
          <p v-if="session" class="mt-1 text-sm text-muted">
            {{ session.courseCode }} {{ session.courseName }} · กลุ่ม {{ session.sectionCode }} ·
            {{ formatTime(session.startTime) }}–{{ formatTime(session.endTime) }}
          </p>
          <p
            v-else-if="cancelledSession"
            role="alert"
            class="mt-2 rounded-field bg-danger-bg px-3 py-2 text-sm font-semibold text-danger"
          >
            คาบนี้ถูกยกเลิก — {{ cancelledSession.courseCode }}-{{ cancelledSession.sectionCode }}
            {{ formatTime(cancelledSession.startTime) }}–{{ formatTime(cancelledSession.endTime) }}
            · กล้องหยุดเช็คชื่อแล้ว ผลที่บันทึกไว้ถูกเก็บแต่ไม่นับในสถิติ
          </p>
          <p v-else class="mt-1 text-sm text-muted">ยังไม่มีคาบเรียนในห้องนี้ตอนนี้</p>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <SessionAdjustMenu
            v-if="session"
            :session-id="session.sessionId"
            :times="session"
            :label="sessionLabel"
            :default-open="openManageMenu"
            @applied="onAdjusted"
          />
          <BaseButton v-if="session" variant="secondary" @click="importOpen = true">
            นำเข้าผลเช็คชื่อ
          </BaseButton>
          <BaseButton v-if="session" variant="secondary" @click="endOpen = true">จบคาบ</BaseButton>
          <BaseButton v-else @click="startOpen = true">เริ่มคาบตอนนี้</BaseButton>
          <RoomSwitcher v-if="rooms.data.value" :rooms="rooms.data.value" :room-id="roomId" />
        </div>
      </div>

      <div class="grid gap-6 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <div class="flex flex-col gap-4">
          <LiveCameraPlayer :room-id="roomId" :camera-status="cameraStatus" />
          <PresenceCounters
            v-if="session"
            :on-time="counts.on_time"
            :late="counts.late"
            :not-yet="counts.absent"
            :total="roster.length"
            :faces-detected="facesDetected"
          />
        </div>

        <BaseCard>
          <div class="mb-3 flex items-center justify-between gap-2">
            <h2 class="text-base font-bold">รายชื่อนักศึกษา</h2>
            <RouterLink
              v-if="session"
              :to="{ name: 'session-report', params: { sessionId: session.sessionId } }"
              class="text-xs font-semibold text-primary hover:underline"
            >
              ดูรายงานคาบนี้
            </RouterLink>
          </div>
          <LiveStudentList v-if="session" :rows="roster" @edit="openEdit" />
          <EmptyState
            v-else
            title="ไม่มีคาบเรียนตอนนี้"
            description='รายชื่อจะแสดงเมื่อถึงเวลาเรียนของกลุ่มที่ใช้ห้องนี้ หรือกด "เริ่มคาบตอนนี้" เพื่อเปิดเช็คชื่อทันที'
          />
        </BaseCard>
      </div>

      <StartSessionDialog
        v-model:open="startOpen"
        :room-id="roomId"
        :room-code="room.data.value.roomCode"
        @started="onStarted"
      />
      <BaseDialog
        v-model:open="endOpen"
        title="จบคาบตอนนี้?"
        :description="
          session
            ? `${session.courseCode}-${session.sectionCode} · นักศึกษาที่ยังไม่ได้เช็คชื่อจะถูกบันทึกว่าขาด (แก้ทีหลังได้ด้วยการเช็คชื่อด้วยมือ)`
            : ''
        "
      >
        <template #actions>
          <BaseButton variant="secondary" @click="endOpen = false">ยกเลิก</BaseButton>
          <BaseButton variant="danger" :loading="ending" @click="endSession">จบคาบ</BaseButton>
        </template>
      </BaseDialog>

      <AttendanceImportWizard
        v-if="session"
        v-model:open="importOpen"
        :session-id="session.sessionId"
        :label="sessionLabel"
        @imported="onImported"
      />

      <ManualAttendanceDialog
        v-if="session"
        v-model:open="dialogOpen"
        :session-id="session.sessionId"
        :session-date="sessionDate"
        :student="editing"
        @saved="onSaved"
      />
    </template>
  </section>
</template>
