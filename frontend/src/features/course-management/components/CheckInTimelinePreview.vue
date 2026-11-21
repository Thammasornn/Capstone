<script setup>
/** แถบเวลา: ก่อนเช็คชื่อ · ตรงเวลา · สาย · หลังปิดเช็คชื่อ (สัดส่วนตามเวลาเรียน) */
import { computed } from 'vue'
import { formatTime, minutesToTime, timeToMinutes } from '@/shared/utils/datetime'

const props = defineProps({
  startTime: { type: String, default: '' },
  endTime: { type: String, default: '' },
  opens: { type: String, default: '' },
  closes: { type: String, default: '' },
  onTimeMinutes: { type: [Number, String], default: 15 },
})

const parts = computed(() => {
  const s = timeToMinutes(props.startTime)
  const e = timeToMinutes(props.endTime)
  const o = timeToMinutes(props.opens)
  const c = timeToMinutes(props.closes)
  if ([s, e, o, c].some((v) => v === null) || e <= s || c <= o) return null
  const onTimeEnd = Math.min(o + Number(props.onTimeMinutes || 0), c)
  const total = e - s
  const pct = (a, b) => `${Math.max(0, ((b - a) / total) * 100)}%`
  return {
    onTimeEnd: minutesToTime(onTimeEnd),
    segments: [
      { label: 'ยังไม่เปิด', width: pct(s, o), cls: 'bg-line' },
      { label: 'ตรงเวลา', width: pct(o, onTimeEnd), cls: 'bg-success' },
      { label: 'สาย', width: pct(onTimeEnd, c), cls: 'bg-warning' },
      { label: 'ปิดเช็คชื่อ', width: pct(c, e), cls: 'bg-line' },
    ],
  }
})
</script>

<template>
  <div v-if="parts" class="flex flex-col gap-2">
    <div
      class="flex h-3 overflow-hidden rounded-full"
      role="img"
      :aria-label="`ตรงเวลา ${formatTime(opens)}–${formatTime(parts.onTimeEnd)} สายถึง ${formatTime(closes)}`"
    >
      <span
        v-for="seg in parts.segments"
        :key="seg.label"
        :class="seg.cls"
        :style="{ width: seg.width }"
      />
    </div>
    <p class="text-xs text-muted">
      ตรงเวลา {{ formatTime(opens) }}–{{ formatTime(parts.onTimeEnd) }} · สายถึง
      {{ formatTime(closes) }} · หลังจากนั้นนับเป็นขาด
    </p>
  </div>
</template>
