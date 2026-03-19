<script setup lang="ts">
import { computed, ref } from 'vue'
import { Bot, FileText, Mic } from 'lucide-vue-next'
import type { A2uiComponent, Message, ReasoningEvent, ReactStepDto, ToolConfirmationRequest } from '@/types'
import A2uiRenderer from '@/components/a2ui/A2uiRenderer.vue'
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
import ToolConfirmationBubble from './ToolConfirmationBubble.vue'

const props = defineProps<{
  message: Message
  streaming?: boolean
  streamingContent?: string
  streamingReasoningEvents?: ReasoningEvent[]
  streamingReactSteps?: ReactStepDto[]
  isLastAssistant?: boolean
  streamingA2uiComponents?: A2uiComponent[]
  /** 流式阶段的工具确认请求（从 useChat.pendingToolConfirmation 传入） */
  streamingToolConfirmation?: ToolConfirmationRequest
  /** 流式阶段的工具确认解决结果 */
  streamingToolConfirmationResolution?: 'approved' | 'rejected' | 'expired'
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

// 当前展示的 ReactStep 列表：流式时用 streamingReactSteps，否则用 message.reactSteps
const activeReactSteps = computed<ReactStepDto[]>(() => {
  if (props.streaming && props.streamingReactSteps?.length) {
    return props.streamingReactSteps
  }
  return props.message.reactSteps ?? []
})

// 当前展示的 ReasoningEvent 列表：流式时用 streamingReasoningEvents，否则用 message.reasoningEvents
const activeReasoningEvents = computed<ReasoningEvent[]>(() => {
  if (props.streaming && props.streamingReasoningEvents?.length) {
    return props.streamingReasoningEvents
  }
  return props.message.reasoningEvents ?? []
})

// 是否为工具确认消息（嵌入在 assistant 气泡内部）
const isToolConfirmation = computed(() => !!props.message.toolConfirmation)
</script>

<template>
  <div
    class="group/message grid w-full gap-md"
    :class="message.role === 'user' ? 'grid-cols-[1fr_auto]' : 'grid-cols-[auto_1fr]'"
  >
    <div
      v-if="message.role === 'assistant'"
      class="mt-xs flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary"
      aria-label="知微回复"
    >
      <Bot :size="14" />
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
          <span class="font-semibold text-foreground">知微</span>
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
        class="relative max-w-full rounded-2xl shadow-sm transition-all duration-200 md:max-w-[85%]"
        :class="message.role === 'user'
          ? 'rounded-tr-sm bg-primary p-md text-primary-foreground shadow-md group-hover/message:-translate-y-0.5 group-hover/message:shadow-lg'
          : 'assistant-bubble rounded-tl-sm border border-border bg-card p-md text-foreground group-hover/message:-translate-y-0.5 group-hover/message:shadow-[0_18px_36px_-28px_hsl(var(--shadow-color)/0.38)]'"
      >
        <div
          v-if="streaming && message.role === 'assistant'"
          class="pointer-events-none absolute inset-0 rounded-2xl rounded-tl-sm border-2 border-primary/60 animate-pulse"
        />

        <div class="relative z-[1]">
          <p
            v-if="message.role === 'user'"
            class="whitespace-pre-wrap text-sm"
            v-html="(message as any).highlightedContent ?? message.content"
          />

          <template v-else>
            <!-- 工具确认卡片（嵌入在 assistant 气泡内部，与文本内容共存） -->
            <div
              v-if="isToolConfirmation"
              class="mb-2"
            >
              <ToolConfirmationBubble
                :request="message.toolConfirmation!"
                :resolved="!!message.toolConfirmationResolution"
                :resolution="message.toolConfirmationResolution"
                @resolve="(r: 'approved' | 'rejected' | 'expired') => emit('tool-confirm-resolve', r)"
              />
            </div>

            <!-- 流式工具确认卡片（流式阶段通过 prop 传入） -->
            <div
              v-else-if="streamingToolConfirmation"
              class="mb-2"
            >
              <ToolConfirmationBubble
                :request="streamingToolConfirmation"
                :resolved="!!streamingToolConfirmationResolution"
                :resolution="streamingToolConfirmationResolution"
                @resolve="(r: 'approved' | 'rejected' | 'expired') => emit('tool-confirm-resolve', r)"
              />
            </div>

            <!-- 普通 assistant 文本内容 -->
            <StreamingText
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

            <!-- P3 修复：assistant 图片附件内联渲染（在消息文本之后、A2UI 面板之前） -->
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
                v-for="source in kbSources"
                :key="source.id"
                :source="source"
              />
            </div>

            <!-- 推理时间线（优先使用 ReasoningEvents，数据更完整） -->
            <ReasoningTimeline
              v-if="activeReasoningEvents.length > 0"
              :summary="message.reasoningSummary"
              :events="activeReasoningEvents"
              :streaming="streaming"
            />

            <!-- ReactStep 时间线兜底（无 reasoningEvents 时回退到 reactSteps） -->
            <ReactStepTimeline
              v-else-if="activeReactSteps.length > 0"
              :steps="activeReactSteps"
              :streaming="streaming"
              :summary="message.reasoningSummary"
            />
          </template>

            <!-- 用户消息的图片附件（保持原位置） -->
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

            <!-- 音频附件内联播放器 -->
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
        <div class="space-y-2 pl-xs md:pointer-events-none md:opacity-0 md:transition-opacity md:duration-150 md:group-hover/message:pointer-events-auto md:group-hover/message:opacity-100">
          <MessageActions
            :message="message"
            :is-last-assistant="isLastAssistant ?? false"
            @copy="(content: string) => emit('copy', content)"
            @fork="(target: Message) => emit('fork', target)"
            @regenerate="(target: Message) => emit('regenerate', target)"
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
      aria-label="你的消息"
    >
      你
    </div>
  </div>
</template>

<style scoped>
.assistant-bubble {
  border-left: 1.5px solid transparent;
  border-image: linear-gradient(to bottom, hsl(var(--primary) / 0.22), transparent 70%) 1;
}
</style>
