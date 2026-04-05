<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { useSlots } from 'vue'
import { cn } from '@/lib/utils'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'

interface Props {
  open?: boolean
  title: string
  description?: string
  contentClass?: HTMLAttributes['class']
  bodyClass?: HTMLAttributes['class']
  footerClass?: HTMLAttributes['class']
  /** Sheet 宽度，默认 480px */
  width?: string
  preventOutsideClose?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  open: true,
  width: '480px',
  preventOutsideClose: false,
})

const emit = defineEmits<{
  close: []
  'update:open': [value: boolean]
}>()

const slots = useSlots()

function handleOpenChange(value: boolean) {
  emit('update:open', value)
  if (!value) {
    emit('close')
  }
}

function preventDismiss(event: Event) {
  if (props.preventOutsideClose) {
    event.preventDefault()
  }
}
</script>

<template>
  <Sheet :open="props.open" @update:open="handleOpenChange">
    <SheetContent
      side="right"
      :class="cn('flex flex-col p-0', props.contentClass)"
      :style="{ width: props.width, maxWidth: '92vw' }"
      @pointer-down-outside="preventDismiss"
      @escape-key-down="preventDismiss"
    >
      <SheetHeader class="border-b border-border/50 px-lg py-lg">
        <SheetTitle class="text-lg font-semibold tracking-tight text-foreground">
          {{ props.title }}
        </SheetTitle>
        <SheetDescription v-if="props.description" class="mt-xs max-w-2xl text-sm leading-6 text-muted-foreground">
          {{ props.description }}
        </SheetDescription>
      </SheetHeader>

      <div :class="cn('flex-1 overflow-y-auto px-lg py-lg', props.bodyClass)">
        <slot />
      </div>

      <div v-if="slots.footer" :class="cn('border-t border-border/50 px-lg py-md', props.footerClass)">
        <slot name="footer" />
      </div>
    </SheetContent>
  </Sheet>
</template>
