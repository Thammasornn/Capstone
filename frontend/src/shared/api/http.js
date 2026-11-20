import axios from 'axios'
import { normalizeError } from './errors'

/**
 * Axios instance เดียวของทั้งแอป — component ห้ามเรียก axios ตรง ให้เรียกผ่าน api.js ของฟีเจอร์
 *
 * shared ไม่รู้จัก auth store จึงให้ชั้น app ฉีดฟังก์ชันเข้ามาผ่าน configureHttp() ใน main.js
 *
 * ตัวเลือกเพิ่มต่อ request:
 *   skipAuth: true          ไม่แนบ Bearer (ใช้กับ POST /auth/login)
 *   skipAuthRedirect: true  ได้ 401 แล้วไม่ logout อัตโนมัติ (หน้า Login ต้องแสดง "อีเมลหรือรหัสผ่านไม่ถูกต้อง" เอง)
 */

export const DEFAULT_TIMEOUT_MS = 15_000
/** อัปโหลดใบหน้าต้องรอ AI Service — คู่มือกำหนดอย่างน้อย 30 วินาที */
export const UPLOAD_TIMEOUT_MS = 60_000

const hooks = {
  /** @type {() => string | null} */
  getToken: () => null,
  /** @type {(error: import('./errors').ApiError) => void} */
  onUnauthorized: () => {},
}

/**
 * @param {object} options
 * @param {() => string | null} options.getToken
 * @param {(error: import('./errors').ApiError) => void} options.onUnauthorized
 */
export function configureHttp({ getToken, onUnauthorized }) {
  hooks.getToken = getToken
  hooks.onUnauthorized = onUnauthorized
}

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: DEFAULT_TIMEOUT_MS,
  headers: { Accept: 'application/json' },
})

http.interceptors.request.use((config) => {
  const token = config.skipAuth ? null : hooks.getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const apiError = normalizeError(error)
    if (apiError.kind === 'unauthorized' && !error?.config?.skipAuthRedirect) {
      hooks.onUnauthorized(apiError)
    }
    return Promise.reject(apiError)
  },
)

/**
 * เรียก GET แล้วคืน data ตรง ๆ
 * @template T
 * @param {string} url
 * @param {import('axios').AxiosRequestConfig} [config]
 * @returns {Promise<T>}
 */
export async function getJson(url, config) {
  const { data } = await http.get(url, config)
  return data
}
