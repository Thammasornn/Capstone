import { readonly, ref } from 'vue'

/**
 * Toast กลางของแอป — App.vue วาง <ToastHost/> ไว้ที่เดียว ฟีเจอร์เรียก useToast().show(...)
 * @typedef {{ id: number, message: string, tone: 'success'|'danger'|'info' }} Toast
 */

/** @type {import('vue').Ref<Toast[]>} */
const toasts = ref([])
let nextId = 1

export function useToast() {
  /** @param {string} message @param {{ tone?: Toast['tone'], durationMs?: number }} [options] */
  function show(message, { tone = 'info', durationMs = 4000 } = {}) {
    const id = nextId++
    toasts.value = [...toasts.value, { id, message, tone }]
    setTimeout(() => dismiss(id), durationMs)
    return id
  }

  /** @param {number} id */
  function dismiss(id) {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }

  return { toasts: readonly(toasts), show, dismiss }
}
