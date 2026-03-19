<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  LibraryBig,
  Search,
  Settings2,
  SlidersHorizontal,
  Square,
} from 'lucide-vue-next'
import { chatApi, llmProviderApi } from '@/api/client'
import type { LlmProvider } from '@/api/client'
import type { ChatAttachment, Message, SessionConfig } from '@/types'
import StatePanel from '@/components/common/StatePanel.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import ChatInput from '@/components/chat/ChatInput.vue'
import DebugDrawer from '@/components/chat/DebugDrawer.vue'
import EmptyState from '@/components/chat/EmptyState.vue'
import MessageList from '@/components/chat/MessageList.vue'
import SessionConfigPanel from '@/components/chat/SessionConfigPanel.vue'
import SessionSidebar from '@/components/chat/SessionSidebar.vue'
import { useChat } from '@/composables/useChat'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useChatStore } from '@/stores/chat'
import { useSkillStore } from '@/stores/skill'
import { useUiStore } from '@/stores/ui'
import { copyToClipboard } from '@/utils/clipboard'

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
  streamingReactSteps,
  streamingA2uiComponents,
  pendingToolConfirmation,
  pendingToolConfirmationResolution,
  resolveToolConfirmation,
} = useChat()

const scrollContainer = ref<HTMLElement | null>(null)
const searchQuery = ref('')
const showDebugDrawer = ref(false)
const showSessionSidebar = ref(false)
const showConfigPanel = ref(false)
const providers = ref<LlmProvider[]>([])
const activeSessionConfig = ref<SessionConfig>({
  temperature: 0.7,
  maxTokens: 2000,
  knowledgeBaseIds: [],
})

const CHAT_SCENES = new Set([
  'chat',
  'agent_react',
])

const chatProviders = computed(() =>
  providers.value.filter(provider =>
    (
      !provider.capabilities
      || provider.capabilities.length === 0
      || provider.capabilities.some(capability => capability.toLowerCase() === 'chat')
    ) && (
      !provider.scenes
      || provider.scenes.length === 0
      || provider.scenes.some(scene => CHAT_SCENES.has(scene))
    ),
  ),
)

const currentSession = computed(() => {
  if (!chatStore.activeSessionId) return null
  return chatStore.sessions.find(session => session.id === chatStore.activeSessionId) || null
})

function resetActiveSessionConfig() {
  activeSessionConfig.value = {
    temperature: 0.7,
    maxTokens: 2000,
    knowledgeBaseIds: [],
  }
}

async function loadActiveSessionConfig(sessionId: string | null) {
  if (!sessionId) {
    resetActiveSessionConfig()
    return
  }

  try {
    const detail = await chatApi.getSession(sessionId)
    if (chatStore.activeSessionId !== sessionId) return

    activeSessionConfig.value = {
      preferredProviderId: detail.preferredProviderId ?? undefined,
      temperature: detail.temperature ?? 0.7,
      maxTokens: detail.maxTokens ?? 2000,
      knowledgeBaseIds: detail.knowledgeBaseIds ?? [],
    }
  } catch (event) {
    console.warn('加载会话配置失败:', event)
    if (chatStore.activeSessionId === sessionId) {
      resetActiveSessionConfig()
    }
  }
}

const hasMessageSearch = computed(() => searchQuery.value.trim().length > 0)
const matchedMessageCount = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  if (!query) return chatStore.messages.length

  return chatStore.messages.filter(message => message.content.toLowerCase().includes(query)).length
})

const lastAssistantMessage = computed(() => {
  for (let index = chatStore.messages.length - 1; index >= 0; index -= 1) {
    if (chatStore.messages[index].role === 'assistant') return chatStore.messages[index]
  }
  return null
})

const latestTraceMessage = computed(() => {
  for (let index = chatStore.messages.length - 1; index >= 0; index -= 1) {
    if (chatStore.messages[index].traceId) return chatStore.messages[index]
  }
  return null
})

const latestUserErrorMessage = computed(() => {
  for (let index = chatStore.messages.length - 1; index >= 0; index -= 1) {
    const message = chatStore.messages[index]
    if (message.role === 'user' && message.status === 'error') return message
  }
  return null
})

const showGlobalErrorPanel = computed(() => (
  Boolean(error.value)
  && (!latestUserErrorMessage.value || latestUserErrorMessage.value.errorMessage !== error.value)
))

const lastToolsSummary = computed(() => lastAssistantMessage.value?.toolsSummary ?? [])
const lastSources = computed(() => lastAssistantMessage.value?.sources ?? [])
const lastKbSources = computed(() => lastSources.value.filter(source => source.type === 'knowledgeBase'))
const lastTraceTarget = computed(() => (
  latestTraceMessage.value?.traceId
    ? { name: 'traces', query: { id: latestTraceMessage.value.traceId } }
    : { name: 'traces' }
))

const headerTitle = computed(() => currentSession.value?.title?.trim() || '新对话')
const contextLabel = computed(() => lastModelId.value || '默认模型')

onMounted(async () => {
  const sessionId = route.params.sessionId as string | undefined
  if (sessionId && sessionId !== chatStore.activeSessionId) {
    chatStore.activeSessionId = sessionId
  } else if (!sessionId && !chatStore.activeSessionId) {
    try {
      await chatStore.startNewSession()
    } catch (event) {
      console.error('创建新会话失败:', event)
    }
  }

  void kbStore.fetchList()
  void skillStore.fetchSkills()
  try {
    providers.value = await llmProviderApi.listEnabledProviders()
  } catch {
    // Provider 列表拉取失败不阻塞页面。
  }
})

watch(
  () => route.params.sessionId as string | undefined,
  async (sessionId) => {
    if (!sessionId) {
      chatStore.activeSessionId = null
      try {
        await chatStore.startNewSession()
      } catch (event) {
        console.error('创建新会话失败:', event)
      }
      return
    }

    if (sessionId !== chatStore.activeSessionId) {
      chatStore.activeSessionId = sessionId
    }
  },
)

watch(
  () => chatStore.activeSessionId,
  sessionId => {
    void loadActiveSessionConfig(sessionId)
  },
  { immediate: true },
)

function scrollToBottom() {
  nextTick(() => {
    if (scrollContainer.value) {
      scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight
    }
  })
}

watch(() => chatStore.messages.length, scrollToBottom)
watch(() => chatStore.streamingContent, scrollToBottom)

async function handleSend(payload: {
  content: string
  attachmentIds?: string[]
  attachments?: ChatAttachment[]
  sessionConfig?: {
    preferredProviderId?: string
    temperature?: number
    maxTokens?: number
    knowledgeBaseIds?: string[]
  }
}) {
  await sendMessage(payload.content, payload.attachmentIds, payload.attachments, payload.sessionConfig)
}

function handleEmptyStateSend(content: string) {
  void handleSend({ content })
}

async function handleRetry(message: Message) {
  if (message.status === 'error') {
    const index = chatStore.messages.findIndex(item => item.id === message.id)
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

async function handleRegenerate(assistantMessage: Message) {
  const sorted = [...chatStore.messages].sort((left, right) => left.timestamp - right.timestamp)
  const assistantIndex = sorted.findIndex(message => message.id === assistantMessage.id)
  if (assistantIndex < 0) return

  let userMessage: Message | null = null
  for (let index = assistantIndex - 1; index >= 0; index -= 1) {
    if (sorted[index].role === 'user') {
      userMessage = sorted[index]
      break
    }
  }
  if (!userMessage) return

  const removeIndex = chatStore.messages.findIndex(message => message.id === assistantMessage.id)
  if (removeIndex !== -1) {
    chatStore.messages.splice(removeIndex, 1)
  }

  await sendMessage(userMessage.content)
}

async function handleFork(message: Message) {
  if (!chatStore.activeSessionId) return
  try {
    const newSession = await chatApi.forkSession(chatStore.activeSessionId, message.id)
    uiStore.showToast('success', '会话分叉成功')
    router.push({ name: 'conversationDetail', params: { sessionId: newSession.id } })
  } catch (event) {
    uiStore.showToast('error', '分叉会话失败，请稍后重试')
    console.error('分叉会话失败:', event)
  }
}

async function handleCopy(content: string) {
  const ok = await copyToClipboard(content)
  uiStore.showToast(ok ? 'success' : 'error', ok ? '已复制到剪贴板' : '复制失败')
}

async function handleLike(message: Message) {
  try {
    await chatApi.submitFeedback(message.id, 'like')
  } catch {
    uiStore.showToast('error', '反馈提交失败')
  }
}

async function handleDislike(message: Message, feedback?: string) {
  try {
    await chatApi.submitFeedback(message.id, 'dislike', feedback)
  } catch {
    uiStore.showToast('error', '反馈提交失败')
  }
}

async function handleClearSession() {
  await chatStore.clearCurrentSessionMessages()
}

async function handleConfigUpdate(config: SessionConfig) {
  if (!chatStore.activeSessionId) return
  try {
    await chatApi.updateSessionConfig(chatStore.activeSessionId, config)
    activeSessionConfig.value = {
      preferredProviderId: config.preferredProviderId,
      temperature: config.temperature ?? activeSessionConfig.value.temperature,
      maxTokens: config.maxTokens ?? activeSessionConfig.value.maxTokens,
      knowledgeBaseIds: config.knowledgeBaseIds ?? [],
    }
    uiStore.showToast('success', '配置已更新')
  } catch {
    uiStore.showToast('error', '配置更新失败')
  }
}

async function handleUpdateSessionTitle(title: string) {
  if (!chatStore.activeSessionId || !title.trim()) return
  try {
    await chatApi.updateSession(chatStore.activeSessionId, { title: title.trim() })
    await chatStore.loadSessions()
  } catch {
    uiStore.showToast('error', '更新会话标题失败')
  }
}

function togglePanel(panel: 'config' | 'sidebar' | 'debug') {
  if (panel === 'config') {
    showConfigPanel.value = !showConfigPanel.value
    showSessionSidebar.value = false
    showDebugDrawer.value = false
  } else if (panel === 'sidebar') {
    showSessionSidebar.value = !showSessionSidebar.value
    showConfigPanel.value = false
    showDebugDrawer.value = false
  } else {
    showDebugDrawer.value = !showDebugDrawer.value
    showConfigPanel.value = false
    showSessionSidebar.value = false
  }
}

function closeInspectorPanels() {
  showSessionSidebar.value = false
  showDebugDrawer.value = false
  showConfigPanel.value = false
}
</script>

<template>
  <div class="flex h-full flex-col overflow-hidden">
    <!-- 头部：标题 + 操作按钮 + 搜索栏 -->
    <header class="shrink-0 border-b border-border/60 bg-background px-4 py-3 sm:px-6">
      <div class="mx-auto max-w-[1460px]">
        <div class="flex items-center justify-between gap-4">
          <div class="min-w-0">
            <h1 class="truncate text-lg font-semibold text-foreground">{{ headerTitle }}</h1>
            <div class="mt-0.5 flex items-center gap-2 text-sm text-muted-foreground">
              <span>{{ chatStore.messages.length }} 条消息</span>
              <span class="text-border">·</span>
              <span>{{ contextLabel }}</span>
              <span v-if="isStreaming" class="text-primary">{{ reasoningStatusText || '生成中' }}</span>
            </div>
          </div>
          <div class="flex items-center gap-2">
            <Button
              type="button" variant="outline" size="sm"
              :class="showConfigPanel && 'status-btn-active'"
              @click="togglePanel('config')"
            >
              <Settings2 class="size-4" />
              配置
            </Button>
            <Button
              v-if="chatStore.activeSessionId"
              type="button" variant="outline" size="sm"
              :class="showSessionSidebar && 'status-btn-active'"
              @click="togglePanel('sidebar')"
            >
              <LibraryBig class="size-4" />
              信息
            </Button>
            <Button
              type="button" variant="outline" size="sm"
              :class="showDebugDrawer && 'status-btn-active'"
              @click="togglePanel('debug')"
            >
              <SlidersHorizontal class="size-4" />
              调试
            </Button>
            <Button v-if="isStreaming" type="button" variant="destructive" size="sm" @click="abort">
              <Square class="size-4" />
              停止
            </Button>
          </div>
        </div>
        <!-- 搜索工具栏：搜索框 + pills + 操作合并为一行 -->
        <div class="mt-2 flex items-center gap-2 border-t border-border/30 pt-2">
          <div class="relative w-48 shrink-0">
            <Search class="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
            <Input
              v-model="searchQuery"
              type="search"
              placeholder="搜索消息…"
              class="h-7 pl-8 text-xs focus-visible:ring-1"
            />
          </div>
          <span class="surface-chip px-2 py-0.5 text-xs">工具 {{ lastToolsSummary.length }}</span>
          <span class="surface-chip px-2 py-0.5 text-xs">知识库 {{ lastKbSources.length }}</span>
          <span v-if="hasMessageSearch" class="surface-chip surface-chip-strong px-2 py-0.5 text-xs">命中 {{ matchedMessageCount }}</span>
          <div class="ml-auto flex items-center gap-1.5">
            <RouterLink
              :to="lastTraceTarget"
              class="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-accent/60 hover:text-foreground"
            >
              轨迹
            </RouterLink>
            <Button
              v-if="chatStore.activeSessionId && chatStore.messages.length > 0"
              type="button" variant="ghost" size="sm" class="h-7 text-xs"
              @click="handleClearSession"
            >
              清空
            </Button>
          </div>
        </div>
      </div>
    </header>

    <!-- 中间：消息滚动区 -->
    <div class="relative min-h-0 flex-1 overflow-hidden">
      <div
        ref="scrollContainer"
        class="h-full overflow-y-auto scrollbar-thin scrollbar-track-transparent scrollbar-thumb-border"
      >
        <div v-if="chatStore.messages.length === 0 && !isStreaming" class="flex h-full items-center justify-center p-6">
          <EmptyState @send="handleEmptyStateSend" />
        </div>
        <div v-else class="mx-auto w-full max-w-4xl px-4 py-6 sm:px-6 xl:px-8">
          <MessageList
            :messages="chatStore.messages"
            :is-streaming="isStreaming"
            :streaming-content="chatStore.streamingContent"
            :streaming-reasoning-events="reasoningEvents"
            :streaming-react-steps="streamingReactSteps"
            :streaming-a2ui-components="streamingA2uiComponents"
            :streaming-tool-confirmation="pendingToolConfirmation"
            :streaming-tool-confirmation-resolution="pendingToolConfirmationResolution"
            :query="searchQuery"
            @retry="handleRetry"
            @like="handleLike"
            @dislike="handleDislike"
            @fork="handleFork"
            @regenerate="handleRegenerate"
            @copy="handleCopy"
            @tool-confirm-resolve="resolveToolConfirmation"
          />
        </div>
      </div>

      <!-- 侧边栏浮层（覆盖在消息区右侧，不挤占布局） -->
      <Transition
        enter-active-class="transition-all duration-250 ease-out"
        enter-from-class="opacity-0 translate-x-4"
        enter-to-class="opacity-100 translate-x-0"
        leave-active-class="transition-all duration-200 ease-in"
        leave-from-class="opacity-100 translate-x-0"
        leave-to-class="opacity-0 translate-x-4"
      >
        <div
          v-if="showSessionSidebar || showDebugDrawer || showConfigPanel"
          class="absolute inset-y-0 right-0 z-30 w-[320px] border-l border-border/60 bg-background/95 shadow-lg backdrop-blur-sm"
        >
          <div class="flex h-full flex-col gap-4 overflow-y-auto p-3">
            <SessionConfigPanel
              v-if="showConfigPanel"
              :preferred-provider-id="activeSessionConfig.preferredProviderId"
              :temperature="activeSessionConfig.temperature"
              :max-tokens="activeSessionConfig.maxTokens"
              :knowledge-base-ids="activeSessionConfig.knowledgeBaseIds"
              :providers="chatProviders"
              :knowledge-bases="kbStore.list"
              @close="showConfigPanel = false"
              @update="handleConfigUpdate"
            />
            <SessionSidebar
              v-if="showSessionSidebar && chatStore.activeSessionId"
              :session="currentSession"
              :knowledge-bases="kbStore.list"
              :message-count="chatStore.messages.length"
              @close="showSessionSidebar = false"
              @update-title="handleUpdateSessionTitle"
            />
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
      </Transition>
    </div>

    <!-- 底部固定：错误/流式状态 + 输入框 -->
    <div class="shrink-0 border-t border-border/60 bg-background px-4 pb-3 pt-2 sm:px-6">
      <div class="mx-auto max-w-[1460px]">
        <StatePanel
          v-if="showGlobalErrorPanel"
          class="mb-2"
          title="本轮对话出现错误"
          :description="error ?? undefined"
          tone="danger"
        >
          <template #actions>
            <Button type="button" variant="outline" size="sm" @click="error = null">
              关闭
            </Button>
          </template>
        </StatePanel>
        <ChatInput :disabled="isStreaming" @send="handleSend" />
      </div>
    </div>

  </div>
</template>
