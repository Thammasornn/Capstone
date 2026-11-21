<script setup>
/** ตัวเลขใหญ่ + คำอธิบาย (Figma Student Home / Dashboard) · tone ใช้กับตัวเลข */
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], required: true },
  hint: { type: String, default: '' },
  tone: {
    type: String,
    default: 'ink',
    validator: (v) => ['ink', 'primary', 'success', 'warning', 'danger', 'muted'].includes(v),
  },
})

const tones = {
  ink: 'text-ink',
  primary: 'text-primary',
  success: 'text-success',
  warning: 'text-warning',
  danger: 'text-danger',
  muted: 'text-muted',
}
</script>

<template>
  <div class="rounded-card border border-line bg-surface p-5">
    <p class="text-[13px] font-medium text-muted">{{ label }}</p>
    <p class="mt-2 text-[28px] leading-tight font-bold" :class="tones[tone]">{{ value }}</p>
    <p v-if="hint" class="mt-1 text-xs text-muted">{{ hint }}</p>
    <slot />
  </div>
</template>
