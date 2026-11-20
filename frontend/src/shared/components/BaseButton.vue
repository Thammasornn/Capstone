<script setup>
/** ปุ่มกลาง — ขนาด/สีตาม Figma (rounded 10, SemiBold 14) */
defineProps({
  variant: {
    type: String,
    default: 'primary',
    validator: (v) => ['primary', 'secondary', 'danger', 'ghost'].includes(v),
  },
  type: {
    type: String,
    default: 'button',
    validator: (v) => ['button', 'submit', 'reset'].includes(v),
  },
  loading: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  block: { type: Boolean, default: false },
})

const variants = {
  primary: 'bg-primary text-on-dark hover:bg-primary/90',
  secondary: 'bg-surface text-ink border border-line hover:bg-page',
  danger: 'bg-danger text-on-dark hover:bg-danger/90',
  ghost: 'bg-transparent text-primary hover:bg-primary/5',
}
</script>

<template>
  <button
    :type="type"
    :disabled="disabled || loading"
    :aria-busy="loading || undefined"
    class="inline-flex min-h-11 items-center justify-center gap-2 rounded-button px-4 py-3 text-sm font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-60"
    :class="[variants[variant], block && 'w-full']"
  >
    <span
      v-if="loading"
      class="size-4 animate-spin rounded-full border-2 border-current border-r-transparent"
      aria-hidden="true"
    />
    <slot />
  </button>
</template>
