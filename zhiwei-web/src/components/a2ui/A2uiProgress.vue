<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  value: number
  label?: string
}>()

// 钳制到 [0, 100]
const clampedValue = computed(() => Math.max(0, Math.min(100, props.value)))
</script>

<template>
  <div class="space-y-2">
    <div v-if="label" class="flex items-center justify-between text-sm">
      <span class="text-foreground">{{ label }}</span>
      <span class="surface-chip">{{ clampedValue }}%</span>
    </div>
    <div class="h-2.5 overflow-hidden rounded-full bg-muted/80">
      <div
        class="h-full rounded-full bg-primary transition-all duration-300"
        :style="{ width: `${clampedValue}%` }"
      />
    </div>
    <div v-if="!label" class="text-xs text-muted-foreground text-right">{{ clampedValue }}%</div>
  </div>
</template>
