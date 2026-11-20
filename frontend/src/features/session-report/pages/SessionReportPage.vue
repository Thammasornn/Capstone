<script setup>
// TODO(R1): Figma 127:2 — หัวคาบ + การ์ด (5.7, เตือนถ้าคาบยังไม่ถึง) + ตารางเช็คชื่อ + ตัวกรองสถานะ (5.8) + เช็คชื่อด้วยมือ (B8)
import EmptyState from '@/shared/components/EmptyState.vue'
</script>

<template>
  <section>
    <h1 class="mb-4 text-[28px] font-bold">รายงานรายคาบ</h1>
    <EmptyState title="กำลังพัฒนา" description="หน้านี้ยังไม่ได้ทำใน scaffold รอบแรก" />
  </section>
</template>
