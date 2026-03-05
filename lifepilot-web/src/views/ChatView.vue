<script setup lang="ts">
import { ref, nextTick, watch, onMounted, computed } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useSkillStore } from '@/stores/skill'
import { useUiStore } from '@/stores/ui'
import { useChat } from '@/composables/useChat'
import { chatApi, llmProviderApi } from '@/api/client'
import type { Message, ChatAttachment, SessionConfig } from '@/types'
import type { LlmProvider } from '@/api/client'
import { Info, Puzzle, LibraryBig, SlidersHorizontal, Settings2 } from 'lucide-vue-next'
import { copyToClipboard } from '@/utils/clipboard'
import MessageList from '@/components/chat/MessageList.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import EmptyState from '@/components/chat/EmptyState.vue'
import DebugDrawer from '@/components/chat/DebugDrawer.vue'
import SessionSidebar from '@/components/chat/SessionSidebar.vue'
import SessionConfigPanel from '@/components/chat/SessionConfigPanel.vue'

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()
const kbStore = useKnowledgeBaseStore()
const skillStore = useSkillStore()
const uiStore = useUiStore()
const {
  sendMessage,
  isStreaming,
  error,
  abort,
  lastModelId,
  lastTokenUsage,
  lastPrompt,
  reasoningStatusText,
  reasoningEvents,
} = useChat()

const scrollContainer = ref<HTMLElement>()
const searchQuery = ref('')
// 面板开关
const showDebugDrawer = ref(false)
const showSessionSidebar = ref(false)
const showConfigPanel = ref(false)

// LLM Provider 列表（用于 SessionConfigPanel）
const providers = ref<LlmProvider[]>([])

// 顶部上下文指示条数据
const hasKnowledgeBases = computed(() => kbStore.list.length > 0)
const hasSkills = computed(() => skillStore.skills.length > 0)

// 当前会话信息
const currentSession = computed(() => {
  if (!chatStore.activeSessionId) return null
  return chatStore.sessions.find(s => s.id === chatStore.activeSessionId) || null
})

// 最近一条 AI 消息及其执行摘要
const lastAssistantMessage = computed(() => {
  for (let i = chatStore.messages.length - 1; i >= 0; i -= 1) {
    if (chatStore.messages[i].role === 'assistant') return chatStore.messages[i]
  }
  return null
})

const lastToolsSummary = computed(() => lastAssistantMessage.value?.toolsSummary ?? [])
const lastSources = computed(() => lastAssistantMessage.value?.sources ?? [])
const lastKbSources = computed(() => lastSources.value.filter(s => s.type === 'knowledgeBase'))

onMounted(async () => {
  // 根据路由参数确定当前会话
  const sessionId = route.params.sessionId as string | undefined
  if (sessionId && sessionId !== chatStore.activeSessionId) {
    chatStore.activeSessionId = sessionId
  }
  // 拉取知识库 / Skill / Provider 列表
  void kbStore.fetchList()
  void skillStore.fetchSkills()
  try {
    providers.value = await llmProviderApi.listProviders()
  } catch {
    // Provider 列表拉取失败不阻塞页面
  }
})

// 路由变化时同步 activeSessionId
watch(
  () => route.params.sessionId as string | undefined,
  (sessionId) => {
    if (!sessionId) {
      chatStore.activeSessionId = null
      return
    }
    if (sessionId !== chatStore.activeSessionId) {
      chatStore.activeSessionId = sessionId
    }
  }
)

// 自动滚动到底部
function scrollToBottom() {
  nextTick(() => {
    if (scrollContainer.value) {
      scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight
    }
  })
}
watch(() => chatStore.messages.length, scrollToBottom)
watch(() => chatStore.streamingContent, scrollToBottom)

// ── 事件处理 ──

async function handleSend(payload: {
  content: string
  attachmentIds?: string[]
  attachments?: ChatAttachment[]
  sessionConfig?: {
    modelId?: string
    temperature?: number
    maxTokens?: number
    knowledgeBaseIds?: string[]
  }
}) {
  await sendMessage(payload.content, payload.attachmentIds, payload.attachments, payload.sessionConfig)
}

/** 空状态示例问题点击 */
function handleEmptyStateSend(content: string) {
  handleSend({ content })
}

async function handleRetry(message: Message) {
  if (message.status === 'error') {
    const index = chatStore.messages.findIndex(m => m.id === message.id)
    if (index !== -1) {
      chatStore.messages.splice(index, 1)
      if (message.role === 'user') {
        const nextMessage = chatStore.messages[index]
        if (nextMessage && nextMessage.role === 'assistant') {
          chatStore.messages.splice(index, 1)
        }
      }
    }
  }
  await sendMessage(message.content)
}

/** 重新生成：找到最后一条 AI 消息对应的用户消息，删除 AI 消息后重新发送 */
async function handleRegenerate(assistantMsg: Message) {
  // 找到该 AI 消息之前最近的用户消息
  const sorted = [...chatStore.messages].sort((a, b) => a.timestamp - b.timestamp)
  const aiIdx = sorted.findIndex(m => m.id === assistantMsg.id)
  if (aiIdx < 0) return

  let userMsg: Message | null = null
  for (let i = aiIdx - 1; i >= 0; i--) {
    if (sorted[i].role === 'user') {
      userMsg = sorted[i]
      break
    }
  }
  if (!userMsg) return

  // 删除该 AI 消息
  const removeIdx = chatStore.messages.findIndex(m => m.id === assistantMsg.id)
  if (removeIdx !== -1) {
    chatStore.messages.splice(removeIdx, 1)
  }

  // 重新发送用户消息内容
  await sendMessage(userMsg.content)
}

/** 分叉会话 */
async function handleFork(message: Message) {
  if (!chatStore.activeSessionId) return
  try {
    const newSession = await chatApi.forkSession(chatStore.activeSessionId, message.id)
    uiStore.showToast('success', '会话分叉成功')
    router.push({ name: 'conversationDetail', params: { sessionId: newSession.id } })
  } catch (e) {
    uiStore.showToast('error', '分叉会话失败，请稍后重试')
    console.error('分叉会话失败:', e)
  }
}

/** 复制消息内容 */
async function handleCopy(content: string) {
  const ok = await copyToClipboard(content)
  uiStore.showToast(ok ? 'success' : 'error', ok ? '已复制到剪贴板' : '复制失败')
}

/** 点赞 */
async function handleLike(message: Message) {
  try {
    await chatApi.submitFeedback(message.id, 'like')
  } catch {
    uiStore.showToast('error', '反馈提交失败')
  }
}

/** 点踩 */
async function handleDislike(message: Message, feedback?: string) {
  try {
    await chatApi.submitFeedback(message.id, 'dislike', feedback)
  } catch {
    uiStore.showToast('error', '反馈提交失败')
  }
}

/** 清空当前会话 */
async function handleClearSession() {
  await chatStore.clearCurrentSessionMessages()
}

/** 会话配置更新 */
async function handleConfigUpdate(config: SessionConfig) {
  if (!chatStore.activeSessionId) return
  try {
    await chatApi.updateSessionConfig(chatStore.activeSessionId, config)
    uiStore.showToast('success', '配置已更新')
  } catch {
    uiStore.showToast('error', '配置更新失败')
  }
}

/** 更新会话标题 */
async function handleUpdateSessionTitle(title: string) {
  if (!chatStore.activeSessionId || !title.trim()) return
  try {
    await chatApi.updateSession(chatStore.activeSessionId, { title: title.trim() })
    await chatStore.loadSessions()
  } catch {
    uiStore.showToast('error', '更新会话标题失败')
  }
}
</script>

<template>
  <div class="flex flex-col h-full">
    <!-- 顶部上下文指示条 -->
    <div class="sticky top-0 z-20 border-b border-border/30 bg-card/60 backdrop-blur-xl dark:bg-card/40 dark:backdrop-blur-2xl">
      <div class="max-w-[1200px] mx-auto h-16 flex items-center justify-between px-md md:px-lg">
        <!-- 左侧 pills -->
        <div class="flex items-center gap-sm text-xs font-medium text-muted-foreground min-w-0">
          <div
            class="inline-flex items-center gap-xs px-sm py-xs rounded-full bg-muted/50 border border-border hover:bg-muted/70 transition-colors"
            title="上下文配置"
          >
            <span class="inline-flex w-1.5 h-1.5 rounded-full bg-green-500" />
            <span class="truncate">上下文：{{ lastModelId ? lastModelId : '默认' }}</span>
          </div>

          <div
            class="hidden md:inline-flex items-center gap-xs px-sm py-xs rounded-full bg-muted/50 border border-border hover:bg-muted/70 transition-colors"
            title="技能状态"
          >
            <Puzzle :size="14" class="text-muted-foreground" />
            <span>技能：{{ hasSkills ? `${skillStore.skills.length} 个活跃` : '未启用' }}</span>
          </div>

          <div
            class="hidden md:inline-flex items-center gap-xs px-sm py-xs rounded-full bg-muted/50 border border-border hover:bg-muted/70 transition-colors"
            title="知识库状态"
          >
            <LibraryBig :size="14" class="text-muted-foreground" />
            <span>知识库：{{ hasKnowledgeBases ? `已配置 ${kbStore.list.length} 个` : '未挂载' }}</span>
          </div>

          <div
            v-if="reasoningStatusText"
            class="hidden lg:inline-flex items-center gap-xs px-sm py-xs rounded-full bg-muted/50 border border-border"
          >
            <span>状态：{{ reasoningStatusText }}</span>
          </div>
        </div>

        <!-- 右侧操作 -->
        <div class="flex items-center gap-sm shrink-0">
          <!-- 会话配置按钮 -->
          <button
            v-if="chatStore.activeSessionId"
            type="button"
            class="p-2 rounded-full text-muted-foreground hover:text-foreground hover:bg-muted/60 transition-colors
                   focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
            :title="showConfigPanel ? '隐藏配置' : '会话配置'"
            @click="showConfigPanel = !showConfigPanel"
          >
            <Settings2 :size="18" />
          </button>

          <button
            v-if="chatStore.activeSessionId"
            type="button"
            class="p-2 rounded-full text-muted-foreground hover:text-foreground hover:bg-muted/60 transition-colors
                   focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
            :title="showSessionSidebar ? '隐藏会话信息' : '会话信息'"
            @click="showSessionSidebar = !showSessionSidebar"
          >
            <Info :size="18" />
          </button>

          <button
            type="button"
            class="inline-flex items-center gap-xs px-sm py-xs rounded-lg border border-border text-sm font-medium
                   text-muted-foreground hover:text-foreground hover:bg-muted/50 transition-colors
                   focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
            :class="showDebugDrawer ? 'bg-muted/60 text-foreground' : ''"
            @click="showDebugDrawer = !showDebugDrawer"
          >
            <SlidersHorizontal :size="16" />
            <span>调试视图</span>
          </button>
        </div>
      </div>
    </div>

    <!-- 消息区域 + 右侧侧栏 -->
    <div class="flex-1 overflow-hidden">
      <div class="max-w-[1200px] mx-auto flex h-full overflow-hidden">
        <!-- 消息区域 -->
        <div
          ref="scrollContainer"
          class="flex-1 overflow-y-auto scrollbar-thin scrollbar-track-transparent scrollbar-thumb-border"
        >
          <!-- 空状态 -->
          <EmptyState
            v-if="chatStore.messages.length === 0 && !isStreaming"
            @send="handleEmptyStateSend"
          />

          <!-- 消息列表 + 顶部搜索 / 过滤条 -->
          <div v-else class="max-w-4xl mx-auto p-md md:p-xl space-y-xl pb-24">
            <!-- 会话配置面板（内联在消息区顶部） -->
            <SessionConfigPanel
              v-if="showConfigPanel"
              :model-id="lastModelId ?? undefined"
              :providers="providers"
              :knowledge-bases="kbStore.list"
              @update="handleConfigUpdate"
              @close="showConfigPanel = false"
            />

            <div class="flex items-center gap-sm">
              <input
                v-model="searchQuery"
                type="search"
                placeholder="在当前对话中搜索（按内容关键字）…"
                class="flex-1 h-9 rounded-lg border border-input bg-background px-3 text-sm
                       placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent transition-all duration-200"
              />
              <span class="text-xs text-muted-foreground whitespace-nowrap">
                共 {{ chatStore.messages.length }} 条
              </span>
              <button
                v-if="chatStore.activeSessionId && chatStore.messages.length > 0"
                type="button"
                class="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg border border-border text-xs font-medium
                       text-muted-foreground hover:text-destructive hover:border-destructive/70 hover:bg-destructive/5
                       transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
                @click="handleClearSession"
              >
                清空当前会话
              </button>
            </div>

            <MessageList
              :messages="chatStore.messages"
              :is-streaming="isStreaming"
              :streaming-content="chatStore.streamingContent"
              :query="searchQuery"
              @retry="handleRetry"
              @like="handleLike"
              @dislike="handleDislike"
              @fork="handleFork"
              @regenerate="handleRegenerate"
              @copy="handleCopy"
            />
          </div>
        </div>

        <!-- 右侧会话信息侧栏 -->
        <SessionSidebar
          v-if="showSessionSidebar && chatStore.activeSessionId"
          :session="currentSession"
          :knowledge-bases="kbStore.list"
          :message-count="chatStore.messages.length"
          @close="showSessionSidebar = false"
          @update-title="handleUpdateSessionTitle"
        />

        <!-- 右侧调试抽屉 -->
        <DebugDrawer
          v-if="showDebugDrawer"
          :token-usage="lastTokenUsage"
          :model-id="lastModelId"
          :prompt="lastPrompt"
          :reasoning-events="reasoningEvents"
          :tools-summary="lastToolsSummary"
          :kb-sources="lastKbSources"
          :trace-id="lastAssistantMessage?.traceId"
          @close="showDebugDrawer = false"
        />
      </div>
    </div>

    <!-- 错误提示 -->
    <div
      v-if="error"
      class="px-md md:px-lg py-md bg-destructive/10 text-destructive text-sm flex items-center justify-center gap-sm"
    >
      <span>{{ error }}</span>
      <button
        class="underline-offset-2 hover:underline"
        type="button"
        @click="error = null"
      >
        关闭
      </button>
      <RouterLink
        :to="{ name: 'traces' }"
        class="underline-offset-2 hover:underline"
      >
        查看执行轨迹
      </RouterLink>
    </div>

    <!-- 流式进行中提示 -->
    <div v-if="isStreaming" class="flex justify-center items-center gap-2 py-2 text-xs text-muted-foreground">
      <span class="inline-flex h-2 w-2 rounded-full bg-primary animate-pulse" />
      <span>正在生成回答…</span>
      <button
        class="underline-offset-2 hover:underline hover:text-foreground transition-colors"
        @click="abort"
      >
        停止生成
      </button>
    </div>

    <!-- 输入框 -->
    <ChatInput :disabled="isStreaming" @send="handleSend" />
  </div>
</template>
