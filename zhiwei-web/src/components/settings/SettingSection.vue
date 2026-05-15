<script setup lang="ts">
defineProps<{
  title: string
  icon?: string
  description?: string
}>()
</script>

<template>
  <section class="setting-section">
    <div class="flex items-start justify-between gap-3 pb-1">
      <div class="min-w-0 space-y-0.5">
        <div class="flex items-center gap-2">
          <slot name="icon">
            <span v-if="icon" class="text-base text-muted-foreground">{{ icon }}</span>
          </slot>
          <h3 class="text-[15px] font-semibold tracking-tight text-foreground">{{ title }}</h3>
        </div>
        <p v-if="description" class="text-[13px] leading-relaxed text-muted-foreground">
          {{ description }}
        </p>
      </div>
      <div v-if="$slots['header-actions']" class="flex items-center gap-2">
        <slot name="header-actions" />
      </div>
    </div>
    <div class="divide-y divide-border/40">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.setting-section {
  position: relative;
  padding: 0;
}
</style>
