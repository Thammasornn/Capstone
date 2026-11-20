<script setup>
// TODO(R1): Figma 104:2 — ทักทาย + คาบถัดไป + การ์ดสรุป (GET /me, /courses, 5.9) + แบนเนอร์ชวนลงทะเบียนใบหน้าถ้า faceRegistration.registered=false
import EmptyState from '@/shared/components/EmptyState.vue'
</script>

<template>
  <section>
    <h1 class="mb-4 text-[28px] font-bold">หน้าแรกนักศึกษา</h1>
    <EmptyState title="กำลังพัฒนา" description="หน้านี้ยังไม่ได้ทำใน scaffold รอบแรก" />
  </section>
</template>
