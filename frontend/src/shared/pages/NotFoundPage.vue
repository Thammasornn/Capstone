<script setup>
import BaseCard from '../components/BaseCard.vue'
</script>

<template>
  <main class="flex min-h-screen items-center justify-center bg-page p-6">
    <BaseCard class="max-w-md text-center">
      <p class="text-sm font-semibold text-muted">404</p>
      <h1 class="mt-1 text-xl font-bold">ไม่พบข้อมูล</h1>
      <p class="mt-2 text-sm text-muted">หน้าหรือข้อมูลที่คุณต้องการอาจถูกย้ายหรือไม่มีอยู่</p>
      <RouterLink to="/" class="mt-5 inline-block text-sm font-semibold text-primary"
        >กลับหน้าแรก</RouterLink
      >
    </BaseCard>
  </main>
</template>
