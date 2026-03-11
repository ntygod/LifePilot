<script setup lang="ts">
defineProps<{
  title?: string
  subtitle?: string
  elevated?: boolean
}>()
</script>

<template>
  <div
    class="detail-card p-4 text-card-foreground"
    :class="elevated ? 'shadow-[0_22px_40px_-30px_hsl(var(--shadow-color)/0.26)]' : ''"
  >
    <div v-if="title || subtitle" class="space-y-1 border-b border-border/60 pb-3">
      <h3 v-if="title" class="text-sm font-semibold text-foreground">{{ title }}</h3>
      <p v-if="subtitle" class="text-xs leading-5 text-muted-foreground">{{ subtitle }}</p>
    </div>
    <div class="mt-3">
      <slot />
    </div>
  </div>
</template>
