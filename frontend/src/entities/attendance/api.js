import { http } from '@/shared/api/http'

/**
 * B8 — อาจารย์เช็คชื่อด้วยมือ (วิธีสำรองสำหรับคนที่ไม่ยินยอม PDPA หรือกล้องจำไม่ได้)
 * ⚠️ ยังไม่มีใน API v1.0 — ใช้ mock จนกว่า Backend จะเสร็จ
 * ใช้ทั้ง Room Live และ Session Report จึงอยู่ที่ entities (ฟีเจอร์ห้าม import กันเอง)
 *
 * @param {string} sessionId
 * @param {string} studentId
 * @param {{ status: import('./status').AttendanceStatus, checkInTime?: string | null, reason: string }} body
 */
export async function setManualAttendance(sessionId, studentId, body) {
  const { data } = await http.put(`/class-sessions/${sessionId}/attendance/${studentId}`, body)
  return data
}
