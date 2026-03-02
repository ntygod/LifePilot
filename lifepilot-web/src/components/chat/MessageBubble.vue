<script setup lang="ts">
import type { Message } from '@/types'
import { computed, ref } from 'vue'
import { ThumbsUp, ThumbsDown, GitBranch, Copy, ChevronDown, ChevronRight, FileAudio2, FileText } from 'lucide-vue-next'
import StreamingText from './StreamingText.vue'
import A2uiRenderer from '@/components/a2ui/A2uiRenderer.vue'

const props = defineProps<{
  message: Message
  streaming?: boolean
  streamingContent?: string
}>()

const emit = defineEmits<{
  (e: 'retry', message: Message): void
  (e: 'like', message: Message): void
  (e: 'dislike', message: Message, feedback?: string): void
  (e: 'fork', message: Message): void
}>()

function copyToClipboard(text: string) {
  if (typeof navigator !== 'undefined' && navigator.clipboard) {
    navigator.clipboard.writeText(text).catch(console.error)
  }
}

// 点赞/点踩状态
const liked = ref(false)
const disliked = ref(false)
const showFeedbackInput = ref(false)
const feedbackText = ref('')
// 推理摘要折叠状态（仅对带 reasoningSummary 的 AI 消息生效）
const showReasoning = ref(false)
// 图片预览状态
const showImagePreview = ref(false)
const previewImageUrl = ref<string | null>(null)

// 本地化时间显示（时:分），后续可根据全局设置扩展
const timeLabel = computed(() => {
  const date = new Date(props.message.timestamp)
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
})

const imageAttachments = computed(() => {
  return props.message.attachments?.filter(a => a.isImage) ?? []
})

const fileAttachments = computed(() => {
  return props.message.attachments?.filter(a => !a.isImage) ?? []
})

const userStatusLabel = computed(() => {
  if (props.message.role !== 'user' || !props.message.status) return ''
  if (props.message.status === 'pending') return '发送中…'
  if (props.message.status === 'error') return '发送失败'
  return ''
})

function handleLike() {
  liked.value = !liked.value
  if (liked.value) {
    disliked.value = false
    emit('like', props.message)
  }
}

function handleDislike() {
  disliked.value = !disliked.value
  if (disliked.value) {
    liked.value = false
    showFeedbackInput.value = true
  } else {
    showFeedbackInput.value = false
    feedbackText.value = ''
  }
}

function submitFeedback() {
  emit('dislike', props.message, feedbackText.value)
  showFeedbackInput.value = false
  feedbackText.value = ''
}

function handleFork() {
  emit('fork', props.message)
}

function openImagePreview(url: string) {
  previewImageUrl.value = url
  showImagePreview.value = true
}

function closeImagePreview() {
  showImagePreview.value = false
  previewImageUrl.value = null
}
</script>

<template>
  <div class="flex gap-2 px-4 md:px-6 py-6" :class="message.role === 'user' ? 'justify-end' : ''">
    <!-- Agent 头像 -->
    <div
      v-if="message.role === 'assistant'"
      class="shrink-0 w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center text-xs font-medium text-primary"
      aria-label="AI 消息"
    >
      AI
    </div>

    <!-- 消息内容 + 元信息 -->
    <div class="flex flex-col items-start max-w-[768px]" :class="message.role === 'user' ? 'items-end' : 'items-start'">
      <div
        class="w-full rounded-2xl px-4 py-3 relative shadow-sm"
        :class="message.role === 'user' ? 'rounded-br-sm' : 'rounded-bl-sm'"
      >
        <!-- 流式进行中的高亮边框与角标 -->
        <div
          v-if="streaming && message.role === 'assistant'"
          class="absolute inset-0 rounded-2xl rounded-bl-sm border-2 border-primary/60 animate-pulse pointer-events-none"
        />

        <div
          :class="message.role === 'user'
            ? 'relative z-[1] bg-primary text-primary-foreground'
            : 'relative z-[1] bg-muted text-foreground'"
        >
        <!-- 用户消息：纯文本（支持可选高亮 HTML） -->
        <p
          v-if="message.role === 'user'"
          class="text-sm whitespace-pre-wrap"
          v-html="(message as any).highlightedContent ?? message.content"
        />

          <!-- Agent 消息：Markdown 渲染 + A2UI 工具卡片 -->
          <template v-else>
            <StreamingText
              :content="streaming ? (streamingContent ?? '') : message.content"
              :streaming="streaming"
            />
            <!-- A2UI 工具卡片渲染：为工具 / 工作流结果提供结构化展示区域 -->
            <div
              v-if="message.a2uiComponents?.length"
              class="mt-2 rounded-lg border border-border bg-background/80 text-foreground text-sm overflow-hidden shadow-sm"
            >
              <div class="px-3 py-2 border-b border-border flex items-center gap-2">
                <span class="font-medium text-xs text-muted-foreground">
                  工具 / 工作流结果
                </span>
                <span class="text-xs text-muted-foreground/80">
                  本条消息中使用的外部能力已结构化展示
                </span>
              </div>
              <div class="p-3">
                <A2uiRenderer
                  :components="message.a2uiComponents"
                />
              </div>
            </div>

            <!-- 推理过程摘要折叠面板（Phase 1） -->
            <div
              v-if="message.reasoningSummary"
              class="mt-2 rounded-lg border border-border/80 bg-background/80 text-xs text-muted-foreground overflow-hidden"
            >
              <button
                type="button"
                class="w-full px-3 py-2 flex items-center justify-between gap-2 text-left hover:bg-muted/60 transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
                @click="showReasoning = !showReasoning"
              >
                <div class="flex items-center gap-2">
                  <span class="inline-flex w-1.5 h-1.5 rounded-full bg-primary" />
                  <span class="text-[11px] font-medium text-foreground/80">
                    本轮推理概要
                  </span>
                </div>
                <div class="flex items-center gap-1 text-[10px] text-muted-foreground">
                  <span>{{ showReasoning ? '收起' : '展开' }}</span>
                  <component
                    :is="showReasoning ? ChevronDown : ChevronRight"
                    :size="12"
                  />
                </div>
              </button>
              <div
                v-if="showReasoning"
                class="px-3 py-2 border-t border-border/70 text-[11px] leading-normal"
              >
                {{ message.reasoningSummary }}
              </div>
            </div>
          </template>

          <!-- 图片附件缩略图（用户或 AI 消息均可展示） -->
          <div
        v-if="imageAttachments.length > 0"
        class="mt-2 grid grid-cols-2 gap-2"
          >
            <button
              v-for="att in imageAttachments"
              :key="att.fileId"
              type="button"
              class="relative w-full overflow-hidden rounded-lg border border-border bg-background/40 hover:bg-background/80 transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
              @click="openImagePreview(att.url)"
            >
              <img
                :src="att.url"
                :alt="att.filename"
                class="block w-full h-32 object-cover"
                loading="lazy"
              />
            </button>
          </div>
        </div>
      </div>

      <!-- 底部时间与角色标签 + 状态 / Trace 入口 / 操作 -->
      <div class="mt-2 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <span>
          {{ message.role === 'user' ? '你' : 'AI' }}
        </span>
        <span>·</span>
        <time :datetime="new Date(message.timestamp).toISOString()">
          {{ timeLabel }}
        </time>
        <!-- 用户消息的发送状态 -->
        <template v-if="message.role === 'user' && userStatusLabel">
          <span>·</span>
          <span
            :class="message.status === 'error' ? 'text-destructive' : 'text-muted-foreground'"
          >
            {{ userStatusLabel }}
          </span>
        </template>
        <!-- 正在生成的 AI 消息：显式状态与停止提示 -->
        <template v-if="message.role === 'assistant' && streaming">
          <span>·</span>
          <span class="inline-flex items-center gap-1 text-primary">
            <span class="inline-flex h-1.5 w-1.5 rounded-full bg-primary animate-pulse" />
            正在生成…可在顶部点击“停止”
          </span>
        </template>
        <!-- 带 Trace 的 AI 消息：提供跳转入口（生成完毕） -->
        <template v-else-if="message.role === 'assistant' && message.traceId">
          <span>·</span>
          <RouterLink
            :to="{ name: 'traces', query: { id: message.traceId } }"
            class="underline-offset-2 hover:underline"
          >
            查看执行轨迹
          </RouterLink>
        </template>
        <!-- 复制内容 -->
        <button
          class="ml-1 text-xs text-muted-foreground hover:text-foreground underline-offset-2 hover:underline transition-colors"
          type="button"
          @click="copyToClipboard(message.content)"
        >
          复制内容
        </button>
      </div>

      <!-- AI消息的操作按钮：点赞/点踩/分叉 -->
      <div v-if="message.role === 'assistant' && !streaming" class="mt-2 flex items-center gap-2">
        <button
          type="button"
          class="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-medium text-muted-foreground hover:text-foreground hover:bg-muted transition-all duration-200"
          :class="liked ? 'text-primary bg-primary/10' : ''"
          @click="handleLike"
        >
          <ThumbsUp :size="14" />
          <span>有用</span>
        </button>
        <button
          type="button"
          class="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-medium text-muted-foreground hover:text-foreground hover:bg-muted transition-all duration-200"
          :class="disliked ? 'text-destructive bg-destructive/10' : ''"
          @click="handleDislike"
        >
          <ThumbsDown :size="14" />
          <span>无用</span>
        </button>
        <button
          type="button"
          class="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-medium text-muted-foreground hover:text-foreground hover:bg-muted transition-all duration-200"
          @click="handleFork"
        >
          <GitBranch :size="14" />
          <span>从此分叉</span>
        </button>
      </div>

      <!-- 图片大图预览层 -->
      <div
        v-if="showImagePreview && previewImageUrl"
        class="fixed inset-0 bg-black/60 flex items-center justify-center z-50"
        @click.self="closeImagePreview"
      >
        <div class="max-w-[90vw] max-h-[90vh] rounded-2xl overflow-hidden bg-background shadow-xl">
          <img
            :src="previewImageUrl"
            alt="预览图片"
            class="block max-w-full max-h-[90vh] object-contain"
          />
        </div>
      </div>

      <!-- 点踩反馈输入框 -->
      <div v-if="showFeedbackInput" class="mt-2">
        <textarea
          v-model="feedbackText"
          placeholder="请描述问题或建议（可选）"
          rows="2"
          class="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm
                 placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent resize-none transition-all duration-200"
        />
        <div class="mt-2 flex justify-end gap-2">
          <button
            type="button"
            class="px-4 py-2 rounded-lg text-sm font-medium border border-input hover:bg-accent transition-all duration-200"
            @click="showFeedbackInput = false; feedbackText = ''"
          >
            取消
          </button>
          <button
            type="button"
            class="px-4 py-2 rounded-lg text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 hover:shadow-md transition-all duration-200 active:scale-[0.98]"
            @click="submitFeedback"
          >
            提交反馈
          </button>
        </div>

        <!-- 非图片附件：文件卡片 / 音频播放器 -->
        <div
          v-if="fileAttachments.length > 0"
          class="mt-2 flex flex-col gap-2"
        >
          <div
            v-for="att in fileAttachments"
            :key="att.fileId"
            class="flex items-center gap-3 rounded-lg border border-border bg-background/60 px-3 py-2 text-xs"
          >
            <!-- 图标：音频 vs 通用文件 -->
            <div class="shrink-0 text-muted-foreground">
              <FileAudio2
                v-if="att.type?.startsWith('audio/')"
                :size="16"
              />
              <FileText
                v-else
                :size="16"
              />
            </div>
            <!-- 文件信息 -->
            <div class="flex-1 min-w-0">
              <div class="flex items-center justify-between gap-2">
                <span class="truncate text-foreground">
                  {{ att.filename }}
                </span>
                <span class="shrink-0 text-[11px] text-muted-foreground">
                  {{ (att.size / 1024).toFixed(1) }} KB
                </span>
              </div>
              <!-- 音频播放器 -->
              <audio
                v-if="att.type?.startsWith('audio/')"
                :src="att.url"
                controls
                class="mt-1 w-full"
              />
              <!-- 通用文件下载链接 -->
              <a
                v-else
                :href="att.url"
                target="_blank"
                rel="noopener noreferrer"
                class="mt-1 inline-flex items-center gap-1 text-[11px] text-primary underline-offset-2 hover:underline"
              >
                下载文件
              </a>
            </div>
          </div>
        </div>
      </div>

      <!-- 用户消息失败时的错误提示与重试 -->
      <div
        v-if="message.role === 'user' && message.status === 'error'"
        class="mt-2 flex items-center gap-2 text-xs text-destructive"
      >
        <span>{{ message.errorMessage || '发送失败' }}</span>
        <RouterLink
          v-if="message.traceId"
          :to="{ name: 'traces', query: { id: message.traceId } }"
          class="underline-offset-2 hover:underline transition-colors"
        >
          查看执行轨迹
        </RouterLink>
        <button
          class="underline-offset-2 hover:underline transition-colors"
          type="button"
          @click="emit('retry', message)"
        >
          重试
        </button>
      </div>
    </div>

    <!-- 用户头像 -->
    <div
      v-if="message.role === 'user'"
      class="shrink-0 w-8 h-8 rounded-full bg-secondary flex items-center justify-center text-xs font-medium"
      aria-label="你的消息"
    >
      你
    </div>
  </div>
</template>
