<script setup>
/** สรุปตารางเรียนของกลุ่ม: วัน, เวลา, ชั่วโมงต่อสัปดาห์ */
import { computed } from 'vue'
import { durationMinutes, formatTime } from '@/shared/utils/datetime'

const props = defineProps({ section: { type: Object, required: true } })
const DAY_LABEL = {
  MON: 'จันทร์',
  TUE: 'อังคาร',
  WED: 'พุธ',
  THU: 'พฤหัสบดี',
  FRI: 'ศุกร์',
  SAT: 'เสาร์',
  SUN: 'อาทิตย์',
}
const weekly = computed(() => {
  const d = durationMinutes(props.section.startTime, props.section.endTime)
  return d === null ? null : (d * props.section.classDays.length) / 60
})
</script>

<template>
  <p class="text-sm text-muted">
    <template v-if="section.classDays.length && weekly !== null">
      ทุกวัน{{ section.classDays.map((d) => DAY_LABEL[d]).join(', ') }}
      {{ formatTime(section.startTime) }}–{{ formatTime(section.endTime) }} · รวม
      {{ weekly.toLocaleString('th-TH', { maximumFractionDigits: 1 }) }} ชั่วโมง/สัปดาห์
    </template>
    <template v-else>กรอกวันและเวลาเรียนเพื่อดูสรุป</template>
  </p>
</template>
