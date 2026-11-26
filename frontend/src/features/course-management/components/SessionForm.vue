<script setup>
/**
 * ฟอร์มคาบเรียน 1 คาบ (ใช้ร่วม: แก้ / เลื่อนคาบ · คาบชดเชย / คาบพิเศษ · ยกเลิก + ชดเชย)
 * v-model = ค่าตาม sessionForm.js · errors = ผลของ validateSessionValues · ไม่มีช่องเหตุผล (dialog ใส่เอง)
 * กติกาเวลาสาย (F1): ตรวจ preset จากค่าที่ได้รับ · preset = คำนวณจากเวลาเรียน · แก้ช่องเช็คชื่อเอง → 'custom'
 */
import { computed, ref, watch } from 'vue'
import {
  CUSTOM_PRESET,
  computeCheckInWindow,
  detectLatePreset,
} from '@/entities/attendance/check-in-presets'
import LatePresetPicker from '@/entities/attendance/components/LatePresetPicker.vue'
import BaseSelect from '@/shared/components/BaseSelect.vue'
import TextField from '@/shared/components/TextField.vue'
import CheckInTimelinePreview from './CheckInTimelinePreview.vue'
import TimeRangeField from './TimeRangeField.vue'

defineProps({
  roomOptions: { type: Array, required: true },
  errors: { type: Object, default: () => ({}) },
  disabled: { type: Boolean, default: false },
})
const values = defineModel({ type: Object, required: true })

const preset = ref(detectLatePreset(values.value))
// ได้ค่าชุดใหม่ (เปิด dialog ใหม่) → ตรวจ preset ใหม่ (watcher นี้ต้องอยู่ก่อน apply)
watch(
  () => values.value,
  (v) => (preset.value = detectLatePreset(v)),
)
watch(
  () => [values.value.startTime, values.value.endTime, preset.value],
  () => {
    if (preset.value === CUSTOM_PRESET) return
    const w = computeCheckInWindow(values.value.startTime, values.value.endTime, preset.value)
    if (!w) return
    const v = values.value
    v.checkInOpens = w.checkInOpens
    v.onTimeUntil = w.onTimeUntil
    v.lateUntil = w.lateUntil
    v.checkInCloses = w.checkInCloses
  },
)

/** ช่องเช็คชื่อที่ผู้ใช้แก้เอง → โหมดกำหนดเอง */
const manual = (key) =>
  computed({
    get: () => values.value[key],
    set: (x) => {
      if (x === values.value[key]) return
      preset.value = CUSTOM_PRESET
      values.value[key] = x
    },
  })
const opens = manual('checkInOpens')
const onTimeUntil = manual('onTimeUntil')
const lateUntil = manual('lateUntil')
const closes = manual('checkInCloses')
</script>

<template>
  <fieldset class="flex flex-col gap-4" :disabled="disabled">
    <div class="grid gap-4 sm:grid-cols-2">
      <TextField
        v-model="values.sessionDate"
        type="date"
        label="วันที่"
        required
        :error="errors.sessionDate"
      />
      <div>
        <BaseSelect
          v-model="values.roomId"
          label="ห้องเรียน *"
          :options="roomOptions"
          placeholder="เลือกห้อง"
        />
        <p v-if="errors.roomId" class="mt-1 text-xs text-danger">{{ errors.roomId }}</p>
      </div>
    </div>

    <TimeRangeField
      v-model:start="values.startTime"
      v-model:end="values.endTime"
      label="เวลาเรียน"
      :error="errors.time"
    />

    <fieldset>
      <legend class="mb-1.5 text-xs font-medium text-muted">
        ช่วงเช็คชื่อ <span class="text-danger">*</span>
      </legend>
      <LatePresetPicker v-model="preset" :disabled="disabled" class="mb-3" />
      <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <TextField v-model="opens" type="time" label="เปิดเช็คชื่อ" />
        <TextField v-model="onTimeUntil" type="time" label="ตรงเวลาถึง" />
        <TextField v-model="lateUntil" type="time" label="สายถึง" />
        <TextField v-model="closes" type="time" label="ปิดเช็คชื่อ" />
      </div>
      <p v-if="errors.window" class="mt-1 text-xs text-danger">{{ errors.window }}</p>
    </fieldset>

    <CheckInTimelinePreview
      :start-time="values.startTime"
      :end-time="values.endTime"
      :opens="values.checkInOpens"
      :closes="values.checkInCloses"
      :on-time-until="values.onTimeUntil"
      :late-until="values.lateUntil"
    />
  </fieldset>
</template>
