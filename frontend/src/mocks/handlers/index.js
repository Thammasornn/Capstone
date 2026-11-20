import { v1Handlers } from './v1'
import { pendingHandlers } from './pending'

/** ต่อ path กับ VITE_API_BASE_URL ให้ตรงกับที่ http.js เรียกจริง */
export function apiPath(path) {
  return `${String(import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')}${path}`
}

/**
 * @param {'all'|'missing'} mode
 *   all     = mock ทุก endpoint (dev ไม่ต้องรัน Backend)
 *   missing = mock เฉพาะ B1–B8 ที่ Backend ยังไม่มี ที่เหลือส่งต่อไป Backend จริง
 */
export function getHandlers(mode) {
  return [...pendingHandlers(apiPath, mode), ...(mode === 'all' ? v1Handlers(apiPath) : [])]
}
