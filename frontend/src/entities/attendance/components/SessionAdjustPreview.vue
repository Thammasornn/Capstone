<script setup>
/**
 * ผลกระทบของการปรับเวลาคาบ (response ของ dryRun) — เวลา before → after + รายชื่อที่ผลเปลี่ยน 3 กลุ่ม
 *  - ผลดีขึ้น (upgrade) ใช้อัตโนมัติ
 *  - ผลแย่ลง (downgrade) ใช้เมื่อติ๊ก v-model:applyDowngrades เท่านั้น
 *  - เปิดให้เช็คชื่อใหม่ (reopen) = ผลขาดที่ระบบบันทึกถูกลบ ให้กล้องเช็คชื่อได้อีก
 */
import { computed } from 'vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import { formatTime, formatTimeOfDateTime } from '@/shared/utils/datetime'
import { getStatusMeta } from '../status'

const props = defineProps({
  /** @type {import('vue').PropType<import('../api').AdjustResponse>} */
  result: { type: Object, required: true },
})
const applyDowngrades = defineModel('applyDowngrades', { type: Boolean, default: false })

const TIME_ROWS = [
  ['startTime', 'เริ่มเรียน'],
  ['checkInOpens', 'เปิดเช็คชื่อ'],
  ['onTimeUntil', 'ตรงเวลาถึง'],
  ['lateUntil', 'สายถึง'],
  ['checkInCloses', 'ปิดเช็คชื่อ'],
  ['endTime', 'เลิกเรียน'],
]
const times = computed(() =>
  TIME_ROWS.map(([key, label]) => {
    const before = formatTime(props.result.before?.[key])
    const after = formatTime(props.result.after?.[key])
    return { key, label, before, after, changed: before !== after }
  }),
)

const groups = computed(() => {
  const changes = props.result.changes ?? []
  return [
    {
      kind: 'upgrade',
      title: 'ผลดีขึ้น (ใช้อัตโนมัติ)',
      rows: changes.filter((c) => c.kind === 'upgrade'),
    },
    {
      kind: 'downgrade',
      title: 'ผลแย่ลง',
      rows: changes.filter((c) => c.kind === 'downgrade'),
    },
    {
      kind: 'reopen',
      title: 'เปิดให้เช็คชื่อใหม่ (ผลขาดของระบบถูกลบ)',
      rows: changes.filter((c) => c.kind === 'reopen'),
    },
  ]
})
const hasChanges = computed(() => groups.value.some((g) => g.rows.length))
</script>

<template>
  <div class="flex flex-col gap-4">
    <div class="overflow-hidden rounded-field border border-line">
      <table class="w-full text-sm">
        <thead class="bg-page text-xs text-muted">
          <tr>
            <th class="px-3 py-2 text-left font-medium">เวลา</th>
            <th class="px-3 py-2 text-left font-medium">เดิม</th>
            <th class="px-3 py-2 text-left font-medium">ใหม่</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in times" :key="t.key" class="border-t border-line">
            <td class="px-3 py-1.5 text-muted">{{ t.label }}</td>
            <td class="px-3 py-1.5" :class="t.changed ? 'text-muted line-through' : ''">
              {{ t.before }}
            </td>
            <td class="px-3 py-1.5" :class="t.changed ? 'font-semibold text-primary' : ''">
              {{ t.after }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <p v-if="!hasChanges" class="text-sm text-muted">ไม่มีนักศึกษาที่ผลเช็คชื่อเปลี่ยน</p>
    <template v-for="g in groups" :key="g.kind">
      <section v-if="g.rows.length" class="flex flex-col gap-2">
        <h3 class="text-sm font-bold">{{ g.title }} · {{ g.rows.length }} คน</h3>
        <label
          v-if="g.kind === 'downgrade'"
          class="flex items-start gap-2 rounded-field bg-warning-bg px-3 py-2 text-sm text-warning"
        >
          <input v-model="applyDowngrades" type="checkbox" class="mt-0.5 size-4" />
          <span>ใช้ผลที่แย่ลงด้วย (ไม่ติ๊ก = คงผลเดิมของนักศึกษากลุ่มนี้)</span>
        </label>
        <ul
          class="flex max-h-48 flex-col divide-y divide-line overflow-y-auto rounded-field border border-line"
        >
          <li
            v-for="c in g.rows"
            :key="c.studentId"
            class="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-sm"
          >
            <span class="min-w-0">
              <span class="block font-medium">{{ c.studentName }}</span>
              <span class="block text-xs text-muted">
                {{ c.studentCode }}
                <template v-if="c.checkInTime">
                  · เข้า {{ formatTimeOfDateTime(c.checkInTime) }}</template
                >
              </span>
            </span>
            <span class="flex items-center gap-1 text-xs">
              <StatusBadge
                :tone="getStatusMeta(c.fromStatus).tone"
                :label="getStatusMeta(c.fromStatus).label"
              />
              <span aria-hidden="true">→</span>
              <StatusBadge
                v-if="c.toStatus"
                :tone="getStatusMeta(c.toStatus).tone"
                :label="getStatusMeta(c.toStatus).label"
              />
              <StatusBadge v-else tone="info" label="รอเช็คชื่อ" />
            </span>
          </li>
        </ul>
      </section>
    </template>

    <p class="text-xs text-muted">
      ผลที่ลงด้วยมือจะไม่เปลี่ยน · คนที่เช็คชื่อหลังเวลาสายใหม่คงผลเดิม
    </p>
  </div>
</template>
