/** กติกาการลงทะเบียนใบหน้า — ตรงกับ API 5.10 (Backend ตรวจซ้ำอีกชั้น) */
export const MIN_PHOTOS = 3
export const MAX_PHOTOS = 5
export const MAX_FILE_BYTES = 5 * 1024 * 1024
export const MAX_TOTAL_BYTES = 25 * 1024 * 1024
export const ALLOWED_TYPES = Object.freeze(['image/jpeg', 'image/png'])

/** ต้องตรงกับเวอร์ชันข้อความใน consent-text.js — แก้ข้อความเมื่อไรต้องขึ้นเวอร์ชันใหม่ */
export const CONSENT_VERSION = 'v1'

/** ลำดับมุมถ่าย (Proposal 5.1) — ส่งเป็น files ตาม API เดิม ไม่ต้องส่งชื่อมุม */
export const CAPTURE_ANGLES = Object.freeze([
  { key: 'front', label: 'หน้าตรง' },
  { key: 'left', label: 'หันซ้าย' },
  { key: 'right', label: 'หันขวา' },
])
