<script setup lang="ts">
import { computed } from 'vue'
import { ChevronRight, LoaderCircle } from 'lucide-vue-next'
import type { A2uiSignal } from '@/types'
import { useA2uiSignal } from '@/composables/useA2uiSignal'
import { useChatStore } from '@/stores/chat'

const props = defineProps<{
  text?: string
  signal?: A2uiSignal
  componentId?: string
  messageId?: string
  traceId?: string
  streaming?: boolean
}>()

const { emitSignal, getSignalState } = useA2uiSignal()
const chatStore = useChatStore()

const signalContext = computed(() => ({
  componentId: props.componentId,
  messageId: props.messageId,
  traceId: props.traceId,
  signalName: props.signal?.name,
}))

const signalState = computed(() => (
  props.signal
    ? getSignalState(signalContext.value)
    : undefined
))

const isSending = computed(() => signalState.value?.status === 'sending')

async function handleClick() {
  if (props.streaming || isSending.value || !props.signal || !chatStore.activeSessionId) return
  await emitSignal(props.signal, chatStore.activeSessionId, signalContext.value)
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    void handleClick()
  }
}
</script>

<template>
  <li
    class="flex items-center justify-between gap-3 rounded-[calc(var(--radius)+4px)] px-3 py-2 text-sm transition-colors"
    :class="signal
      ? 'cursor-pointer border border-border/65 bg-background/72 hover:border-primary/18 hover:bg-accent/60'
      : 'border border-transparent bg-transparent'"
    :tabindex="signal ? 0 : undefined"
    @click="handleClick"
    @keydown="handleKeydown"
  >
    <span class="min-w-0 flex-1 text-foreground">{{ text }}</span>
    <div class="flex shrink-0 items-center gap-2 text-muted-foreground">
      <slot />
      <LoaderCircle v-if="isSending" class="size-4 animate-spin" />
      <ChevronRight v-else-if="signal" class="size-4" />
    </div>
  </li>
</template>
