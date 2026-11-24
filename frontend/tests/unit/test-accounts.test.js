import { describe, expect, it } from 'vitest'
import { groupTestAccounts } from '@/features/auth/test-accounts'

const base = {
  title: null,
  lastName: 'ทดสอบ',
  active: true,
  studentCode: null,
  academicStatus: null,
  faceRegistered: false,
  consentStatus: null,
  needsReconsent: false,
  activeCourses: 0,
  teaching: [],
}
const staff = (over) => ({ ...base, ...over })
const student = (code, over = {}) => ({
  ...base,
  role: 'student',
  email: `${code}@st.sit.kmutt.ac.th`,
  firstName: `น${code.slice(-2)}`,
  studentCode: code,
  academicStatus: 'active',
  activeCourses: 2,
  ...over,
})

describe('บัญชีทดสอบจาก DB (หน้า Login)', () => {
  it('จัดเจ้าหน้าที่ไว้ก่อน แล้วนักศึกษาตามสถานะใบหน้า / ความยินยอม (ไม่แสดงกลุ่มว่าง)', () => {
    const groups = groupTestAccounts([
      staff({ role: 'admin', email: 'admin@x', firstName: 'วิทยา' }),
      staff({
        role: 'lecturer',
        email: 'lec@x',
        title: 'อ.',
        firstName: 'สุภาวดี',
        teaching: ['INT102', 'INT201'],
      }),
      student('66130500013'),
      student('69130500001', { faceRegistered: true, consentStatus: 'granted' }),
      student('69130599002', { consentStatus: 'granted' }),
      student('69130599001', {
        consentStatus: 'granted',
        needsReconsent: true,
        faceRegistered: true,
      }),
      student('68130500005', { consentStatus: 'withdrawn' }),
      student('65130500017', {
        active: false,
        academicStatus: 'graduated',
        faceRegistered: true,
        consentStatus: 'granted',
      }),
    ])

    expect(groups.map((g) => [g.title, g.accounts.map((a) => a.email)])).toEqual([
      ['เจ้าหน้าที่', ['admin@x', 'lec@x']],
      ['นักศึกษา · ยังไม่ลงทะเบียนใบหน้า', ['66130500013@st.sit.kmutt.ac.th']],
      ['นักศึกษา · ยินยอมแล้วแต่ยังไม่มีใบหน้า', ['69130599002@st.sit.kmutt.ac.th']],
      ['นักศึกษา · ต้องยินยอมใหม่', ['69130599001@st.sit.kmutt.ac.th']],
      ['นักศึกษา · ถอนความยินยอมแล้ว', ['68130500005@st.sit.kmutt.ac.th']],
      ['นักศึกษา · ลงทะเบียนใบหน้าแล้ว', ['69130500001@st.sit.kmutt.ac.th']],
      ['นักศึกษา · บัญชีถูกปิด (login ไม่ได้)', ['65130500017@st.sit.kmutt.ac.th']],
    ])
    expect(groups[0].accounts).toEqual([
      { email: 'admin@x', label: 'Admin วิทยา', hint: 'Admin · เห็นทุกห้อง / ทุกวิชา' },
      { email: 'lec@x', label: 'อ.สุภาวดี', hint: 'สอน INT102, INT201' },
    ])
    expect(groups[1].accounts[0]).toEqual({
      email: '66130500013@st.sit.kmutt.ac.th',
      label: 'น13 66130500013',
      hint: '2 วิชาเทอมนี้',
    })
    expect(groups.at(-1).accounts[0].hint).toBe('จบการศึกษา')
  })

  it('กลุ่มที่มีเกิน 8 บัญชีพับไว้', () => {
    const many = Array.from({ length: 9 }, (_, i) =>
      student(`691305000${String(i).padStart(2, '0')}`, { faceRegistered: true }),
    )
    const [registered] = groupTestAccounts(many)
    expect(registered.accounts).toHaveLength(9)
    expect(registered.open).toBe(false)
    expect(groupTestAccounts(many.slice(0, 8))[0].open).toBe(true)
  })
})
