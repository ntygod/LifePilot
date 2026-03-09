<script setup lang="ts">
defineProps<{
  ordered?: boolean
}>()
</script>

<template>
  <component :is="ordered ? 'ol' : 'ul'" class="space-y-1 text-sm">
    <slot />
  </component>
</template>
