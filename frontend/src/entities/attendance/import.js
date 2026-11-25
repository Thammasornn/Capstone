import { UPLOAD_TIMEOUT_MS, downloadFile, getJson, http } from '@/shared/api/http'

/**
 * นำเข้าผลเช็คชื่อจากไฟล์ (คลาสออนไซต์) — ใช้ทั้ง Room Live และ Session Report จึงอยู่ที่ entities
 * สิทธิ์ admin / อาจารย์ผู้สอน · คาบยังไม่เริ่ม / ยกเลิก = 409 (template ดาวน์โหลดได้เสมอ)
 * ไฟล์ผิด = 400 + error.errors[] (rowNo, column, value, message) — ดู shared/api/errors.js
 *
 * @typedef {'template'|'teams'|'zoom'|'google_form'|'paper'|'other'} ImportMethod
 *
 * @typedef {object} ImportMapping
 * @property {string} identifierColumn
 * @property {'email'|'student_code'} identifierType
 * @property {string} [joinTimeColumn]
 * @property {string} [timeFormat]
 * @property {string} [statusColumn]
 * @property {Record<string, 'on_time'|'late'|'absent'>} [statusValues]
 * @property {'on_time'|'late'} [defaultStatus]
 *
 * @typedef {object} InspectResult
 * @property {string[]} headers
 * @property {string[][]} sampleRows   5 แถวแรก
 * @property {Record<string, string[]>} distinctValues   เฉพาะคอลัมน์ที่มีค่าไม่ซ้ำ ≤ 20 ค่า
 * @property {number} rowCount
 *
 * @typedef {object} ImportRow
 * @property {number} rowNo
 * @property {string} studentId
 * @property {string} studentCode
 * @property {string} studentName
 * @property {string | null} fromSource
 * @property {string | null} fromStatus
 * @property {string} toStatus
 * @property {string | null} checkInTime
 * @property {'insert'|'replace'|'skip'|'conflict'} action
 * @property {boolean} applied
 *
 * @typedef {object} ImportResult
 * @property {boolean} dryRun
 * @property {'template'|'mapped'} mode
 * @property {string | null} importId
 * @property {{ importId: string, importedAt: string, importedBy: string, fileName: string } | null} duplicateOf
 * @property {{ startTime: string, onTimeUntil: string, lateUntil: string, endTime: string }} sessionWindow
 * @property {ImportRow[]} rows
 * @property {Array<{ rowNo: number, value: string, reason: 'not_found'|'not_enrolled'|'duplicate' }>} unmatched
 * @property {{ totalRows: number, insert: number, replace: number, skip: number, conflict: number, unmatched: number, applied: number }} summary
 */

/** ข้อจำกัดเดียวกับ backend */
export const IMPORT_MAX_BYTES = 2 * 1024 * 1024
export const IMPORT_MAX_ROWS = 500
export const IMPORT_REASON_MAX = 300

/** ดาวน์โหลด template (มีรายชื่อนักศึกษาแล้ว) @param {'xlsx'|'csv'} format */
export function downloadImportTemplate(sessionId, format) {
  return downloadFile(
    `/class-sessions/${sessionId}/attendance/import-template`,
    { params: { format } },
    `attendance_template.${format}`,
  )
}

/** อ่านหัวตาราง / ตัวอย่าง / ค่าที่ไม่ซ้ำ (ไม่บันทึก) @param {File} file @returns {Promise<InspectResult>} */
export async function inspectImportFile(sessionId, file) {
  const form = new FormData()
  form.append('file', file)
  const { data } = await http.post(`/class-sessions/${sessionId}/attendance/import/inspect`, form, {
    timeout: UPLOAD_TIMEOUT_MS,
  })
  return data
}

/**
 * นำเข้า (dryRun = ดูผลก่อน)
 * @param {string} sessionId
 * @param {{ file: File, mode: 'template'|'mapped', method: ImportMethod, reason: string, dryRun: boolean, overrideCamera: boolean, overrideManual: boolean, mapping?: ImportMapping | null }} params
 * @returns {Promise<ImportResult>}
 */
export async function importAttendanceFile(sessionId, params) {
  const form = new FormData()
  form.append('file', params.file)
  form.append('mode', params.mode)
  form.append('method', params.method)
  form.append('reason', params.reason)
  form.append('dryRun', String(params.dryRun))
  form.append('overrideCamera', String(params.overrideCamera))
  form.append('overrideManual', String(params.overrideManual))
  if (params.mapping) form.append('mapping', JSON.stringify(params.mapping))
  const { data } = await http.post(`/class-sessions/${sessionId}/attendance/import`, form, {
    timeout: UPLOAD_TIMEOUT_MS,
  })
  return data
}

/** ประวัติการนำเข้าของคาบ (ใหม่ → เก่า) */
export function getImportHistory(sessionId) {
  return getJson(`/class-sessions/${sessionId}/attendance/imports`)
}

/** รายละเอียดการนำเข้า 1 ครั้ง: ทุกแถวจากไฟล์ */
export function getImportDetail(sessionId, importId) {
  return getJson(`/class-sessions/${sessionId}/attendance/imports/${importId}`)
}

// ---------- ข้อความ ----------

/** @type {Record<ImportMethod, string>} */
export const IMPORT_METHOD_LABEL = {
  template: 'template',
  teams: 'Teams',
  zoom: 'Zoom',
  google_form: 'Google Form',
  paper: 'ใบเซ็นชื่อ',
  other: 'อื่น ๆ',
}
/** ที่มาที่เลือกได้ในโหมดไฟล์อื่น */
export const MAPPED_METHODS = /** @type {ImportMethod[]} */ ([
  'teams',
  'zoom',
  'google_form',
  'paper',
  'other',
])

export const UNMATCHED_REASON_LABEL = {
  not_found: 'ไม่พบนักศึกษาในระบบ',
  not_enrolled: 'ไม่ได้ลงทะเบียนในกลุ่มเรียนนี้',
  duplicate: 'ซ้ำกับแถวก่อนหน้า (ใช้แถวแรก)',
}

export const IMPORT_ACTION_LABEL = {
  insert: 'บันทึกใหม่',
  replace: 'แทนที่ผลเดิม',
  skip: 'ข้าม (ผลเดิมตรงกัน)',
  conflict: 'ขัดแย้ง',
}

/** รูปแบบเวลาที่ backend รองรับ (ต้องเลือกเองให้ตรงกับไฟล์) */
export const TIME_FORMATS = [
  { value: 'HH:mm', label: 'HH:mm (09:05)' },
  { value: 'HH:mm:ss', label: 'HH:mm:ss (09:05:30)' },
  { value: 'd/M/yyyy HH:mm:ss', label: 'd/M/yyyy HH:mm:ss (25/11/2026 09:05:30 · Google Form)' },
  { value: 'M/d/yyyy', label: 'M/d/yyyy (11/25/2026 · มีแต่วันที่ = ไม่ใช้เวลา)' },
  { value: 'h:mm:ss a', label: 'h:mm:ss a (9:05:30 AM · Teams)' },
  { value: 'yyyy-MM-dd HH:mm:ss', label: 'yyyy-MM-dd HH:mm:ss (2026-11-25 09:05:30)' },
]

/** ป้ายที่มาของผลเช็คชื่อ (5.8 source + importedFrom) */
const SOURCE_LABEL = { camera: 'กล้อง', manual: 'อาจารย์', system: 'ระบบ (ไม่พบ)' }
/**
 * @param {string | null | undefined} source
 * @param {string | null | undefined} [importedFrom]
 * @param {{ short?: boolean }} [options] short = "ระบบ" แทน "ระบบ (ไม่พบ)"
 */
export function attendanceSourceLabel(source, importedFrom, { short = false } = {}) {
  if (!source) return '-'
  if (source === 'import') {
    const m = importedFrom ? (IMPORT_METHOD_LABEL[importedFrom] ?? importedFrom) : ''
    return m ? `นำเข้า (${m})` : 'นำเข้า'
  }
  if (short && source === 'system') return 'ระบบ'
  return SOURCE_LABEL[source] ?? source
}

/** ข้อความ toast หลังนำเข้าสำเร็จ เช่น "บันทึก 8 คน · ข้าม 30 · ขัดแย้ง 2" @param {ImportResult} result */
export function describeImportResult(result) {
  const s = result?.summary
  if (!s) return 'นำเข้าผลเช็คชื่อแล้ว'
  const parts = [`บันทึก ${s.applied} คน`]
  if (s.skip) parts.push(`ข้าม ${s.skip}`)
  const conflictLeft = (result.rows ?? []).filter(
    (r) => r.action === 'conflict' && !r.applied,
  ).length
  if (conflictLeft) parts.push(`ขัดแย้ง ${conflictLeft}`)
  if (s.unmatched) parts.push(`จับคู่ไม่ได้ ${s.unmatched}`)
  return parts.join(' · ')
}
