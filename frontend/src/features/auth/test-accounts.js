/**
 * บัญชีทดสอบที่หน้า Login แสดงให้กดเลือก (ไม่แสดงใน Production)
 *  - mode 'mock': โหมด VITE_MOCK_MODE=all (MSW · คู่มือ API ข้อ 8) — รายชื่อคงที่ด้านล่าง
 *  - mode 'db':   Backend จริง (local / uat) — โหลดรายชื่อจาก DB ผ่าน GET /test-accounts แล้วจัดกลุ่มด้วย groupTestAccounts()
 *                 backend ต้องเปิด app.test-accounts.enabled (uat: TEST_ACCOUNTS_ENABLED=true ใน overlay)
 *
 * ⚠️ ข้อมูลอยู่หลังเงื่อนไขจาก import.meta.env (Vite แทนค่าตอน build) — Production build จะตัดทิ้งทั้งก้อน
 *    อย่าย้ายรายชื่อ/รหัสผ่านออกมาเป็นค่าคงที่นอกเงื่อนไข ไม่งั้นจะติดไปใน bundle ของ Production
 *
 * @typedef {{ email: string, label: string, hint?: string }} TestAccount
 * @typedef {{ title: string, accounts: TestAccount[], open: boolean }} TestAccountGroup
 * @typedef {{ mode: 'mock', password: string, groups: TestAccountGroup[] } | { mode: 'db', password: string }} TestAccounts
 *
 * @typedef {object} DbAccount  แถวจาก GET /test-accounts
 * @property {string} email
 * @property {'admin' | 'lecturer' | 'student'} role
 * @property {string | null} title
 * @property {string} firstName
 * @property {string} lastName
 * @property {boolean} active
 * @property {string | null} studentCode
 * @property {string | null} academicStatus
 * @property {boolean} faceRegistered
 * @property {'granted' | 'withdrawn' | null} consentStatus
 * @property {boolean} needsReconsent
 * @property {number} activeCourses
 * @property {string[]} teaching
 */

const MOCK_MODE = import.meta.env.VITE_MOCK_MODE === 'all'
const SHOW_DB = !MOCK_MODE && import.meta.env.VITE_SHOW_TEST_ACCOUNTS === 'true'

const mockAccounts = () => [
  {
    title: 'บัญชี mock',
    open: true,
    accounts: [
      { label: 'นักศึกษา (ลงทะเบียนใบหน้าแล้ว)', email: 'student01@mock.test' },
      { label: 'นักศึกษา (ยังไม่ลงทะเบียน)', email: 'student08@mock.test' },
      { label: 'อาจารย์', email: 'lecturer01@mock.test' },
      { label: 'Admin', email: 'admin@mock.test' },
    ],
  },
]

/** @type {TestAccounts | null} null = ไม่แสดงบัญชีทดสอบ (Production) */
export const TEST_ACCOUNTS = MOCK_MODE
  ? { mode: 'mock', password: 'Passw0rd!', groups: mockAccounts() }
  : SHOW_DB
    ? // รหัสผ่านของบัญชีใน backend/db/02_seed_mock.sql และ 04 (บัญชีที่เพิ่มเองอาจไม่ใช่ค่านี้)
      { mode: 'db', password: 'Password@123' }
    : null

/** กลุ่มนักศึกษาตามลำดับที่แสดง · กลุ่มที่สมาชิกเกินนี้จะพับไว้ */
const STUDENT_GROUPS = [
  ['none', 'ยังไม่ลงทะเบียนใบหน้า'],
  ['granted', 'ยินยอมแล้วแต่ยังไม่มีใบหน้า'],
  ['reconsent', 'ต้องยินยอมใหม่'],
  ['withdrawn', 'ถอนความยินยอมแล้ว'],
  ['registered', 'ลงทะเบียนใบหน้าแล้ว'],
  ['closed', 'บัญชีถูกปิด (login ไม่ได้)'],
]
const OPEN_MAX = 8

/** @param {DbAccount} a */
function studentState(a) {
  if (!a.active) return 'closed'
  if (a.consentStatus === 'withdrawn') return 'withdrawn'
  if (a.needsReconsent) return 'reconsent'
  if (a.faceRegistered) return 'registered'
  if (a.consentStatus === 'granted') return 'granted'
  return 'none'
}

/** @param {DbAccount} a @returns {TestAccount} */
function staffAccount(a) {
  const hint =
    a.role === 'admin'
      ? 'Admin · เห็นทุกห้อง / ทุกวิชา'
      : a.teaching.length
        ? `สอน ${a.teaching.join(', ')}`
        : 'ไม่มีวิชาเทอมนี้'
  const label = a.role === 'admin' ? `Admin ${a.firstName}` : `${a.title ?? ''}${a.firstName}`
  return { email: a.email, label: a.active ? label : `${label} (login ไม่ได้)`, hint }
}

/** @param {DbAccount} a @returns {TestAccount} */
function studentAccount(a) {
  const closed = { graduated: 'จบการศึกษา', withdrawn: 'ลาออก' }[a.academicStatus]
  const hint = !a.active
    ? (closed ?? 'บัญชีถูกปิด')
    : a.activeCourses
      ? `${a.activeCourses} วิชาเทอมนี้`
      : 'ไม่มีวิชาเทอมนี้'
  return { email: a.email, label: `${a.firstName} ${a.studentCode ?? ''}`.trim(), hint }
}

/**
 * จัดกลุ่มบัญชีจาก DB สำหรับหน้า Login: เจ้าหน้าที่ (admin + อาจารย์) แล้วนักศึกษาตามสถานะใบหน้า / ความยินยอม
 * กลุ่มว่างไม่แสดง · กลุ่มใหญ่ (> 8 บัญชี) พับไว้
 * @param {DbAccount[]} rows
 * @returns {TestAccountGroup[]}
 */
export function groupTestAccounts(rows) {
  const staff = rows.filter((a) => a.role !== 'student').map(staffAccount)
  const students = rows.filter((a) => a.role === 'student')
  const groups = staff.length ? [{ title: 'เจ้าหน้าที่', accounts: staff, open: true }] : []
  for (const [state, title] of STUDENT_GROUPS) {
    const accounts = students.filter((a) => studentState(a) === state).map(studentAccount)
    if (accounts.length) {
      groups.push({ title: `นักศึกษา · ${title}`, accounts, open: accounts.length <= OPEN_MAX })
    }
  }
  return groups
}
