<script setup>
/** ช่วงเช็คชื่อ (กล้องทำงานเฉพาะช่วงนี้) + เวลาที่นับว่าตรงเวลา */
import TextField from '@/shared/components/TextField.vue'
import TimeRangeField from './TimeRangeField.vue'

defineProps({ error: { type: String, default: '' } })
const opens = defineModel('opens', { type: String, default: '' })
const closes = defineModel('closes', { type: String, default: '' })
const onTimeMinutes = defineModel('onTimeMinutes', { type: [Number, String], default: 15 })
</script>

<template>
  <div class="flex flex-col gap-3">
    <TimeRangeField
      v-model:start="opens"
      v-model:end="closes"
      label="ช่วงเช็คชื่อ"
      :error="error"
    />
    <TextField
      v-model.number="onTimeMinutes"
      type="number"
      label="นับว่าตรงเวลาภายใน (นาที หลังเปิดเช็คชื่อ)"
      hint="หลังจากนี้จนปิดเช็คชื่อ = สาย · หลังปิด = ไม่บันทึก (ขาด)"
    />
  </div>
</template>
