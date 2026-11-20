<script setup>
// TODO(R1): Figma 1:18 — ภาพสด (B2) + SSE faces/check_in (B3) + Present/Absent + รายชื่อ (5.8 ด้วย currentSession.sessionId)
import EmptyState from '@/shared/components/EmptyState.vue'
</script>

<template>
  <section>
    <h1 class="mb-4 text-[28px] font-bold">Dashboard ห้องเรียน</h1>
    <EmptyState title="กำลังพัฒนา" description="หน้านี้ยังไม่ได้ทำใน scaffold รอบแรก" />
  </section>
</template>
