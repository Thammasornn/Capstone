<script setup>
/**
 * นำเข้ารายชื่อนักศึกษาจากไฟล์ (pattern เดียวกับนำเข้าผลเช็คชื่อ C3)
 *  1 ดาวน์โหลด template (xlsx / csv) → กรอก → เลือกไฟล์ (.csv / .txt / .xlsx ≤ 2 MB ≤ 500 แถว)
 *  2 ตรวจสอบ: dryRun = true → ผลรายแถว · 400 + errors[] = ตารางข้อผิดพลาด (ไม่บันทึกอะไร)
 *  3 ยืนยัน: dryRun = false → emit imported(AddResponse) แล้วปิด
 */
import { computed, ref, watch } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseDialog from '@/shared/components/BaseDialog.vue'
import TextField from '@/shared/components/TextField.vue'
import { normalizeError } from '@/shared/api/errors'
import {
  ROSTER_MAX_BYTES,
  ROSTER_MAX_ITEMS,
  ROSTER_REASON_MAX,
  downloadRosterTemplate,
  importStudentsFile,
} from '../api'
import AddResultPreview from './AddResultPreview.vue'
import RowErrorsTable from './RowErrorsTable.vue'

const props = defineProps({
  sectionId: { type: String, required: true },
  label: { type: String, default: '' },
  canCreateAccounts: { type: Boolean, default: false },
})
const open = defineModel('open', { type: Boolean, default: false })
const emit = defineEmits(['imported'])

const step = ref(/** @type {'source' | 'preview'} */ ('source'))
const file = ref(/** @type {File | null} */ (null))
const fileInput = ref(/** @type {HTMLInputElement | null} */ (null))
const reason = ref('')
const preview = ref(/** @type {import('../api').AddResponse | null} */ (null))
const rowErrors = ref(/** @type {any[] | null} */ (null))
const error = ref('')
const busy = ref(false)
const downloading = ref(/** @type {string | null} */ (null))

watch(open, (v) => {
  if (!v) return
  step.value = 'source'
  file.value = null
  reason.value = ''
  preview.value = null
  rowErrors.value = null
  error.value = ''
})

function onFile(e) {
  const input = /** @type {HTMLInputElement} */ (e.target)
  const f = input.files?.[0] ?? null
  error.value = ''
  rowErrors.value = null
  preview.value = null
  if (f && f.size > ROSTER_MAX_BYTES) {
    error.value = 'ไฟล์ใหญ่เกิน 2 MB'
    file.value = null
    input.value = ''
    return
  }
  if (f && !/\.(csv|txt|xlsx)$/i.test(f.name)) {
    error.value = 'รองรับเฉพาะไฟล์ .csv, .txt หรือ .xlsx'
    file.value = null
    input.value = ''
    return
  }
  file.value = f
}

async function download(format) {
  downloading.value = format
  error.value = ''
  try {
    await downloadRosterTemplate(props.sectionId, format)
  } catch (e) {
    error.value = normalizeError(e).message
  } finally {
    downloading.value = null
  }
}

function showError(e) {
  const err = normalizeError(e)
  error.value = err.message
  rowErrors.value = err.errors?.length ? err.errors : null
}

function sourceProblem() {
  if (!file.value) return 'เลือกไฟล์'
  if (reason.value.trim().length > ROSTER_REASON_MAX)
    return `เหตุผลยาวได้ไม่เกิน ${ROSTER_REASON_MAX} ตัวอักษร`
  return ''
}

async function run(dryRun) {
  busy.value = true
  error.value = ''
  rowErrors.value = null
  try {
    const result = await importStudentsFile(props.sectionId, {
      file: /** @type {File} */ (file.value),
      dryRun,
      reason: reason.value.trim() || undefined,
    })
    if (dryRun) {
      preview.value = result
      step.value = 'preview'
    } else {
      open.value = false
      emit('imported', result)
    }
  } catch (e) {
    showError(e)
  } finally {
    busy.value = false
  }
}

function check() {
  const p = sourceProblem()
  if (p) {
    error.value = p
    return
  }
  run(true)
}

function backToSource(clearFile) {
  step.value = 'source'
  preview.value = null
  error.value = ''
  rowErrors.value = null
  if (clearFile) {
    file.value = null
    if (fileInput.value) fileInput.value.value = ''
  }
}

const writeCount = computed(() =>
  preview.value
    ? preview.value.summary.insert + preview.value.summary.reactivate + preview.value.summary.create
    : 0,
)
</script>

<template>
  <BaseDialog v-model:open="open" title="นำเข้ารายชื่อนักศึกษาจากไฟล์" :description="label" wide>
    <div v-if="step === 'source'" class="flex flex-col gap-4">
      <section class="flex flex-col gap-2 rounded-field border border-line p-3">
        <h3 class="text-sm font-bold">1. ดาวน์โหลดไฟล์ตัวอย่างแล้วกรอก</h3>
        <p class="text-xs text-muted">
          หัวตาราง: <span class="font-mono">student_code, title, first_name, last_name, email</span>
          · ต้องมีรหัสนักศึกษา (11 หลัก) หรืออีเมลอย่างน้อย 1 ช่อง
          <template v-if="canCreateAccounts">
            · คนที่ยังไม่มีบัญชี: กรอกชื่อ + นามสกุล ระบบจะสร้างบัญชีให้</template
          >
          <template v-else> · คนที่ยังไม่มีบัญชีในระบบจะเพิ่มไม่ได้</template>
        </p>
        <div class="flex flex-wrap gap-2">
          <BaseButton
            variant="secondary"
            :loading="downloading === 'xlsx'"
            :disabled="Boolean(downloading)"
            @click="download('xlsx')"
          >
            ดาวน์โหลด .xlsx
          </BaseButton>
          <BaseButton
            variant="secondary"
            :loading="downloading === 'csv'"
            :disabled="Boolean(downloading)"
            @click="download('csv')"
          >
            ดาวน์โหลด .csv
          </BaseButton>
        </div>
      </section>

      <section class="flex flex-col gap-2 rounded-field border border-line p-3">
        <h3 class="text-sm font-bold">2. เลือกไฟล์ที่กรอกแล้ว</h3>
        <input
          ref="fileInput"
          type="file"
          accept=".csv,.txt,.xlsx"
          class="block w-full min-w-0 text-sm file:mr-3 file:min-h-11 file:rounded-button file:border file:border-line file:bg-surface file:px-4 file:text-sm file:font-semibold"
          :disabled="busy"
          @change="onFile"
        />
        <p class="text-xs text-muted">
          .csv / .txt (UTF-8 คั่นด้วย ,) หรือ .xlsx · ไม่เกิน 2 MB และ {{ ROSTER_MAX_ITEMS }} แถว ·
          คนที่เพิ่มใหม่ถูกลงขาดอัตโนมัติเฉพาะคาบที่เริ่มหลังนำเข้า
        </p>
      </section>

      <TextField
        v-model="reason"
        label="เหตุผล (ไม่บังคับ)"
        multiline
        :maxlength="ROSTER_REASON_MAX"
        placeholder="เช่น รายชื่อจากสำนักทะเบียน"
        :disabled="busy"
      />
    </div>

    <div v-else-if="preview" class="flex flex-col gap-3">
      <p class="text-sm text-muted">ผลตรวจไฟล์ {{ file?.name }} — ยังไม่มีอะไรถูกบันทึก</p>
      <AddResultPreview :result="preview" />
    </div>

    <div v-if="error" class="mt-4 flex flex-col gap-2">
      <p role="alert" class="rounded-field bg-danger-bg px-3 py-2 text-sm text-danger">
        {{ error }}
      </p>
      <template v-if="rowErrors">
        <RowErrorsTable :errors="rowErrors" />
        <p class="text-xs text-muted">แก้ไฟล์แล้วเลือกไฟล์ใหม่ — ยังไม่มีอะไรถูกบันทึก</p>
      </template>
    </div>

    <template #actions>
      <template v-if="step === 'source'">
        <BaseButton variant="secondary" :disabled="busy" @click="open = false">ยกเลิก</BaseButton>
        <BaseButton :loading="busy" :disabled="!file" @click="check">ตรวจสอบไฟล์</BaseButton>
      </template>
      <template v-else>
        <BaseButton variant="secondary" :disabled="busy" @click="backToSource(true)">
          เลือกไฟล์ใหม่
        </BaseButton>
        <BaseButton :loading="busy" :disabled="!writeCount" @click="run(false)">
          ยืนยันนำเข้า {{ writeCount }} คน
        </BaseButton>
      </template>
    </template>
  </BaseDialog>
</template>
