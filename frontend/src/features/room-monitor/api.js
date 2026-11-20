import { getJson } from '@/shared/api/http'

/**
 * ⚠️ ทุก endpoint ในไฟล์นี้ยังไม่มีใน API v1.0 — ใช้ mock (B1–B4) ตาม shape ในเอกสารแจ้ง Back-End
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
 * ปุ่ม "Check in now" — รอคำตอบ Q2 (สั่ง AI สแกนใหม่ หรือเปิดหน้าเช็คชื่อด้วยมือ B8)
 * TODO(Q2): ใส่ endpoint เมื่อ Backend ตอบ
 */
export function checkInNow() {
  return Promise.reject(new Error('ยังไม่รองรับ: รอคำตอบ Q2 จากทีม Back-End'))
}
