<script setup>
/** 400 + errors[] (ไม่บันทึกอะไร) — แสดงทุกแถวที่ผิด · rowNo ว่าง = ทั้งไฟล์ */
defineProps({
  errors: { type: Array, required: true },
  /** ป้อนเอง = "คนที่" · ไฟล์ = "แถว" */
  rowLabel: { type: String, default: 'แถว' },
})
</script>

<template>
  <div class="max-h-64 overflow-auto rounded-field border border-danger/40">
    <table class="w-full text-sm">
      <thead class="sticky top-0 bg-danger-bg text-xs text-danger">
        <tr>
          <th class="px-3 py-2 text-left font-medium">{{ rowLabel }}</th>
          <th class="px-3 py-2 text-left font-medium">ช่อง</th>
          <th class="px-3 py-2 text-left font-medium">ค่า</th>
          <th class="px-3 py-2 text-left font-medium">ข้อความ</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(er, i) in errors" :key="i" class="border-t border-line align-top">
          <td class="px-3 py-1.5">{{ er.rowNo ?? 'ทั้งหมด' }}</td>
          <td class="px-3 py-1.5">{{ er.column ?? '-' }}</td>
          <td class="px-3 py-1.5 break-all">{{ er.value ?? '-' }}</td>
          <td class="px-3 py-1.5">{{ er.message }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
