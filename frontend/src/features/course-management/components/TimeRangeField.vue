<script setup>
/** ช่วงเวลา เริ่ม–สิ้นสุด ("HH:mm:ss") */
import { computed } from 'vue'
import TextField from '@/shared/components/TextField.vue'

defineProps({
  label: { type: String, required: true },
  error: { type: String, default: '' },
})
const start = defineModel('start', { type: String, default: '' })
const end = defineModel('end', { type: String, default: '' })

const toInput = (v) => (v ? v.slice(0, 5) : '')
const fromInput = (v) => (v ? `${v}:00` : '')
const startInput = computed({
  get: () => toInput(start.value),
  set: (v) => (start.value = fromInput(v)),
})
const endInput = computed({ get: () => toInput(end.value), set: (v) => (end.value = fromInput(v)) })
</script>

<template>
  <fieldset>
    <legend class="mb-1.5 text-xs font-medium text-muted">
      {{ label }} <span class="text-danger">*</span>
    </legend>
    <div class="grid grid-cols-2 gap-3">
      <TextField v-model="startInput" type="time" label="เริ่ม" />
      <TextField v-model="endInput" type="time" label="สิ้นสุด" />
    </div>
    <p v-if="error" class="mt-1 text-xs text-danger">{{ error }}</p>
  </fieldset>
</template>
