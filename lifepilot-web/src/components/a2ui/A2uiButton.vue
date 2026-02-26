<script setup lang="ts">
import type { A2uiSignal } from '@/types'
import { useA2uiSignal } from '@/composables/useA2uiSignal'
import { useChatStore } from '@/stores/chat'

const props = defineProps<{
  label?: string
  variant?: 'default' | 'outline' | 'ghost' | 'destructive'
  disabled?: boolean
  signal?: A2uiSignal
}>()

const { emitSignal } = useA2uiSignal()
const chatStore = useChatStore()

function handleClick() {
  if (props.signal && chatStore.activeSessionId) {
    emitSignal(props.signal, chatStore.activeSessionId)
  }
}
</script>

<template>
  <button
    class="inline-flex items-center justify-center rounded-md text-sm font-medium h-9 px-4 py-2 transition-colors"
    :class="{
      'bg-primary text-primary-foreground hover:bg-primary/90': !variant || variant === 'default',
      'border border-input bg-background hover:bg-accent hover:text-accent-foreground': variant === 'outline',
      'hover:bg-accent hover:text-accent-foreground': variant === 'ghost',
      'bg-destructive text-destructive-foreground hover:bg-destructive/90': variant === 'destructive',
      'opacity-50 pointer-events-none': disabled,
    }"
    :disabled="disabled"
    @click="handleClick"
  >
    {{ label }}
  </button>
</template>
