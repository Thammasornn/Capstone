import { getJson } from '@/shared/api/http'

/**
 * Room Monitor (B1–B4) + รายชื่อ/สรุปของคาบที่กำลังเรียน (API 5.7–5.8)
 *
 * @typedef {object} CurrentSession
 * @property {string} sessionId
 * @property {string} courseCode
 * @property {string} courseName
 * @property {string} sectionCode
 * @property {string} startTime
 * @property {string} endTime
 * @property {number} checkedIn
 * @property {number} totalStudents
 *
 * @typedef {object} Room  (B1)
 * @property {string} roomId
 * @property {string} roomCode
 * @property {string} cameraCode
 * @property {'live'|'no_signal'|string} cameraStatus
 * @property {CurrentSession | null} currentSession   null = "Class not started"
 *
 * @typedef {object} StreamInfo  (B2)
 * @property {'whep'|'hls'} protocol
 * @property {string} url
 * @property {string} expiresAt
 */

/** B1 GET /rooms — lecturer เห็นเฉพาะห้องที่มีคาบของตน @returns {Promise<Room[]>} */
export function getRooms() {
  return getJson('/rooms')
}

/** B4 GET /rooms/{roomId} @returns {Promise<Room>} */
export function getRoom(roomId) {
  return getJson(`/rooms/${roomId}`)
}

/** B2 GET /rooms/{roomId}/stream @returns {Promise<StreamInfo>} */
export function getStreamInfo(roomId) {
  return getJson(`/rooms/${roomId}/stream`)
}

/** B3 path ของ SSE — เปิดผ่าน shared/api/sse.js ใน composables/useRoomEvents.js */
export const roomEventsPath = (roomId) => `/rooms/${roomId}/events`

/**
 * สรุปของคาบ (API 5.7) — ใช้ใน Room Live (ฟีเจอร์ห้าม import session-report จึงประกาศซ้ำที่นี่)
 * @returns {Promise<{ sessionId: string, sessionDate: string, totalStudents: number, onTime: number, late: number, absent: number, attendanceRate: number }>}
 */
export function getLiveSummary(sessionId) {
  return getJson(`/class-sessions/${sessionId}/attendance/summary`)
}

/**
 * รายชื่อทั้งคาบ (API 5.8) — source: camera | manual | system | null
 * @returns {Promise<{ sessionId: string, sessionDate: string, items: Array<{ studentId: string, studentCode: string, studentName: string, checkInTime: string | null, checkOutTime: string | null, status: string, source: string | null }> }>}
 */
export function getLiveRoster(sessionId) {
  return getJson(`/class-sessions/${sessionId}/attendance`)
}
