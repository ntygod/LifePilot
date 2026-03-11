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
  <header :class="cn('page-header border-b border-border/55 pb-6', props.class)">
    <div class="flex flex-col gap-5">
      <div class="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between lg:gap-6">
        <div class="min-w-0 flex-1 max-w-3xl space-y-3">
          <div v-if="props.eyebrow || slots.eyebrow" class="surface-label">
            <slot name="eyebrow">{{ props.eyebrow }}</slot>
          </div>
          <div class="space-y-2">
            <h1 class="text-3xl font-semibold tracking-tight text-foreground sm:text-[2.15rem]">
              {{ props.title }}
            </h1>
            <p v-if="props.description" class="max-w-[42rem] text-sm leading-6 text-muted-foreground sm:text-[0.95rem]">
              {{ props.description }}
            </p>
          </div>
        </div>

        <div v-if="slots.actions" class="flex flex-wrap items-center gap-3 lg:shrink-0 lg:justify-end">
          <slot name="actions" />
        </div>
      </div>

      <div v-if="slots.meta" class="grid gap-3 border-t border-border/50 pt-4 md:grid-cols-2 xl:grid-cols-4">
        <slot name="meta" />
      </div>
    </div>
  </header>
</template>
