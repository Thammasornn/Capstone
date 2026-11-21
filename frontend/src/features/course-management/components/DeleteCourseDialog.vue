<script setup>
/** ยืนยันลบรายวิชา (B5 DELETE — soft delete) */
import { ref } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseDialog from '@/shared/components/BaseDialog.vue'
import { deleteCourse } from '../api'

const props = defineProps({ course: { type: Object, default: null } })
const emit = defineEmits(['deleted'])
const open = defineModel('open', { type: Boolean, default: false })
const deleting = ref(false)
const error = ref('')

async function confirm() {
  deleting.value = true
  error.value = ''
  try {
    await deleteCourse(props.course.courseId)
    emit('deleted', props.course)
    open.value = false
  } catch (e) {
    error.value = e?.message ?? 'ลบไม่สำเร็จ'
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <BaseDialog
    v-model:open="open"
    title="ลบรายวิชา"
    :description="course ? `${course.courseCode} ${course.courseName}` : ''"
  >
    <p class="text-sm">
      รายวิชาจะถูกซ่อนจากทุกหน้า และคาบที่ยังไม่เริ่มจะถูกยกเลิก
      ประวัติการเช็คชื่อที่ผ่านมายังเก็บไว้
    </p>
    <p v-if="error" role="alert" class="mt-3 text-sm text-danger">{{ error }}</p>
    <template #actions>
      <BaseButton variant="secondary" @click="open = false">ยกเลิก</BaseButton>
      <BaseButton variant="danger" :loading="deleting" @click="confirm">ลบรายวิชา</BaseButton>
    </template>
  </BaseDialog>
</template>
