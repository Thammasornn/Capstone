<script setup>
/** ข้อความยินยอม v1 + checkbox 2 ข้อ (ไม่ติ๊กไว้ล่วงหน้า) */
import { computed, ref } from 'vue'
import BaseButton from '@/shared/components/BaseButton.vue'
import { CONSENT_TEXT } from '../consent-text'

const emit = defineEmits(['accept', 'decline'])
const checked = ref({ consent: false, adult: false })
const canAccept = computed(() => checked.value.consent && checked.value.adult)
</script>

<template>
  <div class="flex flex-col gap-5">
    <div>
      <h2 class="text-lg font-bold">{{ CONSENT_TEXT.title }}</h2>
      <p class="mt-1 text-xs text-muted">ฉบับ {{ CONSENT_TEXT.version }}</p>
    </div>
    <div
      class="max-h-[50vh] overflow-y-auto rounded-field border border-line bg-page p-4 text-sm leading-relaxed"
      tabindex="0"
    >
      <p>{{ CONSENT_TEXT.intro }}</p>
      <section v-for="s in CONSENT_TEXT.sections" :key="s.heading" class="mt-4">
        <h3 class="font-semibold">{{ s.heading }}</h3>
        <p v-for="(line, i) in s.body" :key="i" class="mt-1">{{ line }}</p>
      </section>
    </div>
    <div class="flex flex-col gap-3">
      <label
        v-for="box in CONSENT_TEXT.checkboxes"
        :key="box.key"
        class="flex items-start gap-3 text-sm"
      >
        <input
          v-model="checked[box.key]"
          type="checkbox"
          class="mt-0.5 size-5 shrink-0 accent-primary"
        />
        <span>{{ box.label }}</span>
      </label>
      <p v-if="checked.consent && !checked.adult" class="text-xs text-warning">
        ผู้ที่อายุยังไม่ถึง 20 ปี ต้องให้ผู้ปกครองยินยอมก่อน —
        ติดต่ออาจารย์ผู้สอนเพื่อเช็คชื่อด้วยวิธีอื่น
      </p>
    </div>
    <div class="flex flex-wrap justify-end gap-2">
      <BaseButton variant="secondary" @click="emit('decline')">{{
        CONSENT_TEXT.declineLabel
      }}</BaseButton>
      <BaseButton :disabled="!canAccept" @click="emit('accept', { ageConfirmed: checked.adult })">
        {{ CONSENT_TEXT.acceptLabel }}
      </BaseButton>
    </div>
  </div>
</template>
