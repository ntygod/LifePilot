<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowUp, CornerDownLeft, FileAudio2, FileText, FileVideo, Image, Mic, Paperclip, Square, X } from 'lucide-vue-next'
import { chatApi } from '@/api/client'
import { useChatStore } from '@/stores/chat'
import { Textarea } from '@/components/ui/textarea'
import { useVoice } from '@/composables/useVoice'
import AudioWaveform from '@/components/chat/AudioWaveform.vue'
import type { ChatAttachment } from '@/types'

const props = defineProps<{
  disabled?: boolean
  placeholder?: string
  continuationTitle?: string | null
  continuationDetail?: string | null
}>()

const emit = defineEmits<{
  send: [{
    content: string
    attachmentIds?: string[]
    attachments?: ChatAttachment[]
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

// 语音录音
const {
  isRecording, recordingDuration, audioBlob, isSupported: voiceSupported, analyserNode,
  startRecording, stopRecording,
} = useVoice()

const promptTemplates = [
  { name: '整理要点', content: '请帮我整理以下内容的重点、结论和待办：\n\n' },
  { name: '翻译成英文', content: '请将以下内容翻译成英文，保留原意和结构：\n\n' },
  { name: '重写表达', content: '请帮我改写以下内容，让表达更清楚、更精炼：\n\n' },
  { name: '代码审查', content: '请审查以下代码，指出潜在问题和改进建议：\n\n' },
  { name: '解释概念', content: '请解释以下概念，并补充一个贴近实际的例子：\n\n' },
] as const

const sendDisabled = computed(() => props.disabled || isUploading.value || (!input.value.trim() && attachments.value.length === 0))

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
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

  emit('send', {
    content,
    attachmentIds,
    attachments: uploadedAttachments,
  })

  input.value = ''
  attachments.value = []
  uploadError.value = null
  showTemplates.value = false
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

function formatFileSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
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

    emit('send', {
      content: '[语音消息]',
      attachmentIds: [uploaded.fileId],
      attachments: [uploaded],
    })
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
  <div class="bg-transparent px-4 py-3 sm:px-5">
    <div class="mx-auto max-w-4xl space-y-3">
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

      <div
        class="overflow-hidden rounded-2xl border border-border/50 bg-card/60 shadow-[0_2px_12px_-4px_hsl(var(--shadow-color)/0.18)] backdrop-blur-sm transition-all duration-200"
        :class="[
          sendDisabled ? '' : 'focus-within:border-primary/30 focus-within:shadow-[0_4px_20px_-6px_hsl(var(--shadow-color)/0.28)]',
          dragActive ? 'border-primary/60 bg-primary/5 shadow-[0_8px_24px_-8px_hsl(var(--primary)/0.25)]' : '',
        ]"
        @dragenter="handleDragEnter"
        @dragover.prevent
        @dragleave="handleDragLeave"
        @drop.prevent="handleDrop"
      >
        <div
          v-if="continuationTitle"
          class="flex items-start gap-3 border-b border-border/60 bg-gradient-to-r from-primary/[0.07] via-primary/[0.04] to-transparent px-4 py-3"
        >
          <div class="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full bg-background/95 text-primary shadow-sm">
            <CornerDownLeft class="size-3.5" />
          </div>
          <div class="min-w-0">
            <div class="flex items-center gap-2">
              <span class="rounded-full bg-background/90 px-2 py-0.5 text-[10px] font-semibold tracking-[0.08em] text-primary">
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
        <div class="relative px-4 pt-3 pb-1">
          <Textarea
            v-model="input"
            :disabled="disabled"
            :maxlength="maxLength"
            :placeholder="placeholder || '输入问题，或粘贴资料继续往下处理…'"
            rows="1"
            class="min-h-[88px] max-h-[200px] resize-none border-0 bg-transparent px-0 text-[15px] leading-relaxed shadow-none placeholder:text-muted-foreground/50 focus-visible:ring-0"
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
        <div class="flex items-center justify-between gap-2 px-3 pb-3 pt-1">
          <div class="flex items-center gap-1.5">
            <!-- 模板 -->
            <div class="relative">
              <button
                type="button"
                class="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-accent/60 hover:text-foreground disabled:opacity-40"
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
                  class="absolute bottom-full left-0 z-10 mb-2 w-48 rounded-xl border border-border/60 bg-card p-1.5 shadow-lg"
                >
                  <div class="mb-1 px-2 pt-1 text-[10px] font-medium tracking-wider text-muted-foreground/70">
                    常用模板
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

            <!-- 附件 -->
            <button
              type="button"
              class="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-accent/60 hover:text-foreground disabled:opacity-40"
              :disabled="disabled"
              @click="handleFileSelect"
            >
              <Paperclip class="size-3.5" />
              附件
              <span v-if="attachments.length > 0" class="ml-0.5 rounded-full bg-primary/15 px-1.5 text-[10px] font-medium text-primary">
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
                class="flex size-8 items-center justify-center rounded-full text-muted-foreground/60 transition-all duration-150 hover:bg-accent/50 hover:text-foreground active:scale-95 disabled:cursor-not-allowed disabled:opacity-30"
                @click="startRecording"
              >
                <Mic class="size-4" />
              </button>

              <!-- 发送按钮 -->
              <button
                type="button"
                :disabled="sendDisabled"
                class="flex size-8 items-center justify-center rounded-lg transition-all duration-150 disabled:cursor-not-allowed disabled:opacity-30"
                :class="sendDisabled
                  ? 'bg-muted/60 text-muted-foreground/40'
                  : 'bg-primary text-primary-foreground shadow-sm hover:brightness-110 active:scale-95'"
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
