<script setup>
/** วิชาของฉัน: ตารางเรียน + อัตราเข้าเรียนของแต่ละวิชา (5.3 + 5.9) */
import BaseCard from '@/shared/components/BaseCard.vue'
import EmptyState from '@/shared/components/EmptyState.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import { useAsyncState } from '@/shared/composables/useAsyncState'
import { formatTime } from '@/shared/utils/datetime'
import { formatRate } from '@/shared/utils/format'
import { useAuthStore } from '@/features/auth/store'
import { getMyCoursesWithAttendance } from '../api'

const auth = useAuthStore()
const courses = useAsyncState(() => getMyCoursesWithAttendance(auth.studentId))
const DAY = { MON: 'จ.', TUE: 'อ.', WED: 'พ.', THU: 'พฤ.', FRI: 'ศ.', SAT: 'ส.', SUN: 'อา.' }
</script>

<template>
  <section class="flex flex-col gap-6">
    <h1 class="text-[28px] font-bold">วิชาของฉัน</h1>
    <ErrorState
      v-if="courses.error.value && !courses.data.value"
      :message="courses.error.value.message"
      @retry="courses.run"
    />
    <LoadingSkeleton v-else-if="!courses.data.value" :lines="4" />
    <EmptyState v-else-if="!courses.data.value.length" title="ยังไม่มีรายวิชาที่ลงทะเบียน" />
    <div v-else class="grid gap-4 md:grid-cols-2">
      <BaseCard v-for="c in courses.data.value" :key="c.courseId" class="flex flex-col gap-3">
        <div class="flex items-start justify-between gap-3">
          <div>
            <p class="text-xs font-semibold text-primary">{{ c.courseCode }}</p>
            <h2 class="font-bold">{{ c.courseName }}</h2>
          </div>
          <div class="text-right">
            <p class="text-xl font-bold">
              {{
                c.attendance
                  ? formatRate(c.attendance.attendanceRate, {
                      totalSessions: c.attendance.items.length,
                    })
                  : '-'
              }}
            </p>
            <p class="text-xs text-muted">อัตราเข้าเรียน</p>
          </div>
        </div>
        <ul class="text-sm text-muted">
          <li v-for="s in c.sections" :key="s.sectionId">
            กลุ่ม {{ s.sectionCode }} · {{ (s.classDays ?? []).map((d) => DAY[d]).join(' ') }}
            {{ formatTime(s.startTime) }}–{{ formatTime(s.endTime) }} · ห้อง
            {{ s.roomCode ?? '-' }} ·
            {{ s.lecturerName ?? '-' }}
          </li>
        </ul>
        <p v-if="c.attendance" class="text-sm">
          ตรงเวลา {{ c.attendance.onTime }} · สาย {{ c.attendance.late }} ·
          <span :class="c.attendance.absent >= 2 && 'font-semibold text-danger'"
            >ขาด {{ c.attendance.absent }}</span
          >
        </p>
        <RouterLink
          :to="{ name: 'student-attendance', query: { courseId: c.courseId } }"
          class="self-start text-sm font-semibold text-primary hover:underline"
        >
          ดูประวัติการเข้าเรียน
        </RouterLink>
      </BaseCard>
    </div>
  </section>
</template>
