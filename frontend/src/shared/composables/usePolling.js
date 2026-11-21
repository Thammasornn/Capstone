import { getCurrentScope, onScopeDispose } from 'vue'

/**
 * เรียก fn ซ้ำทุก intervalMs — หยุดเมื่อแท็บถูกซ่อน (เรียกทันทีเมื่อกลับมา) และเมื่อออกจากหน้า
 * ไม่เรียกซ้อน: รอบก่อนยังไม่จบ จะข้ามรอบนี้
 *
 * @param {() => Promise<unknown> | unknown} fn
 * @param {number} intervalMs
 * @param {{ immediate?: boolean }} [options]
 */
export function usePolling(fn, intervalMs, { immediate = false } = {}) {
  /** @type {ReturnType<typeof setInterval> | null} */
  let timer = null
  let running = false

  async function tick() {
    if (running || document.hidden) return
    running = true
    try {
      await fn()
    } finally {
      running = false
    }
  }

  function start() {
    stop()
    timer = setInterval(tick, intervalMs)
  }

  function stop() {
    if (timer) clearInterval(timer)
    timer = null
  }

  function onVisibility() {
    if (!document.hidden) tick()
  }

  document.addEventListener('visibilitychange', onVisibility)
  start()
  if (immediate) tick()

  function dispose() {
    stop()
    document.removeEventListener('visibilitychange', onVisibility)
  }
  if (getCurrentScope()) onScopeDispose(dispose)

  return { start, stop, dispose }
}
