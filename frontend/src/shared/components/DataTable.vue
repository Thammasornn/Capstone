<script setup>
/**
 * ตารางพื้นฐาน · columns: [{ key, label, align?: 'left'|'right'|'center', sortable?: boolean }]
 * ช่องแต่ละคอลัมน์ปรับได้ด้วย slot `cell-<key>` ({ row, value }) · ไม่มีข้อมูล → slot `empty`
 * sort ทำในหน้าเอง (ข้อมูลไม่เยอะ: 1 กลุ่มเรียน)
 */
import { computed, ref } from 'vue'

const props = defineProps({
  columns: { type: Array, required: true },
  rows: { type: Array, required: true },
  rowKey: { type: String, required: true },
  caption: { type: String, default: '' },
  emptyText: { type: String, default: 'ยังไม่มีข้อมูล' },
})

const sortKey = ref(/** @type {string | null} */ (null))
const sortDir = ref(1)

function toggleSort(column) {
  if (!column.sortable) return
  if (sortKey.value === column.key) sortDir.value = -sortDir.value
  else {
    sortKey.value = column.key
    sortDir.value = 1
  }
}

const sortedRows = computed(() => {
  if (!sortKey.value) return props.rows
  const key = sortKey.value
  return [...props.rows].sort((a, b) => {
    const x = a[key]
    const y = b[key]
    if (x === y) return 0
    if (x === null || x === undefined) return 1
    if (y === null || y === undefined) return -1
    return (
      (typeof x === 'number' && typeof y === 'number'
        ? x - y
        : String(x).localeCompare(String(y), 'th')) * sortDir.value
    )
  })
})

const alignClass = { left: 'text-left', right: 'text-right', center: 'text-center' }

function ariaSort(column) {
  if (!column.sortable || sortKey.value !== column.key) return undefined
  return sortDir.value === 1 ? 'ascending' : 'descending'
}
</script>

<template>
  <div class="overflow-x-auto rounded-card border border-line bg-surface">
    <table class="w-full min-w-[560px] border-collapse text-sm">
      <caption v-if="caption" class="sr-only">
        {{
          caption
        }}
      </caption>
      <thead>
        <tr class="border-b border-line bg-page/60">
          <th
            v-for="column in columns"
            :key="column.key"
            scope="col"
            class="px-4 py-3 text-xs font-semibold whitespace-nowrap text-muted"
            :class="alignClass[column.align ?? 'left']"
            :aria-sort="ariaSort(column)"
          >
            <button
              v-if="column.sortable"
              type="button"
              class="inline-flex items-center gap-1 hover:text-ink"
              @click="toggleSort(column)"
            >
              {{ column.label }}
              <span aria-hidden="true">{{
                sortKey === column.key ? (sortDir === 1 ? '▲' : '▼') : '↕'
              }}</span>
            </button>
            <template v-else>{{ column.label }}</template>
          </th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in sortedRows"
          :key="row[rowKey]"
          class="border-b border-line last:border-b-0 hover:bg-page/40"
        >
          <td
            v-for="column in columns"
            :key="column.key"
            class="px-4 py-3 align-middle"
            :class="alignClass[column.align ?? 'left']"
          >
            <slot :name="`cell-${column.key}`" :row="row" :value="row[column.key]">
              {{ row[column.key] ?? '-' }}
            </slot>
          </td>
        </tr>
        <tr v-if="!rows.length">
          <td :colspan="columns.length" class="px-4 py-10 text-center text-sm text-muted">
            <slot name="empty">{{ emptyText }}</slot>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
