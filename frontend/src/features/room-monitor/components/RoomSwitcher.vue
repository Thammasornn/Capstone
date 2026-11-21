<script setup>
/** สลับห้องจากหน้า Room Live โดยไม่ต้องกลับหน้าเลือกห้อง */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import BaseSelect from '@/shared/components/BaseSelect.vue'

const props = defineProps({
  rooms: { type: Array, required: true },
  roomId: { type: String, required: true },
})
const router = useRouter()

const options = computed(() =>
  props.rooms.map((r) => ({
    value: r.roomId,
    label: `${r.roomCode}${r.currentSession ? ` · ${r.currentSession.courseCode}` : ''}`,
  })),
)
const selected = computed({
  get: () => props.roomId,
  set: (roomId) => roomId && router.push({ name: 'room-live', params: { roomId } }),
})
</script>

<template>
  <div class="w-full sm:w-64">
    <BaseSelect v-model="selected" label="สลับห้อง" :options="options" hide-label />
  </div>
</template>
