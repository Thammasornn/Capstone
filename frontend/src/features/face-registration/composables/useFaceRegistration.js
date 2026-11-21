import { computed, onScopeDispose, ref } from 'vue'
import { useAuthStore } from '@/features/auth/store'
import { registerFace } from '../api'
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
  const ageConfirmed = ref(false)
  /** @type {import('vue').Ref<{ id: number, file: File, url: string, label: string }[]>} */
  const photos = ref([])
  const submitting = ref(false)
  const progress = ref(0)
  const error = ref('')
  const result = ref(/** @type {any} */ (null))
  let nextId = 1

  const canSubmit = computed(() => photos.value.length >= MIN_PHOTOS && !submitting.value)

  /** @param {File} file @returns {string} ข้อความ error หรือ '' */
  function checkFile(file) {
    if (!ALLOWED_TYPES.includes(file.type)) return `${file.name}: ต้องเป็นไฟล์ JPEG หรือ PNG`
    if (file.size > MAX_FILE_BYTES) return `${file.name}: ไฟล์ใหญ่เกิน 5MB`
    return ''
  }

  /** @param {File[]} files @param {string} [label] */
  function addFiles(files, label = '') {
    error.value = ''
    for (const file of files) {
      if (photos.value.length >= MAX_PHOTOS) {
        error.value = `เลือกได้สูงสุด ${MAX_PHOTOS} รูป`
        break
      }
      const problem = checkFile(file)
      if (problem) {
        error.value = problem
        continue
      }
      const total = photos.value.reduce((sum, p) => sum + p.file.size, 0) + file.size
      if (total > MAX_TOTAL_BYTES) {
        error.value = 'ขนาดรูปรวมเกิน 25MB'
        break
      }
      photos.value.push({ id: nextId++, file, url: URL.createObjectURL(file), label })
    }
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
          ageConfirmed: ageConfirmed.value,
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
    ageConfirmed,
    photos,
    submitting,
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
