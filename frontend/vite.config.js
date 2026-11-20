import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
// base มาจาก VITE_BASE ใน .env.<mode>: dev = "/", uat = "/uat/", production = "/prod/"
export default defineConfig(({ mode, command }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')

  return {
    base: env.VITE_BASE || '/',
    plugins: [vue(), tailwindcss(), command === 'serve' && mode !== 'test' && vueDevTools()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      // Backend เปิด CORS ให้ http://localhost:5173 เท่านั้น ห้ามเปลี่ยนพอร์ต
      port: 5173,
      strictPort: true,
    },
  }
})
