<script setup>
defineProps({
  photos: { type: Array, required: true },
  disabled: { type: Boolean, default: false },
})
defineEmits(['remove'])
</script>

<template>
  <ul class="grid grid-cols-3 gap-3 sm:grid-cols-5">
    <li v-for="(p, i) in photos" :key="p.id" class="relative">
      <img
        :src="p.url"
        :alt="`รูปที่ ${i + 1}${p.label ? ` (${p.label})` : ''}`"
        class="aspect-square w-full rounded-field object-cover"
      />
      <span
        v-if="p.label"
        class="absolute bottom-1 left-1 rounded bg-dark/70 px-1.5 text-[11px] text-on-dark"
        >{{ p.label }}</span
      >
      <button
        v-if="!disabled"
        type="button"
        class="absolute top-1 right-1 flex size-7 items-center justify-center rounded-full bg-dark/70 text-sm text-on-dark hover:bg-danger"
        :aria-label="`ลบรูปที่ ${i + 1}`"
        @click="$emit('remove', p.id)"
      >
        ×
      </button>
    </li>
  </ul>
</template>
