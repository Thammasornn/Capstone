import { setupWorker } from 'msw/browser'
import { getHandlers } from './handlers'

/**
 * เปิด Mock Service Worker ในเบราว์เซอร์ (เรียกจาก main.js เมื่อ VITE_MOCK_MODE=all)
 * ไฟล์ worker อยู่ที่ public/mockServiceWorker.js และต้องเสิร์ฟใต้ base เดียวกับแอป (/, /uat/)
 */
export async function startMockWorker() {
  const worker = setupWorker(...getHandlers())
  await worker.start({
    serviceWorker: { url: `${import.meta.env.BASE_URL}mockServiceWorker.js` },
    onUnhandledRequest: 'bypass',
  })
  console.info('[mock] MSW เปิดอยู่ (VITE_MOCK_MODE=all)')
  return worker
}
