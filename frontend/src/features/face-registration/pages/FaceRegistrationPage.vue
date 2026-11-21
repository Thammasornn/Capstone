<script setup>
/**
 * ลงทะเบียนใบหน้า (API 5.10): ยินยอม → ถ่าย/เลือกรูป 3–5 รูป → ส่ง → เสร็จ
 * ลงทะเบียนซ้ำ = แทนที่ใบหน้าเดิมทั้งหมด (เตือนก่อนเริ่ม)
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseCard from '@/shared/components/BaseCard.vue'
import { useAuthStore } from '@/features/auth/store'
import { MAX_PHOTOS, MIN_PHOTOS } from '../constants'
import { useFaceRegistration } from '../composables/useFaceRegistration'
import AngleCaptureStep from '../components/AngleCaptureStep.vue'
import ConsentStep from '../components/ConsentStep.vue'
import PhotoPicker from '../components/PhotoPicker.vue'
import PhotoPreviewGrid from '../components/PhotoPreviewGrid.vue'
import RegistrationStatusBanner from '../components/RegistrationStatusBanner.vue'
import RegistrationStepper from '../components/RegistrationStepper.vue'

const auth = useAuthStore()
const router = useRouter()
const face = computed(
  () =>
    auth.user?.student?.faceRegistration ?? {
      registered: false,
      registeredAt: null,
      activeFaces: 0,
    },
)
const started = ref(!face.value.registered)

const {
  step,
  ageConfirmed,
  photos,
  submitting,
  progress,
  error,
  result,
  canSubmit,
  addFiles,
  removePhoto,
  submit,
} = useFaceRegistration()
const stepIndex = computed(() => ({ consent: 0, photos: 1, done: 2 })[step.value])

function onAccept(payload) {
  ageConfirmed.value = payload.ageConfirmed
  step.value = 'photos'
}
</script>

<template>
  <section class="mx-auto flex max-w-3xl flex-col gap-6">
    <div>
      <h1 class="text-[28px] font-bold">ลงทะเบียนใบหน้า</h1>
      <p class="mt-1 text-sm text-muted">
        ใช้รูป {{ MIN_PHOTOS }}–{{ MAX_PHOTOS }} รูป ระบบเก็บเฉพาะค่าเวกเตอร์ใบหน้า ไม่เก็บไฟล์รูป
      </p>
    </div>

    <RegistrationStatusBanner
      v-if="!started"
      :registered="face.registered"
      :registered-at="face.registeredAt"
      :active-faces="face.activeFaces"
    >
      <div class="flex gap-2">
        <RouterLink
          :to="{ name: 'face-manage' }"
          class="text-sm font-semibold text-primary hover:underline"
          >จัดการข้อมูล</RouterLink
        >
      </div>
    </RegistrationStatusBanner>

    <BaseCard v-if="!started" class="flex flex-col items-start gap-3">
      <p class="text-sm">ต้องการลงทะเบียนใหม่? รูปเดิมทั้งหมดจะถูกแทนที่ด้วยรูปชุดใหม่</p>
      <BaseButton variant="secondary" @click="started = true">ลงทะเบียนใหม่</BaseButton>
    </BaseCard>

    <template v-else>
      <RegistrationStepper :current="stepIndex" />

      <BaseCard v-if="step === 'consent'">
        <ConsentStep @accept="onAccept" @decline="router.push({ name: 'student-home' })" />
      </BaseCard>

      <BaseCard v-else-if="step === 'photos'" class="flex flex-col gap-5">
        <div>
          <h2 class="text-lg font-bold">ถ่ายหรือเลือกรูปใบหน้า</h2>
          <ul class="mt-2 list-disc pl-5 text-sm text-muted">
            <li>หน้าตรง หันซ้าย หันขวา — 1 คนต่อรูป เห็นใบหน้าชัด ไม่เบลอ</li>
            <li>ถอดแว่นกันแดด / หน้ากาก แสงสว่างพอ</li>
          </ul>
        </div>
        <AngleCaptureStep
          v-if="photos.length < MAX_PHOTOS"
          :taken="photos.length"
          @captured="(file, label) => addFiles([file], label)"
        />
        <PhotoPicker v-if="photos.length < MAX_PHOTOS" @picked="addFiles" />
        <PhotoPreviewGrid :photos="photos" :disabled="submitting" @remove="removePhoto" />
        <p class="text-sm" :class="photos.length >= MIN_PHOTOS ? 'text-success' : 'text-muted'">
          เลือกแล้ว {{ photos.length }}/{{ MAX_PHOTOS }} รูป (ขั้นต่ำ {{ MIN_PHOTOS }})
        </p>
        <p
          v-if="error"
          role="alert"
          class="rounded-field bg-danger-bg px-4 py-3 text-sm text-danger"
        >
          {{ error }}
        </p>
        <div v-if="submitting" class="flex flex-col gap-1" role="status">
          <div class="h-2 overflow-hidden rounded-full bg-line">
            <div class="h-full bg-primary transition-all" :style="{ width: `${progress}%` }" />
          </div>
          <p class="text-xs text-muted">
            {{
              progress < 100
                ? `กำลังอัปโหลด ${progress}%`
                : 'กำลังประมวลผลใบหน้า อาจใช้เวลาถึง 30 วินาที…'
            }}
          </p>
        </div>
        <div class="flex justify-end">
          <BaseButton :disabled="!canSubmit" :loading="submitting" @click="submit"
            >ส่งลงทะเบียน</BaseButton
          >
        </div>
      </BaseCard>

      <BaseCard v-else class="flex flex-col items-center gap-3 py-10 text-center">
        <span
          class="flex size-14 items-center justify-center rounded-full bg-success-bg text-2xl text-success"
          aria-hidden="true"
          >✓</span
        >
        <h2 class="text-lg font-bold">ลงทะเบียนใบหน้าสำเร็จ</h2>
        <p class="text-sm text-muted">
          บันทึก {{ result?.savedCount }} รูป · กล้องในห้องเรียนจะเช็คชื่อให้อัตโนมัติ
        </p>
        <RouterLink
          :to="{ name: 'student-home' }"
          class="text-sm font-semibold text-primary hover:underline"
          >กลับหน้าแรก</RouterLink
        >
      </BaseCard>
    </template>
  </section>
</template>
