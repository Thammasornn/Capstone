<script setup>
/**
 * ป้ายระดับความเสี่ยง — ค่ามาจาก entities/attendance/risk.js (getRiskMeta) ส่งเข้ามาเป็น tone + label
 * อย่าแสดงให้ role student (ตรวจด้วย canSeeRisk ก่อน render)
 */
import StatusBadge from './StatusBadge.vue'

defineProps({
  tone: {
    type: String,
    required: true,
    validator: (v) => ['success', 'warning', 'danger', 'neutral'].includes(v),
  },
  label: { type: String, required: true },
})
</script>

<template>
  <StatusBadge :tone="tone" :label="label" dot />
</template>
