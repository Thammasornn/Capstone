import { setupServer } from 'msw/node'
import { getHandlers } from './handlers'

/** MSW สำหรับ Vitest — ใช้ handler ชุดเดียวกับเบราว์เซอร์ */
export const server = setupServer(...getHandlers())
