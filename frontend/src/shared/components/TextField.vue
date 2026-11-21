<script setup>
/** ช่องกรอกข้อความมี label + ข้อความ error (aria-invalid / aria-describedby) */
import { useId } from 'vue'

defineProps({
  label: { type: String, required: true },
  type: { type: String, default: 'text' },
  placeholder: { type: String, default: '' },
  error: { type: String, default: '' },
  hint: { type: String, default: '' },
  required: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  maxlength: { type: Number, default: undefined },
  multiline: { type: Boolean, default: false },
})
const model = defineModel({ type: [String, Number, null], default: '' })
const id = useId()
</script>

<template>
  <div class="flex flex-col gap-1.5">
    <label :for="id" class="text-xs font-medium text-muted">
      {{ label }}<span v-if="required" class="text-danger" aria-hidden="true"> *</span>
    </label>
    <textarea
      v-if="multiline"
      :id="id"
      v-model="model"
      rows="3"
      :placeholder="placeholder"
      :required="required"
      :disabled="disabled"
      :maxlength="maxlength"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="error || hint ? `${id}-msg` : undefined"
      class="min-h-11 rounded-field border bg-surface px-3 py-2 text-sm text-ink focus:border-primary disabled:opacity-60"
      :class="error ? 'border-danger' : 'border-line'"
    />
    <input
      v-else
      :id="id"
      v-model="model"
      :type="type"
      :placeholder="placeholder"
      :required="required"
      :disabled="disabled"
      :maxlength="maxlength"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="error || hint ? `${id}-msg` : undefined"
      class="min-h-11 rounded-field border bg-surface px-3 py-2 text-sm text-ink focus:border-primary disabled:opacity-60"
      :class="error ? 'border-danger' : 'border-line'"
    />
    <p v-if="error" :id="`${id}-msg`" class="text-xs text-danger">{{ error }}</p>
    <p v-else-if="hint" :id="`${id}-msg`" class="text-xs text-muted">{{ hint }}</p>
  </div>
</template>
