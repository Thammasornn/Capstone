<script setup>
/** B6 สวิตช์เช็คชื่อด้วยกล้องของกลุ่มเรียน — บันทึกทันที (มีผลกับกล้องทันที) */
import { ref, watch } from 'vue'
import BaseToggle from '@/shared/components/BaseToggle.vue'
import { useToast } from '@/shared/composables/useToast'
import { setCameraAttendance } from '../api'

const props = defineProps({
  sectionId: { type: String, required: true },
  enabled: { type: Boolean, required: true },
  label: { type: String, default: 'เช็คชื่อด้วยกล้อง' },
})
const emit = defineEmits(['changed'])
const toast = useToast()
const value = ref(props.enabled)
const saving = ref(false)
watch(
  () => props.enabled,
  (v) => (value.value = v),
)

async function onChange(next) {
  saving.value = true
  try {
    const result = await setCameraAttendance(props.sectionId, next)
    value.value = result.cameraAttendanceEnabled
    emit('changed', result.cameraAttendanceEnabled)
    toast.show(next ? 'เปิดเช็คชื่อด้วยกล้องแล้ว' : 'ปิดเช็คชื่อด้วยกล้องแล้ว', { tone: 'success' })
  } catch (e) {
    value.value = props.enabled
    toast.show(e?.message ?? 'บันทึกไม่สำเร็จ', { tone: 'danger' })
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <BaseToggle
    :model-value="value"
    :label="label"
    :loading="saving"
    hide-label
    @update:model-value="onChange"
  />
</template>
