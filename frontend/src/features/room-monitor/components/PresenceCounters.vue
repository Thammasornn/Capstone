<script setup>
/** ตัวเลขของคาบปัจจุบัน: ตรงเวลา / สาย / ยังไม่เข้า + จำนวนใบหน้าในเฟรม (SSE faces) */
import StatCard from '@/shared/components/StatCard.vue'

defineProps({
  onTime: { type: Number, default: 0 },
  late: { type: Number, default: 0 },
  notYet: { type: Number, default: 0 },
  total: { type: Number, default: 0 },
  facesDetected: { type: Number, default: null },
})
</script>

<template>
  <div class="grid grid-cols-2 gap-3 lg:grid-cols-4">
    <StatCard label="ตรงเวลา" :value="onTime" tone="success" :hint="`จาก ${total} คน`" />
    <StatCard label="สาย" :value="late" tone="warning" />
    <StatCard label="ยังไม่เข้า / ขาด" :value="notYet" tone="danger" />
    <StatCard
      label="ใบหน้าในเฟรม"
      :value="facesDetected ?? '-'"
      tone="primary"
      hint="อัปเดตสดจากกล้อง"
    />
  </div>
</template>
