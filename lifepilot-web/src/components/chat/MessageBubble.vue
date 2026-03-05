<script setup lang="ts">
import type { Message } from '@/types'
import { computed, ref } from 'vue'
import { Bot, Copy, GitBranch, ThumbsDown, ThumbsUp, ChevronDown, ChevronRight, FileAudio2, FileText } from 'lucide-vue-next'
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
  <div
    class="grid w-full gap-md"
    :class="message.role === 'user' ? 'grid-cols-[1fr_auto]' : 'grid-cols-[auto_1fr]'"
  >
    <!-- Agent 头像 -->
    <div
      v-if="message.role === 'assistant'"
      class="shrink-0 w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center text-primary mt-xs"
      aria-label="AI 消息"
    >
      <Bot :size="14" />
    </div>

    <!-- 消息内容 + 元信息 -->
    <div
      class="min-w-0 space-y-xs"
      :class="message.role === 'user' ? 'flex flex-col items-end' : ''"
    >
      <!-- 顶部 meta 行（对齐 stitch：名称 • 时间） -->
      <div
        class="flex items-center gap-xs text-xs text-muted-foreground"
        :class="message.role === 'user' ? 'justify-end' : ''"
      >
        <template v-if="message.role === 'assistant'">
          <span class="font-semibold text-foreground">LifePilot 助手</span>
          <span>•</span>
          <time :datetime="new Date(message.timestamp).toISOString()">{{ timeLabel }}</time>
        </template>
        <template v-else>
          <time :datetime="new Date(message.timestamp).toISOString()">{{ timeLabel }}</time>
          <span>•</span>
          <span class="font-semibold text-foreground">你</span>
          <template v-if="userStatusLabel">
            <span>•</span>
            <span :class="message.status === 'error' ? 'text-destructive' : 'text-muted-foreground'">
              {{ userStatusLabel }}
            </span>
          </template>
        </template>
      </div>

      <div
        class="relative max-w-full md:max-w-[85%] rounded-2xl shadow-sm transition-all duration-200"
        :class="[
          message.role === 'user'
            ? 'bg-primary text-primary-foreground rounded-tr-sm shadow-md p-md'
            : 'bg-card text-foreground rounded-tl-sm border border-border p-md'
        ]"
      >
        <!-- 流式进行中的高亮边框与角标 -->
        <div
          v-if="streaming && message.role === 'assistant'"
          class="absolute inset-0 rounded-2xl rounded-tl-sm border-2 border-primary/60 animate-pulse pointer-events-none"
        />

        <div class="relative z-[1]">
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

            <!-- 本轮执行概要卡片（模型 / Token / 工具 / 知识库） -->
            <div
              v-if="message.tokenUsage || (message.toolsSummary && message.toolsSummary.length) || (message.sources && message.sources.length)"
              class="mt-2 rounded-lg border border-border/70 bg-background/80 text-[11px] text-muted-foreground px-3 py-2 space-y-1.5"
            >
              <div class="flex items-center gap-2">
                <span class="inline-flex w-1.5 h-1.5 rounded-full bg-primary" />
                <span class="font-medium text-foreground/80">本轮执行概要</span>
              </div>
              <div v-if="message.tokenUsage" class="text-[11px] leading-snug">
                <span class="text-foreground/90">
                  模型：{{ message.modelId || message.tokenUsage.modelId || '未知模型' }}
                </span>
                <span class="mx-1 text-muted-foreground/70">•</span>
                <span>
                  Tokens：{{ message.tokenUsage.totalTokens }}
                  （提示 {{ message.tokenUsage.promptTokens }} / 回答 {{ message.tokenUsage.completionTokens }}）
                </span>
              </div>
              <div v-if="message.toolsSummary && message.toolsSummary.length" class="text-[11px] leading-snug">
                <span class="text-foreground/80">
                  工具：共 {{ message.toolsSummary.length }} 次调用
                </span>
                <span v-if="message.toolsSummary[0]" class="ml-1 text-muted-foreground">
                  · 示例：
                  {{ message.toolsSummary[0].toolId }}
                  <span v-if="message.toolsSummary[0].success === false" class="text-destructive">
                    （失败）
                  </span>
                  <span v-else class="text-muted-foreground/80">
                    （{{ message.toolsSummary[0].latencyMs }}ms）
                  </span>
                </span>
              </div>
              <div v-if="message.sources && message.sources.length" class="text-[11px] leading-snug">
                <span class="text-foreground/80">
                  知识库：{{ message.sources.filter(s => s.type === 'knowledgeBase').length }} 个
                </span>
                <span v-if="message.sources.find(s => s.type === 'knowledgeBase')" class="ml-1 text-muted-foreground">
                  · 示例：
                  {{
                    message.sources.find(s => s.type === 'knowledgeBase')?.name
                  }}
                </span>
              </div>
            </div>
          </template>

          <!-- 图片附件缩略图（用户或 AI 消息均可展示） -->
          <div v-if="imageAttachments.length > 0" class="mt-2 grid grid-cols-2 gap-sm">
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

          <!-- 非图片附件：视频 / 音频 / 通用文件下载 -->
          <div
            v-if="fileAttachments.length > 0"
            class="mt-2 flex flex-col gap-sm"
          >
            <div
              v-for="att in fileAttachments"
              :key="att.fileId"
              class="flex flex-col gap-2 rounded-lg border border-border bg-background/60 px-3 py-2 text-xs text-foreground"
            >
              <div class="flex items-center justify-between gap-2">
                <span class="truncate">
                  {{ att.filename }}
                </span>
                <span class="shrink-0 text-[11px] text-muted-foreground">
                  {{ (att.size / 1024).toFixed(1) }} KB
                </span>
              </div>

              <!-- 视频播放器 -->
              <video
                v-if="att.type?.startsWith('video/')"
                :src="att.url"
                controls
                class="mt-1 w-full max-w-full rounded-lg"
                style="max-height: 360px;"
              >
                浏览器不支持视频播放
              </video>

              <!-- 音频播放器 -->
              <audio
                v-else-if="att.type?.startsWith('audio/')"
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
                <span class="inline-flex items-center justify-center text-muted-foreground">
                  <FileText :size="14" />
                </span>
                <span>下载文件</span>
              </a>
            </div>
          </div>
        </div>
      </div>

      <!-- AI 消息操作区（对齐 stitch：图标 + 轻按钮） -->
      <div
        v-if="message.role === 'assistant' && !streaming"
        class="flex items-center gap-md pl-xs"
      >
        <button
          type="button"
          class="flex items-center gap-xs text-xs text-muted-foreground hover:text-foreground transition-colors"
          :class="liked ? 'text-primary' : ''"
          @click="handleLike"
        >
          <ThumbsUp :size="14" />
          <span>有帮助</span>
        </button>
        <button
          type="button"
          class="flex items-center gap-xs text-xs text-muted-foreground hover:text-foreground transition-colors"
          :class="disliked ? 'text-destructive' : ''"
          @click="handleDislike"
        >
          <ThumbsDown :size="14" />
          <span>无帮助</span>
        </button>
        <button
          type="button"
          class="flex items-center gap-xs text-xs text-muted-foreground hover:text-foreground transition-colors"
          @click="copyToClipboard(message.content)"
        >
          <Copy :size="14" />
          <span>复制</span>
        </button>
        <button
          type="button"
          class="flex items-center gap-xs text-xs text-muted-foreground hover:text-foreground transition-colors"
          @click="handleFork"
        >
          <GitBranch :size="14" />
          <span>分叉</span>
        </button>

        <RouterLink
          v-if="message.traceId"
          :to="{ name: 'traces', query: { id: message.traceId } }"
          class="ml-auto text-xs text-primary hover:underline underline-offset-2"
        >
          查看执行轨迹
        </RouterLink>
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
      class="shrink-0 w-8 h-8 rounded-full bg-primary text-primary-foreground shadow-md flex items-center justify-center text-[10px] font-semibold mt-xs"
      aria-label="你的消息"
    >
      你
    </div>
  </div>
</template>
