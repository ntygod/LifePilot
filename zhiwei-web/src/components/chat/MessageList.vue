<script setup lang="ts">
import { computed } from 'vue'
import type { A2uiComponent, Message, ReasoningEvent, ReactStepDto, ToolConfirmationRequest } from '@/types'
import MessageBubble from './MessageBubble.vue'
import { useChatStore } from '@/stores/chat'
import { motion } from 'motion-v'

const MotionDiv = motion.div

const props = defineProps<{
  messages: Message[]
  isStreaming?: boolean
  streamingContent?: string
  /** 流式推理中的实时推理事件（可选） */
  streamingReasoningEvents?: ReasoningEvent[]
  /** 流式推理中的实时 ReAct 步骤（可选） */
  streamingReactSteps?: ReactStepDto[]
  /** 流式阶段中的 A2UI 组件树（可选） */
  streamingA2uiComponents?: A2uiComponent[]
  /** 流式阶段的工具确认请求（从 useChat 传入） */
  streamingToolConfirmation?: ToolConfirmationRequest | null
  /** 流式阶段的工具确认解决结果 */
  streamingToolConfirmationResolution?: 'approved' | 'rejected' | 'expired' | null
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
  (e: 'tool-confirm-resolve', resolution: 'approved' | 'rejected' | 'expired'): void
}>()

const chatStore = useChatStore()

/**
 * 合并后的消息列表：将 tool-confirmation 消息合并到前一条 assistant 消息中，
 * 使确认卡片内嵌在同一个 assistant 气泡内部。
 */
const mergedMessages = computed(() => {
  const sorted = [...props.messages].sort((a, b) => a.timestamp - b.timestamp)
  const result: Message[] = []

  for (const msg of sorted) {
    if (msg.role === 'tool-confirmation' && msg.toolConfirmation) {
      // 找到前一条 assistant 消息，将确认数据合并进去
      for (let i = result.length - 1; i >= 0; i--) {
        if (result[i].role === 'assistant') {
          result[i] = {
            ...result[i],
            toolConfirmation: msg.toolConfirmation,
            toolConfirmationResolution: msg.toolConfirmationResolution,
          }
          break
        }
      }
      // tool-confirmation 消息本身不再作为独立消息渲染
      continue
    }
    result.push({ ...msg })
  }

  return result
})

// 最后一条 assistant 消息的 ID，用于控制"重新生成"按钮仅在最后一条 AI 消息上显示
const lastAssistantId = computed(() => {
  for (let i = mergedMessages.value.length - 1; i >= 0; i--) {
    if (mergedMessages.value[i].role === 'assistant') return mergedMessages.value[i].id
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

/** 工具确认气泡解决回调：更新消息状态并通知父组件 */
function handleToolConfirmationResolve(msg: Message, resolution: 'approved' | 'rejected' | 'expired') {
  chatStore.updateMessage(msg.id, { toolConfirmationResolution: resolution })
  emit('tool-confirm-resolve', resolution)
}
</script>

<template>
  <div class="flex flex-col">
    <template v-for="(msg, index) in mergedMessages" :key="msg.id">
      <!-- 日期分组标签 -->
      <div
        v-if="index === 0 || getDateLabel(msg.timestamp) !== getDateLabel(mergedMessages[index - 1]?.timestamp)"
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
        class="w-full"
      >
        <MessageBubble
          :message="{
            ...msg,
            highlightedContent: props.query ? highlight(msg.content) : undefined
          } as Message"
          :streaming="isStreaming && index === mergedMessages.length - 1 && msg.role === 'assistant'"
          :streaming-content="streamingContent"
          :streaming-reasoning-events="(isStreaming && index === mergedMessages.length - 1 && msg.role === 'assistant') ? streamingReasoningEvents : undefined"
          :streaming-react-steps="(isStreaming && index === mergedMessages.length - 1 && msg.role === 'assistant') ? streamingReactSteps : undefined"
          :streaming-a2ui-components="(isStreaming && index === mergedMessages.length - 1 && msg.role === 'assistant') ? streamingA2uiComponents : undefined"
          :streaming-tool-confirmation="(isStreaming && index === mergedMessages.length - 1 && msg.role === 'assistant') ? streamingToolConfirmation ?? undefined : undefined"
          :streaming-tool-confirmation-resolution="(isStreaming && index === mergedMessages.length - 1 && msg.role === 'assistant') ? streamingToolConfirmationResolution ?? undefined : undefined"
          :is-last-assistant="msg.id === lastAssistantId"
          @retry="(m: Message) => emit('retry', m)"
          @like="(m: Message) => emit('like', m)"
          @dislike="(m: Message, f?: string) => emit('dislike', m, f)"
          @fork="(m: Message) => emit('fork', m)"
          @regenerate="(m: Message) => emit('regenerate', m)"
          @copy="(c: string) => emit('copy', c)"
          @tool-confirm-resolve="(r: 'approved' | 'rejected' | 'expired') => handleToolConfirmationResolve(msg, r)"
        />
      </MotionDiv>
    </template>

    <!-- 流式进行中但尚未有 assistant 消息时，显示占位 -->
    <MotionDiv
      v-if="isStreaming && (mergedMessages.length === 0 || mergedMessages[mergedMessages.length - 1]?.role === 'user')"
      :initial="{ y: 16, opacity: 0 }"
      :animate="{ y: 0, opacity: 1 }"
      :transition="{ duration: 0.3, ease: 'easeOut' }"
    >
      <MessageBubble
        :message="{ id: 'streaming', role: 'assistant', content: '', timestamp: Date.now() }"
        :streaming="true"
        :streaming-content="streamingContent"
        :streaming-reasoning-events="streamingReasoningEvents"
        :streaming-react-steps="streamingReactSteps"
        :streaming-a2ui-components="streamingA2uiComponents"
        :streaming-tool-confirmation="streamingToolConfirmation ?? undefined"
        :streaming-tool-confirmation-resolution="streamingToolConfirmationResolution ?? undefined"
        @tool-confirm-resolve="(r: 'approved' | 'rejected' | 'expired') => emit('tool-confirm-resolve', r)"
      />
    </MotionDiv>
  </div>
</template>
