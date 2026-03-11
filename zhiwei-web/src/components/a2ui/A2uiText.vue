<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  text?: string
  variant?: string
}>()

const textClass = computed(() => {
  if (props.variant === 'caption') return 'text-xs leading-5 text-muted-foreground'
  if (props.variant === 'eyebrow') return 'surface-label'
  if (props.variant === 'title') return 'text-base font-semibold tracking-tight text-foreground'
  if (props.variant === 'heading') return 'text-lg font-semibold tracking-tight text-foreground'
  return 'text-sm leading-6 text-foreground'
})
</script>

<template>
  <p :class="textClass">{{ text }}</p>
</template>
