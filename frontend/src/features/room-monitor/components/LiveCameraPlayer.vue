<script setup>
/** ภาพสดของห้อง (B2 → WHEP) · กล้อง no_signal หรือต่อ MediaMTX ไม่ได้ → แสดงข้อความแทนภาพ */
import { onMounted, ref, watch } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import { useLiveStream } from '../composables/useLiveStream'

const props = defineProps({
  roomId: { type: String, required: true },
  cameraStatus: { type: String, default: null },
})

const video = ref(/** @type {HTMLVideoElement | null} */ (null))
const { stream, status, message, start, stop } = useLiveStream()

function connect() {
  if (props.cameraStatus === 'live') start(props.roomId)
  else stop()
}
onMounted(connect)
watch(() => [props.roomId, props.cameraStatus], connect)
watch(stream, (s) => {
  if (video.value) video.value.srcObject = s
})
</script>

<template>
  <div class="relative aspect-video overflow-hidden rounded-card bg-dark">
    <video
      v-show="status === 'playing'"
      ref="video"
      class="size-full object-contain"
      autoplay
      muted
      playsinline
    />
    <div
      v-if="status !== 'playing'"
      class="absolute inset-0 flex flex-col items-center justify-center gap-3 p-6 text-center text-on-dark"
    >
      <template v-if="cameraStatus !== 'live'">
        <p class="font-semibold">{{ cameraStatus ? 'กล้องไม่มีสัญญาณ' : 'ห้องนี้ไม่มีกล้อง' }}</p>
        <p class="text-xs text-on-dark/70">ระบบจะแสดงภาพเมื่อกล้องกลับมาออนไลน์</p>
      </template>
      <template v-else-if="status === 'connecting'">
        <span
          class="size-6 animate-spin rounded-full border-2 border-on-dark border-r-transparent"
        />
        <p class="text-sm">กำลังเชื่อมต่อกล้อง…</p>
      </template>
      <template v-else>
        <p class="font-semibold">เปิดภาพสดไม่ได้</p>
        <p class="text-xs text-on-dark/70">{{ message }}</p>
        <BaseButton variant="secondary" @click="connect">ลองใหม่</BaseButton>
      </template>
    </div>
  </div>
</template>
