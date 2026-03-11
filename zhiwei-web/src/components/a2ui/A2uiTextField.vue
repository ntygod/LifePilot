<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { LoaderCircle } from 'lucide-vue-next'
import type { A2uiSignal } from '@/types'
import { useA2uiSignal } from '@/composables/useA2uiSignal'
import { useChatStore } from '@/stores/chat'
import { Input } from '@/components/ui/input'

const props = defineProps<{
  label?: string
  placeholder?: string
  value?: string
  signal?: A2uiSignal
  componentId?: string
  messageId?: string
  traceId?: string
  streaming?: boolean
}>()

const { emitSignal, getSignalState } = useA2uiSignal()
const chatStore = useChatStore()
const inputValue = ref(props.value ?? '')

watch(() => props.value, value => {
  inputValue.value = value ?? ''
})

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

async function handleChange() {
  if (!props.signal || !chatStore.activeSessionId) return
  await emitSignal(
    { name: props.signal.name, payload: { ...props.signal.payload, value: inputValue.value } },
    chatStore.activeSessionId,
    signalContext.value,
  )
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter') {
    event.preventDefault()
    void handleChange()
  }
}
</script>

<template>
  <div class="space-y-1.5">
    <div class="flex items-center justify-between gap-3">
      <label v-if="label" class="text-sm font-medium leading-none text-foreground">{{ label }}</label>
      <span
        v-if="signalState?.status === 'success'"
        class="text-[11px] font-medium text-primary"
      >
        已更新
      </span>
    </div>
    <div class="relative">
      <Input
      v-model="inputValue"
        type="text"
        :placeholder="placeholder"
        class="pr-10"
        :disabled="isSending"
        @change="handleChange"
        @keydown="handleKeydown"
      />
      <LoaderCircle
        v-if="isSending"
        class="pointer-events-none absolute right-3 top-1/2 size-4 -translate-y-1/2 animate-spin text-muted-foreground"
      />
    </div>
    <p
      v-if="signalState?.status === 'error'"
      class="text-xs text-destructive"
    >
      {{ signalState.error || '发送失败，请重试' }}
    </p>
  </div>
</template>
