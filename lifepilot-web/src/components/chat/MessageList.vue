<script setup lang="ts">
import { computed } from 'vue'
import type { Message } from '@/types'
import MessageBubble from './MessageBubble.vue'
import { motion } from 'motion-v'

const MotionDiv = motion.div

const props = defineProps<{
  messages: Message[]
  isStreaming?: boolean
  streamingContent?: string
  /** 文本搜索关键字（可选），用于高亮匹配内容 */
  query?: string
}>()

const emit = defineEmits<{
  (e: 'retry', message: Message): void
  (e: 'like', message: Message): void
  (e: 'dislike', message: Message, feedback?: string): void
  (e: 'fork', message: Message): void
  (e: 'regenerate', message: Message): void
  (e: 'copy', content: string): void
}>()

// 按 timestamp 升序排列
const sortedMessages = computed(() =>
  [...props.messages].sort((a, b) => a.timestamp - b.timestamp)
)

// 最后一条 assistant 消息的 ID，用于控制"重新生成"按钮仅在最后一条 AI 消息上显示
const lastAssistantId = computed(() => {
  for (let i = sortedMessages.value.length - 1; i >= 0; i--) {
    if (sortedMessages.value[i].role === 'assistant') return sortedMessages.value[i].id
  }
  return null
})

// 简单的日期标签：今天 / 昨天 / 更早
function getDateLabel(timestamp: number): string {
  const date = new Date(timestamp)
  const today = new Date()
  const diffMs = today.setHours(0, 0, 0, 0) - new Date(date.setHours(0, 0, 0, 0)).getTime()
  const diffDays = Math.round(diffMs / (24 * 60 * 60 * 1000))

  if (diffDays === 0) return '今天'
  if (diffDays === 1) return '昨天'
  return date.toLocaleDateString()
}

function highlight(text: string): string {
  if (!props.query) return text
  const escaped = props.query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const reg = new RegExp(escaped, 'gi')
  return text.replace(reg, match => `<mark class="bg-yellow-200/70 dark:bg-yellow-500/40">${match}</mark>`)
}
</script>

<template>
  <div class="flex flex-col">
    <template v-for="(msg, index) in sortedMessages" :key="msg.id">
      <!-- 日期分组标签 -->
      <div
        v-if="index === 0 || getDateLabel(msg.timestamp) !== getDateLabel(sortedMessages[index - 1]?.timestamp)"
        class="my-4 flex items-center justify-center text-xs text-muted-foreground"
      >
        <span class="px-3 py-1 rounded-full bg-muted/70 text-xs font-medium">
          {{ getDateLabel(msg.timestamp) }}
        </span>
      </div>

      <MotionDiv
        :initial="{ y: 16, opacity: 0 }"
        :animate="{ y: 0, opacity: 1 }"
        :transition="{ duration: 0.3, ease: 'easeOut' }"
      >
        <MessageBubble
          :message="{
            ...msg,
            highlightedContent: props.query ? highlight(msg.content) : undefined
          } as Message"
          :streaming="isStreaming && index === sortedMessages.length - 1 && msg.role === 'assistant'"
          :streaming-content="streamingContent"
          :is-last-assistant="msg.id === lastAssistantId"
          @retry="(m: Message) => emit('retry', m)"
          @like="(m: Message) => emit('like', m)"
          @dislike="(m: Message, f?: string) => emit('dislike', m, f)"
          @fork="(m: Message) => emit('fork', m)"
          @regenerate="(m: Message) => emit('regenerate', m)"
          @copy="(c: string) => emit('copy', c)"
        />
      </MotionDiv>
    </template>

    <!-- 流式进行中但尚未有 assistant 消息时，显示占位 -->
    <MotionDiv
      v-if="isStreaming && (sortedMessages.length === 0 || sortedMessages[sortedMessages.length - 1]?.role === 'user')"
      :initial="{ y: 16, opacity: 0 }"
      :animate="{ y: 0, opacity: 1 }"
      :transition="{ duration: 0.3, ease: 'easeOut' }"
    >
      <MessageBubble
        :message="{ id: 'streaming', role: 'assistant', content: '', timestamp: Date.now() }"
        :streaming="true"
        :streaming-content="streamingContent"
      />
    </MotionDiv>
  </div>
</template>
