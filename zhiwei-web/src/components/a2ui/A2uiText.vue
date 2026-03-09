<script setup lang="ts">
defineProps<{
  text?: string
  variant?: string
}>()
</script>

<template>
  <p :class="variant === 'caption' ? 'text-xs text-muted-foreground' : 'text-sm'">
    {{ text }}
  </p>
</template>
