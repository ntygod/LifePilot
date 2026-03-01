<script setup lang="ts">
defineProps<{
  title: string
  icon?: string
  description?: string
}>()
</script>

<template>
  <div class="rounded-lg border bg-card text-card-foreground shadow-sm">
    <div class="p-4 border-b">
      <div class="flex items-center gap-2">
        <span v-if="icon" class="text-lg">{{ icon }}</span>
        <h3 class="text-base font-semibold">{{ title }}</h3>
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
