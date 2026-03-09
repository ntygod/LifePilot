<script setup lang="ts">
import { Label } from '@/components/ui/label'

defineProps<{
  label: string
  description?: string
  required?: boolean
  htmlFor?: string
}>()
</script>

<template>
  <div class="flex items-center justify-between py-3">
    <div class="flex-1 min-w-0 pr-4">
      <Label :for="htmlFor" class="text-sm font-medium leading-none">
        {{ label }}
        <span v-if="required" class="text-destructive">*</span>
      </Label>
      <p v-if="description" class="text-xs text-muted-foreground mt-1">
        {{ description }}
      </p>
    </div>
    <div class="flex-shrink-0">
      <slot />
    </div>
  </div>
</template>
