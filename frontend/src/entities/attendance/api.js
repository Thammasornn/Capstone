import { http } from '@/shared/api/http'

/**
 * B8 — อาจารย์เช็คชื่อด้วยมือ (วิธีสำรองสำหรับคนที่ไม่ยินยอม PDPA หรือกล้องจำไม่ได้)
 * ใช้ทั้ง Room Live และ Session Report จึงอยู่ที่ entities (ฟีเจอร์ห้าม import กันเอง)
 *
 * @param {string} sessionId
 * @param {string} studentId
 * @param {{ status: import('./status').AttendanceStatus, checkInTime?: string | null, reason: string }} body  reason บังคับ (Backend 400 ถ้าไม่มี)
 */
export async function setManualAttendance(sessionId, studentId, body) {
  const { data } = await http.put(`/class-sessions/${sessionId}/attendance/${studentId}`, body)
  return data
}

/**
 * ปรับเวลาคาบที่เริ่มแล้ว (คาบเดียว) — ใช้ทั้ง Room Live และ Session Report
 * เวลาเป็น "HH:mm" · reason บังคับ · dryRun: true = ดูผลก่อน ไม่บันทึก (Preview & Confirm)
 * 400 = เวลา / เหตุผลไม่ถูกต้อง · 403 = ไม่ใช่ผู้สอน · 409 = สถานะคาบไม่อนุญาต / เกิน 7 วัน / ห้องชน
 *
 * @typedef {object} SessionTimes
 * @property {string} startTime
 * @property {string} endTime
 * @property {string} checkInOpens
 * @property {string} onTimeUntil
 * @property {string} lateUntil
 * @property {string} checkInCloses
 *
 * @typedef {object} AttendanceChange
 * @property {string} studentId
 * @property {string} studentCode
 * @property {string} studentName
 * @property {'camera'|'manual'|'system'} source
 * @property {string | null} checkInTime
 * @property {import('./status').AttendanceStatus} fromStatus
 * @property {import('./status').AttendanceStatus | null} toStatus  reopen = null (ลบผลขาดของระบบ ให้เช็คชื่อใหม่ได้)
 * @property {'upgrade'|'downgrade'|'reopen'} kind
 * @property {boolean} applied
 *
 * @typedef {object} AdjustResponse
 * @property {string} sessionId
 * @property {boolean} dryRun
 * @property {SessionTimes} before
 * @property {SessionTimes} after
 * @property {AttendanceChange[]} changes
 * @property {{ upgrades: number, downgrades: number, downgradesApplied: boolean, reopened: number, unchanged: number }} summary
 */

/** อาจารย์เข้าสาย → เลื่อนเวลาเริ่ม + ช่วงเช็คชื่อ @param {{ actualStart: string, reason: string, dryRun: boolean, applyDowngrades?: boolean }} body @returns {Promise<AdjustResponse>} */
export async function reanchorSession(sessionId, body) {
  const { data } = await http.post(`/class-sessions/${sessionId}/reanchor`, body)
  return data
}

/** ปรับเวลาจบ (เฉพาะคาบที่กำลังเรียน) @param {{ endTime: string, reason: string, dryRun?: boolean }} body @returns {Promise<AdjustResponse>} */
export async function extendSession(sessionId, body) {
  const { data } = await http.post(`/class-sessions/${sessionId}/extend`, body)
  return data
}

/** ปรับช่วงตรงเวลา / สาย / ปิดเช็คชื่อ @param {{ onTimeUntil: string, lateUntil: string, checkInCloses: string, reason: string, dryRun: boolean, applyDowngrades?: boolean }} body @returns {Promise<AdjustResponse>} */
export async function adjustCheckInWindow(sessionId, body) {
  const { data } = await http.post(`/class-sessions/${sessionId}/check-in-window`, body)
  return data
}
