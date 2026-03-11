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
  <div class="grid gap-3 py-4 md:grid-cols-[minmax(0,1fr)_auto] md:items-center">
    <div class="min-w-0 pr-4">
      <Label :for="htmlFor" class="text-sm font-medium leading-none">
        {{ label }}
        <span v-if="required" class="text-destructive">*</span>
      </Label>
      <p v-if="description" class="mt-1 text-sm leading-6 text-muted-foreground">
        {{ description }}
      </p>
    </div>
    <div class="flex items-center md:justify-end">
      <slot />
    </div>
  </div>
</template>
