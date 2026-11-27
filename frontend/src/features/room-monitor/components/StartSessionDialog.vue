<script setup>
/**
 * เริ่มคาบ "ตอนนี้" ในห้องนี้ — เลือกกลุ่มเรียน (lecturer เห็นเฉพาะที่ตัวเองสอน · admin เห็นทุกกลุ่ม)
 * เวลาเรียน / ช่วงตรงเวลา / ช่วงสาย ใช้ตามที่ตั้งไว้ในกลุ่มเรียน แต่นับจากตอนนี้ · emit started(ClassSessionInfo)
 * เลือกกลุ่มแล้วโหลดคาบของกลุ่ม (GET /sections/{id}/sessions) เพื่อบอกก่อนกด (start-session-plan.js):
 *   คาบวันนี้จบแล้ว → "เปิดคาบอีกครั้ง" · กำลังเรียนห้องอื่น → "ย้ายมาห้องนี้" · กำลังเรียนห้องนี้ → ปิดปุ่ม
 *   วันที่ที่จะนับ (เวลาไทย) แสดงเสมอ · นอกวัน / เวลาเรียนตามตาราง → เตือนสีเหลือง (ไม่บล็อก)
 */
import { computed, ref, watch } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseDialog from '@/shared/components/BaseDialog.vue'
import BaseSelect from '@/shared/components/BaseSelect.vue'
import { getSectionSessions, getStartableCourses, startSessionNow } from '../api'
import { START_BUTTON_LABEL, planStart } from '../start-session-plan'

const props = defineProps({
  roomId: { type: String, required: true },
  roomCode: { type: String, required: true },
})
const emit = defineEmits(['started'])
const open = defineModel('open', { type: Boolean, default: false })

const options = ref(/** @type {{ value: string, label: string }[]} */ ([]))
/** sectionId → ข้อมูลกลุ่มเรียน (classDays, startTime, endTime) */
const sections = ref(/** @type {Record<string, any>} */ ({}))
const sectionId = ref(/** @type {string | null} */ (null))
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const now = ref(new Date())

watch(open, async (value) => {
  if (!value) return
  error.value = ''
  now.value = new Date()
  loading.value = true
  try {
    const courses = await getStartableCourses()
    const map = {}
    const rows = courses.flatMap((c) =>
      c.sections.map((s) => {
        map[s.sectionId] = s
        return {
          value: s.sectionId,
          label:
            `${c.courseCode}-${s.sectionCode} ${c.courseName}` +
            (s.roomCode && s.roomCode !== props.roomCode ? ` (ปกติเรียนห้อง ${s.roomCode})` : ''),
          here: s.roomId === props.roomId,
        }
      }),
    )
    rows.sort((a, b) => Number(b.here) - Number(a.here) || a.label.localeCompare(b.label))
    sections.value = map
    options.value = rows.map(({ value, label }) => ({ value, label }))
    sectionId.value = rows[0]?.value ?? null
  } catch (e) {
    error.value = e?.message ?? 'โหลดกลุ่มเรียนไม่สำเร็จ'
  } finally {
    loading.value = false
  }
})

// ---------- คาบของวันนี้ของกลุ่มที่เลือก ----------
const sessions = ref(/** @type {any[] | null} */ (null))
const sessionsError = ref('')
const checking = ref(false)
let token = 0
watch(sectionId, async (id) => {
  const mine = ++token
  sessions.value = null
  sessionsError.value = ''
  if (!id) return
  checking.value = true
  try {
    const r = await getSectionSessions(id)
    if (mine === token) sessions.value = r.sessions ?? []
  } catch {
    if (mine === token) {
      sessions.value = []
      sessionsError.value = 'ตรวจคาบของวันนี้ไม่ได้ — กดเริ่มได้ ระบบจะตรวจอีกครั้ง'
    }
  } finally {
    if (mine === token) checking.value = false
  }
})

const plan = computed(() =>
  sectionId.value
    ? planStart({
        section: sections.value[sectionId.value] ?? null,
        sessions: sessions.value ?? [],
        roomId: props.roomId,
        now: now.value,
      })
    : null,
)

const empty = computed(() => !loading.value && !error.value && options.value.length === 0)

async function start() {
  if (!sectionId.value) {
    error.value = 'กรุณาเลือกกลุ่มเรียน'
    return
  }
  if (plan.value?.mode === 'blocked') return
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

      <template v-if="plan && !loading">
        <p class="text-sm">
          นับเป็นคาบของวันที่ <span class="font-semibold">{{ plan.dateLabel }}</span>
        </p>
        <p v-if="checking" class="text-sm text-muted">กำลังตรวจคาบของวันนี้…</p>
        <template v-else>
          <p
            v-if="plan.message"
            role="status"
            class="rounded-field px-3 py-2 text-sm"
            :class="
              plan.mode === 'blocked' ? 'bg-danger-bg text-danger' : 'bg-primary/10 text-primary'
            "
          >
            {{ plan.message }}
          </p>
          <p v-if="sessionsError" class="text-xs text-muted">{{ sessionsError }}</p>
        </template>
        <p
          v-if="plan.warning"
          role="alert"
          class="rounded-field bg-warning-bg px-3 py-2 text-sm text-warning"
        >
          {{ plan.warning }}
        </p>
      </template>

      <p v-if="error" role="alert" class="text-sm text-danger">{{ error }}</p>
    </form>
    <template #actions>
      <BaseButton variant="secondary" @click="open = false">ยกเลิก</BaseButton>
      <BaseButton
        type="submit"
        form="start-session-form"
        :loading="saving"
        :disabled="loading || checking || !options.length || plan?.mode === 'blocked'"
      >
        {{ START_BUTTON_LABEL[plan?.mode ?? 'create'] }}
      </BaseButton>
    </template>
  </BaseDialog>
</template>
