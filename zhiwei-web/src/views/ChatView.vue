<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Activity,
  Archive,
  ArrowDown,
  Bot,
  Brain,
  Copy,
  Cpu,
  DatabaseBackup,
  FileArchive,
  KeyRound,
  Loader2,
  PlugZap,
  X,
} from 'lucide-vue-next'
import { chatApi, diagnosticsApi, knowledgeBaseApi, modelServiceApi } from '@/api/client'
import type { ModelService } from '@/api/client'
import { buildArtifactDownloadUrl, type ArtifactRefPayload } from '@/api/artifacts'
import type { ChatAttachment, ChatSessionDetail, ChatTurnAction, DiagnosticReport, EntityDetail, KnowledgeBase, Message, SessionConfig, SessionConfigOverride, SourceSummary, TaskRecoveryCheckpoint, ToolRecoveryAction } from '@/types'
import { logger } from '@/utils/logger'
import StatePanel from '@/components/common/StatePanel.vue'
import { Button } from '@/components/ui/button'
import ChatInput from '@/components/chat/ChatInput.vue'
import ChatHeader from '@/components/chat/ChatHeader.vue'
import ContinuationHint from '@/components/chat/ContinuationHint.vue'
import EmptyState from '@/components/chat/EmptyState.vue'
import HumanTakeoverModal from '@/components/chat/HumanTakeoverModal.vue'
import MessageList from '@/components/chat/MessageList.vue'
import MemoryInsightPanel from '@/components/chat/MemoryInsightPanel.vue'
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
import { useMemoryStore } from '@/stores/memory'
import { useSkillStore } from '@/stores/skill'
import { useUiStore } from '@/stores/ui'
import { copyToClipboard } from '@/utils/clipboard'
import { buildEmptyPromptSuggestions } from '@/utils/emptyPromptSuggestions'
import type { EmptyPromptSuggestion } from '@/utils/emptyPromptSuggestions'
import { buildDiagnosticNextActions, buildDiagnosticRepairLinks, buildErrorDiagnostic, buildMessageRepairLinks, type DiagnosticRepairLink } from '@/utils/errorDiagnostic'
import { normalizeTurnStatusText } from '@/utils/turnPhase'
import {
  applyEntityToMemorySource,
  buildDeletedMemorySource,
  buildMemoryEntityMessagePatch,
  buildMemoryEntityRemovalMessagePatch,
} from '@/utils/memorySource'
import {
  canUseManualResumeForMessage,
  isRecoverableAssistantTaskMessage,
  isUserReplyRecovery,
  resolveManualResumeStatusLabelForMessage,
} from '@/utils/taskRecovery'
import { buildToolRecoveryContextSummary, formatToolFailureCategory } from '@/utils/toolExecution'

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()
const kbStore = useKnowledgeBaseStore()
const memoryStore = useMemoryStore()
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
  streamingArtifactRefs,
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
const messagesReady = ref(!route.params.sessionId)
const searchQuery = ref('')
const providers = ref<ModelService[]>([])
const activeMemorySource = ref<SourceSummary | null>(null)
const creatingBackup = ref(false)
const creatingDiagnosticBundle = ref(false)
const analyzingGlobalDiagnostic = ref(false)
const globalDiagnosticStatus = ref<string | null>(null)
const globalDiagnosticSummary = ref<string | null>(null)
const globalDiagnosticActions = ref<string[]>([])
const globalDiagnosticError = ref<string | null>(null)
const globalDiagnosticRepairLinks = ref<DiagnosticRepairLink[]>([])
const emptyComposerHasDraft = ref(false)
const activeComposerDraftContent = ref('')
const savingKnowledgeMessageId = ref<string | null>(null)

interface DiagnosticRepairLinkView extends DiagnosticRepairLink {
  icon: Component
}

function diagnosticRepairIcon(link: DiagnosticRepairLink): Component {
  switch (link.id) {
    case 'models':
      return Bot
    case 'code-execution':
      return Cpu
    case 'channels':
      return PlugZap
    case 'capabilities':
      return PlugZap
    case 'permissions':
      return KeyRound
    case 'general':
      return DatabaseBackup
    default:
      return Activity
  }
}

const globalDiagnosticRepairLinkViews = computed<DiagnosticRepairLinkView[]>(() =>
  globalDiagnosticRepairLinks.value.map(link => ({ ...link, icon: diagnosticRepairIcon(link) })),
)

const showGlobalDiagnosticDetail = computed(() =>
  !!globalDiagnosticSummary.value
  || !!globalDiagnosticError.value
  || globalDiagnosticActions.value.length > 0
  || globalDiagnosticRepairLinks.value.length > 0,
)
interface PendingMemoryDraft {
  sourceMessageId: string
  sourceLabel: string
  preview: string
  previousContent: string
}
const pendingMemoryDraft = ref<PendingMemoryDraft | null>(null)
interface SavedKnowledgeMessage {
  knowledgeBaseId: string
  knowledgeBaseName: string
}
interface ArtifactKnowledgeSavedPayload {
  artifactId: string
  fileName: string
  knowledgeBaseId: string
  knowledgeBaseName: string
}
const savedKnowledgeMessages = ref<Record<string, SavedKnowledgeMessage>>({})
const saveKnowledgeErrors = ref<Record<string, string>>({})

const DEFAULT_SESSION_TEMPERATURE = 0.7
const DEFAULT_SESSION_MAX_STEPS = 60
const DEFAULT_SESSION_MAX_DURATION_SECONDS = 300
const REMEMBER_DRAFT_PREFIX = '请记住：'
const REMEMBER_DRAFT_MAX_LENGTH = 4000
const MESSAGE_KNOWLEDGE_FALLBACK_TITLE = '知微回复'

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

const activeMemoryProjectId = computed(() =>
  currentSessionDetail.value?.projectId ?? currentSession.value?.projectId ?? null
)

const artifactKnowledgeBaseTarget = computed<Pick<KnowledgeBase, 'id' | 'name'> | null>(() => {
  const activeIds = (activeSessionConfig.value.knowledgeBaseIds ?? [])
    .map(id => id.trim())
    .filter(Boolean)
  if (activeIds.length === 1) {
    const id = activeIds[0]
    const matched = kbStore.list.find(knowledgeBase => knowledgeBase.id === id)
    return {
      id,
      name: matched?.name?.trim() || '当前资料库',
    }
  }
  if (activeIds.length === 0 && kbStore.list.length === 1) {
    const [knowledgeBase] = kbStore.list
    return {
      id: knowledgeBase.id,
      name: knowledgeBase.name?.trim() || '资料库',
    }
  }
  return null
})

const focusedSourceTurnId = computed(() => normalizeRouteQueryValue(route.query.turnId))
const focusedSourceEntryId = computed(() => normalizeRouteQueryValue(route.query.entryId))
const focusedSourceKey = computed(() => {
  if (!focusedSourceTurnId.value && !focusedSourceEntryId.value) return ''
  return `${focusedSourceEntryId.value}::${focusedSourceTurnId.value}`
})
const appliedFocusedSourceKey = ref('')
const recoveryReturnSessionId = computed(() => (
  chatStore.activeSessionId
  ?? normalizeRouteQueryValue(route.params.sessionId)
) || null)

function normalizeRouteQueryValue(value: unknown) {
  if (Array.isArray(value)) {
    return normalizeRouteQueryValue(value[0])
  }
  return typeof value === 'string' ? value.trim() : ''
}

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

function isRecoverableAssistantMessage(message: Message | null | undefined) {
  return isRecoverableAssistantTaskMessage(message)
}

const latestRecoverableAssistant = computed<Message | null>(() => {
  const message = lastAssistantMessage.value
  if (!message) return null
  return isRecoverableAssistantMessage(message) ? message : null
})

function isAwaitingUserReplyMessage(message: Message | null | undefined) {
  return Boolean(message)
    && (isUserReplyRecovery(message?.taskRecovery)
      || message?.suspendReasonSourceId === '__await_user_input__')
}

function resolveContinuationDetail(message: Message | null) {
  if (!message) {
    return null
  }

  if (message.taskRecovery?.detail) {
    return message.taskRecovery.detail
  }

  if (message.turnRecoveryContext?.detail) {
    return message.turnRecoveryContext.detail
  }

  if (isAwaitingUserReplyMessage(message)) {
    return '直接在输入框补充信息，发送后知微会接着当前任务继续。'
  }

  const detail = message.errorMessage?.trim()
  if (!detail || detail === message.suspendReasonSourceId || detail === 'await_user_input' || detail === 'suspended' || detail.startsWith('__')) {
    return '这条回复会接着刚才继续。'
  }

  return `当前卡住点：${detail}`
}

const continuationTitle = computed(() => {
  if (!latestRecoverableAssistant.value || isStreaming.value) {
    return null
  }
  return latestRecoverableAssistant.value.taskRecovery?.title
    ?? latestRecoverableAssistant.value.turnRecoveryContext?.title
    ?? '继续上一轮'
})

const continuationDetail = computed(() => {
  if (!latestRecoverableAssistant.value || isStreaming.value) {
    return null
  }
  return resolveContinuationDetail(latestRecoverableAssistant.value)
})

const continuationCanResume = computed(() =>
  canUseManualResumeForMessage(latestRecoverableAssistant.value),
)

const continuationActionLabel = computed(() =>
  latestRecoverableAssistant.value?.taskRecovery?.actionLabel ?? '继续',
)

const continuationStatusLabel = computed(() =>
  resolveManualResumeStatusLabelForMessage(latestRecoverableAssistant.value),
)

const continuationInputHint = computed(() => {
  if (!latestRecoverableAssistant.value || isStreaming.value) {
    return null
  }
  if (isAwaitingUserReplyMessage(latestRecoverableAssistant.value)) {
    return '在输入框补充，发送后会自动续接。'
  }
  return null
})

const continuationCheckpoint = computed<TaskRecoveryCheckpoint | null>(() => {
  if (!latestRecoverableAssistant.value || isStreaming.value) {
    return null
  }
  return recoveryCheckpointForMessage(latestRecoverableAssistant.value)
})

const continuationCheckpointLabel = computed(() =>
  formatContinuationCheckpointLabel(continuationCheckpoint.value),
)

const continuationCheckpointDetail = computed(() =>
  formatContinuationCheckpointDetail(continuationCheckpoint.value),
)

const continuationNextActions = computed(() => {
  const message = latestRecoverableAssistant.value
  if (!message) return []
  return uniqueContinuationTexts([
    ...(message.taskRecovery?.nextActions ?? []),
    ...(message.turnRecoveryContext?.nextActions ?? []),
  ]).slice(0, 2)
})

const continuationRetainedContext = computed(() => {
  const message = latestRecoverableAssistant.value
  if (!message || isStreaming.value) {
    return []
  }
  const checkpoint = recoveryCheckpointForMessage(message)
  if (!checkpoint) {
    return []
  }
  const artifactRefs = compactRecoveryArtifactRefs(checkpoint.artifactRefs, message.artifactRefs)
  return buildToolRecoveryContextSummary({
    toolId: checkpoint.toolId,
    failureCategory: checkpoint.failureCategory,
    executionKind: checkpoint.executionKind,
    subjectLabel: checkpoint.subjectLabel,
    subjectNames: checkpoint.subjectNames,
    interrupted: checkpoint.interrupted,
    inputSummary: checkpoint.inputSummary,
    inputDetail: checkpoint.inputDetail,
    outputSummary: checkpoint.outputSummary,
    outputDetail: checkpoint.outputDetail,
    workingDirectory: checkpoint.workingDirectory,
    generatedFilePath: checkpoint.generatedFilePath,
    artifactRefs,
    missingCapabilities: checkpoint.missingCapabilities,
  }).slice(0, 3)
})

const continuationRepairLabel = computed(() => {
  const message = latestRecoverableAssistant.value
  if (!message) return null
  return buildMessageRepairLinks(message).some(link => link.id === 'capabilities') ? '能力中心' : null
})

const continuationRepairTitle = computed(() =>
  continuationRepairLabel.value
    ? '打开能力中心，检查工具、技能状态和 Skill 引用'
    : null,
)

function compactMissingCapabilityIds(message: Message | null | undefined) {
  const checkpoint = recoveryCheckpointForMessage(message)
  return Array.from(new Set(
    checkpoint?.missingCapabilities
      ?.map(item => item.id?.trim())
      .filter(Boolean) ?? [],
  ))
}

function recoverySkillName(message: Message | null | undefined) {
  const checkpoint = recoveryCheckpointForMessage(message)
  return checkpoint?.subjectNames?.find(Boolean)
    ?? checkpoint?.missingCapabilities?.find(item => item.skillName?.trim())?.skillName?.trim()
    ?? ''
}

function recoveryCheckpointForMessage(message: Message | null | undefined): TaskRecoveryCheckpoint | null {
  return message?.taskRecovery?.checkpoint
    ?? message?.turnRecoveryContext?.checkpoint
    ?? null
}

function uniqueContinuationTexts(values: string[]) {
  return Array.from(new Set(values.map(value => value.trim()).filter(Boolean)))
}

const isAwaitingUserReplyContinuation = computed(() => {
  const message = latestRecoverableAssistant.value
  if (!message || isStreaming.value) return false
  return isAwaitingUserReplyMessage(message)
})

function buildContinuationRecoveryAction(message: Message): ToolRecoveryAction | undefined {
  const recovery = message.taskRecovery
  const turnRecovery = message.turnRecoveryContext
  const checkpoint = recoveryCheckpointForMessage(message)
  if (!recovery && !checkpoint && !turnRecovery) return undefined
  const artifactRefs = compactRecoveryArtifactRefs(checkpoint?.artifactRefs, message.artifactRefs)
  return {
    id: 'task-recovery-resume',
    label: recovery?.actionLabel ?? '继续',
    description: checkpoint?.recoveryActionDescription ?? '保留当前进度，按恢复计划从卡住的位置继续。',
    mode: 'resume',
    category: checkpoint?.failureCategory,
    toolId: checkpoint?.toolId,
    callId: checkpoint?.callId,
    toolName: checkpoint?.toolName,
    executionKind: checkpoint?.executionKind,
    action: checkpoint?.action,
    interrupted: checkpoint?.interrupted,
    subjectLabel: checkpoint?.subjectLabel,
    subjectNames: checkpoint?.subjectNames,
    inputSummary: checkpoint?.inputSummary,
    inputDetail: checkpoint?.inputDetail,
    outputSummary: checkpoint?.outputSummary,
    outputDetail: checkpoint?.outputDetail,
    workingDirectory: checkpoint?.workingDirectory,
    generatedFilePath: checkpoint?.generatedFilePath,
    ...(artifactRefs ? { artifactRefs } : {}),
    missingCapabilities: checkpoint?.missingCapabilities,
    recoveryHint: recovery?.detail ?? turnRecovery?.detail,
    nextActions: uniqueContinuationTexts([
      ...(recovery?.nextActions ?? []),
      ...(turnRecovery?.nextActions ?? []),
    ]).slice(0, 3),
  }
}

function withMessageArtifactRefs(
  action: ToolRecoveryAction | undefined,
  message: Message,
): ToolRecoveryAction | undefined {
  if (!action) return undefined
  const artifactRefs = compactRecoveryArtifactRefs(action.artifactRefs, message.artifactRefs)
  return artifactRefs ? { ...action, artifactRefs } : action
}

function compactRecoveryArtifactRefs(
  ...groups: Array<Array<ArtifactRefPayload | Partial<ArtifactRefPayload>> | undefined | null>
): ArtifactRefPayload[] | undefined {
  const seen = new Set<string>()
  const refs: ArtifactRefPayload[] = []
  for (const group of groups) {
    for (const raw of group ?? []) {
      if (!raw?.artifactId?.trim()) continue
      const artifactId = raw.artifactId.trim()
      if (seen.has(artifactId)) continue
      seen.add(artifactId)
      refs.push({
        artifactId,
        fileName: raw.fileName?.trim() || artifactId,
        mimeType: raw.mimeType?.trim() || 'application/octet-stream',
        kind: raw.kind === 'IMAGE' ? 'IMAGE' : 'FILE',
        size: typeof raw.size === 'number' && Number.isFinite(raw.size) ? raw.size : 0,
        downloadUrl: raw.downloadUrl?.trim() || buildArtifactDownloadUrl(artifactId),
      })
      if (refs.length >= 8) return refs
    }
  }
  return refs.length > 0 ? refs : undefined
}

function formatContinuationCheckpointLabel(checkpoint: TaskRecoveryCheckpoint | null) {
  if (!checkpoint) return null
  const subject = checkpoint.subjectNames?.filter(Boolean).slice(0, 2).join('、')
  if (checkpoint.executionKind === 'SKILL' || checkpoint.failureCategory === 'SKILL') {
    return subject ? `技能 ${subject}` : (checkpoint.action ?? checkpoint.toolName ?? '技能步骤')
  }
  if (checkpoint.action && checkpoint.toolName && checkpoint.action !== checkpoint.toolName) {
    return `${checkpoint.action} · ${checkpoint.toolName}`
  }
  return checkpoint.toolName
    ?? checkpoint.action
    ?? formatToolFailureCategory(checkpoint.failureCategory)
    ?? '任务断点'
}

function formatContinuationCheckpointDetail(checkpoint: TaskRecoveryCheckpoint | null) {
  if (!checkpoint) return null
  const detail = checkpoint.outputSummary
    ?? checkpoint.inputSummary
    ?? checkpoint.outputDetail
    ?? checkpoint.generatedFilePath
    ?? checkpoint.workingDirectory
  if (!detail) return null
  return detail.length > 96 ? `${detail.slice(0, 96)}...` : detail
}

const inputPlaceholder = computed(() => {
  if (isAwaitingUserReplyContinuation.value) {
    return '补充信息，发送后继续…'
  }
  if (continuationTitle.value) {
    return '继续说…'
  }
  return '输入问题或贴资料…'
})

const linkedKnowledgeBases = computed(() => {
  const ids = activeSessionConfig.value.knowledgeBaseIds ?? []
  if (ids.length === 0) {
    return []
  }
  return ids.map(id => ({
    id,
    name: kbStore.list.find(knowledgeBase => knowledgeBase.id === id)?.name?.trim() || '资料库',
  }))
})

const hasMemorySignals = computed(() => {
  if (memoryStore.memoryDisabled || !memoryStore.stats) {
    return false
  }
  return memoryStore.stats.entityCount > 0
    || memoryStore.stats.preferenceCount > 0
    || memoryStore.stats.conversationCount > 0
    || memoryStore.stats.relationCount > 0
    || memoryStore.stats.templateCount > 0
})

const emptyPromptSuggestions = computed<EmptyPromptSuggestion[]>(() =>
  buildEmptyPromptSuggestions({
    linkedKnowledgeBases: linkedKnowledgeBases.value,
    hasKnowledgeBases: kbStore.list.length > 0,
    hasMemories: hasMemorySignals.value,
  }),
)
const showEmptyPromptGallery = computed(() =>
  isEmptyChat.value
  && !emptyComposerHasDraft.value
  && emptyPromptSuggestions.value.length > 0
)

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

// store 中的 title 由 SSE title-generated 事件实时更新，优先于可能过时的 currentSessionDetail
const headerTitle = computed(() => currentSession.value?.title?.trim() || currentSessionDetail.value?.title?.trim() || '新对话')
const activeContextCount = computed(() => (
  (activeSessionConfig.value.knowledgeBaseIds?.length ?? 0)
))
const sessionStatusText = computed(() => {
  if (isStreaming.value) {
    return normalizeTurnStatusText(reasoningStatusText.value) || '正在回应'
  }
  if (isAwaitingUserReplyContinuation.value) {
    return '等你补充'
  }
  if (continuationTitle.value) {
    return continuationStatusLabel.value ?? '可继续'
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

watch(error, () => {
  resetGlobalDiagnosticState()
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
    void applyPendingDraftMessage()
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
  if (!memoryStore.stats && !memoryStore.statsLoading && !memoryStore.memoryDisabled) {
    void memoryStore.loadStats()
  }

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
  await applyPendingDraftMessage()

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
  if (sourceFocusRaf !== null) {
    cancelAnimationFrame(sourceFocusRaf)
    sourceFocusRaf = null
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
let sourceFocusRaf: number | null = null

function scrollToBottom() {
  if (focusedSourceKey.value) return
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
  if (focusedSourceKey.value) return
  const el = getScrollEl()
  if (el && el.scrollHeight > el.clientHeight) {
    el.scrollTop = el.scrollHeight
  }
}

function escapeAttributeSelectorValue(value: string) {
  return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
}

function findFocusedMessageElement() {
  const el = getScrollEl()
  if (!el) return null

  const entryId = focusedSourceEntryId.value
  if (entryId) {
    const byEntry = el.querySelector<HTMLElement>(`[data-entry-id="${escapeAttributeSelectorValue(entryId)}"]`)
    if (byEntry) return byEntry
  }

  const turnId = focusedSourceTurnId.value
  if (turnId) {
    return el.querySelector<HTMLElement>(`[data-turn-id="${escapeAttributeSelectorValue(turnId)}"]`)
  }

  return null
}

function scheduleScrollToFocusedMessage() {
  const key = focusedSourceKey.value
  if (!key || appliedFocusedSourceKey.value === key) return

  if (sourceFocusRaf !== null) {
    cancelAnimationFrame(sourceFocusRaf)
    sourceFocusRaf = null
  }

  void nextTick(() => {
    sourceFocusRaf = requestAnimationFrame(() => {
      sourceFocusRaf = null
      if (focusedSourceKey.value !== key || appliedFocusedSourceKey.value === key) return
      const target = findFocusedMessageElement()
      if (!target) return
      target.scrollIntoView({ block: 'center', behavior: 'smooth' })
      appliedFocusedSourceKey.value = key
    })
  })
}

// 消息列表变化 → 有消息时标记就绪 + 滚到底部
watch(() => chatStore.messages.length, (len) => {
  if (len > 0) {
    messagesReady.value = true
    if (focusedSourceKey.value) {
      scheduleScrollToFocusedMessage()
    } else {
      for (const delay of [50, 200, 500]) {
        pendingTimers.push(window.setTimeout(forceScrollBottom, delay))
      }
    }
  }
})
watch(() => chatStore.streamingContent, scrollToBottom)

watch(focusedSourceKey, () => {
  appliedFocusedSourceKey.value = ''
  scheduleScrollToFocusedMessage()
}, { immediate: true })

watch(
  () => chatStore.messages.map(message => `${message.id}:${message.turnId ?? ''}`).join('|'),
  () => {
    scheduleScrollToFocusedMessage()
  },
)

async function handleSend(payload: {
  content: string
  attachmentIds?: string[]
  attachments?: ChatAttachment[]
  singleTurnOverride?: SessionConfigOverride | null
}) {
  emptyComposerHasDraft.value = false
  pendingMemoryDraft.value = null
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
const composerInputRef = ref<InstanceType<typeof ChatInput> | null>(null)

async function applyPendingDraftMessage() {
  const content = chatStore.pendingDraftMessage
  if (!content) return

  chatStore.pendingDraftMessage = null
  await nextTick()
  emptyInputRef.value?.setContent?.(content)
  emptyInputRef.value?.focus?.()
}

function handleEmptyDraftChange(payload: {
  content: string
  hasAttachments: boolean
  contextCount: number
}) {
  activeComposerDraftContent.value = payload.content
  emptyComposerHasDraft.value = Boolean(payload.content.trim())
    || payload.hasAttachments
    || payload.contextCount > 0
  syncPendingMemoryDraft(payload.content)
}

function handleComposerDraftChange(payload: {
  content: string
  hasAttachments: boolean
  contextCount: number
}) {
  activeComposerDraftContent.value = payload.content
  syncPendingMemoryDraft(payload.content)
}

async function fillActiveInput(content: string) {
  activeComposerDraftContent.value = content
  if (isEmptyChat.value) {
    emptyComposerHasDraft.value = Boolean(content.trim())
  }
  await nextTick()
  const target = isEmptyChat.value ? emptyInputRef.value : composerInputRef.value
  target?.setContent?.(content)
  target?.focus?.()
}

function syncPendingMemoryDraft(content: string) {
  if (!pendingMemoryDraft.value) return
  if (!content.trim().startsWith(REMEMBER_DRAFT_PREFIX)) {
    pendingMemoryDraft.value = null
  }
}

function buildRememberDraft(message: Message): string | null {
  const content = message.content.trim()
  if (!content) return null

  const availableLength = REMEMBER_DRAFT_MAX_LENGTH - REMEMBER_DRAFT_PREFIX.length
  const clippedContent = content.length > availableLength
    ? `${content.slice(0, Math.max(0, availableLength - 3)).trimEnd()}...`
    : content
  return `${REMEMBER_DRAFT_PREFIX}${clippedContent}`
}

function buildMemoryDraftPreview(message: Message): string {
  const compact = message.content.replace(/\s+/g, ' ').trim()
  if (!compact) return '这条消息'
  return compact.length > 48 ? `${compact.slice(0, 45).trimEnd()}...` : compact
}

function handleRememberMessage(message: Message) {
  const draft = buildRememberDraft(message)
  if (!draft) {
    uiStore.showToast('info', '这条消息没有可记住的文本')
    return
  }

  const previousContent = activeComposerDraftContent.value
  void fillActiveInput(draft)
  pendingMemoryDraft.value = {
    sourceMessageId: message.id,
    sourceLabel: message.role === 'assistant' ? '知微回复' : '你的消息',
    preview: buildMemoryDraftPreview(message),
    previousContent,
  }
  uiStore.showToast('info', '已放入输入框，发送后后台整理为记忆')
}

async function cancelPendingMemoryDraft() {
  const previousContent = pendingMemoryDraft.value?.previousContent ?? ''
  pendingMemoryDraft.value = null
  await fillActiveInput(previousContent)
}

function formatTimestampForFile(timestamp: number): string {
  const date = new Date(timestamp)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}-${pad(date.getHours())}${pad(date.getMinutes())}`
}

function formatTimestampForDocument(timestamp: number): string {
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) return '未知时间'
  return date.toLocaleString('zh-CN', { hour12: false })
}

function sanitizeFileName(value: string): string {
  const normalized = value
    .replace(/[\\/:*?"<>|]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return (normalized || MESSAGE_KNOWLEDGE_FALLBACK_TITLE).slice(0, 48)
}

function buildMessageKnowledgeTitle(message: Message): string {
  const firstLine = message.content
    .split(/\r?\n/)
    .map(line => line.replace(/^#+\s*/, '').trim())
    .find(Boolean)
  return (firstLine || MESSAGE_KNOWLEDGE_FALLBACK_TITLE).slice(0, 48)
}

function buildMessageKnowledgeFile(message: Message, targetName: string): File {
  const title = buildMessageKnowledgeTitle(message)
  const sessionTitle = headerTitle.value.trim()
  const sourceTitle = sessionTitle && sessionTitle !== '新对话'
    ? `知微对话 / ${sessionTitle}`
    : '知微对话'
  const markdown = [
    `# ${title}`,
    '',
    `> 来源：${sourceTitle}`,
    `> 时间：${formatTimestampForDocument(message.timestamp)}`,
    `> 存入：${targetName}`,
    '',
    message.content.trim(),
    '',
  ].join('\n')
  const fileName = `${sanitizeFileName(title)}-${formatTimestampForFile(message.timestamp)}.md`
  return new File([markdown], fileName, { type: 'text/markdown' })
}

async function handleSaveMessageToKnowledge(message: Message) {
  const target = artifactKnowledgeBaseTarget.value
  if (!target) {
    if (kbStore.list.length > 0) {
      uiStore.showToast('info', '先为会话选择一个资料库，再存入回复')
      overlays.openSettings()
    } else {
      uiStore.showToast('info', '先创建一个资料库，再存入回复')
    }
    return
  }
  if (!message.content.trim()) {
    uiStore.showToast('info', '这条消息没有可存入资料库的文本')
    return
  }
  if (savingKnowledgeMessageId.value) return

  savingKnowledgeMessageId.value = message.id
  clearSaveKnowledgeError(message.id)
  try {
    const file = buildMessageKnowledgeFile(message, target.name)
    await knowledgeBaseApi.uploadDocument(target.id, file)
    await persistKnowledgeSettlement(message.id, {
      knowledgeBaseId: target.id,
      knowledgeBaseName: target.name,
      sourceType: 'MESSAGE_TEXT',
    }, { throwOnError: true })
    await kbStore.fetchList()
    savedKnowledgeMessages.value = {
      ...savedKnowledgeMessages.value,
      [message.id]: {
        knowledgeBaseId: target.id,
        knowledgeBaseName: target.name,
      },
    }
    uiStore.showToast('success', `已存入资料库：${target.name}`)
  } catch (event) {
    logger.error('消息存入资料库失败:', event)
    const messageText = resolveErrorMessage(event, '存入资料库失败')
    saveKnowledgeErrors.value = {
      ...saveKnowledgeErrors.value,
      [message.id]: messageText,
    }
    uiStore.showToast('error', messageText)
  } finally {
    savingKnowledgeMessageId.value = null
  }
}

function clearSaveKnowledgeError(messageId: string) {
  if (!saveKnowledgeErrors.value[messageId]) return
  const { [messageId]: _removed, ...rest } = saveKnowledgeErrors.value
  saveKnowledgeErrors.value = rest
}

function handleArtifactSavedToKnowledge(message: Message, payload: ArtifactKnowledgeSavedPayload) {
  const knowledgeBaseId = payload.knowledgeBaseId?.trim()
  const knowledgeBaseName = payload.knowledgeBaseName?.trim()
  if (!message.id || !knowledgeBaseId || !knowledgeBaseName) {
    return
  }
  savedKnowledgeMessages.value = {
    ...savedKnowledgeMessages.value,
    [message.id]: {
      knowledgeBaseId,
      knowledgeBaseName,
    },
  }
  void Promise.resolve(kbStore.fetchList()).catch(event => {
    logger.warn('资料库列表刷新失败:', event)
  })
}

async function persistArtifactKnowledgeSettlement(message: Message, payload: ArtifactKnowledgeSavedPayload) {
  const knowledgeBaseId = payload.knowledgeBaseId?.trim()
  const knowledgeBaseName = payload.knowledgeBaseName?.trim()
  if (!message.id || !knowledgeBaseId || !knowledgeBaseName) {
    throw new Error('缺少可记录的消息或资料库信息')
  }
  await persistKnowledgeSettlement(message.id, {
    knowledgeBaseId,
    knowledgeBaseName,
    sourceType: 'ARTIFACT',
    artifactId: payload.artifactId,
    fileName: payload.fileName,
  }, { throwOnError: true })
}

async function persistKnowledgeSettlement(
  entryId: string,
  settlement: {
    knowledgeBaseId: string
    knowledgeBaseName: string
    sourceType: 'MESSAGE_TEXT' | 'ARTIFACT'
    artifactId?: string
    fileName?: string
  },
  options: { throwOnError?: boolean } = {},
) {
  try {
    await chatApi.recordKnowledgeSettlement(entryId, settlement)
  } catch (event) {
    logger.warn('记录资料库沉淀状态失败:', event)
    if (options.throwOnError) {
      throw event
    }
  }
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

async function handleTurnAction(
  message: Message,
  action: ChatTurnAction,
  recoveryAction?: ToolRecoveryAction,
) {
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
  const shouldUseRecoveryAction = !!recoveryAction && (action === 'RESUME' || action === 'RESTART')
  const enrichedRecoveryAction = shouldUseRecoveryAction
    ? withMessageArtifactRefs(recoveryAction, message)
    : undefined

  await executeTurn(message.turnId, action, {
    content: (action === 'RESUME' || action === 'RESTART')
      ? (shouldUseRecoveryAction ? undefined : (action === 'RESTART' ? userMessage?.content : undefined))
      : userMessage?.content,
    visibleContent: action === 'RESTART' && shouldUseRecoveryAction
      ? userMessage?.content
      : undefined,
    recoveryAction: enrichedRecoveryAction,
    attachmentIds: userMessage ? getAttachmentIds(userMessage) : undefined,
    attachments: userMessage?.attachments,
    userMessageId: userMessage?.id,
    preserveUserMessageContent: shouldUseRecoveryAction,
  })
}

async function handleRetry(message: Message) {
  await handleTurnAction(message, message.turnId ? 'RETRY' : 'SEND')
}

async function handleRegenerate(assistantMessage: Message) {
  await handleTurnAction(assistantMessage, 'RESTART')
}

async function handleResume(assistantMessage: Message, recoveryAction?: ToolRecoveryAction) {
  await handleTurnAction(assistantMessage, 'RESUME', recoveryAction)
}

async function handleRestart(assistantMessage: Message, recoveryAction?: ToolRecoveryAction) {
  await handleTurnAction(assistantMessage, 'RESTART', recoveryAction)
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

function handleCopy(_content: string, ok: boolean) {
  uiStore.showToast(ok ? 'success' : 'error', ok ? '已复制到剪贴板' : '复制失败')
}

function resolveErrorMessage(event: unknown, fallback: string) {
  if (event instanceof Error) return event.message
  if (typeof event === 'object' && event !== null && 'message' in event) {
    return String((event as { message?: unknown }).message ?? fallback)
  }
  return typeof event === 'string' ? event : fallback
}

function applyGlobalDiagnosticReport(report: DiagnosticReport) {
  globalDiagnosticStatus.value = report.status
  globalDiagnosticSummary.value = report.summary
  globalDiagnosticActions.value = buildDiagnosticNextActions(report, error.value).slice(0, 3)
  globalDiagnosticRepairLinks.value = buildDiagnosticRepairLinks(report)
  globalDiagnosticError.value = null
}

function applyGlobalDiagnosticFailure(reason: string) {
  globalDiagnosticStatus.value = null
  globalDiagnosticSummary.value = null
  globalDiagnosticError.value = reason
  globalDiagnosticRepairLinks.value = []
  const actions = buildDiagnosticNextActions(null, error.value)
  globalDiagnosticActions.value = actions.length > 0
    ? actions.slice(0, 3)
    : ['本机状态暂时读取失败，先复制诊断信息并查看后端日志。']
}

function resetGlobalDiagnosticState() {
  globalDiagnosticStatus.value = null
  globalDiagnosticSummary.value = null
  globalDiagnosticActions.value = []
  globalDiagnosticError.value = null
  globalDiagnosticRepairLinks.value = []
}

async function handleAnalyzeGlobalDiagnostic() {
  if (analyzingGlobalDiagnostic.value) return
  analyzingGlobalDiagnostic.value = true
  globalDiagnosticError.value = null
  try {
    const report = await diagnosticsApi.getReport()
    applyGlobalDiagnosticReport(report)
  } catch (event) {
    applyGlobalDiagnosticFailure(resolveErrorMessage(event, '获取本地诊断失败'))
  } finally {
    analyzingGlobalDiagnostic.value = false
  }
}

function handleOpenDiagnosticRepair(link: DiagnosticRepairLink) {
  router.push({ name: link.routeName })
}

async function handleCopyGlobalErrorDiagnostic() {
  const latestMessage = chatStore.messages.at(-1) ?? null
  let diagnosticReport: DiagnosticReport | null = null
  let diagnosticReportError: string | null = null
  try {
    diagnosticReport = await diagnosticsApi.getReport()
    applyGlobalDiagnosticReport(diagnosticReport)
  } catch (event) {
    diagnosticReportError = resolveErrorMessage(event, '获取本地诊断失败')
    applyGlobalDiagnosticFailure(diagnosticReportError)
  }
  const ok = await copyToClipboard(buildErrorDiagnostic({
    scope: 'global',
    error: error.value,
    message: latestMessage,
    sessionId: chatStore.activeSessionId,
    route: route.fullPath,
    streaming: isStreaming.value,
    lastPrompt: lastPrompt.value,
    diagnosticReport,
    diagnosticReportError,
  }))
  uiStore.showToast(ok ? 'success' : 'error', ok ? '诊断信息已复制' : '复制诊断失败')
}

async function handleCreateLocalBackup() {
  if (creatingBackup.value) return
  creatingBackup.value = true
  try {
    const backup = await diagnosticsApi.createBackup()
    const validation = await diagnosticsApi.validateBackup(backup.fileName)
    if (validation.status === 'OK') {
      uiStore.showToast('success', `已创建并校验本地备份：${backup.fileName}`)
    } else if (validation.status === 'ERROR') {
      uiStore.showToast('error', `备份已创建，但校验失败：${validation.detail}`)
    } else {
      uiStore.showToast('info', `备份已创建，建议确认：${validation.detail}`)
    }
  } catch (event) {
    logger.error('创建本地备份失败:', event)
    uiStore.showToast('error', resolveErrorMessage(event, '创建本地备份失败'))
  } finally {
    creatingBackup.value = false
  }
}

async function handleCreateDiagnosticBundle() {
  if (creatingDiagnosticBundle.value) return
  creatingDiagnosticBundle.value = true
  try {
    const bundle = await diagnosticsApi.createDiagnosticBundle()
    uiStore.showToast('success', `已生成诊断包：${bundle.fileName}`)
  } catch (event) {
    logger.error('生成本地诊断包失败:', event)
    uiStore.showToast('error', resolveErrorMessage(event, '生成本地诊断包失败'))
  } finally {
    creatingDiagnosticBundle.value = false
  }
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

function handlePromptPick(suggestion: { prompt: string }) {
  // 点击轻量建议后，把 prompt 灌入当前输入框并聚焦
  pendingMemoryDraft.value = null
  void fillActiveInput(suggestion.prompt)
}

function handleMessageFollowUp(prompt: string) {
  pendingMemoryDraft.value = null
  void fillActiveInput(prompt)
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
      toolSummaries: lastAssistantMessage.value?.toolsSummary ?? [],
      turnRecoveryContext: lastAssistantMessage.value?.turnRecoveryContext ?? null,
      streaming: true,
      traceId: undefined as string | undefined,
    }
  }

  const msg = chatStore.messages.find(m => m.id === id)
  if (!msg) return null

  return {
    reasoningEvents: msg.reasoningEvents ?? [],
    reactSteps: msg.reactSteps ?? [],
    toolSummaries: msg.toolsSummary ?? [],
    turnRecoveryContext: msg.turnRecoveryContext ?? null,
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

function handleInspectMemory(source: SourceSummary) {
  activeMemorySource.value = source
  overlays.openMemory()
}

function closeMemoryPanel() {
  if (overlays.activeOverlay.value === 'memory') {
    overlays.close()
  }
}

function handleMemoryUpdated(entity: EntityDetail) {
  if (!activeMemorySource.value || activeMemorySource.value.id !== entity.id) return
  activeMemorySource.value = applyEntityToMemorySource(activeMemorySource.value, entity)
  for (const message of chatStore.messages) {
    const patch = buildMemoryEntityMessagePatch(message, entity)
    if (patch) {
      chatStore.updateMessage(message.id, patch)
    }
  }
}

function handleMemoryDeleted(entityId: string, deletedSource?: SourceSummary) {
  const shouldKeepDeletedExplanation = activeMemorySource.value?.id === entityId
  if (activeMemorySource.value?.id === entityId) {
    activeMemorySource.value = deletedSource ?? buildDeletedMemorySource(activeMemorySource.value)
  }
  for (const message of chatStore.messages) {
    const patch = buildMemoryEntityRemovalMessagePatch(message, entityId)
    if (patch) {
      chatStore.updateMessage(message.id, patch)
    }
  }
  if (!shouldKeepDeletedExplanation) {
    closeMemoryPanel()
  }
}

/* ── Overlay 统一管理 ── */

const overlays = useChatOverlays()

function dismissContinuationHint() {
  // 轻量忽略：只标记不同步到后端
  continuationHintDismissed.value = true
}
const continuationHintDismissed = ref(false)

function handleContinuationRepair() {
  if (!continuationRepairLabel.value) return
  const target = latestRecoverableAssistant.value
  const returnSessionId = recoveryReturnSessionId.value || undefined
  const missing = compactMissingCapabilityIds(target)
  const skill = recoverySkillName(target)
  router.push({
    name: 'capabilities',
    query: {
      from: 'task-recovery',
      returnSessionId,
      returnTurnId: target?.turnId ?? undefined,
      returnEntryId: target?.id ?? undefined,
      missing: missing.length ? missing.join(',') : undefined,
      skill: skill || undefined,
    },
  })
}

/** 点击 ContinuationHint 的"继续"按钮 → 带上恢复摘要触发挂起会话恢复 */
async function handleContinuationResume() {
  const target = latestRecoverableAssistant.value
  if (!target) return
  if (!canUseManualResumeForMessage(target)) return
  await handleResume(target, buildContinuationRecoveryAction(target))
}

watch(() => latestRecoverableAssistant.value?.id, () => {
  continuationHintDismissed.value = false
})

const shouldShowContinuationHint = computed(() =>
  Boolean(continuationTitle.value)
    && !continuationHintDismissed.value
    && !isStreaming.value
)

watch(isEmptyChat, (empty) => {
  if (!empty) {
    emptyComposerHasDraft.value = false
  }
})
</script>

<template>
  <div class="chat-shell">
    <ChatHeader
      :is-empty="isEmptyChat"
      :title="headerTitle"
      :task-count="processTaskStore.tasksOrdered.length"
      :has-active-task="processTaskStore.runningCount > 0"
      @rename="handleUpdateSessionTitle"
      @open-info="overlays.openInfo"
      @open-settings="overlays.openSettings"
      @open-tasks="overlays.openTasks"
    />

    <div class="chat-main">
      <section class="chat-main__stream">
        <div
          ref="scrollContainer"
          data-scroll-container
          class="chat-scroll scrollbar-thin scrollbar-track-transparent scrollbar-thumb-border"
        >
          <!-- 空态：Wordmark + 欢迎语 + 居中 Composer + 轻提示 -->
          <div v-if="isEmptyChat" key="empty" class="chat-empty">
            <EmptyState />
            <StatePanel
              v-if="showGlobalErrorPanel"
              class="chat-empty__error"
              title="本轮对话出现错误"
              :description="error ?? undefined"
              tone="danger"
            >
              <template #actions>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  :disabled="analyzingGlobalDiagnostic"
                  @click="handleAnalyzeGlobalDiagnostic"
                >
                  <Loader2 v-if="analyzingGlobalDiagnostic" class="mr-1.5 size-3.5 animate-spin" />
                  <Activity v-else class="mr-1.5 size-3.5" />
                  {{ analyzingGlobalDiagnostic ? '分析中' : showGlobalDiagnosticDetail ? '重新分析' : '分析本机' }}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  :disabled="creatingBackup"
                  @click="handleCreateLocalBackup"
                >
                  <Loader2 v-if="creatingBackup" class="mr-1.5 size-3.5 animate-spin" />
                  <Archive v-else class="mr-1.5 size-3.5" />
                  {{ creatingBackup ? '备份中' : '创建备份' }}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  :disabled="creatingDiagnosticBundle"
                  @click="handleCreateDiagnosticBundle"
                >
                  <Loader2 v-if="creatingDiagnosticBundle" class="mr-1.5 size-3.5 animate-spin" />
                  <FileArchive v-else class="mr-1.5 size-3.5" />
                  {{ creatingDiagnosticBundle ? '生成中' : '生成诊断包' }}
                </Button>
                <Button type="button" variant="outline" size="sm" @click="handleCopyGlobalErrorDiagnostic">
                  <Copy class="mr-1.5 size-3.5" />
                  复制诊断
                </Button>
                <Button type="button" variant="outline" size="sm" @click="error = null">
                  关闭
                </Button>
              </template>
              <div
                v-if="showGlobalDiagnosticDetail"
                class="mt-3 space-y-2 rounded-md border border-destructive/15 bg-destructive/[0.03] px-3 py-2 text-xs leading-5 text-destructive/85"
                role="status"
              >
                <p v-if="globalDiagnosticSummary">
                  本机状态{{ globalDiagnosticStatus ? ` ${globalDiagnosticStatus}` : '' }}：{{ globalDiagnosticSummary }}
                </p>
                <p v-if="globalDiagnosticError" class="text-destructive/90">本机状态分析失败：{{ globalDiagnosticError }}</p>
                <ul v-if="globalDiagnosticActions.length > 0" class="space-y-1">
                  <li
                    v-for="action in globalDiagnosticActions"
                    :key="action"
                    class="flex gap-1.5"
                  >
                    <span aria-hidden="true">-</span>
                    <span>{{ action }}</span>
                  </li>
                </ul>
                <div v-if="globalDiagnosticRepairLinkViews.length > 0" class="flex flex-wrap gap-2">
                  <Button
                    v-for="link in globalDiagnosticRepairLinkViews"
                    :key="link.id"
                    type="button"
                    variant="outline"
                    size="sm"
                    :title="link.title"
                    @click="handleOpenDiagnosticRepair(link)"
                  >
                    <component :is="link.icon" class="mr-1.5 size-3.5" />
                    {{ link.label }}
                  </Button>
                </div>
              </div>
            </StatePanel>
            <div class="chat-empty__composer">
              <div
                v-if="pendingMemoryDraft"
                class="memory-draft-notice"
                role="status"
              >
                <span class="memory-draft-notice__icon" aria-hidden="true">
                  <Brain class="size-3.5" />
                </span>
                <div class="memory-draft-notice__body">
                  <div class="memory-draft-notice__title">
                    <span>确认后记住</span>
                    <small>发送后后台整理，可查看/调整</small>
                  </div>
                  <p>来源：{{ pendingMemoryDraft.sourceLabel }} · {{ pendingMemoryDraft.preview }}</p>
                </div>
                <button
                  type="button"
                  class="memory-draft-notice__close"
                  aria-label="取消这条记忆草稿"
                  title="取消这条记忆草稿"
                  @click="cancelPendingMemoryDraft"
                >
                  <X class="size-3.5" />
                </button>
              </div>
              <ChatInput
                ref="emptyInputRef"
                :placeholder="inputPlaceholder"
                :knowledge-bases="kbStore.list"
                :base-session-config="activeSessionConfig"
                @send="handleSend"
                @draft-change="handleEmptyDraftChange"
              />
              <div v-if="showEmptyPromptGallery" class="chat-empty__gallery">
                <PromptGallery
                  :suggestions="emptyPromptSuggestions"
                  @pick="handlePromptPick"
                />
              </div>
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
              :streaming-artifact-refs="streamingArtifactRefs"
              :query="searchQuery"
              :focused-turn-id="focusedSourceTurnId"
              :focused-entry-id="focusedSourceEntryId"
              :project-id="activeMemoryProjectId"
              :recovery-return-session-id="recoveryReturnSessionId"
              :artifact-knowledge-base-id="artifactKnowledgeBaseTarget?.id ?? null"
              :artifact-knowledge-base-name="artifactKnowledgeBaseTarget?.name ?? null"
              :persist-artifact-knowledge-settlement="persistArtifactKnowledgeSettlement"
              :saving-knowledge-message-id="savingKnowledgeMessageId"
              :saved-knowledge-messages="savedKnowledgeMessages"
              :save-knowledge-errors="saveKnowledgeErrors"
              @retry="handleRetry"
              @edit="handleEdit"
              @like="handleLike"
              @dislike="handleDislike"
              @fork="handleFork"
              @regenerate="handleRegenerate"
              @resume="handleResume"
              @restart="handleRestart"
              @copy="handleCopy"
              @remember="handleRememberMessage"
              @save-knowledge="handleSaveMessageToKnowledge"
              @save-artifact-knowledge="handleArtifactSavedToKnowledge"
              @follow-up="handleMessageFollowUp"
              @show-trace="handleShowTrace"
              @inspect-memory="handleInspectMemory"
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
              <!-- 悬浮：回到底部按钮（居中） -->
              <div class="chat-floating-cluster">
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

              <!-- 任务恢复小卡片 -->
              <ContinuationHint
                v-if="shouldShowContinuationHint && continuationTitle"
                class="chat-composer-wrap__continuation"
                :title="continuationTitle"
                :detail="continuationDetail"
                :input-hint="continuationInputHint"
                :checkpoint-label="continuationCheckpointLabel"
                :checkpoint-detail="continuationCheckpointDetail"
                :next-actions="continuationNextActions"
                :retained-context="continuationRetainedContext"
                :can-resume="continuationCanResume"
                :action-label="continuationActionLabel"
                :status-label="continuationStatusLabel"
                :repair-label="continuationRepairLabel"
                :repair-title="continuationRepairTitle"
                @resume="handleContinuationResume"
                @repair="handleContinuationRepair"
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
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    :disabled="analyzingGlobalDiagnostic"
                    @click="handleAnalyzeGlobalDiagnostic"
                  >
                    <Loader2 v-if="analyzingGlobalDiagnostic" class="mr-1.5 size-3.5 animate-spin" />
                    <Activity v-else class="mr-1.5 size-3.5" />
                    {{ analyzingGlobalDiagnostic ? '分析中' : showGlobalDiagnosticDetail ? '重新分析' : '分析本机' }}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    :disabled="creatingBackup"
                    @click="handleCreateLocalBackup"
                  >
                    <Loader2 v-if="creatingBackup" class="mr-1.5 size-3.5 animate-spin" />
                    <Archive v-else class="mr-1.5 size-3.5" />
                    {{ creatingBackup ? '备份中' : '创建备份' }}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    :disabled="creatingDiagnosticBundle"
                    @click="handleCreateDiagnosticBundle"
                  >
                    <Loader2 v-if="creatingDiagnosticBundle" class="mr-1.5 size-3.5 animate-spin" />
                    <FileArchive v-else class="mr-1.5 size-3.5" />
                    {{ creatingDiagnosticBundle ? '生成中' : '生成诊断包' }}
                  </Button>
                  <Button type="button" variant="outline" size="sm" @click="handleCopyGlobalErrorDiagnostic">
                    <Copy class="mr-1.5 size-3.5" />
                    复制诊断
                  </Button>
                  <Button type="button" variant="outline" size="sm" @click="error = null">
                    关闭
                  </Button>
                </template>
                <div
                  v-if="showGlobalDiagnosticDetail"
                  class="mt-3 space-y-2 rounded-md border border-destructive/15 bg-destructive/[0.03] px-3 py-2 text-xs leading-5 text-destructive/85"
                  role="status"
                >
                  <p v-if="globalDiagnosticSummary">
                    本机状态{{ globalDiagnosticStatus ? ` ${globalDiagnosticStatus}` : '' }}：{{ globalDiagnosticSummary }}
                  </p>
                  <p v-if="globalDiagnosticError" class="text-destructive/90">本机状态分析失败：{{ globalDiagnosticError }}</p>
                  <ul v-if="globalDiagnosticActions.length > 0" class="space-y-1">
                    <li
                      v-for="action in globalDiagnosticActions"
                      :key="action"
                      class="flex gap-1.5"
                    >
                      <span aria-hidden="true">-</span>
                      <span>{{ action }}</span>
                    </li>
                  </ul>
                  <div v-if="globalDiagnosticRepairLinkViews.length > 0" class="flex flex-wrap gap-2">
                    <Button
                      v-for="link in globalDiagnosticRepairLinkViews"
                      :key="link.id"
                      type="button"
                      variant="outline"
                      size="sm"
                      :title="link.title"
                      @click="handleOpenDiagnosticRepair(link)"
                    >
                      <component :is="link.icon" class="mr-1.5 size-3.5" />
                      {{ link.label }}
                    </Button>
                  </div>
                </div>
              </StatePanel>

              <div
                v-if="pendingMemoryDraft"
                class="memory-draft-notice"
                role="status"
              >
                <span class="memory-draft-notice__icon" aria-hidden="true">
                  <Brain class="size-3.5" />
                </span>
                <div class="memory-draft-notice__body">
                  <div class="memory-draft-notice__title">
                    <span>确认后记住</span>
                    <small>发送后后台整理，可查看/调整</small>
                  </div>
                  <p>来源：{{ pendingMemoryDraft.sourceLabel }} · {{ pendingMemoryDraft.preview }}</p>
                </div>
                <button
                  type="button"
                  class="memory-draft-notice__close"
                  aria-label="取消这条记忆草稿"
                  title="取消这条记忆草稿"
                  @click="cancelPendingMemoryDraft"
                >
                  <X class="size-3.5" />
                </button>
              </div>

              <ChatInput
                ref="composerInputRef"
                :streaming="isStreaming"
                :placeholder="inputPlaceholder"
                :knowledge-bases="kbStore.list"
                :base-session-config="activeSessionConfig"
                @send="handleSend"
                @stop="abort"
                @draft-change="handleComposerDraftChange"
              />
            </div>
          </div>
        </Transition>
      </section>
    </div>

    <!-- Overlay：Trace / Tasks / Settings / Info（fixed 定位，覆盖整个视口） -->
    <OverlayHost
      :open="overlays.activeOverlay.value === 'trace'"
      @close="closeTracePanel"
    >
      <div class="overlay-scroll">
        <div class="overlay-heading">
          <div class="overlay-heading__eyebrow">任务进展</div>
          <div class="overlay-heading__title">任务步骤</div>
        </div>
        <TracePanel
          v-if="activeTraceData"
          :reasoning-events="activeTraceData.reasoningEvents"
          :react-steps="activeTraceData.reactSteps"
          :tool-summaries="activeTraceData.toolSummaries"
          :turn-recovery-context="activeTraceData.turnRecoveryContext"
          :streaming="activeTraceData.streaming"
          :trace-id="activeTraceData.traceId"
          hide-header
        />
      </div>
    </OverlayHost>

    <OverlayHost
      :open="overlays.activeOverlay.value === 'memory'"
      :show-close="false"
      @close="closeMemoryPanel"
    >
      <div class="overlay-scroll">
        <MemoryInsightPanel
          :source="activeMemorySource"
          :project-id="activeMemoryProjectId"
          @close="closeMemoryPanel"
          @updated="handleMemoryUpdated"
          @deleted="handleMemoryDeleted"
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
  padding: 13vh var(--chat-gutter-x-desktop, 24px) 24px;
  gap: 18px;
}

.chat-empty__gallery {
  width: 100%;
  display: flex;
  justify-content: flex-start;
  padding: 8px 18px 0;
  animation: fade-slide-in 500ms ease-out both;
  animation-delay: 260ms;
}

.chat-empty__composer {
  width: 100%;
  max-width: 600px;
  animation: fade-slide-in 500ms ease-out both;
  animation-delay: 180ms;
}

.chat-empty__error {
  width: min(100%, 600px);
  animation: fade-slide-in 500ms ease-out both;
  animation-delay: 210ms;
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

.memory-draft-notice {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 44px;
  margin-bottom: 8px;
  padding: 8px 8px 8px 10px;
  border: 1px solid hsl(from var(--border) h s l / 0.62);
  border-radius: 8px;
  background: hsl(from var(--background) h s l / 0.94);
  box-shadow: 0 8px 24px -18px hsl(var(--shadow-color) / 0.2);
}

.memory-draft-notice__icon {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 999px;
  background: hsl(var(--primary) / 0.1);
  color: var(--primary);
}

.memory-draft-notice__body {
  min-width: 0;
  flex: 1;
}

.memory-draft-notice__title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
  font-size: 13px;
  line-height: 1.35;
  color: var(--foreground);
}

.memory-draft-notice__title span {
  flex: 0 0 auto;
  font-weight: 600;
}

.memory-draft-notice__title small {
  min-width: 0;
  overflow: hidden;
  color: var(--muted-foreground);
  font-size: 12px;
  font-weight: 400;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.memory-draft-notice__body p {
  margin: 2px 0 0;
  overflow: hidden;
  color: var(--muted-foreground);
  font-size: 12px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.memory-draft-notice__close {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--muted-foreground);
  cursor: pointer;
  transition:
    background 120ms ease,
    color 120ms ease;
}

.memory-draft-notice__close:hover {
  background: hsl(from var(--muted) h s l / 0.7);
  color: var(--foreground);
}

.memory-draft-notice__close:focus-visible {
  outline: 2px solid hsl(var(--primary) / 0.32);
  outline-offset: 2px;
}

/* 悬浮集群：停止生成 + 回到底部，竖排居中 */
.chat-floating-cluster {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  bottom: calc(100% + 12px);
  display: flex;
  flex-direction: column-reverse;
  align-items: center;
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
