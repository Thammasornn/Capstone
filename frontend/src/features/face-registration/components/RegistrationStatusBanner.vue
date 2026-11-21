<script setup>
/** แถบสถานะการลงทะเบียนใบหน้า (จาก /me หรือ 5.11) */
import { formatThaiDateTime } from '@/shared/utils/datetime'

defineProps({
  registered: { type: Boolean, required: true },
  registeredAt: { type: String, default: null },
  activeFaces: { type: Number, default: 0 },
})
</script>

<template>
  <div
    class="flex flex-wrap items-center gap-3 rounded-card border px-5 py-4"
    :class="registered ? 'border-success/30 bg-success-bg' : 'border-warning/30 bg-warning-bg'"
    role="status"
  >
    <div class="min-w-0 flex-1">
      <p class="font-semibold" :class="registered ? 'text-success' : 'text-warning'">
        {{ registered ? 'ลงทะเบียนใบหน้าแล้ว' : 'ยังไม่ได้ลงทะเบียนใบหน้า' }}
      </p>
      <p class="text-sm text-ink/80">
        <template v-if="registered">
          {{ activeFaces }} รูป · เมื่อ {{ formatThaiDateTime(registeredAt) }} —
          กล้องในห้องเรียนเช็คชื่อให้อัตโนมัติ
        </template>
        <template v-else
          >ลงทะเบียนเพื่อให้กล้องเช็คชื่อให้อัตโนมัติ หรือแจ้งอาจารย์ให้เช็คชื่อด้วยมือ</template
        >
      </p>
    </div>
    <slot />
  </div>
</template>
