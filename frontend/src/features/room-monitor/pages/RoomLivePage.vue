<script setup>
/**
 * Room Live (B2–B4 + 5.7–5.8 + B8)
 *  - ข้อมูลห้อง + คาบปัจจุบันจาก B4 (รีเฟรชทุก 30 วินาที และเมื่อมี event session)
 *  - SSE (B3): camera → สถานะกล้อง · faces → จำนวนใบหน้า · check_in → อัปเดตรายชื่อทันที
 *  - เช็คชื่อด้วยมือ (B8) ผ่าน ManualAttendanceDialog
 */
import { computed, ref, watch } from 'vue'
import BaseCard from '@/shared/components/BaseCard.vue'
import EmptyState from '@/shared/components/EmptyState.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import { useAsyncState } from '@/shared/composables/useAsyncState'
import { usePolling } from '@/shared/composables/usePolling'
import { useToast } from '@/shared/composables/useToast'
import { formatTime } from '@/shared/utils/datetime'
import ManualAttendanceDialog from '@/entities/attendance/components/ManualAttendanceDialog.vue'
import { getLiveRoster, getRoom, getRooms } from '../api'
import { useRoomEvents } from '../composables/useRoomEvents'
import CameraStatusBadge from '../components/CameraStatusBadge.vue'
import LiveCameraPlayer from '../components/LiveCameraPlayer.vue'
import LiveStudentList from '../components/LiveStudentList.vue'
import PresenceCounters from '../components/PresenceCounters.vue'
import RoomSwitcher from '../components/RoomSwitcher.vue'

const props = defineProps({ roomId: { type: String, required: true } })
const toast = useToast()

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
async function loadRoster() {
  if (!session.value) {
    roster.value = []
    return
  }
  try {
    const result = await getLiveRoster(session.value.sessionId)
    roster.value = result.items
    sessionDate.value = result.sessionDate
  } catch (e) {
    toast.show(e?.message ?? 'โหลดรายชื่อไม่สำเร็จ', { tone: 'danger' })
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
watch(() => session.value?.sessionId, loadRoster)
usePolling(loadRoom, 30_000)

const { status: streamStatus } = useRoomEvents(() => props.roomId, {
  camera: (data) => (cameraStatus.value = data.cameraStatus),
  faces: (data) => (facesDetected.value = data.detected),
  check_in: (data) => {
    if (data.sessionId !== session.value?.sessionId) return
    roster.value = roster.value.map((r) =>
      r.studentId === data.studentId
        ? { ...r, status: data.status, checkInTime: data.checkInTime, source: data.source }
        : r,
    )
    if (data.source === 'camera' && data.status !== 'absent') {
      const who = roster.value.find((r) => r.studentId === data.studentId)
      toast.show(`${who?.studentName ?? data.studentCode} เข้าเรียนแล้ว`, { tone: 'success' })
    }
  },
  session: () => loadRoom(),
})

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
          <p v-else class="mt-1 text-sm text-muted">ยังไม่มีคาบเรียนในห้องนี้ตอนนี้</p>
        </div>
        <RoomSwitcher v-if="rooms.data.value" :rooms="rooms.data.value" :room-id="roomId" />
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
            description="รายชื่อจะแสดงเมื่อถึงเวลาเรียนของกลุ่มที่ใช้ห้องนี้"
          />
        </BaseCard>
      </div>

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
