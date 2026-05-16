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
  <div class="setting-item grid gap-3 py-3.5 md:grid-cols-[minmax(180px,1fr)_auto] md:items-center">
    <div class="min-w-0 pr-4">
      <Label :for="htmlFor" class="text-sm font-medium leading-none text-foreground/85">
        <slot name="label">
          {{ label }}
          <span v-if="required" class="text-destructive">*</span>
        </slot>
      </Label>
      <p v-if="$slots.description || description" class="mt-1 text-[13px] leading-relaxed text-muted-foreground">
        <slot name="description">{{ description }}</slot>
      </p>
    </div>
    <div class="flex items-center md:justify-end">
      <slot />
    </div>
  </div>
</template>
