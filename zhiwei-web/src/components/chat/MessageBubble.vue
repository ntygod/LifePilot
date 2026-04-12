<script setup lang="ts">
import { computed, ref } from 'vue'
import { Check, Copy, FileText, Mic, Pencil } from 'lucide-vue-next'
import type {
  A2uiComponent,
  Message,
  PermissionApprovalLog,
  PermissionApprovalRequest,
  ReasoningEvent,
  ReactStepDto,
} from '@/types'
import A2uiRenderer from '@/components/a2ui/A2uiRenderer.vue'
import { buildPermissionApprovalLog } from '@/utils/permissionApproval'
import {
  Dialog,
  DialogContent,
} from '@/components/ui/dialog'
import { copyToClipboard } from '@/utils/clipboard'
import { getPreviewContent, shouldCollapse } from '@/utils/messageUtils'
import KbSourceTag from './KbSourceTag.vue'
import MessageActions from './MessageActions.vue'
import MessageError from './MessageError.vue'
import MessageFeedback from './MessageFeedback.vue'
import ThinkingIndicator from './ThinkingIndicator.vue'
import StreamingText from './StreamingText.vue'
import ToolCallCard from './ToolCallCard.vue'
import PermissionApprovalBubble from './PermissionApprovalBubble.vue'

const props = defineProps<{
  message: Message
  streaming?: boolean
  streamingContent?: string
  streamingReasoningEvents?: ReasoningEvent[]
  streamingReactSteps?: ReactStepDto[]
  isLastAssistant?: boolean
  streamingA2uiComponents?: A2uiComponent[]
  streamingPermissionApprovals?: Record<string, PermissionApprovalRequest>
  streamingPermissionApprovalResolutions?: Record<string, 'approved' | 'rejected' | 'expired'>
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
  (e: 'edit', message: Message): void
  (e: 'show-trace', messageId: string): void
  (e: 'permission-approval-resolve', requestId: string, resolution: 'approved' | 'rejected' | 'expired', subjectType?: string): void
}>()

const collapsed = ref(props.message.collapsed ?? shouldCollapse(props.message.content))
const showImagePreview = ref(false)
const userCopied = ref(false)

async function handleUserCopy() {
  if (await copyToClipboard(props.message.content)) {
    userCopied.value = true
    window.setTimeout(() => { userCopied.value = false }, 2000)
  }
}
const previewImageUrl = ref<string | null>(null)

const timeLabel = computed(() => {
  const date = new Date(props.message.timestamp)
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
})

const imageAttachments = computed(() =>
  props.message.attachments?.filter(attachment => attachment.isImage) ?? [],
)

const fileAttachments = computed(() =>
  props.message.attachments?.filter(attachment => !attachment.isImage && !attachment.type?.startsWith('audio/')) ?? [],
)

const audioAttachments = computed(() =>
  props.message.attachments?.filter(attachment => attachment.type?.startsWith('audio/')) ?? [],
)

const kbSources = computed(() =>
  props.message.sources?.filter(source => source.type === 'knowledgeBase') ?? [],
)

const visibleA2uiComponents = computed(() => {
  if (props.streaming) {
    return props.streamingA2uiComponents ?? props.message.a2uiComponents ?? []
  }

  return props.message.a2uiComponents ?? []
})

const a2uiPanelLabel = computed(() => (
  props.streaming
    ? '交互面板正在更新'
    : '工具 / 工作流结果'
))

const userStatusLabel = computed(() => {
  if (props.message.role !== 'user' || !props.message.status) {
    return ''
  }

  if (props.message.status === 'pending') {
    return '发送中'
  }

  if (props.message.status === 'error') {
    return '发送失败'
  }

  return ''
})

const displayContent = computed(() => {
  if (props.streaming) {
    return props.streamingContent ?? ''
  }

  if (props.message.role === 'assistant' && collapsed.value && shouldCollapse(props.message.content)) {
    return getPreviewContent(props.message.content)
  }

  return props.message.content
})

const isCollapsible = computed(() =>
  props.message.role === 'assistant' && !props.streaming && shouldCollapse(props.message.content),
)

const activeReactSteps = computed<ReactStepDto[]>(() => {
  if (props.streaming && props.streamingReactSteps?.length) {
    return props.streamingReactSteps
  }
  return props.message.reactSteps ?? []
})

const activeReasoningEvents = computed<ReasoningEvent[]>(() => {
  if (props.streaming && props.streamingReasoningEvents?.length) {
    return props.streamingReasoningEvents
  }
  return props.message.reasoningEvents ?? []
})

const activePermissionApprovals = computed<Record<string, PermissionApprovalRequest>>(() => ({
  ...(props.message.permissionApprovals ?? {}),
  ...(props.streamingPermissionApprovals ?? {}),
}))

const activePermissionApprovalResolutions = computed<Record<string, 'approved' | 'rejected' | 'expired'>>(() => ({
  ...(props.message.permissionApprovalResolutions ?? {}),
  ...(props.streamingPermissionApprovalResolutions ?? {}),
}))

const pendingApprovals = computed(() =>
  Object.entries(activePermissionApprovals.value)
    .filter(([requestId]) => !activePermissionApprovalResolutions.value[requestId]),
)

const approvalLogs = computed<PermissionApprovalLog[]>(() => {
  const logs = [...(props.message.permissionApprovalLogs ?? [])]

  for (const [requestId, request] of Object.entries(activePermissionApprovals.value)) {
    const resolution = activePermissionApprovalResolutions.value[requestId]
    if (!resolution) {
      continue
    }
    logs.push({
      requestId,
      toolId: request.toolId,
      toolName: request.toolName,
      actionType: request.actionType,
      resolution,
      subjectType: request.recommendedSubjectType ?? undefined,
      reason: null,
      timestamp: request.timestamp,
    })
  }

  return logs
})

const hasNonApprovalAssistantBody = computed(() => (
  !!displayContent.value
  || imageAttachments.value.length > 0
  || fileAttachments.value.length > 0
  || audioAttachments.value.length > 0
  || visibleA2uiComponents.value.length > 0
  || !!props.message.toolsSummary?.length
  || kbSources.value.length > 0
  || activeReasoningEvents.value.length > 0
  || activeReactSteps.value.length > 0
))

const isApprovalOnlyAssistant = computed(() => (
  props.message.role === 'assistant'
  && !hasNonApprovalAssistantBody.value
  && (pendingApprovals.value.length > 0 || approvalLogs.value.length > 0)
))

const assistantBubbleClass = computed(() => {
  if (isApprovalOnlyAssistant.value) {
    return 'w-fit max-w-full rounded-none border-none bg-transparent px-0 py-0 text-foreground shadow-none'
  }

  return [
    'assistant-bubble px-0 py-0 text-foreground',
    props.streaming
      ? 'assistant-bubble-streaming'
      : 'assistant-bubble-idle',
  ].join(' ')
})

const userBubbleClass = computed(() => [
  'user-bubble rounded-[1rem] px-4 py-3 text-foreground',
  props.message.status === 'pending' ? 'user-bubble-pending' : 'group-hover/message:-translate-y-0.5 group-hover/message:shadow-[0_14px_24px_-20px_hsl(var(--shadow-color)/0.26)]',
].join(' '))

function approvalLogTone(log: PermissionApprovalLog) {
  if (log.resolution === 'approved') {
    return 'border-emerald-200/80 bg-emerald-50/90 text-emerald-700'
  }
  if (log.resolution === 'expired') {
    return 'border-amber-200/80 bg-amber-50/90 text-amber-700'
  }
  return 'border-slate-200 bg-slate-50 text-slate-600'
}
</script>

<template>
  <div
    class="group/message w-full"
    :class="message.role === 'user' ? 'flex flex-col items-end' : ''"
  >
    <div
      class="min-w-0 space-y-xs"
      :class="message.role === 'user' ? 'flex flex-col items-end' : ''"
    >

      <div
        v-if="streaming && message.role === 'assistant'"
        class="streaming-status-pill inline-flex w-fit items-center gap-2 rounded-full border border-primary/14 bg-primary/[0.06] px-2.5 py-1 text-[11px] font-medium text-primary"
      >
        <span class="streaming-status-dot" />
        正在生成
      </div>

      <div
        class="relative max-w-full transition-all duration-200"
        :class="[
          message.role === 'user' ? 'w-fit overflow-hidden shadow-sm max-w-[60%]' : 'overflow-visible',
          message.role === 'user' ? userBubbleClass : assistantBubbleClass,
        ]"
      >
        <div class="relative z-[1]">
          <p
            v-if="message.role === 'user'"
            class="whitespace-pre-wrap text-[15px] leading-relaxed"
            v-html="(message as any).highlightedContent ?? message.content"
          />

          <template v-else>
            <div
              v-if="pendingApprovals.length > 0"
              class="mb-2 flex flex-col gap-2"
            >
              <PermissionApprovalBubble
                v-for="[requestId, request] in pendingApprovals"
                :key="requestId"
                :request="request"
                :resolved="!!activePermissionApprovalResolutions[requestId]"
                :resolution="activePermissionApprovalResolutions[requestId]"
                @resolve="(resolution: 'approved' | 'rejected' | 'expired', subjectType?: string) => emit('permission-approval-resolve', requestId, resolution, subjectType)"
              />
            </div>

            <div
              v-if="approvalLogs.length > 0"
              class="mb-2 flex max-w-full flex-wrap gap-1.5"
            >
              <div
                v-for="log in approvalLogs"
                :key="`${log.requestId}-${log.resolution}`"
                class="inline-flex max-w-full items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11px] font-medium shadow-sm"
                :class="approvalLogTone(log)"
              >
                <span
                  class="size-1.5 shrink-0 rounded-full"
                  :class="log.resolution === 'approved'
                    ? 'bg-emerald-500'
                    : (log.resolution === 'expired' ? 'bg-amber-500' : 'bg-slate-400')"
                />
                <span class="truncate">{{ buildPermissionApprovalLog(log) }}</span>
              </div>
            </div>

            <ThinkingIndicator
              v-if="activeReasoningEvents.length > 0 || activeReactSteps.length > 0"
              :reasoning-events="activeReasoningEvents"
              :react-steps="activeReactSteps"
              :streaming="streaming"
              @show-trace="emit('show-trace', message.id)"
            />

            <StreamingText
              v-if="displayContent"
              :content="displayContent"
              :streaming="streaming"
            />

            <button
              v-if="isCollapsible"
              type="button"
              class="mt-2 text-xs text-primary transition-colors hover:underline underline-offset-2"
              @click="collapsed = !collapsed"
            >
              {{ collapsed ? '查看完整回复 ↓' : '收起 ↑' }}
            </button>

            <div v-if="imageAttachments.length > 0" class="mt-3 grid grid-cols-2 gap-sm">
              <button
                v-for="attachment in imageAttachments"
                :key="attachment.fileId"
                type="button"
                class="relative w-full overflow-hidden rounded-lg border border-border/50 transition-shadow hover:shadow-md focus:ring-2 focus:ring-ring focus:ring-offset-2 focus:outline-none"
                @click="previewImageUrl = attachment.url; showImagePreview = true"
              >
                <img :src="attachment.url" :alt="attachment.filename" class="block h-40 w-full object-cover" loading="lazy" />
              </button>
            </div>

            <div
              v-if="visibleA2uiComponents.length"
              class="mt-3 overflow-hidden rounded-[calc(var(--radius)+4px)] border border-border/70 bg-background/80 text-sm text-foreground shadow-sm"
            >
              <div class="flex items-center gap-2 border-b border-border px-3 py-2">
                <span class="text-xs font-medium text-muted-foreground">{{ a2uiPanelLabel }}</span>
              </div>
              <div class="p-3">
                <A2uiRenderer
                  :components="visibleA2uiComponents"
                  :message-id="message.id"
                  :trace-id="message.traceId"
                  :streaming="streaming"
                />
              </div>
            </div>

            <div v-if="message.toolsSummary?.length && !activeReactSteps.length" class="mt-3 space-y-1.5">
              <ToolCallCard
                v-for="tool in message.toolsSummary"
                :key="tool.toolId"
                :tool="tool"
              />
            </div>

            <div v-if="kbSources.length" class="mt-3 flex flex-wrap gap-1.5">
              <KbSourceTag
                v-for="(source, index) in kbSources"
                :key="source.id"
                :source="source"
                :index="index"
              />
            </div>

          </template>

          <div v-if="message.role === 'user' && imageAttachments.length > 0" class="mt-3 grid grid-cols-2 gap-sm">
            <button
              v-for="attachment in imageAttachments"
              :key="attachment.fileId"
              type="button"
              class="list-card relative w-full overflow-hidden rounded-lg focus:ring-2 focus:ring-ring focus:ring-offset-2 focus:outline-none"
              @click="previewImageUrl = attachment.url; showImagePreview = true"
            >
              <img :src="attachment.url" :alt="attachment.filename" class="block h-32 w-full object-cover" loading="lazy" />
            </button>
          </div>

          <div v-if="audioAttachments.length > 0" class="mt-3 flex flex-col gap-sm">
            <div
              v-for="attachment in audioAttachments"
              :key="attachment.fileId"
              class="list-card flex items-center gap-2 px-3 py-2 text-xs text-foreground"
            >
              <Mic class="size-3.5 shrink-0 text-muted-foreground" />
              <span class="shrink-0 text-muted-foreground">语音</span>
              <audio :src="attachment.url" controls class="h-8 w-full min-w-0" />
            </div>
          </div>

          <div v-if="fileAttachments.length > 0" class="mt-3 flex flex-col gap-sm">
            <div
              v-for="attachment in fileAttachments"
              :key="attachment.fileId"
              class="list-card flex flex-col gap-2 px-3 py-3 text-xs text-foreground"
            >
              <div class="flex items-center justify-between gap-2">
                <span class="truncate">{{ attachment.filename }}</span>
                <span class="shrink-0 text-[11px] text-muted-foreground">
                  {{ (attachment.size / 1024).toFixed(1) }} KB
                </span>
              </div>
              <video
                v-if="attachment.type?.startsWith('video/')"
                :src="attachment.url"
                controls
                class="mt-1 w-full max-w-full max-h-[360px] rounded-lg"
              >
                当前浏览器不支持视频播放
              </video>
              <a
                v-else
                :href="attachment.url"
                target="_blank"
                rel="noopener noreferrer"
                class="mt-1 inline-flex items-center gap-1 text-[11px] text-primary underline-offset-2 hover:underline"
              >
                <FileText :size="14" class="text-muted-foreground" />
                <span>下载文件</span>
              </a>
            </div>
          </div>
        </div>
      </div>

      <template v-if="message.role === 'assistant' && !streaming">
        <div class="flex items-center gap-0.5 pl-xs">
          <MessageActions
            :message="message"
            :is-last-assistant="isLastAssistant ?? false"
            @copy="(content: string) => emit('copy', content)"
            @fork="(target: Message) => emit('fork', target)"
            @regenerate="(target: Message) => emit('regenerate', target)"
            @resume="(target: Message) => emit('resume', target)"
            @restart="(target: Message) => emit('restart', target)"
          />
          <MessageFeedback
            :message="message"
            @like="(target: Message) => emit('like', target)"
            @dislike="(target: Message, feedback?: string) => emit('dislike', target, feedback)"
          />
        </div>
      </template>

      <!-- 用户消息操作：复制 + 编辑 -->
      <div v-if="message.role === 'user' && message.status !== 'pending'" class="flex items-center justify-end gap-0.5 pr-xs">
        <button
          type="button"
          class="user-act-btn"
          title="复制"
          @click="handleUserCopy"
        >
          <Check v-if="userCopied" class="size-3.5 text-primary" />
          <Copy v-else class="size-3.5" />
        </button>
        <button
          type="button"
          class="user-act-btn"
          title="编辑"
          @click="emit('edit', message)"
        >
          <Pencil class="size-3.5" />
        </button>
      </div>

      <MessageError
        v-if="message.role === 'user' && message.status === 'error'"
        :message="message"
        @retry="(target: Message) => emit('retry', target)"
      />

      <Dialog
        v-if="previewImageUrl"
        :open="showImagePreview"
        @update:open="(value) => {
          showImagePreview = value
          if (!value) {
            previewImageUrl = null
          }
        }"
      >
        <DialogContent
          :show-close-button="false"
          class="max-w-[90vw] border-border/60 bg-background/95 p-2 shadow-2xl"
        >
          <img :src="previewImageUrl" alt="预览图片" class="block max-h-[85vh] max-w-full rounded-xl object-contain" />
        </DialogContent>
      </Dialog>
    </div>

  </div>
</template>

<style scoped>
.assistant-bubble {
  position: relative;
  border: none;
  background: transparent;
  box-shadow: none;
}


.streaming-status-pill {
  box-shadow: 0 8px 14px -20px hsl(var(--shadow-color) / 0.08);
}


.streaming-status-dot {
  width: 0.45rem;
  height: 0.45rem;
  border-radius: 999px;
  background: hsl(from var(--primary) h s l / 0.96);
  box-shadow: 0 0 0.45rem hsl(from var(--primary) h s l / 0.22);
  animation: streaming-dot-pulse 1.2s ease-in-out infinite;
}

.user-act-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.5rem;
  height: 1.5rem;
  border-radius: 0.3rem;
  color: var(--muted-foreground);
  transition: color 120ms ease;
}

.user-act-btn:hover {
  color: var(--foreground);
}

.user-bubble {
  position: relative;
  transform-origin: right bottom;
  border: 1px solid hsl(from var(--border) h s l / 0.5);
  background: hsl(from var(--primary) h s l / 0.08);
  box-shadow: 0 10px 18px -20px hsl(var(--shadow-color) / 0.1);
}

.user-bubble-pending {
  animation: user-bubble-pulse 1.7s var(--ease-fluid) infinite;
}

.message-toolbar {}

@keyframes streaming-dot-pulse {
  0%,
  100% {
    transform: scale(0.9);
    opacity: 0.8;
  }

  50% {
    transform: scale(1.15);
    opacity: 1;
  }
}

@keyframes user-bubble-pulse {
  0%,
  100% {
    transform: translateY(0) scale(1);
    box-shadow: 0 14px 24px -22px hsl(var(--shadow-color) / 0.24);
  }

  50% {
    transform: translateY(-1px) scale(1.01);
    box-shadow: 0 22px 30px -20px hsl(var(--shadow-color) / 0.28);
  }
}

</style>
