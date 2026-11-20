import { describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { openEventStream } from '@/shared/api/sse'

class FakeEventSource {
  static CONNECTING = 0
  static OPEN = 1
  static CLOSED = 2
  static instances = []
  constructor(url) {
    this.url = url
    this.readyState = FakeEventSource.CONNECTING
    this.listeners = {}
    this.closed = false
    FakeEventSource.instances.push(this)
  }
  addEventListener(name, fn) {
    this.listeners[name] = fn
  }
  emit(name, data) {
    this.listeners[name]?.({ data: JSON.stringify(data) })
  }
  close() {
    this.closed = true
    this.readyState = FakeEventSource.CLOSED
  }
}

describe('openEventStream', () => {
  it('parse JSON แล้วส่งให้ handler ตามชื่อ event', () => {
    FakeEventSource.instances = []
    const faces = vi.fn()
    openEventStream('/rooms/r1/events', { handlers: { faces }, EventSourceImpl: FakeEventSource })
    const es = FakeEventSource.instances[0]
    expect(es.url).toContain('/rooms/r1/events')
    es.onopen()
    es.emit('faces', { detected: 25 })
    expect(faces).toHaveBeenCalledWith({ detected: 25 }, expect.anything())
  })

  it('server ปิดถาวร → ลองใหม่แบบ backoff แล้วหยุดเมื่อครบจำนวน', () => {
    vi.useFakeTimers()
    FakeEventSource.instances = []
    const { status } = openEventStream('/x', {
      handlers: {},
      maxRetries: 1,
      retryDelayMs: 100,
      EventSourceImpl: FakeEventSource,
    })
    const first = FakeEventSource.instances[0]
    first.readyState = FakeEventSource.CLOSED
    first.onerror()
    expect(status.value).toBe('reconnecting')
    vi.advanceTimersByTime(100)
    expect(FakeEventSource.instances).toHaveLength(2)
    const second = FakeEventSource.instances[1]
    second.readyState = FakeEventSource.CLOSED
    second.onerror()
    expect(status.value).toBe('failed')
    vi.useRealTimers()
  })

  it('ปิด connection เมื่อ scope ถูกทำลาย (ออกจากหน้า)', () => {
    FakeEventSource.instances = []
    const scope = effectScope()
    scope.run(() => openEventStream('/x', { handlers: {}, EventSourceImpl: FakeEventSource }))
    scope.stop()
    expect(FakeEventSource.instances[0].closed).toBe(true)
  })
})
