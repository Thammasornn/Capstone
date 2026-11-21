<script setup>
/**
 * เพิ่ม / แก้ไขรายวิชา (B5) — ตารางเรียน ห้อง ช่วงเช็คชื่อ และสวิตช์กล้องผูกที่กลุ่มเรียน
 * แก้ตารางเรียนแล้วมีผลกับคาบที่ยังไม่เริ่มเท่านั้น (D2)
 */
import { computed } from 'vue'
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
  router.push({ name: 'courses-manage' })
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
        แก้ตารางเรียนแล้วมีผลกับคาบที่ยังไม่เริ่มเท่านั้น ผลเช็คชื่อที่ผ่านมาไม่เปลี่ยน
      </p>
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
