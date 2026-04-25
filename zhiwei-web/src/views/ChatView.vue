<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowDown,
  EllipsisVertical,
  FileText,
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
import DocumentWorkspacePanel from '@/components/chat/DocumentWorkspacePanel.vue'
import EmptyState from '@/components/chat/EmptyState.vue'
import HumanTakeoverModal from '@/components/chat/HumanTakeoverModal.vue'
import MessageList from '@/components/chat/MessageList.vue'
import SessionConfigPanel from '@/components/chat/SessionConfigPanel.vue'
import SessionSidebar from '@/components/chat/SessionSidebar.vue'
import ChatRightPanel from '@/components/chat/ChatRightPanel.vue'
import { useProcessTaskStore } from '@/stores/processTask'
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
  activeBrowserTakeover,
  browserTakeoverError,
  confirmBrowserTakeover,
  cancelBrowserTakeover,
} = useChat()

type SidebarPanel = 'session' | 'config' | 'debug'

const scrollContainer = ref<HTMLElement | null>(null)
const showScrollToBottom = ref(false)
const messagesReady = ref(false)
const searchQuery = ref('')
const activeSidebarPanel = ref<SidebarPanel>('session')
const showMobileSidebar = ref(false)
const showDocumentPanel = ref(false)
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

/** 空状态：无消息且非流式中 */
// 消息加载完成且为空时显示欢迎页（加载中不显示，防止闪烁）
const isEmptyChat = computed(() =>
  chatStore.messages.length === 0 && !isStreaming.value && messagesReady.value
)

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

// store 中的 title 由 SSE title-generated 事件实时更新，优先于可能过时的 currentSessionDetail
const headerTitle = computed(() => currentSession.value?.title?.trim() || currentSessionDetail.value?.title?.trim() || '新对话')
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

// 会话懒创建或状态重置后，同步 URL 与 activeSessionId：
// - 新会话创建 → URL 从 /conversations/new 替换为 /conversations/:id
// - 在旧会话页面点"新建对话"后发送消息 → URL 同步到新会话
watch(() => chatStore.activeSessionId, (newId) => {
  if (newId && route.params.sessionId !== newId) {
    router.replace({ name: 'conversationDetail', params: { sessionId: newId } })
  }
})

// 同一组件在 newConversation ↔ conversationDetail 间复用时 onMounted 不会重新触发，
// 需要 watch route 来重置状态
watch(() => route.fullPath, () => {
  const sessionId = route.params.sessionId as string | undefined
  if (route.name === 'newConversation') {
    // 设置 activeSessionId = null 会触发 store 内部 watcher 清空 messages/streamingContent
    chatStore.activeSessionId = null
    messagesReady.value = true
    currentSessionDetail.value = null
    resetActiveSessionConfig()
  } else if (sessionId && sessionId !== chatStore.activeSessionId) {
    chatStore.activeSessionId = sessionId
  }
})

onMounted(async () => {
  const sessionId = route.params.sessionId as string | undefined
  if (sessionId && sessionId !== chatStore.activeSessionId) {
    chatStore.activeSessionId = sessionId
  } else if (!sessionId) {
    // 空对话页：不立即创建会话，等用户发第一条消息时懒创建
    chatStore.activeSessionId = null
  }

  void kbStore.fetchList()
  void datastoreStore.fetchList()
  void skillStore.fetchSkills()

  try {
    providers.value = await modelServiceApi.listEnabledServices('GENERATION')
  } catch {
    // Provider 列表拉取失败不阻塞页面。
  }

  // 处理首屏传递的待发送消息
  if (chatStore.pendingFirstMessage) {
    const content = chatStore.pendingFirstMessage
    chatStore.pendingFirstMessage = null
    await sendMessage(content)
  }

  // 首次加载标记就绪
  messagesReady.value = true

  // 监听滚动，判断是否显示"回到底部"按钮
  scrollListenerEl = getScrollEl()
  scrollListenerEl?.addEventListener('scroll', handleScroll, { passive: true })

  // 首次加载完成后滚到底部
  for (const delay of [50, 200, 500]) {
    pendingTimers.push(window.setTimeout(forceScrollBottom, delay))
  }
})

onUnmounted(() => {
  scrollListenerEl?.removeEventListener('scroll', handleScroll)
  scrollListenerEl = null
  pendingTimers.forEach(clearTimeout)
  pendingTimers.length = 0
  if (scrollRaf !== null) {
    cancelAnimationFrame(scrollRaf)
    scrollRaf = null
  }
  if (readyTimer !== null) {
    clearTimeout(readyTimer)
    readyTimer = null
  }
})

/* 滚动事件注册引用 + 定时器收集（卸载时统一清理） */
let scrollListenerEl: HTMLElement | null = null
const pendingTimers: number[] = []
let readyTimer: number | null = null

function handleScroll() {
  const el = getScrollEl()
  if (!el) return
  const { scrollTop, scrollHeight, clientHeight } = el
  showScrollToBottom.value = scrollHeight - scrollTop - clientHeight > 120
}

watch(
  () => route.params.sessionId as string | undefined,
  async (sessionId) => {
    messagesReady.value = false

    if (!sessionId) {
      // 空对话页：不立即创建会话，等用户发第一条消息时懒创建
      chatStore.activeSessionId = null
      messagesReady.value = true
      return
    }

    if (sessionId !== chatStore.activeSessionId) {
      chatStore.activeSessionId = sessionId
    }

    // 等消息加载完成后标记就绪（store 内部 watch 是异步的，延迟兜底）
    if (readyTimer !== null) clearTimeout(readyTimer)
    readyTimer = window.setTimeout(() => { messagesReady.value = true; readyTimer = null }, 300)

    // 等消息加载 + DOM 渲染完成后滚到底部
    await nextTick()
    // 额外等一帧确保长列表渲染完
    requestAnimationFrame(() => scrollToBottom())
  },
)

watch(
  () => chatStore.activeSessionId,
  sessionId => {
    void loadActiveSessionConfig(sessionId)
  },
  { immediate: true },
)

/* 获取实际滚动容器（ref 可能因 Transition 延迟为 null，兜底用 data 属性查询） */
function getScrollEl(): HTMLElement | null {
  return scrollContainer.value
    ?? document.querySelector<HTMLElement>('[data-scroll-container]')
}

/* 滚动合并：用 rAF 将同一帧内的多次 scrollToBottom 合并为一次 */
let scrollRaf: number | null = null

function scrollToBottom() {
  if (scrollRaf !== null) return
  scrollRaf = requestAnimationFrame(() => {
    scrollRaf = null
    const el = getScrollEl()
    if (el) el.scrollTop = el.scrollHeight
  })
}

function scrollToBottomSmooth() {
  const el = getScrollEl()
  if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
}

/** 强制滚到底（直接查询，不依赖 ref） */
function forceScrollBottom() {
  const el = getScrollEl()
  if (el && el.scrollHeight > el.clientHeight) {
    el.scrollTop = el.scrollHeight
  }
}

// 消息列表变化 → 有消息时标记就绪 + 滚到底部
watch(() => chatStore.messages.length, (len) => {
  if (len > 0) {
    messagesReady.value = true
    for (const delay of [50, 200, 500]) {
      pendingTimers.push(window.setTimeout(forceScrollBottom, delay))
    }
  }
})
watch(() => chatStore.streamingContent, scrollToBottom)

async function handleSend(payload: {
  content: string
  attachmentIds?: string[]
  attachments?: ChatAttachment[]
  sessionConfig?: SessionConfig
  restoreSessionConfig?: SessionConfig
}) {
  // 发送后立即滚到底，让用户看到消息弹入
  nextTick(() => {
    window.setTimeout(scrollToBottom, 50)
  })
  await sendMessage(
    payload.content,
    payload.attachmentIds,
    payload.attachments,
    payload.sessionConfig,
    payload.restoreSessionConfig,
  )
}

const emptyInputRef = ref<InstanceType<typeof ChatInput> | null>(null)

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

async function handleEdit(message: Message, newContent: string) {
  if (isStreaming.value) return
  // 更新用户消息内容后，以 RESTART 重新生成
  chatStore.updateMessage(message.id, { content: newContent })
  const turnId = message.turnId
  if (turnId) {
    await executeTurn(turnId, 'RESTART', {
      content: newContent,
      attachmentIds: getAttachmentIds(message),
      attachments: message.attachments,
      userMessageId: message.id,
    })
  } else {
    await sendMessage(newContent, getAttachmentIds(message), message.attachments)
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

// ─── 执行轨迹面板 ───

const activeTraceMessageId = ref<string | null>(null)

const activeTraceData = computed(() => {
  const id = activeTraceMessageId.value
  if (!id) return null

  if (id === 'streaming' || (isStreaming.value && id === lastAssistantMessage.value?.id)) {
    return {
      reasoningEvents: reasoningEvents.value,
      reactSteps: streamingReactSteps.value,
      streaming: true,
      traceId: undefined as string | undefined,
    }
  }

  const msg = chatStore.messages.find(m => m.id === id)
  if (!msg) return null

  return {
    reasoningEvents: msg.reasoningEvents ?? [],
    reactSteps: msg.reactSteps ?? [],
    streaming: false,
    traceId: msg.traceId,
  }
})

const showTracePanel = computed(() => !!activeTraceMessageId.value && !!activeTraceData.value)
const processTaskStore = useProcessTaskStore()
/** 右侧面板显示条件：有轨迹数据、或有任何后台任务（含终态，避免最后一个任务结束瞬间面板消失） */
const showRightPanel = computed(() =>
  showTracePanel.value || processTaskStore.tasksOrdered.length > 0
)

// 流式结束后，把 'streaming' placeholder ID 更新为真实消息 ID
watch(isStreaming, (streaming) => {
  if (!streaming && activeTraceMessageId.value === 'streaming') {
    const realMsg = lastAssistantMessage.value
    activeTraceMessageId.value = realMsg?.id ?? null
  }
})

function handleShowTrace(messageId: string) {
  activeTraceMessageId.value = messageId
  showMobileSidebar.value = false
}

function closeTracePanel() {
  activeTraceMessageId.value = null
}
</script>

<template>
  <div class="relative flex h-full flex-col overflow-hidden">
    <header class="relative shrink-0 px-4 pt-2 sm:px-6">
      <div class="mx-auto max-w-[800px]">
        <div class="flex min-w-0 items-center gap-3 px-1 py-1" :class="isEmptyChat ? 'justify-end' : 'justify-between'">
          <h1 v-if="!isEmptyChat" class="min-w-0 truncate text-base font-semibold tracking-tight text-foreground">
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
              停止生成
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
                <DropdownMenuItem class="gap-2" @click="showDocumentPanel = true">
                  <FileText class="size-4" />
                  文档
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
      </div>
    </header>

    <div class="relative min-h-0 flex-1 flex overflow-hidden pt-3">
      <section class="relative min-h-0 flex-1 flex min-w-0 flex-col overflow-hidden">
        <div
          ref="scrollContainer"
          data-scroll-container
          class="relative min-h-0 flex-1 overflow-y-auto scrollbar-thin scrollbar-track-transparent scrollbar-thumb-border"
        >
          <!-- 空状态：问候 + 输入框居中 -->
          <div v-if="isEmptyChat" key="empty" class="flex h-full flex-col items-center px-4 pt-[12vh] sm:px-6">
            <EmptyState />
            <div class="w-full max-w-[600px] mt-xl animate-in fade-in slide-in-from-bottom-4 duration-500 delay-200">
              <ChatInput
                ref="emptyInputRef"
                :placeholder="inputPlaceholder"
                :knowledge-bases="kbStore.list"
                :datastores="datastoreStore.list"
                :base-session-config="activeSessionConfig"
                @send="handleSend"
              />
            </div>
          </div>

          <!-- 有消息：正常消息列表 -->
          <div v-else key="messages" class="mx-auto w-full max-w-[800px] px-4 pt-4 pb-[48px] sm:px-6">
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
              @edit="handleEdit"
              @like="handleLike"
              @dislike="handleDislike"
              @fork="handleFork"
              @regenerate="handleRegenerate"
              @resume="handleResume"
              @restart="handleRestart"
              @copy="handleCopy"
              @show-trace="handleShowTrace"
              @permission-approval-resolve="resolvePermissionApproval"
            />
          </div>
        </div>
        <!-- 底部输入框：仅有消息时显示 -->
        <Transition
          enter-active-class="transition-all duration-300 ease-out"
          enter-from-class="translate-y-4 opacity-0"
          enter-to-class="translate-y-0 opacity-100"
        >
        <div v-if="!isEmptyChat" class="shrink-0 px-4 pb-3 pt-2 sm:px-6">
          <div class="mx-auto w-full max-w-[800px] relative">
            <!-- 回到底部按钮：固定在输入框上方 -->
            <Transition
              enter-active-class="transition-all duration-200 ease-out"
              enter-from-class="translate-y-2 opacity-0"
              enter-to-class="translate-y-0 opacity-100"
              leave-active-class="transition-all duration-150 ease-in"
              leave-from-class="translate-y-0 opacity-100"
              leave-to-class="translate-y-2 opacity-0"
            >
              <button
                v-if="showScrollToBottom"
                type="button"
                class="absolute -top-10 left-1/2 z-10 flex size-8 -translate-x-1/2 items-center justify-center rounded-full border border-border/50 bg-background shadow-md transition-colors hover:bg-muted"
                title="回到底部"
                @click="scrollToBottomSmooth"
              >
                <ArrowDown class="size-4 text-muted-foreground" />
              </button>
            </Transition>
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
        </Transition>
      </section>

      <!-- 右侧面板：执行轨迹 + 后台任务 tab 切换 -->
      <aside
        v-if="showRightPanel"
        class="w-[340px] shrink-0 border-l border-border/40 bg-background"
      >
        <ChatRightPanel
          :trace-data="activeTraceData"
          @close="closeTracePanel"
        />
      </aside>

      <!-- 浮窗侧边栏：配置 / 信息 / 调试 -->
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

    <!-- P1-6 文档工作区抽屉：列出本 session 下的工作副本；点击暂时仅用于查看（open-document 跳转留给后续） -->
    <DocumentWorkspacePanel
      v-if="chatStore.activeSessionId"
      :session-id="chatStore.activeSessionId"
      :open="showDocumentPanel"
      @close="showDocumentPanel = false"
      @open-document="(id: string) => console.info('切换到文档', id)"
    />

    <!-- P2-D 浏览器人工接管弹窗：Agent 因登录/验证码/人机验证挂起时出现 -->
    <HumanTakeoverModal
      v-if="activeBrowserTakeover"
      :open="true"
      :reason="activeBrowserTakeover.reason"
      :timeout-seconds="activeBrowserTakeover.timeoutSeconds"
      :error="browserTakeoverError"
      @continue="confirmBrowserTakeover"
      @cancel="cancelBrowserTakeover"
    />
  </div>
</template>
