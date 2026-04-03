<script setup lang="ts">
/**
 * Agent 测试聊天组件。
 *
 * 封装 SSE 流式对话，支持 Token 统计、工具调用展示、错误处理。
 * 调用 POST /api/agents/{id}/test-chat/stream 端点。
 */
import { ref, computed, nextTick, watch } from 'vue'
import { agentApi } from '@/api/client'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'
import { logger } from '@/utils/logger'
import type { SseTokenEvent, SseDoneEvent, SseErrorEvent, TokenUsage, ToolCallSummary } from '@/types'
import { Bot, Send, Trash2, Loader2, AlertCircle, ChevronDown, ChevronRight } from 'lucide-vue-next'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import StreamingText from '@/components/chat/StreamingText.vue'

const props = defineProps<{
  agentId: string
}>()

// ─── 消息类型 ───
interface TestMessage {
  id: string
  role: 'user' | 'assistant' | 'error'
  content: string
  timestamp: number
  toolsSummary?: ToolCallSummary[]
  tokenUsage?: TokenUsage
}

// ─── 状态 ───
const messages = ref<TestMessage[]>([])
const inputText = ref('')
const isStreaming = ref(false)
const streamingContent = ref('')
const tokenUsage = ref<TokenUsage | null>(null)
const messageListRef = ref<HTMLElement | null>(null)
// 工具调用折叠状态，key 为消息 ID
const expandedTools = ref<Set<string>>(new Set())
let abortController: AbortController | null = null

// ─── 计算属性 ───
const canSend = computed(() => inputText.value.trim().length > 0 && !isStreaming.value)

// ─── 自动滚动到底部 ───
function scrollToBottom() {
  nextTick(() => {
    if (messageListRef.value) {
      messageListRef.value.scrollTop = messageListRef.value.scrollHeight
    }
  })
}

watch([messages, streamingContent], scrollToBottom, { deep: true })

// ─── 工具调用折叠切换 ───
function toggleTools(entryId: string) {
  if (expandedTools.value.has(entryId)) {
    expandedTools.value.delete(entryId)
  } else {
    expandedTools.value.add(entryId)
  }
}

// ─── SSE 流式对话 ───
async function sendMessage() {
  const content = inputText.value.trim()
  if (!content || isStreaming.value) return

  // 添加用户消息
  const userMsg: TestMessage = {
    id: crypto.randomUUID(),
    role: 'user',
    content,
    timestamp: Date.now(),
  }
  messages.value.push(userMsg)
  inputText.value = ''

  // 重置流式状态
  isStreaming.value = true
  streamingContent.value = ''
  abortController = new AbortController()

  try {
    const stream = await agentApi.testChatStream(props.agentId, content, abortController.signal)
    await parseSseStream(stream)
  } catch (e: unknown) {
    if (e instanceof DOMException && e.name === 'AbortError') return
    const message = e instanceof Error ? e.message : '请求失败'
    messages.value.push({
      id: crypto.randomUUID(),
      role: 'error',
      content: `网络异常：${message}`,
      timestamp: Date.now(),
    })
  } finally {
    isStreaming.value = false
    abortController = null
  }
}

/** 解析 SSE 流（复用 useChat 的解析模式） */
async function parseSseStream(stream: ReadableStream<Uint8Array>) {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let currentEvent = ''
  let currentData = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''

      for (const line of lines) {
        if (line.startsWith('event:')) {
          if (currentData && currentEvent) {
            handleSseEvent(currentEvent, currentData)
            currentData = ''
          }
          currentEvent = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          const data = line.slice(5)
          if (data.startsWith(' ')) {
            currentData += '\n' + data.slice(1)
          } else {
            if (currentData && currentEvent) {
              handleSseEvent(currentEvent, currentData)
            }
            currentData = data.trim()
          }
        } else if (line === '') {
          if (currentData && currentEvent) {
            handleSseEvent(currentEvent, currentData)
            currentData = ''
            currentEvent = ''
          }
        }
      }
    }
    // 处理剩余数据
    if (currentData && currentEvent) {
      handleSseEvent(currentEvent, currentData)
    }
  } catch (e) {
    const message = e instanceof Error ? e.message : 'SSE 解析失败'
    messages.value.push({
      id: crypto.randomUUID(),
      role: 'error',
      content: `流式响应解析失败：${message}`,
      timestamp: Date.now(),
    })
  } finally {
    reader.releaseLock()
    // 如果流结束但没有收到 done 事件，将已有的流式内容作为消息保存
    if (streamingContent.value && isStreaming.value) {
      messages.value.push({
        id: crypto.randomUUID(),
        role: 'assistant',
        content: streamingContent.value,
        timestamp: Date.now(),
      })
      streamingContent.value = ''
    }
  }
}

/** 处理单个 SSE 事件（仅处理 token / done / error） */
function handleSseEvent(eventType: string, data: string) {
  try {
    switch (eventType) {
      case SSE_EVENT_TYPES.TOKEN: {
        const event: SseTokenEvent = JSON.parse(data)
        streamingContent.value += event.content
        break
      }
      case SSE_EVENT_TYPES.DONE: {
        const event: SseDoneEvent = JSON.parse(data)
        const finalContent = event.content ?? streamingContent.value
        const msg: TestMessage = {
          id: event.entryId ?? crypto.randomUUID(),
          role: 'assistant',
          content: finalContent,
          timestamp: event.timestamp ?? Date.now(),
          toolsSummary: event.toolsSummary,
        }
        // 提取 Token 统计
        if (event.tokenUsage) {
          msg.tokenUsage = event.tokenUsage
          tokenUsage.value = event.tokenUsage
        } else if (event.usage) {
          const usage: TokenUsage = {
            promptTokens: event.usage.inputTokens,
            completionTokens: event.usage.outputTokens,
            totalTokens: event.usage.totalTokens,
            modelId: 'unknown',
          }
          msg.tokenUsage = usage
          tokenUsage.value = usage
        }
        messages.value.push(msg)
        streamingContent.value = ''
        break
      }
      case SSE_EVENT_TYPES.ERROR: {
        const event: SseErrorEvent = JSON.parse(data)
        let uiMessage: string
        if (event.code === 429) {
          uiMessage = '请求过于频繁，请稍后再试。'
        } else if (event.code >= 500) {
          uiMessage = '服务器暂时出现问题，请稍后重试。'
        } else {
          uiMessage = event.message || '对话过程中发生未知错误'
        }
        messages.value.push({
          id: crypto.randomUUID(),
          role: 'error',
          content: uiMessage,
          timestamp: Date.now(),
        })
        streamingContent.value = ''
        break
      }
      case SSE_EVENT_TYPES.HEARTBEAT:
      case SSE_EVENT_TYPES.REASONING:
      case SSE_EVENT_TYPES.TRACE_START:
        // 测试聊天中忽略这些事件
        break
      default:
        break
    }
  } catch (e) {
    logger.warn('SSE 事件解析失败:', eventType, e)
  }
}

// ─── 清空对话 ───
function clearChat() {
  if (abortController) {
    abortController.abort()
  }
  messages.value = []
  streamingContent.value = ''
  tokenUsage.value = null
  isStreaming.value = false
  expandedTools.value.clear()
}

// ─── 键盘事件 ───
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

function formatTime(ts: number): string {
  return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <div class="flex flex-col h-full border border-border rounded-lg overflow-hidden bg-background">
    <!-- 顶部 Token 统计条 -->
    <div class="flex items-center justify-between px-4 py-2 border-b border-border bg-muted/30 shrink-0">
      <div data-token-usage class="flex items-center gap-4 text-xs text-muted-foreground">
        <span>提示词：<strong class="text-foreground">{{ tokenUsage?.promptTokens ?? 0 }}</strong></span>
        <span>回复：<strong class="text-foreground">{{ tokenUsage?.completionTokens ?? 0 }}</strong></span>
        <span>总计：<strong class="text-foreground">{{ tokenUsage?.totalTokens ?? 0 }}</strong></span>
      </div>
      <Button
        variant="ghost"
        size="sm"
        class="text-xs text-muted-foreground hover:text-destructive"
        :disabled="messages.length === 0 && !isStreaming"
        @click="clearChat"
      >
        <Trash2 :size="14" class="mr-1" />
        清空对话
      </Button>
    </div>

    <!-- 消息列表 -->
    <div ref="messageListRef" class="flex-1 overflow-y-auto px-4 py-4 space-y-4">
      <!-- 空状态 -->
      <div v-if="messages.length === 0 && !isStreaming" class="flex flex-col items-center justify-center h-full text-muted-foreground">
        <Bot :size="32" class="mb-2 opacity-40" />
        <p class="text-sm">发送消息开始测试对话</p>
      </div>

      <!-- 消息气泡 -->
      <template v-for="msg in messages" :key="msg.id">
        <!-- 用户消息 -->
        <div v-if="msg.role === 'user'" class="flex justify-end">
          <div class="max-w-[80%] rounded-2xl rounded-tr-sm bg-primary text-primary-foreground px-4 py-2.5 shadow-sm">
            <p class="text-sm whitespace-pre-wrap">{{ msg.content }}</p>
            <span class="block text-[10px] opacity-60 mt-1 text-right">{{ formatTime(msg.timestamp) }}</span>
          </div>
        </div>

        <!-- 错误消息 -->
        <div v-else-if="msg.role === 'error'" class="flex justify-start">
          <div class="max-w-[80%] rounded-2xl rounded-tl-sm bg-destructive/10 border border-destructive/30 px-4 py-2.5">
            <div class="flex items-start gap-2">
              <AlertCircle :size="14" class="text-destructive shrink-0 mt-0.5" />
              <p class="text-sm text-destructive">{{ msg.content }}</p>
            </div>
            <span class="block text-[10px] text-destructive/60 mt-1">{{ formatTime(msg.timestamp) }}</span>
          </div>
        </div>

        <!-- Assistant 消息 -->
        <div v-else class="flex justify-start gap-2">
          <div class="shrink-0 w-7 h-7 rounded-full bg-primary/10 flex items-center justify-center text-primary mt-0.5">
            <Bot :size="12" />
          </div>
          <div class="max-w-[80%] space-y-1.5">
            <div class="rounded-2xl rounded-tl-sm bg-card border border-border px-4 py-2.5 shadow-sm">
              <StreamingText :content="msg.content" />
              <span class="block text-[10px] text-muted-foreground mt-1">{{ formatTime(msg.timestamp) }}</span>
            </div>

            <!-- 工具调用折叠区域 -->
            <div v-if="msg.toolsSummary?.length" class="ml-1">
              <button
                type="button"
                class="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
                @click="toggleTools(msg.id)"
              >
                <component :is="expandedTools.has(msg.id) ? ChevronDown : ChevronRight" :size="12" />
                <span>{{ msg.toolsSummary.length }} 次工具调用</span>
              </button>
              <div v-if="expandedTools.has(msg.id)" class="mt-1.5 space-y-1">
                <div
                  v-for="(tool, idx) in msg.toolsSummary"
                  :key="idx"
                  class="rounded-md border border-border bg-muted/30 px-3 py-2 text-xs"
                >
                  <div class="flex items-center gap-2">
                    <Badge :variant="tool.success ? 'secondary' : 'destructive'" class="text-[10px]">
                      {{ tool.success ? '成功' : '失败' }}
                    </Badge>
                    <span class="font-medium text-foreground">{{ tool.toolId }}</span>
                    <span v-if="tool.latencyMs" class="text-muted-foreground ml-auto">{{ tool.latencyMs }}ms</span>
                  </div>
                  <div v-if="tool.inputSummary" class="mt-1 text-muted-foreground">
                    <span class="text-muted-foreground/70">参数：</span>{{ tool.inputSummary }}
                  </div>
                  <div v-if="tool.outputSummary" class="mt-0.5 text-muted-foreground">
                    <span class="text-muted-foreground/70">结果：</span>{{ tool.outputSummary }}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </template>

      <!-- 流式响应中 -->
      <div v-if="isStreaming && streamingContent" class="flex justify-start gap-2">
        <div class="shrink-0 w-7 h-7 rounded-full bg-primary/10 flex items-center justify-center text-primary mt-0.5">
          <Bot :size="12" />
        </div>
        <div class="max-w-[80%] rounded-2xl rounded-tl-sm bg-card border border-border border-primary/40 px-4 py-2.5 shadow-sm">
          <StreamingText :content="streamingContent" :streaming="true" />
        </div>
      </div>

      <!-- 加载指示器（流式开始但还没有内容时） -->
      <div v-if="isStreaming && !streamingContent" class="flex justify-start gap-2">
        <div class="shrink-0 w-7 h-7 rounded-full bg-primary/10 flex items-center justify-center text-primary mt-0.5">
          <Bot :size="12" />
        </div>
        <div class="rounded-2xl rounded-tl-sm bg-card border border-border px-4 py-3">
          <Loader2 :size="14" class="animate-spin text-muted-foreground/60" />
        </div>
      </div>
    </div>

    <!-- 输入区域 -->
    <div class="border-t border-border px-4 py-3 bg-background shrink-0">
      <div class="flex items-end gap-2">
        <textarea
          v-model="inputText"
          :disabled="isStreaming"
          class="flex-1 resize-none rounded-lg border border-border bg-muted/30 px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-1 disabled:opacity-50"
          :rows="1"
          placeholder="输入测试消息..."
          @keydown="onKeydown"
        />
        <Button
          :disabled="!canSend"
          size="sm"
          class="shrink-0"
          @click="sendMessage"
        >
          <Loader2 v-if="isStreaming" :size="14" class="animate-spin" />
          <Send v-else :size="14" />
        </Button>
      </div>
    </div>
  </div>
</template>
