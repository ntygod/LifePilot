<script setup lang="ts">
defineProps<{
  ordered?: boolean
}>()
</script>

<template>
  <component
    :is="ordered ? 'ol' : 'ul'"
    class="space-y-2 text-sm"
    :class="ordered ? 'list-decimal pl-5' : 'list-none'"
  >
    <slot />
  </component>
</template>
