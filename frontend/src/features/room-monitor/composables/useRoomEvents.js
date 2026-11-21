import { onScopeDispose, readonly, ref, watch } from 'vue'
import { openEventStream } from '@/shared/api/sse'
import { roomEventsPath } from '../api'

/**
 * SSE ของห้อง (B3) — event: camera {cameraStatus} · faces {detected, at} · check_in {...} · session {sessionId, sessionStatus}
 * เปิดใหม่อัตโนมัติเมื่อ roomId เปลี่ยน และปิดเมื่อออกจากหน้า
 *
 * @param {import('vue').Ref<string> | (() => string)} roomId
 * @param {Record<string, (data: any) => void>} handlers
 */
export function useRoomEvents(roomId, handlers) {
  /** @type {import('vue').Ref<import('@/shared/api/sse').StreamStatus>} */
  const status = ref('connecting')
  /** @type {{ close: () => void } | null} */
  let stream = null
  let stopStatus = () => {}

  watch(
    roomId,
    (id) => {
      stream?.close()
      stopStatus()
      if (!id) return
      stream = openEventStream(roomEventsPath(id), { handlers })
      stopStatus = watch(stream.status, (s) => (status.value = s), { immediate: true })
    },
    { immediate: true },
  )

  onScopeDispose(() => {
    stopStatus()
    stream?.close()
  })

  return { status: readonly(status) }
}
