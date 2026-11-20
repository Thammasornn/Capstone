import '@fontsource-variable/inter'
import '@fontsource-variable/noto-sans-thai'
import './assets/main.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import { router } from './app/router'
import { configureHttp } from './shared/api/http'
import { configureSse } from './shared/api/sse'
import { useAuthStore } from './features/auth/store'
import { useCourseStore } from './entities/course/store'

async function enableMocks() {
  const mode = import.meta.env.VITE_MOCK_MODE
  // VITE_MOCK_MODE=off ถูกแทนค่าตอน build → โค้ด mock ไม่ถูกรวมใน bundle production
  if (mode !== 'all' && mode !== 'missing') return
  const { startMockWorker } = await import('./mocks/browser')
  await startMockWorker(mode)
}

async function bootstrap() {
  await enableMocks()

  const app = createApp(App)
  const pinia = createPinia()
  app.use(pinia)

  const auth = useAuthStore(pinia)

  configureHttp({
    getToken: () => auth.token,
    // 401 จาก request ใดก็ตาม (ยกเว้น login) → ล้าง token แล้วกลับหน้า Login พร้อมบอกว่าเซสชันหมดอายุ
    onUnauthorized: () => {
      if (!auth.token) return
      auth.logout('expired')
      const current = router.currentRoute.value
      if (current.name !== 'login') {
        router.replace({ name: 'login', query: { reason: 'expired', redirect: current.fullPath } })
      }
    },
  })
  configureSse({ getToken: () => auth.token })

  // ออกจากระบบแล้วล้างข้อมูลที่ cache ไว้ของผู้ใช้คนก่อน (เช่น รายวิชาที่เลือก)
  const courseStore = useCourseStore(pinia)
  auth.$onAction(({ name, after }) => {
    if (name === 'logout') after(() => courseStore.reset())
  })

  app.use(router)
  app.mount('#app')
}

bootstrap()
