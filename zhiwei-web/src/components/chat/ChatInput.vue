<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { FileAudio2, FileText, FileVideo, Image, Mic, Paperclip, Square, X } from 'lucide-vue-next'
import { chatApi } from '@/api/client'
import { useChatStore } from '@/stores/chat'
import { Textarea } from '@/components/ui/textarea'
import { useVoice } from '@/composables/useVoice'
import AudioWaveform from '@/components/chat/AudioWaveform.vue'
import type { ChatAttachment } from '@/types'

const props = defineProps<{
  disabled?: boolean
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

const sendDisabled = computed(() => props.disabled || !input.value.trim() || isUploading.value)
const attachmentSummary = computed(() => {
  if (attachments.value.length === 0) return '可附加图片、音频、视频或文档'
  return `已选 ${attachments.value.length} 个附件`
})
const footerHint = computed(() => {
  if (isUploading.value) return '正在上传附件，完成后会自动继续发送。'
  if (props.disabled) return '当前回复还在生成，稍候即可继续输入。'
  if (attachments.value.length > 0) return 'Enter 发送时会连同附件一起上传。'
  return '按 Enter 发送，Shift + Enter 换行。'
})

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void submit()
  }
}

async function submit() {
  const content = input.value.trim()
  if (!content || sendDisabled.value) return

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

  for (const file of Array.from(files)) {
    const duplicated = attachments.value.some(item => item.name === file.name && item.size === file.size)
    if (!duplicated) {
      attachments.value.push(file)
    }
  }

  target.value = ''
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
  <div class="bg-transparent px-4 py-4 sm:px-5">
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
        class="overflow-hidden rounded-[calc(var(--radius)+8px)] border border-border/70 bg-card/80 shadow-[0_24px_40px_-28px_hsl(var(--shadow-color)/0.42)] transition-all duration-200"
        :class="sendDisabled ? '' : 'hover:border-primary/24 focus-within:border-primary/24 focus-within:shadow-[0_28px_46px_-30px_hsl(var(--shadow-color)/0.5)]'"
      >
        <div class="px-3 pt-3">
          <Textarea
            v-model="input"
            :disabled="disabled"
            :maxlength="maxLength"
            placeholder="输入问题，或粘贴资料继续往下处理…"
            rows="1"
            class="min-h-[64px] max-h-[220px] resize-none border-0 bg-transparent px-1 text-base shadow-none focus-visible:ring-0"
            @keydown="handleKeydown"
            @click="showTemplates = false"
          />
        </div>

        <div class="border-t border-border/60 bg-background/38 px-3 py-3">
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div class="flex flex-wrap items-center gap-2">
              <div class="relative">
                <button
                  type="button"
                  class="filter-pill text-sm"
                  :disabled="disabled"
                  @click="showTemplates = !showTemplates"
                >
                  <FileText class="size-4" />
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
                    class="absolute bottom-full left-0 z-10 mb-2 w-52 rounded-[calc(var(--radius)+4px)] border border-border/70 bg-card/96 p-2 shadow-[0_20px_36px_-28px_hsl(var(--shadow-color)/0.45)]"
                  >
                    <div class="mb-2 px-2 text-[11px] font-medium tracking-[0.08em] text-muted-foreground">
                      常用模板
                    </div>
                    <button
                      v-for="template in promptTemplates"
                      :key="template.name"
                      type="button"
                      class="flex w-full items-center rounded-lg px-2 py-2 text-left text-sm text-foreground transition-colors hover:bg-accent/75"
                      @click="insertTemplate(template)"
                    >
                      {{ template.name }}
                    </button>
                  </div>
                </Transition>
              </div>

              <button
                type="button"
                class="filter-pill text-sm disabled:cursor-not-allowed disabled:opacity-50"
                :disabled="disabled"
                @click="handleFileSelect"
              >
                <Paperclip class="size-4" />
                附件
              </button>

              <span class="surface-chip">{{ attachmentSummary }}</span>

              <input
                ref="fileInput"
                type="file"
                multiple
                accept="image/*,audio/*,video/*,.pdf,.txt,.md,.csv,.doc,.docx,.xls,.xlsx,.ppt,.pptx"
                class="hidden"
                @change="handleFileChange"
              />
            </div>

            <div class="flex items-center gap-3 self-end sm:self-auto">
              <span class="text-xs text-muted-foreground">{{ inputLength }} / {{ maxLength }}</span>

              <!-- 录音中：波形 + 时长 + 停止按钮 -->
              <template v-if="isRecording">
                <AudioWaveform :analyser-node="analyserNode" :is-active="isRecording" />
                <span class="text-xs font-mono text-destructive">{{ formatDuration(recordingDuration) }}</span>
                <button
                  type="button"
                  class="flex h-11 w-11 items-center justify-center rounded-full border border-destructive bg-destructive text-destructive-foreground shadow-sm transition-all hover:-translate-y-0.5"
                  @click="stopRecording"
                >
                  <Square class="size-4" />
                </button>
              </template>

              <template v-else>
                <!-- 麦克风按钮 -->
                <button
                  v-if="voiceSupported"
                  type="button"
                  :disabled="disabled || isUploading || voiceSending"
                  class="flex h-11 w-11 items-center justify-center rounded-full border transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                  :class="disabled || isUploading || voiceSending
                    ? 'border-border bg-muted text-muted-foreground'
                    : 'border-border bg-card text-foreground hover:-translate-y-0.5 hover:border-primary/40 hover:text-primary'"
                  @click="startRecording"
                >
                  <Mic class="size-4" />
                </button>

                <!-- 发送按钮 -->
                <button
                type="button"
                :disabled="sendDisabled"
                class="flex h-11 w-11 items-center justify-center rounded-full border transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                :class="sendDisabled
                  ? 'border-border bg-muted text-muted-foreground'
                  : 'border-primary bg-primary text-primary-foreground shadow-[0_16px_28px_-18px_hsl(var(--shadow-color)/0.45)] hover:-translate-y-0.5 hover:bg-primary/92'"
                @click="submit"
              >
                <svg
                  xmlns="http://www.w3.org/2000/svg"
                  width="18"
                  height="18"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  class="-translate-y-0.5 translate-x-0.5 -rotate-45"
                >
                  <path d="m22 2-7 20-4-9-9-4Z" />
                  <path d="M22 2 11 13" />
                </svg>
                </button>
              </template>
            </div>
          </div>
        </div>
      </div>

      <div
        v-if="uploadError"
        class="rounded-[calc(var(--radius)+4px)] border border-destructive/20 bg-destructive/6 px-3 py-2 text-xs text-destructive"
      >
        {{ uploadError }}
      </div>

      <div
        v-if="voiceError"
        class="rounded-[calc(var(--radius)+4px)] border border-destructive/20 bg-destructive/6 px-3 py-2 text-xs text-destructive"
      >
        {{ voiceError }}
      </div>

      <div class="flex flex-wrap items-center justify-between gap-2 px-1 text-[11px] text-muted-foreground">
        <p>{{ footerHint }}</p>
        <span v-if="isUploading" class="surface-chip surface-chip-strong">正在上传附件</span>
        <span v-if="voiceSending" class="surface-chip surface-chip-strong">正在发送语音消息</span>
      </div>
    </div>
  </div>
</template>
