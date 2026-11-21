<script setup>
/** ตารางนักศึกษา (5.6) + ระดับความเสี่ยง + สถานะลงทะเบียนใบหน้า */
import { computed } from 'vue'
import DataTable from '@/shared/components/DataTable.vue'
import RiskBadge from '@/shared/components/RiskBadge.vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import { getRiskMeta, withRiskLevel } from '@/entities/attendance/risk'
import { formatRate } from '@/shared/utils/format'

const props = defineProps({
  students: { type: Array, required: true },
  showFace: { type: Boolean, default: true },
})

const rows = computed(() => withRiskLevel(props.students))
const columns = computed(() => [
  { key: 'studentCode', label: 'รหัสนักศึกษา', sortable: true },
  { key: 'studentName', label: 'ชื่อ', sortable: true },
  { key: 'onTime', label: 'ตรงเวลา', align: 'right', sortable: true },
  { key: 'late', label: 'สาย', align: 'right', sortable: true },
  { key: 'absent', label: 'ขาด', align: 'right', sortable: true },
  { key: 'attendanceRate', label: 'อัตราเข้าเรียน', align: 'right', sortable: true },
  { key: 'riskLevel', label: 'ความเสี่ยง' },
  ...(props.showFace ? [{ key: 'faceRegistered', label: 'ใบหน้า' }] : []),
])
</script>

<template>
  <DataTable :columns="columns" :rows="rows" row-key="studentId" caption="การเข้าเรียนรายคน">
    <template #cell-attendanceRate="{ value }">{{ formatRate(value) }}</template>
    <template #cell-riskLevel="{ value }">
      <RiskBadge :tone="getRiskMeta(value).tone" :label="getRiskMeta(value).label" />
    </template>
    <template #cell-faceRegistered="{ value }">
      <StatusBadge
        :tone="value ? 'success' : 'neutral'"
        :label="value ? 'ลงทะเบียนแล้ว' : 'ยังไม่ลงทะเบียน'"
      />
    </template>
  </DataTable>
</template>
