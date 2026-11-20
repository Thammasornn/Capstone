<script setup>
defineProps({
  title: { type: String, default: 'ยังไม่มีข้อมูล' },
  description: { type: String, default: '' },
})
</script>

<template>
  <div class="flex flex-col items-center gap-2 px-4 py-10 text-center">
    <p class="text-base font-semibold text-ink">{{ title }}</p>
    <p v-if="description" class="max-w-md text-sm text-muted">{{ description }}</p>
    <slot />
  </div>
</template>
