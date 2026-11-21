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
