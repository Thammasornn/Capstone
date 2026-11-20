<script setup>
/**
 * Layout อาจารย์/admin — Figma "Room Selection": top nav
 * Classrooms · Dashboard · Courses · Attendance Reports · Settings (admin)
 */
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/features/auth/store'
import AppHeader from './AppHeader.vue'

const route = useRoute()
const auth = useAuthStore()

/** "Dashboard" = หน้า Monitor ของห้องล่าสุดที่เลือก (ยังไม่เลือก → ไปหน้าเลือกห้อง) */
const lastRoomId = ref(sessionStorage.getItem('bangmod.lastRoomId'))
watch(
  () => route.params.roomId,
  (roomId) => {
    if (typeof roomId === 'string' && roomId) {
      lastRoomId.value = roomId
      sessionStorage.setItem('bangmod.lastRoomId', roomId)
    }
  },
  { immediate: true },
)

const routeName = computed(() => String(route.name ?? ''))

const navItems = computed(() => [
  {
    label: 'ห้องเรียน',
    to: { name: 'room-selection' },
    active: routeName.value === 'room-selection',
  },
  {
    label: 'Dashboard',
    to: lastRoomId.value
      ? { name: 'room-live', params: { roomId: lastRoomId.value } }
      : { name: 'room-selection' },
    active: routeName.value === 'room-live',
  },
  {
    label: 'รายวิชา',
    to: { name: 'courses' },
    active: routeName.value.startsWith('course') && routeName.value !== 'courses-analytics',
  },
  // R1 ยังไม่มีหน้า Attendance Reports แยก → ใช้หน้า Analytics (มีลิงก์ลงไปที่ Session Report)
  {
    label: 'รายงานการเข้าเรียน',
    to: { name: 'courses-analytics' },
    active: routeName.value === 'courses-analytics' || routeName.value === 'session-report',
  },
  // Settings (admin) อยู่นอก R1
  ...(auth.role === 'admin' ? [{ label: 'ตั้งค่า', disabled: true }] : []),
])
</script>

<template>
  <div class="min-h-screen bg-page">
    <AppHeader>
      <template #nav>
        <nav aria-label="เมนูหลัก" class="overflow-x-auto">
          <ul class="flex min-w-max items-center gap-1 sm:justify-center sm:gap-2">
            <li v-for="item in navItems" :key="item.label">
              <span
                v-if="item.disabled"
                class="block cursor-not-allowed px-3 py-2 text-sm text-muted/50"
                aria-disabled="true"
                title="เร็ว ๆ นี้"
              >
                {{ item.label }}
              </span>
              <RouterLink
                v-else
                :to="item.to"
                class="block rounded-button px-3 py-2 text-sm transition-colors"
                :class="item.active ? 'font-semibold text-primary' : 'text-muted hover:text-ink'"
                :aria-current="item.active ? 'page' : undefined"
              >
                {{ item.label }}
              </RouterLink>
            </li>
          </ul>
        </nav>
      </template>
    </AppHeader>
    <main class="mx-auto max-w-[1440px] px-4 py-6 sm:px-12 sm:py-8">
      <RouterView />
    </main>
  </div>
</template>
