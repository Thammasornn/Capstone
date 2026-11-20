<script setup>
/**
 * หน้า Login (คู่มือ API ข้อ 4.1) — Figma: "Student - Login" (103:2)
 * ต่างจาก Figma 2 จุดเพราะต้องตาม API: ช่องแรกเป็น "อีเมล" (ไม่ใช่ Student ID)
 * และใช้หน้าเดียวทุกบทบาท (POST /auth/login ตัวเดียว นำทางตาม user.role)
 */
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BaseButton from '@/shared/components/BaseButton.vue'
import { HOME_ROUTE_BY_ROLE } from '@/shared/constants/roles'
import { useAuthStore } from '../store'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const form = reactive({ email: '', password: '' })
const submitting = ref(false)
const errorMessage = ref('')

const sessionExpired = computed(() => route.query.reason === 'expired')
const showMockAccounts = import.meta.env.VITE_MOCK_MODE === 'all'

/** บัญชีทดสอบจากคู่มือ API ข้อ 8 (แสดงเฉพาะโหมด mock ทั้งหมด) */
const mockAccounts = [
  { label: 'นักศึกษา (ลงทะเบียนใบหน้าแล้ว)', email: 'student01@mock.test' },
  { label: 'นักศึกษา (ยังไม่ลงทะเบียน)', email: 'student08@mock.test' },
  { label: 'อาจารย์', email: 'lecturer01@mock.test' },
  { label: 'Admin', email: 'admin@mock.test' },
]

function useMockAccount(email) {
  form.email = email
  form.password = 'Passw0rd!'
  errorMessage.value = ''
}

/** รับเฉพาะ path ภายในแอป กัน open redirect */
function safeRedirect(value) {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')
    ? value
    : null
}

async function onSubmit() {
  errorMessage.value = ''
  if (!form.email.trim() || !form.password) {
    errorMessage.value = 'กรุณากรอกอีเมลและรหัสผ่าน'
    return
  }
  submitting.value = true
  try {
    const user = await auth.login({ email: form.email.trim(), password: form.password })
    const target = safeRedirect(route.query.redirect) ?? { name: HOME_ROUTE_BY_ROLE[user.role] }
    await router.replace(target)
  } catch (error) {
    // 401 = อีเมลหรือรหัสผ่านผิด/บัญชีถูกปิด — ข้อความเดียวกันทุกกรณี (คู่มือข้อ 4.1)
    errorMessage.value =
      error?.kind === 'unauthorized'
        ? 'อีเมลหรือรหัสผ่านไม่ถูกต้อง'
        : (error?.message ?? 'เข้าสู่ระบบไม่สำเร็จ')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="flex min-h-screen items-center justify-center bg-page px-4 py-10">
    <div
      class="w-full max-w-[420px] rounded-card border border-line bg-surface p-7 shadow-card sm:p-9"
    >
      <div class="flex items-center gap-2.5">
        <span class="size-10 rounded-full bg-primary" aria-hidden="true" />
        <p class="text-[17px] font-bold">Bangmod Smart Camera</p>
      </div>

      <div class="mt-6">
        <h1 class="text-[22px] font-bold">เข้าสู่ระบบ</h1>
        <p class="mt-1 text-[13px] text-muted">
          ใช้บัญชีมหาวิทยาลัยเพื่อดูการเข้าเรียนและลงทะเบียนใบหน้า
        </p>
      </div>

      <p
        v-if="sessionExpired"
        role="status"
        class="mt-5 rounded-field bg-warning-bg px-3.5 py-2.5 text-[13px] text-warning"
      >
        เซสชันหมดอายุ กรุณาเข้าสู่ระบบอีกครั้ง
      </p>

      <form class="mt-5 flex flex-col gap-5" novalidate @submit.prevent="onSubmit">
        <div class="flex flex-col gap-1.5">
          <label for="login-email" class="text-xs font-medium text-muted">อีเมล</label>
          <input
            id="login-email"
            v-model="form.email"
            type="email"
            autocomplete="username"
            inputmode="email"
            required
            class="rounded-field border border-line bg-page px-3.5 py-2.5 text-[13px] text-ink focus:border-primary"
            placeholder="student01@mock.test"
          />
        </div>

        <div class="flex flex-col gap-1.5">
          <label for="login-password" class="text-xs font-medium text-muted">รหัสผ่าน</label>
          <input
            id="login-password"
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            required
            class="rounded-field border border-line bg-page px-3.5 py-2.5 text-[13px] text-ink focus:border-primary"
          />
        </div>

        <p v-if="errorMessage" role="alert" class="text-[13px] font-medium text-danger">
          {{ errorMessage }}
        </p>

        <BaseButton type="submit" block :loading="submitting">
          {{ submitting ? 'กำลังเข้าสู่ระบบ…' : 'เข้าสู่ระบบ' }}
        </BaseButton>
      </form>

      <section
        v-if="showMockAccounts"
        class="mt-6 border-t border-line pt-4"
        aria-labelledby="mock-title"
      >
        <h2 id="mock-title" class="text-xs font-semibold text-muted">
          บัญชีทดสอบ (โหมด mock · รหัสผ่าน Passw0rd!)
        </h2>
        <ul class="mt-2 flex flex-wrap gap-2">
          <li v-for="account in mockAccounts" :key="account.email">
            <button
              type="button"
              class="rounded-full border border-line px-3 py-1 text-xs text-ink hover:border-primary"
              @click="useMockAccount(account.email)"
            >
              {{ account.label }}
            </button>
          </li>
        </ul>
      </section>
    </div>
  </main>
</template>
