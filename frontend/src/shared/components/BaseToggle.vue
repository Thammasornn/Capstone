<script setup>
/** สวิตช์เปิด/ปิด (role=switch) · label จำเป็นเพื่อ screen reader (ซ่อนได้ด้วย hideLabel) */
defineProps({
  label: { type: String, required: true },
  disabled: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
  hideLabel: { type: Boolean, default: false },
})
const model = defineModel({ type: Boolean, default: false })
</script>

<template>
  <button
    type="button"
    role="switch"
    :aria-checked="model"
    :aria-busy="loading || undefined"
    :disabled="disabled || loading"
    class="inline-flex items-center gap-2 disabled:cursor-not-allowed disabled:opacity-60"
    @click="model = !model"
  >
    <span
      class="relative inline-flex h-6 w-11 shrink-0 rounded-full transition-colors"
      :class="model ? 'bg-primary' : 'bg-line'"
      aria-hidden="true"
    >
      <span
        class="absolute top-0.5 left-0.5 size-5 rounded-full bg-surface shadow transition-transform"
        :class="model && 'translate-x-5'"
      />
    </span>
    <span class="text-sm" :class="hideLabel && 'sr-only'">{{ label }}</span>
  </button>
</template>
