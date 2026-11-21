<script setup>
/** การ์ดสรุประดับวิชา (5.4) · ตัวเลข ตรงเวลา/สาย/ขาด นับเป็น "ครั้ง" ไม่ใช่จำนวนคน */
import StatCard from '@/shared/components/StatCard.vue'
import { formatRate } from '@/shared/utils/format'

defineProps({ summary: { type: Object, required: true } })
</script>

<template>
  <div class="grid grid-cols-2 gap-3 lg:grid-cols-5">
    <StatCard
      label="อัตราเข้าเรียน"
      :value="formatRate(summary.attendanceRate, summary)"
      tone="primary"
      :hint="`${summary.totalSessions} คาบที่เริ่มแล้ว`"
    />
    <StatCard label="นักศึกษา" :value="summary.totalStudents" hint="คน" />
    <StatCard label="ตรงเวลา" :value="summary.onTime" tone="success" hint="ครั้ง" />
    <StatCard label="สาย" :value="summary.late" tone="warning" hint="ครั้ง" />
    <StatCard label="ขาด" :value="summary.absent" tone="danger" hint="ครั้ง" />
  </div>
</template>
