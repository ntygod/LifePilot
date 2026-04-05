<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  EllipsisVertical,
  LibraryBig,
  Settings2,
  SlidersHorizontal,
  Square,
} from 'lucide-vue-next'
import { chatApi, modelServiceApi } from '@/api/client'
import type { ModelService } from '@/api/client'
import type { ChatAttachment, ChatSessionDetail, ChatTurnAction, Message, SessionConfig } from '@/types'
import { logger } from '@/utils/logger'
import StatePanel from '@/components/common/StatePanel.vue'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
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

type SidebarPanel = 'session' | 'config' | 'debug'

const scrollContainer = ref<HTMLElement | null>(null)
const searchQuery = ref('')
const activeSidebarPanel = ref<SidebarPanel>('session')
const showMobileSidebar = ref(false)
const providers = ref<ModelService[]>([])

const DEFAULT_SESSION_TEMPERATURE = 0.7
const DEFAULT_SESSION_MAX_STEPS = 60
const DEFAULT_SESSION_MAX_DURATION_SECONDS = 300

const activeSessionConfig = ref<SessionConfig>({
  temperature: DEFAULT_SESSION_TEMPERATURE,
  maxSteps: DEFAULT_SESSION_MAX_STEPS,
  maxDurationSeconds: DEFAULT_SESSION_MAX_DURATION_SECONDS,
  knowledgeBaseIds: [],
  datastoreIds: [],
})
const currentSessionDetail = ref<ChatSessionDetail | null>(null)

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
    maxSteps: DEFAULT_SESSION_MAX_STEPS,
    maxDurationSeconds: DEFAULT_SESSION_MAX_DURATION_SECONDS,
    knowledgeBaseIds: [],
    datastoreIds: [],
  }
}

async function loadActiveSessionConfig(sessionId: string | null) {
  if (!sessionId) {
    currentSessionDetail.value = null
    resetActiveSessionConfig()
    return
  }

  try {
    const detail = await chatApi.getSession(sessionId)
    if (chatStore.activeSessionId !== sessionId) return

    currentSessionDetail.value = detail
    activeSessionConfig.value = {
      preferredProviderId: detail.preferredProviderId ?? undefined,
      temperature: detail.temperature ?? DEFAULT_SESSION_TEMPERATURE,
      maxSteps: detail.maxSteps ?? DEFAULT_SESSION_MAX_STEPS,
      maxDurationSeconds: detail.maxDurationSeconds ?? DEFAULT_SESSION_MAX_DURATION_SECONDS,
      knowledgeBaseIds: detail.knowledgeBaseIds ?? [],
      datastoreIds: detail.datastoreIds ?? [],
    }
  } catch (event) {
    logger.warn('加载会话配置失败:', event)
    if (chatStore.activeSessionId === sessionId) {
      currentSessionDetail.value = null
      resetActiveSessionConfig()
    }
  }
}

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
    return '这条回复会接着刚才继续。'
  }

  const detail = message.errorMessage?.trim()
  if (!detail || detail === message.suspendReasonSourceId || detail === 'await_user_input' || detail === 'suspended' || detail.startsWith('__')) {
    return '这条回复会接着刚才继续。'
  }

  return `当前卡住点：${detail}`
}

const continuationTitle = computed(() => {
  if (!latestSuspendedAssistant.value || isStreaming.value) {
    return null
  }
  return '继续上一轮'
})

const continuationDetail = computed(() => {
  if (!latestSuspendedAssistant.value || isStreaming.value) {
    return null
  }
  return resolveContinuationDetail(latestSuspendedAssistant.value)
})

const inputPlaceholder = computed(() => {
  if (continuationTitle.value) {
    return '继续说…'
  }
  return '输入问题或贴资料…'
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

const headerTitle = computed(() => currentSessionDetail.value?.title?.trim() || currentSession.value?.title?.trim() || '新对话')
const activeContextCount = computed(() => (
  (activeSessionConfig.value.knowledgeBaseIds?.length ?? 0)
  + (activeSessionConfig.value.datastoreIds?.length ?? 0)
))
const sessionStatusText = computed(() => {
  if (isStreaming.value) {
    return reasoningStatusText.value || '处理中'
  }
  if (continuationTitle.value) {
    return '可继续'
  }
  if (chatStore.messages.length === 0) {
    return '待开始'
  }
  return '就绪'
})

onMounted(async () => {
  const sessionId = route.params.sessionId as string | undefined
  if (sessionId && sessionId !== chatStore.activeSessionId) {
    chatStore.activeSessionId = sessionId
  } else if (!sessionId && !chatStore.activeSessionId) {
    try {
      await chatStore.startNewSession()
    } catch (event) {
      logger.error('创建新会话失败:', event)
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
        logger.error('创建新会话失败:', event)
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

/* 滚动合并：用 rAF 将同一帧内的多次 scrollToBottom 合并为一次，
   避免 token 到达时（~24ms）与 DOM 重排交叉触发导致滚动抖动 */
let scrollRaf: number | null = null

function scrollToBottom() {
  if (scrollRaf !== null) return
  scrollRaf = requestAnimationFrame(() => {
    scrollRaf = null
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
    logger.error('分叉会话失败:', event)
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
    if (currentSessionDetail.value) {
      currentSessionDetail.value = {
        ...currentSessionDetail.value,
        title: title.trim(),
      }
    }
    await chatStore.loadSessions()
  } catch {
    uiStore.showToast('error', '更新会话标题失败')
  }
}

function togglePanel(panel: 'config' | 'sidebar' | 'debug') {
  const mapped: SidebarPanel = panel === 'sidebar' ? 'session' : panel
  if (showMobileSidebar.value && activeSidebarPanel.value === mapped) {
    showMobileSidebar.value = false
    return
  }
  activeSidebarPanel.value = mapped
  showMobileSidebar.value = true
}

function closeMobileSidebar() {
  showMobileSidebar.value = false
}

function selectSidebarPanel(panel: SidebarPanel) {
  activeSidebarPanel.value = panel
}
</script>

<template>
  <div class="relative flex h-full flex-col overflow-hidden">
    <div class="pointer-events-none absolute inset-0 overflow-hidden">
      <div class="absolute inset-x-[14%] top-[-10rem] h-[20rem] rounded-full bg-[radial-gradient(circle,rgba(13,148,136,0.12),transparent_70%)] blur-3xl" />
      <div class="absolute right-[-8rem] top-[22%] h-[18rem] w-[18rem] rounded-full bg-[radial-gradient(circle,rgba(59,130,246,0.12),transparent_70%)] blur-3xl" />
      <div class="absolute left-[-10rem] bottom-[-8rem] h-[20rem] w-[20rem] rounded-full bg-[radial-gradient(circle,rgba(15,23,42,0.08),transparent_72%)] blur-3xl dark:bg-[radial-gradient(circle,rgba(148,163,184,0.1),transparent_72%)]" />
    </div>

    <header class="relative shrink-0 px-4 pt-2 sm:px-6">
      <div class="mx-auto max-w-[1180px]">
        <div class="flex min-w-0 items-center justify-between gap-3 px-1 py-1">
          <h1 class="min-w-0 truncate text-base font-semibold tracking-tight text-foreground">
            {{ headerTitle }}
          </h1>

          <div class="flex items-center gap-2">
            <Button
              v-if="isStreaming"
              type="button"
              variant="destructive"
              size="sm"
              class="rounded-full"
              @click="abort"
            >
              <Square class="size-4" />
              停止
            </Button>

            <DropdownMenu>
              <DropdownMenuTrigger as-child>
                <Button type="button" variant="outline" size="icon" class="size-9 rounded-full" aria-label="更多操作">
                  <EllipsisVertical class="size-4" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" class="w-36">
                <DropdownMenuItem class="gap-2" @click="togglePanel('config')">
                  <Settings2 class="size-4" />
                  配置
                </DropdownMenuItem>
                <DropdownMenuItem class="gap-2" @click="togglePanel('sidebar')">
                  <LibraryBig class="size-4" />
                  信息
                </DropdownMenuItem>
                <DropdownMenuItem class="gap-2" @click="togglePanel('debug')">
                  <SlidersHorizontal class="size-4" />
                  调试
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
      </div>
    </header>

    <div class="relative min-h-0 flex-1 overflow-hidden pt-3">
      <section class="relative min-h-0 flex h-full min-w-0 flex-col overflow-hidden">
        <div
          ref="scrollContainer"
          class="min-h-0 flex-1 overflow-y-auto scrollbar-thin scrollbar-track-transparent scrollbar-thumb-border"
        >
          <div
            v-if="chatStore.messages.length === 0 && !isStreaming"
            class="mx-auto flex h-full w-full max-w-[1180px] items-center justify-center px-4 py-4 sm:px-6"
          >
            <div class="shell-card w-full border-border/52 bg-card/84 px-4 py-6 sm:px-6 sm:py-8">
              <EmptyState @send="handleEmptyStateSend" />
            </div>
          </div>
          <div v-else class="mx-auto w-full max-w-[1180px] px-4 py-4 sm:px-6">
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

        <div class="shrink-0 border-t border-border/45 bg-background/72 px-4 pb-3 pt-2 sm:px-6">
          <div class="mx-auto w-full max-w-[1180px]">
            <StatePanel
              v-if="showGlobalErrorPanel"
              class="mb-3"
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
      </section>

      <Transition
        enter-active-class="transition-all duration-250 ease-out"
        enter-from-class="opacity-0 translate-x-4"
        enter-to-class="opacity-100 translate-x-0"
        leave-active-class="transition-all duration-200 ease-in"
        leave-from-class="opacity-100 translate-x-0"
        leave-to-class="opacity-0 translate-x-4"
      >
        <div
          v-if="showMobileSidebar"
          class="absolute inset-y-4 right-4 z-30 w-[340px] rounded-[1.2rem] border border-border/58 bg-background/94 p-3 shadow-[0_18px_32px_-24px_hsl(var(--shadow-color)/0.18)]"
        >
          <div class="flex h-full min-h-0 flex-col gap-3">
            <div class="shell-card border-border/52 bg-card/86 p-1">
              <div class="grid grid-cols-3 gap-1">
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  class="rounded-xl"
                  :class="activeSidebarPanel === 'session' && 'status-btn-active'"
                  @click="selectSidebarPanel('session')"
                >
                  <LibraryBig class="size-4" />
                  信息
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  class="rounded-xl"
                  :class="activeSidebarPanel === 'config' && 'status-btn-active'"
                  @click="selectSidebarPanel('config')"
                >
                  <Settings2 class="size-4" />
                  配置
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  class="rounded-xl"
                  :class="activeSidebarPanel === 'debug' && 'status-btn-active'"
                  @click="selectSidebarPanel('debug')"
                >
                  <SlidersHorizontal class="size-4" />
                  调试
                </Button>
              </div>
            </div>

            <div class="min-h-0 flex-1 overflow-hidden">
              <div v-if="activeSidebarPanel === 'config'" class="h-full overflow-y-auto pr-1 scrollbar-thin">
                <SessionConfigPanel
                  :preferred-provider-id="activeSessionConfig.preferredProviderId"
                  :temperature="activeSessionConfig.temperature"
                  :max-steps="activeSessionConfig.maxSteps"
                  :max-duration-seconds="activeSessionConfig.maxDurationSeconds"
                  :knowledge-base-ids="activeSessionConfig.knowledgeBaseIds"
                  :datastore-ids="activeSessionConfig.datastoreIds"
                  :providers="chatProviders"
                  :knowledge-bases="kbStore.list"
                  :datastores="datastoreStore.list"
                  @close="closeMobileSidebar"
                  @update="handleConfigUpdate"
                />
              </div>
              <SessionSidebar
                v-else-if="activeSidebarPanel === 'session'"
                :session="currentSessionDetail"
                :knowledge-bases="kbStore.list"
                v-model:search-query="searchQuery"
                :message-count="chatStore.messages.length"
                :status-text="sessionStatusText"
                :context-count="activeContextCount"
                :matched-message-count="matchedMessageCount"
                @close="closeMobileSidebar"
                @clear="handleClearSession"
                @update-title="handleUpdateSessionTitle"
              />
              <DebugDrawer
                v-else
                :token-usage="lastTokenUsage"
                :model-id="lastModelId"
                :prompt="lastPrompt"
                :reasoning-events="reasoningEvents"
                :tools-summary="lastToolsSummary"
                :kb-sources="lastKbSources"
                :trace-id="lastAssistantMessage?.traceId"
                @close="closeMobileSidebar"
              />
            </div>
          </div>
        </div>
      </Transition>
    </div>
  </div>
</template>
