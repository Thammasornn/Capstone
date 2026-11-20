<script setup>
defineProps({
  lines: { type: Number, default: 3 },
  label: { type: String, default: 'กำลังโหลด' },
})
</script>

<template>
  <div role="status" :aria-label="label" class="animate-pulse space-y-3">
    <div v-for="n in lines" :key="n" class="h-4 rounded bg-line" :class="n === lines && 'w-2/3'" />
    <span class="sr-only">{{ label }}…</span>
  </div>
</template>
