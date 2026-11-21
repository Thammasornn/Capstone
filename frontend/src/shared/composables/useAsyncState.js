import { ref, shallowRef } from 'vue'

/**
 * สถานะของการโหลดข้อมูลจาก API — ทุกหน้าใช้รูปเดียวกัน: loading → error | data
 *
 * @template T
 * @param {(...args: any[]) => Promise<T>} loader
 * @param {{ immediate?: boolean, initial?: T }} [options]
 *   immediate: เรียก loader ทันที (ค่าเริ่มต้น true)
 */
export function useAsyncState(loader, { immediate = true, initial = null } = {}) {
  /** @type {import('vue').ShallowRef<T | null>} */
  const data = shallowRef(initial)
  const loading = ref(false)
  /** @type {import('vue').Ref<import('../api/errors').ApiError | null>} */
  const error = ref(null)
  let requestId = 0

  /** เรียกซ้ำได้ (เช่น ปุ่มลองใหม่ / รีเฟรช) — ผลของคำขอเก่าที่ตอบช้ากว่าจะถูกทิ้ง */
  async function run(...args) {
    const id = ++requestId
    loading.value = true
    error.value = null
    try {
      const result = await loader(...args)
      if (id === requestId) data.value = result
      return result
    } catch (e) {
      if (id === requestId) error.value = /** @type {any} */ (e)
      return null
    } finally {
      if (id === requestId) loading.value = false
    }
  }

  if (immediate) run()

  return { data, loading, error, run }
}
