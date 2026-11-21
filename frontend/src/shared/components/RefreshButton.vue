<script setup>
/** ปุ่มรีเฟรช — API ส่วนใหญ่ไม่ใช่ real-time · updatedAt = เวลาที่โหลดล่าสุด (Date) */
import { computed } from 'vue'
import BaseButton from './BaseButton.vue'

const props = defineProps({
  loading: { type: Boolean, default: false },
  updatedAt: { type: Date, default: null },
})
defineEmits(['refresh'])

const updatedText = computed(() =>
  props.updatedAt
    ? `อัปเดต ${props.updatedAt.toLocaleTimeString('th-TH', { hour: '2-digit', minute: '2-digit' })}`
    : '',
)
</script>

<template>
  <div class="flex items-center gap-3">
    <span v-if="updatedText" class="text-xs text-muted">{{ updatedText }}</span>
    <BaseButton variant="secondary" :loading="loading" @click="$emit('refresh')">รีเฟรช</BaseButton>
  </div>
</template>
