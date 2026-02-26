<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{
  disabled?: boolean
}>()

const emit = defineEmits<{
  send: [content: string]
}>()

const input = ref('')

function handleKeydown(e: KeyboardEvent) {
  // Enter 发送，Shift+Enter 换行
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}

function submit() {
  const content = input.value.trim()
  if (!content || props.disabled) return
  emit('send', content)
  input.value = ''
}
</script>

<template>
  <div class="border-t border-border p-4 bg-card">
    <div class="flex gap-2 items-end max-w-3xl mx-auto">
      <textarea
        v-model="input"
        :disabled="disabled"
        placeholder="输入消息..."
        rows="1"
        class="flex-1 resize-none rounded-lg border border-input bg-background px-3 py-2 text-sm
               placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring
               disabled:opacity-50 min-h-[40px] max-h-[120px]"
        @keydown="handleKeydown"
      />
      <button
        :disabled="disabled || !input.trim()"
        class="rounded-lg bg-primary px-4 py-2 text-sm text-primary-foreground
               hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed
               transition-colors shrink-0"
        @click="submit"
      >
        发送
      </button>
    </div>
  </div>
</template>
