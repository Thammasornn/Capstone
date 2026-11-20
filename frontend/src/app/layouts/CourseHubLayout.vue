<script setup>
/**
 * แท็บในหน้า Courses (Figma "Course & Schedule")
 * Registration Status · Manage Courses · Attendance Rules · Notifications · Analytics · At-Risk
 * Attendance Rules = R2, Notifications = R3 → แสดงเป็น "เร็ว ๆ นี้"
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import TabNav from '@/shared/components/TabNav.vue'

const route = useRoute()

const tabs = computed(() =>
  [
    { label: 'สถานะลงทะเบียนใบหน้า', name: 'courses-registration' },
    { label: 'จัดการรายวิชา', name: 'courses-manage' },
    { label: 'กฎการเช็คชื่อ', disabled: true },
    { label: 'การแจ้งเตือน', disabled: true },
    { label: 'วิเคราะห์การเข้าเรียน', name: 'courses-analytics' },
    { label: 'นักศึกษากลุ่มเสี่ยง', name: 'courses-at-risk' },
  ].map((tab) =>
    tab.disabled
      ? tab
      : { label: tab.label, to: { name: tab.name }, active: route.name === tab.name },
  ),
)
</script>

<template>
  <div>
    <header class="mb-6">
      <h1 class="text-[28px] font-bold">รายวิชาและตารางเรียน</h1>
      <p class="mt-1 text-sm text-muted">
        จัดการรายวิชา ดูสถานะการลงทะเบียนใบหน้า และติดตามการเข้าเรียน
      </p>
    </header>
    <TabNav :items="tabs" label="เมนูรายวิชา" class="mb-6" />
    <RouterView />
  </div>
</template>
