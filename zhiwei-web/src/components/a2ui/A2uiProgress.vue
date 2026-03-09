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
  <div class="space-y-1">
    <div v-if="label" class="flex items-center justify-between text-sm">
      <span class="text-foreground">{{ label }}</span>
      <span class="text-muted-foreground">{{ clampedValue }}%</span>
    </div>
    <div class="h-2 rounded-full bg-muted overflow-hidden">
      <div
        class="h-full rounded-full bg-primary transition-all duration-300"
        :style="{ width: `${clampedValue}%` }"
      />
    </div>
    <div v-if="!label" class="text-xs text-muted-foreground text-right">{{ clampedValue }}%</div>
  </div>
</template>
