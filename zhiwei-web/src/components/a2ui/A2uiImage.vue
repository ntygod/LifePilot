<script setup lang="ts">
defineProps<{
  src?: string
  alt?: string
  width?: string | number
  height?: string | number
}>()
</script>

<template>
  <img
    :src="src"
    :alt="alt ?? ''"
    :width="width"
    :height="height"
    class="max-w-full rounded-[calc(var(--radius)+4px)] border border-border/60 bg-background/60 object-cover"
  />
</template>
