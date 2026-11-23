import { computed, onScopeDispose, ref } from 'vue'
import { useAuthStore } from '@/features/auth/store'
import { registerFace } from '../api'
import { prepareImage } from '../prepare-image'
import {
  ALLOWED_TYPES,
  MAX_FILE_BYTES,
  MAX_PHOTOS,
  MAX_TOTAL_BYTES,
  MIN_PHOTOS,
} from '../constants'

/**
 * ขั้นตอนลงทะเบียนใบหน้า: ยินยอม → รูป 3–5 รูป → ส่ง (API 5.10, รอ AI ≥ 30 วินาที)
 * error 422 จาก AI (เช่น ไม่พบใบหน้า / เบลอ) → ให้เลือกรูปใหม่ · 502 → ลองใหม่ภายหลัง
 */
export function useFaceRegistration() {
  const auth = useAuthStore()
  const step = ref(/** @type {'consent'|'photos'|'done'} */ ('consent'))
  /** @type {import('vue').Ref<{ id: number, file: File, url: string, label: string }[]>} */
  const photos = ref([])
  const submitting = ref(false)
  const progress = ref(0)
  const error = ref('')
  const result = ref(/** @type {any} */ (null))
  let nextId = 1

  const canSubmit = computed(() => photos.value.length >= MIN_PHOTOS && !submitting.value)

  const preparing = ref(false)

  /**
   * รับรูปจากกล้อง / เครื่อง → ย่อ + แปลงเป็น JPEG (prepare-image.js) แล้วตรวจกติกา API 5.10
   * @param {File[]} files @param {string | ((index: number) => string)} [label] ชื่อมุม (หรือฟังก์ชันจากลำดับรูป)
   */
  async function addFiles(files, label = '') {
    error.value = ''
    preparing.value = true
    try {
      for (const original of files) {
        if (photos.value.length >= MAX_PHOTOS) {
          error.value = `เลือกได้สูงสุด ${MAX_PHOTOS} รูป`
          break
        }
        let file
        try {
          file = await prepareImage(original)
        } catch (e) {
          error.value = e.message
          continue
        }
        if (!ALLOWED_TYPES.includes(file.type) || file.size > MAX_FILE_BYTES) {
          error.value = `${original.name}: รูปใหญ่เกิน 5MB หลังย่อแล้ว — เลือกรูปอื่น`
          continue
        }
        const name = typeof label === 'function' ? label(photos.value.length) : label
        if (!pushPhoto(file, name)) break
      }
    } finally {
      preparing.value = false
    }
  }

  /** @returns {boolean} false = เกินขนาดรวม */
  function pushPhoto(file, label) {
    const total = photos.value.reduce((sum, p) => sum + p.file.size, 0) + file.size
    if (total > MAX_TOTAL_BYTES) {
      error.value = 'ขนาดรูปรวมเกิน 25MB'
      return false
    }
    photos.value.push({ id: nextId++, file, url: URL.createObjectURL(file), label })
    return true
  }

  function removePhoto(id) {
    const p = photos.value.find((x) => x.id === id)
    if (p) URL.revokeObjectURL(p.url)
    photos.value = photos.value.filter((x) => x.id !== id)
  }

  function clearPhotos() {
    photos.value.forEach((p) => URL.revokeObjectURL(p.url))
    photos.value = []
  }

  async function submit() {
    if (!auth.studentId || !canSubmit.value) return
    submitting.value = true
    progress.value = 0
    error.value = ''
    try {
      result.value = await registerFace(
        auth.studentId,
        photos.value.map((p) => p.file),
        {
          onUploadProgress: (e) =>
            (progress.value = e.total ? Math.round((e.loaded / e.total) * 100) : 0),
        },
      )
      await auth.refreshMe().catch(() => {})
      clearPhotos()
      step.value = 'done'
    } catch (e) {
      error.value = e?.message ?? 'ลงทะเบียนไม่สำเร็จ'
    } finally {
      submitting.value = false
    }
  }

  onScopeDispose(clearPhotos)

  return {
    step,
    photos,
    submitting,
    preparing,
    progress,
    error,
    result,
    canSubmit,
    addFiles,
    removePhoto,
    clearPhotos,
    submit,
  }
}
