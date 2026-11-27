/**
 * เริ่มคาบ "ตอนนี้" — ตัดสินว่าจะเกิดอะไรขึ้นก่อนกดปุ่ม (StartSessionDialog) และข้อความหลังสำเร็จ
 * 1 กลุ่มเรียนมีได้ 1 คาบต่อวัน (วันที่ตามเวลาไทย Asia/Bangkok):
 *   - วันนี้ไม่มีคาบ / มีคาบที่ยังไม่เริ่ม          → create  (backend สร้างคาบเพิ่ม / ใช้คาบเดิมของวันนี้)
 *   - คาบวันนี้จบแล้ว (completed)                   → reopen  (เปิดแถวเดิมอีกครั้ง · เก็บผลเช็คชื่อ · ลบเฉพาะขาดที่ระบบใส่)
 *   - กำลังเรียนอยู่ห้องอื่น                        → move    (ย้ายมาห้องนี้ · เก็บผลทั้งหมด)
 *   - กำลังเรียนอยู่ห้องนี้ / ยกเลิกแล้วมีคาบชดเชย  → blocked (backend ตอบ 409)
 *   - ยกเลิกไว้ (ไม่มีคาบชดเชย)                     → create  + บอกว่าจะคืนคาบที่ยกเลิก
 * เตือน (ไม่บล็อก): วันนี้ไม่ใช่วันเรียน หรือห่างจากเวลาเรียนตามตารางเกิน OFF_SCHEDULE_MINUTES
 */
import { formatThaiDate, formatTime, timeToMinutes } from '@/shared/utils/datetime'

export const TIME_ZONE = 'Asia/Bangkok'
export const OFF_SCHEDULE_MINUTES = 60

const DAY_CODES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT']
const DAY_LABEL = {
  MON: 'จันทร์',
  TUE: 'อังคาร',
  WED: 'พุธ',
  THU: 'พฤหัสบดี',
  FRI: 'ศุกร์',
  SAT: 'เสาร์',
  SUN: 'อาทิตย์',
}

/** วันที่ / เวลา / วันในสัปดาห์ ตามเวลาไทย (ไม่ขึ้นกับ timezone ของเครื่อง) @param {Date} now */
export function bangkokClock(now = new Date()) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', {
      timeZone: TIME_ZONE,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    })
      .formatToParts(now)
      .map((p) => [p.type, p.value]),
  )
  const date = `${parts.year}-${parts.month}-${parts.day}`
  const [y, m, d] = date.split('-').map(Number)
  return {
    date,
    minutes: Number(parts.hour) * 60 + Number(parts.minute),
    day: DAY_CODES[new Date(Date.UTC(y, m - 1, d)).getUTCDay()],
  }
}

/**
 * @param {{
 *   section: { classDays?: string[], startTime?: string, endTime?: string } | null,
 *   sessions: Array<{ sessionDate: string, sessionStatus: string, startTime: string, endTime: string,
 *     roomId: string, roomCode: string, makeupSessionId?: string | null }>,
 *   roomId: string,
 *   now?: Date,
 * }} input
 * @returns {{ mode: 'create' | 'reopen' | 'move' | 'blocked', date: string, dateLabel: string,
 *   message: string, warning: string, todaySession: object | null }}
 */
export function planStart({ section, sessions, roomId, now = new Date() }) {
  const clock = bangkokClock(now)
  const dateLabel = formatThaiDate(clock.date)
  const today = (sessions ?? []).find((s) => s.sessionDate === clock.date) ?? null
  const span = (s) => `${formatTime(s.startTime)}–${formatTime(s.endTime)}`

  let mode = /** @type {'create' | 'reopen' | 'move' | 'blocked'} */ ('create')
  let message = ''
  if (today?.sessionStatus === 'completed') {
    mode = 'reopen'
    message =
      `วันนี้กลุ่มนี้มีคาบไปแล้ว ${span(today)} (ห้อง ${today.roomCode}) · เปิดคาบนี้อีกครั้งตั้งแต่ตอนนี้? ` +
      'ผลเช็คชื่อเดิมจะถูกเก็บไว้ ส่วนคนที่ระบบบันทึกว่าขาดจะเช็คชื่อได้อีก'
  } else if (today?.sessionStatus === 'in_progress') {
    if (today.roomId === roomId) {
      mode = 'blocked'
      message = `กลุ่มนี้มีคาบกำลังเรียนอยู่ในห้องนี้แล้ว (${span(today)})`
    } else {
      mode = 'move'
      message = `กลุ่มนี้กำลังเรียนอยู่ที่ห้อง ${today.roomCode} (${span(today)}) · ย้ายมาห้องนี้? ผลเช็คชื่อทั้งหมดจะถูกเก็บไว้`
    }
  } else if (today?.sessionStatus === 'cancelled') {
    if (today.makeupSessionId) {
      mode = 'blocked'
      message =
        'คาบวันนี้ถูกยกเลิกและมีคาบชดเชยแล้ว จึงเริ่มคาบวันนี้ไม่ได้ (1 กลุ่มมีได้ 1 คาบต่อวัน)'
    } else {
      message = 'คาบวันนี้ถูกยกเลิกไว้ · เริ่มคาบตอนนี้จะคืนคาบนี้และเปิดเช็คชื่อทันที'
    }
  }

  const warnings = []
  const days = section?.classDays ?? []
  if (days.length && !days.includes(clock.day)) {
    warnings.push(`วันนี้ (${DAY_LABEL[clock.day]}) ไม่ใช่วันเรียนตามตาราง`)
  } else {
    const s = timeToMinutes(section?.startTime)
    const e = timeToMinutes(section?.endTime)
    if (s !== null && e !== null) {
      const gap = clock.minutes < s ? s - clock.minutes : clock.minutes > e ? clock.minutes - e : 0
      if (gap > OFF_SCHEDULE_MINUTES)
        warnings.push(
          `ตอนนี้อยู่นอกเวลาเรียนตามตาราง (${formatTime(section.startTime)}–${formatTime(section.endTime)})`,
        )
    }
  }
  const warning = warnings.length
    ? `${warnings[0]} · คาบนี้จะนับเป็นคาบของวันที่ ${dateLabel} (1 กลุ่มมีได้ 1 คาบต่อวัน)`
    : ''

  return { mode, date: clock.date, dateLabel, message, warning, todaySession: today }
}

/** ปุ่มยืนยันตาม mode */
export const START_BUTTON_LABEL = {
  create: 'เริ่มคาบ',
  reopen: 'เปิดคาบอีกครั้ง',
  move: 'ย้ายมาห้องนี้',
  blocked: 'เริ่มคาบ',
}

/**
 * toast หลังเริ่มคาบสำเร็จ ตาม startMode (ไม่มี field = แบบเดิม)
 * @param {{ startMode?: string, previousRoomCode?: string | null, onTimeUntil: string, lateUntil: string }} info
 */
export function describeStartResult(info) {
  const window = `ตรงเวลาถึง ${formatTime(info.onTimeUntil)} · สายถึง ${formatTime(info.lateUntil)}`
  switch (info.startMode) {
    case 'reopened':
      return `เปิดคาบอีกครั้งแล้ว · ผลเช็คชื่อเดิมเก็บไว้ · ${window}`
    case 'moved':
      return `ย้ายคาบ${info.previousRoomCode ? `จากห้อง ${info.previousRoomCode} ` : ''}มาห้องนี้แล้ว · ผลเช็คชื่อเก็บไว้ทั้งหมด`
    default:
      return `เริ่มคาบแล้ว · ${window}`
  }
}
