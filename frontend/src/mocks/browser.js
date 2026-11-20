import { setupWorker } from 'msw/browser'
import { getHandlers } from './handlers'

/**
 * เปิด Mock Service Worker ในเบราว์เซอร์ (เรียกจาก main.js เมื่อ VITE_MOCK_MODE ≠ off)
 * ไฟล์ worker อยู่ที่ public/mockServiceWorker.js และต้องเสิร์ฟใต้ base เดียวกับแอป (/, /uat/)
 * @param {'all'|'missing'} mode
 */
export async function startMockWorker(mode) {
  const worker = setupWorker(...getHandlers(mode))
  await worker.start({
    serviceWorker: { url: `${import.meta.env.BASE_URL}mockServiceWorker.js` },
    onUnhandledRequest: 'bypass',
    quiet: mode === 'missing',
  })
  console.info(`[mock] MSW เปิดอยู่ (VITE_MOCK_MODE=${mode})`)
  return worker
}
