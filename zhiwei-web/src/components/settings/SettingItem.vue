<script setup lang="ts">
import { Label } from '@/components/ui/label'

defineProps<{
  label?: string
  description?: string
  required?: boolean
  htmlFor?: string
}>()
</script>

<template>
  <div class="setting-item grid gap-3 py-3 md:grid-cols-[minmax(180px,1fr)_auto] md:items-center">
    <div class="min-w-0 pr-4">
      <Label :for="htmlFor" class="text-sm font-medium leading-none">
        <slot name="label">
          {{ label }}
          <span v-if="required" class="text-destructive">*</span>
        </slot>
      </Label>
      <p v-if="$slots.description || description" class="mt-1 text-sm leading-6 text-muted-foreground">
        <slot name="description">{{ description }}</slot>
      </p>
    </div>
    <div class="flex items-center md:justify-end">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.setting-item {
  position: relative;
}

.setting-item::before {
  content: "";
  position: absolute;
  left: -1.25rem;
  top: 1.35rem;
  width: 2px;
  height: 1.25rem;
  border-radius: 999px;
  background: linear-gradient(180deg, hsl(from var(--primary) h s l / 0.52), transparent);
  opacity: 0.55;
}
</style>
