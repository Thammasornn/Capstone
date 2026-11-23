/**
 * เตรียมรูปก่อนส่ง (ทั้งถ่ายจากกล้องมือถือและเลือกจากเครื่อง)
 *  - ย่อด้านยาวสุดเหลือ MAX_SIDE px · หมุนตาม EXIF · แปลงเป็น JPEG → ไม่เกิน 5MB ต่อรูป (รูปมือถือมักเกิน)
 *  - เขียนรูปใหม่จาก canvas จึงตัด EXIF ทิ้งทั้งหมด (รวมพิกัด GPS) ก่อนส่งขึ้นเซิร์ฟเวอร์
 *  - รับ HEIC ได้ถ้าเบราว์เซอร์ถอดรหัสได้ (Safari) · ถอดไม่ได้ → แจ้งให้ตั้งกล้องเป็น JPEG
 * ใบหน้าใหญ่พอสำหรับ AI (ขั้นต่ำ 112 px) ที่ 1600 px
 */
export const MAX_SIDE = 1600
const JPEG_QUALITY = 0.9

/**
 * @param {File} file
 * @returns {Promise<File>} JPEG ไม่เกิน MAX_SIDE
 * @throws {Error} ข้อความภาษาไทยเมื่อเปิดรูปไม่ได้
 */
export async function prepareImage(file) {
  if (!file.type.startsWith('image/') && !/\.(heic|heif)$/i.test(file.name)) {
    throw new Error(`${file.name}: ไม่ใช่ไฟล์รูปภาพ`)
  }
  let bitmap
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
  } catch {
    throw new Error(
      `${file.name}: เปิดรูปนี้ไม่ได้ (เช่น HEIC บนเบราว์เซอร์ที่ไม่รองรับ) — ตั้งกล้องเป็น JPEG หรือเลือกรูปอื่น`,
    )
  }
  const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height))
  const width = Math.round(bitmap.width * scale)
  const height = Math.round(bitmap.height * scale)
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  canvas.getContext('2d')?.drawImage(bitmap, 0, 0, width, height)
  bitmap.close?.()
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', JPEG_QUALITY))
  if (!blob) throw new Error(`${file.name}: แปลงรูปไม่สำเร็จ`)
  const name = file.name.replace(/\.[^.]+$/, '') + '.jpg'
  return new File([blob], name, { type: 'image/jpeg' })
}

/** เปิดกล้องสดในหน้าเว็บได้ไหม (ต้องเป็น HTTPS หรือ localhost) */
export function canUseLiveCamera() {
  return Boolean(globalThis.isSecureContext && navigator.mediaDevices?.getUserMedia)
}

/** อุปกรณ์จอสัมผัส (มือถือ / แท็บเล็ต) — ใช้ input capture เปิดแอปกล้องได้แม้เป็น HTTP */
export function isTouchDevice() {
  return Boolean(globalThis.matchMedia?.('(pointer: coarse)').matches)
}
