<script setup>
/**
 * ยกเลิกคาบ (POST /class-sessions/{id}/cancel) + เหตุผล
 * ติ๊ก "สร้างคาบชดเชยเลย" → ฟอร์มคาบชดเชยใน dialog เดียวกัน (ส่งคำขอเดียว: ชดเชยไม่ผ่าน = ไม่ยกเลิก)
 * คาบที่มีผลเช็คชื่อแล้ว → แจ้งว่าผลถูกเก็บไว้แต่ไม่นับในสถิติ
 */
import { computed, ref, watch } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseDialog from '@/shared/components/BaseDialog.vue'
import TextField from '@/shared/components/TextField.vue'
import { normalizeError } from '@/shared/api/errors'
import { formatThaiDate } from '@/shared/utils/datetime'
import { cancelSession } from '../api'
import {
  SESSION_REASON_MAX,
  toSessionBody,
  validateReason,
  validateSessionValues,
  valuesFromSection,
} from '../composables/sessionForm'
import SessionForm from './SessionForm.vue'

const props = defineProps({
  session: { type: Object, default: null },
  section: { type: Object, default: null },
  roomOptions: { type: Array, required: true },
  semester: { type: Object, default: () => ({}) },
})
const open = defineModel('open', { type: Boolean, default: false })
const emit = defineEmits(['cancelled'])

const reason = ref('')
const withMakeup = ref(false)
const makeup = ref(valuesFromSection(null))
const errors = ref(/** @type {Record<string, string>} */ ({}))
const serverError = ref('')
const saving = ref(false)

watch(open, (v) => {
  if (!v) return
  reason.value = ''
  withMakeup.value = false
  makeup.value = valuesFromSection(props.section)
  errors.value = {}
  serverError.value = ''
})

const recorded = computed(() => {
  const a = props.session?.attendance
  return a ? a.onTime + a.late + a.absent : 0
})
const inProgress = computed(() => props.session?.sessionStatus === 'in_progress')

async function submit() {
  /** @type {Record<string, string>} */
  const e = withMakeup.value
    ? validateSessionValues(makeup.value, {
        semesterStart: props.semester.start,
        semesterEnd: props.semester.end,
      })
    : {}
  const r = validateReason(reason.value)
  if (r) e.reason = r
  errors.value = e
  serverError.value = ''
  if (Object.keys(e).length) return
  saving.value = true
  try {
    const result = await cancelSession(props.session.sessionId, {
      reason: reason.value.trim(),
      ...(withMakeup.value ? { makeup: toSessionBody(makeup.value) } : {}),
    })
    open.value = false
    emit('cancelled', result)
  } catch (err) {
    serverError.value = normalizeError(err).message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <BaseDialog
    v-model:open="open"
    :title="`ยกเลิกคาบที่ ${session?.sessionNo ?? ''}?`"
    :description="
      session
        ? `${formatThaiDate(session.sessionDate)} · ${session.startTime}–${session.endTime} · ห้อง ${session.roomCode}`
        : ''
    "
    :wide="withMakeup"
  >
    <form class="flex flex-col gap-4" novalidate @submit.prevent="submit">
      <p
        v-if="inProgress"
        role="alert"
        class="rounded-field bg-warning-bg px-3 py-2 text-sm text-warning"
      >
        คาบนี้กำลังเรียนอยู่ ยกเลิกแล้วกล้องจะหยุดเช็คชื่อทันที
      </p>
      <p
        v-if="recorded"
        role="alert"
        class="rounded-field bg-warning-bg px-3 py-2 text-sm text-warning"
      >
        คาบนี้มีผลเช็คชื่อแล้ว {{ recorded }} คน — ผลเช็คชื่อจะถูกเก็บไว้ แต่ไม่นับในสถิติ
      </p>
      <TextField
        v-model="reason"
        label="เหตุผล"
        multiline
        required
        :maxlength="SESSION_REASON_MAX"
        :error="errors.reason"
        placeholder="เช่น วันหยุดราชการ / อาจารย์ลาป่วย"
      />
      <label class="flex items-start gap-2 text-sm">
        <input v-model="withMakeup" type="checkbox" class="mt-0.5 size-4" :disabled="saving" />
        <span>สร้างคาบชดเชยเลย</span>
      </label>
      <div v-if="withMakeup" class="rounded-field border border-line p-4">
        <p class="mb-3 text-xs text-muted">
          คาบชดเชย (ค่าเริ่มต้นตามตารางปกติของกลุ่ม) — ถ้าคาบชดเชยบันทึกไม่ได้ จะไม่ยกเลิกคาบเดิม
        </p>
        <SessionForm
          v-model="makeup"
          :room-options="roomOptions"
          :errors="errors"
          :disabled="saving"
        />
      </div>
      <p
        v-if="serverError"
        role="alert"
        class="rounded-field bg-danger-bg px-3 py-2 text-sm text-danger"
      >
        {{ serverError }}
      </p>
    </form>
    <template #actions>
      <BaseButton variant="secondary" :disabled="saving" @click="open = false"
        >ไม่ยกเลิก</BaseButton
      >
      <BaseButton variant="danger" :loading="saving" @click="submit">
        {{ withMakeup ? 'ยกเลิกและสร้างคาบชดเชย' : 'ยกเลิกคาบ' }}
      </BaseButton>
    </template>
  </BaseDialog>
</template>
