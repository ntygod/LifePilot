<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { useSlots } from 'vue'
import { cn } from '@/lib/utils'

interface Props {
  title: string
  description?: string
  eyebrow?: string
  class?: HTMLAttributes['class']
}

const props = defineProps<Props>()
const slots = useSlots()
</script>

<template>
  <header :class="cn('page-header border-b border-border/55 pb-3.5', props.class)">
    <div class="flex flex-col gap-3">
      <div class="flex flex-col gap-2 lg:flex-row lg:items-start lg:justify-between lg:gap-4">
        <div class="min-w-0 flex-1 max-w-3xl space-y-1.5">
          <div v-if="props.eyebrow || slots.eyebrow" class="surface-label">
            <slot name="eyebrow">{{ props.eyebrow }}</slot>
          </div>
          <div class="space-y-1">
            <h1 class="text-[1.28rem] font-semibold tracking-tight text-foreground sm:text-[1.38rem]">
              {{ props.title }}
            </h1>
            <p v-if="props.description" class="max-w-[42rem] text-sm leading-5 text-muted-foreground">
              {{ props.description }}
            </p>
          </div>
        </div>

        <div v-if="slots.actions" class="flex flex-wrap items-center gap-2 lg:shrink-0 lg:justify-end">
          <slot name="actions" />
        </div>
      </div>

      <div v-if="slots.meta" class="grid gap-2 border-t border-border/50 pt-2 md:grid-cols-2 xl:grid-cols-4">
        <slot name="meta" />
      </div>
    </div>
  </header>
</template>
