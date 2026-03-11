<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { X } from 'lucide-vue-next'
import { useSlots } from 'vue'
import { cn } from '@/lib/utils'

interface Props {
  title: string
  description?: string
  class?: HTMLAttributes['class']
  contentClass?: HTMLAttributes['class']
  showClose?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  showClose: true,
})

const emit = defineEmits<{
  close: []
}>()

const slots = useSlots()
</script>

<template>
  <aside :class="cn('shell-card flex h-full min-h-0 w-full max-w-[320px] shrink-0 flex-col overflow-hidden', props.class)">
    <div class="border-b border-border/55 px-4 py-4">
      <div class="flex items-start justify-between gap-3">
        <div class="space-y-1">
          <div class="surface-label text-[0.68rem]">
            <slot name="eyebrow">Inspector</slot>
          </div>
          <div class="text-sm font-semibold tracking-tight text-foreground">
            {{ props.title }}
          </div>
          <p v-if="props.description" class="text-xs leading-5 text-muted-foreground">
            {{ props.description }}
          </p>
        </div>

        <div class="flex items-center gap-2">
          <slot name="actions" />
          <button
            v-if="props.showClose"
            type="button"
            class="rounded-lg p-2 text-muted-foreground transition-colors hover:bg-accent/80 hover:text-foreground"
            @click="emit('close')"
          >
            <X class="size-4" />
          </button>
        </div>
      </div>
    </div>

    <div :class="cn('min-h-0 flex-1 overflow-y-auto px-4 py-4 scrollbar-thin', props.contentClass)">
      <slot />
    </div>

    <div v-if="slots.footer" class="border-t border-border/55 px-4 py-3">
      <slot name="footer" />
    </div>
  </aside>
</template>
