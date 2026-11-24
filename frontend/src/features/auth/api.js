import { http } from '@/shared/api/http'

/**
 * Shape ตามคู่มือ Attendance API v1.0 ข้อ 5.1–5.2
 *
 * @typedef {object} FaceRegistration
 * @property {boolean} registered
 * @property {string | null} registeredAt  เวลาไทยไม่มี timezone
 * @property {number} activeFaces
 *
 * @typedef {object} StudentProfile
 * @property {string} studentId
 * @property {string} studentCode
 * @property {string} faculty
 * @property {string} department
 * @property {number} yearLevel
 * @property {FaceRegistration} faceRegistration
 *
 * @typedef {object} User
 * @property {string} userId
 * @property {string} email
 * @property {string} firstName
 * @property {string} lastName
 * @property {import('@/shared/constants/roles').Role} role
 * @property {StudentProfile | null} student   มีค่าเฉพาะบัญชีนักศึกษา
 * @property {object | null} lecturer          มีค่าเฉพาะบัญชีอาจารย์ (คู่มือยังไม่ระบุ field)
 *
 * @typedef {object} LoginResponse
 * @property {string} accessToken
 * @property {'Bearer'} tokenType
 * @property {number} expiresIn  วินาที (28800 = 8 ชม.)
 * @property {User} user
 */

/**
 * POST /auth/login — ไม่แนบ token และไม่ logout อัตโนมัติเมื่อ 401
 * (401 ที่นี่แปลว่าอีเมลหรือรหัสผ่านผิด ไม่ใช่เซสชันหมดอายุ)
 * @param {{ email: string, password: string }} credentials
 * @returns {Promise<LoginResponse>}
 */
export async function login(credentials) {
  const { data } = await http.post('/auth/login', credentials, {
    skipAuth: true,
    skipAuthRedirect: true,
  })
  return data
}

/** GET /me — โครงสร้างเดียวกับ user ใน /auth/login @returns {Promise<User>} */
export async function getMe() {
  const { data } = await http.get('/me')
  return data
}

/**
 * GET /test-accounts — บัญชีใน DB ให้หน้า Login แสดง (local / uat เท่านั้น · backend ปิดไว้ = 404)
 * ไม่แนบ token และไม่ logout อัตโนมัติ
 * @returns {Promise<import('./test-accounts').DbAccount[]>}
 */
export async function getTestAccounts() {
  const { data } = await http.get('/test-accounts', { skipAuth: true, skipAuthRedirect: true })
  return data
}
