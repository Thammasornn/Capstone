<script setup>
/**
 * ฟอร์มคาบเรียน 1 คาบ (ใช้ร่วม: แก้ / เลื่อนคาบ · คาบชดเชย / คาบพิเศษ · ยกเลิก + ชดเชย)
 * v-model = ค่าตาม sessionForm.js · errors = ผลของ validateSessionValues · ไม่มีช่องเหตุผล (dialog ใส่เอง)
 */
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
      <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <TextField v-model="values.checkInOpens" type="time" label="เปิดเช็คชื่อ" />
        <TextField v-model="values.onTimeUntil" type="time" label="ตรงเวลาถึง" />
        <TextField v-model="values.lateUntil" type="time" label="สายถึง" />
        <TextField v-model="values.checkInCloses" type="time" label="ปิดเช็คชื่อ" />
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
