<script setup>
/** รายชื่อกลุ่มเสี่ยง (ขาด ≥ 2 ครั้ง) เรียงเสี่ยงมากก่อน */
import { computed } from 'vue'
import BaseCard from '@/shared/components/BaseCard.vue'
import RiskBadge from '@/shared/components/RiskBadge.vue'
import { getRiskMeta, isAtRisk, withRiskLevel } from '@/entities/attendance/risk'
import { formatRate } from '@/shared/utils/format'

const props = defineProps({ students: { type: Array, required: true } })
const rows = computed(() => withRiskLevel(props.students).filter((s) => isAtRisk(s.riskLevel)))
</script>

<template>
  <div>
    <p v-if="!rows.length" class="py-10 text-center text-sm text-muted">
      ไม่มีนักศึกษากลุ่มเสี่ยงในตัวเลือกนี้
    </p>
    <ul v-else class="grid gap-3 md:grid-cols-2">
      <li v-for="s in rows" :key="s.studentId">
        <BaseCard class="flex items-center gap-4">
          <div class="min-w-0 flex-1">
            <p class="truncate font-semibold">{{ s.studentName }}</p>
            <p class="text-xs text-muted">{{ s.studentCode }}</p>
            <p class="mt-2 text-sm">
              ขาด <strong class="text-danger">{{ s.absent }}</strong> ครั้ง · สาย {{ s.late }} ครั้ง
              · เข้าเรียน {{ formatRate(s.attendanceRate) }}
            </p>
          </div>
          <RiskBadge
            :tone="getRiskMeta(s.riskLevel).tone"
            :label="getRiskMeta(s.riskLevel).label"
          />
        </BaseCard>
      </li>
    </ul>
  </div>
</template>
