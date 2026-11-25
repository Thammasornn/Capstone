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

/**
 * ชื่อไฟล์จาก Content-Disposition (รองรับ filename*=UTF-8''... ของ Spring) — ไม่มี = null
 * @param {string | undefined | null} header
 */
export function filenameFromDisposition(header) {
  if (!header) return null
  const star = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/.exec(header)
  if (star) {
    try {
      return decodeURIComponent(star[1].trim().replace(/^"|"$/g, ''))
    } catch {
      /* ใช้ filename= แทน */
    }
  }
  const plain = /filename\s*=\s*"?([^";]+)"?/.exec(header)
  return plain ? plain[1].trim() : null
}

/**
 * ดาวน์โหลดไฟล์ผ่าน http (แนบ token ให้) แล้วให้เบราว์เซอร์ save — ชื่อไฟล์จาก Content-Disposition
 * @param {string} url
 * @param {import('axios').AxiosRequestConfig} [config]
 * @param {string} [fallbackName] ใช้เมื่อ server ไม่ส่งชื่อไฟล์
 * @returns {Promise<string>} ชื่อไฟล์ที่ save
 */
export async function downloadFile(url, config = {}, fallbackName = 'download') {
  const response = await http.get(url, { ...config, responseType: 'blob' })
  const name = filenameFromDisposition(response.headers['content-disposition']) ?? fallbackName
  const href = URL.createObjectURL(response.data)
  const a = document.createElement('a')
  a.href = href
  a.download = name
  document.body.append(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(href), 1000)
  return name
}
