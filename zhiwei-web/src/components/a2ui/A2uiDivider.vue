<script setup lang="ts">
defineProps<{
  orientation?: 'horizontal' | 'vertical'
}>()
</script>

<template>
  <hr
    v-if="!orientation || orientation === 'horizontal'"
    class="my-3 border-t border-border/70"
  />
  <div v-else class="mx-2 self-stretch border-l border-border/70" />
</template>
