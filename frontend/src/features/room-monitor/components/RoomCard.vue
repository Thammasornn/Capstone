<script setup>
/** การ์ดห้องในหน้าเลือกห้อง (B1) — คลิกทั้งการ์ดเพื่อเข้า Room Live */
import { formatTime } from '@/shared/utils/datetime'
import CameraStatusBadge from './CameraStatusBadge.vue'

defineProps({ room: { type: Object, required: true } })
</script>

<template>
  <RouterLink
    :to="{ name: 'room-live', params: { roomId: room.roomId } }"
    class="flex flex-col gap-4 rounded-card border border-line bg-surface p-5 transition-shadow hover:shadow-card focus-visible:shadow-card"
  >
    <div class="flex items-start justify-between gap-3">
      <div>
        <p class="text-xs text-muted">ห้อง</p>
        <p class="text-2xl font-bold">{{ room.roomCode }}</p>
        <p class="text-xs text-muted">{{ room.cameraCode ?? 'ไม่มีกล้อง' }}</p>
      </div>
      <CameraStatusBadge :status="room.cameraStatus" />
    </div>

    <div v-if="room.currentSession" class="rounded-field bg-primary/5 p-3">
      <p class="text-xs font-semibold text-primary">กำลังเรียน</p>
      <p class="mt-1 text-sm font-semibold">
        {{ room.currentSession.courseCode }} กลุ่ม {{ room.currentSession.sectionCode }}
      </p>
      <p class="truncate text-xs text-muted">{{ room.currentSession.courseName }}</p>
      <div class="mt-2 flex items-center justify-between text-xs">
        <span>
          {{ formatTime(room.currentSession.startTime) }}–{{
            formatTime(room.currentSession.endTime)
          }}
        </span>
        <span class="font-semibold">
          เช็คชื่อแล้ว {{ room.currentSession.checkedIn }}/{{ room.currentSession.totalStudents }}
        </span>
      </div>
    </div>
    <p v-else class="rounded-field bg-page p-3 text-sm text-muted">ยังไม่มีคาบเรียนตอนนี้</p>
  </RouterLink>
</template>
