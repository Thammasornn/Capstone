import { getJson } from '@/shared/api/http'

/**
 * @typedef {object} SessionSummary  (API 5.7 — ไม่กรองเวลา: คาบอนาคตทุกคนเป็น absent → หน้าจอต้องเตือน/ซ่อน)
 * @property {string} sessionId
 * @property {string} sectionId
 * @property {string} sectionCode
 * @property {string} courseId
 * @property {string} sessionDate
 * @property {string} startTime
 * @property {string} endTime
 * @property {string} sessionStatus
 * @property {number} totalStudents
 * @property {number} onTime
 * @property {number} late
 * @property {number} absent
 * @property {number} attendanceRate
 *
 * @typedef {object} RosterRow  (API 5.8)
 * @property {string} studentId
 * @property {string} studentCode
 * @property {string} studentName
 * @property {string | null} checkInTime
 * @property {string | null} checkOutTime
 * @property {import('@/entities/attendance/status').AttendanceStatus} status
 * @property {'camera'|'manual'|null} [source]  ขอเพิ่มใน B8
 */

/** @returns {Promise<SessionSummary>} */
export function getSessionSummary(sessionId) {
  return getJson(`/class-sessions/${sessionId}/attendance/summary`)
}

/**
 * @param {string} sessionId
 * @param {import('@/entities/attendance/status').AttendanceStatus | null} [status]
 * @returns {Promise<{ sessionId: string, sessionDate: string, items: RosterRow[] }>}
 */
export function getSessionAttendance(sessionId, status = null) {
  return getJson(`/class-sessions/${sessionId}/attendance`, { params: status ? { status } : {} })
}
