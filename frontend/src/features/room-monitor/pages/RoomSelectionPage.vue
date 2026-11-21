<script setup>
/** B1 เลือกห้องเรียน — อาจารย์เห็นเฉพาะห้องที่มีกลุ่มเรียนของตน · รีเฟรชเองทุก 30 วินาที */
import { ref } from 'vue'
import EmptyState from '@/shared/components/EmptyState.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import RefreshButton from '@/shared/components/RefreshButton.vue'
import { useAsyncState } from '@/shared/composables/useAsyncState'
import { usePolling } from '@/shared/composables/usePolling'
import { getRooms } from '../api'
import RoomCard from '../components/RoomCard.vue'

const updatedAt = ref(/** @type {Date | null} */ (null))
const {
  data: rooms,
  loading,
  error,
  run,
} = useAsyncState(async () => {
  const result = await getRooms()
  updatedAt.value = new Date()
  return result
})
usePolling(run, 30_000)
</script>

<template>
  <section>
    <div class="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="text-[28px] font-bold">เลือกห้องเรียน</h1>
        <p class="mt-1 text-sm text-muted">
          ห้องที่มีกลุ่มเรียนของคุณ พร้อมสถานะกล้องและคาบที่กำลังเรียน
        </p>
      </div>
      <RefreshButton :loading="loading" :updated-at="updatedAt" @refresh="run" />
    </div>

    <ErrorState v-if="error && !rooms" :message="error.message" @retry="run" />
    <LoadingSkeleton v-else-if="!rooms" :lines="4" />
    <EmptyState
      v-else-if="!rooms.length"
      title="ยังไม่มีห้องเรียน"
      description="ห้องจะแสดงเมื่อคุณมีกลุ่มเรียนที่สอนในห้องนั้น"
    />
    <div v-else class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
      <RoomCard v-for="room in rooms" :key="room.roomId" :room="room" />
    </div>
  </section>
</template>
