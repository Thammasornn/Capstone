<script setup>
/** รายชื่อทั้งคาบ (5.8) + ที่มาของการเช็คชื่อ (import แสดงตาม importedFrom) + ปุ่มแก้ไข (B8) */
import DataTable from '@/shared/components/DataTable.vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import { attendanceSourceLabel } from '@/entities/attendance/import'
import { getStatusMeta } from '@/entities/attendance/status'
import { formatTimeOfDateTime } from '@/shared/utils/datetime'

defineProps({
  rows: { type: Array, required: true },
  editable: { type: Boolean, default: true },
})
defineEmits(['edit'])

const columns = [
  { key: 'studentCode', label: 'รหัสนักศึกษา', sortable: true },
  { key: 'studentName', label: 'ชื่อ', sortable: true },
  { key: 'status', label: 'สถานะ', sortable: true },
  { key: 'checkInTime', label: 'เวลาเข้า', sortable: true },
  { key: 'checkOutTime', label: 'พบล่าสุด' },
  { key: 'source', label: 'ที่มา' },
  { key: 'actions', label: '' },
]
</script>

<template>
  <DataTable :columns="columns" :rows="rows" row-key="studentId" caption="รายชื่อการเข้าเรียน">
    <template #cell-status="{ value }">
      <StatusBadge :tone="getStatusMeta(value).tone" :label="getStatusMeta(value).label" />
    </template>
    <template #cell-checkInTime="{ value }">{{ formatTimeOfDateTime(value) }}</template>
    <template #cell-checkOutTime="{ value }">{{ formatTimeOfDateTime(value) }}</template>
    <template #cell-source="{ row }">{{
      attendanceSourceLabel(row.source, row.importedFrom)
    }}</template>
    <template #cell-actions="{ row }">
      <button
        v-if="editable"
        type="button"
        class="rounded-button px-2 py-1 text-xs font-semibold text-primary hover:bg-primary/5"
        @click="$emit('edit', row)"
      >
        แก้ไข
      </button>
    </template>
  </DataTable>
</template>
