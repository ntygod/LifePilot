<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  ArrowUp,
  AtSign,
  BrainCircuit,
  FileAudio2,
  FileText,
  FileVideo,
  EyeOff,
  Image,
  LibraryBig,
  Mic,
  Paperclip,
  Search,
  Square,
  X,
} from 'lucide-vue-next'
import { chatApi } from '@/api/client'
import { logger } from '@/utils/logger'
import { useChatStore } from '@/stores/chat'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { useVoice } from '@/composables/useVoice'
import { useWhisperDownload } from '@/composables/useWhisperDownload'
import AudioWaveform from '@/components/chat/AudioWaveform.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import type { ChatAttachment, KnowledgeBase, SessionConfig, SessionConfigOverride } from '@/types'

const props = defineProps<{
  disabled?: boolean
  streaming?: boolean
  placeholder?: string
  knowledgeBases?: KnowledgeBase[]
  baseSessionConfig?: SessionConfig
}>()

const emit = defineEmits<{
  send: [{
    content: string
    attachmentIds?: string[]
    attachments?: ChatAttachment[]
    /** 单轮临时覆盖的会话配置：仅影响本轮 Agent 执行，不污染持久化 config */
    singleTurnOverride?: SessionConfigOverride | null
  }]
  stop: []
  'draft-change': [{
    content: string
    hasAttachments: boolean
    contextCount: number
  }]
}>()

const chatStore = useChatStore()
const route = useRoute()
const input = ref('')
const textareaRef = ref<{ $el?: HTMLElement; focus?: () => void } | null>(null)
const maxLength = 4000
type ContextKind = 'knowledge-base' | 'memory'
type MemoryContextMode = 'focused' | 'off'
interface ComposerContext {
  id: string
  name: string
  description?: string | null
  kind: ContextKind
  memoryMode?: MemoryContextMode
}
const MEMORY_FOCUSED_CONTEXT_ID = '__zhiwei_memory_focused__'
const MEMORY_OFF_CONTEXT_ID = '__zhiwei_memory_off__'
const MEMORY_CONTEXT_OPTIONS: ComposerContext[] = [{
  id: MEMORY_FOCUSED_CONTEXT_ID,
  name: '我的记忆',
  description: '偏好、事实和经验',
  kind: 'memory',
  memoryMode: 'focused',
}, {
  id: MEMORY_OFF_CONTEXT_ID,
  name: '本轮不用记忆',
  description: '只按当前消息回答',
  kind: 'memory',
  memoryMode: 'off',
}]

const PLACEHOLDERS = [
  '想聊点什么？',
  '有什么我能帮到你的？',
  '试试 @ 引用记忆或知识库...',
  '可以直接粘贴图片或文件',
  '输入问题，或贴一段内容...',
]
const randomPlaceholder = ref(PLACEHOLDERS[Math.floor(Math.random() * PLACEHOLDERS.length)])
const inputLength = computed(() => input.value.length)

const attachments = ref<File[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const isUploading = ref(false)
const uploadError = ref<string | null>(null)
const dragDepth = ref(0)
const dragActive = computed(() => dragDepth.value > 0)

const voiceSending = ref(false)
const sendPulsing = ref(false)
const voiceError = ref<string | null>(null)
const manualContextPickerOpen = ref(false)
const manualContextQuery = ref('')
const selectedContexts = ref<ComposerContext[]>([])

// 语音录音
const {
  isRecording, recordingDuration, audioBlob, isSupported: voiceSupported, analyserNode,
  startRecording, stopRecording,
} = useVoice()

// Whisper 自动下载（桌面端）
const {
  available: whisperAvailable,
  status: whisperStatus,
  checkAvailability: checkWhisper,
  triggerDownload: downloadWhisper,
} = useWhisperDownload()

/** Whisper 下载确认弹窗 */
const showWhisperConfirm = ref(false)

/** 麦克风按钮点击：检测语音能力，不可用则 Tauri 端弹下载确认，Web 端提示 */
async function handleMicClick() {
  if (props.streaming) {
    return
  }
  if (!whisperAvailable.value) {
    await checkWhisper()
    if (!whisperAvailable.value) {
      if ('__TAURI_INTERNALS__' in window) {
        // 桌面端：可以下载 Whisper
        if (whisperStatus.value !== 'downloading') {
          showWhisperConfirm.value = true
        }
      } else {
        // Web 端：无法本地下载，提示配置
        voiceError.value = '语音功能暂不可用，请在设置中配置语音模型'
      }
      return
    }
  }
  startRecording()
}

function confirmWhisperDownload() {
  showWhisperConfirm.value = false
  downloadWhisper()
}

const sendDisabled = computed(() => props.disabled || isUploading.value || (!input.value.trim() && attachments.value.length === 0))
const mentionQuery = computed(() => {
  const match = input.value.match(/(?:^|\s)@([^\s@]*)$/)
  return match ? match[1] ?? '' : null
})
const contextQuery = computed(() => (
  manualContextPickerOpen.value ? manualContextQuery.value.trim() : (mentionQuery.value ?? '').trim()
))
const showContextPicker = computed(() => manualContextPickerOpen.value || mentionQuery.value !== null)

// 输入 @ 时自动同步到手动选择器，保证两种触发方式表现一致
watch(mentionQuery, (query) => {
  if (query !== null && !manualContextPickerOpen.value) {
    manualContextPickerOpen.value = true
  }
  if (query !== null) {
    manualContextQuery.value = query
  }
})
const allContextOptions = computed(() => [
  ...MEMORY_CONTEXT_OPTIONS,
  ...(props.knowledgeBases ?? []).map(knowledgeBase => ({
    id: knowledgeBase.id,
    name: knowledgeBase.name,
    description: knowledgeBase.description,
    kind: 'knowledge-base' as const,
  })),
])
const filteredContextOptions = computed(() => {
  const query = contextQuery.value.toLowerCase()
  return allContextOptions.value.filter(option => {
    if (selectedContexts.value.some(selected => selected.kind === option.kind && selected.id === option.id)) {
      return false
    }
    if (!query) {
      return true
    }
    return option.name.toLowerCase().includes(query)
      || option.id.toLowerCase().includes(query)
      || (option.description?.toLowerCase().includes(query) ?? false)
  })
})
const pickerTitle = computed(() => (
  manualContextPickerOpen.value
    ? '引用上下文'
    : `@ ${mentionQuery.value ?? ''}`.trim()
))
const selectedContextCount = computed(() => selectedContexts.value.length)
const selectedMemoryMode = computed(() =>
  selectedContexts.value.find(option => option.kind === 'memory')?.memoryMode,
)
const activeContextCount = computed(() =>
  selectedContexts.value.filter(option => option.kind !== 'memory' || option.memoryMode !== 'off').length,
)
const contextPickerDescription = computed(() => (
  selectedContextCount.value > 0
    ? `已选 ${selectedContextCount.value} 项，仅对当前消息生效。`
    : '仅对当前消息生效。'
))
const selectedContextSummary = computed(() => (
  selectedContextCount.value === 0
    ? ''
    : selectedMemoryMode.value === 'off' && activeContextCount.value === 0
      ? '本轮不使用长期记忆 · 发送后清空'
      : selectedMemoryMode.value === 'off'
        ? `本轮使用 ${activeContextCount.value} 个上下文 · 不用长期记忆 · 发送后清空`
        : `本轮使用 ${selectedContextCount.value} 个上下文 · 发送后清空`
))

watch(
  [input, () => attachments.value.length, selectedContextCount],
  ([content, attachmentCount, contextCount]) => {
    emit('draft-change', {
      content,
      hasAttachments: attachmentCount > 0,
      contextCount,
    })
  },
)

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    if (mentionQuery.value !== null && filteredContextOptions.value.length > 0) {
      event.preventDefault()
      selectContext(filteredContextOptions.value[0])
      return
    }
    if (props.streaming) {
      event.preventDefault()
      return
    }
    event.preventDefault()
    void submit()
  }
}

function normalizeAttachmentFile(file: File): File {
  if (file.name && file.name.trim()) {
    return file
  }

  const mimeType = file.type || 'application/octet-stream'
  const suffix = mimeType.startsWith('image/')
    ? mimeType.split('/')[1] || 'png'
    : 'bin'
  return new File([file], `clipboard-${Date.now()}.${suffix}`, {
    type: mimeType,
    lastModified: Date.now(),
  })
}

function addAttachments(files: File[]) {
  for (const file of files) {
    const normalized = normalizeAttachmentFile(file)
    const duplicated = attachments.value.some(item => item.name === normalized.name && item.size === normalized.size)
    if (!duplicated) {
      attachments.value.push(normalized)
    }
  }
}

function handlePaste(event: ClipboardEvent) {
  const files = Array.from(event.clipboardData?.files ?? [])
  if (files.length === 0) {
    return
  }

  event.preventDefault()
  addAttachments(files)
}

async function submit() {
  const content = input.value.trim()
  if (props.streaming || (!content && attachments.value.length === 0) || sendDisabled.value) return

  let attachmentIds: string[] | undefined
  let uploadedAttachments: ChatAttachment[] | undefined

  if (attachments.value.length > 0) {
    isUploading.value = true
    uploadError.value = null

    // 懒创建：上传附件需要 sessionId，如果还没有会话则先创建
    // 注意：创建会话后不要触发路由跳转导致组件状态重置，
    // 路由同步由 ChatView 的 activeSessionId watcher 统一处理
    if (!chatStore.activeSessionId) {
      const projectIdFromQuery = typeof route.query.projectId === 'string'
        ? route.query.projectId
        : null
      try {
        await chatStore.startNewSession(undefined, projectIdFromQuery)
        // 等待一个 tick 让 router.replace 和相关 watcher 稳定
        await new Promise(resolve => setTimeout(resolve, 0))
      } catch {
        uploadError.value = '创建会话失败，请重试'
        isUploading.value = false
        return
      }
    }

    try {
      const uploaded = await Promise.all(
        attachments.value.map(file => chatApi.uploadAttachment(file, chatStore.activeSessionId!)),
      )
      attachmentIds = uploaded.map(item => item.fileId)
      uploadedAttachments = uploaded
    } catch (error) {
      logger.error('附件上传失败:', error)
      uploadError.value = error instanceof Error ? error.message : '附件处理失败，请重试或移除附件'
      return
    } finally {
      isUploading.value = false
    }
  }

  const singleTurnOverride = buildSingleTurnOverride() ?? null

  // 发送脉冲动效
  sendPulsing.value = true
  setTimeout(() => { sendPulsing.value = false }, 500)

  emit('send', {
    content,
    attachmentIds,
    attachments: uploadedAttachments,
    singleTurnOverride,
  })

  input.value = ''
  attachments.value = []
  uploadError.value = null
  resetTemporaryContextSelection()
  randomPlaceholder.value = PLACEHOLDERS[Math.floor(Math.random() * PLACEHOLDERS.length)]
}

function handleFileSelect() {
  fileInput.value?.click()
}

function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const files = target.files
  if (!files) return

  addAttachments(Array.from(files))

  target.value = ''
}

function handleDragEnter(event: DragEvent) {
  if (event.dataTransfer?.types.includes('Files')) {
    dragDepth.value += 1
  }
}

function handleDragLeave(event: DragEvent) {
  if (!event.dataTransfer?.types.includes('Files')) {
    return
  }

  dragDepth.value = Math.max(0, dragDepth.value - 1)
}

function handleDrop(event: DragEvent) {
  dragDepth.value = 0
  const files = Array.from(event.dataTransfer?.files ?? [])
  if (files.length > 0) {
    addAttachments(files)
  }
}

function removeAttachment(index: number) {
  attachments.value.splice(index, 1)
}

function toggleManualContextPicker() {
  manualContextPickerOpen.value = !manualContextPickerOpen.value
  if (!manualContextPickerOpen.value) {
    manualContextQuery.value = ''
  }
}

function selectContext(option: {
  id: string
  name: string
  description?: string | null
  kind: ContextKind
  memoryMode?: MemoryContextMode
}) {
  if (!selectedContexts.value.some(selected => selected.kind === option.kind && selected.id === option.id)) {
    if (option.kind === 'memory') {
      selectedContexts.value = selectedContexts.value.filter(selected => selected.kind !== 'memory')
    }
    selectedContexts.value.push(option)
  }
  if (mentionQuery.value !== null) {
    removeTrailingMention()
  }
  manualContextPickerOpen.value = false
  manualContextQuery.value = ''
}

function removeContext(kind: ContextKind, id: string) {
  selectedContexts.value = selectedContexts.value.filter(option => !(option.kind === kind && option.id === id))
}

function removeTrailingMention() {
  input.value = input.value.replace(/(?:^|\s)@[^\s@]*$/, matched => matched.startsWith(' ') ? ' ' : '')
  input.value = input.value.replace(/\s{2,}$/g, ' ')
  if (input.value === ' ') {
    input.value = ''
  }
}

function buildSingleTurnOverride(): SessionConfigOverride | undefined {
  // 仅当用户临时勾选了 @ 上下文时才构造 override；
  // 没勾时返回 undefined，让后端走会话持久化配置。
  if (selectedContexts.value.length === 0) {
    return undefined
  }

  const extraKbIds = selectedContexts.value
    .filter(option => option.kind === 'knowledge-base')
    .map(option => option.id)
  const memoryMode = selectedContexts.value.find(option => option.kind === 'memory')?.memoryMode
  const override: SessionConfigOverride = {}

  if (extraKbIds.length > 0) {
    const baseKbIds = props.baseSessionConfig?.knowledgeBaseIds ?? []
    override.knowledgeBaseIds = mergeIds(baseKbIds, extraKbIds)
  }
  if (memoryMode) {
    override.memoryContextMode = memoryMode
  }

  return Object.keys(override).length > 0 ? override : undefined
}

function mergeIds(baseIds: string[], extraIds: string[]) {
  return Array.from(new Set([...baseIds, ...extraIds]))
}

function resetTemporaryContextSelection() {
  manualContextPickerOpen.value = false
  manualContextQuery.value = ''
  selectedContexts.value = []
}

function formatFileSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function getContextOptionIcon(option: ComposerContext) {
  if (option.memoryMode === 'off') return EyeOff
  if (option.kind === 'memory') return BrainCircuit
  return LibraryBig
}

function getContextKindLabel(option: ComposerContext) {
  if (option.memoryMode === 'off') return '关闭记忆'
  return option.kind === 'memory' ? '记忆' : '知识库'
}

function getFileIcon(file: File) {
  const type = file.type || ''

  if (type.startsWith('image/')) return Image
  if (type.startsWith('audio/')) return FileAudio2
  if (type.startsWith('video/')) return FileVideo
  return FileText
}

/** 格式化录音时长为 mm:ss */
function formatDuration(seconds: number) {
  const m = Math.floor(seconds / 60).toString().padStart(2, '0')
  const s = (seconds % 60).toString().padStart(2, '0')
  return `${m}:${s}`
}

// 录音完成后自动上传并发送
watch(audioBlob, async (blob) => {
  if (!blob) return
  voiceSending.value = true
  voiceError.value = null

  try {
    // 懒创建：语音上传需要 sessionId，如果还没有会话则先创建
    if (!chatStore.activeSessionId) {
      const projectIdFromQuery = typeof route.query.projectId === 'string'
        ? route.query.projectId
        : null
      await chatStore.startNewSession(undefined, projectIdFromQuery)
    }

    const ext = blob.type.includes('wav') ? 'wav' : 'webm'
    const file = new File([blob], `voice-${Date.now()}.${ext}`, { type: blob.type })
    const sessionId = chatStore.activeSessionId!
    const uploaded = await chatApi.uploadAttachment(file, sessionId)
    const singleTurnOverride = buildSingleTurnOverride() ?? null

    emit('send', {
      content: '[语音消息]',
      attachmentIds: [uploaded.fileId],
      attachments: [uploaded],
      singleTurnOverride,
    })
    resetTemporaryContextSelection()
  } catch (error) {
    logger.error('语音消息上传失败:', error)
    voiceError.value = error instanceof Error ? error.message : '语音消息发送失败，请重试'
  } finally {
    voiceSending.value = false
  }
})

defineExpose({
  input,
  isUploading,
  getFileIcon,
  /** 把外部文本填入输入框 —— 用于 PromptGallery 点击建议 → 灌入 prompt */
  setContent(text: string) {
    input.value = text
  },
  /** 聚焦到输入框 */
  focus() {
    const el = (textareaRef.value as { $el?: HTMLElement; focus?: () => void } | null) ?? null
    if (!el) return
    if (typeof el.focus === 'function') {
      el.focus()
    } else if (el.$el instanceof HTMLElement) {
      const real = el.$el.tagName === 'TEXTAREA' ? el.$el : el.$el.querySelector('textarea')
      real?.focus()
    }
  },
})
</script>

<template>
  <div class="bg-transparent">
    <div class="mx-auto max-w-[1180px] space-y-3">
      <div
        v-if="selectedContexts.length > 0"
        class="context-active-strip"
        aria-label="本轮上下文"
      >
        <div class="context-active-strip__meta">
          <AtSign aria-hidden="true" />
          <span>{{ selectedContextSummary }}</span>
        </div>
        <TransitionGroup
          name="context-chip"
          tag="div"
          class="context-chip-list"
        >
          <div
            v-for="context in selectedContexts"
            :key="`${context.kind}:${context.id}`"
            class="context-chip"
            :class="{ 'context-chip--off': context.memoryMode === 'off' }"
            :data-context-mode="context.memoryMode ?? context.kind"
          >
            <component :is="getContextOptionIcon(context)" class="context-chip__icon" aria-hidden="true" />
            <span class="context-chip__name">
              {{ context.name }}
            </span>
            <span class="sr-only">{{ getContextKindLabel(context) }}</span>
            <button
              type="button"
              class="context-chip__remove"
              :aria-label="`移除上下文：${context.name}`"
              :title="`移除上下文：${context.name}`"
              @click="removeContext(context.kind, context.id)"
            >
              <X class="size-3" />
            </button>
          </div>
        </TransitionGroup>
      </div>

      <div v-if="attachments.length > 0" class="flex flex-wrap gap-2">
        <div
          v-for="(file, index) in attachments"
          :key="`${file.name}-${file.size}-${index}`"
          class="list-card inline-flex items-center gap-2 px-3 py-2 text-xs"
        >
          <component :is="getFileIcon(file)" class="size-3.5 text-muted-foreground" />
          <span class="max-w-[220px] truncate text-foreground">{{ file.name }}</span>
          <span class="text-muted-foreground">({{ formatFileSize(file.size) }})</span>
          <button
            type="button"
            class="rounded-full p-1 text-muted-foreground transition-colors hover:bg-muted/80 hover:text-destructive"
            :aria-label="`移除附件：${file.name}`"
            :title="`移除附件：${file.name}`"
            @click="removeAttachment(index)"
          >
            <X class="size-3" />
          </button>
        </div>
      </div>

      <!-- 上下文选择面板 -->
      <Transition
        enter-active-class="transition-all duration-200 ease-out"
        enter-from-class="translate-y-2 opacity-0"
        enter-to-class="translate-y-0 opacity-100"
        leave-active-class="transition-all duration-150 ease-in"
        leave-from-class="translate-y-0 opacity-100"
        leave-to-class="translate-y-2 opacity-0"
      >
        <div
          v-if="showContextPicker"
          class="context-picker-panel p-sm"
        >
          <div class="context-picker-heading">
            <div class="context-picker-title">{{ pickerTitle }}</div>
            <div class="context-picker-description">{{ contextPickerDescription }}</div>
          </div>

          <!-- 搜索框 -->
          <div class="mb-sm">
            <div class="relative">
              <Search class="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
              <Input
                v-model="manualContextQuery"
                aria-label="搜索上下文"
                placeholder="搜索记忆或知识库..."
                class="h-8 rounded-xl bg-background/60 pl-8 text-xs"
              />
            </div>
          </div>

          <!-- 列表 -->
          <div class="max-h-[240px] space-y-0.5 overflow-y-auto scrollbar-thin">
            <button
              v-for="option in filteredContextOptions"
              :key="`${option.kind}:${option.id}`"
              type="button"
              class="context-option-row"
              :aria-label="`引用上下文：${option.name}`"
              @click="selectContext(option)"
            >
              <component
                :is="getContextOptionIcon(option)"
                class="size-4 shrink-0 text-muted-foreground"
              />
              <span class="truncate text-sm text-foreground">{{ option.name }}</span>
              <span class="ml-auto shrink-0 text-[10px] text-muted-foreground">
                {{ getContextKindLabel(option) }}
              </span>
            </button>

            <div
              v-if="filteredContextOptions.length === 0"
              class="px-sm py-md text-center text-xs text-muted-foreground"
            >
              {{ manualContextQuery ? '没有匹配结果' : '还没有可引用的上下文' }}
            </div>
          </div>

          <!-- 关闭 -->
          <div class="mt-sm flex justify-end">
            <button
              type="button"
              class="text-xs text-muted-foreground transition-colors hover:text-foreground"
              @click="manualContextPickerOpen = false; manualContextQuery = ''"
            >
              关闭
            </button>
          </div>
        </div>
      </Transition>

      <div
        class="chat-composer-shell overflow-hidden rounded-[24px] border transition-all duration-200"
        :class="[
          dragActive
            ? 'border-primary/40 bg-primary/[0.02]'
            : 'border-border/40 focus-within:border-border/60',
        ]"
        @dragenter="handleDragEnter"
        @dragover.prevent
        @dragleave="handleDragLeave"
        @drop.prevent="handleDrop"
      >
        <!-- 输入区 -->
        <div class="relative px-4 pb-1 pt-2">
          <Textarea
            ref="textareaRef"
            v-model="input"
            :disabled="disabled"
            :maxlength="maxLength"
            :placeholder="placeholder || randomPlaceholder"
            rows="1"
            class="min-h-0 max-h-[220px] resize-none border-0 bg-transparent px-0 text-[15px] leading-relaxed shadow-none placeholder:text-muted-foreground/45 focus-visible:ring-0"
            @keydown="handleKeydown"
            @paste="handlePaste"
          />
          <!-- 字数统计：输入区右下角 -->
          <span
            v-if="inputLength > 0"
            class="absolute right-4 bottom-2 text-[11px] tabular-nums text-muted-foreground/50"
          >
            {{ inputLength }} / {{ maxLength }}
          </span>
        </div>

        <!-- 工具栏 -->
        <div class="flex items-center justify-between gap-2 px-3 pb-2.5 pt-1">
          <div class="flex flex-wrap items-center gap-0.5">
            <button
              type="button"
              class="relative flex size-8 items-center justify-center rounded-[10px] transition-colors disabled:opacity-30"
              :class="selectedContextCount > 0 || showContextPicker
                ? 'text-primary hover:bg-primary/8'
                : 'text-muted-foreground/70 hover:bg-accent/50 hover:text-foreground'"
              :disabled="disabled"
              aria-label="选择上下文"
              title="上下文"
              @click="toggleManualContextPicker"
            >
              <AtSign class="size-4" />
              <span
                v-if="selectedContexts.length > 0"
                class="absolute -right-0.5 -top-0.5 flex size-3.5 items-center justify-center rounded-full bg-primary text-[9px] font-medium text-primary-foreground"
              >
                {{ selectedContexts.length }}
              </span>
            </button>

            <!-- 附件 -->
            <button
              type="button"
              class="relative flex size-8 items-center justify-center rounded-[10px] text-muted-foreground/70 transition-colors hover:bg-accent/50 hover:text-foreground disabled:opacity-30"
              :disabled="disabled"
              aria-label="添加附件"
              title="附件"
              @click="handleFileSelect"
            >
              <Paperclip class="size-4" />
              <span
                v-if="attachments.length > 0"
                class="absolute -right-0.5 -top-0.5 flex size-3.5 items-center justify-center rounded-full bg-primary text-[9px] font-medium text-primary-foreground"
              >
                {{ attachments.length }}
              </span>
            </button>

            <input
              ref="fileInput"
              type="file"
              multiple
              accept="image/*,audio/*,video/*,.pdf,.txt,.md,.csv,.doc,.docx,.xls,.xlsx,.ppt,.pptx"
              class="hidden"
              @change="handleFileChange"
            />
          </div>

          <div class="flex items-center gap-2">
            <!-- 录音中：波形 + 时长 + 停止按钮 -->
            <template v-if="isRecording">
              <AudioWaveform :analyser-node="analyserNode" :is-active="isRecording" />
              <span class="text-xs font-mono text-destructive">{{ formatDuration(recordingDuration) }}</span>
              <button
                type="button"
                class="flex size-9 items-center justify-center rounded-full bg-destructive text-destructive-foreground shadow-sm transition-all hover:-translate-y-0.5 active:scale-95"
                aria-label="停止录音"
                title="停止录音"
                @click="stopRecording"
              >
                <Square class="size-3.5" />
              </button>
            </template>

            <template v-else>
              <!-- 麦克风按钮 -->
              <button
                v-if="voiceSupported"
                type="button"
                :disabled="disabled || streaming || isUploading || voiceSending"
                class="flex size-8 items-center justify-center rounded-[10px] text-muted-foreground/60 transition-all duration-150 hover:text-foreground active:scale-95 disabled:cursor-not-allowed disabled:opacity-30"
                aria-label="语音输入"
                title="语音输入"
                @click="handleMicClick"
              >
                <Mic class="size-[18px]" />
              </button>

              <!-- 发送/停止按钮 -->
              <span class="relative inline-flex">
                <button
                  v-if="streaming"
                  type="button"
                  aria-label="停止生成"
                  title="停止生成"
                  class="relative z-[1] flex size-8 items-center justify-center rounded-full bg-foreground text-background shadow-sm transition-all duration-150 hover:brightness-110 active:scale-95"
                  @click="emit('stop')"
                >
                  <Square class="size-3.5" />
                </button>
                <button
                  v-else
                  type="button"
                  :disabled="sendDisabled"
                  aria-label="发送消息"
                  title="发送消息"
                  class="relative z-[1] flex size-8 items-center justify-center rounded-full transition-all duration-150 disabled:cursor-not-allowed"
                  :class="sendDisabled
                    ? 'bg-muted/50 text-muted-foreground/30'
                    : 'bg-primary text-primary-foreground shadow-sm hover:brightness-110 active:scale-95'"
                  @click="submit"
                >
                  <ArrowUp class="size-4" :stroke-width="2.5" />
                </button>
                <span
                  v-if="sendPulsing"
                  class="send-pulse-ring"
                />
              </span>
            </template>
          </div>
        </div>
      </div>

      <!-- 错误提示 -->
      <div
        v-if="uploadError"
        class="rounded-lg border border-destructive/20 bg-destructive/5 px-3 py-2 text-xs text-destructive"
      >
        {{ uploadError }}
      </div>
      <div
        v-if="voiceError"
        class="rounded-lg border border-destructive/20 bg-destructive/5 px-3 py-2 text-xs text-destructive"
      >
        {{ voiceError }}
      </div>

      <!-- 状态提示：仅在上传/语音发送时显示 -->
      <div v-if="isUploading || voiceSending" class="px-1 text-[11px] text-muted-foreground/60">
        <span v-if="isUploading">正在处理附件…</span>
        <span v-if="voiceSending">正在发送语音…</span>
      </div>
    </div>

    <!-- Whisper 语音引擎下载确认弹窗 -->
    <ConfirmDialog
      v-model:show="showWhisperConfirm"
      title="需要下载语音引擎"
      message="语音输入功能需要 Whisper 语音识别引擎（约 200 MB），是否立即下载？"
      confirm-label="开始下载"
      cancel-label="暂不需要"
      @confirm="confirmWhisperDownload"
    />
  </div>
</template>

<style scoped>
.context-active-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.4rem 0.6rem;
  padding: 0 0.25rem;
}

.context-active-strip__meta {
  display: inline-flex;
  min-height: 1.75rem;
  flex: 0 0 auto;
  align-items: center;
  gap: 0.35rem;
  color: hsl(from var(--muted-foreground) h s l / 0.82);
  font-size: 0.72rem;
  line-height: 1.35;
}

.context-active-strip__meta svg {
  width: 0.9rem;
  height: 0.9rem;
  color: hsl(from var(--primary) h s l / 0.78);
}

.context-chip-list {
  display: flex;
  min-width: 0;
  flex: 1 1 14rem;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.context-chip {
  display: inline-flex;
  min-width: 0;
  max-width: min(16rem, 100%);
  min-height: 1.9rem;
  align-items: center;
  gap: 0.35rem;
  border: 1px solid hsl(from var(--border) h s l / 0.56);
  border-radius: 999px;
  background: hsl(from var(--card) h s l / 0.7);
  padding: 0.1rem 0.2rem 0.1rem 0.55rem;
  color: var(--foreground);
  font-size: 0.76rem;
}

.context-chip__icon {
  width: 0.82rem;
  height: 0.82rem;
  flex: 0 0 auto;
  color: hsl(from var(--primary) h s l / 0.82);
}

.context-chip--off {
  background: hsl(from var(--muted) h s l / 0.58);
  color: hsl(from var(--muted-foreground) h s l / 0.94);
}

.context-chip--off .context-chip__icon {
  color: hsl(from var(--muted-foreground) h s l / 0.82);
}

.context-chip__name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.context-chip__remove {
  display: inline-flex;
  width: 1.5rem;
  height: 1.5rem;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: hsl(from var(--muted-foreground) h s l / 0.8);
  cursor: pointer;
  transition:
    background-color 160ms var(--ease-fluid),
    color 160ms var(--ease-fluid);
}

.context-chip__remove:hover {
  background: hsl(from var(--muted) h s l / 0.82);
  color: var(--destructive);
}

.context-chip__remove:focus-visible {
  outline: 2px solid var(--ring);
  outline-offset: 2px;
}

.context-picker-panel {
  position: relative;
  overflow: hidden;
  border: 1px solid hsl(from var(--border) h s l / 0.62);
  border-radius: 1.1rem;
  background: var(--card);
  box-shadow: 0 8px 22px -18px hsl(var(--shadow-color) / 0.16);
}

.context-picker-heading {
  margin-bottom: 0.75rem;
  padding: 0 0.1rem;
}

.context-picker-title {
  color: var(--foreground);
  font-size: 0.82rem;
  font-weight: 600;
  line-height: 1.35;
}

.context-picker-description {
  margin-top: 0.1rem;
  color: hsl(from var(--muted-foreground) h s l / 0.78);
  font-size: 0.72rem;
  line-height: 1.4;
}

.context-option-row {
  display: flex;
  width: 100%;
  min-height: 2.25rem;
  align-items: center;
  gap: 0.75rem;
  border: 0;
  border-radius: 0.8rem;
  background: transparent;
  color: inherit;
  cursor: pointer;
  padding: 0.38rem 0.55rem;
  text-align: left;
  transition:
    background-color 160ms var(--ease-fluid),
    color 160ms var(--ease-fluid);
}

.context-option-row:hover {
  background: hsl(from var(--accent) h s l / 0.5);
}

.context-option-row:focus-visible {
  outline: 2px solid var(--ring);
  outline-offset: 2px;
}

.chat-send-ready {
  animation: send-button-breathe 1.7s var(--ease-fluid) infinite;
}

.chat-composer-shell {
  position: relative;
  background: var(--card);
}

.context-chip-enter-active,
.context-chip-leave-active {
  transition:
    transform 220ms var(--ease-fluid),
    opacity 180ms var(--ease-fluid);
}

.context-chip-enter-from,
.context-chip-leave-to {
  opacity: 0;
  transform: translateY(10px) scale(0.96);
}

.context-chip-move {
  transition: transform 220ms var(--ease-fluid);
}

.context-option-enter-active,
.context-option-leave-active {
  transition:
    transform 180ms var(--ease-fluid),
    opacity 160ms var(--ease-fluid);
}

.context-option-enter-from,
.context-option-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

.send-pulse-ring {
  position: absolute;
  inset: 0;
  z-index: 0;
  border-radius: 9999px;
  border: 2px solid hsl(from var(--primary) h s l / 0.6);
  animation: send-pulse-expand 500ms var(--ease-fluid) forwards;
  pointer-events: none;
}

@keyframes send-pulse-expand {
  0% {
    transform: scale(1);
    opacity: 0.7;
  }

  100% {
    transform: scale(2.2);
    opacity: 0;
  }
}

@keyframes send-button-breathe {
  0%,
  100% {
    transform: translateY(0);
    box-shadow: 0 12px 22px -14px hsl(var(--shadow-color) / 0.2);
  }

  50% {
    transform: translateY(-1px) scale(1.01);
    box-shadow: 0 14px 24px -14px hsl(var(--shadow-color) / 0.24);
  }
}

</style>
