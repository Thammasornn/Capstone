<script setup>
/** จัดการข้อมูลใบหน้า: สถานะ (5.11) + ลงทะเบียนใหม่ + ถอนความยินยอม (B7) */
import { ref } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseCard from '@/shared/components/BaseCard.vue'
import BaseDialog from '@/shared/components/BaseDialog.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import { useAsyncState } from '@/shared/composables/useAsyncState'
import { useToast } from '@/shared/composables/useToast'
import { formatThaiDateTime } from '@/shared/utils/datetime'
import { useAuthStore } from '@/features/auth/store'
import { getFaceStatus, withdrawConsent } from '../api'
import RegistrationStatusBanner from '../components/RegistrationStatusBanner.vue'

const auth = useAuthStore()
const toast = useToast()
const status = useAsyncState(() => getFaceStatus(auth.studentId))

const confirmOpen = ref(false)
const withdrawing = ref(false)
const withdrawError = ref('')

async function withdraw() {
  withdrawing.value = true
  withdrawError.value = ''
  try {
    const result = await withdrawConsent(auth.studentId)
    confirmOpen.value = false
    toast.show(`ถอนความยินยอมแล้ว ลบข้อมูลใบหน้า ${result.deletedCount ?? 0} รายการ`, {
      tone: 'success',
    })
    await Promise.all([status.run(), auth.refreshMe().catch(() => {})])
  } catch (e) {
    withdrawError.value = e?.message ?? 'ถอนความยินยอมไม่สำเร็จ'
  } finally {
    withdrawing.value = false
  }
}
</script>

<template>
  <section class="mx-auto flex max-w-3xl flex-col gap-6">
    <div>
      <RouterLink :to="{ name: 'face-registration' }" class="text-sm text-muted hover:text-ink"
        >← ลงทะเบียนใบหน้า</RouterLink
      >
      <h1 class="mt-2 text-[28px] font-bold">จัดการข้อมูลใบหน้า</h1>
    </div>

    <ErrorState
      v-if="status.error.value && !status.data.value"
      :message="status.error.value.message"
      @retry="status.run"
    />
    <LoadingSkeleton v-else-if="!status.data.value" :lines="4" />
    <template v-else>
      <RegistrationStatusBanner
        :registered="status.data.value.registered"
        :registered-at="status.data.value.registeredAt"
        :active-faces="status.data.value.activeFaces"
      />

      <BaseCard>
        <dl class="grid gap-3 text-sm sm:grid-cols-2">
          <div>
            <dt class="text-muted">ความยินยอม</dt>
            <dd class="font-semibold">
              {{
                { granted: 'ให้ความยินยอมแล้ว', withdrawn: 'ถอนความยินยอมแล้ว' }[
                  status.data.value.consentStatus
                ] ?? 'ยังไม่เคยให้ความยินยอม'
              }}
            </dd>
          </div>
          <div>
            <dt class="text-muted">ฉบับข้อความ / เมื่อ</dt>
            <dd class="font-semibold">
              {{ status.data.value.consentVersion ?? '-' }} ·
              {{ formatThaiDateTime(status.data.value.consentedAt) }}
            </dd>
          </div>
        </dl>
        <p
          v-if="status.data.value.needsReconsent"
          class="mt-4 rounded-field bg-warning-bg px-4 py-3 text-sm text-warning"
        >
          ข้อความขอความยินยอมมีฉบับใหม่ กรุณาลงทะเบียนใหม่เพื่อยินยอมตามฉบับปัจจุบัน
        </p>
      </BaseCard>

      <BaseCard class="flex flex-col gap-3">
        <h2 class="font-bold">ถอนความยินยอม</h2>
        <p class="text-sm text-muted">
          ระบบจะลบค่าเวกเตอร์ใบหน้าทันที และหยุดเช็คชื่อคุณด้วยกล้อง
          การเช็คชื่อที่ผ่านมายังมีผลตามเดิม หลังถอนแล้วอาจารย์จะเช็คชื่อให้ด้วยวิธีอื่น
          และคุณลงทะเบียนใหม่ได้ทุกเมื่อ
        </p>
        <div>
          <BaseButton
            variant="danger"
            :disabled="status.data.value.consentStatus !== 'granted'"
            @click="confirmOpen = true"
          >
            ถอนความยินยอมและลบข้อมูลใบหน้า
          </BaseButton>
        </div>
      </BaseCard>
    </template>

    <BaseDialog
      v-model:open="confirmOpen"
      title="ยืนยันถอนความยินยอม"
      description="ข้อมูลใบหน้าจะถูกลบทันทีและกู้คืนไม่ได้"
    >
      <p v-if="withdrawError" role="alert" class="text-sm text-danger">{{ withdrawError }}</p>
      <template #actions>
        <BaseButton variant="secondary" @click="confirmOpen = false">ยกเลิก</BaseButton>
        <BaseButton variant="danger" :loading="withdrawing" @click="withdraw"
          >ถอนความยินยอม</BaseButton
        >
      </template>
    </BaseDialog>
  </section>
</template>
