<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { useSlots } from 'vue'
import { cn } from '@/lib/utils'

interface Props {
  class?: HTMLAttributes['class']
  mainClass?: HTMLAttributes['class']
  railClass?: HTMLAttributes['class']
}

const props = defineProps<Props>()
const slots = useSlots()
</script>

<template>
  <div :class="cn('flex flex-col gap-5 xl:flex-row', props.class)">
    <div :class="cn('min-w-0 flex-1 space-y-5', props.mainClass)">
      <slot />
    </div>

    <div v-if="slots.rail" :class="cn('shrink-0', props.railClass)">
      <slot name="rail" />
    </div>
  </div>
</template>
