<script setup>
/**
 * เลือกรูป / ถ่ายด้วยแอปกล้องของมือถือ
 *  - "ถ่ายรูปด้วยกล้องมือถือ" (เฉพาะจอสัมผัส): <input capture="user"> เปิดกล้องหน้าของเครื่องได้แม้เว็บเป็น HTTP
 *  - "เลือกรูปจากเครื่อง": หลายรูปพร้อมกัน
 * รูปทุกแบบถูกย่อ + แปลงเป็น JPEG ใน useFaceRegistration (prepare-image.js) จึงรับ image/* ได้ (รวม HEIC บน iPhone)
 * emit picked(File[], fromCamera: boolean)
 */
import { ref } from 'vue'
import { isTouchDevice } from '../prepare-image'

defineProps({ disabled: { type: Boolean, default: false } })
const emit = defineEmits(['picked'])
const fileInput = ref(/** @type {HTMLInputElement | null} */ (null))
const cameraInput = ref(/** @type {HTMLInputElement | null} */ (null))
const showCamera = isTouchDevice()

function onChange(event, fromCamera) {
  const files = Array.from(event.target.files ?? [])
  if (files.length) emit('picked', files, fromCamera)
  event.target.value = ''
}
</script>

<template>
  <div class="flex flex-wrap justify-center gap-2">
    <input
      ref="cameraInput"
      type="file"
      accept="image/*"
      capture="user"
      class="sr-only"
      tabindex="-1"
      @change="onChange($event, true)"
    />
    <input
      ref="fileInput"
      type="file"
      accept="image/*"
      multiple
      class="sr-only"
      tabindex="-1"
      @change="onChange($event, false)"
    />
    <button
      v-if="showCamera"
      type="button"
      :disabled="disabled"
      class="inline-flex min-h-11 items-center rounded-button bg-primary px-4 text-sm font-semibold text-on-dark hover:bg-primary/90 disabled:opacity-60"
      @click="cameraInput?.click()"
    >
      ถ่ายรูปด้วยกล้องมือถือ
    </button>
    <button
      type="button"
      :disabled="disabled"
      class="inline-flex min-h-11 items-center rounded-button border border-dashed border-primary/50 px-4 text-sm font-semibold text-primary hover:bg-primary/5 disabled:opacity-60"
      @click="fileInput?.click()"
    >
      เลือกรูปจากเครื่อง
    </button>
  </div>
</template>
