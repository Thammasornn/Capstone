<script setup>
/** เลือกวันเรียน (หลายวันได้) MON–SUN */
const DAYS = [
  { value: 'MON', label: 'จ.' },
  { value: 'TUE', label: 'อ.' },
  { value: 'WED', label: 'พ.' },
  { value: 'THU', label: 'พฤ.' },
  { value: 'FRI', label: 'ศ.' },
  { value: 'SAT', label: 'ส.' },
  { value: 'SUN', label: 'อา.' },
]
defineProps({ error: { type: String, default: '' } })
const model = defineModel({ type: Array, default: () => [] })

function toggle(day) {
  model.value = model.value.includes(day)
    ? model.value.filter((d) => d !== day)
    : DAYS.map((d) => d.value).filter((d) => d === day || model.value.includes(d))
}
</script>

<template>
  <fieldset class="flex flex-col gap-1.5">
    <legend class="mb-1.5 text-xs font-medium text-muted">
      วันเรียน <span class="text-danger">*</span>
    </legend>
    <div class="flex flex-wrap gap-2">
      <button
        v-for="day in DAYS"
        :key="day.value"
        type="button"
        :aria-pressed="model.includes(day.value)"
        class="min-h-10 min-w-11 rounded-button border px-3 text-sm font-medium transition-colors"
        :class="
          model.includes(day.value)
            ? 'border-primary bg-primary text-on-dark'
            : 'border-line bg-surface text-ink hover:border-primary/40'
        "
        @click="toggle(day.value)"
      >
        {{ day.label }}
      </button>
    </div>
    <p v-if="error" class="text-xs text-danger">{{ error }}</p>
  </fieldset>
</template>
