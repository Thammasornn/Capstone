<script setup>
/** select มี label · options: [{ value, label, disabled? }] · value null = ตัวเลือก placeholder */
import { useId } from 'vue'

defineProps({
  label: { type: String, required: true },
  options: { type: Array, required: true },
  placeholder: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  hideLabel: { type: Boolean, default: false },
})
const model = defineModel({ type: [String, Number, null], default: null })
const id = useId()
</script>

<template>
  <div class="flex flex-col gap-1.5">
    <label :for="id" class="text-xs font-medium text-muted" :class="hideLabel && 'sr-only'">
      {{ label }}
    </label>
    <select
      :id="id"
      v-model="model"
      :disabled="disabled"
      class="min-h-11 rounded-field border border-line bg-surface px-3 py-2 text-sm text-ink focus:border-primary disabled:opacity-60"
    >
      <option v-if="placeholder" :value="null">{{ placeholder }}</option>
      <option
        v-for="option in options"
        :key="String(option.value)"
        :value="option.value"
        :disabled="option.disabled"
      >
        {{ option.label }}
      </option>
    </select>
  </div>
</template>
