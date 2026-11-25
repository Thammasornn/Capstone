<script setup>
/** รายชื่อคาบปัจจุบัน (5.8) + เช็คชื่อด้วยมือ (B8) · เรียงยังไม่เข้าก่อน */
import { computed, ref } from 'vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import { attendanceSourceLabel } from '@/entities/attendance/import'
import { getStatusMeta } from '@/entities/attendance/status'
import { formatTimeOfDateTime } from '@/shared/utils/datetime'

const props = defineProps({ rows: { type: Array, required: true } })
defineEmits(['edit'])

const query = ref('')
const rank = { absent: 0, late: 1, on_time: 2 }
const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  return props.rows
    .filter((r) => !q || r.studentCode.includes(q) || r.studentName.toLowerCase().includes(q))
    .sort((a, b) => rank[a.status] - rank[b.status] || a.studentCode.localeCompare(b.studentCode))
})
</script>

<template>
  <div class="flex flex-col gap-3">
    <input
      v-model="query"
      type="search"
      placeholder="ค้นหารหัสหรือชื่อนักศึกษา"
      aria-label="ค้นหานักศึกษา"
      class="min-h-11 rounded-field border border-line bg-surface px-3 text-sm focus:border-primary"
    />
    <ul class="divide-y divide-line rounded-card border border-line bg-surface">
      <li v-for="row in filtered" :key="row.studentId" class="flex items-center gap-3 px-4 py-3">
        <div class="min-w-0 flex-1">
          <p class="truncate text-sm font-medium">{{ row.studentName }}</p>
          <p class="text-xs text-muted">
            {{ row.studentCode }}
            <template v-if="row.checkInTime">
              · เข้า {{ formatTimeOfDateTime(row.checkInTime) }}
            </template>
            <template v-if="row.source">
              · {{ attendanceSourceLabel(row.source, row.importedFrom, { short: true }) }}</template
            >
          </p>
        </div>
        <StatusBadge
          :tone="getStatusMeta(row.status).tone"
          :label="
            row.status === 'absent' && !row.source ? 'ยังไม่เข้า' : getStatusMeta(row.status).label
          "
        />
        <button
          type="button"
          class="rounded-button px-2 py-1 text-xs font-semibold text-primary hover:bg-primary/5"
          @click="$emit('edit', row)"
        >
          แก้ไข
        </button>
      </li>
      <li v-if="!filtered.length" class="px-4 py-8 text-center text-sm text-muted">
        ไม่พบนักศึกษา
      </li>
    </ul>
  </div>
</template>
