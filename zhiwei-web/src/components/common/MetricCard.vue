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
  <article :class="cn('metric-card group', props.class)">
    <div class="flex items-start justify-between gap-4">
      <div class="min-w-0 flex-1 space-y-3">
        <div class="space-y-1.5">
          <p class="surface-label">
            {{ props.label }}
          </p>
          <div class="text-xl font-semibold tracking-tight text-foreground sm:text-[1.55rem]">
            {{ props.value }}
          </div>
        </div>
        <p v-if="props.hint" class="max-w-[24rem] text-[0.82rem] leading-5 text-muted-foreground sm:text-[0.85rem]">
          {{ props.hint }}
        </p>
        <div v-if="slots.default" class="border-t border-border/60 pt-3 text-sm text-muted-foreground">
          <slot />
        </div>
      </div>

      <div
        v-if="slots.icon"
        class="flex size-11 shrink-0 items-center justify-center rounded-2xl border border-border/70 bg-background/80 text-primary transition-transform duration-200 group-hover:-translate-y-0.5"
      >
        <slot name="icon" />
      </div>
    </div>
  </article>
</template>
