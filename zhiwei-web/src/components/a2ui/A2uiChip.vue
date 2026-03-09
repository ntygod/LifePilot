<script setup lang="ts">
import type { A2uiSignal } from '@/types'
import { useA2uiSignal } from '@/composables/useA2uiSignal'
import { useChatStore } from '@/stores/chat'

const props = defineProps<{
  label?: string
  selected?: boolean
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
  <span
    class="inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium transition-colors"
    :class="selected
      ? 'bg-primary text-primary-foreground'
      : 'bg-secondary text-secondary-foreground hover:bg-secondary/80'"
    :role="signal ? 'button' : undefined"
    :tabindex="signal ? 0 : undefined"
    @click="handleClick"
    @keydown.enter="handleClick"
  >
    {{ label }}
  </span>
</template>
