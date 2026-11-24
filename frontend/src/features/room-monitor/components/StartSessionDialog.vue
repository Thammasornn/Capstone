<script setup>
/**
 * เริ่มคาบ "ตอนนี้" ในห้องนี้ — เลือกกลุ่มเรียน (lecturer เห็นเฉพาะที่ตัวเองสอน · admin เห็นทุกกลุ่ม)
 * เวลาเรียน / ช่วงตรงเวลา / ช่วงสาย ใช้ตามที่ตั้งไว้ในกลุ่มเรียน แต่นับจากตอนนี้ · emit started(ClassSessionInfo)
 */
import { computed, ref, watch } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseDialog from '@/shared/components/BaseDialog.vue'
import BaseSelect from '@/shared/components/BaseSelect.vue'
import { getStartableCourses, startSessionNow } from '../api'

const props = defineProps({
  roomId: { type: String, required: true },
  roomCode: { type: String, required: true },
})
const emit = defineEmits(['started'])
const open = defineModel('open', { type: Boolean, default: false })

const options = ref(/** @type {{ value: string, label: string }[]} */ ([]))
const sectionId = ref(/** @type {string | null} */ (null))
const loading = ref(false)
const saving = ref(false)
const error = ref('')

watch(open, async (value) => {
  if (!value) return
  error.value = ''
  loading.value = true
  try {
    const courses = await getStartableCourses()
    const rows = courses.flatMap((c) =>
      c.sections.map((s) => ({
        value: s.sectionId,
        label:
          `${c.courseCode}-${s.sectionCode} ${c.courseName}` +
          (s.roomCode && s.roomCode !== props.roomCode ? ` (ปกติเรียนห้อง ${s.roomCode})` : ''),
        here: s.roomId === props.roomId,
      })),
    )
    rows.sort((a, b) => Number(b.here) - Number(a.here) || a.label.localeCompare(b.label))
    options.value = rows.map(({ value, label }) => ({ value, label }))
    sectionId.value = rows[0]?.value ?? null
  } catch (e) {
    error.value = e?.message ?? 'โหลดกลุ่มเรียนไม่สำเร็จ'
  } finally {
    loading.value = false
  }
})

const empty = computed(() => !loading.value && !error.value && options.value.length === 0)

async function start() {
  if (!sectionId.value) {
    error.value = 'กรุณาเลือกกลุ่มเรียน'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const result = await startSessionNow(props.roomId, sectionId.value)
    emit('started', result)
    open.value = false
  } catch (e) {
    error.value = e?.message ?? 'เริ่มคาบไม่สำเร็จ'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <BaseDialog
    v-model:open="open"
    :title="`เริ่มคาบในห้อง ${roomCode} ตอนนี้`"
    description="เปิดเช็คชื่อทันที — ช่วงตรงเวลา / สาย และความยาวคาบตามที่ตั้งไว้ในกลุ่มเรียน นับจากตอนนี้"
  >
    <form id="start-session-form" class="flex flex-col gap-4" @submit.prevent="start">
      <p v-if="loading" class="text-sm text-muted">กำลังโหลดกลุ่มเรียน…</p>
      <p v-else-if="empty" class="text-sm text-muted">ไม่มีกลุ่มเรียนที่คุณเริ่มคาบได้</p>
      <BaseSelect
        v-else-if="options.length"
        v-model="sectionId"
        label="กลุ่มเรียน"
        :options="options"
      />
      <p v-if="error" role="alert" class="text-sm text-danger">{{ error }}</p>
    </form>
    <template #actions>
      <BaseButton variant="secondary" @click="open = false">ยกเลิก</BaseButton>
      <BaseButton
        type="submit"
        form="start-session-form"
        :loading="saving"
        :disabled="loading || !options.length"
      >
        เริ่มคาบ
      </BaseButton>
    </template>
  </BaseDialog>
</template>
