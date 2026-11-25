<script setup>
/**
 * ประวัติการนำเข้าผลเช็คชื่อของคาบ (GET .../imports) — กดรายการเพื่อดูทุกแถวจากไฟล์ (GET .../imports/{importId})
 * ค่าดิบ → นักศึกษา → สถานะ → action · แถวที่บันทึกจริง (applied) แสดงเด่น
 * reloadKey เปลี่ยน = โหลดใหม่ (หลังนำเข้าสำเร็จ)
 */
import { ref, watch } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import BaseCard from '@/shared/components/BaseCard.vue'
import BaseDialog from '@/shared/components/BaseDialog.vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import { normalizeError } from '@/shared/api/errors'
import { formatThaiDateTime, formatTimeOfDateTime } from '@/shared/utils/datetime'
import { getStatusMeta } from '@/entities/attendance/status'
import {
  IMPORT_ACTION_LABEL,
  IMPORT_METHOD_LABEL,
  UNMATCHED_REASON_LABEL,
  getImportDetail,
  getImportHistory,
} from '@/entities/attendance/import'

const props = defineProps({
  sessionId: { type: String, required: true },
  reloadKey: { type: Number, default: 0 },
})

const items = ref(/** @type {any[]} */ ([]))
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    items.value = await getImportHistory(props.sessionId)
  } catch (e) {
    error.value = normalizeError(e).message
  } finally {
    loading.value = false
  }
}
watch(() => [props.sessionId, props.reloadKey], load, { immediate: true })

const detailOpen = ref(false)
const detail = ref(/** @type {any} */ (null))
const detailError = ref('')
const detailLoading = ref(false)
async function openDetail(item) {
  detail.value = { summary: item, rows: [] }
  detailError.value = ''
  detailOpen.value = true
  detailLoading.value = true
  try {
    detail.value = await getImportDetail(props.sessionId, item.importId)
  } catch (e) {
    detailError.value = normalizeError(e).message
  } finally {
    detailLoading.value = false
  }
}

const methodLabel = (m) => IMPORT_METHOD_LABEL[m] ?? m
function counts(i) {
  const parts = [`${i.totalRows} แถว`]
  if (i.insert + i.replace) parts.push(`บันทึก ${i.insert + i.replace}`)
  if (i.skip) parts.push(`ข้าม ${i.skip}`)
  if (i.conflict) parts.push(`ขัดแย้ง ${i.conflict}`)
  if (i.unmatched) parts.push(`จับคู่ไม่ได้ ${i.unmatched}`)
  return parts.join(' · ')
}
</script>

<template>
  <BaseCard>
    <h2 class="mb-3 text-base font-bold">ประวัติการนำเข้า</h2>
    <p v-if="error" role="alert" class="text-sm text-danger">{{ error }}</p>
    <p v-else-if="loading && !items.length" class="text-sm text-muted">กำลังโหลด…</p>
    <p v-else-if="!items.length" class="text-sm text-muted">ยังไม่เคยนำเข้าผลเช็คชื่อของคาบนี้</p>
    <ul v-else class="divide-y divide-line">
      <li v-for="i in items" :key="i.importId">
        <button
          type="button"
          class="flex w-full flex-wrap items-start justify-between gap-2 px-1 py-3 text-left hover:bg-page"
          @click="openDetail(i)"
        >
          <span class="min-w-0">
            <span class="block text-sm font-semibold break-all">
              {{ i.fileName }}
            </span>
            <span class="block text-xs text-muted">
              {{ formatThaiDateTime(i.importedAt) }} · {{ i.importedBy }} ·
              {{ methodLabel(i.method) }}
            </span>
            <span class="block text-xs text-muted">{{ counts(i) }}</span>
          </span>
          <span class="text-xs font-semibold text-primary">ดูรายละเอียด</span>
        </button>
      </li>
    </ul>

    <BaseDialog
      v-model:open="detailOpen"
      :title="detail?.summary?.fileName ?? 'รายละเอียดการนำเข้า'"
      :description="
        detail?.summary
          ? `${formatThaiDateTime(detail.summary.importedAt)} · ${detail.summary.importedBy} · ${methodLabel(detail.summary.method)}`
          : ''
      "
      wide
    >
      <div v-if="detail?.summary" class="flex flex-col gap-3">
        <p class="text-sm"><span class="text-muted">เหตุผล:</span> {{ detail.summary.reason }}</p>
        <p class="text-xs text-muted">
          {{ counts(detail.summary) }}
          <template v-if="detail.summary.overrideCamera"> · แทนที่ผลจากกล้อง</template>
          <template v-if="detail.summary.overrideManual"> · แทนที่ผลที่อาจารย์แก้</template>
        </p>
        <p v-if="detailError" role="alert" class="text-sm text-danger">{{ detailError }}</p>
        <p v-else-if="detailLoading" class="text-sm text-muted">กำลังโหลด…</p>
        <div v-else class="max-h-[60vh] overflow-auto rounded-field border border-line">
          <table class="w-full text-sm">
            <thead class="sticky top-0 bg-page text-xs text-muted">
              <tr>
                <th class="px-2 py-2 text-left font-medium">แถว</th>
                <th class="px-2 py-2 text-left font-medium">ค่าในไฟล์</th>
                <th class="px-2 py-2 text-left font-medium">นักศึกษา</th>
                <th class="px-2 py-2 text-left font-medium">สถานะ</th>
                <th class="px-2 py-2 text-left font-medium">ผล</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="r in detail.rows"
                :key="r.rowNo"
                class="border-t border-line align-top"
                :class="r.applied ? 'bg-success-bg/60' : 'text-muted'"
              >
                <td class="px-2 py-1.5">{{ r.rowNo }}</td>
                <td class="px-2 py-1.5 text-xs break-all">
                  {{ r.rawIdentifier || '-' }}
                  <template v-if="r.rawJoinTime"><br />เวลา {{ r.rawJoinTime }}</template>
                  <template v-if="r.rawStatus"><br />สถานะ {{ r.rawStatus }}</template>
                </td>
                <td class="px-2 py-1.5">
                  <template v-if="r.studentCode">
                    {{ r.studentName }}
                    <span class="block text-xs text-muted">{{ r.studentCode }}</span>
                  </template>
                  <template v-else>-</template>
                </td>
                <td class="px-2 py-1.5">
                  <StatusBadge
                    v-if="r.status"
                    :tone="getStatusMeta(r.status).tone"
                    :label="getStatusMeta(r.status).label"
                  />
                  <span v-if="r.checkInTime" class="block text-xs text-muted">
                    เข้า {{ formatTimeOfDateTime(r.checkInTime) }}
                  </span>
                  <template v-if="!r.status">-</template>
                </td>
                <td class="px-2 py-1.5 text-xs">
                  <template v-if="r.unmatchedReason">
                    จับคู่ไม่ได้:
                    {{ UNMATCHED_REASON_LABEL[r.unmatchedReason] ?? r.unmatchedReason }}
                  </template>
                  <template v-else>{{ IMPORT_ACTION_LABEL[r.action] ?? r.action ?? '-' }}</template>
                  <span v-if="r.applied" class="block font-semibold text-success">บันทึกแล้ว</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <template #actions>
        <BaseButton variant="secondary" @click="detailOpen = false">ปิด</BaseButton>
      </template>
    </BaseDialog>
  </BaseCard>
</template>
