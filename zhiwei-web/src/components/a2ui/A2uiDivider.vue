<script setup lang="ts">
defineProps<{
  orientation?: 'horizontal' | 'vertical'
}>()
</script>

<template>
  <hr
    v-if="!orientation || orientation === 'horizontal'"
    class="border-t border-border my-2"
  />
  <div v-else class="border-l border-border mx-2 self-stretch" />
</template>
