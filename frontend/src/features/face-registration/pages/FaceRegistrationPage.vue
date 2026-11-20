<script setup>
// TODO(R1): Figma 87:2 + 87:45 — Stepper: ยินยอม (consent-text.js) → ถ่าย 3 มุม → ส่ง (API 5.10) แล้ว auth.refreshMe()
import EmptyState from '@/shared/components/EmptyState.vue'
</script>

<template>
  <section>
    <h1 class="mb-4 text-[28px] font-bold">ลงทะเบียนใบหน้า</h1>
    <EmptyState title="กำลังพัฒนา" description="หน้านี้ยังไม่ได้ทำใน scaffold รอบแรก" />
  </section>
</template>
