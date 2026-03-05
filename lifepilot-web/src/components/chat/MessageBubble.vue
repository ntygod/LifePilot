<script setup lang="ts">
import type { Message } from '@/types'
import { computed, ref } from 'vue'
import { Bot, ChevronDown, ChevronRight, FileText } from 'lucide-vue-next'
import { shouldCollapse, getPreviewContent } from '@/utils/messageUtils'
import StreamingText from './StreamingText.vue'
import A2uiRenderer from '@/components/a2ui/A2uiRenderer.vue'
import ToolCallCard from './ToolCallCard.vue'
import KbSourceTag from './KbSourceTag.vue'
import MessageActions from './MessageActions.vue'
import MessageFeedback from './MessageFeedback.vue'
import MessageError from './MessageError.vue'

const props = defineProps<{
  message: Message
  streaming?: boolean
  streamingContent?: string
  isLastAssistant?: boolean
}>()

const emit = defineEmits<{
  (e: 'retry', message: Message): void
  (e: 'like', message: Message): void
  (e: 'dislike', message: Message, feedback?: string): void
  (e: 'fork', message: Message): void
  (e: 'regenerate', message: Message): void
  (e: 'copy', content: string): void
}>()

// 长消息折叠状态
const collapsed = ref(props.message.collapsed ?? shouldCollapse(props.message.content))

// 推理摘要折叠状态
const showReasoning = ref(false)
// 图片预览状态
const showImagePreview = ref(false)
const previewImageUrl = ref<string | null>(null)

const timeLabel = computed(() => {
  const date = new Date(props.message.timestamp)
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
})

const imageAttachments = computed(() =>
  props.message.attachments?.filter(a => a.isImage) ?? []
)
const fileAttachments = computed(() =>
  props.message.attachments?.filter(a => !a.isImage) ?? []
)
const kbSources = computed(() =>
  props.message.sources?.filter(s => s.type === 'knowledgeBase') ?? []
)

const userStatusLabel = computed(() => {
  if (props.message.role !== 'user' || !props.message.status) return ''
  if (props.message.status === 'pending') return '发送中…'
  if (props.message.status === 'error') return '发送失败'
  return ''
})

// 展示内容：折叠时显示预览，展开时显示完整
const displayContent = computed(() => {
  if (props.streaming) return props.streamingContent ?? ''
  if (props.message.role === 'assistant' && collapsed.value && shouldCollapse(props.message.content)) {
    return getPreviewContent(props.message.content)
  }
  return props.message.content
})

const isCollapsible = computed(() =>
  props.message.role === 'assistant' && !props.streaming && shouldCollapse(props.message.content)
)
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
      <!-- 顶部 meta 行 -->
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

      <!-- 消息气泡 -->
      <div
        class="relative max-w-full md:max-w-[85%] rounded-2xl shadow-sm transition-all duration-200"
        :class="[
          message.role === 'user'
            ? 'bg-primary text-primary-foreground rounded-tr-sm shadow-md p-md'
            : 'bg-card text-foreground rounded-tl-sm border border-border p-md'
        ]"
      >
        <!-- 流式高亮边框 -->
        <div
          v-if="streaming && message.role === 'assistant'"
          class="absolute inset-0 rounded-2xl rounded-tl-sm border-2 border-primary/60 animate-pulse pointer-events-none"
        />

        <div class="relative z-[1]">
          <!-- 用户消息 -->
          <p
            v-if="message.role === 'user'"
            class="text-sm whitespace-pre-wrap"
            v-html="(message as any).highlightedContent ?? message.content"
          />

          <!-- Agent 消息 -->
          <template v-else>
            <StreamingText
              :content="displayContent"
              :streaming="streaming"
            />

            <!-- 长消息折叠控制 -->
            <button
              v-if="isCollapsible"
              type="button"
              class="mt-2 text-xs text-primary hover:underline underline-offset-2 transition-colors"
              @click="collapsed = !collapsed"
            >
              {{ collapsed ? '展开全文' : '收起' }}
            </button>

            <!-- A2UI 工具卡片 -->
            <div
              v-if="message.a2uiComponents?.length"
              class="mt-2 rounded-lg border border-border bg-background/80 text-foreground text-sm overflow-hidden shadow-sm"
            >
              <div class="px-3 py-2 border-b border-border flex items-center gap-2">
                <span class="font-medium text-xs text-muted-foreground">工具 / 工作流结果</span>
              </div>
              <div class="p-3">
                <A2uiRenderer :components="message.a2uiComponents" />
              </div>
            </div>

            <!-- 工具调用卡片 -->
            <div v-if="message.toolsSummary?.length" class="mt-2 space-y-1.5">
              <ToolCallCard
                v-for="tool in message.toolsSummary"
                :key="tool.toolId"
                :tool="tool"
              />
            </div>

            <!-- 知识库来源标签 -->
            <div v-if="kbSources.length" class="mt-2 flex flex-wrap gap-1">
              <KbSourceTag
                v-for="source in kbSources"
                :key="source.id"
                :source="source"
              />
            </div>

            <!-- 推理过程摘要折叠面板 -->
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
                  <span class="text-[11px] font-medium text-foreground/80">本轮推理概要</span>
                </div>
                <div class="flex items-center gap-1 text-[10px] text-muted-foreground">
                  <span>{{ showReasoning ? '收起' : '展开' }}</span>
                  <component :is="showReasoning ? ChevronDown : ChevronRight" :size="12" />
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

          <!-- 图片附件 -->
          <div v-if="imageAttachments.length > 0" class="mt-2 grid grid-cols-2 gap-sm">
            <button
              v-for="att in imageAttachments"
              :key="att.fileId"
              type="button"
              class="relative w-full overflow-hidden rounded-lg border border-border bg-background/40 hover:bg-background/80 transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
              @click="previewImageUrl = att.url; showImagePreview = true"
            >
              <img :src="att.url" :alt="att.filename" class="block w-full h-32 object-cover" loading="lazy" />
            </button>
          </div>

          <!-- 非图片附件 -->
          <div v-if="fileAttachments.length > 0" class="mt-2 flex flex-col gap-sm">
            <div
              v-for="att in fileAttachments"
              :key="att.fileId"
              class="flex flex-col gap-2 rounded-lg border border-border bg-background/60 px-3 py-2 text-xs text-foreground"
            >
              <div class="flex items-center justify-between gap-2">
                <span class="truncate">{{ att.filename }}</span>
                <span class="shrink-0 text-[11px] text-muted-foreground">{{ (att.size / 1024).toFixed(1) }} KB</span>
              </div>
              <video v-if="att.type?.startsWith('video/')" :src="att.url" controls class="mt-1 w-full max-w-full rounded-lg" style="max-height: 360px;">浏览器不支持视频播放</video>
              <audio v-else-if="att.type?.startsWith('audio/')" :src="att.url" controls class="mt-1 w-full" />
              <a v-else :href="att.url" target="_blank" rel="noopener noreferrer" class="mt-1 inline-flex items-center gap-1 text-[11px] text-primary underline-offset-2 hover:underline">
                <FileText :size="14" class="text-muted-foreground" />
                <span>下载文件</span>
              </a>
            </div>
          </div>
        </div>
      </div>

      <!-- AI 消息操作区：MessageActions + MessageFeedback -->
      <template v-if="message.role === 'assistant' && !streaming">
        <div class="space-y-2 pl-xs">
          <MessageActions
            :message="message"
            :is-last-assistant="isLastAssistant ?? false"
            @copy="(c: string) => emit('copy', c)"
            @fork="(m: Message) => emit('fork', m)"
            @regenerate="(m: Message) => emit('regenerate', m)"
          />
          <MessageFeedback
            :message="message"
            @like="(m: Message) => emit('like', m)"
            @dislike="(m: Message, f?: string) => emit('dislike', m, f)"
          />
        </div>
      </template>

      <!-- 用户消息错误处理 -->
      <MessageError
        v-if="message.role === 'user' && message.status === 'error'"
        :message="message"
        @retry="(m: Message) => emit('retry', m)"
      />

      <!-- 图片大图预览层 -->
      <div
        v-if="showImagePreview && previewImageUrl"
        class="fixed inset-0 bg-black/60 flex items-center justify-center z-50"
        @click.self="showImagePreview = false; previewImageUrl = null"
      >
        <div class="max-w-[90vw] max-h-[90vh] rounded-2xl overflow-hidden bg-background shadow-xl">
          <img :src="previewImageUrl" alt="预览图片" class="block max-w-full max-h-[90vh] object-contain" />
        </div>
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
