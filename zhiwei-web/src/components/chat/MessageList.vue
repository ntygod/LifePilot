<script setup lang="ts">
import { computed } from 'vue'
import type { A2uiComponent, Message, ReasoningEvent, ReactStepDto, PermissionApprovalRequest, SourceSummary, ToolRecoveryAction } from '@/types'
import MessageBubble from './MessageBubble.vue'
import { motion } from 'motion-v'

const MotionDiv = motion.div
interface SavedKnowledgeMessage {
  knowledgeBaseId: string
  knowledgeBaseName: string
}

interface ArtifactKnowledgeSavedPayload {
  artifactId: string
  fileName: string
  knowledgeBaseId: string
  knowledgeBaseName: string
}

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
  /** 流式阶段中已收到的文件产物引用 — 由 SSE artifact-ref 事件累积。 */
  streamingArtifactRefs?: import('@/api/artifacts').ArtifactRefPayload[]
  /** 文本搜索关键字，用于高亮匹配内容。 */
  query?: string
  /** 从记忆来源或外部入口跳转过来的目标轮次。 */
  focusedTurnId?: string | null
  /** 从记忆来源或外部入口跳转过来的目标消息条目。 */
  focusedEntryId?: string | null
  /** 当前会话所属项目，用于把消息内的记忆入口限定到项目上下文。 */
  projectId?: string | null
  /** 能力修复后返回当前对话所需的会话 ID。 */
  recoveryReturnSessionId?: string | null
  /** 当前对话里明确的产物沉淀目标资料库；为空时产物卡片不显示“存资料”。 */
  artifactKnowledgeBaseId?: string | null
  artifactKnowledgeBaseName?: string | null
  persistArtifactKnowledgeSettlement?: (message: Message, payload: ArtifactKnowledgeSavedPayload) => Promise<void> | void
  savingKnowledgeMessageId?: string | null
  savedKnowledgeMessages?: Record<string, SavedKnowledgeMessage>
  saveKnowledgeErrors?: Record<string, string>
}>()

const emit = defineEmits<{
  (e: 'retry', message: Message): void
  (e: 'edit', message: Message, newContent: string): void
  (e: 'like', message: Message): void
  (e: 'dislike', message: Message, feedback?: string): void
  (e: 'fork', message: Message): void
  (e: 'regenerate', message: Message): void
  (e: 'resume', message: Message, action?: ToolRecoveryAction): void
  (e: 'restart', message: Message, action?: ToolRecoveryAction): void
  (e: 'copy', content: string, success: boolean): void
  (e: 'remember', message: Message): void
  (e: 'save-knowledge', message: Message): void
  (e: 'save-artifact-knowledge', message: Message, payload: ArtifactKnowledgeSavedPayload): void
  (e: 'follow-up', prompt: string): void
  (e: 'show-trace', messageId: string): void
  (e: 'inspect-memory', source: SourceSummary): void
  (e: 'permission-approval-resolve', requestId: string, resolution: 'approved' | 'rejected' | 'expired', subjectType?: string): void
}>()

function hasStructuredApprovalPayload(message: Message) {
  return !!(
    message.permissionApprovals
    || message.permissionApprovalLogs?.length
    || message.permissionApprovalResolutions
  )
}

function findAssistantIndexByTurnId(messages: Message[], turnId?: string) {
  if (!turnId) {
    return -1
  }
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const item = messages[index]
    if (item.role === 'assistant' && item.turnId === turnId) {
      return index
    }
  }
  return -1
}

function findPreviousAssistantIndex(messages: Message[], timestamp: number) {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const candidate = messages[index]
    if (candidate.timestamp > timestamp) {
      continue
    }
    if (candidate.role === 'assistant') {
      return index
    }
    if (candidate.role === 'user') {
      break
    }
  }
  return -1
}

/**
 * 合并后的消息列表。
 * `permission-approval` 条目会并入上一条 assistant 消息，避免单独渲染成独立气泡。
 */
const mergedMessages = computed(() => {
  const sorted = [...props.messages].sort((a, b) => a.timestamp - b.timestamp)
  const result: Message[] = sorted
    .filter(msg => !(msg.role === 'permission-approval' && hasStructuredApprovalPayload(msg)))
    .map(msg => (msg.role === 'permission-approval'
      ? {
          ...msg,
          role: 'assistant',
        }
      : { ...msg }))

  for (const msg of sorted) {
    if (!(msg.role === 'permission-approval' && hasStructuredApprovalPayload(msg))) {
      continue
    }

    let targetIndex = findAssistantIndexByTurnId(result, msg.turnId)
    if (targetIndex === -1) {
      targetIndex = findPreviousAssistantIndex(result, msg.timestamp)
    }

    if (targetIndex !== -1) {
      result[targetIndex] = {
        ...result[targetIndex],
        permissionApprovals: {
          ...(result[targetIndex].permissionApprovals ?? {}),
          ...(msg.permissionApprovals ?? {}),
        },
        permissionApprovalResolutions: {
          ...(result[targetIndex].permissionApprovalResolutions ?? {}),
          ...(msg.permissionApprovalResolutions ?? {}),
        },
        permissionApprovalLogs: [
          ...(result[targetIndex].permissionApprovalLogs ?? []),
          ...(msg.permissionApprovalLogs ?? []),
        ],
      }
      continue
    }

    const standaloneApproval: Message = {
      ...msg,
      role: 'assistant',
      content: '',
    }
    const insertIndex = result.findIndex(item => item.timestamp > msg.timestamp)
    if (insertIndex === -1) {
      result.push(standaloneApproval)
    } else {
      result.splice(insertIndex, 0, standaloneApproval)
    }
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

function getMotionInitial(message: Message) {
  if (message.role === 'assistant') {
    return { y: 16, opacity: 0, scale: 0.995 }
  }
  // 用户消息：从右下快速弹入
  return { y: 24, x: 20, opacity: 0, scale: 0.94 }
}

function getMotionTransition(message: Message) {
  if (message.role === 'assistant') {
    return { duration: 0.32, ease: 'easeOut' as const }
  }
  // 用户消息：更快的弹入动画
  return { duration: 0.2, ease: 'easeOut' as const }
}

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

function isFocusedMessage(message: Message) {
  const entryId = props.focusedEntryId?.trim()
  if (entryId && message.id === entryId) return true

  const turnId = props.focusedTurnId?.trim()
  return Boolean(turnId && message.turnId === turnId)
}

function savedKnowledgeFor(message: Message): SavedKnowledgeMessage | null {
  const saved = props.savedKnowledgeMessages?.[message.id]
    ?? message.knowledgeSettlements?.find(settlement => settlement.knowledgeBaseId?.trim())
  const targetId = props.artifactKnowledgeBaseId?.trim()
  if (!saved || !targetId || saved.knowledgeBaseId !== targetId) {
    return null
  }
  return {
    knowledgeBaseId: saved.knowledgeBaseId,
    knowledgeBaseName: saved.knowledgeBaseName,
  }
}

function forwardArtifactKnowledgeSaved(message: Message, payload: ArtifactKnowledgeSavedPayload) {
  emit('save-artifact-knowledge', message, payload)
}
</script>

<template>
  <div class="flex flex-col gap-2xl">
    <template v-for="(msg, index) in mergedMessages" :key="msg.id">
      <div
        v-if="index === 0 || getDateLabel(msg.timestamp) !== getDateLabel(mergedMessages[index - 1]?.timestamp)"
        class="my-6 flex items-center justify-center text-xs text-muted-foreground"
      >
        <span class="rounded-full bg-muted/70 px-3 py-1 text-xs font-medium">
          {{ getDateLabel(msg.timestamp) }}
        </span>
      </div>

      <MotionDiv
        :initial="getMotionInitial(msg)"
        :animate="{ y: 0, x: 0, opacity: 1, scale: 1 }"
        :transition="getMotionTransition(msg)"
        class="message-list__item w-full"
        :class="{ 'message-list__item--focused': isFocusedMessage(msg) }"
        :data-entry-id="msg.id"
        :data-turn-id="msg.turnId || undefined"
      >
        <MessageBubble
          :message="{
            ...msg,
            artifactRefs: (isStreaming && index === mergedMessages.length - 1 && msg.role === 'assistant')
              ? [...(msg.artifactRefs ?? []), ...((streamingArtifactRefs ?? []).filter(r => !msg.artifactRefs?.some(x => x.artifactId === r.artifactId)))]
              : msg.artifactRefs,
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
          :project-id="projectId"
          :recovery-return-session-id="recoveryReturnSessionId"
          :artifact-knowledge-base-id="artifactKnowledgeBaseId"
          :artifact-knowledge-base-name="artifactKnowledgeBaseName"
          :persist-artifact-knowledge-settlement="persistArtifactKnowledgeSettlement"
          :saving-to-knowledge="savingKnowledgeMessageId === msg.id"
          :saved-knowledge-base-id="savedKnowledgeFor(msg)?.knowledgeBaseId ?? null"
          :saved-knowledge-base-name="savedKnowledgeFor(msg)?.knowledgeBaseName ?? null"
          :save-knowledge-error="saveKnowledgeErrors?.[msg.id] ?? null"
          @retry="(m: Message) => emit('retry', m)"
          @edit="(m: Message, c: string) => emit('edit', m, c)"
          @like="(m: Message) => emit('like', m)"
          @dislike="(m: Message, f?: string) => emit('dislike', m, f)"
          @fork="(m: Message) => emit('fork', m)"
          @regenerate="(m: Message) => emit('regenerate', m)"
          @resume="(m: Message, action?: ToolRecoveryAction) => emit('resume', m, action)"
          @restart="(m: Message, action?: ToolRecoveryAction) => emit('restart', m, action)"
          @copy="(c: string, success: boolean) => emit('copy', c, success)"
          @remember="(m: Message) => emit('remember', m)"
          @save-knowledge="(m: Message) => emit('save-knowledge', m)"
          @save-artifact-knowledge="forwardArtifactKnowledgeSaved"
          @follow-up="(prompt: string) => emit('follow-up', prompt)"
          @show-trace="(id: string) => emit('show-trace', id)"
          @inspect-memory="(source: SourceSummary) => emit('inspect-memory', source)"
          @permission-approval-resolve="(requestId: string, r: 'approved' | 'rejected' | 'expired', subjectType?: string) => emit('permission-approval-resolve', requestId, r, subjectType)"
        />
      </MotionDiv>
    </template>

    <MotionDiv
      v-if="isStreaming && (mergedMessages.length === 0 || mergedMessages[mergedMessages.length - 1]?.role === 'user')"
      :initial="{ y: 18, x: -10, opacity: 0, scale: 0.992 }"
      :animate="{ y: 0, x: 0, opacity: 1, scale: 1 }"
      :transition="{ duration: 0.34, ease: 'easeOut' }"
    >
      <MessageBubble
        :message="{ id: 'streaming', role: 'assistant', content: '', timestamp: Date.now(), artifactRefs: streamingArtifactRefs }"
        :streaming="true"
        :streaming-content="streamingContent"
        :streaming-reasoning-events="streamingReasoningEvents"
        :streaming-react-steps="streamingReactSteps"
        :streaming-a2ui-components="streamingA2uiComponents"
        :streaming-permission-approvals="streamingPermissionApprovals"
        :streaming-permission-approval-resolutions="streamingPermissionApprovalResolutions"
        :project-id="projectId"
        :recovery-return-session-id="recoveryReturnSessionId"
        :artifact-knowledge-base-id="null"
        :artifact-knowledge-base-name="null"
        :saving-to-knowledge="savingKnowledgeMessageId === 'streaming'"
        @follow-up="(prompt: string) => emit('follow-up', prompt)"
        @show-trace="(id: string) => emit('show-trace', id)"
        @inspect-memory="(source: SourceSummary) => emit('inspect-memory', source)"
        @permission-approval-resolve="(requestId: string, r: 'approved' | 'rejected' | 'expired', subjectType?: string) => emit('permission-approval-resolve', requestId, r, subjectType)"
      />
    </MotionDiv>
  </div>
</template>

<style scoped>
.message-list__item {
  border-radius: 12px;
  scroll-margin: 96px;
  transition:
    background-color 220ms ease,
    box-shadow 220ms ease;
}

.message-list__item--focused {
  animation: message-source-focus 2200ms ease-out both;
  box-shadow: 0 0 0 1px hsl(from var(--primary) h s l / 0.22);
  background: hsl(from var(--primary) h s l / 0.04);
}

@keyframes message-source-focus {
  0% {
    background: hsl(from var(--primary) h s l / 0.12);
    box-shadow: 0 0 0 1px hsl(from var(--primary) h s l / 0.34);
  }
  100% {
    background: hsl(from var(--primary) h s l / 0.04);
    box-shadow: 0 0 0 1px hsl(from var(--primary) h s l / 0.22);
  }
}

@media (prefers-reduced-motion: reduce) {
  .message-list__item--focused {
    animation: none;
  }
}
</style>
