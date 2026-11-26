import { UPLOAD_TIMEOUT_MS, downloadFile, getJson, http } from '@/shared/api/http'

/**
 * E2 นักศึกษาในกลุ่มเรียน — /api/sections/{sectionId}/enrollments · admin / อาจารย์ผู้สอนกลุ่มนั้น
 * enrolled_at = ตอนเพิ่ม → ลงขาดอัตโนมัติเฉพาะคาบที่เริ่มหลังเพิ่ม · ถอน = soft (ผลเช็คชื่อเก็บไว้)
 *
 * @typedef {object} EnrolledStudent
 * @property {string} studentId
 * @property {string} studentCode
 * @property {string | null} title
 * @property {string} firstName
 * @property {string} lastName
 * @property {string} fullName
 * @property {string} email
 * @property {boolean} accountActive
 * @property {'active' | 'dropped'} status
 * @property {string} enrolledAt        "yyyy-MM-ddTHH:mm:ss" เวลาไทย
 * @property {string | null} droppedAt
 * @property {boolean} faceRegistered
 * @property {'granted' | 'withdrawn' | null} consentStatus
 *
 * @typedef {object} EnrollmentList
 * @property {string} sectionId
 * @property {string} courseCode
 * @property {string} sectionCode
 * @property {boolean} canCreateAccounts  true = ไม่มีบัญชี + ส่งชื่อ-นามสกุล → ระบบสร้างบัญชีให้ (uat เปิด · prod ปิด)
 * @property {number} activeCount
 * @property {EnrolledStudent[]} items
 *
 * @typedef {{ studentCode?: string, email?: string, title?: string, firstName?: string, lastName?: string }} StudentInput
 *
 * @typedef {'insert' | 'reactivate' | 'create' | 'skip' | 'unmatched'} AddAction
 * @typedef {{ rowNo: number, identifier: string, studentId: string | null, studentCode: string | null,
 *   studentName: string | null, email: string | null, action: AddAction, reason: string | null, applied: boolean }} AddRow
 * @typedef {{ dryRun: boolean, sectionId: string, canCreateAccounts: boolean, rows: AddRow[],
 *   summary: { total: number, insert: number, reactivate: number, create: number, skip: number, unmatched: number, applied: number } }} AddResponse
 */

const base = (sectionId) => `/sections/${sectionId}/enrollments`

export const ROSTER_MAX_ITEMS = 500
export const ROSTER_MAX_BYTES = 2 * 1024 * 1024
export const ROSTER_REASON_MAX = 500

/** GET (ไม่กรอง = ทุกสถานะ) @returns {Promise<EnrollmentList>} */
export function listEnrollments(sectionId) {
  return getJson(base(sectionId))
}

/** POST ป้อนเอง · dryRun = ดูผลก่อน @param {{ students: StudentInput[], reason?: string, dryRun: boolean }} body @returns {Promise<AddResponse>} */
export async function addStudents(sectionId, body) {
  const { data } = await http.post(base(sectionId), body)
  return data
}

/** POST /{studentId}/drop @returns {Promise<{ studentId: string, studentCode: string, status: 'dropped', droppedAt: string, keptAttendanceCount: number }>} */
export async function dropStudent(sectionId, studentId, reason) {
  const { data } = await http.post(`${base(sectionId)}/${studentId}/drop`, reason ? { reason } : {})
  return data
}

/** GET /import-template?format=xlsx|csv → ดาวน์โหลด (ชื่อไฟล์จาก Content-Disposition) */
export function downloadRosterTemplate(sectionId, format) {
  return downloadFile(
    `${base(sectionId)}/import-template`,
    { params: { format } },
    `students.${format}`,
  )
}

/**
 * POST /import multipart · dryRun ค่าเริ่มต้นของ backend = true
 * @param {{ file: File, dryRun: boolean, reason?: string }} params @returns {Promise<AddResponse>}
 */
export async function importStudentsFile(sectionId, { file, dryRun, reason }) {
  const form = new FormData()
  form.append('file', file)
  form.append('dryRun', String(dryRun))
  if (reason) form.append('reason', reason)
  const { data } = await http.post(`${base(sectionId)}/import`, form, {
    timeout: UPLOAD_TIMEOUT_MS,
  })
  return data
}

// ---------- ป้ายข้อความ ----------

export const ADD_ACTION_LABEL = {
  insert: 'เพิ่มเข้ากลุ่ม',
  reactivate: 'เพิ่มกลับ (เคยถอน)',
  create: 'สร้างบัญชีใหม่ + เพิ่ม',
  skip: 'ข้าม',
  unmatched: 'เพิ่มไม่ได้',
}

/** @param {boolean} canCreateAccounts */
export function addReasonLabel(reason, canCreateAccounts) {
  switch (reason) {
    case 'already_enrolled':
      return 'อยู่ในกลุ่มแล้ว'
    case 'not_found':
      return canCreateAccounts
        ? 'ไม่พบบัญชี — ใส่ชื่อและนามสกุลเพื่อสร้างบัญชีใหม่'
        : 'ไม่พบบัญชีนักศึกษา — ให้ผู้ดูแลระบบสร้างบัญชีก่อน'
    case 'duplicate':
      return 'ซ้ำกับแถวอื่นในรายการ'
    case 'account_inactive':
      return 'บัญชีถูกปิดใช้งาน'
    case 'email_taken':
      return 'อีเมลนี้เป็นของบัญชีอื่น'
    default:
      return reason ?? ''
  }
}

export const CONSENT_LABEL = {
  granted: { label: 'ยินยอมแล้ว', tone: 'success' },
  withdrawn: { label: 'ถอนความยินยอม', tone: 'danger' },
}

/** ข้อความ toast หลังบันทึก */
export function describeAddResult(r) {
  const s = r.summary
  const parts = [`เพิ่ม ${s.applied} คน`]
  if (s.create) parts.push(`สร้างบัญชีใหม่ ${s.create}`)
  if (s.reactivate) parts.push(`เพิ่มกลับ ${s.reactivate}`)
  if (s.skip) parts.push(`อยู่ในกลุ่มแล้ว ${s.skip}`)
  if (s.unmatched) parts.push(`เพิ่มไม่ได้ ${s.unmatched}`)
  return parts.join(' · ')
}
