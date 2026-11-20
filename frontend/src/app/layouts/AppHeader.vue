<script setup>
/** แถบบนสุดที่ Student/Staff ใช้ร่วมกัน — Figma: โลโก้ซ้าย · เมนูกลาง (slot) · ป้าย role + ชื่อผู้ใช้ขวา */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import { ROLE_LABELS } from '@/shared/constants/roles'
import { fullName } from '@/shared/utils/format'
import { useAuthStore } from '@/features/auth/store'

const auth = useAuthStore()
const router = useRouter()

const name = computed(() => fullName(auth.user))
const initials = computed(() =>
  [auth.user?.firstName, auth.user?.lastName]
    .filter(Boolean)
    .map((part) => part.charAt(0))
    .join('')
    .toUpperCase(),
)
/** Figma: Admin = pill สีน้ำเงิน, Student = pill สีเหลือง */
const roleTone = computed(
  () => ({ admin: 'info', lecturer: 'info', student: 'warning' })[auth.role] ?? 'neutral',
)

async function onLogout() {
  auth.logout('manual')
  await router.replace({ name: 'login' })
}
</script>

<template>
  <header class="border-b border-line bg-surface">
    <div
      class="mx-auto flex max-w-[1440px] flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3 sm:px-12 sm:py-5"
    >
      <RouterLink to="/" class="flex items-center gap-2.5">
        <span class="size-9 rounded-full bg-primary" aria-hidden="true" />
        <span class="text-[17px] font-bold">Bangmod Smart Camera</span>
      </RouterLink>

      <div class="order-last w-full sm:order-none sm:w-auto sm:flex-1">
        <slot name="nav" />
      </div>

      <div class="ml-auto flex items-center gap-3">
        <StatusBadge v-if="auth.role" :tone="roleTone" :label="ROLE_LABELS[auth.role]" />
        <span
          class="flex size-9 items-center justify-center rounded-full bg-dark text-xs font-semibold text-on-dark"
          aria-hidden="true"
        >
          {{ initials }}
        </span>
        <span class="hidden text-sm font-medium md:inline">{{ name }}</span>
        <button
          type="button"
          class="rounded-button px-2 py-1 text-sm font-medium text-muted hover:text-danger"
          @click="onLogout"
        >
          ออกจากระบบ
        </button>
      </div>
    </div>
  </header>
</template>
