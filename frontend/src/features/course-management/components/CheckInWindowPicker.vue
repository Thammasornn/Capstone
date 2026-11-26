<script setup>
/**
 * ช่วงเช็คชื่อ (กล้องทำงานเฉพาะช่วงนี้) + เวลาที่นับว่าตรงเวลา
 * กติกาเวลาสาย (F1): preset = คำนวณจากเวลาเรียน (เปลี่ยนเวลาเรียนแล้วคำนวณใหม่) · แก้ช่องเอง → 'custom'
 * preset: lateUntil = null (สายได้จนปิดเช็คชื่อ) — สูตรอยู่ที่ entities/attendance/check-in-presets.js
 */
import { computed, watch } from 'vue'
import TextField from '@/shared/components/TextField.vue'
import { CUSTOM_PRESET, computeCheckInWindow } from '@/entities/attendance/check-in-presets'
import LatePresetPicker from '@/entities/attendance/components/LatePresetPicker.vue'
import TimeRangeField from './TimeRangeField.vue'

const props = defineProps({
  error: { type: String, default: '' },
  startTime: { type: String, default: '' },
  endTime: { type: String, default: '' },
})
const opens = defineModel('opens', { type: String, default: '' })
const closes = defineModel('closes', { type: String, default: '' })
const onTimeMinutes = defineModel('onTimeMinutes', { type: [Number, String], default: 15 })
const lateUntil = defineModel('lateUntil', { type: [String, null], default: null })
const preset = defineModel('preset', { type: [Number, String], default: CUSTOM_PRESET })

function apply() {
  if (preset.value === CUSTOM_PRESET) return
  const w = computeCheckInWindow(props.startTime, props.endTime, preset.value, { seconds: true })
  if (!w) return
  opens.value = w.checkInOpens
  closes.value = w.checkInCloses
  onTimeMinutes.value = w.onTimeMinutes
  lateUntil.value = null
}
watch(() => [props.startTime, props.endTime, preset.value], apply)

/** ช่องที่ผู้ใช้แก้เอง → โหมดกำหนดเอง */
const manual = (model) =>
  computed({
    get: () => model.value,
    set: (v) => {
      if (v === model.value) return
      preset.value = CUSTOM_PRESET
      model.value = v
    },
  })
const opensInput = manual(opens)
const closesInput = manual(closes)
const onTimeInput = manual(onTimeMinutes)
</script>

<template>
  <div class="flex flex-col gap-3">
    <LatePresetPicker v-model="preset" />
    <TimeRangeField
      v-model:start="opensInput"
      v-model:end="closesInput"
      label="ช่วงเช็คชื่อ"
      :error="error"
    />
    <TextField
      v-model.number="onTimeInput"
      type="number"
      label="นับว่าตรงเวลาภายใน (นาที หลังเปิดเช็คชื่อ)"
      hint="หลังจากนี้จนปิดเช็คชื่อ = สาย · หลังปิด = ไม่บันทึก (ขาด)"
    />
  </div>
</template>
