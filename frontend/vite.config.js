import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
// base มาจาก VITE_BASE ใน .env.<mode>: dev = "/", uat = "/capstone26/cp26ms3/uat/", production = "/capstone26/cp26ms3/prod/" (ผ่าน bscit)
export default defineConfig(({ mode, command }) => {
  // prefix '' = อ่านทุกตัวแปรใน .env (DEV_BACKEND_URL ใช้ฝั่ง Node เท่านั้น ไม่เข้า bundle)
  const env = loadEnv(mode, process.cwd(), '')
  const base = env.VITE_BASE || '/'
  const target = env.DEV_BACKEND_URL || 'http://localhost:8081'
  // dev / preview: ส่ง <base>api/* ต่อไป Spring Boot ที่ /api/* (ตัด prefix เช่น /prod/ ออก)
  // เหมือน NGINX ใน container FE บน VM · SSE (B3) ผ่าน proxy นี้ได้
  const proxy = {
    [`${base}api`]: {
      target,
      // คง Host เดิม (เหมือน NGINX บน VM) → Spring เห็นเป็น same-origin ไม่ติด CORS ไม่ว่า FE จะรัน port ไหน
      changeOrigin: false,
      rewrite: (path) => path.replace(new RegExp(`^${base}api`), '/api'),
    },
  }

  return {
    base,
    plugins: [vue(), tailwindcss(), command === 'serve' && mode !== 'test' && vueDevTools()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      strictPort: true,
      proxy,
    },
    preview: {
      proxy,
    },
  }
})
