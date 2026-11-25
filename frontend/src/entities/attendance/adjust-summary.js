/**
 * ข้อความ toast หลังปรับเวลาคาบสำเร็จ (response ของ reanchor / extend / check-in-window)
 * @param {import('./api').AdjustResponse} result
 * @param {'reanchor'|'extend'|'window'} mode
 */
export function describeAdjustResult(result, mode) {
  const t = result?.after ?? {}
  const head =
    mode === 'extend'
      ? `ยืดเวลาจบเป็น ${t.endTime ?? '-'} แล้ว`
      : mode === 'reanchor'
        ? `เริ่มนับเวลาใหม่ที่ ${t.startTime ?? '-'} แล้ว`
        : `ปรับช่วงเช็คชื่อแล้ว (ตรงเวลาถึง ${t.onTimeUntil ?? '-'} · สายถึง ${t.lateUntil ?? '-'})`
  const s = result?.summary
  if (!s || mode === 'extend') return head
  const parts = []
  if (s.upgrades) parts.push(`ดีขึ้น ${s.upgrades} คน`)
  if (s.downgrades)
    parts.push(`แย่ลง ${s.downgrades} คน${s.downgradesApplied ? '' : ' (คงผลเดิม)'}`)
  if (s.reopened) parts.push(`เปิดให้เช็คชื่อใหม่ ${s.reopened} คน`)
  return parts.length ? `${head} · ${parts.join(' · ')}` : head
}
