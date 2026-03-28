<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { computed, useSlots } from 'vue'
import { cn } from '@/lib/utils'

interface Props {
  title: string
  description?: string
  tone?: 'default' | 'warning' | 'danger'
  class?: HTMLAttributes['class']
}

const props = withDefaults(defineProps<Props>(), {
  tone: 'default',
})

const slots = useSlots()

const toneClass = computed(() => {
  if (props.tone === 'warning') return 'border-amber-300/60 bg-amber-50/56 dark:border-amber-500/24 dark:bg-amber-500/8'
  if (props.tone === 'danger') return 'border-destructive/22 bg-destructive/5'
  return 'border-border/64 bg-card/90'
})
</script>

<template>
  <div :class="cn('state-panel rounded-[calc(var(--radius)+1px)] border px-4 py-4 sm:px-5', toneClass, props.class)">
    <div class="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between sm:gap-5">
      <div class="flex min-w-0 flex-1 items-start gap-3.5">
        <div
          v-if="slots.icon"
          class="flex size-10 shrink-0 items-center justify-center rounded-[0.95rem] border border-border/62 bg-background/84 text-primary"
        >
          <slot name="icon" />
        </div>

        <div class="min-w-0 flex-1 space-y-1.5">
          <h3 class="text-base font-semibold tracking-tight text-foreground">
            {{ props.title }}
          </h3>
          <p v-if="props.description" class="max-w-[42rem] text-sm leading-6 text-muted-foreground sm:text-[0.95rem]">
            {{ props.description }}
          </p>
        </div>
      </div>

      <div v-if="slots.actions" class="flex flex-wrap items-center gap-3 sm:shrink-0 sm:justify-end">
        <slot name="actions" />
      </div>
    </div>

    <div v-if="slots.default" class="mt-4 border-t border-border/60 pt-3">
      <slot />
    </div>
  </div>
</template>
