<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  ArrowUp,
  AtSign,
  CornerDownLeft,
  Database,
  FileAudio2,
  FileText,
  FileVideo,
  Image,
  LibraryBig,
  Mic,
  Paperclip,
  Search,
  Sparkles,
  Square,
  X,
} from 'lucide-vue-next'
import { chatApi } from '@/api/client'
import { useChatStore } from '@/stores/chat'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { useVoice } from '@/composables/useVoice'
import { useWhisperDownload } from '@/composables/useWhisperDownload'
import AudioWaveform from '@/components/chat/AudioWaveform.vue'
import type { ChatAttachment, Datastore, KnowledgeBase, SessionConfig } from '@/types'

const props = defineProps<{
  disabled?: boolean
  placeholder?: string
  continuationTitle?: string | null
  continuationDetail?: string | null
  knowledgeBases?: KnowledgeBase[]
  datastores?: Datastore[]
  baseSessionConfig?: SessionConfig
}>()

const emit = defineEmits<{
  send: [{
    content: string
    attachmentIds?: string[]
    attachments?: ChatAttachment[]
    sessionConfig?: SessionConfig
    restoreSessionConfig?: SessionConfig
  }]
}>()

const chatStore = useChatStore()
const input = ref('')
const maxLength = 4000
const inputLength = computed(() => input.value.length)

const attachments = ref<File[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const isUploading = ref(false)
const uploadError = ref<string | null>(null)
const dragDepth = ref(0)
const dragActive = computed(() => dragDepth.value > 0)

const showTemplates = ref(false)
const voiceSending = ref(false)
const voiceError = ref<string | null>(null)
const manualContextPickerOpen = ref(false)
const manualContextQuery = ref('')
const selectedContexts = ref<Array<{
  id: string
  name: string
  description?: string | null
  kind: 'datastore' | 'knowledge-base'
}>>([])

// 语音录音
const {
  isRecording, recordingDuration, audioBlob, isSupported: voiceSupported, analyserNode,
  startRecording, stopRecording,
} = useVoice()

// Whisper 语音引擎自动下载（桌面端）
const { checkAvailability: checkWhisper, triggerDownload: triggerWhisperDownload } = useWhisperDownload()

/** 麦克风按钮点击：检测 Whisper 可用性，不可用则触发下载，可用则录音 */
async function handleMicClick() {
  if (!('__TAURI_INTERNALS__' in window)) {
    // 浏览器环境直接录音
    startRecording()
    return
  }
  const ok = await checkWhisper()
  if (ok) {
    startRecording()
  } else {
    triggerWhisperDownload()
  }
}

const promptTemplates = [
  { name: '整理要点', content: '请帮我整理以下内容的重点、结论和待办：\n\n' },
  { name: '翻译成英文', content: '请将以下内容翻译成英文，保留原意和结构：\n\n' },
  { name: '重写表达', content: '请帮我改写以下内容，让表达更清楚、更精炼：\n\n' },
  { name: '代码审查', content: '请审查以下代码，指出潜在问题和改进建议：\n\n' },
  { name: '解释概念', content: '请解释以下概念，并补充一个贴近实际的例子：\n\n' },
] as const

const sendDisabled = computed(() => props.disabled || isUploading.value || (!input.value.trim() && attachments.value.length === 0))
const mentionQuery = computed(() => {
  const match = input.value.match(/(?:^|\s)@([^\s@]*)$/)
  return match ? match[1] ?? '' : null
})
const contextQuery = computed(() => (
  manualContextPickerOpen.value ? manualContextQuery.value.trim() : (mentionQuery.value ?? '').trim()
))
const showContextPicker = computed(() => manualContextPickerOpen.value || mentionQuery.value !== null)
const allContextOptions = computed(() => [
  ...(props.datastores ?? []).map(datastore => ({
    id: datastore.id,
    name: datastore.name,
    description: datastore.description,
    kind: 'datastore' as const,
  })),
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
const filteredDatastores = computed(() => filteredContextOptions.value.filter(option => option.kind === 'datastore'))
const filteredKnowledgeBases = computed(() => filteredContextOptions.value.filter(option => option.kind === 'knowledge-base'))
const pickerTitle = computed(() => (
  manualContextPickerOpen.value
    ? '引用上下文'
    : `@ ${mentionQuery.value ?? ''}`.trim()
))
const selectedContextCount = computed(() => selectedContexts.value.length)
const contextPickerDescription = computed(() => (
  selectedContextCount.value > 0
    ? `已选 ${selectedContextCount.value} 项，仅对当前消息生效。`
    : '仅对当前消息生效。'
))

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    if (mentionQuery.value !== null && filteredContextOptions.value.length > 0) {
      event.preventDefault()
      selectContext(filteredContextOptions.value[0])
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
  if ((!content && attachments.value.length === 0) || sendDisabled.value) return

  let attachmentIds: string[] | undefined
  let uploadedAttachments: ChatAttachment[] | undefined

  if (attachments.value.length > 0) {
    isUploading.value = true
    uploadError.value = null

    try {
      const uploaded = await Promise.all(
        attachments.value.map(file => chatApi.uploadAttachment(file, chatStore.activeSessionId ?? undefined)),
      )
      attachmentIds = uploaded.map(item => item.fileId)
      uploadedAttachments = uploaded
    } catch (error) {
      console.error('附件上传失败:', error)
      uploadError.value = error instanceof Error ? error.message : '附件上传失败，请重试或移除附件'
      return
    } finally {
      isUploading.value = false
    }
  }

  const sessionConfig = buildTemporarySessionConfig()
  const restoreSessionConfig = sessionConfig ? buildRestoreSessionConfig() : undefined

  emit('send', {
    content,
    attachmentIds,
    attachments: uploadedAttachments,
    sessionConfig,
    restoreSessionConfig,
  })

  input.value = ''
  attachments.value = []
  uploadError.value = null
  showTemplates.value = false
  resetTemporaryContextSelection()
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

function insertTemplate(template: (typeof promptTemplates)[number]) {
  input.value = template.content + input.value
  showTemplates.value = false
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
  kind: 'datastore' | 'knowledge-base'
}) {
  if (!selectedContexts.value.some(selected => selected.kind === option.kind && selected.id === option.id)) {
    selectedContexts.value.push(option)
  }
  if (mentionQuery.value !== null) {
    removeTrailingMention()
  }
  manualContextPickerOpen.value = false
  manualContextQuery.value = ''
}

function removeContext(kind: 'datastore' | 'knowledge-base', id: string) {
  selectedContexts.value = selectedContexts.value.filter(option => !(option.kind === kind && option.id === id))
}

function removeTrailingMention() {
  input.value = input.value.replace(/(?:^|\s)@[^\s@]*$/, matched => matched.startsWith(' ') ? ' ' : '')
  input.value = input.value.replace(/\s{2,}$/g, ' ')
  if (input.value === ' ') {
    input.value = ''
  }
}

function buildTemporarySessionConfig(): SessionConfig | undefined {
  if (selectedContexts.value.length === 0 || !props.baseSessionConfig) {
    return undefined
  }

  const knowledgeBaseIds = mergeIds(
    props.baseSessionConfig.knowledgeBaseIds ?? [],
    selectedContexts.value.filter(option => option.kind === 'knowledge-base').map(option => option.id),
  )
  const datastoreIds = mergeIds(
    props.baseSessionConfig.datastoreIds ?? [],
    selectedContexts.value.filter(option => option.kind === 'datastore').map(option => option.id),
  )

  return {
    preferredProviderId: props.baseSessionConfig.preferredProviderId,
    temperature: props.baseSessionConfig.temperature,
    maxSteps: props.baseSessionConfig.maxSteps,
    maxDurationSeconds: props.baseSessionConfig.maxDurationSeconds,
    knowledgeBaseIds,
    datastoreIds,
  }
}

function buildRestoreSessionConfig(): SessionConfig | undefined {
  if (!props.baseSessionConfig) {
    return undefined
  }

  return {
    preferredProviderId: props.baseSessionConfig.preferredProviderId,
    temperature: props.baseSessionConfig.temperature,
    maxSteps: props.baseSessionConfig.maxSteps,
    maxDurationSeconds: props.baseSessionConfig.maxDurationSeconds,
    knowledgeBaseIds: props.baseSessionConfig.knowledgeBaseIds ?? [],
    datastoreIds: props.baseSessionConfig.datastoreIds ?? [],
  }
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

function getContextIcon(kind: 'datastore' | 'knowledge-base') {
  return kind === 'datastore' ? Database : LibraryBig
}

function getContextKindLabel(kind: 'datastore' | 'knowledge-base') {
  return kind === 'datastore' ? 'Datastore' : '知识库'
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
    const file = new File([blob], `voice-${Date.now()}.webm`, { type: blob.type })
    const sessionId = chatStore.activeSessionId ?? undefined
    const uploaded = await chatApi.uploadAttachment(file, sessionId)
    const sessionConfig = buildTemporarySessionConfig()
    const restoreSessionConfig = sessionConfig ? buildRestoreSessionConfig() : undefined

    emit('send', {
      content: '[语音消息]',
      attachmentIds: [uploaded.fileId],
      attachments: [uploaded],
      sessionConfig,
      restoreSessionConfig,
    })
    resetTemporaryContextSelection()
  } catch (error) {
    console.error('语音消息上传失败:', error)
    voiceError.value = error instanceof Error ? error.message : '语音消息上传失败，请重试'
  } finally {
    voiceSending.value = false
  }
})

defineExpose({
  input,
  isUploading,
  getFileIcon,
})
</script>

<template>
  <div class="bg-transparent">
    <div class="mx-auto max-w-[1180px] space-y-3">
      <TransitionGroup
        v-if="selectedContexts.length > 0"
        name="context-chip"
        tag="div"
        class="flex flex-wrap gap-2"
      >
        <div
          v-for="context in selectedContexts"
          :key="`${context.kind}:${context.id}`"
          class="context-chip-card inline-flex items-center gap-2.5 px-3 py-2 text-xs"
        >
          <span
            class="context-chip-icon"
            :class="context.kind === 'datastore'
              ? 'bg-sky-500/12 text-sky-600'
              : 'bg-primary/12 text-primary'"
          >
            <component :is="getContextIcon(context.kind)" class="size-3.5" />
          </span>
          <div class="min-w-0">
            <div class="text-[10px] font-semibold text-muted-foreground">
              {{ getContextKindLabel(context.kind) }}
            </div>
            <div class="max-w-[220px] truncate text-xs font-medium text-foreground">
              {{ context.name }}
            </div>
          </div>
          <button
            type="button"
            class="rounded-full p-1 text-muted-foreground transition-colors hover:bg-muted/80 hover:text-destructive"
            @click="removeContext(context.kind, context.id)"
          >
            <X class="size-3" />
          </button>
        </div>
      </TransitionGroup>

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
            @click="removeAttachment(index)"
          >
            <X class="size-3" />
          </button>
        </div>
      </div>

      <Transition
        enter-active-class="transition-all duration-150 ease-out"
        enter-from-class="translate-y-1 opacity-0"
        enter-to-class="translate-y-0 opacity-100"
        leave-active-class="transition-all duration-100 ease-in"
        leave-from-class="translate-y-0 opacity-100"
        leave-to-class="translate-y-1 opacity-0"
      >
        <div
          v-if="showContextPicker"
          class="chat-context-panel context-picker-panel rounded-[1.05rem] border border-border/58 bg-card/92 p-3 shadow-[0_14px_24px_-22px_hsl(var(--shadow-color)/0.12)]"
        >
          <div class="mb-3 flex items-center justify-between gap-3">
            <div class="flex min-w-0 items-center gap-3">
              <div class="flex size-8 shrink-0 items-center justify-center rounded-[0.9rem] bg-primary/8 text-primary">
                <AtSign class="size-4" />
              </div>
              <div class="min-w-0">
                <div class="text-sm font-medium text-foreground">{{ pickerTitle }}</div>
                <div class="text-[11px] text-muted-foreground">
                  {{ contextPickerDescription }}
                </div>
              </div>
            </div>
            <div class="flex items-center gap-2">
              <span
                v-if="selectedContextCount > 0"
                class="inline-flex items-center gap-1 rounded-full bg-primary/8 px-2 py-1 text-[10px] font-semibold text-primary"
              >
                <Sparkles class="size-3" />
                {{ selectedContextCount }}
              </span>
              <button
                v-if="manualContextPickerOpen"
                type="button"
                class="rounded-full p-1 text-muted-foreground transition-colors hover:bg-muted/80 hover:text-foreground"
                @click="toggleManualContextPicker"
              >
                <X class="size-3.5" />
              </button>
            </div>
          </div>

          <div v-if="manualContextPickerOpen" class="mb-3">
            <div class="relative">
              <Search class="pointer-events-none absolute left-3 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
              <Input
                v-model="manualContextQuery"
                placeholder="搜索资料"
                class="h-9 rounded-full bg-background/70 pl-9 text-sm"
              />
            </div>
          </div>

          <div class="grid gap-3 md:grid-cols-2">
            <section class="space-y-2">
              <div class="text-[11px] font-medium uppercase tracking-[0.16em] text-muted-foreground">
                Datastore
              </div>
              <TransitionGroup
                v-if="filteredDatastores.length > 0"
                name="context-option"
                tag="div"
                class="space-y-1.5"
              >
                <button
                  v-for="option in filteredDatastores"
                  :key="`${option.kind}:${option.id}`"
                  type="button"
                  class="context-option-card"
                  @click="selectContext(option)"
                >
                  <div class="flex min-w-0 items-start gap-3">
                    <span class="context-option-icon bg-sky-500/12 text-sky-600">
                      <Database class="size-4" />
                    </span>
                    <div class="min-w-0">
                      <div class="truncate text-sm font-medium text-foreground">{{ option.name }}</div>
                      <div v-if="option.description" class="line-clamp-2 text-xs leading-5 text-muted-foreground">
                        {{ option.description }}
                      </div>
                    </div>
                  </div>
                  <div class="shrink-0 text-right">
                    <div class="text-[10px] font-semibold text-sky-600">Datastore</div>
                    <div class="text-[10px] text-muted-foreground">{{ option.id }}</div>
                  </div>
                </button>
              </TransitionGroup>
              <div v-else class="rounded-xl border border-dashed border-border/60 px-3 py-4 text-xs text-muted-foreground">
                没有结果。
              </div>
            </section>

            <section class="space-y-2">
              <div class="text-[11px] font-medium uppercase tracking-[0.16em] text-muted-foreground">
                Knowledge Base
              </div>
              <TransitionGroup
                v-if="filteredKnowledgeBases.length > 0"
                name="context-option"
                tag="div"
                class="space-y-1.5"
              >
                <button
                  v-for="option in filteredKnowledgeBases"
                  :key="`${option.kind}:${option.id}`"
                  type="button"
                  class="context-option-card"
                  @click="selectContext(option)"
                >
                  <div class="flex min-w-0 items-start gap-3">
                    <span class="context-option-icon bg-primary/12 text-primary">
                      <LibraryBig class="size-4" />
                    </span>
                    <div class="min-w-0">
                      <div class="truncate text-sm font-medium text-foreground">{{ option.name }}</div>
                      <div v-if="option.description" class="line-clamp-2 text-xs leading-5 text-muted-foreground">
                        {{ option.description }}
                      </div>
                    </div>
                  </div>
                  <div class="shrink-0 text-right">
                    <div class="text-[10px] font-semibold text-primary">知识库</div>
                    <div class="text-[10px] text-muted-foreground">{{ option.id }}</div>
                  </div>
                </button>
              </TransitionGroup>
              <div v-else class="rounded-xl border border-dashed border-border/60 px-3 py-4 text-xs text-muted-foreground">
                没有结果。
              </div>
            </section>
          </div>

          <div v-if="!manualContextPickerOpen" class="mt-3 rounded-[0.95rem] bg-background/74 px-3 py-2 text-[11px] text-muted-foreground">
            输入 <span class="font-semibold text-foreground">@</span> 可快速引用资料，也可以直接点下面的“上下文”按钮选择。
          </div>
        </div>
      </Transition>

      <div
        class="chat-composer-shell overflow-hidden rounded-[1.35rem] border border-border/52 bg-card/82 transition-all duration-200"
        :class="[
          sendDisabled ? '' : 'focus-within:border-primary/24 focus-within:shadow-[0_16px_26px_-22px_hsl(var(--shadow-color)/0.12)]',
          dragActive ? 'border-primary/60 bg-primary/[0.04] shadow-[0_14px_24px_-18px_hsl(var(--primary)/0.14)]' : '',
        ]"
        @dragenter="handleDragEnter"
        @dragover.prevent
        @dragleave="handleDragLeave"
        @drop.prevent="handleDrop"
      >
        <div
          v-if="continuationTitle"
          class="chat-composer-continuation flex items-start gap-3 border-b border-border/60 px-4 py-3"
        >
          <div class="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full bg-background/92 text-primary">
            <CornerDownLeft class="size-3.5" />
          </div>
          <div class="min-w-0">
            <div class="flex items-center gap-2">
              <span class="rounded-full bg-background/92 px-2 py-0.5 text-[10px] font-semibold tracking-[0.08em] text-primary">
                接续中
              </span>
              <span class="text-sm font-medium text-foreground">{{ continuationTitle }}</span>
            </div>
            <p
              v-if="continuationDetail"
              class="mt-1 text-xs leading-5 text-muted-foreground"
            >
              {{ continuationDetail }}
            </p>
          </div>
        </div>

        <!-- 输入区 -->
        <div class="relative px-4 pb-1 pt-3">
          <Textarea
            v-model="input"
            :disabled="disabled"
            :maxlength="maxLength"
            :placeholder="placeholder || '输入问题或贴资料…'"
            rows="1"
            class="min-h-[56px] max-h-[220px] resize-none border-0 bg-transparent px-0 text-[15px] leading-relaxed shadow-none placeholder:text-muted-foreground/55 focus-visible:ring-0"
            @keydown="handleKeydown"
            @click="showTemplates = false"
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
        <div class="flex items-center justify-between gap-2 border-t border-border/45 px-3 pb-2.5 pt-2.5">
          <div class="flex flex-wrap items-center gap-1.5">
            <!-- 模板 -->
            <div class="relative">
              <button
                type="button"
                class="inline-flex items-center gap-1.5 rounded-full border border-border/55 bg-background/68 px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-accent/60 hover:text-foreground disabled:opacity-40"
                :disabled="disabled"
                @click="showTemplates = !showTemplates"
              >
                <FileText class="size-3.5" />
                模板
              </button>

              <Transition
                enter-active-class="transition-all duration-200 ease-out"
                enter-from-class="translate-y-2 scale-95 opacity-0"
                enter-to-class="translate-y-0 scale-100 opacity-100"
                leave-active-class="transition-all duration-150 ease-in"
                leave-from-class="translate-y-0 scale-100 opacity-100"
                leave-to-class="translate-y-2 scale-95 opacity-0"
              >
                <div
                  v-if="showTemplates"
                  class="absolute bottom-full left-0 z-10 mb-2 w-48 rounded-[0.95rem] border border-border/58 bg-card/96 p-1.5 shadow-[0_16px_28px_-20px_hsl(var(--shadow-color)/0.16)]"
                >
                  <div class="mb-1 px-2 pt-1 text-[10px] font-medium tracking-wider text-muted-foreground/70">
                    模板
                  </div>
                  <button
                    v-for="template in promptTemplates"
                    :key="template.name"
                    type="button"
                    class="flex w-full items-center rounded-lg px-2 py-1.5 text-left text-sm text-foreground transition-colors hover:bg-accent/60"
                    @click="insertTemplate(template)"
                  >
                    {{ template.name }}
                  </button>
                </div>
              </Transition>
            </div>

            <button
              type="button"
              class="inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs transition-colors disabled:opacity-40"
              :class="selectedContextCount > 0 || showContextPicker
                ? 'border-primary/22 bg-primary/8 text-primary hover:bg-primary/10'
                : 'border-border/55 bg-background/72 text-muted-foreground hover:bg-accent/56 hover:text-foreground'"
              :disabled="disabled"
              @click="toggleManualContextPicker"
            >
              <AtSign class="size-3.5" />
              上下文
              <span v-if="selectedContexts.length > 0" class="ml-0.5 rounded-full bg-primary/12 px-1.5 text-[10px] font-medium text-primary">
                {{ selectedContexts.length }}
              </span>
            </button>

            <!-- 附件 -->
            <button
              type="button"
              class="inline-flex items-center gap-1.5 rounded-full border border-border/55 bg-background/72 px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-accent/56 hover:text-foreground disabled:opacity-40"
              :disabled="disabled"
              @click="handleFileSelect"
            >
              <Paperclip class="size-3.5" />
              附件
              <span v-if="attachments.length > 0" class="ml-0.5 rounded-full bg-primary/12 px-1.5 text-[10px] font-medium text-primary">
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
                :disabled="disabled || isUploading || voiceSending"
                class="flex size-9 items-center justify-center rounded-[1rem] border border-border/55 bg-background/76 text-muted-foreground/70 transition-all duration-150 hover:bg-accent/48 hover:text-foreground active:scale-95 disabled:cursor-not-allowed disabled:opacity-30"
                @click="handleMicClick"
              >
                <Mic class="size-4" />
              </button>

              <!-- 发送按钮 -->
              <button
                type="button"
                :disabled="sendDisabled"
                class="flex size-10 items-center justify-center rounded-[1rem] transition-all duration-150 disabled:cursor-not-allowed disabled:opacity-30"
                :class="sendDisabled
                  ? 'border border-border/55 bg-muted/60 text-muted-foreground/40'
                  : 'chat-send-ready bg-primary text-primary-foreground shadow-[0_12px_22px_-14px_hsl(var(--shadow-color)/0.2)] hover:brightness-105 active:scale-95'"
                @click="submit"
              >
                <ArrowUp class="size-4" :stroke-width="2.5" />
              </button>
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
        <span v-if="isUploading">正在上传附件…</span>
        <span v-if="voiceSending">正在发送语音…</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.context-chip-card {
  position: relative;
  overflow: hidden;
  border: 1px solid hsl(from var(--border) h s l / 0.58);
  border-radius: 1rem;
  background: linear-gradient(180deg, hsl(from var(--card) h s l / 0.92), hsl(from var(--background) h s l / 0.8));
  box-shadow:
    0 8px 14px -20px hsl(var(--shadow-color) / 0.08),
    inset 0 1px 0 hsl(from var(--card) h s l / 0.68);
}

.context-chip-card::before {
  content: "";
  position: absolute;
  inset: 0 auto 0 0;
  width: 2px;
  background: linear-gradient(180deg, hsl(from var(--primary) h s l / 0.72), hsl(from var(--primary) h s l / 0.08));
}

.context-chip-icon {
  display: inline-flex;
  height: 2rem;
  width: 2rem;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 0.9rem;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--card) h s l / 0.78);
}

.context-picker-panel {
  position: relative;
  overflow: hidden;
}

.chat-context-panel {
  background: linear-gradient(180deg, hsl(from var(--card) h s l / 0.94), hsl(from var(--card) h s l / 0.9));
}

.context-picker-panel::before {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: linear-gradient(90deg, hsl(from var(--primary) h s l / 0.16), transparent 24%);
  opacity: 0.7;
}

.context-option-card {
  position: relative;
  display: flex;
  width: 100%;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
  overflow: hidden;
  border: 1px solid hsl(from var(--border) h s l / 0.46);
  border-radius: 1rem;
  background: linear-gradient(180deg, hsl(from var(--background) h s l / 0.72), hsl(from var(--background) h s l / 0.58));
  padding: 0.75rem;
  text-align: left;
  transition:
    transform 180ms var(--ease-fluid),
    border-color 180ms var(--ease-fluid),
    background-color 180ms var(--ease-fluid),
    box-shadow 180ms var(--ease-fluid);
}

.context-option-card:hover {
  transform: translateY(-1px);
  border-color: hsl(from var(--primary) h s l / 0.24);
  background: linear-gradient(180deg, hsl(from var(--card) h s l / 0.94), hsl(from var(--background) h s l / 0.84));
  box-shadow: 0 10px 16px -20px hsl(var(--shadow-color) / 0.08);
}

.context-option-icon {
  display: inline-flex;
  height: 2rem;
  width: 2rem;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 0.9rem;
}

.chat-send-ready {
  animation: send-button-breathe 1.7s var(--ease-fluid) infinite;
}

.chat-composer-shell {
  position: relative;
  background: linear-gradient(180deg, hsl(from var(--card) h s l / 0.94), hsl(from var(--card) h s l / 0.88));
  box-shadow:
    0 12px 22px -24px hsl(var(--shadow-color) / 0.1),
    inset 0 1px 0 hsl(from var(--card) h s l / 0.72);
}

.chat-composer-shell::before {
  content: "";
  position: absolute;
  inset: 0 0 auto 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, hsl(from var(--primary) h s l / 0.18) 18%, transparent 72%);
}

.chat-composer-continuation {
  background: linear-gradient(180deg, hsl(from var(--primary) h s l / 0.05), transparent);
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
