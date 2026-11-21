<script setup>
/** สถานะลงทะเบียนใบหน้าของนักศึกษาในวิชา/กลุ่ม (5.6 + faceRegistered) */
import { computed, ref, watch } from 'vue'
import BaseToggle from '@/shared/components/BaseToggle.vue'
import DataTable from '@/shared/components/DataTable.vue'
import EmptyState from '@/shared/components/EmptyState.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import StatCard from '@/shared/components/StatCard.vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import { useAsyncState } from '@/shared/composables/useAsyncState'
import CourseSectionSelect from '@/entities/course/components/CourseSectionSelect.vue'
import { useCourseStore } from '@/entities/course/store'
import { getRegistrationStatus } from '../api'

const store = useCourseStore()
const onlyMissing = ref(true)
const state = useAsyncState(
  () =>
    store.selectedCourseId
      ? getRegistrationStatus(store.selectedCourseId, store.selectedSectionId, null)
      : null,
  { immediate: false },
)
watch(
  () => [store.selectedCourseId, store.selectedSectionId],
  () => state.run(),
  { immediate: true },
)

const all = computed(() => state.data.value?.items ?? [])
const registered = computed(() => all.value.filter((s) => s.faceRegistered).length)
const rows = computed(() =>
  onlyMissing.value ? all.value.filter((s) => !s.faceRegistered) : all.value,
)
const columns = [
  { key: 'studentCode', label: 'รหัสนักศึกษา', sortable: true },
  { key: 'studentName', label: 'ชื่อ', sortable: true },
  { key: 'faceRegistered', label: 'สถานะ' },
]
</script>

<template>
  <section class="flex flex-col gap-6">
    <div class="w-full max-w-2xl"><CourseSectionSelect /></div>

    <EmptyState v-if="store.loaded && !store.courses.length" title="ยังไม่มีรายวิชา" />
    <ErrorState
      v-else-if="state.error.value && !state.data.value"
      :message="state.error.value.message"
      @retry="state.run"
    />
    <LoadingSkeleton v-else-if="!state.data.value" :lines="5" />
    <template v-else>
      <div class="grid grid-cols-2 gap-3 lg:grid-cols-3">
        <StatCard
          label="ลงทะเบียนแล้ว"
          :value="registered"
          tone="success"
          :hint="`จาก ${all.length} คน`"
        />
        <StatCard
          label="ยังไม่ลงทะเบียน"
          :value="all.length - registered"
          tone="warning"
          hint="ต้องเช็คชื่อด้วยมือ"
        />
      </div>
      <BaseToggle v-model="onlyMissing" label="แสดงเฉพาะคนที่ยังไม่ลงทะเบียน" />
      <DataTable
        :columns="columns"
        :rows="rows"
        row-key="studentId"
        caption="สถานะลงทะเบียนใบหน้า"
        empty-text="ทุกคนลงทะเบียนแล้ว"
      >
        <template #cell-faceRegistered="{ value }">
          <StatusBadge
            :tone="value ? 'success' : 'warning'"
            :label="value ? 'ลงทะเบียนแล้ว' : 'ยังไม่ลงทะเบียน'"
          />
        </template>
      </DataTable>
    </template>
  </section>
</template>
