<script setup>
/**
 * นำเข้าผลเช็คชื่อจากไฟล์ (คลาสออนไซต์) — ใช้ทั้ง Room Live และ Session Report
 *  1 เลือกแบบ: template ของระบบ (ดาวน์โหลด → กรอก → อัปโหลด) หรือไฟล์อื่น (Teams / Zoom / Google Form ฯลฯ → inspect)
 *  2 เลือกคอลัมน์ (เฉพาะไฟล์อื่น · ไม่เติมค่าให้)
 *  3 Preview: dryRun = true · error รายแถว (400 + errors[]) แสดงเป็นตาราง · checkbox แทนที่ผลเดิม → dryRun ใหม่
 *  4 ยืนยัน: dryRun = false → emit imported(ImportResult) แล้วปิด
 * ผู้ใช้เลือกทุกอย่างเอง ระบบไม่เดาคอลัมน์ / รูปแบบเวลา / ความหมายของสถานะ
 */
import { computed, reactive, ref, watch } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseDialog from '@/shared/components/BaseDialog.vue'
import BaseSelect from '@/shared/components/BaseSelect.vue'
import TextField from '@/shared/components/TextField.vue'
import { normalizeError } from '@/shared/api/errors'
import {
  IMPORT_MAX_BYTES,
  IMPORT_MAX_ROWS,
  IMPORT_METHOD_LABEL,
  IMPORT_REASON_MAX,
  MAPPED_METHODS,
  downloadImportTemplate,
  importAttendanceFile,
  inspectImportFile,
} from '../import'
import ImportMappingStep from './ImportMappingStep.vue'
import ImportPreviewStep from './ImportPreviewStep.vue'

const props = defineProps({
  sessionId: { type: String, required: true },
  /** เช่น "INT201-001 · 25 พ.ย. 2569" */
  label: { type: String, default: '' },
})
const emit = defineEmits(['imported'])
const open = defineModel('open', { type: Boolean, default: false })

/** @type {import('vue').Ref<'source'|'mapping'|'preview'>} */
const step = ref('source')
const kind = ref(/** @type {'template'|'mapped'|null} */ (null))
const method = ref(/** @type {string | null} */ (null))
const file = ref(/** @type {File | null} */ (null))
const fileInput = ref(/** @type {HTMLInputElement | null} */ (null))
const reason = ref('')
const inspect = ref(/** @type {import('../import').InspectResult | null} */ (null))
const mapping = reactive(emptyMapping())
const mappingValid = ref(false)
const overrideCamera = ref(false)
const overrideManual = ref(false)
const preview = ref(/** @type {import('../import').ImportResult | null} */ (null))
const rowErrors = ref(/** @type {any[] | null} */ (null))
const error = ref('')
const busy = ref(false)
const downloading = ref(/** @type {string | null} */ (null))

function emptyMapping() {
  return {
    identifierColumn: null,
    identifierType: null,
    joinTimeColumn: null,
    timeFormat: null,
    statusColumn: null,
    statusValues: {},
    defaultStatus: null,
  }
}

function reset() {
  step.value = 'source'
  kind.value = null
  method.value = null
  file.value = null
  reason.value = ''
  inspect.value = null
  Object.assign(mapping, emptyMapping())
  overrideCamera.value = false
  overrideManual.value = false
  preview.value = null
  rowErrors.value = null
  error.value = ''
}
watch(open, (value) => {
  if (value) reset()
})

const methodOptions = MAPPED_METHODS.map((m) => ({ value: m, label: IMPORT_METHOD_LABEL[m] }))

function onFile(e) {
  const f = /** @type {HTMLInputElement} */ (e.target).files?.[0] ?? null
  error.value = ''
  if (f && f.size > IMPORT_MAX_BYTES) {
    error.value = 'ไฟล์ใหญ่เกิน 2 MB'
    file.value = null
    e.target.value = ''
    return
  }
  if (f && !/\.(csv|xlsx)$/i.test(f.name)) {
    error.value = 'รองรับเฉพาะไฟล์ .csv หรือ .xlsx'
    file.value = null
    e.target.value = ''
    return
  }
  file.value = f
  // ไฟล์ใหม่ = ข้อมูลจาก inspect / preview เดิมใช้ไม่ได้
  inspect.value = null
  preview.value = null
  rowErrors.value = null
}

async function download(format) {
  downloading.value = format
  error.value = ''
  try {
    await downloadImportTemplate(props.sessionId, format)
  } catch (e) {
    error.value = normalizeError(e).message
  } finally {
    downloading.value = null
  }
}

function sourceProblem() {
  if (!kind.value) return 'เลือกแบบไฟล์'
  if (kind.value === 'mapped' && !method.value) return 'เลือกที่มาของไฟล์'
  if (!file.value) return 'เลือกไฟล์'
  const r = reason.value.trim()
  if (!r) return 'กรุณาระบุเหตุผล'
  if (r.length > IMPORT_REASON_MAX) return `เหตุผลยาวได้ไม่เกิน ${IMPORT_REASON_MAX} ตัวอักษร`
  return ''
}

/** จับข้อผิดพลาด: 400 ที่มี errors[] → ตาราง · อื่น ๆ → ข้อความ */
function showError(e) {
  const err = normalizeError(e)
  error.value = err.message
  rowErrors.value = err.errors?.length ? err.errors : null
}

function buildMapping() {
  if (kind.value !== 'mapped') return null
  const m = {
    identifierColumn: mapping.identifierColumn,
    identifierType: mapping.identifierType,
  }
  if (mapping.joinTimeColumn) {
    m.joinTimeColumn = mapping.joinTimeColumn
    m.timeFormat = mapping.timeFormat
  }
  if (mapping.statusColumn) {
    m.statusColumn = mapping.statusColumn
    m.statusValues = { ...mapping.statusValues }
  }
  if (!mapping.joinTimeColumn && !mapping.statusColumn) m.defaultStatus = mapping.defaultStatus
  return m
}

function call(dryRun) {
  return importAttendanceFile(props.sessionId, {
    file: /** @type {File} */ (file.value),
    mode: kind.value === 'template' ? 'template' : 'mapped',
    method: kind.value === 'template' ? 'template' : /** @type {any} */ (method.value),
    reason: reason.value.trim(),
    dryRun,
    overrideCamera: overrideCamera.value,
    overrideManual: overrideManual.value,
    mapping: buildMapping(),
  })
}

async function runPreview() {
  busy.value = true
  error.value = ''
  rowErrors.value = null
  step.value = 'preview'
  try {
    preview.value = await call(true)
  } catch (e) {
    preview.value = null
    showError(e)
  } finally {
    busy.value = false
  }
}

async function next() {
  if (step.value === 'source') {
    error.value = sourceProblem()
    rowErrors.value = null
    if (error.value) return
    if (kind.value === 'template') return runPreview()
    busy.value = true
    try {
      inspect.value = await inspectImportFile(props.sessionId, /** @type {File} */ (file.value))
      if (inspect.value.rowCount > IMPORT_MAX_ROWS) {
        error.value = `ไฟล์มี ${inspect.value.rowCount} แถว เกิน ${IMPORT_MAX_ROWS} แถว`
        return
      }
      step.value = 'mapping'
    } catch (e) {
      showError(e)
    } finally {
      busy.value = false
    }
    return
  }
  if (step.value === 'mapping') {
    if (!mappingValid.value) return
    return runPreview()
  }
  // preview → ยืนยัน
  if (!preview.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await call(false)
    emit('imported', result)
    open.value = false
  } catch (e) {
    showError(e)
  } finally {
    busy.value = false
  }
}

// เปลี่ยน checkbox แทนที่ผลเดิม → ดูผลใหม่ (dryRun)
watch([overrideCamera, overrideManual], () => {
  if (step.value === 'preview' && preview.value) runPreview()
})

/** @param {boolean} clearFile true = ให้เลือกไฟล์ใหม่ (ปุ่ม "เลือกไฟล์ใหม่") */
function backToSource(clearFile) {
  step.value = 'source'
  preview.value = null
  rowErrors.value = null
  error.value = ''
  if (clearFile) {
    file.value = null
    inspect.value = null
    if (fileInput.value) fileInput.value.value = ''
  }
}
function backToMapping() {
  step.value = 'mapping'
  preview.value = null
  rowErrors.value = null
  error.value = ''
}

const TITLES = {
  source: 'นำเข้าผลเช็คชื่อ · 1 เลือกไฟล์',
  mapping: 'นำเข้าผลเช็คชื่อ · 2 เลือกคอลัมน์',
  preview: 'นำเข้าผลเช็คชื่อ · 3 ตรวจผลก่อนบันทึก',
}
const nextLabel = computed(() => {
  if (step.value === 'preview') return 'ยืนยันบันทึก'
  if (step.value === 'source' && kind.value === 'mapped') return 'ถัดไป: เลือกคอลัมน์'
  return 'ถัดไป: ดูผล'
})
const nextDisabled = computed(() => {
  if (step.value === 'mapping') return !mappingValid.value
  if (step.value === 'preview') return !preview.value
  return false
})
</script>

<template>
  <BaseDialog v-model:open="open" :title="TITLES[step]" :description="label" wide>
    <form id="attendance-import-form" class="flex flex-col gap-4" @submit.prevent="next">
      <!-- ขั้น 1 -->
      <template v-if="step === 'source'">
        <fieldset class="grid gap-2 sm:grid-cols-2">
          <legend class="mb-2 text-xs font-medium text-muted">แบบไฟล์ *</legend>
          <label
            v-for="opt in [
              {
                value: 'template',
                title: 'ใช้ template ของระบบ',
                hint: 'มีรายชื่อนักศึกษาให้แล้ว กรอกสถานะ / เวลา',
              },
              {
                value: 'mapped',
                title: 'ไฟล์อื่น',
                hint: 'Teams / Zoom / Google Form / ใบเซ็นชื่อ ฯลฯ เลือกคอลัมน์เอง',
              },
            ]"
            :key="opt.value"
            class="flex cursor-pointer items-start gap-2 rounded-field border px-3 py-3"
            :class="kind === opt.value ? 'border-primary bg-primary/5' : 'border-line'"
          >
            <input v-model="kind" type="radio" name="import-kind" :value="opt.value" class="mt-1" />
            <span>
              <span class="block text-sm font-semibold">{{ opt.title }}</span>
              <span class="block text-xs text-muted">{{ opt.hint }}</span>
            </span>
          </label>
        </fieldset>

        <div v-if="kind === 'template'" class="flex flex-col gap-2 rounded-field bg-page px-3 py-3">
          <div class="flex flex-wrap gap-2">
            <BaseButton
              variant="secondary"
              :loading="downloading === 'xlsx'"
              @click="download('xlsx')"
            >
              ดาวน์โหลด XLSX
            </BaseButton>
            <BaseButton
              variant="secondary"
              :loading="downloading === 'csv'"
              @click="download('csv')"
            >
              ดาวน์โหลด CSV
            </BaseButton>
          </div>
          <ul class="list-disc pl-5 text-xs text-muted">
            <li>ห้ามแก้หัวตารางและรหัสนักศึกษา</li>
            <li>status: on_time / late / absent · ว่าง = ไม่เปลี่ยนผลของคนนั้น</li>
            <li>check_in_time: HH:mm เช่น 09:05 (ว่างได้ · absent ห้ามมีเวลา)</li>
          </ul>
        </div>

        <BaseSelect
          v-if="kind === 'mapped'"
          v-model="method"
          label="ที่มาของไฟล์ *"
          :options="methodOptions"
          placeholder="— เลือก —"
        />

        <div v-if="kind" class="flex flex-col gap-1.5">
          <label for="attendance-import-file" class="text-xs font-medium text-muted">
            ไฟล์ (.csv / .xlsx) <span class="text-danger" aria-hidden="true">*</span>
          </label>
          <input
            id="attendance-import-file"
            ref="fileInput"
            type="file"
            accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            class="rounded-field border border-line bg-surface px-3 py-2 text-sm file:mr-3 file:rounded-button file:border-0 file:bg-page file:px-3 file:py-1.5 file:text-sm file:font-semibold"
            @change="onFile"
          />
          <p v-if="file" class="text-xs text-ink">ไฟล์ที่เลือก: {{ file.name }}</p>
          <p class="text-xs text-muted">
            CSV (UTF-8 คั่นด้วย comma) หรือ XLSX (ชีตแรก) · หัวตารางอยู่แถวแรก · ≤ 2 MB · ≤
            {{ IMPORT_MAX_ROWS }} แถว
            <template v-if="kind === 'mapped'">
              · รายงาน Teams: เปิดใน Excel แล้ว Save As เป็น XLSX และลบส่วน Summary ด้านบนออกก่อน ·
              ลบแถวที่ไม่ใช่นักศึกษา (เช่น อาจารย์) ออกด้วย
            </template>
          </p>
        </div>

        <TextField
          v-if="kind"
          v-model="reason"
          label="เหตุผล"
          multiline
          required
          :maxlength="IMPORT_REASON_MAX"
          :hint="`${reason.trim().length}/${IMPORT_REASON_MAX} ตัวอักษร`"
          placeholder="เช่น กล้องห้องเสีย ใช้ใบเซ็นชื่อแทน"
        />
      </template>

      <!-- ขั้น 2 -->
      <ImportMappingStep
        v-else-if="step === 'mapping' && inspect"
        v-model="mapping"
        v-model:valid="mappingValid"
        :inspect="inspect"
      />

      <!-- ขั้น 3 -->
      <template v-else-if="step === 'preview'">
        <p v-if="busy && !preview" class="text-sm text-muted">กำลังตรวจไฟล์…</p>
        <ImportPreviewStep
          v-if="preview"
          v-model:override-camera="overrideCamera"
          v-model:override-manual="overrideManual"
          :result="preview"
          :busy="busy"
        />
      </template>

      <div v-if="rowErrors" class="flex flex-col gap-2">
        <div class="max-h-64 overflow-auto rounded-field border border-danger/40">
          <table class="w-full text-sm">
            <thead class="sticky top-0 bg-danger-bg text-xs text-danger">
              <tr>
                <th class="px-3 py-2 text-left font-medium">แถว</th>
                <th class="px-3 py-2 text-left font-medium">คอลัมน์</th>
                <th class="px-3 py-2 text-left font-medium">ค่า</th>
                <th class="px-3 py-2 text-left font-medium">ข้อความ</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(er, i) in rowErrors" :key="i" class="border-t border-line align-top">
                <td class="px-3 py-1.5">{{ er.rowNo ?? 'ทั้งไฟล์' }}</td>
                <td class="px-3 py-1.5">{{ er.column ?? '-' }}</td>
                <td class="px-3 py-1.5 break-all">{{ er.value ?? '-' }}</td>
                <td class="px-3 py-1.5">{{ er.message }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <p v-if="error" role="alert" class="text-sm text-danger">{{ error }}</p>
    </form>

    <template #actions>
      <template v-if="step === 'preview'">
        <BaseButton variant="secondary" :disabled="busy" @click="backToSource(true)">
          เลือกไฟล์ใหม่
        </BaseButton>
        <BaseButton
          v-if="kind === 'mapped'"
          variant="secondary"
          :disabled="busy"
          @click="backToMapping"
        >
          แก้การเลือกคอลัมน์
        </BaseButton>
      </template>
      <BaseButton
        v-else-if="step === 'mapping'"
        variant="secondary"
        :disabled="busy"
        @click="backToSource(false)"
      >
        ย้อนกลับ
      </BaseButton>
      <BaseButton v-else variant="secondary" @click="open = false">ยกเลิก</BaseButton>
      <BaseButton
        type="submit"
        form="attendance-import-form"
        :loading="busy"
        :disabled="nextDisabled"
      >
        {{ nextLabel }}
      </BaseButton>
    </template>
  </BaseDialog>
</template>
