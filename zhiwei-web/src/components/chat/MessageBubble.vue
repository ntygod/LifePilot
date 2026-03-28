<script setup lang="ts">
import { computed, ref } from 'vue'
import { FileText, Mic } from 'lucide-vue-next'
import type {
  A2uiComponent,
  Message,
  PermissionApprovalLog,
  PermissionApprovalRequest,
  ReasoningEvent,
  ReactStepDto,
} from '@/types'
import A2uiRenderer from '@/components/a2ui/A2uiRenderer.vue'
import ZhiweiMark from '@/components/brand/ZhiweiMark.vue'
import { buildPermissionApprovalLog } from '@/utils/permissionApproval'
import {
  Dialog,
  DialogContent,
} from '@/components/ui/dialog'
import { getPreviewContent, shouldCollapse } from '@/utils/messageUtils'
import KbSourceTag from './KbSourceTag.vue'
import MessageActions from './MessageActions.vue'
import MessageError from './MessageError.vue'
import MessageFeedback from './MessageFeedback.vue'
import ReasoningTimeline from './ReasoningTimeline.vue'
import ReactStepTimeline from './ReactStepTimeline.vue'
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
  (e: 'permission-approval-resolve', requestId: string, resolution: 'approved' | 'rejected' | 'expired', subjectType?: string): void
}>()

const collapsed = ref(props.message.collapsed ?? shouldCollapse(props.message.content))
const showImagePreview = ref(false)
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
  'user-bubble rounded-[1.2rem] rounded-tr-[0.45rem] px-4 py-3 text-primary-foreground',
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
    class="group/message grid w-full gap-md"
    :class="message.role === 'user' ? 'grid-cols-[1fr_auto]' : 'grid-cols-[auto_1fr]'"
  >
    <div
      v-if="message.role === 'assistant'"
      class="assistant-avatar-shell mt-1 flex h-8 w-8 shrink-0 items-center justify-center rounded-[0.95rem] border border-primary/16 bg-card/82 text-primary"
      :class="streaming && 'assistant-avatar-streaming'"
      style="--zhiwei-logo-accent: hsl(from var(--card) h s l / 0.98);"
      aria-label="知微回复"
    >
      <ZhiweiMark class="size-[1.05rem]" />
    </div>

    <div
      class="min-w-0 space-y-xs"
      :class="message.role === 'user' ? 'flex flex-col items-end' : ''"
    >
      <div
        class="flex items-center gap-xs text-xs text-muted-foreground"
        :class="message.role === 'user' ? 'justify-end' : ''"
      >
        <template v-if="message.role === 'assistant'">
          <span class="inline-flex items-center gap-1.5">
            <span class="h-px w-3 rounded-full bg-primary/72" />
            <span class="font-medium text-foreground/92">知微</span>
          </span>
          <span>·</span>
          <time :datetime="new Date(message.timestamp).toISOString()">{{ timeLabel }}</time>
        </template>
        <template v-else>
          <time :datetime="new Date(message.timestamp).toISOString()">{{ timeLabel }}</time>
          <span>·</span>
          <span class="font-semibold text-foreground">你</span>
          <template v-if="userStatusLabel">
            <span>·</span>
            <span :class="message.status === 'error' ? 'text-destructive' : 'text-muted-foreground'">
              {{ userStatusLabel }}
            </span>
          </template>
        </template>
      </div>

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
          message.role === 'user' ? 'overflow-hidden shadow-sm md:max-w-[82%]' : 'overflow-visible md:max-w-[88%]',
          message.role === 'user' ? userBubbleClass : assistantBubbleClass,
        ]"
      >
        <div
          v-if="streaming && message.role === 'assistant'"
          class="pointer-events-none absolute inset-y-1 left-0 w-px bg-primary/36"
        />

        <div class="relative z-[1]">
          <p
            v-if="message.role === 'user'"
            class="whitespace-pre-wrap text-sm"
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
              {{ collapsed ? '展开全文' : '收起' }}
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

            <ReasoningTimeline
              v-if="activeReasoningEvents.length > 0"
              :summary="message.reasoningSummary"
              :events="activeReasoningEvents"
              :streaming="streaming"
            />

            <ReactStepTimeline
              v-else-if="activeReactSteps.length > 0"
              :steps="activeReactSteps"
              :streaming="streaming"
              :summary="message.reasoningSummary"
            />
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
                class="mt-1 w-full max-w-full rounded-lg"
                style="max-height: 360px;"
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
        <div class="message-toolbar space-y-2 pl-xs md:pointer-events-none md:opacity-0 md:transition-all md:duration-200 md:group-hover/message:pointer-events-auto md:group-hover/message:translate-y-0 md:group-hover/message:opacity-100">
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

    <div
      v-if="message.role === 'user'"
      class="mt-xs flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary text-[10px] font-semibold text-primary-foreground shadow-md"
      :class="message.status === 'pending' && 'user-avatar-pending'"
      aria-label="你的消息"
    >
      你
    </div>
  </div>
</template>

<style scoped>
.assistant-bubble {
  position: relative;
  padding-left: 1rem;
  border: none;
  background: transparent;
  box-shadow: none;
}

.assistant-bubble::before {
  content: "";
  position: absolute;
  inset: 0 auto 0 0;
  pointer-events: none;
  width: 1px;
  background: linear-gradient(180deg, transparent, hsl(from var(--primary) h s l / 0.72) 18%, hsl(from var(--border) h s l / 0.42) 84%, transparent);
  opacity: 0.9;
}

.assistant-bubble::after {
  content: "";
  position: absolute;
  left: -1px;
  top: 0.4rem;
  height: 0.42rem;
  width: 0.42rem;
  border-radius: 999px;
  background: hsl(from var(--primary) h s l / 0.82);
  box-shadow: 0 0 0.55rem hsl(from var(--primary) h s l / 0.16);
  opacity: 0.88;
}

.assistant-bubble-streaming {
  padding-left: 1rem;
}

.streaming-status-pill {
  box-shadow: 0 8px 14px -20px hsl(var(--shadow-color) / 0.08);
}

.assistant-bubble-streaming::after {
  left: -1px;
  top: 0.35rem;
  animation: assistant-stream-sheen 2.3s linear infinite;
}

.assistant-avatar-shell {
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.74);
}

.assistant-avatar-streaming {
  box-shadow:
    0 0 0 1px hsl(from var(--primary) h s l / 0.14),
    0 0 0.75rem hsl(from var(--primary) h s l / 0.1),
    inset 0 1px 0 hsl(from var(--card) h s l / 0.74);
  animation: assistant-avatar-breathe 1.8s var(--ease-fluid) infinite;
}

.streaming-status-dot {
  width: 0.45rem;
  height: 0.45rem;
  border-radius: 999px;
  background: hsl(from var(--primary) h s l / 0.96);
  box-shadow: 0 0 0.45rem hsl(from var(--primary) h s l / 0.22);
  animation: streaming-dot-pulse 1.2s ease-in-out infinite;
}

.user-bubble {
  position: relative;
  transform-origin: right bottom;
  border: 1px solid hsl(from var(--primary) h s l / 0.14);
  background: linear-gradient(180deg, hsl(from var(--primary) h s l / 0.96), hsl(from var(--primary) h s l / 0.9));
  box-shadow: 0 10px 18px -20px hsl(var(--shadow-color) / 0.18);
}

.user-bubble::before {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  border-radius: inherit;
  box-shadow: inset 0 1px 0 hsl(from var(--primary-foreground) h s l / 0.14);
  opacity: 1;
}

.user-bubble-pending {
  animation: user-bubble-pulse 1.7s var(--ease-fluid) infinite;
}

.user-avatar-pending {
  animation: user-avatar-pulse 1.7s var(--ease-fluid) infinite;
}

.message-toolbar {
  transform: translateY(4px);
}

@keyframes assistant-stream-sheen {
  0% {
    opacity: 0;
    transform: translateX(-12%) rotate(10deg);
  }

  18% {
    opacity: 0.7;
  }

  100% {
    opacity: 0;
    transform: translateX(260%) rotate(10deg);
  }
}

@keyframes assistant-avatar-breathe {
  0%,
  100% {
    transform: translateY(0) scale(1);
  }

  50% {
    transform: translateY(-1px) scale(1.04);
  }
}

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

@keyframes user-avatar-pulse {
  0%,
  100% {
    transform: scale(1);
  }

  50% {
    transform: scale(1.06);
  }
}
</style>
