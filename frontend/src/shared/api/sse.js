import { getCurrentScope, onScopeDispose, readonly, ref } from 'vue'

/**
 * Server-Sent Events (SSE) กลางของแอป — ใช้กับ B3 `GET /rooms/{roomId}/events`
 *
 * - EventSource ใส่ header Authorization ไม่ได้ → ตอนนี้ส่ง token ทาง query (`access_token`)
 *   ⚠️ รอ Backend ตอบ Q3 (token อายุสั้นทาง query หรือ cookie) แล้วเปลี่ยนที่ configureSse() จุดเดียว
 * - เปิดใน setup() / effect scope แล้วจะปิดให้อัตโนมัติเมื่อออกจากหน้า (onScopeDispose)
 * - EventSource reconnect เองเมื่อเน็ตหลุด ถ้า server ปิดถาวร (readyState CLOSED) จะลองใหม่แบบ backoff
 */

const hooks = {
  /** @type {() => string | null} */
  getToken: () => null,
  /** ชื่อ query param ที่ใช้ส่ง token — null = ไม่ส่ง (เช่น เมื่อ Backend ใช้ cookie) */
  tokenParam: /** @type {string | null} */ ('access_token'),
  withCredentials: false,
}

/**
 * @param {object} options
 * @param {() => string | null} [options.getToken]
 * @param {string | null} [options.tokenParam]
 * @param {boolean} [options.withCredentials]
 */
export function configureSse(options) {
  Object.assign(hooks, options)
}

/**
 * ต่อ path เข้ากับ VITE_API_BASE_URL (รองรับทั้ง URL เต็มและ path แบบ /uat/api)
 * @param {string} path เช่น `/rooms/${roomId}/events`
 * @param {Record<string, string | number | boolean | null | undefined>} [params]
 */
export function buildStreamUrl(path, params = {}) {
  const base = String(import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
  const url = new URL(`${base}${path}`, window.location.origin)
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined) url.searchParams.set(key, String(value))
  }
  const token = hooks.getToken()
  if (token && hooks.tokenParam) url.searchParams.set(hooks.tokenParam, token)
  return url.toString()
}

/** @param {string} raw */
function parseData(raw) {
  try {
    return JSON.parse(raw)
  } catch {
    return raw
  }
}

/**
 * @typedef {'connecting'|'open'|'reconnecting'|'closed'|'failed'} StreamStatus
 */

/**
 * เปิด EventSource พร้อมจัดการ reconnect และปิดเมื่อ unmount
 *
 * @param {string} path
 * @param {object} options
 * @param {Record<string, (data: any, event: MessageEvent) => void>} options.handlers  ชื่อ event → handler (data parse JSON แล้ว)
 * @param {Record<string, any>} [options.params]
 * @param {number} [options.maxRetries=5]
 * @param {number} [options.retryDelayMs=2000]
 * @param {typeof EventSource} [options.EventSourceImpl]  ให้ test ใส่ตัวปลอมได้
 * @returns {{ status: Readonly<import('vue').Ref<StreamStatus>>, close: () => void }}
 */
export function openEventStream(path, options) {
  const {
    handlers,
    params,
    maxRetries = 5,
    retryDelayMs = 2000,
    EventSourceImpl = globalThis.EventSource,
  } = options

  /** @type {import('vue').Ref<StreamStatus>} */
  const status = ref('connecting')
  /** @type {EventSource | null} */
  let source = null
  /** @type {ReturnType<typeof setTimeout> | null} */
  let retryTimer = null
  let attempts = 0
  let closedByUser = false

  function connect() {
    source = new EventSourceImpl(buildStreamUrl(path, params), {
      withCredentials: hooks.withCredentials,
    })

    source.onopen = () => {
      attempts = 0
      status.value = 'open'
    }

    source.onerror = () => {
      if (closedByUser || !source) return
      if (source.readyState === EventSourceImpl.CONNECTING) {
        status.value = 'reconnecting'
        return
      }
      // CLOSED: server ตอบไม่ใช่ 200 หรือปิดถาวร → ลองใหม่เอง
      source.close()
      scheduleRetry()
    }

    for (const [eventName, handler] of Object.entries(handlers)) {
      source.addEventListener(eventName, (event) => {
        const message = /** @type {MessageEvent} */ (event)
        handler(parseData(message.data), message)
      })
    }
  }

  function scheduleRetry() {
    if (attempts >= maxRetries) {
      status.value = 'failed'
      return
    }
    status.value = 'reconnecting'
    const delay = Math.min(retryDelayMs * 2 ** attempts, 30_000)
    attempts += 1
    retryTimer = setTimeout(connect, delay)
  }

  function close() {
    closedByUser = true
    if (retryTimer) clearTimeout(retryTimer)
    source?.close()
    source = null
    status.value = 'closed'
  }

  connect()
  if (getCurrentScope()) onScopeDispose(close)

  return { status: readonly(status), close }
}
