<script setup>
/**
 * กล่องโต้ตอบ (<dialog> ของเบราว์เซอร์: focus trap + Esc ปิดให้ในตัว)
 * v-model:open คุมการเปิด/ปิด · slot default = เนื้อหา · slot actions = ปุ่มด้านล่าง
 */
import { onMounted, ref, watch } from 'vue'

defineProps({
  title: { type: String, required: true },
  description: { type: String, default: '' },
  /** กว้างขึ้น (สำหรับตาราง) — ยังพอดีจอมือถือ */
  wide: { type: Boolean, default: false },
})
const open = defineModel('open', { type: Boolean, default: false })
const dialog = ref(/** @type {HTMLDialogElement | null} */ (null))

function sync(value) {
  const el = dialog.value
  if (!el) return
  if (value && !el.open) el.showModal()
  if (!value && el.open) el.close()
}
watch(open, sync)
onMounted(() => sync(open.value))
</script>

<template>
  <dialog
    ref="dialog"
    class="m-auto rounded-card border border-line bg-surface p-0 text-ink shadow-card backdrop:bg-dark/40"
    :class="wide ? 'w-[min(880px,calc(100vw-2rem))]' : 'w-[min(520px,calc(100vw-2rem))]'"
    :aria-label="title"
    @close="open = false"
    @click.self="open = false"
  >
    <div class="p-6">
      <h2 class="text-lg font-bold">{{ title }}</h2>
      <p v-if="description" class="mt-1 text-sm text-muted">{{ description }}</p>
      <div class="mt-4">
        <slot />
      </div>
      <div v-if="$slots.actions" class="mt-6 flex flex-wrap justify-end gap-2">
        <slot name="actions" />
      </div>
    </div>
  </dialog>
</template>
