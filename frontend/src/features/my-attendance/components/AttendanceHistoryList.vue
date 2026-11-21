<script setup>
/** ประวัติรายคาบ (5.9) ใหม่ → เก่า */
import { computed } from 'vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import { getStatusMeta } from '@/entities/attendance/status'
import { formatThaiDate, formatTimeOfDateTime } from '@/shared/utils/datetime'

const props = defineProps({ items: { type: Array, required: true } })
const rows = computed(() => [...props.items].reverse())
</script>

<template>
  <ul class="divide-y divide-line rounded-card border border-line bg-surface">
    <li
      v-for="item in rows"
      :key="item.sessionId"
      class="flex flex-wrap items-center gap-3 px-4 py-3"
    >
      <span class="flex-1 text-sm font-medium">{{ formatThaiDate(item.sessionDate) }}</span>
      <span class="text-xs text-muted">
        <template v-if="item.checkInTime"
          >เข้า {{ formatTimeOfDateTime(item.checkInTime) }}</template
        >
        <template v-if="item.checkOutTime">
          · พบล่าสุด {{ formatTimeOfDateTime(item.checkOutTime) }}</template
        >
      </span>
      <StatusBadge
        :tone="getStatusMeta(item.status).tone"
        :label="getStatusMeta(item.status).label"
      />
    </li>
    <li v-if="!rows.length" class="px-4 py-8 text-center text-sm text-muted">
      ยังไม่มีคาบที่เริ่มแล้ว
    </li>
  </ul>
</template>
