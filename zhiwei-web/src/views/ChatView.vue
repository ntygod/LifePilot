<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowDown,
} from 'lucide-vue-next'
import { chatApi, modelServiceApi } from '@/api/client'
import type { ModelService } from '@/api/client'
import { listSessionDocuments } from '@/api/documents'
import type { ChatAttachment, ChatSessionDetail, ChatTurnAction, Message, SessionConfig, SessionConfigOverride } from '@/types'
import { logger } from '@/utils/logger'
import StatePanel from '@/components/common/StatePanel.vue'
import { Button } from '@/components/ui/button'
import ChatInput from '@/components/chat/ChatInput.vue'
import ChatHeader from '@/components/chat/ChatHeader.vue'
import ComposerStopPill from '@/components/chat/ComposerStopPill.vue'
import ContinuationHint from '@/components/chat/ContinuationHint.vue'
import DocumentWorkspacePanel from '@/components/chat/DocumentWorkspacePanel.vue'
import EmptyState from '@/components/chat/EmptyState.vue'
import HumanTakeoverModal from '@/components/chat/HumanTakeoverModal.vue'
import MessageList from '@/components/chat/MessageList.vue'
import OverlayHost from '@/components/chat/OverlayHost.vue'
import PromptGallery from '@/components/chat/PromptGallery.vue'
import SessionConfigPanel from '@/components/chat/SessionConfigPanel.vue'
import SessionSidebar from '@/components/chat/SessionSidebar.vue'
import TracePanel from '@/components/chat/TracePanel.vue'
import ProcessTaskList from '@/components/process/ProcessTaskList.vue'
import { useProcessTaskStore } from '@/stores/processTask'
import { useChat } from '@/composables/useChat'
import { useChatOverlays } from '@/composables/useChatOverlays'
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
  pendingPermissionApprovals,
  pendingPermissionApprovalResolutions,
  resolvePermissionApproval,
  activeBrowserTakeover,
  browserTakeoverError,
  confirmBrowserTakeover,
  cancelBrowserTakeover,
} = useChat()

const scrollContainer = ref<HTMLElement | null>(null)
const showScrollToBottom = ref(false)
const messagesReady = ref(false)
const searchQuery = ref('')
const providers = ref<ModelService[]>([])

const DEFAULT_SESSION_TEMPERATURE = 0.7
const DEFAULT_SESSION_MAX_STEPS = 60
const DEFAULT_SESSION_MAX_DURATION_SECONDS = 300

const activeSessionConfig = ref<SessionConfig>({
  temperature: DEFAULT_SESSION_TEMPERATURE,
  maxSteps: DEFAULT_SESSION_MAX_STEPS,
  maxDurationSeconds: DEFAULT_SESSION_MAX_DURATION_SECONDS,
  knowledgeBaseIds: [],
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
  }
}

async function loadActiveSessionConfig(sessionId: string | null) {
  if (!sessionId) {
    currentSessionDetail.value = null
    resetActiveSessionConfig()
    sessionDocumentCount.value = 0
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
    }
  } catch (event) {
    logger.warn('加载会话配置失败:', event)
    if (chatStore.activeSessionId === sessionId) {
      currentSessionDetail.value = null
      resetActiveSessionConfig()
    }
  }

  // 刷新文档工作副本数量（独立请求，失败不影响会话元数据）
  void refreshSessionDocumentCount(sessionId)
}

/** 当前会话下已开始编辑的文档工作副本数量（用于头部快捷入口角标） */
const sessionDocumentCount = ref(0)

async function refreshSessionDocumentCount(sessionId: string) {
  try {
    const docs = await listSessionDocuments(sessionId, 'working')
    if (chatStore.activeSessionId === sessionId) {
      sessionDocumentCount.value = docs.length
    }
  } catch {
    // 拉不到就当 0，不影响主流程
    if (chatStore.activeSessionId === sessionId) {
      sessionDocumentCount.value = 0
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
    // 如果 activeSessionId 已有值，说明正在懒创建会话（startNewSession 已完成但 router.replace 尚未生效），
    // 此时不应重置，否则会导致"闪回欢迎页"的竞态问题
    if (chatStore.activeSessionId) return
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
  void skillStore.fetchSkills()

  try {
    providers.value = await modelServiceApi.listEnabledServices('GENERATION')
  } catch {
    // Provider 列表拉取失败不阻塞页面。
  }

  // 处理首屏传递的待发送消息
  // 1) 项目详情页等场景：使用完整结构（含附件 / 单轮 override）
  // 2) 首屏纯文本链路：保留兼容字段
  if (chatStore.pendingFirstSend) {
    const payload = chatStore.pendingFirstSend
    chatStore.pendingFirstSend = null
    await sendMessage(
      payload.content,
      payload.attachmentIds,
      payload.attachments,
      payload.singleTurnOverride,
    )
  } else if (chatStore.pendingFirstMessage) {
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
    if (!sessionId) {
      // 空对话页：不立即创建会话，等用户发第一条消息时懒创建
      chatStore.activeSessionId = null
      messagesReady.value = true
      return
    }

    // 如果 sessionId 和当前 activeSessionId 相同，说明是懒创建后的 router.replace，
    // 不需要重置状态和重新加载消息
    if (sessionId === chatStore.activeSessionId) {
      messagesReady.value = true
      return
    }

    messagesReady.value = false
    chatStore.activeSessionId = sessionId

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
  singleTurnOverride?: SessionConfigOverride | null
}) {
  // 发送后立即滚到底，让用户看到消息弹入
  nextTick(() => {
    window.setTimeout(scrollToBottom, 50)
  })
  await sendMessage(
    payload.content,
    payload.attachmentIds,
    payload.attachments,
    payload.singleTurnOverride,
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

function handlePromptPick(card: { prompt: string }) {
  // 点击示例卡片 → 把 prompt 灌入空态输入框并聚焦
  emptyInputRef.value?.setContent?.(card.prompt)
  emptyInputRef.value?.focus?.()
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

const processTaskStore = useProcessTaskStore()

// 流式结束后，把 'streaming' placeholder ID 更新为真实消息 ID
watch(isStreaming, (streaming) => {
  if (!streaming && activeTraceMessageId.value === 'streaming') {
    const realMsg = lastAssistantMessage.value
    activeTraceMessageId.value = realMsg?.id ?? null
  }
  // 流式结束可能产出新文档工件，刷新一次快捷入口数量
  if (!streaming && chatStore.activeSessionId) {
    void refreshSessionDocumentCount(chatStore.activeSessionId)
  }
})

function handleShowTrace(messageId: string) {
  activeTraceMessageId.value = messageId
  overlays.openTrace(messageId)
}

function closeTracePanel() {
  activeTraceMessageId.value = null
  if (overlays.activeOverlay.value === 'trace') {
    overlays.close()
  }
}

/* ── Overlay 统一管理 ── */

const overlays = useChatOverlays()

function openDocumentOverlay() {
  overlays.openDocument()
}

function dismissContinuationHint() {
  // 轻量忽略：只标记不同步到后端
  continuationHintDismissed.value = true
}
const continuationHintDismissed = ref(false)

/** 点击 ContinuationHint 的"继续"按钮 → 用空 RESUME 触发挂起会话恢复 */
async function handleContinuationResume() {
  const target = latestSuspendedAssistant.value
  if (!target) return
  await handleResume(target)
}

watch(() => latestSuspendedAssistant.value?.id, () => {
  continuationHintDismissed.value = false
})

const shouldShowContinuationHint = computed(() =>
  Boolean(continuationTitle.value)
    && !continuationHintDismissed.value
    && !isStreaming.value
)
</script>

<template>
  <div class="chat-shell">
    <ChatHeader
      :is-empty="isEmptyChat"
      :title="headerTitle"
      :document-count="sessionDocumentCount"
      :task-count="processTaskStore.tasksOrdered.length"
      :has-active-task="processTaskStore.runningCount > 0"
      @rename="handleUpdateSessionTitle"
      @open-info="overlays.openInfo"
      @open-settings="overlays.openSettings"
      @open-document="overlays.openDocument"
      @open-tasks="overlays.openTasks"
    />

    <div class="chat-main">
      <section class="chat-main__stream">
        <div
          ref="scrollContainer"
          data-scroll-container
          class="chat-scroll scrollbar-thin scrollbar-track-transparent scrollbar-thumb-border"
        >
          <!-- 空态：Wordmark + 欢迎语 + PromptGallery + 居中 Composer -->
          <div v-if="isEmptyChat" key="empty" class="chat-empty">
            <EmptyState />
            <div class="chat-empty__gallery">
              <PromptGallery @pick="handlePromptPick" />
            </div>
            <div class="chat-empty__composer">
              <ChatInput
                ref="emptyInputRef"
                :placeholder="inputPlaceholder"
                :knowledge-bases="kbStore.list"
                :base-session-config="activeSessionConfig"
                @send="handleSend"
              />
            </div>
          </div>

          <!-- 对话态：消息列表 -->
          <div v-else key="messages" class="chat-stream">
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

        <!-- 对话态底部输入区域：Continuation + Composer + StopPill + 全局错误 -->
        <Transition
          enter-active-class="transition-all duration-300 ease-out"
          enter-from-class="translate-y-4 opacity-0"
          enter-to-class="translate-y-0 opacity-100"
        >
          <div v-if="!isEmptyChat" class="chat-composer-wrap">
            <div class="chat-composer-wrap__inner">
              <!-- 右下角悬浮集群：停止 + 回到底部（竖排，对话区右下角） -->
              <div class="chat-floating-cluster">
                <Transition
                  enter-active-class="transition-all duration-200 ease-out"
                  enter-from-class="translate-y-2 opacity-0"
                  enter-to-class="translate-y-0 opacity-100"
                  leave-active-class="transition-all duration-150 ease-in"
                  leave-from-class="translate-y-0 opacity-100"
                  leave-to-class="translate-y-2 opacity-0"
                >
                  <ComposerStopPill v-if="isStreaming" @abort="abort" />
                </Transition>
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
                    class="chat-scroll-to-bottom"
                    title="回到底部"
                    @click="scrollToBottomSmooth"
                  >
                    <ArrowDown class="size-4 text-muted-foreground" />
                  </button>
                </Transition>
              </div>

              <!-- 挂起恢复小卡片 -->
              <ContinuationHint
                v-if="shouldShowContinuationHint && continuationTitle"
                class="chat-composer-wrap__continuation"
                :title="continuationTitle"
                :detail="continuationDetail"
                @resume="handleContinuationResume"
                @dismiss="dismissContinuationHint"
              />

              <!-- 全局错误提示 -->
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
                :disabled="isStreaming"
                :placeholder="inputPlaceholder"
                :continuation-title="null"
                :continuation-detail="null"
                :knowledge-bases="kbStore.list"
                :base-session-config="activeSessionConfig"
                @send="handleSend"
              />
            </div>
          </div>
        </Transition>
      </section>
    </div>

    <!-- Overlay：Trace / Document / Tasks / Settings / Info（fixed 定位，覆盖整个视口） -->
    <OverlayHost
      :open="overlays.activeOverlay.value === 'trace'"
      @close="closeTracePanel"
    >
      <div class="overlay-scroll">
        <div class="overlay-heading">
          <div class="overlay-heading__eyebrow">对话观测</div>
          <div class="overlay-heading__title">执行轨迹</div>
        </div>
        <TracePanel
          v-if="activeTraceData"
          :reasoning-events="activeTraceData.reasoningEvents"
          :react-steps="activeTraceData.reactSteps"
          :streaming="activeTraceData.streaming"
          :trace-id="activeTraceData.traceId"
          hide-header
        />
      </div>
    </OverlayHost>

    <OverlayHost
      :open="overlays.activeOverlay.value === 'tasks'"
      @close="overlays.close"
    >
      <div class="overlay-scroll">
        <div class="overlay-heading">
          <div class="overlay-heading__eyebrow">会话</div>
          <div class="overlay-heading__title">后台任务</div>
        </div>
        <div
          v-if="processTaskStore.tasksOrdered.length === 0"
          class="overlay-empty"
        >
          暂无后台任务。助手调用 shell.run 等长耗时操作时会在这里显示进度。
        </div>
        <ProcessTaskList v-else />
      </div>
    </OverlayHost>

    <OverlayHost
      :open="overlays.activeOverlay.value === 'document'"
      @close="overlays.close"
    >
      <DocumentWorkspacePanel
        v-if="chatStore.activeSessionId"
        :session-id="chatStore.activeSessionId"
        :open="true"
        embedded
        @close="overlays.close"
        @open-document="(id: string) => logger.info('切换到文档', id)"
      />
    </OverlayHost>

    <OverlayHost
      :open="overlays.activeOverlay.value === 'settings'"
      @close="overlays.close"
    >
      <div class="overlay-scroll">
        <div class="overlay-heading">
          <div class="overlay-heading__eyebrow">会话</div>
          <div class="overlay-heading__title">当前会话设置</div>
        </div>
        <SessionConfigPanel
          :preferred-provider-id="activeSessionConfig.preferredProviderId"
          :temperature="activeSessionConfig.temperature"
          :max-steps="activeSessionConfig.maxSteps"
          :max-duration-seconds="activeSessionConfig.maxDurationSeconds"
          :knowledge-base-ids="activeSessionConfig.knowledgeBaseIds"
          :providers="chatProviders"
          :knowledge-bases="kbStore.list"
          :show-close="false"
          hide-header
          @close="overlays.close"
          @update="handleConfigUpdate"
        />
      </div>
    </OverlayHost>

    <OverlayHost
      :open="overlays.activeOverlay.value === 'info'"
      @close="overlays.close"
    >
      <div class="overlay-scroll">
        <div class="overlay-heading">
          <div class="overlay-heading__eyebrow">会话</div>
          <div class="overlay-heading__title">本次会话概览</div>
        </div>
        <SessionSidebar
          :session="currentSessionDetail"
          :knowledge-bases="kbStore.list"
          v-model:search-query="searchQuery"
          :message-count="chatStore.messages.length"
          :status-text="sessionStatusText"
          :context-count="activeContextCount"
          :matched-message-count="matchedMessageCount"
          :show-close="false"
          hide-header
          @close="overlays.close"
          @clear="handleClearSession"
          @update-title="handleUpdateSessionTitle"
        />
      </div>
    </OverlayHost>

    <!-- 浏览器人工接管弹窗：优先级最高，单独挂载 -->
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

<style scoped>
.chat-shell {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.chat-main {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
}

.chat-main__stream {
  position: relative;
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-scroll {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.chat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-height: 100%;
  padding: 14vh var(--chat-gutter-x-desktop, 24px) 24px;
  gap: 28px;
}

.chat-empty__gallery {
  width: 100%;
  display: flex;
  justify-content: center;
  animation: fade-slide-in 500ms ease-out both;
  animation-delay: 160ms;
}

.chat-empty__composer {
  width: 100%;
  max-width: 600px;
  animation: fade-slide-in 500ms ease-out both;
  animation-delay: 220ms;
}

@keyframes fade-slide-in {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.chat-stream {
  margin: 0 auto;
  width: 100%;
  max-width: var(--chat-main-max-w, 720px);
  padding: 16px var(--chat-gutter-x-desktop, 24px) 48px;
}

.chat-composer-wrap {
  flex-shrink: 0;
  padding: 8px var(--chat-gutter-x-desktop, 24px) 12px;
}

.chat-composer-wrap__inner {
  position: relative;
  margin: 0 auto;
  width: 100%;
  max-width: var(--chat-main-max-w, 720px);
}

.chat-composer-wrap__continuation {
  margin-bottom: 8px;
}

/* 右下角悬浮集群：停止生成 + 回到底部，竖排 */
.chat-floating-cluster {
  position: absolute;
  right: 0;
  bottom: calc(100% + 12px);
  display: flex;
  flex-direction: column-reverse;
  align-items: flex-end;
  gap: 8px;
  z-index: 10;
  pointer-events: none;
}

.chat-floating-cluster > * {
  pointer-events: auto;
}

.chat-scroll-to-bottom {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.5);
  background: var(--background);
  box-shadow: 0 4px 12px -6px hsl(var(--shadow-color) / 0.18);
  cursor: pointer;
  transition: background 120ms ease;
}

.chat-scroll-to-bottom:hover {
  background: hsl(from var(--muted) h s l / 0.6);
}

.overlay-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px;
  scroll-behavior: smooth;
}

.overlay-heading {
  margin-bottom: 14px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.overlay-heading__eyebrow {
  font-size: 11px;
  font-weight: 500;
  color: var(--muted-foreground);
  text-transform: uppercase;
  letter-spacing: 0.12em;
}

.overlay-heading__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--foreground);
}

.overlay-tasks {
  margin-top: 20px;
  padding-top: 14px;
  border-top: 1px dashed hsl(from var(--border) h s l / 0.55);
}

.overlay-tasks__label {
  padding: 0 0 8px;
  font-size: 11px;
  font-weight: 500;
  color: var(--muted-foreground);
  text-transform: uppercase;
  letter-spacing: 0.12em;
}

/* Overlay 的空状态提示（文档 0 份、任务 0 个时显示） */
.overlay-empty {
  padding: 32px 12px;
  border-radius: 12px;
  border: 1px dashed hsl(from var(--border) h s l / 0.55);
  background: hsl(from var(--muted) h s l / 0.25);
  text-align: center;
  font-size: 13px;
  line-height: 1.7;
  color: var(--muted-foreground);
}

/* Overlay 内嵌 InspectorRail 时去除其 320px 宽度限制 + 外框，让它融入 Overlay */
.overlay-scroll :deep(.inspector-rail) {
  max-width: none;
  border: none;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  padding: 0;
}

.overlay-scroll :deep(.inspector-rail > div:last-child) {
  padding: 0;
}
</style>
