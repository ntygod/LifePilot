<script setup lang="ts">
defineProps<{
  title: string
  icon?: string
  description?: string
}>()
</script>

<template>
  <div class="detail-card text-card-foreground">
    <div class="p-4 border-b">
      <div class="flex items-center justify-between gap-2">
        <div class="flex items-center gap-2">
          <span v-if="icon" class="text-lg">{{ icon }}</span>
          <h3 class="text-base font-semibold">{{ title }}</h3>
        </div>
        <div v-if="$slots['header-actions']" class="flex items-center gap-2">
          <slot name="header-actions" />
        </div>
      </div>
      <p v-if="description" class="text-xs text-muted-foreground mt-1">
        {{ description }}
      </p>
    </div>
    <div class="p-4 space-y-4">
      <slot />
    </div>
  </div>
</template>
