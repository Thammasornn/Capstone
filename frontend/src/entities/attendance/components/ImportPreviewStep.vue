<script setup>
/**
 * ผลของ dryRun: ช่วงเวลาของคาบ + ตัวเลขสรุป + ตารางแยกแท็บ
 * (จะบันทึก insert / replace · ขัดแย้ง · ข้าม · จับคู่ไม่ได้) · checkbox แทนที่ผลเดิม = v-model
 */
import { computed, ref, watch } from 'vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import { formatThaiDateTime, formatTimeOfDateTime } from '@/shared/utils/datetime'
import { getStatusMeta } from '../status'
import { IMPORT_ACTION_LABEL, UNMATCHED_REASON_LABEL, attendanceSourceLabel } from '../import'

const props = defineProps({
  /** @type {import('vue').PropType<import('../import').ImportResult>} */
  result: { type: Object, required: true },
  busy: { type: Boolean, default: false },
})
const overrideCamera = defineModel('overrideCamera', { type: Boolean, default: false })
const overrideManual = defineModel('overrideManual', { type: Boolean, default: false })

const rows = computed(() => props.result.rows ?? [])
const tabs = computed(() => [
  {
    key: 'write',
    label: 'จะบันทึก',
    rows: rows.value.filter((r) => r.action === 'insert' || r.action === 'replace'),
  },
  { key: 'conflict', label: 'ขัดแย้ง', rows: rows.value.filter((r) => r.action === 'conflict') },
  { key: 'skip', label: 'ข้าม', rows: rows.value.filter((r) => r.action === 'skip') },
  { key: 'unmatched', label: 'จับคู่ไม่ได้', rows: props.result.unmatched ?? [] },
])
const tab = ref('write')
// เปิดแท็บแรกที่มีข้อมูล เมื่อได้ผลใหม่ครั้งแรก
watch(
  () => props.result,
  () => {
    if (!tabs.value.find((t) => t.key === tab.value)?.rows.length) {
      tab.value = tabs.value.find((t) => t.rows.length)?.key ?? 'write'
    }
  },
  { immediate: true },
)
const current = computed(() => tabs.value.find((t) => t.key === tab.value))

const hasCameraConflict = computed(() =>
  rows.value.some((r) => r.action === 'conflict' && r.fromSource === 'camera'),
)
const hasManualConflict = computed(() =>
  rows.value.some((r) => r.action === 'conflict' && r.fromSource === 'manual'),
)

/** dryRun ตอบ conflict เสมอ — บอกผู้ใช้ว่าแถวนี้จะถูกแทนที่ไหมตาม checkbox */
function willOverride(r) {
  if (r.fromSource === 'camera') return overrideCamera.value
  if (r.fromSource === 'manual') return overrideManual.value
  return false
}

const s = computed(() => props.result.summary)
const w = computed(() => props.result.sessionWindow)
const hhmm = (v) => (typeof v === 'string' ? v.slice(0, 5) : '-')
</script>

<template>
  <div class="flex flex-col gap-4">
    <p
      v-if="result.duplicateOf"
      role="alert"
      class="rounded-field bg-warning-bg px-3 py-2 text-sm text-warning"
    >
      ไฟล์นี้เคยนำเข้าแล้วเมื่อ {{ formatThaiDateTime(result.duplicateOf.importedAt) }} โดย
      {{ result.duplicateOf.importedBy }} ({{ result.duplicateOf.fileName }}) — นำเข้าซ้ำได้
      ผลจากไฟล์นี้จะแทนที่ผลนำเข้าครั้งก่อน
    </p>

    <div class="flex flex-wrap gap-x-4 gap-y-1 rounded-field bg-page px-3 py-2 text-xs text-muted">
      <span>เรียน {{ hhmm(w?.startTime) }}–{{ hhmm(w?.endTime) }}</span>
      <span>ตรงเวลาถึง {{ hhmm(w?.onTimeUntil) }}</span>
      <span>สายถึง {{ hhmm(w?.lateUntil) }}</span>
      <span class="w-full">ไฟล์ที่มีเวลาเข้า: ระบบเทียบกับเวลานี้ (เกินเวลาสาย = สาย)</span>
    </div>

    <dl class="grid grid-cols-3 gap-2 text-center sm:grid-cols-6">
      <div
        v-for="[label, value] in [
          ['ทั้งหมด', s.totalRows],
          ['ใหม่', s.insert],
          ['แทนที่', s.replace],
          ['ข้าม', s.skip],
          ['ขัดแย้ง', s.conflict],
          ['จับคู่ไม่ได้', s.unmatched],
        ]"
        :key="label"
        class="rounded-field border border-line px-2 py-2"
      >
        <dt class="text-[11px] text-muted">{{ label }}</dt>
        <dd class="text-lg font-bold">{{ value }}</dd>
      </div>
    </dl>

    <fieldset class="flex flex-col gap-2 text-sm" :disabled="busy">
      <label class="flex items-start gap-2" :class="!hasCameraConflict && 'text-muted'">
        <input v-model="overrideCamera" type="checkbox" class="mt-0.5 size-4" />
        <span>แทนที่ผลจากกล้องที่ไม่ตรงกับไฟล์</span>
      </label>
      <label class="flex items-start gap-2" :class="!hasManualConflict && 'text-muted'">
        <input v-model="overrideManual" type="checkbox" class="mt-0.5 size-4" />
        <span>แทนที่ผลที่อาจารย์แก้เอง</span>
      </label>
      <p class="text-xs text-muted">
        ไม่ติ๊ก = คงผลเดิมของแถวที่ขัดแย้ง · เปลี่ยนแล้วระบบคำนวณผลใหม่ให้ดูก่อน
      </p>
    </fieldset>

    <div role="tablist" class="-mx-1 flex gap-2 overflow-x-auto px-1 py-1">
      <button
        v-for="t in tabs"
        :key="t.key"
        type="button"
        role="tab"
        :aria-selected="tab === t.key ? 'true' : 'false'"
        class="inline-flex shrink-0 items-center gap-1 rounded-button border px-3 py-1.5 text-[13px] font-medium"
        :class="
          tab === t.key
            ? 'border-primary bg-primary text-on-dark'
            : 'border-line bg-surface text-ink hover:border-primary/40'
        "
        @click="tab = t.key"
      >
        {{ t.label }} <span class="opacity-80">{{ t.rows.length }}</span>
      </button>
    </div>

    <div role="tabpanel" class="max-h-72 overflow-auto rounded-field border border-line">
      <p v-if="!current.rows.length" class="px-3 py-4 text-sm text-muted">ไม่มีรายการ</p>

      <table v-else-if="tab === 'unmatched'" class="w-full text-sm">
        <thead class="sticky top-0 bg-page text-xs text-muted">
          <tr>
            <th class="px-3 py-2 text-left font-medium">แถว</th>
            <th class="px-3 py-2 text-left font-medium">ค่าในไฟล์</th>
            <th class="px-3 py-2 text-left font-medium">เหตุผล</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in current.rows" :key="u.rowNo" class="border-t border-line">
            <td class="px-3 py-1.5">{{ u.rowNo }}</td>
            <td class="px-3 py-1.5 break-all">{{ u.value || '-' }}</td>
            <td class="px-3 py-1.5">{{ UNMATCHED_REASON_LABEL[u.reason] ?? u.reason }}</td>
          </tr>
        </tbody>
      </table>

      <ul v-else class="divide-y divide-line">
        <li
          v-for="r in current.rows"
          :key="r.rowNo"
          class="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-sm"
        >
          <span class="min-w-0">
            <span class="block font-medium">{{ r.studentName }}</span>
            <span class="block text-xs text-muted">
              แถว {{ r.rowNo }} · {{ r.studentCode }}
              <template v-if="r.checkInTime">
                · เข้า {{ formatTimeOfDateTime(r.checkInTime) }}</template
              >
              · {{ IMPORT_ACTION_LABEL[r.action] ?? r.action }}
            </span>
            <span
              v-if="r.action === 'conflict'"
              class="block text-xs font-semibold"
              :class="willOverride(r) ? 'text-warning' : 'text-muted'"
            >
              {{ willOverride(r) ? 'จะแทนที่ด้วยผลจากไฟล์' : 'คงผลเดิม (ไม่แทนที่)' }}
            </span>
          </span>
          <span class="flex flex-wrap items-center gap-1 text-xs">
            <template v-if="r.fromStatus">
              <span class="text-muted">{{ attendanceSourceLabel(r.fromSource) }}</span>
              <StatusBadge
                :tone="getStatusMeta(r.fromStatus).tone"
                :label="getStatusMeta(r.fromStatus).label"
              />
              <span aria-hidden="true">→</span>
            </template>
            <span v-else class="text-muted">ยังไม่มีผล →</span>
            <StatusBadge
              :tone="getStatusMeta(r.toStatus).tone"
              :label="getStatusMeta(r.toStatus).label"
            />
          </span>
        </li>
      </ul>
    </div>
  </div>
</template>
