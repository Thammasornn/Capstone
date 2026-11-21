<script setup>
/**
 * ถ่ายรูปด้วยกล้องของเครื่องตามมุม (หน้าตรง → ซ้าย → ขวา) · ต้องใช้ HTTPS หรือ localhost
 * emit captured(File, label)
 */
import { computed, onBeforeUnmount, ref } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import { CAPTURE_ANGLES } from '../constants'
import FaceGuideOverlay from './FaceGuideOverlay.vue'

const props = defineProps({ taken: { type: Number, default: 0 } })
const emit = defineEmits(['captured'])

const video = ref(/** @type {HTMLVideoElement | null} */ (null))
const active = ref(false)
const error = ref('')
/** @type {MediaStream | null} */
let stream = null

const angle = computed(() => CAPTURE_ANGLES[props.taken % CAPTURE_ANGLES.length])

async function startCamera() {
  error.value = ''
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'user', width: 1280, height: 960 },
      audio: false,
    })
    active.value = true
    await Promise.resolve()
    if (video.value) video.value.srcObject = stream
  } catch {
    error.value =
      'เปิดกล้องไม่ได้ — อนุญาตการใช้กล้องในเบราว์เซอร์ หรือใช้ “เลือกรูปจากเครื่อง” แทน'
  }
}

function stopCamera() {
  stream?.getTracks().forEach((t) => t.stop())
  stream = null
  active.value = false
}

function capture() {
  const v = video.value
  if (!v || !v.videoWidth) return
  const canvas = document.createElement('canvas')
  canvas.width = v.videoWidth
  canvas.height = v.videoHeight
  canvas.getContext('2d')?.drawImage(v, 0, 0)
  canvas.toBlob(
    (blob) => {
      if (!blob) return
      const file = new File([blob], `face-${angle.value.key}-${Date.now()}.jpg`, {
        type: 'image/jpeg',
      })
      emit('captured', file, angle.value.label)
    },
    'image/jpeg',
    0.92,
  )
}

onBeforeUnmount(stopCamera)
</script>

<template>
  <div class="flex flex-col gap-3">
    <div
      v-if="active"
      class="relative mx-auto aspect-[4/3] w-full max-w-md overflow-hidden rounded-card bg-dark"
    >
      <video ref="video" autoplay playsinline muted class="size-full -scale-x-100 object-cover" />
      <FaceGuideOverlay :label="angle.label" />
    </div>
    <div class="flex flex-wrap justify-center gap-2">
      <BaseButton v-if="!active" variant="secondary" @click="startCamera">เปิดกล้อง</BaseButton>
      <template v-else>
        <BaseButton @click="capture">ถ่ายรูป ({{ angle.label }})</BaseButton>
        <BaseButton variant="secondary" @click="stopCamera">ปิดกล้อง</BaseButton>
      </template>
    </div>
    <p v-if="error" class="text-center text-sm text-danger">{{ error }}</p>
  </div>
</template>
