<script setup>
/**
 * ปุ่มเมนูปรับเวลาคาบ + SessionAdjustDialog — Room Live ("จัดการคาบ" 3 เมนู) · Session Report ("แก้เวลาคาบ" 2 เมนู)
 * เมนูเป็นรายการปุ่มใต้ปุ่มหลัก กดง่ายบนมือถือ · ปิดเมื่อเลือก / กดนอกเมนู / Esc
 * emit applied({ result, mode }) หลังบันทึกสำเร็จ
 */
import { onBeforeUnmount, ref, watch } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import SessionAdjustDialog from './SessionAdjustDialog.vue'

defineProps({
  sessionId: { type: String, required: true },
  times: { type: Object, default: () => ({}) },
  inProgress: { type: Boolean, default: true },
  label: { type: String, default: '' },
  buttonLabel: { type: String, default: 'จัดการคาบ' },
  /** เมนูที่แสดง */
  modes: { type: Array, default: () => ['reanchor', 'extend', 'window'] },
})
const emit = defineEmits(['applied'])

const ITEMS = {
  reanchor: { label: 'เริ่มนับเวลาใหม่', hint: 'อาจารย์เข้าสาย / เริ่มจริงช้ากว่าตาราง' },
  extend: { label: 'ยืดเวลาจบ', hint: 'เลิกช้ากว่าตาราง' },
  window: { label: 'ปรับช่วงเช็คชื่อ', hint: 'ตรงเวลาถึง / สายถึง / ปิดเช็คชื่อ' },
}

const menuOpen = ref(false)
const root = ref(/** @type {HTMLElement | null} */ (null))
const dialogOpen = ref(false)
const mode = ref('reanchor')

function choose(m) {
  mode.value = m
  menuOpen.value = false
  dialogOpen.value = true
}

function onDocClick(e) {
  if (root.value && !root.value.contains(/** @type {Node} */ (e.target))) menuOpen.value = false
}
function onKey(e) {
  if (e.key === 'Escape') menuOpen.value = false
}
watch(menuOpen, (value) => {
  const fn = value ? 'addEventListener' : 'removeEventListener'
  document[fn]('click', onDocClick)
  document[fn]('keydown', onKey)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onKey)
})

function onApplied(result) {
  emit('applied', { result, mode: mode.value })
}
</script>

<template>
  <div ref="root" class="relative">
    <BaseButton
      variant="secondary"
      aria-haspopup="menu"
      :aria-expanded="menuOpen ? 'true' : 'false'"
      @click="menuOpen = !menuOpen"
    >
      {{ buttonLabel }} ▾
    </BaseButton>
    <div
      v-if="menuOpen"
      role="menu"
      class="absolute left-0 z-20 sm:right-0 sm:left-auto mt-2 flex w-[min(18rem,calc(100vw-2rem))] flex-col overflow-hidden rounded-card border border-line bg-surface shadow-card"
    >
      <button
        v-for="m in modes"
        :key="m"
        type="button"
        role="menuitem"
        class="flex min-h-12 flex-col items-start px-4 py-2.5 text-left hover:bg-page focus:bg-page"
        @click="choose(m)"
      >
        <span class="text-sm font-semibold">{{ ITEMS[m].label }}</span>
        <span class="text-xs text-muted">{{ ITEMS[m].hint }}</span>
      </button>
    </div>

    <SessionAdjustDialog
      v-model:open="dialogOpen"
      :session-id="sessionId"
      :mode="mode"
      :times="times"
      :in-progress="inProgress"
      :label="label"
      @applied="onApplied"
    />
  </div>
</template>
