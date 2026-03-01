<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps<{
  disabled?: boolean
}>()

const emit = defineEmits<{
  send: [content: string]
}>()

const input = ref('')
// 基础长度限制：主要防止一次性粘贴超长内容导致请求失败
const maxLength = 4000
const inputLength = computed(() => input.value.length)

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
  <div class="border-t border-border p-3 bg-card">
    <div class="max-w-3xl mx-auto flex flex-col gap-1">
      <div class="flex gap-2 items-end">
        <textarea
          v-model="input"
          :disabled="disabled"
          :maxlength="maxLength"
          placeholder="输入你的问题，或粘贴一段内容让 AI 帮你分析…"
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
      <div class="flex justify-between text-[11px] text-muted-foreground px-1">
        <span>按 Enter 发送，Shift+Enter 换行</span>
        <span>{{ inputLength }} / {{ maxLength }} 字符</span>
        <span v-if="disabled">正在生成回答，稍候即可继续输入</span>
      </div>
    </div>
  </div>
</template>
