<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { useSlots } from 'vue'
import { cn } from '@/lib/utils'

interface Props {
  label: string
  value: string | number
  hint?: string
  class?: HTMLAttributes['class']
}

const props = defineProps<Props>()
const slots = useSlots()
</script>

<template>
  <article :class="cn('metric-card group', props.class)" :title="props.hint">
    <div class="flex items-start justify-between gap-3">
      <div class="min-w-0 flex-1 space-y-1">
        <div class="space-y-0.5">
          <p class="surface-label">
            {{ props.label }}
          </p>
          <div class="text-[1.1rem] font-semibold tracking-tight text-foreground sm:text-[1.18rem]">
            {{ props.value }}
          </div>
        </div>
        <div v-if="slots.default" class="pt-1 text-xs text-muted-foreground">
          <slot />
        </div>
      </div>

      <div
        v-if="slots.icon"
        class="metric-card-icon"
      >
        <slot name="icon" />
      </div>
    </div>
  </article>
</template>
