<script setup>
/**
 * ผลของ dryRun (ป้อนเอง / นำเข้าไฟล์): ตัวเลขสรุป + แท็บ จะเพิ่ม · อยู่ในกลุ่มแล้ว · เพิ่มไม่ได้
 * dryRun = false (บันทึกแล้ว) แสดงป้าย "บันทึกแล้ว" ต่อแถว
 */
import { computed, ref, watch } from 'vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import { ADD_ACTION_LABEL, addReasonLabel } from '../api'

const props = defineProps({
  /** @type {import('vue').PropType<import('../api').AddResponse>} */
  result: { type: Object, required: true },
  /** ป้อนเอง = "คนที่" · ไฟล์ = "แถว" */
  rowLabel: { type: String, default: 'แถว' },
})

const WRITE = ['insert', 'reactivate', 'create']
const tabs = computed(() => {
  const rows = props.result.rows ?? []
  return [
    { key: 'write', label: 'จะเพิ่ม', rows: rows.filter((r) => WRITE.includes(r.action)) },
    { key: 'skip', label: 'อยู่ในกลุ่มแล้ว', rows: rows.filter((r) => r.action === 'skip') },
    { key: 'unmatched', label: 'เพิ่มไม่ได้', rows: rows.filter((r) => r.action === 'unmatched') },
  ]
})
const tab = ref('write')
watch(
  () => props.result,
  () => {
    if (!tabs.value.find((t) => t.key === tab.value)?.rows.length)
      tab.value = tabs.value.find((t) => t.rows.length)?.key ?? 'write'
  },
  { immediate: true },
)
const current = computed(() => tabs.value.find((t) => t.key === tab.value))
const s = computed(() => props.result.summary)
const tone = {
  insert: 'success',
  reactivate: 'info',
  create: 'info',
  skip: 'neutral',
  unmatched: 'danger',
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <dl class="grid grid-cols-3 gap-2 text-center sm:grid-cols-6">
      <div
        v-for="[label, value] in [
          ['ทั้งหมด', s.total],
          ['เพิ่ม', s.insert],
          ['เพิ่มกลับ', s.reactivate],
          ['สร้างบัญชี', s.create],
          ['อยู่แล้ว', s.skip],
          ['เพิ่มไม่ได้', s.unmatched],
        ]"
        :key="label"
        class="rounded-field border border-line px-2 py-2"
      >
        <dt class="text-[11px] text-muted">{{ label }}</dt>
        <dd class="text-lg font-bold">{{ value }}</dd>
      </div>
    </dl>

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
      <ul v-else class="divide-y divide-line">
        <li
          v-for="r in current.rows"
          :key="r.rowNo"
          class="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-sm"
          :class="r.applied && 'bg-success-bg/60'"
        >
          <span class="min-w-0">
            <span class="block font-medium break-all">
              {{ r.studentName || r.identifier || '-' }}
            </span>
            <span class="block text-xs break-all text-muted">
              {{ rowLabel }} {{ r.rowNo }}
              <template v-if="r.studentCode"> · {{ r.studentCode }}</template>
              <template v-if="r.email"> · {{ r.email }}</template>
            </span>
            <span v-if="r.reason" class="block text-xs text-muted">
              {{ addReasonLabel(r.reason, result.canCreateAccounts) }}
            </span>
          </span>
          <span class="flex items-center gap-1">
            <StatusBadge
              :tone="tone[r.action] ?? 'neutral'"
              :label="ADD_ACTION_LABEL[r.action] ?? r.action"
            />
            <span v-if="r.applied" class="text-xs font-semibold text-success">บันทึกแล้ว</span>
          </span>
        </li>
      </ul>
    </div>
  </div>
</template>
