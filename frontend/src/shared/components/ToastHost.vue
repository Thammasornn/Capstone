<script setup>
import { useToast } from '../composables/useToast'

const { toasts, dismiss } = useToast()

const tones = {
  success: 'border-success/30 bg-success-bg text-success',
  danger: 'border-danger/30 bg-danger-bg text-danger',
  info: 'border-line bg-surface text-ink',
}
</script>

<template>
  <div
    aria-live="polite"
    class="pointer-events-none fixed inset-x-0 bottom-4 z-50 flex flex-col items-center gap-2 px-4"
  >
    <div
      v-for="toast in toasts"
      :key="toast.id"
      class="pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-button border px-4 py-3 text-sm shadow-card"
      :class="tones[toast.tone]"
    >
      <p class="flex-1">{{ toast.message }}</p>
      <button type="button" class="text-xs font-semibold" @click="dismiss(toast.id)">ปิด</button>
    </div>
  </div>
</template>
