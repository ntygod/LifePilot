<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { computed } from 'vue'
import { cn } from '@/lib/utils'

interface Props {
  class?: HTMLAttributes['class']
  size?: 'default' | 'wide' | 'full'
  padded?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  size: 'default',
  padded: true,
})

const sizeClass = computed(() => {
  if (props.size === 'wide') return 'max-w-[1440px]'
  if (props.size === 'full') return 'max-w-none'
  return 'max-w-[1280px]'
})
</script>

<template>
  <div
    :class="cn(
      'mx-auto w-full min-w-0',
      sizeClass,
      props.padded && 'px-4 sm:px-5 lg:px-6',
      props.class,
    )"
  >
    <slot />
  </div>
</template>
