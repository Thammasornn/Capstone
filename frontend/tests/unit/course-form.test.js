import { describe, expect, it } from 'vitest'
import {
  emptySection,
  toPayload,
  validateCourse,
} from '@/features/course-management/composables/useCourseForm'

const validForm = () => ({
  courseCode: 'int101',
  courseName: ' Intro ',
  sections: [{ ...emptySection(), sectionCode: '001', roomId: 'r1', classDays: ['MON'] }],
})

describe('ฟอร์มรายวิชา (B5) — กติกาเดียวกับ Backend', () => {
  it('ฟอร์มที่ถูกต้องไม่มี error', () => {
    expect(validateCourse(validForm())).toEqual({})
  })

  it('รหัสวิชาต้องเป็นตัวอักษร 3 + ตัวเลข 3', () => {
    const form = validForm()
    form.courseCode = 'IT1'
    expect(validateCourse(form).courseCode).toBeTruthy()
  })

  it('ช่วงเช็คชื่อต้องอยู่ในเวลาเรียน และนาทีตรงเวลาไม่เกินช่วงเช็คชื่อ', () => {
    const form = validForm()
    form.sections[0].checkInOpens = '08:30:00'
    expect(validateCourse(form)['s0.checkIn']).toMatch('ในเวลาเรียน')
    form.sections[0].checkInOpens = '09:00:00'
    form.sections[0].onTimeMinutes = 60
    expect(validateCourse(form)['s0.checkIn']).toMatch('0–45')
  })

  it('admin ต้องเลือกผู้สอนให้กลุ่มใหม่', () => {
    expect(validateCourse(validForm(), { requireLecturer: true })['s0.lecturerId']).toBeTruthy()
  })

  it('payload ตัดช่องว่าง + ตัวพิมพ์ใหญ่ และไม่ส่ง sectionId / lecturerId ที่ว่าง', () => {
    const body = toPayload(validForm())
    expect(body.courseCode).toBe('INT101')
    expect(body.courseName).toBe('Intro')
    expect(body.sections[0]).not.toHaveProperty('sectionId')
    expect(body.sections[0]).not.toHaveProperty('lecturerId')
  })
})
