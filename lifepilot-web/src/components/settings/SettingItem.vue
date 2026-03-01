<script setup lang="ts">
defineProps<{
  label: string
  description?: string
  required?: boolean
}>()
</script>

<template>
  <div class="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-2 sm:gap-4">
    <div class="flex-1 min-w-0">
      <label class="text-sm font-medium leading-none flex items-center gap-1">
        {{ label }}
        <span v-if="required" class="text-destructive">*</span>
      </label>
      <p v-if="description" class="text-xs text-muted-foreground mt-1">
        {{ description }}
      </p>
    </div>
    <div class="flex-shrink-0 sm:w-64">
      <slot />
    </div>
  </div>
</template>
