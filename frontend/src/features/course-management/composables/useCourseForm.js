import { computed, reactive, ref } from 'vue'
import { durationMinutes, timeToMinutes } from '@/shared/utils/datetime'
import { createCourse, getCourse, updateCourse } from '../api'

const COURSE_CODE = /^[A-Z]{3}[0-9]{3}$/

/** กลุ่มเรียนใหม่ (ค่าเริ่มต้นที่พบบ่อย: 3 ชั่วโมง เช็คชื่อ 30 นาทีแรก) */
export function emptySection() {
  return {
    sectionId: null,
    sectionCode: '',
    roomId: null,
    lecturerId: null,
    classDays: [],
    startTime: '09:00:00',
    endTime: '12:00:00',
    checkInOpens: '09:00:00',
    checkInCloses: '09:30:00',
    onTimeMinutes: 15,
    cameraAttendanceEnabled: true,
  }
}

/**
 * ตรวจฟอร์มก่อนส่ง (กติกาเดียวกับ Backend — Backend ตรวจซ้ำและตรวจห้องชนอีกชั้น)
 * @returns {Record<string, string>} key: 'courseCode' | 'courseName' | 'sections' | `s{i}.<field>`
 */
export function validateCourse(form, { requireLecturer = false } = {}) {
  /** @type {Record<string, string>} */
  const errors = {}
  const code = form.courseCode.trim().toUpperCase()
  if (!code) errors.courseCode = 'กรุณากรอกรหัสวิชา'
  else if (!COURSE_CODE.test(code)) errors.courseCode = 'ตัวอักษร 3 ตัว + ตัวเลข 3 ตัว เช่น INT101'
  if (!form.courseName.trim()) errors.courseName = 'กรุณากรอกชื่อรายวิชา'
  if (!form.sections.length) errors.sections = 'ต้องมีอย่างน้อย 1 กลุ่มเรียน'

  const codes = new Set()
  form.sections.forEach((s, i) => {
    const k = (f) => `s${i}.${f}`
    const sc = s.sectionCode.trim()
    if (!sc) errors[k('sectionCode')] = 'กรุณากรอกรหัสกลุ่ม'
    else if (codes.has(sc)) errors[k('sectionCode')] = 'รหัสกลุ่มซ้ำกัน'
    codes.add(sc)
    if (!s.roomId) errors[k('roomId')] = 'กรุณาเลือกห้อง'
    if (requireLecturer && !s.sectionId && !s.lecturerId)
      errors[k('lecturerId')] = 'กรุณาเลือกผู้สอน'
    if (!s.classDays.length) errors[k('classDays')] = 'กรุณาเลือกวันเรียน'
    if (durationMinutes(s.startTime, s.endTime) === null)
      errors[k('time')] = 'เวลาเลิกเรียนต้องหลังเวลาเริ่ม'
    const o = timeToMinutes(s.checkInOpens)
    const c = timeToMinutes(s.checkInCloses)
    if (o === null || c === null || c <= o) errors[k('checkIn')] = 'ช่วงเช็คชื่อไม่ถูกต้อง'
    else if (o < timeToMinutes(s.startTime) || c > timeToMinutes(s.endTime)) {
      errors[k('checkIn')] = 'ช่วงเช็คชื่อต้องอยู่ในเวลาเรียน'
    } else {
      const n = Number(s.onTimeMinutes)
      if (!Number.isInteger(n) || n < 0 || n > c - o) {
        errors[k('checkIn')] = `นับว่าตรงเวลาได้ 0–${c - o} นาที`
      }
    }
  })
  return errors
}

/** แปลงฟอร์ม → body ของ POST / PUT /courses */
export function toPayload(form) {
  return {
    courseCode: form.courseCode.trim().toUpperCase(),
    courseName: form.courseName.trim(),
    sections: form.sections.map((s) => ({
      ...(s.sectionId ? { sectionId: s.sectionId } : {}),
      sectionCode: s.sectionCode.trim(),
      roomId: s.roomId,
      ...(s.lecturerId ? { lecturerId: s.lecturerId } : {}),
      classDays: s.classDays,
      startTime: s.startTime,
      endTime: s.endTime,
      checkInOpens: s.checkInOpens,
      checkInCloses: s.checkInCloses,
      onTimeMinutes: Number(s.onTimeMinutes),
      cameraAttendanceEnabled: s.cameraAttendanceEnabled,
    })),
  }
}

/**
 * ฟอร์มเพิ่ม / แก้ไขรายวิชา (B5)
 * @param {string | null} courseId null = เพิ่มใหม่
 * @param {{ requireLecturer: boolean }} options requireLecturer = admin ต้องเลือกผู้สอนให้กลุ่มใหม่
 */
export function useCourseForm(courseId, options) {
  const form = reactive({ courseCode: '', courseName: '', sections: [emptySection()] })
  const errors = ref(/** @type {Record<string, string>} */ ({}))
  const loading = ref(Boolean(courseId))
  const saving = ref(false)
  const serverError = ref('')
  const loadError = ref(/** @type {any} */ (null))
  const isEdit = computed(() => Boolean(courseId))

  async function load() {
    if (!courseId) return
    loading.value = true
    loadError.value = null
    try {
      const c = await getCourse(courseId)
      form.courseCode = c.courseCode
      form.courseName = c.courseName
      form.sections = c.sections.map((s) => ({
        ...emptySection(),
        ...s,
        lecturerId: null, // ไม่ส่ง = ไม่เปลี่ยนผู้สอน
        currentLecturerName: s.lecturerName,
      }))
    } catch (e) {
      loadError.value = e
    } finally {
      loading.value = false
    }
  }

  function addSection() {
    const last = form.sections[form.sections.length - 1]
    form.sections.push({
      ...emptySection(),
      ...(last
        ? {
            startTime: last.startTime,
            endTime: last.endTime,
            checkInOpens: last.checkInOpens,
            checkInCloses: last.checkInCloses,
          }
        : {}),
      sectionCode: String(form.sections.length + 1).padStart(3, '0'),
    })
  }

  function removeSection(index) {
    form.sections.splice(index, 1)
  }

  /** @returns {Promise<object | null>} รายวิชาที่บันทึกแล้ว หรือ null ถ้าไม่ผ่าน */
  async function submit() {
    errors.value = validateCourse(form, options)
    serverError.value = ''
    if (Object.keys(errors.value).length) return null
    saving.value = true
    try {
      const body = toPayload(form)
      return courseId ? await updateCourse(courseId, body) : await createCourse(body)
    } catch (e) {
      serverError.value = e?.message ?? 'บันทึกไม่สำเร็จ'
      return null
    } finally {
      saving.value = false
    }
  }

  load()
  return {
    form,
    errors,
    loading,
    saving,
    serverError,
    loadError,
    isEdit,
    addSection,
    removeSection,
    submit,
    load,
  }
}
