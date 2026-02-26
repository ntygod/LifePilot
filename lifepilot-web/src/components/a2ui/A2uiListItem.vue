<script setup lang="ts">
import type { A2uiSignal } from '@/types'
import { useA2uiSignal } from '@/composables/useA2uiSignal'
import { useChatStore } from '@/stores/chat'

const props = defineProps<{
  text?: string
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
  <li
    class="flex items-center gap-2 px-2 py-1.5 rounded-md text-sm"
    :class="signal ? 'cursor-pointer hover:bg-accent' : ''"
    @click="handleClick"
  >
    <span>{{ text }}</span>
    <slot />
  </li>
</template>
