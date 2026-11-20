<script setup>
// TODO(R1): Figma 65:2 — ฟอร์มรายวิชา + ตารางเรียนต่อ section + ช่วงเช็คชื่อ (B5) ใช้ทั้ง Add และ Edit
import EmptyState from '@/shared/components/EmptyState.vue'
</script>

<template>
  <section>
    <h1 class="mb-4 text-[28px] font-bold">เพิ่ม/แก้ไขรายวิชา</h1>
    <EmptyState title="กำลังพัฒนา" description="หน้านี้ยังไม่ได้ทำใน scaffold รอบแรก" />
  </section>
</template>
