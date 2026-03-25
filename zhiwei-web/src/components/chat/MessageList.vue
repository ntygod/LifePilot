<script setup lang="ts">
import { computed } from 'vue'
import type { A2uiComponent, Message, ReasoningEvent, ReactStepDto, PermissionApprovalRequest } from '@/types'
import MessageBubble from './MessageBubble.vue'
import { motion } from 'motion-v'

const MotionDiv = motion.div

const props = defineProps<{
  messages: Message[]
  isStreaming?: boolean
  streamingContent?: string
  /** 流式推理中的实时推理事件。 */
  streamingReasoningEvents?: ReasoningEvent[]
  /** 流式推理中的实时 ReAct 步骤。 */
  streamingReactSteps?: ReactStepDto[]
  /** 流式阶段中的 A2UI 组件树。 */
  streamingA2uiComponents?: A2uiComponent[]
  /** 流式阶段中的权限审批请求。 */
  streamingPermissionApprovals?: Record<string, PermissionApprovalRequest>
  /** 流式阶段中的权限审批结果。 */
  streamingPermissionApprovalResolutions?: Record<string, 'approved' | 'rejected' | 'expired'>
  /** 文本搜索关键字，用于高亮匹配内容。 */
  query?: string
}>()

const emit = defineEmits<{
  (e: 'retry', message: Message): void
  (e: 'like', message: Message): void
  (e: 'dislike', message: Message, feedback?: string): void
  (e: 'fork', message: Message): void
  (e: 'regenerate', message: Message): void
  (e: 'resume', message: Message): void
  (e: 'restart', message: Message): void
  (e: 'copy', content: string): void
  (e: 'permission-approval-resolve', requestId: string, resolution: 'approved' | 'rejected' | 'expired'): void
}>()

/**
 * 合并后的消息列表。
 * `permission-approval` 条目会并入上一条 assistant 消息，避免单独渲染成独立气泡。
 */
const mergedMessages = computed(() => {
  const sorted = [...props.messages].sort((a, b) => a.timestamp - b.timestamp)
  const result: Message[] = []

  for (const msg of sorted) {
    if (msg.role === 'permission-approval' && msg.permissionApprovals) {
      const assistantIndex = [...result].reverse().findIndex(item =>
        item.role === 'assistant' && item.turnId && item.turnId === msg.turnId,
      )

      if (assistantIndex !== -1) {
        const targetIndex = result.length - 1 - assistantIndex
        result[targetIndex] = {
          ...result[targetIndex],
          permissionApprovals: {
            ...(result[targetIndex].permissionApprovals ?? {}),
            ...msg.permissionApprovals,
          },
          permissionApprovalResolutions: {
            ...(result[targetIndex].permissionApprovalResolutions ?? {}),
            ...(msg.permissionApprovalResolutions ?? {}),
          },
        }
        continue
      }
    }

    result.push({ ...msg })
  }

  return result
})

/** 最后一条 assistant 消息的 ID，用于控制“重新生成”按钮只显示在最后一条回复上。 */
const lastAssistantId = computed(() => {
  for (let i = mergedMessages.value.length - 1; i >= 0; i--) {
    if (mergedMessages.value[i].role === 'assistant') return mergedMessages.value[i].id
  }
  return null
})

/** 简单日期标签：今天 / 昨天 / 更早。 */
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
    <template v-for="(msg, index) in mergedMessages" :key="msg.id">
      <div
        v-if="index === 0 || getDateLabel(msg.timestamp) !== getDateLabel(mergedMessages[index - 1]?.timestamp)"
        class="my-4 flex items-center justify-center text-xs text-muted-foreground"
      >
        <span class="rounded-full bg-muted/70 px-3 py-1 text-xs font-medium">
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
          :streaming-permission-approvals="(isStreaming && index === mergedMessages.length - 1 && msg.role === 'assistant') ? streamingPermissionApprovals : undefined"
          :streaming-permission-approval-resolutions="(isStreaming && index === mergedMessages.length - 1 && msg.role === 'assistant') ? streamingPermissionApprovalResolutions : undefined"
          :is-last-assistant="msg.id === lastAssistantId"
          @retry="(m: Message) => emit('retry', m)"
          @like="(m: Message) => emit('like', m)"
          @dislike="(m: Message, f?: string) => emit('dislike', m, f)"
          @fork="(m: Message) => emit('fork', m)"
          @regenerate="(m: Message) => emit('regenerate', m)"
          @resume="(m: Message) => emit('resume', m)"
          @restart="(m: Message) => emit('restart', m)"
          @copy="(c: string) => emit('copy', c)"
          @permission-approval-resolve="(requestId: string, r: 'approved' | 'rejected' | 'expired') => emit('permission-approval-resolve', requestId, r)"
        />
      </MotionDiv>
    </template>

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
        :streaming-permission-approvals="streamingPermissionApprovals"
        :streaming-permission-approval-resolutions="streamingPermissionApprovalResolutions"
        @permission-approval-resolve="(requestId: string, r: 'approved' | 'rejected' | 'expired') => emit('permission-approval-resolve', requestId, r)"
      />
    </MotionDiv>
  </div>
</template>
