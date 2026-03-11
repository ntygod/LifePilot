<script setup lang="ts">
import { computed } from 'vue'
import { LoaderCircle } from 'lucide-vue-next'
import type { A2uiSignal } from '@/types'
import { useA2uiSignal } from '@/composables/useA2uiSignal'
import { useChatStore } from '@/stores/chat'

const props = defineProps<{
  label?: string
  selected?: boolean
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
const hasError = computed(() => signalState.value?.status === 'error')

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
  <button
    type="button"
    class="inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium transition-all duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed"
    :class="[
      selected
        ? 'border-primary/24 bg-primary/10 text-primary'
        : 'border-border/70 bg-background/75 text-muted-foreground hover:border-primary/18 hover:text-foreground',
      hasError && 'border-destructive/22 bg-destructive/6 text-destructive',
      isSending && 'cursor-wait',
    ]"
    :disabled="streaming || !signal || isSending"
    :aria-busy="isSending"
    @click="handleClick"
    @keydown="handleKeydown"
  >
    <LoaderCircle v-if="isSending" class="size-3.5 animate-spin" />
    <span>{{ label }}</span>
  </button>
</template>
