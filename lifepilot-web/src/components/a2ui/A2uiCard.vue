<script setup lang="ts">
defineProps<{
  title?: string
  subtitle?: string
  elevated?: boolean
}>()
</script>

<template>
  <div
    class="rounded-lg border bg-card text-card-foreground p-4"
    :class="elevated ? 'shadow-md' : ''"
  >
    <h3 v-if="title" class="text-sm font-semibold">{{ title }}</h3>
    <p v-if="subtitle" class="text-xs text-muted-foreground mt-1">{{ subtitle }}</p>
    <div class="mt-2">
      <slot />
    </div>
  </div>
</template>
