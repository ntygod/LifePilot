<script setup lang="ts">
import { ref } from 'vue'
import type { A2uiSignal } from '@/types'
import { useA2uiSignal } from '@/composables/useA2uiSignal'
import { useChatStore } from '@/stores/chat'

const props = defineProps<{
  label?: string
  value?: string
  signal?: A2uiSignal
}>()

const { emitSignal } = useA2uiSignal()
const chatStore = useChatStore()
const dateValue = ref(props.value ?? '')

function handleChange() {
  if (props.signal && chatStore.activeSessionId) {
    emitSignal(
      { name: props.signal.name, payload: { ...props.signal.payload, value: dateValue.value } },
      chatStore.activeSessionId
    )
  }
}
</script>

<template>
  <div class="space-y-1.5">
    <label v-if="label" class="text-sm font-medium leading-none">{{ label }}</label>
    <input
      v-model="dateValue"
      type="date"
      class="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
      @change="handleChange"
    />
  </div>
</template>
