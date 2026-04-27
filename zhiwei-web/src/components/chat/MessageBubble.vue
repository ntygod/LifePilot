<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { Check, Copy, FileCode2, FileText, FileType, Mic, Pencil, Presentation, Sheet } from 'lucide-vue-next'
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
// messageUtils 导入已移除 — 不再折叠 AI 消息
import KbSourceTag from './KbSourceTag.vue'
import MessageActions from './MessageActions.vue'
import MessageError from './MessageError.vue'
import MessageFeedback from './MessageFeedback.vue'
import ReasoningSection from './ReasoningSection.vue'
import ThinkingIndicator from './ThinkingIndicator.vue'
import StreamingText from './StreamingText.vue'
import ToolCallCard from './ToolCallCard.vue'
import PermissionApprovalBubble from './PermissionApprovalBubble.vue'
import DocumentDiffCard from './DocumentDiffCard.vue'
import DocumentXlsxDiffCard from './DocumentXlsxDiffCard.vue'
import { useDocumentMeta } from '@/composables/useDocumentMeta'

const props = defineProps<{
  message: Message
  streaming?: boolean
  streamingContent?: string
  streamingReasoningEvents?: ReasoningEvent[]
  /** 流式期间的推理 token 累计文本（reasoning_content 增量缓冲区） */
  streamingReasoningBuffer?: string
  /** 是否处于推理流活跃中（首个 reasoning delta 触发，DONE 后置 false） */
  streamingIsReasoningActive?: boolean
  /** 流式推理时长（毫秒），DONE 后定格 */
  streamingReasoningDurationMs?: number
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
  (e: 'edit', message: Message, newContent: string): void
  (e: 'show-trace', messageId: string): void
  (e: 'permission-approval-resolve', requestId: string, resolution: 'approved' | 'rejected' | 'expired', subjectType?: string): void
}>()

const showImagePreview = ref(false)
const userCopied = ref(false)
const isEditing = ref(false)
const editContent = ref('')
const editTextareaRef = ref<HTMLTextAreaElement | null>(null)

function startEditing() {
  editContent.value = props.message.content
  isEditing.value = true
  nextTick(() => {
    const textarea = editTextareaRef.value
    if (textarea) {
      autoResizeTextarea(textarea)
      textarea.focus()
      textarea.setSelectionRange(textarea.value.length, textarea.value.length)
    }
  })
}

function cancelEditing() {
  isEditing.value = false
  editContent.value = ''
}

function submitEdit() {
  const trimmed = editContent.value.trim()
  if (!trimmed) return
  isEditing.value = false
  emit('edit', props.message, trimmed)
  editContent.value = ''
}

function autoResizeTextarea(el: HTMLTextAreaElement) {
  el.style.height = 'auto'
  el.style.height = `${el.scrollHeight}px`
}

function onEditInput(event: Event) {
  autoResizeTextarea(event.target as HTMLTextAreaElement)
}

function onEditKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    submitEdit()
  }
  if (event.key === 'Escape') {
    cancelEditing()
  }
}

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

/**
 * 判断附件是否是 AI 可解析的文档类型。
 *
 * Phase 1B 实际可解析集合：pdf / docx / xlsx / pptx / md / txt / csv / log / tsv。
 *
 * 与后端保持同步：`PlainTextParser.EXTENSIONS` + `MarkdownParser.EXTENSIONS` +
 * `WordParser / PdfParser / ExcelParser / PowerpointParser` 的 supportedExtensions()，
 * 以及 `BrowserIngressService.DOCUMENT_MIME_PREFIXES`。新增文档 parser 时需同步更新。
 */
function isParseableDocument(att: { type?: string; filename: string }): boolean {
  const t = att.type?.toLowerCase() ?? ''
  if (t === 'application/pdf') return true
  if (t.includes('wordprocessingml')) return true  // docx
  if (t.includes('spreadsheetml')) return true     // xlsx
  if (t.includes('presentationml')) return true    // pptx
  if (t === 'text/markdown' || t === 'text/plain' || t === 'text/csv'
      || t === 'text/tab-separated-values') return true
  // 兜底按扩展名识别
  const ext = att.filename.split('.').pop()?.toLowerCase()
  return ['pdf', 'docx', 'xlsx', 'pptx', 'md', 'txt', 'csv', 'log', 'tsv'].includes(ext ?? '')
}

/** 按文件扩展名返回 lucide 图标组件 */
function documentIcon(att: { type?: string; filename: string }) {
  const ext = att.filename.split('.').pop()?.toLowerCase() ?? ''
  if (ext === 'pdf') return FileType
  if (ext === 'xlsx' || ext === 'csv') return Sheet
  if (ext === 'pptx') return Presentation
  if (ext === 'md') return FileCode2
  return FileText
}

/**
 * Phase 3A：识别「已被 document.edit 修改过的 docx」—— 需要换用 DocumentDiffCard 渲染。
 *
 * 判定条件：
 * 1. MIME 包含 `wordprocessingml.document`（docx）
 * 2. URL 形如 `/api/documents/{id}/download`，可解析出 documentId
 * 3. GET `/api/documents/{id}` 返回 `latestVersion > 0`（经历过至少一次 patch）
 *
 * 条件不满足 → 回落到既有附件卡片（非 docx / Phase 2A 产物 / 未编辑 docx）。
 */
// 文档元数据缓存：从 composable 取模块级共享实例，所有 MessageBubble + useChat 都引用同一份。
// useChat 在 AI 流式结束时调 invalidateAllDocumentMeta() 清空，触发下次 render 重新拉 latestVersion
const { documentMetaCache, resolveDocumentMeta, invalidateDocumentMeta } = useDocumentMeta()

/** 按 download URL 提取 documentId；不匹配则返回 null */
function extractDocumentId(url: string | undefined): string | null {
  if (!url) return null
  const m = url.match(/\/api\/documents\/([^/]+)\/download/)
  return m ? m[1] : null
}

/** 判断附件是否为「已编辑的 docx」—— 同步返回，异步触发缓存填充，下次渲染自动切换 */
function isEditedDocx(att: { type?: string; url?: string }): boolean {
  if (!att.type?.includes('wordprocessingml.document')) return false
  const docId = extractDocumentId(att.url)
  if (!docId) return false
  void resolveDocumentMeta(docId)
  const meta = documentMetaCache.value[docId]
  return !!meta && meta.latestVersion > 0
}

/** 判断附件是否为「已编辑的 xlsx」—— 与 isEditedDocx 同款机制，MIME 检查换成 spreadsheetml.sheet */
function isEditedXlsx(att: { type?: string; url?: string }): boolean {
  if (!att.type?.includes('spreadsheetml.sheet')) return false
  const docId = extractDocumentId(att.url)
  if (!docId) return false
  void resolveDocumentMeta(docId)
  const meta = documentMetaCache.value[docId]
  return !!meta && meta.latestVersion > 0
}

/** DiffCard commit / 丢弃工作副本 → 失效缓存，下次渲染重新拉取（latestVersion 变化 or 404） */
function onDocumentCommitted(documentId: string) {
  invalidateDocumentMeta(documentId)
}
function onDocumentDiscarded(documentId: string) {
  invalidateDocumentMeta(documentId)
}

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
  return props.message.content
})

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

/** 推理流文本：流式期间从 streamingReasoningBuffer 取，历史消息从 message.reasoningContent 取 */
const activeReasoningContent = computed<string>(() => {
  if (props.streaming) {
    return props.streamingReasoningBuffer ?? ''
  }
  return props.message.reasoningContent ?? ''
})

/** 推理流活跃状态：流式期间响应实时；历史消息恒为 false（不再思考） */
const activeReasoningIsActive = computed<boolean>(() => {
  if (props.streaming) {
    return !!props.streamingIsReasoningActive
  }
  return false
})

/** 推理流时长：流式期间响应实时（DONE 后定格）；历史消息从 message.reasoningDurationMs 取 */
const activeReasoningDurationMs = computed<number | undefined>(() => {
  if (props.streaming) {
    return props.streamingReasoningDurationMs
  }
  return props.message.reasoningDurationMs
})

/** 推理 section 显示条件：流式中或已落入历史 reasoningContent */
const shouldShowReasoning = computed<boolean>(() =>
  activeReasoningIsActive.value || activeReasoningContent.value.length > 0,
)

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
  || shouldShowReasoning.value
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
  'user-bubble rounded-2xl px-xl py-md text-foreground',
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
      class="space-y-xs"
      :class="message.role === 'user' ? 'w-full flex flex-col items-end' : 'min-w-0'"
    >

      <div
        class="relative transition-all duration-200"
        :class="[
          message.role === 'user'
            ? (isEditing ? 'w-full overflow-visible' : 'w-fit overflow-hidden shadow-sm max-w-[65%]')
            : 'max-w-full overflow-visible',
          message.role === 'user' ? (isEditing ? '' : userBubbleClass) : assistantBubbleClass,
        ]"
      >
        <div class="relative z-[1]">
          <!-- 用户消息：编辑模式 / 展示模式 -->
          <template v-if="message.role === 'user'">
            <div v-if="isEditing" class="edit-container rounded-2xl border border-border/60 bg-background px-xl py-md shadow-sm">
              <textarea
                ref="editTextareaRef"
                v-model="editContent"
                class="w-full max-h-[50vh] resize-none overflow-y-auto border-none bg-transparent text-[15px] leading-relaxed text-foreground outline-none placeholder:text-muted-foreground/50"
                rows="1"
                @input="onEditInput"
                @keydown="onEditKeydown"
              />
              <div class="mt-sm flex items-center justify-end gap-sm">
                <button
                  type="button"
                  class="rounded-full px-lg py-xs text-sm text-muted-foreground transition-colors hover:bg-muted/60"
                  @click="cancelEditing"
                >
                  取消
                </button>
                <button
                  type="button"
                  class="rounded-full bg-primary px-lg py-xs text-sm text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-40"
                  :disabled="!editContent.trim()"
                  @click="submitEdit"
                >
                  发送
                </button>
              </div>
            </div>
            <p
              v-else
              class="whitespace-pre-wrap text-[15px] leading-relaxed"
              v-html="(message as any).highlightedContent ?? message.content"
            />
          </template>

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

            <ReasoningSection
              v-if="shouldShowReasoning"
              :reasoning="activeReasoningContent"
              :active="activeReasoningIsActive"
              :duration-ms="activeReasoningDurationMs"
            />

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

          <div v-if="fileAttachments.length > 0" class="mt-md flex flex-col gap-sm">
            <template v-for="attachment in fileAttachments" :key="attachment.fileId">
              <!-- Phase 3A：docx 被 document.edit 改过 → 挂 DiffCard；其余路径沿用原卡片 -->
              <DocumentDiffCard
                v-if="isEditedDocx(attachment)"
                :document-id="extractDocumentId(attachment.url)!"
                @committed="onDocumentCommitted"
                @discarded="onDocumentDiscarded"
              />

              <!-- Phase 3B：xlsx 被 document.edit 改过 → 挂 XlsxDiffCard -->
              <DocumentXlsxDiffCard
                v-else-if="isEditedXlsx(attachment)"
                :document-id="extractDocumentId(attachment.url)!"
                @committed="onDocumentCommitted"
                @discarded="onDocumentDiscarded"
              />

              <!-- 视频附件：保留原 list-card + <video> 播放器布局 -->
              <div
                v-else-if="attachment.type?.startsWith('video/')"
                class="list-card flex flex-col gap-sm px-md py-md text-xs text-foreground"
              >
                <div class="flex items-center justify-between gap-sm">
                  <span class="truncate">{{ attachment.filename }}</span>
                  <span class="shrink-0 text-xs text-muted-foreground">
                    {{ (attachment.size / 1024).toFixed(1) }} KB
                  </span>
                </div>
                <video
                  :src="attachment.url"
                  controls
                  class="mt-xs w-full max-w-full max-h-[360px] rounded-lg"
                >
                  当前浏览器不支持视频播放
                </video>
              </div>

              <!-- 文档附件：图标 + 「AI 可读取」徽标 + 下载链接 -->
              <div
                v-else
                class="flex items-center gap-sm rounded-md border border-border bg-muted/40 p-sm"
              >
                <component :is="documentIcon(attachment)" class="h-md w-md shrink-0 text-muted-foreground" />
                <div class="min-w-0 flex-1">
                  <div class="flex items-center gap-xs">
                    <span class="truncate text-sm">{{ attachment.filename }}</span>
                    <span
                      v-if="isParseableDocument(attachment)"
                      class="shrink-0 rounded-md bg-primary/10 px-xs py-xs text-xs text-primary"
                      title="AI 可直接读取此文档内容"
                    >AI 可读取</span>
                  </div>
                  <div class="text-xs text-muted-foreground">
                    {{ (attachment.size / 1024).toFixed(1) }} KB
                  </div>
                </div>
                <a
                  :href="attachment.url"
                  :download="attachment.filename"
                  class="shrink-0 text-xs text-primary hover:underline"
                >下载</a>
              </div>
            </template>
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
      <div v-if="message.role === 'user' && message.status !== 'pending' && !isEditing" class="flex items-center justify-end gap-0.5 pr-xs">
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
          @click="startEditing"
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
  border: none;
  background: var(--muted);
  box-shadow: none;
}

:global(.dark) .user-bubble {
  box-shadow: inset 0 0 0 1px hsl(0 0% 100% / 0.05);
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
