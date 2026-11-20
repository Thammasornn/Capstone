<script setup>
import BaseCard from '../components/BaseCard.vue'
</script>

<template>
  <main class="flex min-h-screen items-center justify-center bg-page p-6">
    <BaseCard class="max-w-md text-center">
      <p class="text-sm font-semibold text-danger">403</p>
      <h1 class="mt-1 text-xl font-bold">ไม่มีสิทธิ์เข้าถึง</h1>
      <p class="mt-2 text-sm text-muted">
        บัญชีของคุณไม่มีสิทธิ์ดูหน้านี้ หากคิดว่าผิดพลาด โปรดติดต่อผู้ดูแลระบบ
      </p>
      <RouterLink to="/" class="mt-5 inline-block text-sm font-semibold text-primary"
        >กลับหน้าแรก</RouterLink
      >
    </BaseCard>
  </main>
</template>
