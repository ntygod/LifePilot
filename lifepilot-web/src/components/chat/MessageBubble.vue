<script setup lang="ts">
import type { Message } from '@/types'
import { computed } from 'vue'
import StreamingText from './StreamingText.vue'
import A2uiRenderer from '@/components/a2ui/A2uiRenderer.vue'

const props = defineProps<{
  message: Message
  streaming?: boolean
  streamingContent?: string
}>()

const emit = defineEmits<{
  (e: 'retry', message: Message): void
}>()

// 本地化时间显示（时:分），后续可根据全局设置扩展
const timeLabel = computed(() => {
  const date = new Date(props.message.timestamp)
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
})

const userStatusLabel = computed(() => {
  if (props.message.role !== 'user' || !props.message.status) return ''
  if (props.message.status === 'pending') return '发送中…'
  if (props.message.status === 'error') return '发送失败'
  return ''
})
</script>

<template>
  <div class="flex gap-3 px-4 py-2" :class="message.role === 'user' ? 'justify-end' : ''">
    <!-- Agent 头像 -->
    <div
      v-if="message.role === 'assistant'"
      class="shrink-0 w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center text-xs font-medium text-primary"
      aria-label="AI 消息"
    >
      AI
    </div>

    <!-- 消息内容 + 元信息 -->
    <div class="flex flex-col items-start max-w-[70%]">
      <div class="w-full rounded-lg px-4 py-2 relative">
        <!-- 流式进行中的高亮边框与角标 -->
        <div
          v-if="streaming && message.role === 'assistant'"
          class="absolute inset-0 rounded-lg border-2 border-primary/60 animate-pulse pointer-events-none"
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
              class="mt-2 rounded-md border border-border bg-background/80 text-foreground text-xs overflow-hidden"
            >
              <div class="px-3 py-1.5 border-b border-border flex items-center gap-2">
                <span class="font-medium text-[11px] text-muted-foreground">
                  工具 / 工作流结果
                </span>
                <span class="text-[11px] text-muted-foreground/80">
                  本条消息中使用的外部能力已结构化展示
                </span>
              </div>
              <div class="p-3">
                <A2uiRenderer
                  :components="message.a2uiComponents"
                />
              </div>
            </div>
          </template>
        </div>
      </div>

      <!-- 底部时间与角色标签 + 状态 / Trace 入口 / 操作 -->
      <div class="mt-1 flex flex-wrap items-center gap-2 text-[11px] text-muted-foreground/80">
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
          class="ml-1 text-[11px] text-muted-foreground hover:text-foreground underline-offset-2 hover:underline"
          type="button"
          @click="navigator.clipboard?.writeText(message.content)"
        >
          复制内容
        </button>
      </div>

      <!-- 用户消息失败时的错误提示与重试 -->
      <div
        v-if="message.role === 'user' && message.status === 'error'"
        class="mt-1 flex items-center gap-2 text-[11px] text-destructive/90"
      >
        <span>{{ message.errorMessage || '发送失败' }}</span>
        <RouterLink
          v-if="message.traceId"
          :to="{ name: 'traces', query: { id: message.traceId } }"
          class="underline-offset-2 hover:underline"
        >
          查看执行轨迹
        </RouterLink>
        <button
          class="underline-offset-2 hover:underline"
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
