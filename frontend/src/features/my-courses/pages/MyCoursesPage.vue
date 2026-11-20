<script setup>
// TODO(R1): Figma 105:2 — รายการวิชา/กลุ่มจาก GET /courses (entities/course)
import EmptyState from '@/shared/components/EmptyState.vue'
</script>

<template>
  <section>
    <h1 class="mb-4 text-[28px] font-bold">วิชาของฉัน</h1>
    <EmptyState title="กำลังพัฒนา" description="หน้านี้ยังไม่ได้ทำใน scaffold รอบแรก" />
  </section>
</template>
