<script setup>
/** กรอบวงรีช่วยจัดใบหน้าบนภาพกล้อง */
defineProps({ label: { type: String, default: '' } })
</script>

<template>
  <div
    class="pointer-events-none absolute inset-0 flex flex-col items-center justify-center"
    aria-hidden="true"
  >
    <div
      class="h-[70%] aspect-[3/4] rounded-[50%] border-4 border-dashed border-on-dark/80 shadow-[0_0_0_9999px_rgb(15_23_42/0.35)]"
    />
    <p
      v-if="label"
      class="mt-3 rounded-full bg-dark/70 px-3 py-1 text-sm font-semibold text-on-dark"
    >
      {{ label }}
    </p>
  </div>
</template>
