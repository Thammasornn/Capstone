<script setup>
/** ถอนนักศึกษาออกจากกลุ่ม (soft) — ผลเช็คชื่อเดิมเก็บไว้ · เหตุผลไม่บังคับ ≤ 500 */
import { ref, watch } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseDialog from '@/shared/components/BaseDialog.vue'
import TextField from '@/shared/components/TextField.vue'
import { normalizeError } from '@/shared/api/errors'
import { ROSTER_REASON_MAX, dropStudent } from '../api'

const props = defineProps({
  sectionId: { type: String, required: true },
  /** @type {import('vue').PropType<import('../api').EnrolledStudent | null>} */
  student: { type: Object, default: null },
})
const open = defineModel('open', { type: Boolean, default: false })
const emit = defineEmits(['dropped'])

const reason = ref('')
const error = ref('')
const busy = ref(false)
watch(open, (v) => {
  if (v) {
    reason.value = ''
    error.value = ''
  }
})

async function submit() {
  error.value = ''
  if (reason.value.trim().length > ROSTER_REASON_MAX) {
    error.value = `เหตุผลยาวได้ไม่เกิน ${ROSTER_REASON_MAX} ตัวอักษร`
    return
  }
  busy.value = true
  try {
    const result = await dropStudent(props.sectionId, props.student.studentId, reason.value.trim())
    open.value = false
    emit('dropped', { ...result, studentName: props.student.fullName })
  } catch (e) {
    error.value = normalizeError(e).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <BaseDialog
    v-model:open="open"
    title="ถอนนักศึกษาออกจากกลุ่มเรียน?"
    :description="student ? `${student.studentCode} ${student.fullName}` : ''"
  >
    <div class="flex flex-col gap-3">
      <p class="rounded-field bg-warning-bg px-3 py-2 text-sm text-warning">
        ผลเช็คชื่อที่ผ่านมาของนักศึกษาคนนี้ยังเก็บไว้ · คาบต่อจากนี้จะไม่มีชื่อและไม่ถูกลงขาด ·
        เพิ่มกลับได้ภายหลัง
      </p>
      <TextField
        v-model="reason"
        label="เหตุผล (ไม่บังคับ)"
        multiline
        :maxlength="ROSTER_REASON_MAX"
        placeholder="เช่น ถอนรายวิชา (W)"
        :disabled="busy"
      />
      <p v-if="error" role="alert" class="rounded-field bg-danger-bg px-3 py-2 text-sm text-danger">
        {{ error }}
      </p>
    </div>
    <template #actions>
      <BaseButton variant="secondary" :disabled="busy" @click="open = false">ไม่ถอน</BaseButton>
      <BaseButton variant="danger" :loading="busy" @click="submit">ถอนออกจากกลุ่ม</BaseButton>
    </template>
  </BaseDialog>
</template>
