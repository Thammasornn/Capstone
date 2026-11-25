<script setup>
/**
 * ปรับเวลาคาบที่เริ่มแล้ว — ใช้ทั้ง Room Live (เมนู "จัดการคาบ") และ Session Report ("แก้เวลาคาบ")
 *  mode = reanchor   เริ่มนับเวลาใหม่ (อาจารย์เข้าสาย) → Preview & Confirm
 *         extend     ยืดเวลาจบ → บันทึกเลย (ไม่กระทบผลเช็คชื่อ)
 *         window     ปรับช่วงตรงเวลา / สาย / ปิดเช็คชื่อ → Preview & Confirm
 *  Preview: "ดูผลกระทบ" = dryRun: true → "ยืนยัน" = เรียกซ้ำ dryRun: false (+ applyDowngrades)
 *  times = เวลาปัจจุบันของคาบ ("HH:mm[:ss]" · ช่องเช็คชื่ออาจไม่มีถ้า API ยังไม่ส่ง) · emit applied(AdjustResponse)
 */
import { computed, ref, watch } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseDialog from '@/shared/components/BaseDialog.vue'
import TextField from '@/shared/components/TextField.vue'
import { normalizeError } from '@/shared/api/errors'
import { formatTime } from '@/shared/utils/datetime'
import { adjustCheckInWindow, extendSession, reanchorSession } from '../api'
import SessionAdjustPreview from './SessionAdjustPreview.vue'

const props = defineProps({
  sessionId: { type: String, required: true },
  mode: {
    type: String,
    default: 'reanchor',
    validator: (v) => ['reanchor', 'extend', 'window'].includes(v),
  },
  /** @type {import('vue').PropType<Partial<import('../api').SessionTimes>>} */
  times: { type: Object, default: () => ({}) },
  /** คาบกำลังเรียน → เวลาเริ่มจริงค่าเริ่มต้น = ตอนนี้ · คาบจบแล้ว = เวลาเริ่มเดิม */
  inProgress: { type: Boolean, default: true },
  /** เช่น "INT101-001 · 25 พ.ย. 2569" */
  label: { type: String, default: '' },
})
const emit = defineEmits(['applied'])
const open = defineModel('open', { type: Boolean, default: false })

const TITLES = {
  reanchor: 'เริ่มนับเวลาใหม่ (อาจารย์เข้าสาย)',
  extend: 'ยืดเวลาจบ',
  window: 'ปรับช่วงเช็คชื่อ',
}
const title = computed(() => TITLES[props.mode])

const hhmm = (v) => (formatTime(v) === '-' ? '' : formatTime(v))
const nowHHmm = () => {
  const d = new Date()
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const actualStart = ref('')
const endTime = ref('')
const onTimeUntil = ref('')
const lateUntil = ref('')
const checkInCloses = ref('')
const reason = ref('')
const applyDowngrades = ref(false)
const preview = ref(/** @type {import('../api').AdjustResponse | null} */ (null))
const busy = ref(false)
const error = ref('')

watch(open, (value) => {
  if (!value) return
  const t = props.times
  actualStart.value = props.inProgress ? nowHHmm() : hhmm(t.startTime)
  endTime.value = hhmm(t.endTime)
  onTimeUntil.value = hhmm(t.onTimeUntil)
  lateUntil.value = hhmm(t.lateUntil)
  checkInCloses.value = hhmm(t.checkInCloses)
  reason.value = ''
  applyDowngrades.value = false
  preview.value = null
  error.value = ''
})

const currentTimes = computed(() =>
  [
    ['เรียน', `${formatTime(props.times.startTime)}–${formatTime(props.times.endTime)}`],
    ['เปิดเช็คชื่อ', formatTime(props.times.checkInOpens)],
    ['ตรงเวลาถึง', formatTime(props.times.onTimeUntil)],
    ['สายถึง', formatTime(props.times.lateUntil)],
    ['ปิดเช็คชื่อ', formatTime(props.times.checkInCloses)],
  ].filter(([, v]) => v !== '-' && v !== '-–-'),
)

/** ตรวจช่องว่างก่อนส่ง (ลำดับเวลา backend ตรวจและตอบข้อความเอง) */
function missing() {
  if (props.mode === 'reanchor' && !actualStart.value) return 'กรุณาระบุเวลาเริ่มจริง'
  if (props.mode === 'extend' && !endTime.value) return 'กรุณาระบุเวลาจบใหม่'
  if (props.mode === 'window' && !(onTimeUntil.value && lateUntil.value && checkInCloses.value))
    return 'กรุณาระบุเวลาให้ครบ 3 ช่อง'
  if (!reason.value.trim()) return 'กรุณาระบุเหตุผล'
  return ''
}

function call(dryRun) {
  const base = { reason: reason.value.trim(), dryRun }
  if (props.mode === 'extend')
    return extendSession(props.sessionId, { ...base, endTime: endTime.value })
  const extra = { applyDowngrades: applyDowngrades.value }
  if (props.mode === 'reanchor')
    return reanchorSession(props.sessionId, { ...base, ...extra, actualStart: actualStart.value })
  return adjustCheckInWindow(props.sessionId, {
    ...base,
    ...extra,
    onTimeUntil: onTimeUntil.value,
    lateUntil: lateUntil.value,
    checkInCloses: checkInCloses.value,
  })
}

async function submit() {
  error.value = missing()
  if (error.value) return
  busy.value = true
  try {
    if (props.mode === 'extend' || preview.value) {
      const result = await call(false)
      emit('applied', result)
      open.value = false
    } else {
      preview.value = await call(true)
    }
  } catch (e) {
    error.value = normalizeError(e).message
  } finally {
    busy.value = false
  }
}

function backToForm() {
  preview.value = null
  error.value = ''
}
</script>

<template>
  <BaseDialog v-model:open="open" :title="title" :description="label">
    <form id="session-adjust-form" class="flex flex-col gap-4" @submit.prevent="submit">
      <template v-if="!preview">
        <div
          v-if="currentTimes.length"
          class="flex flex-wrap gap-x-4 gap-y-1 rounded-field bg-page px-3 py-2 text-xs text-muted"
        >
          <span class="w-full font-semibold text-ink">เวลาปัจจุบันของคาบ</span>
          <span v-for="[k, v] in currentTimes" :key="k">{{ k }} {{ v }}</span>
        </div>

        <TextField
          v-if="mode === 'reanchor'"
          v-model="actualStart"
          type="time"
          label="เวลาเริ่มจริง"
          required
          hint="ช่วงเช็คชื่อทั้งหมดเลื่อนตามเท่ากัน · เวลาเลิกเรียนคงเดิม"
        />
        <TextField
          v-if="mode === 'extend'"
          v-model="endTime"
          type="time"
          label="เวลาจบใหม่"
          required
          hint="ไม่กระทบผลเช็คชื่อ"
        />
        <div v-if="mode === 'window'" class="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <TextField v-model="onTimeUntil" type="time" label="ตรงเวลาถึง" required />
          <TextField v-model="lateUntil" type="time" label="สายถึง" required />
          <TextField v-model="checkInCloses" type="time" label="ปิดเช็คชื่อ" required />
        </div>
        <TextField
          v-model="reason"
          label="เหตุผล"
          multiline
          required
          :maxlength="500"
          :placeholder="
            mode === 'reanchor'
              ? 'เช่น อาจารย์ติดประชุม เริ่มสอนช้า'
              : mode === 'extend'
                ? 'เช่น สอนชดเชยต่อท้ายคาบ'
                : 'เช่น ฝนตกหนัก รถติด'
          "
        />
      </template>

      <SessionAdjustPreview v-else v-model:apply-downgrades="applyDowngrades" :result="preview" />

      <p v-if="error" role="alert" class="text-sm text-danger">{{ error }}</p>
    </form>
    <template #actions>
      <BaseButton v-if="preview" variant="secondary" :disabled="busy" @click="backToForm">
        ย้อนกลับ
      </BaseButton>
      <BaseButton v-else variant="secondary" @click="open = false">ยกเลิก</BaseButton>
      <BaseButton type="submit" form="session-adjust-form" :loading="busy">
        {{ mode === 'extend' ? 'บันทึก' : preview ? 'ยืนยัน' : 'ดูผลกระทบ' }}
      </BaseButton>
    </template>
  </BaseDialog>
</template>
