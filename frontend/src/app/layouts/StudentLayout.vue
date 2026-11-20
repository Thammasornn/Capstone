<script setup>
/** Layout นักศึกษา — Figma "Student - Home": header + แท็บ pill (Home · My Courses · My Attendance · Face Registration · Notifications) */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import TabNav from '@/shared/components/TabNav.vue'
import AppHeader from './AppHeader.vue'

const route = useRoute()

const tabs = computed(() => [
  { label: 'หน้าแรก', to: { name: 'student-home' }, active: route.name === 'student-home' },
  {
    label: 'วิชาของฉัน',
    to: { name: 'student-courses' },
    active: route.name === 'student-courses',
  },
  {
    label: 'การเข้าเรียน',
    to: { name: 'student-attendance' },
    active: route.name === 'student-attendance',
  },
  {
    label: 'ลงทะเบียนใบหน้า',
    to: { name: 'face-registration' },
    active: route.name === 'face-registration' || route.name === 'face-manage',
  },
  // Notifications เป็น Release 3
  { label: 'การแจ้งเตือน', disabled: true },
])
</script>

<template>
  <div class="min-h-screen bg-page">
    <AppHeader />
    <main class="mx-auto max-w-[1440px] px-4 py-6 sm:px-12 sm:py-8">
      <TabNav :items="tabs" label="เมนูนักศึกษา" class="mb-6" />
      <RouterView />
    </main>
  </div>
</template>
