<script setup>
/**
 * B8 อาจารย์เช็คชื่อด้วยมือ — ใช้ทั้ง Room Live และ Session Report
 * v-model:open · student = { studentId, studentCode, studentName, status, checkInTime }
 * emit saved(ผลจาก API) หลังบันทึกสำเร็จ · Backend บังคับเหตุผล และบันทึก audit_logs
 */
import { computed, ref, watch } from 'vue'
import BaseDialog from '@/shared/components/BaseDialog.vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseSelect from '@/shared/components/BaseSelect.vue'
import TextField from '@/shared/components/TextField.vue'
import { setManualAttendance } from '../api'
import { ATTENDANCE_STATUS, ATTENDANCE_STATUS_ORDER } from '../status'

const props = defineProps({
  sessionId: { type: String, required: true },
  /** วันที่ของคาบ "2026-11-16" ใช้ประกอบเวลาเช็คชื่อ */
  sessionDate: { type: String, required: true },
  student: { type: Object, default: null },
})
const emit = defineEmits(['saved'])
const open = defineModel('open', { type: Boolean, default: false })

const status = ref('on_time')
const time = ref('')
const reason = ref('')
const saving = ref(false)
const error = ref('')

const statusOptions = ATTENDANCE_STATUS_ORDER.map((s) => ({
  value: s,
  label: ATTENDANCE_STATUS[s].label,
}))

watch(open, (value) => {
  if (!value || !props.student) return
  status.value = props.student.status === 'absent' ? 'on_time' : props.student.status
  time.value = props.student.checkInTime?.slice(11, 16) ?? ''
  reason.value = ''
  error.value = ''
})

const needsTime = computed(() => status.value !== 'absent')

async function save() {
  if (!reason.value.trim()) {
    error.value = 'กรุณาระบุเหตุผล'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const result = await setManualAttendance(props.sessionId, props.student.studentId, {
      status: status.value,
      reason: reason.value.trim(),
      checkInTime: needsTime.value && time.value ? `${props.sessionDate}T${time.value}:00` : null,
    })
    emit('saved', result)
    open.value = false
  } catch (e) {
    error.value = e?.message ?? 'บันทึกไม่สำเร็จ'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <BaseDialog
    v-model:open="open"
    title="เช็คชื่อด้วยมือ"
    :description="student ? `${student.studentCode} ${student.studentName}` : ''"
  >
    <form id="manual-attendance-form" class="flex flex-col gap-4" @submit.prevent="save">
      <BaseSelect v-model="status" label="สถานะ" :options="statusOptions" />
      <TextField
        v-if="needsTime"
        v-model="time"
        type="time"
        label="เวลาเข้าเรียน"
        hint="เว้นว่าง = ใช้เวลาเดิม หรือเวลาเริ่มคาบ"
      />
      <TextField
        v-model="reason"
        label="เหตุผล"
        multiline
        required
        :maxlength="500"
        placeholder="เช่น กล้องจับใบหน้าไม่ได้ / นักศึกษาไม่ได้ให้ความยินยอม"
      />
      <p v-if="error" role="alert" class="text-sm text-danger">{{ error }}</p>
    </form>
    <template #actions>
      <BaseButton variant="secondary" @click="open = false">ยกเลิก</BaseButton>
      <BaseButton type="submit" form="manual-attendance-form" :loading="saving">บันทึก</BaseButton>
    </template>
  </BaseDialog>
</template>
