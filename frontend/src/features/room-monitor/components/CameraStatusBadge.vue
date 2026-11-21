<script setup>
import { computed } from 'vue'
import StatusBadge from '@/shared/components/StatusBadge.vue'

const props = defineProps({ status: { type: String, default: null } })

const meta = computed(
  () =>
    ({
      live: { tone: 'success', label: 'กล้องออนไลน์' },
      no_signal: { tone: 'danger', label: 'ไม่มีสัญญาณ' },
    })[props.status] ?? { tone: 'neutral', label: 'ไม่มีกล้อง' },
)
</script>

<template>
  <StatusBadge :tone="meta.tone" :label="meta.label" dot />
</template>
