<script setup>
/**
 * ขั้นเลือกคอลัมน์ (ไฟล์อื่น) — ทุก dropdown เริ่มที่ "— เลือก —" ไม่เติมค่าให้
 * v-model = ImportMapping แบบร่าง · emit valid(boolean) ผ่าน v-model:valid
 */
import { computed, watch } from 'vue'
import BaseSelect from '@/shared/components/BaseSelect.vue'
import { ATTENDANCE_STATUS, ATTENDANCE_STATUS_ORDER } from '../status'
import { TIME_FORMATS } from '../import'

const props = defineProps({
  /** @type {import('vue').PropType<import('../import').InspectResult>} */
  inspect: { type: Object, required: true },
})
const draft = defineModel({ type: Object, required: true })
const valid = defineModel('valid', { type: Boolean, default: false })

const PICK = '— เลือก —'
const columnOptions = computed(() => props.inspect.headers.map((h) => ({ value: h, label: h })))
const idTypeOptions = [
  { value: 'email', label: 'อีเมล' },
  { value: 'student_code', label: 'รหัสนักศึกษา' },
]
const statusOptions = ATTENDANCE_STATUS_ORDER.map((s) => ({
  value: s,
  label: ATTENDANCE_STATUS[s].label,
}))
const defaultStatusOptions = statusOptions.filter((o) => o.value !== 'absent')

/** ตัวอย่างค่าของคอลัมน์จาก sampleRows (ไม่ซ้ำ, ไม่ว่าง) */
function samples(column) {
  const i = props.inspect.headers.indexOf(column)
  if (i < 0) return []
  return [...new Set(props.inspect.sampleRows.map((r) => r[i]).filter(Boolean))].slice(0, 3)
}
const timeSamples = computed(() =>
  draft.value.joinTimeColumn ? samples(draft.value.joinTimeColumn) : [],
)
const idSamples = computed(() =>
  draft.value.identifierColumn ? samples(draft.value.identifierColumn) : [],
)

/** ค่าในคอลัมน์สถานะ · null = คอลัมน์นี้มีค่าไม่ซ้ำเกิน 20 ใช้เป็นสถานะไม่ได้ */
const statusDistinct = computed(() => {
  const c = draft.value.statusColumn
  if (!c) return []
  return props.inspect.distinctValues?.[c] ?? null
})

// เปลี่ยนคอลัมน์สถานะ → ล้างการจับคู่เดิม (ไม่เดาให้)
watch(
  () => draft.value.statusColumn,
  () => {
    draft.value.statusValues = {}
  },
)
watch(
  () => draft.value.joinTimeColumn,
  (c) => {
    if (!c) draft.value.timeFormat = null
  },
)

const needsDefault = computed(() => !draft.value.joinTimeColumn && !draft.value.statusColumn)

const problems = computed(() => {
  const d = draft.value
  const list = []
  if (!d.identifierColumn) list.push('เลือกคอลัมน์ตัวระบุนักศึกษา')
  if (!d.identifierType) list.push('เลือกชนิดของตัวระบุ')
  if (d.joinTimeColumn && !d.timeFormat) list.push('เลือกรูปแบบเวลาเข้า')
  if (d.statusColumn) {
    if (statusDistinct.value === null) list.push('คอลัมน์สถานะที่เลือกมีค่าเยอะเกินไป')
    else if (statusDistinct.value.some((v) => !d.statusValues?.[v]))
      list.push('จับคู่ค่าสถานะให้ครบทุกค่า')
  }
  if (needsDefault.value && !d.defaultStatus) list.push('เลือกสถานะเริ่มต้น')
  return list
})
watch(
  problems,
  (p) => {
    valid.value = p.length === 0
  },
  { immediate: true },
)
</script>

<template>
  <div class="flex flex-col gap-5">
    <section>
      <h3 class="mb-2 text-sm font-bold">
        ตัวอย่างข้อมูล ({{ inspect.rowCount }} แถว · แสดง {{ inspect.sampleRows.length }} แถวแรก)
      </h3>
      <div class="max-h-56 overflow-auto rounded-field border border-line">
        <table class="w-full text-xs">
          <thead class="sticky top-0 bg-page text-muted">
            <tr>
              <th
                v-for="h in inspect.headers"
                :key="h"
                class="px-2 py-1.5 text-left font-medium whitespace-nowrap"
              >
                {{ h }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in inspect.sampleRows" :key="i" class="border-t border-line">
              <td v-for="(cell, j) in row" :key="j" class="px-2 py-1.5 whitespace-nowrap">
                {{ cell || '-' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="grid gap-3 sm:grid-cols-2">
      <BaseSelect
        v-model="draft.identifierColumn"
        label="คอลัมน์ตัวระบุนักศึกษา *"
        :options="columnOptions"
        :placeholder="PICK"
      />
      <BaseSelect
        v-model="draft.identifierType"
        label="ชนิดของตัวระบุ *"
        :options="idTypeOptions"
        :placeholder="PICK"
      />
      <p v-if="idSamples.length" class="text-xs text-muted sm:col-span-2">
        ตัวอย่างค่า: {{ idSamples.join(' · ') }}
        <template v-if="draft.identifierType === 'email'">
          — อีเมลต้องขึ้นต้นด้วยรหัสนักศึกษา 11 หลัก</template
        >
      </p>
    </section>

    <section class="grid gap-3 sm:grid-cols-2">
      <BaseSelect
        v-model="draft.joinTimeColumn"
        label="คอลัมน์เวลาเข้า (ไม่บังคับ)"
        :options="columnOptions"
        :placeholder="PICK"
      />
      <BaseSelect
        v-if="draft.joinTimeColumn"
        v-model="draft.timeFormat"
        label="รูปแบบเวลา *"
        :options="TIME_FORMATS"
        :placeholder="PICK"
      />
      <p v-if="draft.joinTimeColumn" class="text-xs break-words text-muted sm:col-span-2">
        ค่าในไฟล์:
        <span class="font-mono text-ink">{{ timeSamples.join(' · ') || '(ว่าง)' }}</span>
        — เลือกรูปแบบให้ตรงกับค่านี้
      </p>
    </section>

    <section class="flex flex-col gap-3">
      <div class="sm:w-1/2 sm:pr-1.5">
        <BaseSelect
          v-model="draft.statusColumn"
          label="คอลัมน์สถานะ (ไม่บังคับ)"
          :options="columnOptions"
          :placeholder="PICK"
        />
      </div>
      <p
        v-if="draft.statusColumn && statusDistinct === null"
        role="alert"
        class="rounded-field bg-warning-bg px-3 py-2 text-sm text-warning"
      >
        คอลัมน์ "{{ draft.statusColumn }}" มีค่าไม่ซ้ำเกิน 20 ค่า ใช้เป็นคอลัมน์สถานะไม่ได้ —
        เลือกคอลัมน์อื่น หรือไม่ใช้คอลัมน์สถานะ
      </p>
      <p v-else-if="draft.statusColumn && statusDistinct.length === 0" class="text-sm text-muted">
        คอลัมน์นี้ไม่มีค่า
      </p>
      <div
        v-else-if="draft.statusColumn"
        class="grid gap-3 rounded-field border border-line p-3 sm:grid-cols-2"
      >
        <p class="text-xs text-muted sm:col-span-2">จับคู่ทุกค่าในคอลัมน์กับสถานะ</p>
        <BaseSelect
          v-for="v in statusDistinct"
          :key="v"
          v-model="draft.statusValues[v]"
          :label="`“${v}”`"
          :options="statusOptions"
          :placeholder="PICK"
        />
      </div>
    </section>

    <section v-if="needsDefault" class="sm:w-1/2 sm:pr-1.5">
      <BaseSelect
        v-model="draft.defaultStatus"
        label="สถานะเริ่มต้นของทุกคนในไฟล์ *"
        :options="defaultStatusOptions"
        :placeholder="PICK"
      />
      <p class="mt-1 text-xs text-muted">
        ไม่ได้เลือกคอลัมน์เวลาหรือสถานะ — ทุกคนในไฟล์จะได้สถานะนี้
      </p>
    </section>

    <ul v-if="problems.length" class="list-disc pl-5 text-xs text-muted">
      <li v-for="p in problems" :key="p">{{ p }}</li>
    </ul>
  </div>
</template>
