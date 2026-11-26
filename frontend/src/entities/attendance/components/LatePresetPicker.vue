<script setup>
/**
 * ปุ่มเลือกกติกาเวลาสาย (F1): สาย 10 / 15 / 30 นาที (คำนวณจากเวลาเริ่มเรียน) หรือ กำหนดเอง
 * v-model = lateAfterMinutes ของ preset หรือ 'custom' · ค่า preset อยู่ที่ check-in-presets.js
 */
import { useId } from 'vue'
import { CUSTOM_PRESET, LATE_PRESETS } from '../check-in-presets'

defineProps({ disabled: { type: Boolean, default: false } })
const model = defineModel({ type: [Number, String], required: true })
const id = useId()

const options = [
  ...LATE_PRESETS.map((p) => ({
    value: p.lateAfterMinutes,
    label: `สาย ${p.lateAfterMinutes} นาที`,
    hint: `ขาดหลัง ${p.absentAfterMinutes} นาที`,
  })),
  { value: CUSTOM_PRESET, label: 'กำหนดเอง', hint: 'แก้ทุกช่องเอง' },
]
</script>

<template>
  <div role="radiogroup" :aria-labelledby="`${id}-label`" class="@container flex flex-col gap-1.5">
    <p :id="`${id}-label`" class="text-xs font-medium text-muted">กติกาเวลาสาย</p>
    <div class="grid grid-cols-2 gap-2 @md:grid-cols-4">
      <button
        v-for="o in options"
        :key="o.value"
        type="button"
        role="radio"
        :aria-checked="model === o.value ? 'true' : 'false'"
        :disabled="disabled"
        class="flex min-h-11 flex-col items-start rounded-button border px-3 py-1.5 text-left disabled:opacity-60"
        :class="
          model === o.value
            ? 'border-primary bg-primary/10 text-primary'
            : 'border-line bg-surface text-ink hover:border-primary/40'
        "
        @click="model = o.value"
      >
        <span class="text-sm font-semibold">{{ o.label }}</span>
        <span class="text-[11px] text-muted">{{ o.hint }}</span>
      </button>
    </div>
    <p v-if="model !== 'custom'" class="text-xs text-muted">
      ระบบคำนวณช่วงเช็คชื่อจากเวลาเริ่มเรียนให้ · แก้ช่องใดเองจะเปลี่ยนเป็น "กำหนดเอง"
    </p>
  </div>
</template>
