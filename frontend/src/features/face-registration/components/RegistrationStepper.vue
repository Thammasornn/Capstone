<script setup>
defineProps({ current: { type: Number, required: true } })
const STEPS = ['ความยินยอม', 'ถ่าย / เลือกรูป', 'เสร็จสิ้น']
</script>

<template>
  <ol class="flex flex-wrap items-center gap-2 text-sm" aria-label="ขั้นตอน">
    <li v-for="(label, i) in STEPS" :key="label" class="flex items-center gap-2">
      <span
        class="flex size-7 items-center justify-center rounded-full text-xs font-bold"
        :class="
          i < current
            ? 'bg-success text-on-dark'
            : i === current
              ? 'bg-primary text-on-dark'
              : 'bg-line text-muted'
        "
        :aria-current="i === current ? 'step' : undefined"
      >
        {{ i < current ? '✓' : i + 1 }}
      </span>
      <span :class="i === current ? 'font-semibold' : 'text-muted'">{{ label }}</span>
      <span v-if="i < STEPS.length - 1" class="mx-1 h-px w-6 bg-line" aria-hidden="true" />
    </li>
  </ol>
</template>
