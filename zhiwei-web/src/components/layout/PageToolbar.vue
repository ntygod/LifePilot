<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { cn } from '@/lib/utils'

interface Props {
  class?: HTMLAttributes['class']
}

const props = defineProps<Props>()
</script>

<template>
  <div :class="cn('page-toolbar rounded-[calc(var(--radius)+2px)] border border-border/60 p-4 sm:p-5', props.class)">
    <slot />
  </div>
</template>
