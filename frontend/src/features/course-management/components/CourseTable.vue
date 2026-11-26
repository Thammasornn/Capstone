<script setup>
/**
 * ตารางรายวิชา: 1 แถวต่อกลุ่มเรียน + สวิตช์กล้อง (B6) + นักศึกษา / คาบเรียน (ของกลุ่มนี้) / แก้ไข / ลบ
 * ทุกแถวแสดงรหัสวิชาและปุ่ม (ปุ่มทำกับทั้งรายวิชา) — แถวที่ 2+ ของวิชาเดียวกันแสดงแบบจาง
 */
import { computed } from 'vue'
import DataTable from '@/shared/components/DataTable.vue'
import { formatTime } from '@/shared/utils/datetime'
import CameraAttendanceToggle from './CameraAttendanceToggle.vue'

const props = defineProps({ courses: { type: Array, required: true } })
defineEmits(['delete'])

const DAY = { MON: 'จ.', TUE: 'อ.', WED: 'พ.', THU: 'พฤ.', FRI: 'ศ.', SAT: 'ส.', SUN: 'อา.' }
const rows = computed(() =>
  props.courses.flatMap((c) =>
    c.sections.map((s, i) => ({ ...s, rowKey: s.sectionId, course: c, firstOfCourse: i === 0 })),
  ),
)
const columns = [
  { key: 'course', label: 'รายวิชา' },
  { key: 'sectionCode', label: 'กลุ่ม' },
  { key: 'schedule', label: 'วัน / เวลา' },
  { key: 'checkIn', label: 'เช็คชื่อ' },
  { key: 'roomCode', label: 'ห้อง' },
  { key: 'lecturerName', label: 'ผู้สอน' },
  { key: 'camera', label: 'กล้อง' },
  { key: 'actions', label: '' },
]
</script>

<template>
  <DataTable :columns="columns" :rows="rows" row-key="rowKey" caption="รายวิชาและกลุ่มเรียน">
    <template #cell-course="{ row }">
      <p class="font-semibold" :class="!row.firstOfCourse && 'text-muted'">
        {{ row.course.courseCode }}
      </p>
      <p v-if="row.firstOfCourse" class="max-w-56 truncate text-xs text-muted">
        {{ row.course.courseName }}
      </p>
    </template>
    <template #cell-schedule="{ row }">
      {{ (row.classDays ?? []).map((d) => DAY[d]).join(' ') }}
      {{ formatTime(row.startTime) }}–{{ formatTime(row.endTime) }}
    </template>
    <template #cell-checkIn="{ row }">
      {{ formatTime(row.checkInOpens) }}–{{ formatTime(row.checkInCloses) }}
    </template>
    <template #cell-camera="{ row }">
      <CameraAttendanceToggle
        :section-id="row.sectionId"
        :enabled="row.cameraAttendanceEnabled"
        :label="`เช็คชื่อด้วยกล้อง ${row.course.courseCode} กลุ่ม ${row.sectionCode}`"
      />
    </template>
    <template #cell-actions="{ row }">
      <div class="flex gap-1">
        <RouterLink
          :to="{
            name: 'section-students',
            params: { courseId: row.course.courseId, sectionId: row.sectionId },
          }"
          class="rounded-button px-2 py-1 text-xs font-semibold whitespace-nowrap text-primary hover:bg-primary/5"
          :aria-label="`รายชื่อนักศึกษา ${row.course.courseCode} กลุ่ม ${row.sectionCode}`"
        >
          นักศึกษา
        </RouterLink>
        <RouterLink
          :to="{
            name: 'section-sessions',
            params: { courseId: row.course.courseId, sectionId: row.sectionId },
          }"
          class="rounded-button px-2 py-1 text-xs font-semibold whitespace-nowrap text-primary hover:bg-primary/5"
          :aria-label="`คาบเรียน ${row.course.courseCode} กลุ่ม ${row.sectionCode}`"
        >
          คาบเรียน
        </RouterLink>
        <RouterLink
          :to="{ name: 'course-edit', params: { courseId: row.course.courseId } }"
          class="rounded-button px-2 py-1 text-xs font-semibold text-primary hover:bg-primary/5"
        >
          แก้ไข
        </RouterLink>
        <button
          type="button"
          class="rounded-button px-2 py-1 text-xs font-semibold text-danger hover:bg-danger/5"
          @click="$emit('delete', row.course)"
        >
          ลบ
        </button>
      </div>
    </template>
  </DataTable>
</template>
