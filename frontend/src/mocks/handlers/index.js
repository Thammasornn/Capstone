import { v1Handlers } from './v1'

/** ต่อ path กับ VITE_API_BASE_URL ให้ตรงกับที่ http.js เรียกจริง */
export function apiPath(path) {
  return `${String(import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')}${path}`
}

/**
 * mock เฉพาะ API v1.0 (5.1–5.11) ใช้ตอน VITE_MOCK_MODE=all (dev ไม่รัน Backend) และ Vitest
 * B1–B8 ไม่มี mock แล้ว — Backend ทำครบแล้ว ต้องรัน Backend จริงถ้าจะใช้หน้า Room / จัดการรายวิชา
 */
export function getHandlers() {
  return v1Handlers(apiPath)
}
