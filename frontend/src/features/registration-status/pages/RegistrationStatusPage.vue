<script setup>
// TODO(R1): Figma 42:2 — นักศึกษาที่ยังไม่ลงทะเบียน (API 5.6 faceRegistered=false)
import EmptyState from '@/shared/components/EmptyState.vue'
</script>

<template>
  <section>
    <h1 class="mb-4 text-[28px] font-bold">สถานะลงทะเบียนใบหน้า</h1>
    <EmptyState title="กำลังพัฒนา" description="หน้านี้ยังไม่ได้ทำใน scaffold รอบแรก" />
  </section>
</template>
