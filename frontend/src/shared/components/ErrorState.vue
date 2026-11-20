<script setup>
import BaseButton from './BaseButton.vue'

/** แสดงข้อผิดพลาดจาก ApiError (message ภาษาไทยจาก Backend) + ปุ่มลองใหม่ */
defineProps({
  message: { type: String, required: true },
  retryable: { type: Boolean, default: true },
})
defineEmits(['retry'])
</script>

<template>
  <div role="alert" class="flex flex-col items-center gap-3 px-4 py-10 text-center">
    <p class="text-sm font-medium text-danger">{{ message }}</p>
    <BaseButton v-if="retryable" variant="secondary" @click="$emit('retry')">ลองใหม่</BaseButton>
  </div>
</template>
