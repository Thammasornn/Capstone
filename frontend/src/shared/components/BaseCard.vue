<script setup>
defineProps({
  as: { type: String, default: 'section' },
  padded: { type: Boolean, default: true },
})
</script>

<template>
  <component
    :is="as"
    class="rounded-card border border-line bg-surface"
    :class="padded && 'p-5 sm:p-6'"
  >
    <slot />
  </component>
</template>
