<script setup lang="ts">
import { computed, ref } from 'vue'
import { Paperclip, FileText, X, ChevronDown, ChevronUp, Settings, Image, FileAudio2, FileVideo } from 'lucide-vue-next'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { chatApi } from '@/api/client'
import type { ChatAttachment } from '@/types'

const props = defineProps<{
  disabled?: boolean
}>()

const emit = defineEmits<{
  send: [{ content: string; attachmentIds?: string[]; attachments?: ChatAttachment[] }]
}>()

const kbStore = useKnowledgeBaseStore()
const input = ref('')
// 基础长度限制：主要防止一次性粘贴超长内容导致请求失败
const maxLength = 4000
const inputLength = computed(() => input.value.length)

// 附件相关（本地选中的文件列表）
const attachments = ref<File[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const isUploading = ref(false)
const uploadError = ref<string | null>(null)

// Prompt模板相关
const showTemplates = ref(false)
const promptTemplates = [
  { name: '总结', content: '请帮我总结以下内容：\n\n' },
  { name: '翻译', content: '请将以下内容翻译成英文：\n\n' },
  { name: '改写', content: '请帮我改写以下内容，使其更加简洁明了：\n\n' },
  { name: '代码审查', content: '请审查以下代码，指出潜在问题和改进建议：\n\n' },
  { name: '解释', content: '请详细解释以下概念：\n\n' }
]

// 上下文配置相关
const showContextConfig = ref(false)
const contextConfig = ref({
  model: '',
  temperature: 0.7,
  maxTokens: 2000,
  knowledgeBases: [] as string[]
})

function handleKeydown(e: KeyboardEvent) {
  // Enter 发送，Shift+Enter 换行
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}

async function submit() {
  const content = input.value.trim()
  if (!content || props.disabled || isUploading.value) return

  // 如果有附件，先上传到后端，获取附件 ID 列表
  let attachmentIds: string[] | undefined
  let uploadedAttachments: ChatAttachment[] | undefined
  if (attachments.value.length > 0) {
    isUploading.value = true
    uploadError.value = null
    try {
      const sessionId = undefined
      const uploaded = await Promise.all(
        attachments.value.map(file => chatApi.uploadAttachment(file, sessionId))
      )
      attachmentIds = uploaded.map(a => a.fileId)
      uploadedAttachments = uploaded
    } catch (e) {
      // 上传失败时，不发送消息，给出错误提示，允许用户重试
      console.error('附件上传失败:', e)
      uploadError.value = e instanceof Error ? e.message : '附件上传失败，请重试或移除附件'
      isUploading.value = false
      return
    } finally {
      isUploading.value = false
    }
  }

  emit('send', { content, attachmentIds, attachments: uploadedAttachments })
  input.value = ''
  attachments.value = []
  uploadError.value = null
}

function handleFileSelect() {
  fileInput.value?.click()
}

function handleFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files
  if (!files) return
  
  Array.from(files).forEach(file => {
    if (!attachments.value.find(f => f.name === file.name && f.size === file.size)) {
      attachments.value.push(file)
    }
  })
  input.value = ''
}

function removeAttachment(index: number) {
  attachments.value.splice(index, 1)
}

function insertTemplate(template: typeof promptTemplates[0]) {
  input.value = template.content + input.value
  showTemplates.value = false
}

function formatFileSize(bytes: number): string {
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

defineExpose({
  isUploading,
  input,
  getFileIcon
})
</script>

<template>
  <!-- 输入区域（对齐 stitch：浮层感、rounded-2xl、shadow-lg、底部提示） -->
  <div class="bg-background/90 backdrop-blur-md supports-[backdrop-filter]:bg-background/60 pt-sm pb-lg px-md md:px-lg border-t border-transparent">
    <div class="max-w-4xl mx-auto">
      <!-- 附件列表 -->
      <div v-if="attachments.length > 0" class="pt-sm pb-xs flex flex-wrap gap-sm">
        <div
          v-for="(file, index) in attachments"
          :key="index"
          class="inline-flex items-center gap-xs px-sm py-xs rounded-lg bg-muted text-xs text-foreground"
        >
          <component :is="getFileIcon(file)" :size="12" />
          <span class="max-w-[200px] truncate">{{ file.name }}</span>
          <span class="text-muted-foreground">({{ formatFileSize(file.size) }})</span>
          <button
            type="button"
            class="text-muted-foreground hover:text-destructive transition-colors"
            @click="removeAttachment(index)"
          >
            <X :size="12" />
          </button>
        </div>
      </div>

      <!-- 上下文配置折叠区域 -->
      <div v-if="showContextConfig" class="mb-sm rounded-lg border border-border bg-muted/20 p-sm text-xs">
        <div class="space-y-sm">
          <div class="flex items-center justify-between">
            <span class="font-medium text-foreground">上下文配置</span>
            <button
              type="button"
              class="text-muted-foreground hover:text-foreground"
              @click="showContextConfig = false"
            >
              <ChevronUp :size="14" />
            </button>
          </div>
          <div class="grid grid-cols-2 gap-sm">
            <div>
              <label class="text-muted-foreground mb-1 block">模型</label>
              <select
                v-model="contextConfig.model"
                class="w-full rounded-lg border border-input bg-background px-3 py-2 text-xs
                       focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent transition-all duration-200"
              >
                <option value="">使用默认</option>
                <!-- TODO: 从设置中获取可用模型列表 -->
              </select>
            </div>
            <div>
              <label class="text-muted-foreground mb-1 block">温度</label>
              <input
                v-model.number="contextConfig.temperature"
                type="number"
                min="0"
                max="2"
                step="0.1"
                class="w-full rounded-lg border border-input bg-background px-3 py-2 text-xs
                       focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent transition-all duration-200"
              />
            </div>
            <div>
              <label class="text-muted-foreground mb-1 block">最大 Tokens</label>
              <input
                v-model.number="contextConfig.maxTokens"
                type="number"
                min="100"
                max="8000"
                step="100"
                class="w-full rounded-lg border border-input bg-background px-3 py-2 text-xs
                       focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent transition-all duration-200"
              />
            </div>
            <div>
              <label class="text-muted-foreground mb-1 block">关联知识库</label>
              <select
                v-model="contextConfig.knowledgeBases"
                multiple
                class="w-full rounded-lg border border-input bg-background px-3 py-2 text-xs
                       focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent transition-all duration-200"
              >
                <option v-for="kb in kbStore.list" :key="kb.id" :value="kb.id">
                  {{ kb.name }}
                </option>
              </select>
            </div>
          </div>
        </div>
      </div>

      <div class="relative">
        <div
          class="bg-card border border-input rounded-2xl shadow-lg overflow-hidden flex flex-col
                 focus-within:ring-2 focus-within:ring-ring focus-within:border-transparent transition-all duration-200"
        >
          <textarea
            v-model="input"
            :disabled="disabled"
            :maxlength="maxLength"
            placeholder="问任何问题，或粘贴文本让 AI 分析…"
            rows="1"
            class="w-full bg-transparent border-none text-foreground placeholder:text-muted-foreground focus:ring-0 resize-none
                   py-md px-md min-h-[56px] max-h-[200px] text-base disabled:opacity-50 disabled:cursor-not-allowed"
            @keydown="handleKeydown"
            @click="showTemplates = false"
          />

          <div class="flex items-center justify-between px-sm pb-sm">
            <div class="flex items-center gap-sm">
              <!-- Prompt 模板下拉 -->
              <div class="relative">
                <button
                  type="button"
                  class="p-2 rounded-lg text-muted-foreground hover:text-foreground hover:bg-muted/60 transition-colors
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                  :disabled="disabled"
                  title="Prompt 模板"
                  @click="showTemplates = !showTemplates"
                >
                  <FileText :size="18" />
                </button>
                <Transition
                  enter-active-class="transition-all duration-200 ease-out"
                  enter-from-class="opacity-0 scale-95 translate-y-2"
                  enter-to-class="opacity-100 scale-100 translate-y-0"
                  leave-active-class="transition-all duration-150 ease-in"
                  leave-from-class="opacity-100 scale-100 translate-y-0"
                  leave-to-class="opacity-0 scale-95 translate-y-2"
                >
                  <div
                    v-if="showTemplates"
                    class="absolute bottom-full mb-1 left-0 w-48 rounded-md border border-border bg-card shadow-lg z-10 overflow-hidden"
                  >
                    <div class="p-1">
                      <div
                        v-for="template in promptTemplates"
                        :key="template.name"
                        class="px-3 py-1.5 rounded text-xs text-foreground hover:bg-accent cursor-pointer transition-colors duration-150"
                        @click="insertTemplate(template)"
                      >
                        {{ template.name }}
                      </div>
                    </div>
                  </div>
                </Transition>
              </div>

              <!-- 附件上传按钮 -->
              <button
                type="button"
                class="p-2 rounded-lg text-muted-foreground hover:text-foreground hover:bg-muted/60 transition-colors
                       disabled:opacity-50 disabled:cursor-not-allowed
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                :disabled="disabled"
                title="附件"
                @click="handleFileSelect"
              >
                <Paperclip :size="18" />
              </button>
              <input
                ref="fileInput"
                type="file"
                multiple
                accept="image/*,audio/*,video/*,.pdf,.txt,.md,.csv,.doc,.docx,.xls,.xlsx,.ppt,.pptx"
                class="hidden"
                @change="handleFileChange"
              />

              <!-- 上下文配置按钮 -->
              <button
                type="button"
                class="p-2 rounded-lg text-muted-foreground hover:text-foreground hover:bg-muted/60 transition-colors
                       disabled:opacity-50 disabled:cursor-not-allowed
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                :disabled="disabled"
                :class="showContextConfig ? 'bg-muted/60 text-foreground' : ''"
                title="上下文配置"
                @click="showContextConfig = !showContextConfig"
              >
                <Settings :size="18" />
              </button>
            </div>

            <div class="flex items-center gap-sm">
              <span class="text-xs text-muted-foreground hidden sm:inline-block">
                {{ inputLength }} / {{ maxLength }}
              </span>
              <button
                :disabled="disabled || !input.trim() || isUploading"
                class="w-10 h-10 rounded-full shadow-sm flex items-center justify-center
                       transition-all duration-200 active:scale-[0.98]
                       disabled:opacity-50 disabled:cursor-not-allowed
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                :class="(disabled || !input.trim() || isUploading)
                  ? 'bg-muted text-muted-foreground border border-border hover:bg-muted'
                  : 'bg-primary text-primary-foreground hover:bg-primary/90 hover:shadow-md'"
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
                  class="-rotate-45 translate-x-0.5 -translate-y-0.5"
                >
                  <path d="m22 2-7 20-4-9-9-4Z" />
                  <path d="M22 2 11 13" />
                </svg>
              </button>
            </div>
          </div>
        </div>
      </div>

      <div class="text-center mt-xs">
        <p class="text-[10px] text-muted-foreground">
          按 Enter 发送，Shift + Enter 换行。LifePilot 可能会出错。请核实重要信息。
          <span v-if="isUploading" class="ml-2 text-primary">正在上传附件…</span>
          <span v-else-if="disabled" class="ml-2">正在生成回答，稍候即可继续输入</span>
        </p>
      </div>

      <div v-if="uploadError" class="mt-xs text-xs text-destructive">
        {{ uploadError }}
      </div>
    </div>
  </div>
</template>
