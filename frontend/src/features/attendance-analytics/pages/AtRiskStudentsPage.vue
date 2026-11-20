<script setup>
// TODO(R1): Figma 122:2 — เฉพาะ Medium/High จาก withRiskLevel() ใน entities/attendance/risk.js
import EmptyState from '@/shared/components/EmptyState.vue'
</script>

<template>
  <section>
    <h1 class="mb-4 text-[28px] font-bold">นักศึกษากลุ่มเสี่ยง</h1>
    <EmptyState title="กำลังพัฒนา" description="หน้านี้ยังไม่ได้ทำใน scaffold รอบแรก" />
  </section>
</template>
