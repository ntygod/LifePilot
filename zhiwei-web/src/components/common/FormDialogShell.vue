<script setup lang="ts">
import type { HTMLAttributes } from 'vue'
import { useSlots } from 'vue'
import { cn } from '@/lib/utils'
import {
  Dialog,
  DialogDescription,
  DialogHeader,
  DialogScrollContent,
  DialogTitle,
} from '@/components/ui/dialog'

interface Props {
  open?: boolean
  title: string
  description?: string
  contentClass?: HTMLAttributes['class']
  bodyClass?: HTMLAttributes['class']
  footerClass?: HTMLAttributes['class']
  preventOutsideClose?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  open: true,
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
  <Dialog :open="props.open" @update:open="handleOpenChange">
    <DialogScrollContent
      :class="cn('max-h-[90vh] overflow-hidden p-0 sm:max-w-[42rem]', props.contentClass)"
      @pointer-down-outside="preventDismiss"
      @escape-key-down="preventDismiss"
    >
      <div class="flex max-h-[90vh] flex-col">
        <DialogHeader class="border-b border-border/58 bg-background/72 px-6 py-5">
          <DialogTitle class="text-xl font-semibold tracking-tight text-foreground">
            {{ props.title }}
          </DialogTitle>
          <DialogDescription v-if="props.description" class="mt-2 max-w-3xl text-sm leading-6 text-muted-foreground">
            {{ props.description }}
          </DialogDescription>
        </DialogHeader>

        <div :class="cn('flex-1 overflow-y-auto bg-card/72 px-6 py-5', props.bodyClass)">
          <slot />
        </div>

        <div v-if="slots.footer" :class="cn('border-t border-border/58 bg-background/68 px-6 py-4', props.footerClass)">
          <slot name="footer" />
        </div>
      </div>
    </DialogScrollContent>
  </Dialog>
</template>
