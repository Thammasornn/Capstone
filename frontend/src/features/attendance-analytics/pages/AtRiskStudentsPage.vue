<script setup>
/** นักศึกษากลุ่มเสี่ยง (ขาด 2 ครั้ง = ปานกลาง, 3+ = สูง) จาก 5.6 */
import EmptyState from '@/shared/components/EmptyState.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import RefreshButton from '@/shared/components/RefreshButton.vue'
import CourseSectionSelect from '@/entities/course/components/CourseSectionSelect.vue'
import { RISK_THRESHOLDS } from '@/entities/attendance/risk'
import { useCourseDashboard } from '../composables/useCourseDashboard'
import AtRiskList from '../components/AtRiskList.vue'

const { store, summary, students, loading, error, updatedAt, load } = useCourseDashboard()
</script>

<template>
  <section class="flex flex-col gap-6">
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div class="w-full max-w-2xl"><CourseSectionSelect /></div>
      <RefreshButton :loading="loading" :updated-at="updatedAt" @refresh="load" />
    </div>
    <p class="text-sm text-muted">
      เกณฑ์: ขาด {{ RISK_THRESHOLDS.medium }} ครั้ง = เสี่ยงปานกลาง · ขาด
      {{ RISK_THRESHOLDS.high }} ครั้งขึ้นไป = เสี่ยงสูง
    </p>

    <EmptyState v-if="store.loaded && !store.courses.length" title="ยังไม่มีรายวิชา" />
    <ErrorState v-else-if="error && !summary" :message="error.message" @retry="load" />
    <LoadingSkeleton v-else-if="!summary" :lines="4" />
    <AtRiskList v-else :students="students" />
  </section>
</template>
