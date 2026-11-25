<script setup>
/**
 * ปุ่มต่อคาบ — แสดงตาม allowedActions ที่ backend ส่งมาเท่านั้น
 * adjust: คาบที่กำลังเรียน → หน้าห้อง (เปิดเมนูจัดการคาบ) · คาบที่จบ ≤ 7 วัน → Session Report ("แก้เวลาคาบ")
 */
import { computed } from 'vue'

const props = defineProps({ session: { type: Object, required: true } })
defineEmits(['reschedule', 'cancel', 'restore'])

const has = (a) => props.session.allowedActions?.includes(a)
const adjustTo = computed(() =>
  props.session.sessionStatus === 'in_progress'
    ? { name: 'room-live', params: { roomId: props.session.roomId }, query: { manage: '1' } }
    : { name: 'session-report', params: { sessionId: props.session.sessionId } },
)
const btn =
  'inline-flex min-h-9 items-center rounded-button px-2.5 text-xs font-semibold hover:bg-primary/5'
</script>

<template>
  <div class="flex flex-wrap gap-1">
    <button
      v-if="has('reschedule')"
      type="button"
      :class="[btn, 'text-primary']"
      @click="$emit('reschedule')"
    >
      แก้ / เลื่อน
    </button>
    <RouterLink v-if="has('adjust')" :to="adjustTo" :class="[btn, 'text-primary']">
      {{ session.sessionStatus === 'in_progress' ? 'จัดการคาบ (หน้าห้อง)' : 'แก้เวลาคาบ' }}
    </RouterLink>
    <RouterLink
      v-if="has('report')"
      :to="{ name: 'session-report', params: { sessionId: session.sessionId } }"
      :class="[btn, 'text-primary']"
    >
      รายงาน
    </RouterLink>
    <button
      v-if="has('restore')"
      type="button"
      :class="[btn, 'text-primary']"
      @click="$emit('restore')"
    >
      คืนคาบ
    </button>
    <button
      v-if="has('cancel')"
      type="button"
      :class="[btn, 'text-danger hover:bg-danger/5']"
      @click="$emit('cancel')"
    >
      ยกเลิก
    </button>
  </div>
</template>
