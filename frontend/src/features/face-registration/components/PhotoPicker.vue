<script setup>
/** เลือกรูปจากเครื่อง (JPEG / PNG ≤ 5MB ต่อรูป) */
import { ref } from 'vue'
import { ALLOWED_TYPES } from '../constants'

const emit = defineEmits(['picked'])
const input = ref(/** @type {HTMLInputElement | null} */ (null))

function onChange(event) {
  const files = Array.from(event.target.files ?? [])
  if (files.length) emit('picked', files)
  event.target.value = ''
}
</script>

<template>
  <div class="flex justify-center">
    <input
      ref="input"
      type="file"
      :accept="ALLOWED_TYPES.join(',')"
      multiple
      class="sr-only"
      @change="onChange"
    />
    <button
      type="button"
      class="inline-flex min-h-11 items-center rounded-button border border-dashed border-primary/50 px-4 text-sm font-semibold text-primary hover:bg-primary/5"
      @click="input?.click()"
    >
      เลือกรูปจากเครื่อง
    </button>
  </div>
</template>
