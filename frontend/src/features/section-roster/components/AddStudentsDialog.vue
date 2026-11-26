<script setup>
/**
 * เพิ่มนักศึกษาเอง (1 คนหรือหลายคน) → ตรวจสอบ (dryRun) → ยืนยัน (บันทึกจริง)
 * ช่องชื่อ-นามสกุลแสดงเมื่อ canCreateAccounts (ไม่มีบัญชี + มีชื่อ → ระบบสร้างบัญชีให้ · uat เปิด / prod ปิด)
 * "วางหลายคน": รหัส / อีเมล บรรทัดละคน → เติมเป็นแถว
 * กติกาเดียวกับ backend: ต้องมีรหัสหรืออีเมล · รหัส 11 หลัก · อีเมลขึ้นต้นด้วยรหัส 11 หลัก (ตรงกับรหัสถ้ากรอกทั้งคู่) · ชื่อ + นามสกุลคู่กัน
 */
import { computed, ref, watch } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseDialog from '@/shared/components/BaseDialog.vue'
import TextField from '@/shared/components/TextField.vue'
import { normalizeError } from '@/shared/api/errors'
import { ROSTER_MAX_ITEMS, ROSTER_REASON_MAX, addStudents } from '../api'
import AddResultPreview from './AddResultPreview.vue'
import RowErrorsTable from './RowErrorsTable.vue'

const props = defineProps({
  sectionId: { type: String, required: true },
  label: { type: String, default: '' },
  canCreateAccounts: { type: Boolean, default: false },
  /** รหัสนักศึกษาที่เติมไว้ตอนเปิด (เช่น ปุ่ม "เพิ่มกลับ" ของคนที่ถอนแล้ว) */
  prefillCodes: { type: Array, default: () => [] },
})
const open = defineModel('open', { type: Boolean, default: false })
const emit = defineEmits(['added'])

const emptyRow = () => ({ studentCode: '', email: '', title: '', firstName: '', lastName: '' })
const rows = ref([emptyRow()])
const reason = ref('')
const paste = ref('')
const pasteOpen = ref(false)
const step = ref(/** @type {'form' | 'preview'} */ ('form'))
const preview = ref(/** @type {import('../api').AddResponse | null} */ (null))
const fieldErrors = ref(/** @type {Record<number, string>} */ ({}))
const rowErrors = ref(/** @type {any[] | null} */ (null))
const error = ref('')
const busy = ref(false)

watch(open, (v) => {
  if (!v) return
  rows.value = props.prefillCodes.length
    ? props.prefillCodes.map((c) => ({ ...emptyRow(), studentCode: c }))
    : [emptyRow()]
  reason.value = ''
  paste.value = ''
  pasteOpen.value = false
  step.value = 'form'
  preview.value = null
  fieldErrors.value = {}
  rowErrors.value = null
  error.value = ''
})

function addRow() {
  rows.value.push(emptyRow())
}
function removeRow(i) {
  rows.value.splice(i, 1)
  if (!rows.value.length) rows.value.push(emptyRow())
}
function applyPaste() {
  const items = paste.value
    .split(/[\n,;\t]+/)
    .map((x) => x.trim())
    .filter(Boolean)
  if (!items.length) return
  const filled = rows.value.filter((r) => !isEmpty(r))
  rows.value = [
    ...filled,
    ...items.map((x) => ({
      ...emptyRow(),
      ...(x.includes('@') ? { email: x } : { studentCode: x }),
    })),
  ]
  paste.value = ''
  pasteOpen.value = false
}

const isEmpty = (r) => !Object.values(r).some((v) => String(v).trim())
const CODE = /^\d{11}$/
const EMAIL = /^(\d{11})@[^@\s]+\.[^@\s]+$/
/** @returns {string} ข้อความ error ของแถว หรือ '' */
function rowProblem(r) {
  const code = r.studentCode.trim()
  const email = r.email.trim().toLowerCase()
  if (!code && !email) return 'กรอกรหัสนักศึกษาหรืออีเมล'
  if (code && !CODE.test(code)) return 'รหัสนักศึกษาต้องเป็นตัวเลข 11 หลัก'
  if (email) {
    const m = EMAIL.exec(email)
    if (!m) return 'อีเมลต้องขึ้นต้นด้วยรหัสนักศึกษา 11 หลัก เช่น 66130500013@st.sit.kmutt.ac.th'
    if (code && m[1] !== code) return 'อีเมลไม่ตรงกับรหัสนักศึกษา'
  }
  if (Boolean(r.firstName.trim()) !== Boolean(r.lastName.trim())) return 'กรอกชื่อและนามสกุลคู่กัน'
  return ''
}
function toInput(r) {
  /** @type {import('../api').StudentInput} */
  const o = {}
  for (const k of ['studentCode', 'email', 'title', 'firstName', 'lastName']) {
    const v = r[k].trim()
    if (v && (props.canCreateAccounts || ['studentCode', 'email'].includes(k)))
      o[k] = k === 'email' ? v.toLowerCase() : v
  }
  return o
}

const filledCount = computed(() => rows.value.filter((r) => !isEmpty(r)).length)
const writeCount = computed(() =>
  preview.value
    ? preview.value.summary.insert + preview.value.summary.reactivate + preview.value.summary.create
    : 0,
)

function showError(e) {
  const err = normalizeError(e)
  error.value = err.message
  rowErrors.value = err.errors?.length ? err.errors : null
}

async function check() {
  rows.value = rows.value.filter((r) => !isEmpty(r))
  if (!rows.value.length) rows.value = [emptyRow()]
  /** @type {Record<number, string>} */
  const fe = {}
  rows.value.forEach((r, i) => {
    const p = isEmpty(r) ? 'กรอกรหัสนักศึกษาหรืออีเมล' : rowProblem(r)
    if (p) fe[i] = p
  })
  fieldErrors.value = fe
  error.value = ''
  rowErrors.value = null
  if (reason.value.trim().length > ROSTER_REASON_MAX)
    error.value = `เหตุผลยาวได้ไม่เกิน ${ROSTER_REASON_MAX} ตัวอักษร`
  else if (rows.value.length > ROSTER_MAX_ITEMS)
    error.value = `เพิ่มได้ครั้งละไม่เกิน ${ROSTER_MAX_ITEMS} คน — ใช้นำเข้าไฟล์แทน`
  if (Object.keys(fe).length || error.value) return
  busy.value = true
  try {
    preview.value = await addStudents(props.sectionId, {
      students: rows.value.map(toInput),
      ...(reason.value.trim() ? { reason: reason.value.trim() } : {}),
      dryRun: true,
    })
    step.value = 'preview'
  } catch (e) {
    showError(e)
  } finally {
    busy.value = false
  }
}

async function confirm() {
  busy.value = true
  error.value = ''
  rowErrors.value = null
  try {
    const result = await addStudents(props.sectionId, {
      students: rows.value.map(toInput),
      ...(reason.value.trim() ? { reason: reason.value.trim() } : {}),
      dryRun: false,
    })
    open.value = false
    emit('added', result)
  } catch (e) {
    showError(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <BaseDialog v-model:open="open" title="เพิ่มนักศึกษาเอง" :description="label" wide>
    <div v-if="step === 'form'" class="flex flex-col gap-4">
      <p class="rounded-field bg-page px-3 py-2 text-xs text-muted">
        กรอกรหัสนักศึกษาหรืออีเมลมหาวิทยาลัย (อย่างใดอย่างหนึ่งก็ได้) ·
        คนที่เพิ่มใหม่จะถูกลงขาดอัตโนมัติเฉพาะคาบที่เริ่มหลังจากนี้
        <template v-if="canCreateAccounts">
          · ยังไม่มีบัญชี: กรอกชื่อและนามสกุลด้วย ระบบจะสร้างบัญชีให้</template
        >
        <template v-else> · ต้องมีบัญชีนักศึกษาในระบบอยู่แล้ว</template>
      </p>

      <ol class="flex flex-col gap-3">
        <li
          v-for="(r, i) in rows"
          :key="i"
          class="rounded-field border p-3"
          :class="fieldErrors[i] ? 'border-danger' : 'border-line'"
        >
          <div class="mb-2 flex items-center justify-between gap-2">
            <span class="text-xs font-semibold text-muted">คนที่ {{ i + 1 }}</span>
            <button
              v-if="rows.length > 1"
              type="button"
              class="text-xs font-semibold text-danger hover:underline"
              :disabled="busy"
              @click="removeRow(i)"
            >
              ลบแถว
            </button>
          </div>
          <div class="grid gap-3 sm:grid-cols-2">
            <TextField
              v-model="r.studentCode"
              label="รหัสนักศึกษา"
              placeholder="66130500013"
              :maxlength="11"
              :disabled="busy"
            />
            <TextField
              v-model="r.email"
              type="email"
              label="อีเมล"
              placeholder="66130500013@st.sit.kmutt.ac.th"
              :disabled="busy"
            />
            <template v-if="canCreateAccounts">
              <div
                class="grid grid-cols-[5.5rem_minmax(0,1fr)] gap-3 sm:col-span-2 sm:grid-cols-[6rem_minmax(0,1fr)_minmax(0,1fr)]"
              >
                <TextField v-model="r.title" label="คำนำหน้า" placeholder="นาย" :disabled="busy" />
                <TextField v-model="r.firstName" label="ชื่อ" :disabled="busy" />
                <TextField
                  v-model="r.lastName"
                  label="นามสกุล"
                  class="col-span-2 sm:col-span-1"
                  :disabled="busy"
                />
              </div>
            </template>
          </div>
          <p v-if="fieldErrors[i]" class="mt-2 text-xs text-danger">{{ fieldErrors[i] }}</p>
        </li>
      </ol>

      <div class="flex flex-wrap gap-2">
        <BaseButton variant="secondary" :disabled="busy" @click="addRow">+ เพิ่มแถว</BaseButton>
        <BaseButton variant="secondary" :disabled="busy" @click="pasteOpen = !pasteOpen">
          วางหลายคน
        </BaseButton>
      </div>
      <div v-if="pasteOpen" class="flex flex-col gap-2 rounded-field border border-line p-3">
        <TextField
          v-model="paste"
          label="รหัสนักศึกษาหรืออีเมล บรรทัดละคน (หรือคั่นด้วย , )"
          multiline
          :disabled="busy"
        />
        <div>
          <BaseButton :disabled="!paste.trim()" @click="applyPaste">ใส่ในรายการ</BaseButton>
        </div>
      </div>

      <TextField
        v-model="reason"
        label="เหตุผล (ไม่บังคับ)"
        multiline
        :maxlength="ROSTER_REASON_MAX"
        placeholder="เช่น ลงทะเบียนเพิ่มช่วงเพิ่ม-ถอน"
        :disabled="busy"
      />
    </div>

    <div v-else-if="preview" class="flex flex-col gap-3">
      <p class="text-sm text-muted">ตรวจผลก่อนบันทึก — ยังไม่มีอะไรถูกบันทึก</p>
      <AddResultPreview :result="preview" row-label="คนที่" />
    </div>

    <div v-if="error" class="mt-4 flex flex-col gap-2">
      <p role="alert" class="rounded-field bg-danger-bg px-3 py-2 text-sm text-danger">
        {{ error }}
      </p>
      <RowErrorsTable v-if="rowErrors" :errors="rowErrors" row-label="คนที่" />
    </div>

    <template #actions>
      <template v-if="step === 'form'">
        <BaseButton variant="secondary" :disabled="busy" @click="open = false">ยกเลิก</BaseButton>
        <BaseButton :loading="busy" :disabled="!filledCount" @click="check">
          ตรวจสอบ {{ filledCount }} คน
        </BaseButton>
      </template>
      <template v-else>
        <BaseButton variant="secondary" :disabled="busy" @click="step = 'form'"
          >แก้รายการ</BaseButton
        >
        <BaseButton :loading="busy" :disabled="!writeCount" @click="confirm">
          ยืนยันเพิ่ม {{ writeCount }} คน
        </BaseButton>
      </template>
    </template>
  </BaseDialog>
</template>
