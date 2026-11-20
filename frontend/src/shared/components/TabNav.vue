<script setup>
/**
 * แท็บนำทางแบบ pill ตาม Figma (Student Home / Courses hub)
 * แท็บที่ยังไม่เปิดใน R1 ใส่ disabled: true จะแสดงเป็น "เร็ว ๆ นี้"
 */
defineProps({
  items: {
    type: Array,
    required: true,
    validator: (items) => items.every((i) => typeof i.label === 'string' && (i.to || i.disabled)),
  },
  label: { type: String, required: true },
})
</script>

<template>
  <nav :aria-label="label" class="-mx-1 overflow-x-auto">
    <ul class="flex min-w-max gap-2 px-1 py-1">
      <li v-for="item in items" :key="item.label">
        <span
          v-if="item.disabled"
          class="inline-flex cursor-not-allowed items-center gap-1 rounded-button border border-line bg-surface px-4 py-2 text-[13px] font-medium text-muted/60"
          aria-disabled="true"
        >
          {{ item.label }}
          <span class="text-[11px]">(เร็ว ๆ นี้)</span>
        </span>
        <RouterLink
          v-else
          :to="item.to"
          class="inline-flex items-center rounded-button border px-4 py-2 text-[13px] font-medium transition-colors"
          :class="
            item.active
              ? 'border-primary bg-primary text-on-dark'
              : 'border-line bg-surface text-ink hover:border-primary/40'
          "
          :aria-current="item.active ? 'page' : undefined"
        >
          {{ item.label }}
        </RouterLink>
      </li>
    </ul>
  </nav>
</template>
