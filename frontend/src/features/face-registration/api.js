import { UPLOAD_TIMEOUT_MS, getJson, http } from '@/shared/api/http'
import { CONSENT_VERSION } from './constants'

/**
 * @typedef {object} FaceStatus  (API 5.11 + consentVersion/consentedAt ที่ขอใน B7)
 * @property {string} studentId
 * @property {boolean} registered
 * @property {string | null} registeredAt
 * @property {number} activeFaces
 * @property {string | null} [consentVersion]
 * @property {string | null} [consentedAt]
 * @property {'granted'|'withdrawn'|null} [consentStatus]
 * @property {boolean} [needsReconsent]  ข้อความยินยอมมีฉบับใหม่ ต้องยินยอมใหม่
 *
 * @typedef {object} RegisterFaceResult  (API 5.10)
 * @property {string} studentId
 * @property {number} savedCount
 * @property {string} model
 */

/**
 * POST /students/{studentId}/face/register — multipart, files ซ้ำ 3–5 ครั้ง
 * ห้ามตั้ง Content-Type เอง (browser ใส่ boundary ให้) · timeout ≥ 30 วินาที
 * ลงทะเบียนซ้ำ = แทนที่ใบหน้าเดิมทั้งหมด → หน้าจอต้องเตือนก่อนเรียก
 * @param {string} studentId
 * @param {File[]} files
 * @param {{ signal?: AbortSignal, onUploadProgress?: (e: import('axios').AxiosProgressEvent) => void }} [options]
 *   ไม่ส่ง ageConfirmed แล้ว (เอาข้อยืนยันอายุ 20 ปีออกจากหน้ายินยอม) → backend ใช้ค่าเริ่มต้น false
 * @returns {Promise<RegisterFaceResult>}
 */
export async function registerFace(studentId, files, options = {}) {
  const form = new FormData()
  files.forEach((file) => form.append('files', file))
  form.append('consent', 'true')
  form.append('consentTextVersion', CONSENT_VERSION)
  const { data } = await http.post(`/students/${studentId}/face/register`, form, {
    timeout: UPLOAD_TIMEOUT_MS,
    signal: options.signal,
    onUploadProgress: options.onUploadProgress,
  })
  return data
}

/** GET /students/{studentId}/face/status @returns {Promise<FaceStatus>} */
export function getFaceStatus(studentId) {
  return getJson(`/students/${studentId}/face/status`)
}

/**
 * B7 — ถอนความยินยอม + ลบข้อมูลใบหน้า
 * จำเป็นตาม PDPA: ถอนต้องง่ายเท่ากับตอนให้ · aiNotified = AI Service ลบข้อมูลในหน่วยความจำแล้ว
 * @returns {Promise<{ studentId: string, registered: false, withdrawnAt: string, deletedCount: number, aiNotified: boolean }>}
 */
export async function withdrawConsent(studentId) {
  const { data } = await http.delete(`/students/${studentId}/face`)
  return data
}
