<script setup>
/**
 * เพิ่ม / แก้ไขรายวิชา (B5) — ตารางเรียน ห้อง ช่วงเช็คชื่อ และสวิตช์กล้องผูกที่กลุ่มเรียน
 * แก้ตารางเรียนแล้วมีผลกับคาบที่ยังไม่เริ่มและยังไม่ถูกแก้รายคาบ (D2)
 * เลื่อน / ยกเลิก / ชดเชยคาบ ที่หน้า "คาบเรียน" ของแต่ละกลุ่มเรียน (SectionSessionsPage)
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseCard from '@/shared/components/BaseCard.vue'
import BaseSelect from '@/shared/components/BaseSelect.vue'
import BaseToggle from '@/shared/components/BaseToggle.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import TextField from '@/shared/components/TextField.vue'
import { useAsyncState } from '@/shared/composables/useAsyncState'
import { useToast } from '@/shared/composables/useToast'
import { formatThaiDate, formatTime } from '@/shared/utils/datetime'
import { useCourseStore } from '@/entities/course/store'
import { useAuthStore } from '@/features/auth/store'
import { getLecturers, getRoomOptions } from '../api'
import { useCourseForm } from '../composables/useCourseForm'
import CheckInTimelinePreview from '../components/CheckInTimelinePreview.vue'
import CheckInWindowPicker from '../components/CheckInWindowPicker.vue'
import ClassDaysPicker from '../components/ClassDaysPicker.vue'
import ScheduleSummaryCard from '../components/ScheduleSummaryCard.vue'
import TimeRangeField from '../components/TimeRangeField.vue'

const props = defineProps({ courseId: { type: String, default: null } })
const router = useRouter()
const toast = useToast()
const auth = useAuthStore()
const courseStore = useCourseStore()
const isAdmin = computed(() => auth.role === 'admin')

const {
  form,
  errors,
  loading,
  saving,
  serverError,
  loadError,
  isEdit,
  addSection,
  removeSection,
  submit,
  load,
} = useCourseForm(props.courseId, { requireLecturer: isAdmin.value })

const rooms = useAsyncState(getRoomOptions)
const lecturers = useAsyncState(getLecturers, { immediate: isAdmin.value })
const roomOptions = computed(() =>
  (rooms.data.value ?? []).map((r) => ({
    value: r.roomId,
    label: `${r.roomCode}${r.building ? ` · ${r.building}` : ''}${r.capacity ? ` (${r.capacity} ที่)` : ''}`,
  })),
)
const existingSections = computed(() => form.sections.filter((s) => s.sectionId))
const lecturerOptions = computed(() =>
  (lecturers.data.value ?? []).map((l) => ({ value: l.lecturerId, label: l.name })),
)

async function onSubmit() {
  const saved = await submit()
  if (!saved) {
    toast.show('ยังบันทึกไม่ได้ ตรวจสอบช่องที่มีข้อความสีแดง', { tone: 'danger' })
    return
  }
  toast.show(isEdit.value ? 'บันทึกการแก้ไขแล้ว' : `เพิ่ม ${saved.courseCode} แล้ว`, {
    tone: 'success',
  })
  courseStore.load({ force: true }).catch(() => {})
  // PUT: คาบที่เริ่มแล้ว / แก้รายคาบไว้ ไม่ถูกแก้ตามตารางใหม่ → แจ้งให้ชัดก่อนกลับหน้ารายการ
  const notChanged = saved.startedSessionsNotChanged ?? []
  const kept = saved.overriddenSessionsKept ?? []
  if (notChanged.length || kept.length) {
    notChangedSessions.value = notChanged
    keptSessions.value = kept
    savedSections.value = saved.sections ?? []
    window.scrollTo({ top: 0, behavior: 'smooth' })
    return
  }
  router.push({ name: 'courses-manage' })
}

/** @type {import('vue').Ref<Array<{ sessionId: string, sessionDate: string, sectionCode: string, sessionStatus: string, startTime: string, endTime: string }>>} */
const notChangedSessions = ref([])
const NOT_CHANGED_REASON = {
  in_progress: 'กำลังเรียนอยู่',
  completed: 'จบไปแล้ว',
}
/** @type {import('vue').Ref<Array<{ sessionId: string, sessionDate: string, sectionCode: string, sessionStatus: string, isExtra: boolean }>>} */
const keptSessions = ref([])
const savedSections = ref(/** @type {any[]} */ ([]))
function keptText(s) {
  const why =
    s.sessionStatus === 'cancelled'
      ? 'ยกเลิกไว้'
      : s.isExtra
        ? 'เป็นคาบชดเชย / คาบพิเศษ'
        : 'แก้รายคาบไว้ (เช่น เลื่อนวัน)'
  return `คาบวันที่ ${formatThaiDate(s.sessionDate)} (กลุ่ม ${s.sectionCode}) ${why} จึงคงตามที่แก้`
}
/** ลิงก์หน้าคาบเรียนของกลุ่ม (จับคู่ด้วย sectionCode) */
function sessionsLink(sectionCode) {
  const sec = savedSections.value.find((x) => x.sectionCode === sectionCode)
  return sec && props.courseId
    ? { name: 'section-sessions', params: { courseId: props.courseId, sectionId: sec.sectionId } }
    : null
}
const hasNotice = computed(() => notChangedSessions.value.length + keptSessions.value.length > 0)

function notChangedText(s) {
  const why = NOT_CHANGED_REASON[s.sessionStatus] ?? 'เริ่มไปแล้ว'
  return `คาบวันที่ ${formatThaiDate(s.sessionDate)} (กลุ่ม ${s.sectionCode} · ${formatTime(s.startTime)}–${formatTime(s.endTime)}) ${why} จึงไม่ถูกแก้`
}
</script>

<template>
  <section class="mx-auto flex max-w-4xl flex-col gap-6">
    <div>
      <RouterLink :to="{ name: 'courses-manage' }" class="text-sm text-muted hover:text-ink"
        >← รายวิชา</RouterLink
      >
      <h1 class="mt-2 text-[28px] font-bold">{{ isEdit ? 'แก้ไขรายวิชา' : 'เพิ่มรายวิชา' }}</h1>
      <p v-if="isEdit" class="mt-1 text-sm text-muted">
        แก้ตารางเรียนที่นี่มีผลกับคาบที่ยังไม่เริ่มและยังไม่ถูกแก้รายคาบ · เลื่อน / ยกเลิก /
        ชดเชยคาบ ที่หน้า
        <template v-if="existingSections.length === 1">
          <RouterLink
            :to="{
              name: 'section-sessions',
              params: { courseId, sectionId: existingSections[0].sectionId },
            }"
            class="font-semibold text-primary hover:underline"
            >คาบเรียน</RouterLink
          >
        </template>
        <template v-else>คาบเรียน (ลิงก์ในแต่ละกลุ่มเรียนด้านล่าง)</template>
      </p>
    </div>

    <div
      v-if="hasNotice"
      role="status"
      class="flex flex-col gap-3 rounded-card border border-warning bg-warning-bg px-4 py-4 text-sm text-warning"
    >
      <p class="font-semibold">บันทึกการแก้ไขแล้ว แต่มีคาบที่ไม่ได้เปลี่ยนตามตารางใหม่</p>
      <ul v-if="keptSessions.length" class="flex list-disc flex-col gap-1 pl-5">
        <li v-for="s in keptSessions" :key="s.sessionId">
          {{ keptText(s) }}
          <template v-if="sessionsLink(s.sectionCode)">
            —
            <RouterLink :to="sessionsLink(s.sectionCode)" class="font-semibold underline"
              >ดูคาบเรียน</RouterLink
            >
          </template>
        </li>
      </ul>
      <ul v-if="notChangedSessions.length" class="flex list-disc flex-col gap-1 pl-5">
        <li v-for="s in notChangedSessions" :key="s.sessionId">
          {{ notChangedText(s) }} —
          <RouterLink
            :to="{ name: 'session-report', params: { sessionId: s.sessionId } }"
            class="font-semibold underline"
            >ดูคาบนี้</RouterLink
          >
        </li>
      </ul>
      <p v-if="notChangedSessions.length">
        ปรับเวลาคาบที่กำลังเรียนได้ที่หน้าห้อง เมนู "จัดการคาบ" · คาบที่จบแล้วแก้ได้ที่รายงานคาบ
        ปุ่ม "แก้เวลาคาบ" (ภายใน 7 วัน)
      </p>
      <div class="flex flex-wrap gap-2">
        <RouterLink
          :to="{ name: 'room-selection' }"
          class="inline-flex min-h-11 items-center rounded-button border border-line bg-surface px-4 font-semibold text-ink hover:bg-page"
          >ไปหน้าห้องเรียน</RouterLink
        >
        <RouterLink
          :to="{ name: 'courses-manage' }"
          class="inline-flex min-h-11 items-center rounded-button border border-line bg-surface px-4 font-semibold text-ink hover:bg-page"
          >กลับหน้ารายวิชา</RouterLink
        >
      </div>
    </div>

    <ErrorState v-if="loadError" :message="loadError.message" @retry="load" />
    <LoadingSkeleton v-else-if="loading" :lines="6" />

    <form v-else class="flex flex-col gap-6" novalidate @submit.prevent="onSubmit">
      <BaseCard class="grid gap-4 sm:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
        <TextField
          v-model="form.courseCode"
          label="รหัสวิชา"
          placeholder="INT101"
          required
          :maxlength="6"
          :error="errors.courseCode"
        />
        <TextField
          v-model="form.courseName"
          label="ชื่อรายวิชา"
          required
          :maxlength="200"
          :error="errors.courseName"
        />
      </BaseCard>

      <BaseCard
        v-for="(section, i) in form.sections"
        :key="section.sectionId ?? `new-${i}`"
        class="flex flex-col gap-5"
      >
        <div class="flex items-center justify-between gap-3">
          <h2 class="text-base font-bold">
            กลุ่มเรียน {{ section.sectionCode || i + 1 }}
            <span v-if="!section.sectionId" class="ml-1 text-xs font-medium text-primary"
              >ใหม่</span
            >
          </h2>
          <RouterLink
            v-if="section.sectionId && courseId"
            :to="{
              name: 'section-sessions',
              params: { courseId, sectionId: section.sectionId },
            }"
            class="text-xs font-semibold text-primary hover:underline"
          >
            คาบเรียนของกลุ่มนี้ →
          </RouterLink>
          <button
            v-if="!section.sectionId && form.sections.length > 1"
            type="button"
            class="text-xs font-semibold text-danger hover:underline"
            @click="removeSection(i)"
          >
            ลบกลุ่มนี้
          </button>
        </div>

        <div class="grid gap-4 sm:grid-cols-3">
          <TextField
            v-model="section.sectionCode"
            label="รหัสกลุ่ม"
            placeholder="001"
            required
            :maxlength="10"
            :error="errors[`s${i}.sectionCode`]"
          />
          <div>
            <BaseSelect
              v-model="section.roomId"
              label="ห้องเรียน"
              :options="roomOptions"
              placeholder="เลือกห้อง"
            />
            <p v-if="errors[`s${i}.roomId`]" class="mt-1 text-xs text-danger">
              {{ errors[`s${i}.roomId`] }}
            </p>
          </div>
          <div v-if="isAdmin">
            <BaseSelect
              v-model="section.lecturerId"
              label="อาจารย์ผู้สอน"
              :options="lecturerOptions"
              :placeholder="
                section.sectionId
                  ? `ไม่เปลี่ยน (${section.currentLecturerName ?? '-'})`
                  : 'เลือกผู้สอน'
              "
            />
            <p v-if="errors[`s${i}.lecturerId`]" class="mt-1 text-xs text-danger">
              {{ errors[`s${i}.lecturerId`] }}
            </p>
          </div>
          <p v-else class="self-end text-xs text-muted">
            ผู้สอน: {{ section.currentLecturerName ?? 'คุณ' }}
          </p>
        </div>

        <ClassDaysPicker v-model="section.classDays" :error="errors[`s${i}.classDays`]" />

        <div class="grid gap-4 md:grid-cols-2">
          <TimeRangeField
            v-model:start="section.startTime"
            v-model:end="section.endTime"
            label="เวลาเรียน"
            :error="errors[`s${i}.time`]"
          />
          <CheckInWindowPicker
            v-model:opens="section.checkInOpens"
            v-model:closes="section.checkInCloses"
            v-model:on-time-minutes="section.onTimeMinutes"
            :error="errors[`s${i}.checkIn`]"
          />
        </div>

        <CheckInTimelinePreview
          :start-time="section.startTime"
          :end-time="section.endTime"
          :opens="section.checkInOpens"
          :closes="section.checkInCloses"
          :on-time-minutes="section.onTimeMinutes"
        />
        <ScheduleSummaryCard :section="section" />
        <BaseToggle
          v-model="section.cameraAttendanceEnabled"
          label="เช็คชื่อด้วยกล้องในห้องเรียน"
        />
      </BaseCard>

      <p v-if="errors.sections" class="text-sm text-danger">{{ errors.sections }}</p>
      <div>
        <BaseButton variant="secondary" @click="addSection">+ เพิ่มกลุ่มเรียน</BaseButton>
      </div>

      <p
        v-if="serverError"
        role="alert"
        class="rounded-field bg-danger-bg px-4 py-3 text-sm text-danger"
      >
        {{ serverError }}
      </p>

      <div class="flex justify-end gap-2 border-t border-line pt-4">
        <RouterLink
          :to="{ name: 'courses-manage' }"
          class="inline-flex min-h-11 items-center rounded-button border border-line bg-surface px-4 text-sm font-semibold hover:bg-page"
        >
          ยกเลิก
        </RouterLink>
        <BaseButton type="submit" :loading="saving">{{
          isEdit ? 'บันทึกการแก้ไข' : 'เพิ่มรายวิชา'
        }}</BaseButton>
      </div>
    </form>
  </section>
</template>
