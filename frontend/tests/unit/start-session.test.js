import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { HttpResponse, http } from 'msw'
import { server } from '@/mocks/node'
import { apiPath } from '@/mocks/handlers'
import StartSessionDialog from '@/features/room-monitor/components/StartSessionDialog.vue'
import {
  bangkokClock,
  describeStartResult,
  planStart,
} from '@/features/room-monitor/start-session-plan'

// 27 พ.ย. 2569 (ศุกร์) 00:40 เวลาไทย = 26 พ.ย. 17:40 UTC — เหตุการณ์จริงบน uat
const AFTER_MIDNIGHT = new Date('2026-11-26T17:40:00Z')
const ROOM_HERE = 'room-4303'
const ROOM_OTHER = 'room-4301'
/** INT201-001 เรียนวันจันทร์ 13:00–16:00 */
const SECTION = { classDays: ['MON'], startTime: '13:00:00', endTime: '16:00:00' }
const session = (over) => ({
  sessionId: 's1',
  sessionDate: '2026-11-27',
  sessionStatus: 'completed',
  startTime: '00:38',
  endTime: '02:33',
  roomId: ROOM_HERE,
  roomCode: '4303',
  makeupSessionId: null,
  ...over,
})

describe('เริ่มคาบตอนนี้ — วันที่ตามเวลาไทย', () => {
  it('หลังเที่ยงคืนเวลาไทยนับเป็นวันใหม่ ไม่ขึ้นกับ timezone ของเครื่อง', () => {
    expect(bangkokClock(AFTER_MIDNIGHT)).toEqual({ date: '2026-11-27', minutes: 40, day: 'FRI' })
  })
})

describe('เริ่มคาบตอนนี้ — แผนก่อนกดปุ่ม (planStart)', () => {
  const plan = (sessions, section = SECTION, now = AFTER_MIDNIGHT) =>
    planStart({ section, sessions, roomId: ROOM_HERE, now })

  it('วันนี้ไม่มีคาบ → เริ่มคาบใหม่ · แสดงวันที่ที่จะนับเสมอ', () => {
    const p = plan([session({ sessionDate: '2026-11-23' })])
    expect(p.mode).toBe('create')
    expect(p.message).toBe('')
    expect(p.dateLabel).toBe('27 พ.ย. 2569')
  })

  it('คาบวันนี้จบแล้ว → ยืนยัน "เปิดคาบอีกครั้ง" พร้อมเวลาและห้องเดิม', () => {
    const p = plan([session()])
    expect(p.mode).toBe('reopen')
    expect(p.message).toContain('00:38–02:33 (ห้อง 4303)')
    expect(p.message).toContain('ผลเช็คชื่อเดิมจะถูกเก็บไว้')
  })

  it('กำลังเรียนอยู่ห้องอื่น → "ย้ายมาห้องนี้"', () => {
    const p = plan([
      session({ sessionStatus: 'in_progress', roomId: ROOM_OTHER, roomCode: '4301' }),
    ])
    expect(p.mode).toBe('move')
    expect(p.message).toContain('ห้อง 4301')
  })

  it('กำลังเรียนอยู่ห้องนี้ / ยกเลิกแล้วมีคาบชดเชย → ปิดปุ่มเริ่ม', () => {
    expect(plan([session({ sessionStatus: 'in_progress' })]).mode).toBe('blocked')
    expect(plan([session({ sessionStatus: 'cancelled', makeupSessionId: 'mk1' })]).mode).toBe(
      'blocked',
    )
    expect(plan([session({ sessionStatus: 'cancelled' })]).mode).toBe('create')
  })

  it('ไม่ใช่วันเรียน → เตือนสีเหลือง พร้อมวันที่ที่จะนับ (ไม่บล็อก)', () => {
    const p = plan([])
    expect(p.mode).toBe('create')
    expect(p.warning).toContain('ไม่ใช่วันเรียน')
    expect(p.warning).toContain('คาบนี้จะนับเป็นคาบของวันที่ 27 พ.ย. 2569')
  })

  it('วันเรียนแต่ห่างจากเวลาเรียนเกิน 60 นาที → เตือน · ภายใน 60 นาที → ไม่เตือน', () => {
    const friday = { ...SECTION, classDays: ['FRI'] }
    expect(plan([], friday).warning).toContain('นอกเวลาเรียนตามตาราง (13:00–16:00)')
    // 12:10 เวลาไทย (05:10 UTC) = ก่อนเริ่ม 50 นาที
    expect(plan([], friday, new Date('2026-11-27T05:10:00Z')).warning).toBe('')
    // 17:30 เวลาไทย = หลังเลิก 90 นาที
    expect(plan([], friday, new Date('2026-11-27T10:30:00Z')).warning).not.toBe('')
  })
})

describe('เริ่มคาบตอนนี้ — toast ตาม startMode', () => {
  const base = { onTimeUntil: '00:55:00', lateUntil: '01:25:00' }
  it('created / ไม่มี startMode = ข้อความเดิม', () => {
    expect(describeStartResult(base)).toBe('เริ่มคาบแล้ว · ตรงเวลาถึง 00:55 · สายถึง 01:25')
    expect(describeStartResult({ ...base, startMode: 'created' })).toContain('เริ่มคาบแล้ว')
  })
  it('reopened / moved', () => {
    expect(describeStartResult({ ...base, startMode: 'reopened' })).toContain('เปิดคาบอีกครั้งแล้ว')
    expect(describeStartResult({ ...base, startMode: 'moved', previousRoomCode: '4301' })).toBe(
      'ย้ายคาบจากห้อง 4301 มาห้องนี้แล้ว · ผลเช็คชื่อเก็บไว้ทั้งหมด',
    )
  })
})

describe('StartSessionDialog + MSW', () => {
  beforeAll(() => {
    // jsdom ไม่มี <dialog>.showModal / close
    HTMLDialogElement.prototype.showModal ??= function () {
      this.open = true
    }
    HTMLDialogElement.prototype.close ??= function () {
      this.open = false
    }
  })
  beforeEach(() => vi.useFakeTimers({ toFake: ['Date'], now: AFTER_MIDNIGHT }))
  afterEach(() => vi.useRealTimers())

  /** @param {any[]} sessions */
  function useBackend(sessions, startResult) {
    const posted = []
    server.use(
      http.get(apiPath('/courses'), () =>
        HttpResponse.json([
          {
            courseId: 'c1',
            courseCode: 'INT201',
            courseName: 'Web',
            sections: [
              {
                sectionId: 'sec1',
                sectionCode: '001',
                roomId: ROOM_HERE,
                roomCode: '4303',
                ...SECTION,
              },
            ],
          },
        ]),
      ),
      http.get(apiPath('/sections/sec1/sessions'), () =>
        HttpResponse.json({ sectionId: 'sec1', sessions }),
      ),
      http.post(apiPath(`/rooms/${ROOM_HERE}/sessions`), async ({ request }) => {
        posted.push(await request.json())
        return HttpResponse.json(startResult)
      }),
    )
    return posted
  }

  async function openDialog() {
    const wrapper = mount(StartSessionDialog, {
      props: { roomId: ROOM_HERE, roomCode: '4303', open: false },
      attachTo: document.body,
    })
    await wrapper.setProps({ open: true })
    await flushPromises()
    await flushPromises()
    return wrapper
  }
  const submit = (w) => w.findAll('button').find((b) => b.attributes('type') === 'submit')

  it('คาบวันนี้จบแล้ว: ปุ่ม "เปิดคาบอีกครั้ง" + เตือนนอกวันเรียน → ส่งคำขอเดิม แล้ว emit ผล reopened', async () => {
    const posted = useBackend([session()], {
      sessionId: 's1',
      startMode: 'reopened',
      onTimeUntil: '00:55:00',
      lateUntil: '01:25:00',
    })
    const w = await openDialog()
    expect(w.text()).toContain('นับเป็นคาบของวันที่ 27 พ.ย. 2569')
    expect(w.text()).toContain('วันนี้กลุ่มนี้มีคาบไปแล้ว 00:38–02:33 (ห้อง 4303)')
    expect(w.find('[role="alert"]').text()).toContain('ไม่ใช่วันเรียน')
    expect(submit(w).text()).toBe('เปิดคาบอีกครั้ง')

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(posted).toEqual([{ sectionId: 'sec1' }])
    expect(w.emitted('started')[0][0].startMode).toBe('reopened')
    w.unmount()
  })

  it('กำลังเรียนห้องอื่น: ปุ่ม "ย้ายมาห้องนี้"', async () => {
    useBackend(
      [session({ sessionStatus: 'in_progress', roomId: ROOM_OTHER, roomCode: '4301' })],
      {},
    )
    const w = await openDialog()
    expect(w.text()).toContain('กลุ่มนี้กำลังเรียนอยู่ที่ห้อง 4301')
    expect(submit(w).text()).toBe('ย้ายมาห้องนี้')
    w.unmount()
  })

  it('กำลังเรียนห้องนี้: ปิดปุ่มเริ่ม', async () => {
    useBackend([session({ sessionStatus: 'in_progress' })], {})
    const w = await openDialog()
    expect(w.text()).toContain('มีคาบกำลังเรียนอยู่ในห้องนี้แล้ว')
    expect(submit(w).attributes('disabled')).toBeDefined()
    w.unmount()
  })
})
