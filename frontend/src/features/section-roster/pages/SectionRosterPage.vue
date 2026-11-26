<script setup>
/**
 * รายชื่อนักศึกษาในกลุ่มเรียน (E2) — admin / อาจารย์ผู้สอน
 *  - GET /sections/{id}/enrollments (ทุกสถานะ · กรองฝั่งหน้า) · ค้นหารหัส / ชื่อ / อีเมล
 *  - เพิ่มเอง (หลายคน + ตรวจสอบก่อนบันทึก) · นำเข้าไฟล์ (template → ไฟล์ → ตรวจสอบ → ยืนยัน)
 *  - ถอน (soft · ผลเช็คชื่อเก็บไว้) · คนที่ถอนแล้ว → "เพิ่มกลับ"
 *  - ป้ายใบหน้า / ความยินยอม / บัญชีถูกปิด · จอใหญ่ = ตาราง · มือถือ = card
 */
import { computed, ref } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseCard from '@/shared/components/BaseCard.vue'
import DataTable from '@/shared/components/DataTable.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import RefreshButton from '@/shared/components/RefreshButton.vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import TextField from '@/shared/components/TextField.vue'
import { useAsyncState } from '@/shared/composables/useAsyncState'
import { useToast } from '@/shared/composables/useToast'
import { formatThaiDateTime } from '@/shared/utils/datetime'
import { CONSENT_LABEL, describeAddResult, listEnrollments } from '../api'
import AddStudentsDialog from '../components/AddStudentsDialog.vue'
import DropStudentDialog from '../components/DropStudentDialog.vue'
import ImportStudentsWizard from '../components/ImportStudentsWizard.vue'

const props = defineProps({
  courseId: { type: String, required: true },
  sectionId: { type: String, required: true },
})
const toast = useToast()

const updatedAt = ref(/** @type {Date | null} */ (null))
const list = useAsyncState(async () => {
  const r = await listEnrollments(props.sectionId)
  updatedAt.value = new Date()
  return r
})
const data = computed(() => list.data.value)
const label = computed(() =>
  data.value ? `${data.value.courseCode}-${data.value.sectionCode}` : '',
)

// ---------- ตัวกรอง + ค้นหา ----------
const FILTERS = [
  { key: 'active', label: 'ลงทะเบียนอยู่', test: (s) => s.status === 'active' },
  { key: 'dropped', label: 'ถอนแล้ว', test: (s) => s.status === 'dropped' },
  { key: 'all', label: 'ทั้งหมด', test: () => true },
]
const filter = ref('active')
const query = ref('')
const items = computed(() => data.value?.items ?? [])
const counts = computed(() =>
  Object.fromEntries(FILTERS.map((f) => [f.key, items.value.filter(f.test).length])),
)
const shown = computed(() => {
  const f = FILTERS.find((x) => x.key === filter.value)
  const q = query.value.trim().toLowerCase()
  return items.value
    .filter(f.test)
    .filter(
      (s) =>
        !q ||
        s.studentCode.includes(q) ||
        s.fullName.toLowerCase().includes(q) ||
        s.email.toLowerCase().includes(q),
    )
})

const columns = [
  { key: 'studentCode', label: 'รหัสนักศึกษา', sortable: true },
  { key: 'fullName', label: 'ชื่อ-นามสกุล', sortable: true },
  { key: 'face', label: 'ใบหน้า' },
  { key: 'consent', label: 'ความยินยอม' },
  { key: 'status', label: 'สถานะ' },
  { key: 'actions', label: '' },
]
const consentMeta = (s) =>
  CONSENT_LABEL[s.consentStatus] ?? { label: 'ยังไม่ยินยอม', tone: 'neutral' }
const statusText = (s) =>
  s.status === 'active'
    ? `เพิ่มเมื่อ ${formatThaiDateTime(s.enrolledAt)}`
    : `ถอนเมื่อ ${formatThaiDateTime(s.droppedAt)}`

// ---------- dialogs ----------
const addOpen = ref(false)
const importOpen = ref(false)
const prefill = ref(/** @type {string[]} */ ([]))
function openAdd(codes = []) {
  prefill.value = codes
  addOpen.value = true
}
const dropOpen = ref(false)
const dropping = ref(/** @type {any} */ (null))
function openDrop(s) {
  dropping.value = s
  dropOpen.value = true
}

async function onAdded(result) {
  toast.show(describeAddResult(result), { tone: 'success' })
  filter.value = 'active'
  await list.run()
}
async function onDropped(r) {
  toast.show(
    `ถอน ${r.studentCode} ${r.studentName} แล้ว${r.keptAttendanceCount ? ` · เก็บผลเช็คชื่อเดิม ${r.keptAttendanceCount} คาบ` : ''}`,
    { tone: 'success' },
  )
  await list.run()
}
</script>

<template>
  <section class="mx-auto flex max-w-6xl flex-col gap-6">
    <div>
      <RouterLink :to="{ name: 'courses-manage' }" class="text-sm text-muted hover:text-ink"
        >← รายวิชา</RouterLink
      >
      <div class="mt-2 flex flex-wrap items-start justify-between gap-3">
        <div class="min-w-0">
          <h1 class="text-[28px] font-bold">รายชื่อนักศึกษา {{ label }}</h1>
          <p v-if="data" class="text-sm text-muted">
            ลงทะเบียนอยู่ {{ data.activeCount }} คน ·
            <RouterLink
              :to="{ name: 'section-sessions', params: { courseId, sectionId } }"
              class="font-semibold text-primary hover:underline"
              >คาบเรียนของกลุ่มนี้</RouterLink
            >
          </p>
          <p class="mt-1 text-xs text-muted">
            คนที่เพิ่มใหม่ถูกลงขาดอัตโนมัติเฉพาะคาบที่เริ่มหลังเพิ่ม ·
            ถอนแล้วผลเช็คชื่อเดิมยังเก็บไว้
          </p>
        </div>
        <div class="flex flex-wrap gap-2">
          <BaseButton variant="secondary" :disabled="!data" @click="importOpen = true">
            นำเข้าไฟล์
          </BaseButton>
          <BaseButton :disabled="!data" @click="openAdd()">+ เพิ่มนักศึกษา</BaseButton>
        </div>
      </div>
    </div>

    <ErrorState
      v-if="list.error.value && !data"
      :message="list.error.value.message"
      @retry="list.run"
    />
    <LoadingSkeleton v-else-if="!data" :lines="6" />

    <template v-else>
      <div class="flex flex-wrap items-end justify-between gap-3">
        <div role="tablist" class="-mx-1 flex gap-2 overflow-x-auto px-1 py-1">
          <button
            v-for="f in FILTERS"
            :key="f.key"
            type="button"
            role="tab"
            :aria-selected="filter === f.key ? 'true' : 'false'"
            class="inline-flex shrink-0 items-center gap-1 rounded-button border px-3 py-1.5 text-[13px] font-medium"
            :class="
              filter === f.key
                ? 'border-primary bg-primary text-on-dark'
                : 'border-line bg-surface text-ink hover:border-primary/40'
            "
            @click="filter = f.key"
          >
            {{ f.label }} <span class="opacity-80">{{ counts[f.key] }}</span>
          </button>
        </div>
        <div class="flex w-full flex-wrap items-end gap-3 sm:w-auto">
          <TextField
            v-model="query"
            type="search"
            label="ค้นหา"
            placeholder="รหัส / ชื่อ / อีเมล"
            class="min-w-0 flex-1 sm:w-64"
          />
          <RefreshButton
            :loading="list.loading.value"
            :updated-at="updatedAt"
            @refresh="list.run"
          />
        </div>
      </div>

      <!-- จอใหญ่: ตาราง -->
      <div class="hidden md:block">
        <DataTable
          :columns="columns"
          :rows="shown"
          row-key="studentId"
          caption="รายชื่อนักศึกษาในกลุ่มเรียน"
          :empty-text="query ? 'ไม่พบนักศึกษาที่ค้นหา' : 'ไม่มีนักศึกษาในตัวกรองนี้'"
        >
          <template #cell-fullName="{ row }">
            <p class="font-medium">{{ row.fullName }}</p>
            <p class="text-xs break-all text-muted">{{ row.email }}</p>
            <StatusBadge v-if="!row.accountActive" tone="danger" label="บัญชีถูกปิด" class="mt-1" />
          </template>
          <template #cell-face="{ row }">
            <StatusBadge
              :tone="row.faceRegistered ? 'success' : 'warning'"
              :label="row.faceRegistered ? 'ลงทะเบียนแล้ว' : 'ยังไม่ลงทะเบียน'"
            />
          </template>
          <template #cell-consent="{ row }">
            <StatusBadge :tone="consentMeta(row).tone" :label="consentMeta(row).label" />
          </template>
          <template #cell-status="{ row }">
            <StatusBadge
              :tone="row.status === 'active' ? 'info' : 'neutral'"
              :label="row.status === 'active' ? 'ลงทะเบียนอยู่' : 'ถอนแล้ว'"
            />
            <p class="mt-1 text-xs whitespace-nowrap text-muted">{{ statusText(row) }}</p>
          </template>
          <template #cell-actions="{ row }">
            <button
              v-if="row.status === 'active'"
              type="button"
              class="rounded-button px-2 py-1 text-xs font-semibold text-danger hover:bg-danger/5"
              @click="openDrop(row)"
            >
              ถอน
            </button>
            <button
              v-else
              type="button"
              class="rounded-button px-2 py-1 text-xs font-semibold whitespace-nowrap text-primary hover:bg-primary/5"
              @click="openAdd([row.studentCode])"
            >
              เพิ่มกลับ
            </button>
          </template>
        </DataTable>
      </div>

      <!-- มือถือ: card -->
      <ul class="flex flex-col gap-3 md:hidden">
        <li v-if="!shown.length" class="py-8 text-center text-sm text-muted">
          {{ query ? 'ไม่พบนักศึกษาที่ค้นหา' : 'ไม่มีนักศึกษาในตัวกรองนี้' }}
        </li>
        <li v-for="s in shown" :key="s.studentId">
          <BaseCard class="flex flex-col gap-2">
            <div class="min-w-0">
              <p class="font-semibold">{{ s.fullName }}</p>
              <p class="text-sm text-muted">{{ s.studentCode }}</p>
              <p class="text-xs break-all text-muted">{{ s.email }}</p>
            </div>
            <div class="flex flex-wrap gap-1">
              <StatusBadge
                :tone="s.faceRegistered ? 'success' : 'warning'"
                :label="s.faceRegistered ? 'ใบหน้า: ลงทะเบียนแล้ว' : 'ใบหน้า: ยังไม่ลงทะเบียน'"
              />
              <StatusBadge :tone="consentMeta(s).tone" :label="consentMeta(s).label" />
              <StatusBadge v-if="!s.accountActive" tone="danger" label="บัญชีถูกปิด" />
              <StatusBadge v-if="s.status === 'dropped'" tone="neutral" label="ถอนแล้ว" />
            </div>
            <div class="flex items-center justify-between gap-2 border-t border-line pt-2">
              <span class="text-xs text-muted">{{ statusText(s) }}</span>
              <button
                v-if="s.status === 'active'"
                type="button"
                class="min-h-9 rounded-button px-2.5 text-xs font-semibold text-danger hover:bg-danger/5"
                @click="openDrop(s)"
              >
                ถอน
              </button>
              <button
                v-else
                type="button"
                class="min-h-9 rounded-button px-2.5 text-xs font-semibold text-primary hover:bg-primary/5"
                @click="openAdd([s.studentCode])"
              >
                เพิ่มกลับ
              </button>
            </div>
          </BaseCard>
        </li>
      </ul>
    </template>

    <AddStudentsDialog
      v-model:open="addOpen"
      :section-id="sectionId"
      :label="label"
      :can-create-accounts="data?.canCreateAccounts ?? false"
      :prefill-codes="prefill"
      @added="onAdded"
    />
    <ImportStudentsWizard
      v-model:open="importOpen"
      :section-id="sectionId"
      :label="label"
      :can-create-accounts="data?.canCreateAccounts ?? false"
      @imported="onAdded"
    />
    <DropStudentDialog
      v-model:open="dropOpen"
      :section-id="sectionId"
      :student="dropping"
      @dropped="onDropped"
    />
  </section>
</template>
