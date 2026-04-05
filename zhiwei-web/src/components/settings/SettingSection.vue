<script setup lang="ts">
defineProps<{
  title: string
  icon?: string
  description?: string
}>()
</script>

<template>
  <section class="setting-section detail-card p-4 text-card-foreground sm:p-5">
    <div class="flex items-start justify-between gap-3 border-b border-border/70 pb-3">
      <div class="min-w-0 space-y-1">
        <div class="flex items-center gap-2">
          <slot name="icon">
            <span v-if="icon" class="text-base text-muted-foreground">{{ icon }}</span>
          </slot>
          <h3 class="text-base font-semibold text-foreground">{{ title }}</h3>
        </div>
        <p v-if="description" class="text-sm leading-6 text-muted-foreground">
          {{ description }}
        </p>
      </div>
      <div v-if="$slots['header-actions']" class="flex items-center gap-2">
        <slot name="header-actions" />
      </div>
    </div>
    <div class="mt-1.5 divide-y divide-border/60">
      <slot />
    </div>
  </section>
</template>
