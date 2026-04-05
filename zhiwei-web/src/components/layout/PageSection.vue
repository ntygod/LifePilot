<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { useSlots } from 'vue'
import { cn } from '@/lib/utils'

interface Props {
  title?: string
  description?: string
  eyebrow?: string
  class?: HTMLAttributes['class']
  contentClass?: HTMLAttributes['class']
  variant?: 'card' | 'plain'
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'card',
})

const slots = useSlots()
</script>

<template>
  <section :class="cn('space-y-2', props.class)">
    <div v-if="props.title || props.description || props.eyebrow || slots.actions || slots.eyebrow" class="flex flex-col gap-2 lg:flex-row lg:items-end lg:justify-between lg:gap-4">
      <div class="min-w-0 flex-1 space-y-1">
        <div v-if="props.eyebrow || slots.eyebrow" class="surface-label">
          <slot name="eyebrow">{{ props.eyebrow }}</slot>
        </div>
        <h2 v-if="props.title" class="section-title text-foreground">
          {{ props.title }}
        </h2>
        <p v-if="props.description" class="max-w-[42rem] text-sm leading-6 text-muted-foreground">
          {{ props.description }}
        </p>
      </div>

      <div v-if="slots.actions" class="flex flex-wrap items-center gap-3 lg:shrink-0 lg:justify-end">
        <slot name="actions" />
      </div>
    </div>

    <div
      :class="cn(
        props.variant === 'card' && 'section-panel p-3 sm:p-3.5',
        props.contentClass,
      )"
    >
      <slot />
    </div>
  </section>
</template>
