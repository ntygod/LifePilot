<script setup lang="ts">
import { computed } from 'vue'
import { LoaderCircle } from 'lucide-vue-next'
import type { A2uiSignal } from '@/types'
import { useA2uiSignal } from '@/composables/useA2uiSignal'
import { useChatStore } from '@/stores/chat'
import { Button } from '@/components/ui/button'

const props = withDefaults(defineProps<{
  label?: string
  variant?: 'default' | 'outline' | 'ghost' | 'destructive'
  disabled?: boolean
  signal?: A2uiSignal
  componentId?: string
  entryId?: string
  traceId?: string
  streaming?: boolean
}>(), {
  variant: 'default',
})

const { emitSignal, getSignalState } = useA2uiSignal()
const chatStore = useChatStore()

const signalContext = computed(() => ({
  componentId: props.componentId,
  entryId: props.entryId,
  traceId: props.traceId,
  signalName: props.signal?.name,
}))

const signalState = computed(() => (
  props.signal
    ? getSignalState(signalContext.value)
    : undefined
))

const isSending = computed(() => signalState.value?.status === 'sending')
const helperText = computed(() => {
  if (signalState.value?.status === 'error') return signalState.value.error || '发送失败，请重试'
  if (signalState.value?.status === 'success') return '已发送'
  return ''
})

async function handleClick() {
  if (props.disabled || props.streaming || isSending.value || !props.signal || !chatStore.activeSessionId) return
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
  <div class="inline-flex min-w-0 flex-col items-start gap-2">
    <Button
      type="button"
      :variant="variant"
      :disabled="disabled || streaming || isSending"
      class="min-w-[5.5rem]"
      :aria-busy="isSending"
      @click="handleClick"
      @keydown="handleKeydown"
    >
      <LoaderCircle v-if="isSending" class="size-4 animate-spin" />
      {{ isSending ? '提交中' : (label || '继续') }}
    </Button>

    <p
      v-if="helperText"
      class="text-xs"
      :class="signalState?.status === 'error' ? 'text-destructive' : 'text-primary'"
    >
      {{ helperText }}
    </p>
  </div>
</template>
