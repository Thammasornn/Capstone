<script setup>
/** วิเคราะห์การเข้าเรียน: การ์ดสรุป (5.4) + กราฟรายคาบ (5.5) + ตารางคาบ + ตารางนักศึกษา (5.6) */
import BaseCard from '@/shared/components/BaseCard.vue'
import DataTable from '@/shared/components/DataTable.vue'
import EmptyState from '@/shared/components/EmptyState.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import RefreshButton from '@/shared/components/RefreshButton.vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import CourseSectionSelect from '@/entities/course/components/CourseSectionSelect.vue'
import { formatSessionStatus } from '@/entities/attendance/status'
import { formatThaiDate, formatTime } from '@/shared/utils/datetime'
import { formatRate } from '@/shared/utils/format'
import { useCourseDashboard } from '../composables/useCourseDashboard'
import CourseSummaryCards from '../components/CourseSummaryCards.vue'
import SessionTrendChart from '../components/SessionTrendChart.vue'
import StudentAttendanceTable from '../components/StudentAttendanceTable.vue'

const { store, summary, sessions, students, loading, error, updatedAt, load } = useCourseDashboard()

const sessionColumns = [
  { key: 'sessionDate', label: 'วันที่', sortable: true },
  { key: 'sectionCode', label: 'กลุ่ม' },
  { key: 'time', label: 'เวลา' },
  { key: 'sessionStatus', label: 'สถานะ' },
  { key: 'attendanceRate', label: 'อัตราเข้าเรียน', align: 'right' },
  { key: 'link', label: '' },
]
</script>

<template>
  <section class="flex flex-col gap-6">
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div class="w-full max-w-2xl"><CourseSectionSelect /></div>
      <RefreshButton :loading="loading" :updated-at="updatedAt" @refresh="load" />
    </div>

    <EmptyState
      v-if="store.loaded && !store.courses.length"
      title="ยังไม่มีรายวิชา"
      description="รายวิชาจะแสดงเมื่อคุณเป็นผู้สอนอย่างน้อย 1 กลุ่มเรียน"
    />
    <ErrorState v-else-if="error && !summary" :message="error.message" @retry="load" />
    <LoadingSkeleton v-else-if="!summary" :lines="6" />

    <template v-else>
      <CourseSummaryCards :summary="summary" />

      <BaseCard>
        <h2 class="mb-4 text-base font-bold">การเข้าเรียนรายคาบ</h2>
        <SessionTrendChart :sessions="sessions" />
      </BaseCard>

      <div>
        <h2 class="mb-3 text-base font-bold">คาบเรียนทั้งหมด</h2>
        <DataTable
          :columns="sessionColumns"
          :rows="sessions"
          row-key="sessionId"
          caption="คาบเรียน"
        >
          <template #cell-sessionDate="{ value }">{{ formatThaiDate(value) }}</template>
          <template #cell-time="{ row }">
            {{ formatTime(row.startTime) }}–{{ formatTime(row.endTime) }}
          </template>
          <template #cell-sessionStatus="{ value }">
            <StatusBadge tone="neutral" :label="formatSessionStatus(value)" />
          </template>
          <template #cell-attendanceRate="{ row }">
            {{ row.started ? formatRate(row.attendanceRate) : 'ยังไม่ถึงคาบ' }}
          </template>
          <template #cell-link="{ row }">
            <RouterLink
              v-if="row.started"
              :to="{ name: 'session-report', params: { sessionId: row.sessionId } }"
              class="text-xs font-semibold text-primary hover:underline"
            >
              ดูรายงาน
            </RouterLink>
          </template>
        </DataTable>
      </div>

      <div>
        <h2 class="mb-3 text-base font-bold">นักศึกษา</h2>
        <StudentAttendanceTable :students="students" />
      </div>
    </template>
  </section>
</template>
