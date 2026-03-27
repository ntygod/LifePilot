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
import { chatApi, modelServiceApi } from '@/api/client'
import type { ModelService } from '@/api/client'
import type { ChatAttachment, ChatTurnAction, Message, SessionConfig } from '@/types'
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
import { useDatastoreStore } from '@/stores/datastore'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useChatStore } from '@/stores/chat'
import { useSkillStore } from '@/stores/skill'
import { useUiStore } from '@/stores/ui'
import { copyToClipboard } from '@/utils/clipboard'

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()
const datastoreStore = useDatastoreStore()
const kbStore = useKnowledgeBaseStore()
const skillStore = useSkillStore()
const uiStore = useUiStore()

const {
  sendMessage,
  executeTurn,
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
  activeInteraction,
  pendingPermissionApprovals,
  pendingPermissionApprovalResolutions,
  resolvePermissionApproval,
} = useChat()

const scrollContainer = ref<HTMLElement | null>(null)
const searchQuery = ref('')
const showDebugDrawer = ref(false)
const showSessionSidebar = ref(false)
const showConfigPanel = ref(false)
const providers = ref<ModelService[]>([])

const DEFAULT_SESSION_TEMPERATURE = 0.7
const DEFAULT_SESSION_MAX_TOKENS = 131072
const DEFAULT_SESSION_MAX_STEPS = 60
const DEFAULT_SESSION_MAX_DURATION_SECONDS = 300

const activeSessionConfig = ref<SessionConfig>({
  temperature: DEFAULT_SESSION_TEMPERATURE,
  maxTokens: DEFAULT_SESSION_MAX_TOKENS,
  maxSteps: DEFAULT_SESSION_MAX_STEPS,
  maxDurationSeconds: DEFAULT_SESSION_MAX_DURATION_SECONDS,
  knowledgeBaseIds: [],
  datastoreIds: [],
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
    temperature: DEFAULT_SESSION_TEMPERATURE,
    maxTokens: DEFAULT_SESSION_MAX_TOKENS,
    maxSteps: DEFAULT_SESSION_MAX_STEPS,
    maxDurationSeconds: DEFAULT_SESSION_MAX_DURATION_SECONDS,
    knowledgeBaseIds: [],
    datastoreIds: [],
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
      temperature: detail.temperature ?? DEFAULT_SESSION_TEMPERATURE,
      maxTokens: detail.maxTokens ?? DEFAULT_SESSION_MAX_TOKENS,
      maxSteps: detail.maxSteps ?? DEFAULT_SESSION_MAX_STEPS,
      maxDurationSeconds: detail.maxDurationSeconds ?? DEFAULT_SESSION_MAX_DURATION_SECONDS,
      knowledgeBaseIds: detail.knowledgeBaseIds ?? [],
      datastoreIds: detail.datastoreIds ?? [],
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

const latestSuspendedAssistant = computed<Message | null>(() => {
  const message = lastAssistantMessage.value
  if (!message) return null
  if (message.turnStatus === 'SUSPENDED' || message.completionMode === 'SUSPENDED') {
    return message
  }
  return null
})

function resolveContinuationDetail(message: Message | null) {
  if (!message) {
    return null
  }

  if (message.suspendReasonSourceId === '__await_user_input__') {
    return '你这次回复会直接接到刚才那轮任务上，我会沿着当前进度继续处理。'
  }

  const detail = message.errorMessage?.trim()
  if (!detail || detail === message.suspendReasonSourceId || detail === 'await_user_input' || detail === 'suspended' || detail.startsWith('__')) {
    return '你这次回复会直接接到刚才那轮任务上，我会沿着当前进度继续处理。'
  }

  return `当前卡住点：${detail}`
}

const continuationTitle = computed(() => {
  if (!latestSuspendedAssistant.value || isStreaming.value) {
    return null
  }
  return '正在继续上一轮任务'
})

const continuationDetail = computed(() => {
  if (!latestSuspendedAssistant.value || isStreaming.value) {
    return null
  }
  return resolveContinuationDetail(latestSuspendedAssistant.value)
})

const inputPlaceholder = computed(() => {
  if (continuationTitle.value) {
    return '回复补充信息，继续刚才的任务…'
  }
  return '输入问题，或粘贴资料继续往下处理…'
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
  void datastoreStore.fetchList()
  void skillStore.fetchSkills()

  try {
    providers.value = await modelServiceApi.listEnabledServices('GENERATION')
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
  sessionConfig?: SessionConfig
  restoreSessionConfig?: SessionConfig
}) {
  await sendMessage(
    payload.content,
    payload.attachmentIds,
    payload.attachments,
    payload.sessionConfig,
    payload.restoreSessionConfig,
  )
}

function handleEmptyStateSend(content: string) {
  void handleSend({ content })
}

function getAttachmentIds(message: Message): string[] | undefined {
  const attachmentIds = message.attachments?.map(attachment => attachment.fileId).filter(Boolean)
  return attachmentIds && attachmentIds.length > 0 ? attachmentIds : undefined
}

function findUserMessageByTurnId(turnId: string): Message | null {
  for (let index = chatStore.messages.length - 1; index >= 0; index -= 1) {
    const message = chatStore.messages[index]
    if (message.role === 'user' && message.turnId === turnId) return message
  }
  return null
}

async function handleTurnAction(message: Message, action: ChatTurnAction) {
  if (!message.turnId) {
    if (action === 'SEND' || action === 'RETRY') {
      await sendMessage(
        message.content,
        getAttachmentIds(message),
        message.attachments,
      )
    }
    return
  }

  const userMessage = message.role === 'assistant'
    ? findUserMessageByTurnId(message.turnId)
    : message

  await executeTurn(message.turnId, action, {
    content: action === 'RESUME' ? undefined : userMessage?.content,
    attachmentIds: userMessage ? getAttachmentIds(userMessage) : undefined,
    attachments: userMessage?.attachments,
    userMessageId: userMessage?.id,
  })
}

async function handleRetry(message: Message) {
  await handleTurnAction(message, message.turnId ? 'RETRY' : 'SEND')
}

async function handleRegenerate(assistantMessage: Message) {
  await handleTurnAction(assistantMessage, 'RESTART')
}

async function handleResume(assistantMessage: Message) {
  await handleTurnAction(assistantMessage, 'RESUME')
}

async function handleRestart(assistantMessage: Message) {
  await handleTurnAction(assistantMessage, 'RESTART')
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
      maxSteps: config.maxSteps ?? activeSessionConfig.value.maxSteps,
      maxDurationSeconds: config.maxDurationSeconds ?? activeSessionConfig.value.maxDurationSeconds,
      knowledgeBaseIds: config.knowledgeBaseIds ?? [],
      datastoreIds: config.datastoreIds ?? [],
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
</script>

<template>
  <div class="flex h-full flex-col overflow-hidden">
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
              type="button"
              variant="outline"
              size="sm"
              :class="showConfigPanel && 'status-btn-active'"
              @click="togglePanel('config')"
            >
              <Settings2 class="size-4" />
              配置
            </Button>
            <Button
              v-if="chatStore.activeSessionId"
              type="button"
              variant="outline"
              size="sm"
              :class="showSessionSidebar && 'status-btn-active'"
              @click="togglePanel('sidebar')"
            >
              <LibraryBig class="size-4" />
              详情
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
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
          <span
            v-if="hasMessageSearch"
            class="surface-chip surface-chip-strong px-2 py-0.5 text-xs"
          >
            命中 {{ matchedMessageCount }}
          </span>
          <div class="ml-auto flex items-center gap-1.5">
            <RouterLink
              :to="lastTraceTarget"
              class="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-accent/60 hover:text-foreground"
            >
              轨迹
            </RouterLink>
            <Button
              v-if="chatStore.activeSessionId && chatStore.messages.length > 0"
              type="button"
              variant="ghost"
              size="sm"
              class="h-7 text-xs"
              @click="handleClearSession"
            >
              清空
            </Button>
          </div>
        </div>
      </div>
    </header>

    <div class="relative min-h-0 flex-1 overflow-hidden">
      <div
        ref="scrollContainer"
        class="h-full overflow-y-auto scrollbar-thin scrollbar-track-transparent scrollbar-thumb-border"
      >
        <div v-if="chatStore.messages.length === 0 && !isStreaming" class="flex h-full items-center justify-center p-6">
          <EmptyState @send="handleEmptyStateSend" />
        </div>
        <div v-else class="mx-auto w-full max-w-5xl px-4 py-6 sm:px-6 xl:px-8">
          <MessageList
            :messages="chatStore.messages"
            :is-streaming="isStreaming"
            :streaming-content="chatStore.streamingContent"
            :streaming-reasoning-events="reasoningEvents"
            :streaming-react-steps="streamingReactSteps"
            :streaming-a2ui-components="streamingA2uiComponents"
            :streaming-permission-approvals="pendingPermissionApprovals"
            :streaming-permission-approval-resolutions="pendingPermissionApprovalResolutions"
            :query="searchQuery"
            @retry="handleRetry"
            @like="handleLike"
            @dislike="handleDislike"
            @fork="handleFork"
            @regenerate="handleRegenerate"
            @resume="handleResume"
            @restart="handleRestart"
            @copy="handleCopy"
            @permission-approval-resolve="resolvePermissionApproval"
          />
        </div>
      </div>

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
              :max-steps="activeSessionConfig.maxSteps"
              :max-duration-seconds="activeSessionConfig.maxDurationSeconds"
              :knowledge-base-ids="activeSessionConfig.knowledgeBaseIds"
              :datastore-ids="activeSessionConfig.datastoreIds"
              :providers="chatProviders"
              :knowledge-bases="kbStore.list"
              :datastores="datastoreStore.list"
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
        <ChatInput
          :disabled="isStreaming && !activeInteraction"
          :placeholder="inputPlaceholder"
          :continuation-title="continuationTitle"
          :continuation-detail="continuationDetail"
          :knowledge-bases="kbStore.list"
          :datastores="datastoreStore.list"
          :base-session-config="activeSessionConfig"
          @send="handleSend"
        />
      </div>
    </div>
  </div>
</template>
