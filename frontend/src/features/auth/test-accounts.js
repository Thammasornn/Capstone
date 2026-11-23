/**
 * บัญชีทดสอบที่หน้า Login แสดงให้กดเลือก (ไม่แสดงใน Production)
 *  - MOCK_ACCOUNTS: โหมด VITE_MOCK_MODE=all (MSW · คู่มือ API ข้อ 8)
 *  - SEED_ACCOUNTS: Backend จริง + ข้อมูล mock จาก backend/db/02_seed_mock.sql (local / uat)
 * ต้องตรงกับตาราง "บัญชีทดสอบ" ใน README.md ที่ root และ backend/db/README.md
 *
 * ⚠️ ข้อมูลอยู่หลังเงื่อนไขจาก import.meta.env (Vite แทนค่าตอน build) — Production build จะตัดทิ้งทั้งก้อน
 *    อย่าย้ายรายชื่อ/รหัสผ่านออกมาเป็นค่าคงที่นอกเงื่อนไข ไม่งั้นจะติดไปใน bundle ของ Production
 *
 * @typedef {{ email: string, label: string, hint?: string }} TestAccount
 * @typedef {{ title: string, accounts: TestAccount[] }} TestAccountGroup
 * @typedef {{ mode: 'mock' | 'seed', password: string, groups: TestAccountGroup[] }} TestAccounts
 */

const MOCK_MODE = import.meta.env.VITE_MOCK_MODE === 'all'
const SHOW_SEED = !MOCK_MODE && import.meta.env.VITE_SHOW_TEST_ACCOUNTS === 'true'

const mockAccounts = () => [
  {
    title: 'บัญชี mock',
    accounts: [
      { label: 'นักศึกษา (ลงทะเบียนใบหน้าแล้ว)', email: 'student01@mock.test' },
      { label: 'นักศึกษา (ยังไม่ลงทะเบียน)', email: 'student08@mock.test' },
      { label: 'อาจารย์', email: 'lecturer01@mock.test' },
      { label: 'Admin', email: 'admin@mock.test' },
    ],
  },
]

const st = (code) => `${code}@st.sit.kmutt.ac.th`

const seedAccounts = () => [
  {
    title: 'เจ้าหน้าที่',
    accounts: [
      { label: 'Admin', email: 'admin@sit.kmutt.ac.th', hint: 'เห็นทุกห้อง / ทุกวิชา' },
      {
        label: 'อาจารย์ สุภาวดี',
        email: 'supawadee.c@sit.kmutt.ac.th',
        hint: 'INT102, INT201 (มีคาบเดโมกำลังเรียนในห้อง 4302)',
      },
      {
        label: 'อาจารย์ ธนากร',
        email: 'thanakorn.w@sit.kmutt.ac.th',
        hint: 'INT101 สองกลุ่ม + ผู้สอนร่วม INT301',
      },
    ],
  },
  {
    title: 'นักศึกษา',
    accounts: [
      {
        label: 'ลงทะเบียนใบหน้าแล้ว',
        email: st('69130500001'),
        hint: 'ปี 1 · 3 วิชา · เข้าเรียนครบ',
      },
      {
        label: 'ยังไม่เคยลงทะเบียนใบหน้า',
        email: st('69130500011'),
        hint: 'อาจารย์เช็คชื่อด้วยมือ',
      },
      { label: 'ถอนความยินยอมแล้ว', email: st('68130500005'), hint: 'ข้อมูลใบหน้าถูกลบแล้ว' },
      {
        label: 'ต้องยินยอมใหม่',
        email: st('69130599001'),
        hint: 'ยินยอมข้อความฉบับเก่า (v0)',
      },
      {
        label: 'ยินยอมแล้วแต่ยังไม่มีใบหน้า',
        email: st('69130599002'),
        hint: 'ส่งรูปไม่สำเร็จ',
      },
      { label: 'อายุไม่ถึง 20 ปี', email: st('69130599003'), hint: 'B9 · ยังไม่ได้ยินยอม' },
      { label: 'กลุ่มเสี่ยงสูง', email: st('69130500004'), hint: 'ขาด 3–5 ครั้งต่อวิชา' },
      { label: 'กลุ่มเสี่ยงปานกลาง', email: st('69130500009'), hint: 'ขาด 2 ครั้งต่อวิชา' },
      { label: 'ถอนรายวิชา', email: st('68130500012'), hint: 'ถอน INT201 เหลือ 1 วิชา' },
      { label: 'ไม่มีวิชาเทอมนี้', email: st('66130599004'), hint: 'ปี 4 ฝึกงาน' },
      {
        label: 'จบการศึกษา (login ไม่ได้)',
        email: st('65130500017'),
        hint: 'บัญชีถูกปิด',
      },
      { label: 'ลาออก (login ไม่ได้)', email: st('66130599005'), hint: 'บัญชีถูกปิด' },
    ],
  },
  {
    title: 'สมาชิกทีม (ยังไม่ลงทะเบียนใบหน้า · INT201)',
    accounts: [
      { label: 'จักรพงษ์', email: st('66130500013'), hint: 'ลงทะเบียนใบหน้าด้วยรูปตัวเอง' },
      { label: 'ทัตพล', email: st('66130500036'), hint: 'ลงทะเบียนใบหน้าด้วยรูปตัวเอง' },
      { label: 'ธรรมสรณ์', email: st('66130500041'), hint: 'ลงทะเบียนใบหน้าด้วยรูปตัวเอง' },
    ],
  },
]

/** @type {TestAccounts | null} null = ไม่แสดงบัญชีทดสอบ (Production) */
export const TEST_ACCOUNTS = MOCK_MODE
  ? { mode: 'mock', password: 'Passw0rd!', groups: mockAccounts() }
  : SHOW_SEED
    ? { mode: 'seed', password: 'Password@123', groups: seedAccounts() }
    : null
