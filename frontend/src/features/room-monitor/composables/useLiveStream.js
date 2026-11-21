import { onScopeDispose, readonly, ref, shallowRef } from 'vue'
import { getStreamInfo } from '../api'

/**
 * เล่นภาพสดจาก MediaMTX ผ่าน WHEP (WebRTC) ตาม URL จาก B2
 * status: idle | connecting | playing | unavailable (ไม่มีกล้อง / ต่อ MediaMTX ไม่ได้)
 * ไม่มี HLS fallback ใน R1 (MediaMTX เปิด WHEP ให้แล้ว)
 */
export function useLiveStream() {
  /** @type {import('vue').ShallowRef<MediaStream | null>} */
  const stream = shallowRef(null)
  const status = ref('idle')
  const message = ref('')
  /** @type {RTCPeerConnection | null} */
  let pc = null

  function stop() {
    pc?.close()
    pc = null
    stream.value = null
    status.value = 'idle'
  }

  /** @param {string} roomId */
  async function start(roomId) {
    stop()
    status.value = 'connecting'
    message.value = ''
    try {
      const info = await getStreamInfo(roomId)
      if (info.protocol !== 'whep' || typeof RTCPeerConnection === 'undefined') {
        throw new Error('เบราว์เซอร์นี้เล่นภาพสดไม่ได้')
      }
      const peer = new RTCPeerConnection()
      pc = peer
      peer.addTransceiver('video', { direction: 'recvonly' })
      peer.addTransceiver('audio', { direction: 'recvonly' })
      peer.ontrack = (event) => {
        stream.value = event.streams[0] ?? new MediaStream([event.track])
        status.value = 'playing'
      }
      peer.onconnectionstatechange = () => {
        if (['failed', 'disconnected'].includes(peer.connectionState)) {
          status.value = 'unavailable'
          message.value = 'สัญญาณภาพหลุด'
        }
      }
      const offer = await peer.createOffer()
      await peer.setLocalDescription(offer)
      const response = await fetch(info.url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/sdp' },
        body: offer.sdp,
      }).catch(() => {
        throw new Error('เชื่อมต่อเซิร์ฟเวอร์ภาพ (MediaMTX) ไม่ได้')
      })
      if (!response.ok) throw new Error(`MediaMTX ตอบ ${response.status}`)
      await peer.setRemoteDescription({ type: 'answer', sdp: await response.text() })
    } catch (e) {
      pc?.close()
      pc = null
      status.value = 'unavailable'
      message.value = e?.message ?? 'เชื่อมต่อกล้องไม่ได้'
    }
  }

  onScopeDispose(stop)

  return { stream, status: readonly(status), message: readonly(message), start, stop }
}
