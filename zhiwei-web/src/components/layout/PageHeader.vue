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
  <header :class="cn('page-header relative overflow-hidden rounded-xl border border-border/30 bg-gradient-to-br from-card/80 via-card/60 to-background/40 px-5 py-5 sm:px-6 sm:py-6', props.class)">
    <!-- 装饰性光晕 -->
    <div class="pointer-events-none absolute -right-12 -top-12 size-48 rounded-full bg-primary/[0.04] blur-3xl" />
    <div class="pointer-events-none absolute -bottom-8 -left-8 size-32 rounded-full bg-primary/[0.03] blur-2xl" />

    <div class="relative flex flex-col gap-3">
      <div class="flex flex-col gap-2 lg:flex-row lg:items-start lg:justify-between lg:gap-4">
        <div class="min-w-0 flex-1 max-w-3xl space-y-1.5">
          <div v-if="props.eyebrow || slots.eyebrow" class="surface-label text-[0.68rem]">
            <slot name="eyebrow">{{ props.eyebrow }}</slot>
          </div>
          <div class="space-y-1">
            <h1 class="text-[1.28rem] font-semibold tracking-tight text-foreground sm:text-[1.38rem]">
              {{ props.title }}
            </h1>
            <p v-if="props.description" class="max-w-[42rem] text-[13px] leading-relaxed text-muted-foreground">
              {{ props.description }}
            </p>
          </div>
        </div>

        <div v-if="slots.actions" class="flex flex-wrap items-center gap-2 lg:shrink-0 lg:justify-end">
          <slot name="actions" />
        </div>
      </div>

      <div v-if="slots.meta" class="grid gap-2 border-t border-border/30 pt-3 md:grid-cols-2 xl:grid-cols-4">
        <slot name="meta" />
      </div>
    </div>
  </header>
</template>
