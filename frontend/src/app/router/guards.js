import { HOME_ROUTE_BY_ROLE } from '@/shared/constants/roles'

/**
 * @typedef {object} SessionSnapshot
 * @property {boolean} isAuthenticated
 * @property {boolean} isExpired
 * @property {import('@/shared/constants/roles').Role | null} role
 */

/**
 * roles ที่ใช้กับ route ปลายทาง — เอาค่าที่อยู่ลึกที่สุดใน matched (route ลูก override parent ได้)
 * @param {import('vue-router').RouteLocationNormalized} to
 * @returns {string[] | null}
 */
export function requiredRoles(to) {
  for (let i = to.matched.length - 1; i >= 0; i--) {
    const roles = to.matched[i].meta?.roles
    if (Array.isArray(roles)) return roles
  }
  return null
}

/**
 * ตัดสินการเข้าหน้า (pure function — test ได้โดยไม่ต้องมี router จริง)
 * @param {import('vue-router').RouteLocationNormalized} to
 * @param {SessionSnapshot} session
 * @returns {true | import('vue-router').RouteLocationRaw}
 */
export function resolveAccess(to, session) {
  const isPublic = to.matched.some((r) => r.meta?.public)
  const guestOnly = to.matched.some((r) => r.meta?.guestOnly)

  if (guestOnly && session.isAuthenticated && session.role) {
    return { name: HOME_ROUTE_BY_ROLE[session.role] }
  }
  if (isPublic) return true

  if (!session.isAuthenticated) {
    return {
      name: 'login',
      query: { redirect: to.fullPath, ...(session.isExpired ? { reason: 'expired' } : {}) },
    }
  }

  const roles = requiredRoles(to)
  if (roles && !roles.includes(session.role)) return { name: 'forbidden' }
  return true
}

/**
 * @param {import('vue-router').Router} router
 * @param {() => SessionSnapshot & { expire: () => void }} getSession
 */
export function installGuards(router, getSession) {
  router.beforeEach((to) => {
    const session = getSession()
    // token หมดอายุระหว่างใช้งาน (ไม่มี refresh token) → ล้าง session ก่อนตัดสิน
    if (session.isExpired) session.expire()
    return resolveAccess(to, session)
  })

  router.afterEach((to) => {
    const title = to.meta?.title
    document.title = title ? `${title} · Bangmod Smart Attendance` : 'Bangmod Smart Attendance'
  })
}
