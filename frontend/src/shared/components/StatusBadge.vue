<script setup>
/**
 * ป้ายสถานะแบบ pill — รับ tone + label (shared ไม่รู้ความหมายของสถานะ)
 * ข้อความต้องมีเสมอ เพื่อไม่สื่อด้วยสีอย่างเดียว
 */
defineProps({
  tone: {
    type: String,
    default: 'neutral',
    validator: (v) => ['success', 'warning', 'danger', 'neutral', 'info'].includes(v),
  },
  label: { type: String, required: true },
  dot: { type: Boolean, default: false },
})

const tones = {
  success: 'bg-success-bg text-success',
  warning: 'bg-warning-bg text-warning',
  danger: 'bg-danger-bg text-danger',
  neutral: 'bg-page text-muted border border-line',
  info: 'bg-primary/10 text-primary',
}
</script>

<template>
  <span
    class="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold whitespace-nowrap"
    :class="tones[tone]"
  >
    <span v-if="dot" class="size-1.5 rounded-full bg-current" aria-hidden="true" />
    {{ label }}
  </span>
</template>
