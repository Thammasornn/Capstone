<script setup>
/**
 * กราฟแท่งซ้อนรายคาบ (5.5): ตรงเวลา / สาย / ขาด · ข้ามคาบที่ยังไม่เริ่ม (started=false)
 * คลิกแท่ง → รายงานคาบนั้น
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Bar } from 'vue-chartjs'
import { BarElement, CategoryScale, Chart as ChartJS, Legend, LinearScale, Tooltip } from 'chart.js'
import {
  ATTENDANCE_STATUS,
  ATTENDANCE_STATUS_ORDER,
  resolveStatusColor,
} from '@/entities/attendance/status'
import { formatThaiDate } from '@/shared/utils/datetime'

ChartJS.register(BarElement, CategoryScale, LinearScale, Tooltip, Legend)

const props = defineProps({ sessions: { type: Array, required: true } })
const router = useRouter()

const started = computed(() => props.sessions.filter((s) => s.started))

const keyOf = { on_time: 'onTime', late: 'late', absent: 'absent' }
const chartData = computed(() => ({
  labels: started.value.map((s) => `${formatThaiDate(s.sessionDate)} (${s.sectionCode})`),
  datasets: ATTENDANCE_STATUS_ORDER.map((status) => ({
    label: ATTENDANCE_STATUS[status].label,
    data: started.value.map((s) => s[keyOf[status]]),
    backgroundColor: resolveStatusColor(status),
    stack: 'attendance',
    borderRadius: 4,
  })),
}))

const options = computed(() => ({
  responsive: true,
  maintainAspectRatio: false,
  scales: {
    x: { stacked: true, ticks: { maxRotation: 60, autoSkip: true } },
    y: {
      stacked: true,
      beginAtZero: true,
      ticks: { precision: 0 },
      title: { display: true, text: 'คน' },
    },
  },
  plugins: { legend: { position: 'bottom' } },
  onClick: (_event, elements) => {
    const s = elements[0] && started.value[elements[0].index]
    if (s) router.push({ name: 'session-report', params: { sessionId: s.sessionId } })
  },
}))
</script>

<template>
  <div>
    <p v-if="!started.length" class="py-10 text-center text-sm text-muted">
      ยังไม่มีคาบที่เริ่มแล้ว
    </p>
    <div v-else class="h-72" role="img" :aria-label="`กราฟการเข้าเรียน ${started.length} คาบ`">
      <Bar :data="chartData" :options="options" />
    </div>
  </div>
</template>
