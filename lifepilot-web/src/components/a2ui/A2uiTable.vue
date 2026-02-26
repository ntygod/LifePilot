<script setup lang="ts">
defineProps<{
  columns: { key: string; label: string }[]
  rows: Record<string, unknown>[]
}>()
</script>

<template>
  <div class="overflow-x-auto rounded-md border border-border">
    <table class="w-full text-sm">
      <thead>
        <tr class="border-b border-border bg-muted">
          <th
            v-for="col in columns"
            :key="col.key"
            class="px-4 py-2 text-left font-medium text-muted-foreground"
          >{{ col.label }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="rows.length === 0">
          <td :colspan="columns.length" class="px-4 py-6 text-center text-muted-foreground">暂无数据</td>
        </tr>
        <tr
          v-for="(row, i) in rows"
          :key="i"
          class="border-b border-border last:border-b-0 hover:bg-muted/50 transition-colors"
        >
          <td
            v-for="col in columns"
            :key="col.key"
            class="px-4 py-2 text-foreground"
          >{{ row[col.key] ?? '' }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
