import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as authApi from './api'

/**
 * เซสชันผู้ใช้ — token อายุ 8 ชม. ไม่มี refresh token (คู่มือข้อ 2.1, 7)
 * เก็บใน localStorage ตามที่คู่มือแนะนำ เพื่อให้รีเฟรชหน้าแล้วยังล็อกอินอยู่
 * logout = ลบ token ฝั่ง FE เท่านั้น (token เดิมยังใช้ได้จนหมดอายุ — ข้อจำกัดของ Backend)
 */

export const STORAGE_KEY = 'bangmod.auth.v1'

/** @typedef {'expired'|'manual'|null} LogoutReason */

function readStorage() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (typeof parsed?.token !== 'string' || typeof parsed?.expiresAt !== 'number') return null
    return parsed
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const saved = readStorage()

  /** @type {import('vue').Ref<string | null>} */
  const token = ref(saved?.token ?? null)
  /** epoch ms @type {import('vue').Ref<number | null>} */
  const expiresAt = ref(saved?.expiresAt ?? null)
  /** @type {import('vue').Ref<import('./api').User | null>} */
  const user = ref(saved?.user ?? null)
  /** @type {import('vue').Ref<LogoutReason>} */
  const logoutReason = ref(null)

  const isExpired = computed(() => expiresAt.value !== null && Date.now() >= expiresAt.value)
  const isAuthenticated = computed(() => Boolean(token.value && user.value) && !isExpired.value)
  const role = computed(() => user.value?.role ?? null)
  /** studentId ที่ใช้เรียก API 5.9–5.11 */
  const studentId = computed(() => user.value?.student?.studentId ?? null)

  function persist() {
    if (!token.value) {
      localStorage.removeItem(STORAGE_KEY)
      return
    }
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ token: token.value, expiresAt: expiresAt.value, user: user.value }),
    )
  }

  /** @param {{ email: string, password: string }} credentials */
  async function login(credentials) {
    const result = await authApi.login(credentials)
    token.value = result.accessToken
    expiresAt.value = Date.now() + result.expiresIn * 1000
    user.value = result.user
    logoutReason.value = null
    persist()
    return result.user
  }

  /** รีเฟรชโปรไฟล์ (เช่น หลังลงทะเบียนใบหน้าเสร็จ — คู่มือข้อ 4.2) */
  async function refreshMe() {
    user.value = await authApi.getMe()
    persist()
    return user.value
  }

  /** @param {LogoutReason} [reason='manual'] */
  function logout(reason = 'manual') {
    token.value = null
    expiresAt.value = null
    user.value = null
    logoutReason.value = reason
    persist()
  }

  /** ให้หน้า Login อ่านเหตุผลครั้งเดียวแล้วล้าง */
  function consumeLogoutReason() {
    const reason = logoutReason.value
    logoutReason.value = null
    return reason
  }

  return {
    token,
    expiresAt,
    user,
    role,
    studentId,
    isAuthenticated,
    isExpired,
    logoutReason,
    login,
    refreshMe,
    logout,
    consumeLogoutReason,
  }
})
