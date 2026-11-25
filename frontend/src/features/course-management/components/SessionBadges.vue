<script setup>
/** ป้ายของคาบ: แก้รายคาบ · เลื่อนจาก · ชดเชยคาบ · คาบพิเศษ · มีคาบชดเชย · ยกเลิก: เหตุผล */
import { computed } from 'vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'
import { shortDate } from '../composables/sessionForm'

const props = defineProps({ session: { type: Object, required: true } })

const badges = computed(() => {
  const s = props.session
  const list = []
  const cancelled = s.sessionStatus === 'cancelled'
  if (s.originalDate) list.push({ tone: 'info', label: `เลื่อนจาก ${shortDate(s.originalDate)}` })
  if (s.makeupForDate) list.push({ tone: 'info', label: `ชดเชยคาบ ${shortDate(s.makeupForDate)}` })
  else if (s.isExtra) list.push({ tone: 'info', label: 'คาบพิเศษ' })
  if (s.isOverridden && !s.isExtra && !s.originalDate && !cancelled)
    list.push({ tone: 'neutral', label: 'แก้รายคาบ' })
  if (cancelled && s.makeupSessionId) list.push({ tone: 'neutral', label: 'มีคาบชดเชยแล้ว' })
  return list
})
</script>

<template>
  <span class="flex flex-wrap items-center gap-1">
    <StatusBadge v-for="b in badges" :key="b.label" :tone="b.tone" :label="b.label" />
    <span v-if="session.sessionStatus === 'cancelled'" class="text-xs break-words text-danger">
      ยกเลิก: {{ session.cancelReason || '-' }}
    </span>
  </span>
</template>
