/**
 * แปลงข้อผิดพลาดจาก axios ให้เป็นรูปเดียวกันทั้งแอป (ตามคู่มือ Attendance API v1.0 ข้อ 2.3)
 *
 * Body ข้อผิดพลาดจาก Backend:
 * @typedef {object} ApiErrorBody
 * @property {string} timestamp  เวลาไทย ไม่มี timezone
 * @property {number} status
 * @property {string} error
 * @property {string} message    ข้อความภาษาไทย แสดงให้ผู้ใช้ได้
 * @property {string} path
 */

/**
 * ชนิดข้อผิดพลาดที่หน้าจอใช้ตัดสินใจ
 * @typedef {'validation'|'unauthorized'|'forbidden'|'not_found'|'conflict'|'unprocessable'|'unavailable'|'timeout'|'network'|'canceled'|'unknown'} ApiErrorKind
 */

/**
 * สิ่งที่ UI ควรทำ
 * - show_message: แสดง message ให้ผู้ใช้แก้ไข
 * - logout: ล้าง token แล้วกลับหน้า Login (http.js จัดการให้แล้ว)
 * - forbidden_page / not_found_page: แสดงหน้า "ไม่มีสิทธิ์" / "ไม่พบข้อมูล"
 * - pick_new_photos: แสดง message แล้วให้เลือก/ถ่ายรูปใหม่ (422 จาก AI Service)
 * - retry: แสดงข้อความ + ปุ่มลองใหม่
 * @typedef {'show_message'|'logout'|'forbidden_page'|'not_found_page'|'pick_new_photos'|'retry'|'none'} ApiErrorAction
 */

/** @type {Record<ApiErrorKind, { message: string, action: ApiErrorAction }>} */
export const ERROR_DEFAULTS = {
  validation: { message: 'ข้อมูลที่ส่งไม่ถูกต้อง', action: 'show_message' },
  unauthorized: { message: 'เซสชันหมดอายุ กรุณาเข้าสู่ระบบอีกครั้ง', action: 'logout' },
  forbidden: { message: 'ไม่มีสิทธิ์เข้าถึงข้อมูลนี้', action: 'forbidden_page' },
  not_found: { message: 'ไม่พบข้อมูล', action: 'not_found_page' },
  conflict: { message: 'ทำรายการนี้ไม่ได้ เพราะข้อมูลถูกใช้งานอยู่', action: 'show_message' },
  unprocessable: {
    message: 'ระบบไม่สามารถใช้รูปนี้ได้ กรุณาเลือกรูปใหม่',
    action: 'pick_new_photos',
  },
  unavailable: { message: 'ระบบไม่พร้อมใช้งาน ลองใหม่ภายหลัง', action: 'retry' },
  timeout: { message: 'ระบบใช้เวลานานเกินไป ลองใหม่อีกครั้ง', action: 'retry' },
  network: {
    message: 'เชื่อมต่อเซิร์ฟเวอร์ไม่ได้ ตรวจสอบอินเทอร์เน็ตแล้วลองใหม่',
    action: 'retry',
  },
  canceled: { message: 'ยกเลิกคำขอแล้ว', action: 'none' },
  unknown: { message: 'เกิดข้อผิดพลาด ลองใหม่อีกครั้ง', action: 'retry' },
}

/** @param {number|undefined} status @returns {ApiErrorKind} */
export function kindFromStatus(status) {
  if (status === 400) return 'validation'
  if (status === 401) return 'unauthorized'
  if (status === 403) return 'forbidden'
  if (status === 404) return 'not_found'
  if (status === 409) return 'conflict'
  if (status === 422) return 'unprocessable'
  if (status === 502 || status === 503 || status === 504) return 'unavailable'
  return 'unknown'
}

export class ApiError extends Error {
  /**
   * @param {object} init
   * @param {ApiErrorKind} init.kind
   * @param {number|null} init.status
   * @param {string} init.message
   * @param {string|null} [init.path]
   * @param {unknown} [init.cause]
   */
  constructor({ kind, status, message, path = null, cause }) {
    super(message, { cause })
    this.name = 'ApiError'
    this.kind = kind
    this.status = status
    this.path = path
    this.action = ERROR_DEFAULTS[kind].action
  }
}

/**
 * แปลง error จาก axios (หรืออะไรก็ได้) → ApiError
 * ตัดสินจาก HTTP status เป็นหลัก เพราะ 401 อาจไม่มี body
 * 502 ใช้ข้อความกลางเสมอ ตามคู่มือข้อ 2.3
 * @param {any} error
 * @returns {ApiError}
 */
export function normalizeError(error) {
  if (error instanceof ApiError) return error

  if (error?.code === 'ERR_CANCELED' || error?.name === 'CanceledError') {
    return new ApiError({
      kind: 'canceled',
      status: null,
      message: ERROR_DEFAULTS.canceled.message,
      cause: error,
    })
  }
  if (error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT') {
    return new ApiError({
      kind: 'timeout',
      status: null,
      message: ERROR_DEFAULTS.timeout.message,
      cause: error,
    })
  }

  const response = error?.response
  if (!response) {
    const kind = error?.request ? 'network' : 'unknown'
    return new ApiError({ kind, status: null, message: ERROR_DEFAULTS[kind].message, cause: error })
  }

  const kind = kindFromStatus(response.status)
  /** @type {Partial<ApiErrorBody>} */
  const body = response.data && typeof response.data === 'object' ? response.data : {}
  const serverMessage =
    typeof body.message === 'string' && body.message.trim() ? body.message : null
  const useServerMessage = serverMessage && kind !== 'unavailable' && kind !== 'unknown'

  return new ApiError({
    kind,
    status: response.status,
    message: useServerMessage ? serverMessage : ERROR_DEFAULTS[kind].message,
    path: body.path ?? null,
    cause: error,
  })
}
