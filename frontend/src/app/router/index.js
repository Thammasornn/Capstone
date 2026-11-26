import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/features/auth/store'
import { routes } from './routes'
import { installGuards } from './guards'

/**
 * history base = import.meta.env.BASE_URL (มาจาก VITE_BASE) → build เดียวใช้ได้ใต้ /capstone26/cp26ms3/prod/ และ /capstone26/cp26ms3/uat/
 * NGINX ต้อง fallback ทุก path ไป index.html (ดู nginx.conf)
 */
export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: (_to, _from, saved) => saved ?? { top: 0 },
})

installGuards(router, () => {
  const auth = useAuthStore()
  return {
    isAuthenticated: auth.isAuthenticated,
    isExpired: auth.isExpired,
    role: auth.role,
    expire: () => auth.logout('expired'),
  }
})
