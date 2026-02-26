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
    class="rounded-md max-w-full"
  />
</template>
