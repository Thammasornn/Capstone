<script setup>
/** หน้าแรกนักศึกษา: สถานะใบหน้า + ภาพรวมการเข้าเรียน + เรียนวันนี้ */
import { computed } from 'vue'
import BaseCard from '@/shared/components/BaseCard.vue'
import ErrorState from '@/shared/components/ErrorState.vue'
import LoadingSkeleton from '@/shared/components/LoadingSkeleton.vue'
import StatCard from '@/shared/components/StatCard.vue'
import { useAsyncState } from '@/shared/composables/useAsyncState'
import { formatTime } from '@/shared/utils/datetime'
import { formatRate } from '@/shared/utils/format'
import { useAuthStore } from '@/features/auth/store'
import { getMyCoursesWithAttendance } from '../api'

const auth = useAuthStore()
const face = computed(() => auth.user?.student?.faceRegistration ?? { registered: false })
const courses = useAsyncState(() => getMyCoursesWithAttendance(auth.studentId))

const totals = computed(() => {
  const t = { onTime: 0, late: 0, absent: 0 }
  for (const c of courses.data.value ?? []) {
    if (!c.attendance) continue
    t.onTime += c.attendance.onTime
    t.late += c.attendance.late
    t.absent += c.attendance.absent
  }
  const n = t.onTime + t.late + t.absent
  return { ...t, sessions: n, rate: n ? Math.round(((t.onTime + t.late) / n) * 10000) / 100 : 0 }
})

const DAY_KEYS = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT']
const today = computed(() => {
  const key = DAY_KEYS[new Date().getDay()]
  return (courses.data.value ?? [])
    .flatMap((c) =>
      c.sections.filter((s) => (s.classDays ?? []).includes(key)).map((s) => ({ ...s, course: c })),
    )
    .sort((a, b) => a.startTime.localeCompare(b.startTime))
})
</script>

<template>
  <section class="flex flex-col gap-6">
    <div>
      <h1 class="text-[28px] font-bold">สวัสดี {{ auth.user?.firstName }}</h1>
      <p class="mt-1 text-sm text-muted">
        {{ auth.user?.student?.studentCode }} · {{ auth.user?.student?.department }}
      </p>
    </div>

    <BaseCard
      v-if="!face.registered"
      class="flex flex-wrap items-center gap-3 border-warning/40 bg-warning-bg"
    >
      <p class="flex-1 text-sm">
        <strong class="text-warning">ยังไม่ได้ลงทะเบียนใบหน้า</strong> —
        ลงทะเบียนเพื่อให้กล้องเช็คชื่อให้อัตโนมัติ
      </p>
      <RouterLink
        :to="{ name: 'face-registration' }"
        class="text-sm font-semibold text-primary hover:underline"
      >
        ลงทะเบียนเลย
      </RouterLink>
    </BaseCard>

    <ErrorState
      v-if="courses.error.value && !courses.data.value"
      :message="courses.error.value.message"
      @retry="courses.run"
    />
    <LoadingSkeleton v-else-if="!courses.data.value" :lines="4" />
    <template v-else>
      <div class="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard
          label="อัตราเข้าเรียนรวม"
          :value="formatRate(totals.rate, { totalSessions: totals.sessions })"
          tone="primary"
          :hint="`${totals.sessions} คาบ`"
        />
        <StatCard label="ตรงเวลา" :value="totals.onTime" tone="success" hint="ครั้ง" />
        <StatCard label="สาย" :value="totals.late" tone="warning" hint="ครั้ง" />
        <StatCard label="ขาด" :value="totals.absent" tone="danger" hint="ครั้ง" />
      </div>

      <BaseCard>
        <h2 class="mb-3 text-base font-bold">เรียนวันนี้</h2>
        <p v-if="!today.length" class="text-sm text-muted">วันนี้ไม่มีคาบเรียน</p>
        <ul v-else class="divide-y divide-line">
          <li v-for="s in today" :key="s.sectionId" class="flex flex-wrap items-center gap-3 py-3">
            <span class="w-28 text-sm font-semibold"
              >{{ formatTime(s.startTime) }}–{{ formatTime(s.endTime) }}</span
            >
            <span class="flex-1 text-sm"
              >{{ s.course.courseCode }} {{ s.course.courseName }} · กลุ่ม {{ s.sectionCode }}</span
            >
            <span class="text-xs text-muted"
              >ห้อง {{ s.roomCode ?? '-' }} · เช็คชื่อ {{ formatTime(s.checkInOpens) }}–{{
                formatTime(s.checkInCloses)
              }}</span
            >
          </li>
        </ul>
      </BaseCard>
    </template>
  </section>
</template>
