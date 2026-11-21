<script setup>
/** หัวรายงานคาบ (5.7) */
import StatCard from '@/shared/components/StatCard.vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import { formatSessionStatus } from '@/entities/attendance/status'
import { formatThaiDate, formatTime, isSessionInFuture } from '@/shared/utils/datetime'
import { formatRate } from '@/shared/utils/format'

defineProps({
  summary: { type: Object, required: true },
  courseLabel: { type: String, default: '' },
})
</script>

<template>
  <div class="flex flex-col gap-4">
    <div>
      <div class="flex flex-wrap items-center gap-2">
        <h1 class="text-[28px] font-bold">รายงานคาบเรียน</h1>
        <StatusBadge tone="neutral" :label="formatSessionStatus(summary.sessionStatus)" />
      </div>
      <p class="mt-1 text-sm text-muted">
        {{ courseLabel }} กลุ่ม {{ summary.sectionCode }} ·
        {{ formatThaiDate(summary.sessionDate) }} {{ formatTime(summary.startTime) }}–{{
          formatTime(summary.endTime)
        }}
      </p>
    </div>
    <p
      v-if="isSessionInFuture(summary.sessionDate, summary.startTime)"
      class="rounded-field bg-warning-bg px-4 py-3 text-sm text-warning"
    >
      คาบนี้ยังไม่เริ่ม ตัวเลขด้านล่างยังไม่ใช่ผลการเข้าเรียนจริง
    </p>
    <div class="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <StatCard
        label="อัตราเข้าเรียน"
        :value="formatRate(summary.attendanceRate)"
        tone="primary"
        :hint="`${summary.totalStudents} คน`"
      />
      <StatCard label="ตรงเวลา" :value="summary.onTime" tone="success" />
      <StatCard label="สาย" :value="summary.late" tone="warning" />
      <StatCard label="ขาด" :value="summary.absent" tone="danger" />
    </div>
  </div>
</template>
