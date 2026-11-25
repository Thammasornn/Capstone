<script setup>
/**
 * แก้ / เลื่อนคาบที่ยังไม่เริ่ม (PATCH /class-sessions/{id}) หรือ เพิ่มคาบชดเชย / คาบพิเศษ (POST /sections/{id}/sessions)
 * mode 'create': ค่าเริ่มต้นตามตารางปกติของกลุ่ม · เลือกได้ว่าชดเชยคาบที่ยกเลิกคาบไหน (ไม่เลือก = คาบพิเศษ)
 * ตรวจฝั่ง FE ก่อนส่ง · error 400 / 409 ของ backend แสดงในฟอร์ม
 */
import { computed, ref, watch } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseDialog from '@/shared/components/BaseDialog.vue'
import BaseSelect from '@/shared/components/BaseSelect.vue'
import TextField from '@/shared/components/TextField.vue'
import { normalizeError } from '@/shared/api/errors'
import { formatThaiDate } from '@/shared/utils/datetime'
import { createSectionSession, rescheduleSession } from '../api'
import {
  SESSION_REASON_MAX,
  toSessionBody,
  validateReason,
  validateSessionValues,
  valuesFromSection,
  valuesFromSession,
} from '../composables/sessionForm'
import SessionForm from './SessionForm.vue'

const props = defineProps({
  mode: { type: String, default: 'reschedule' }, // 'reschedule' | 'create'
  sectionId: { type: String, required: true },
  /** คาบที่จะแก้ (mode reschedule) */
  session: { type: Object, default: null },
  /** SectionDetail — ค่าเริ่มต้นของคาบใหม่ */
  section: { type: Object, default: null },
  roomOptions: { type: Array, required: true },
  semester: { type: Object, default: () => ({}) },
  /** คาบที่ยกเลิกแล้วและยังไม่มีคาบชดเชย (ตัวเลือก "ชดเชยคาบ") */
  makeupCandidates: { type: Array, default: () => [] },
})
const open = defineModel('open', { type: Boolean, default: false })
const emit = defineEmits(['saved'])

const values = ref(valuesFromSection(null))
const reason = ref('')
const makeupFor = ref(/** @type {string | null} */ (null))
const errors = ref(/** @type {Record<string, string>} */ ({}))
const serverError = ref('')
const saving = ref(false)

watch(open, (v) => {
  if (!v) return
  values.value =
    props.mode === 'reschedule' && props.session
      ? valuesFromSession(props.session)
      : valuesFromSection(props.section)
  reason.value = ''
  makeupFor.value = null
  errors.value = {}
  serverError.value = ''
})

const title = computed(() =>
  props.mode === 'reschedule'
    ? `แก้ / เลื่อนคาบที่ ${props.session?.sessionNo ?? ''}`
    : 'เพิ่มคาบชดเชย / คาบพิเศษ',
)
const description = computed(() =>
  props.mode === 'reschedule' && props.session
    ? `เดิม ${formatThaiDate(props.session.sessionDate)} · ${props.session.startTime}–${props.session.endTime} · ห้อง ${props.session.roomCode} — แก้เฉพาะคาบนี้ ตารางเรียนของรายวิชาไม่เปลี่ยน`
    : 'ค่าเริ่มต้นตามตารางปกติของกลุ่ม · นักศึกษาที่ลงทะเบียนกลุ่มนี้เช็คชื่อได้ตามปกติ',
)
const makeupOptions = computed(() =>
  props.makeupCandidates.map((s) => ({
    value: s.sessionId,
    label: `คาบที่ ${s.sessionNo} · ${formatThaiDate(s.sessionDate)}${s.cancelReason ? ` (${s.cancelReason})` : ''}`,
  })),
)

async function submit() {
  const e = validateSessionValues(values.value, {
    semesterStart: props.semester.start,
    semesterEnd: props.semester.end,
  })
  const r = validateReason(reason.value)
  if (r) e.reason = r
  errors.value = e
  serverError.value = ''
  if (Object.keys(e).length) return
  saving.value = true
  try {
    const body = { ...toSessionBody(values.value), reason: reason.value.trim() }
    const result =
      props.mode === 'reschedule'
        ? await rescheduleSession(props.session.sessionId, body)
        : await createSectionSession(props.sectionId, {
            ...body,
            ...(makeupFor.value ? { makeupForSessionId: makeupFor.value } : {}),
          })
    open.value = false
    emit('saved', { result, mode: props.mode, makeup: Boolean(makeupFor.value) })
  } catch (err) {
    serverError.value = normalizeError(err).message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <BaseDialog v-model:open="open" :title="title" :description="description" wide>
    <form class="flex flex-col gap-4" novalidate @submit.prevent="submit">
      <BaseSelect
        v-if="mode === 'create' && makeupOptions.length"
        v-model="makeupFor"
        label="ชดเชยคาบที่ยกเลิก"
        :options="makeupOptions"
        placeholder="ไม่ใช่ — เป็นคาบพิเศษ"
      />
      <SessionForm
        v-model="values"
        :room-options="roomOptions"
        :errors="errors"
        :disabled="saving"
      />
      <TextField
        v-model="reason"
        label="เหตุผล"
        multiline
        required
        :maxlength="SESSION_REASON_MAX"
        :error="errors.reason"
        placeholder="เช่น ตรงกับวันหยุด / อาจารย์ติดประชุม"
      />
      <p
        v-if="serverError"
        role="alert"
        class="rounded-field bg-danger-bg px-3 py-2 text-sm text-danger"
      >
        {{ serverError }}
      </p>
    </form>
    <template #actions>
      <BaseButton variant="secondary" :disabled="saving" @click="open = false">ยกเลิก</BaseButton>
      <BaseButton :loading="saving" @click="submit">
        {{ mode === 'reschedule' ? 'บันทึกการแก้คาบ' : 'เพิ่มคาบ' }}
      </BaseButton>
    </template>
  </BaseDialog>
</template>
