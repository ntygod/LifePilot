import { computed, getCurrentInstance, ref } from 'vue'
import { useRoute } from 'vue-router'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useA2uiStore } from '@/stores/a2ui'
import { chatApi, browserTakeoverApi, memoryApi } from '@/api/client'
import { buildArtifactDownloadUrl, type ArtifactRefPayload } from '@/api/artifacts'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'
import { logger } from '@/utils/logger'
import {
  buildToolRecoveryActions,
  buildToolRecoveryPlan,
  resolveToolAction,
  resolveToolExecutionKind,
  resolveToolFailureCategory,
  resolveToolRecoveryHint,
} from '@/utils/toolExecution'
import { isUserReplyRecovery, resolveRecoverableTaskStatus } from '@/utils/taskRecovery'
import type {
  A2uiComponent,
  ChatAttachment,
  ChatTurnAction,
  ChatTurnStatus,
  MemoryChangeStatus,
  ReasoningEvent,
  ReactStepDto,
  SessionConfigOverride,
  SseAgentSuspendedEvent,
  SseDoneEvent,
  SseErrorEvent,
  SseMediaEvent,
  SseTokenEvent,
  TokenUsage,
  PermissionApprovalRequest,
  SessionConfig,
  SourceSummary,
  ToolRecoveryAction,
  ToolCallSummary,
} from '@/types'

type ExecuteTurnOptions = {
  content?: string
  visibleContent?: string | null
  attachmentIds?: string[]
  attachments?: ChatAttachment[]
  /** 单轮临时覆盖的会话配置（模型 / KB / 温度 / 预算）。仅影响本轮 Agent 执行，不污染持久化 config。 */
  singleTurnOverride?: SessionConfigOverride | null
  userMessageId?: string | null
  /** 内部恢复/重启说明只发给后端执行，不覆盖对话里原始用户消息。 */
  preserveUserMessageContent?: boolean
  /** 点击恢复按钮时携带的结构化断点动作，由后端统一转成恢复上下文。 */
  recoveryAction?: ToolRecoveryAction | null
}

type InterruptedCompletionState = Partial<Pick<SseDoneEvent, 'turnStatus' | 'completionMode' | 'taskRecovery'>>

const TOKEN_FLUSH_INTERVAL_MS = 40
const TOKEN_FLUSH_CHAR_THRESHOLD = 160
const MEMORY_CHANGE_REFRESH_DELAYS_MS = [600, 1800, 3600, 7200, 12000] as const
const MEMORY_CHANGE_EMPTY_VISIBLE_MS = 2400
const MEMORY_STATUS_EXPLICIT_PATTERN = /(记住|记下|帮我记|记到(长期)?记忆|记进(长期)?记忆|写入(长期)?记忆|存(到|进)(长期)?记忆|加入(长期)?记忆|沉淀(到|为)?(长期)?记忆|长期记忆|偏好|习惯|我的设置|我的要求|不要忘|以后.*(默认|都|请|记得|叫我|称呼|用|不要)|我.*(喜欢|希望|更喜欢|不喜欢)|remember|memorize|preference|habit|keep in mind)/
const MEMORY_STATUS_PROFILE_PATTERN = /(我的(名字|昵称|生日|邮箱|电话|手机号|微信|城市|地址|公司|职业|岗位|工作|项目|要求|偏好)|我(叫|来自|住在|负责|主要做|目前做)|my name is|call me|my birthday|my email|i work|i live)/
const MEMORY_STATUS_CONTEXT_PATTERN = /(这个(应用|产品|项目)|本项目|当前项目|项目背景|产品定位|应用定位|核心定位|阶段重点|长期目标|目标是|需求是|约束是|原则是|边界是|我正在|我们正在|最近在做|当前在做|后续.*(都|默认|优先|保持)|之后.*(都|默认|优先|保持)|workflow|roadmap|requirement|constraint)/

export function useChat() {
  const chatStore = useChatStore()
  const a2uiStore = useA2uiStore()
  // 懒创建会话时读 URL query.projectId，实现「项目详情页 → 新建对话」的项目上下文继承
  const route = getCurrentInstance()
    ? useRoute()
    : ({ query: {} } as Pick<RouteLocationNormalizedLoaded, 'query'>)

  const isStreaming = ref(false)
  const error = ref<string | null>(null)
  const lastPrompt = ref<string | null>(null)
  const lastModelId = ref<string | null>(null)
  const lastTokenUsage = ref<TokenUsage | null>(null)
  const reasoningEvents = ref<ReasoningEvent[]>([])
  const reasoningStatusText = ref<string | null>(null)
  /**
   * 推理 token 流缓冲区（DeepSeek/Qwen 等推理模型的 reasoning_content 增量累计）。
   *
   * <p>来源：后端 StreamingCallback#pushReasoningToSse 推送的 SSE reasoning 事件
   * （payload 含 delta 字段），与 ReactAgentLoop 推送的 ReAct 步骤事件共享同名事件
   * 但 payload 形态不同。
   */
  const reasoningBuffer = ref<string>('')
  /** 是否处于推理流活跃中（首个 reasoning delta 触发，DONE 复位） */
  const isReasoningActive = ref<boolean>(false)
  /** 当前轮推理流持续时间（毫秒），DONE 时定格供历史消息渲染 */
  const reasoningDurationMs = ref<number>(0)
  // 首个 reasoning delta 到达时间戳；用于流结束时计算思考时长
  let reasoningStartedAt: number | null = null
  const streamingReactSteps = ref<ReactStepDto[]>([])
  const streamingMedia = ref<SseMediaEvent[]>([])
  const streamingArtifactRefs = ref<import('@/api/artifacts').ArtifactRefPayload[]>([])
  const pendingPermissionApprovals = ref<Map<string, PermissionApprovalRequest>>(new Map())
  const pendingPermissionApprovalResolutions = ref<Map<string, 'approved' | 'rejected' | 'expired'>>(new Map())
  /** 浏览器人工接管 modal 状态：非空表示需要展示 HumanTakeoverModal */
  const activeBrowserTakeover = ref<{
    turnId: string
    sessionId: string
    reason: string
    timeoutSeconds: number
  } | null>(null)
  /** 浏览器接管恢复/取消请求失败时的错误信息，用于在 Modal 内提示并允许重试 */
  const browserTakeoverError = ref<string | null>(null)

  let abortController: AbortController | null = null
  let currentTurnId: string | null = null
  let currentUserMessageId: string | null = null
  let currentTurnProjectId: string | null = null
  let currentMemoryChangeStatusVisible = false
  let currentExecutionSeq = 0
  let pendingStreamingText = ''
  let pendingStreamingFlushTimer: ReturnType<typeof setTimeout> | null = null

  const activeSessionProjectId = computed(() => {
    const sessionId = chatStore.activeSessionId
    const sessionProjectId = sessionId
      ? chatStore.sessions.find(session => session.id === sessionId)?.projectId
      : null
    return normalizeProjectId(sessionProjectId) ?? normalizeProjectId(route.query.projectId)
  })

  function flushStreamingText() {
    if (pendingStreamingFlushTimer !== null) {
      clearTimeout(pendingStreamingFlushTimer)
      pendingStreamingFlushTimer = null
    }
    if (!pendingStreamingText) {
      return
    }
    chatStore.streamingContent += pendingStreamingText
    pendingStreamingText = ''
  }

  function clearStreamingTextBuffer() {
    if (pendingStreamingFlushTimer !== null) {
      clearTimeout(pendingStreamingFlushTimer)
      pendingStreamingFlushTimer = null
    }
    pendingStreamingText = ''
  }

  function scheduleStreamingFlush() {
    if (pendingStreamingFlushTimer !== null) {
      return
    }
    pendingStreamingFlushTimer = setTimeout(() => {
      pendingStreamingFlushTimer = null
      flushStreamingText()
    }, TOKEN_FLUSH_INTERVAL_MS)
  }

  function queueStreamingText(content: string) {
    if (!content) {
      return
    }
    pendingStreamingText += content
    if (pendingStreamingText.length >= TOKEN_FLUSH_CHAR_THRESHOLD) {
      flushStreamingText()
      return
    }
    scheduleStreamingFlush()
  }

  async function sendMessage(
    content: string,
    attachmentIds?: string[],
    attachments?: ChatAttachment[],
    singleTurnOverride?: SessionConfigOverride | null,
  ) {
    const suspendedTurn = findLatestSuspendedTurn()
    if (suspendedTurn?.turnId) {
      await executeTurn(suspendedTurn.turnId, 'RESUME', {
        content,
        attachmentIds,
        attachments,
        singleTurnOverride,
        recoveryAction: buildAutoResumeRecoveryAction(suspendedTurn),
      })
      return
    }

    const turnId = crypto.randomUUID()
    await executeTurn(turnId, 'SEND', {
      content,
      attachmentIds,
      attachments,
      singleTurnOverride,
    })
  }

  async function executeTurn(
    turnId: string,
    action: ChatTurnAction,
    options: ExecuteTurnOptions = {},
  ) {
    const executionSeq = ++currentExecutionSeq
    const content = options.content ?? ''
    const hasContent = content.trim().length > 0
    const hasAttachments = (options.attachmentIds?.length ?? 0) > 0

    if (action === 'SEND' && !hasContent && !hasAttachments) {
      return
    }

    const userMessageId = prepareTurnMessages(
      turnId,
      action,
      content,
      options.visibleContent,
      options.attachments,
      options.singleTurnOverride ?? null,
      options.userMessageId,
      options.preserveUserMessageContent === true,
    )
    currentTurnId = turnId
    currentUserMessageId = userMessageId
    currentTurnProjectId = null
    currentMemoryChangeStatusVisible = shouldSurfaceMemoryChangeStatus(options.visibleContent ?? content)

    resetStreamingState(options.visibleContent ?? content)
    abortController = new AbortController()

    try {
      if (!chatStore.activeSessionId) {
        const projectIdFromQuery = normalizeProjectId(route.query.projectId)
        try {
          await chatStore.startNewSession(undefined, projectIdFromQuery, { preserveCurrentMessages: true })
        } catch {
          throw new Error('创建会话失败，请重试')
        }
      }
      currentTurnProjectId = activeSessionProjectId.value
      const baseStreamArgs = [
        content,
        chatStore.activeSessionId ?? undefined,
        options.attachmentIds,
        turnId,
        action,
        abortController.signal,
        options.singleTurnOverride ?? null,
      ] as const
      const stream = options.recoveryAction !== undefined
        ? await chatApi.sendMessageStream(...baseStreamArgs, options.visibleContent, options.recoveryAction)
        : options.visibleContent !== undefined
          ? await chatApi.sendMessageStream(...baseStreamArgs, options.visibleContent)
          : await chatApi.sendMessageStream(...baseStreamArgs)
      await parseSseStream(stream)
    } catch (e: unknown) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        // 请求阶段就被中止 — 将用户消息标记为已发送（可编辑）
        if (currentUserMessageId) {
          chatStore.updateMessage(currentUserMessageId, {
            status: 'success',
            errorMessage: undefined,
          })
        }
        chatStore.resetStreaming()
        return
      }
      clearStreamingTextBuffer()
      markCurrentTurnFailed(e instanceof Error ? e.message : '请求失败')
      chatStore.resetStreaming()
      a2uiStore.clearComponents()
    } finally {
      if (currentExecutionSeq === executionSeq) {
        isStreaming.value = false
        chatStore.isStreaming = false
        abortController = null
        currentTurnId = null
        currentUserMessageId = null
        currentTurnProjectId = null
        currentMemoryChangeStatusVisible = false
      }
    }
  }

  function prepareTurnMessages(
    turnId: string,
    action: ChatTurnAction,
    content: string,
    visibleContent?: string | null,
    attachments?: ChatAttachment[],
    singleTurnOverride?: SessionConfigOverride | null,
    explicitUserMessageId?: string | null,
    preserveUserMessageContent = false,
  ) {
    const messageContent = visibleContent ?? content
    const shouldAppendResumeMessage = action === 'RESUME'
      && !explicitUserMessageId
      && (messageContent.trim().length > 0 || (attachments?.length ?? 0) > 0)

    const existingUserMessage = explicitUserMessageId
      ? chatStore.messages.find(message => message.id === explicitUserMessageId)
      : findUserMessageByTurnId(turnId)
    const shouldPreserveExistingUserContent = !!explicitUserMessageId
      && (action === 'RESUME' || preserveUserMessageContent)

    if (action === 'RETRY' || action === 'RESTART' || (action === 'RESUME' && shouldPreserveExistingUserContent)) {
      chatStore.messages = chatStore.messages.filter(message =>
        !(message.role === 'assistant' && message.turnId === turnId),
      )
    }

    if (!existingUserMessage || action === 'SEND' || shouldAppendResumeMessage) {
      const userMessageId = crypto.randomUUID()
      chatStore.addMessage({
        id: userMessageId,
        turnId,
        role: 'user',
        content: messageContent,
        timestamp: Date.now(),
        status: 'pending',
        turnStatus: 'PENDING',
        attachments,
        singleTurnOverride: singleTurnOverride ?? undefined,
      })
      return userMessageId
    }

    chatStore.updateMessage(existingUserMessage.id, {
      content: shouldPreserveExistingUserContent ? existingUserMessage.content : (messageContent || existingUserMessage.content),
      attachments: attachments ?? existingUserMessage.attachments,
      singleTurnOverride: singleTurnOverride ?? existingUserMessage.singleTurnOverride,
      status: 'pending',
      turnStatus: 'PENDING',
      errorMessage: undefined,
    })
    return existingUserMessage.id
  }

  function resetStreamingState(content: string) {
    clearStreamingTextBuffer()
    isStreaming.value = true
    chatStore.isStreaming = true
    chatStore.streamingContent = ''
    error.value = null
    lastPrompt.value = content
    lastModelId.value = null
    lastTokenUsage.value = null
    reasoningEvents.value = []
    reasoningStatusText.value = null
    reasoningBuffer.value = ''
    isReasoningActive.value = false
    reasoningDurationMs.value = 0
    reasoningStartedAt = null
    streamingReactSteps.value = []
    streamingMedia.value = []
    streamingArtifactRefs.value = []
    pendingPermissionApprovals.value = new Map()
    pendingPermissionApprovalResolutions.value = new Map()
    a2uiStore.clearComponents()
  }

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
            continue
          }

          if (line.startsWith('data:')) {
            const data = line.slice(5)
            if (data.startsWith(' ')) {
              currentData += '\n' + data.slice(1)
            } else {
              if (currentData && currentEvent) {
                handleSseEvent(currentEvent, currentData)
              }
              currentData = data.trim()
            }
            continue
          }

          if (line === '' && currentData && currentEvent) {
            handleSseEvent(currentEvent, currentData)
            currentData = ''
            currentEvent = ''
          }
        }
      }

      if (currentData && currentEvent) {
        handleSseEvent(currentEvent, currentData)
      }
    } catch (e) {
      if (abortController?.signal.aborted) {
        // 用户主动停止 — 保留已接收的部分内容
        flushStreamingText()
        const partialContent = chatStore.streamingContent.trim()
        if (partialContent) {
          finalizeInterruptedTurn({
            id: buildTerminalAssistantId(currentTurnId ?? undefined, undefined, 'aborted'),
            turnId: currentTurnId ?? undefined,
            turnStatus: 'CANCELLED',
            content: partialContent,
          })
        } else {
          // 无内容（停止太早），仅更新用户消息状态
          if (currentUserMessageId) {
            chatStore.updateMessage(currentUserMessageId, {
              status: 'success',
              errorMessage: undefined,
            })
          }
          chatStore.resetStreaming()
        }
        return
      }
      // 流意外断开（后端 timeout / 网络中断 / 解析异常）— 已渲染的 partial content
      // 不清空，按 INTERRUPTED 落地保留已生成内容 + 错误标记，避免对话历史丢失体感。
      flushStreamingText()
      const partialContent = chatStore.streamingContent.trim()
      const errorMsg = e instanceof Error ? e.message : 'SSE 解析失败'
      if (partialContent) {
        finalizeInterruptedTurn({
          id: buildTerminalAssistantId(currentTurnId ?? undefined, undefined, 'error'),
          turnId: currentTurnId ?? undefined,
          turnStatus: 'FAILED',
          terminationReason: errorMsg,
          content: partialContent,
        })
      } else {
        markCurrentTurnFailed(errorMsg)
      }
      chatStore.resetStreaming()
      a2uiStore.clearComponents()
    } finally {
      reader.releaseLock()
    }
  }

  function handleSseEvent(eventType: string, data: string) {
    try {
      switch (eventType) {
        case SSE_EVENT_TYPES.TRACE_START: {
          const payload: { traceId?: string; turnId?: string; userEntryId?: string } = JSON.parse(data)
          if (payload.turnId) {
            currentTurnId = payload.turnId
          }
          if (payload.traceId && currentUserMessageId) {
            chatStore.updateMessage(currentUserMessageId, {
              traceId: payload.traceId,
              turnId: payload.turnId ?? currentTurnId ?? undefined,
            })
            a2uiStore.setCurrentTraceId(payload.traceId)
          }
          if (payload.userEntryId && currentUserMessageId) {
            chatStore.replaceMessageId(currentUserMessageId, payload.userEntryId)
            currentUserMessageId = payload.userEntryId
          }
          break
        }
        case SSE_EVENT_TYPES.REASONING: {
          // 同 SSE 事件名 "reasoning" 承载两类 payload：
          //   1. ReactAgentLoop 推送 ReAct 步骤元数据 → { event: ReasoningEvent }
          //   2. StreamingCallback 推送推理 token 增量 → { sessionId, turnId, delta }
          // 通过 delta / event 字段区分。
          const payload: {
            event?: ReasoningEvent
            delta?: string
            sessionId?: string
            turnId?: string
          } = JSON.parse(data)

          if (typeof payload.delta === 'string') {
            // 推理 token 增量：累计到 buffer 并维持 active 状态
            if (payload.delta.length === 0) {
              break
            }
            if (!isReasoningActive.value) {
              isReasoningActive.value = true
              reasoningStartedAt = Date.now()
            }
            reasoningBuffer.value += payload.delta
            break
          }

          if (payload.event) {
            const event = payload.event
            reasoningEvents.value.push(event)
            reasoningStatusText.value = mapReasoningStatus(event)
            const reactStep = buildReactStepFromEvent(event, streamingReactSteps.value.length)
            if (reactStep) {
              streamingReactSteps.value.push(reactStep)
            }
          }
          break
        }
        case SSE_EVENT_TYPES.TOKEN: {
          const event: SseTokenEvent = JSON.parse(data)
          queueStreamingText(event.content)
          break
        }
        case SSE_EVENT_TYPES.UI: {
          const event: { components: A2uiComponent[] } = JSON.parse(data)
          a2uiStore.updateComponents(event.components, { traceId: a2uiStore.currentTraceId })
          break
        }
        case SSE_EVENT_TYPES.MEDIA: {
          const event: SseMediaEvent = JSON.parse(data)
          streamingMedia.value.push(event)
          break
        }
        case SSE_EVENT_TYPES.ARTIFACT_REF: {
          const ref = normalizeArtifactRef(JSON.parse(data))
          // 后端按工具调用顺序推送，前端按 artifactId 去重防重复
          if (ref && !streamingArtifactRefs.value.some(x => x.artifactId === ref.artifactId)) {
            streamingArtifactRefs.value.push(ref)
          }
          break
        }
        case SSE_EVENT_TYPES.DONE: {
          const event: SseDoneEvent = JSON.parse(data)
          const turnId = event.turnId ?? currentTurnId ?? undefined

          if (event.sessionId && event.sessionId !== chatStore.activeSessionId) {
            chatStore.activeSessionId = event.sessionId
          }

          flushStreamingText()
          // DONE 触发：定格推理时长，关闭 active（折叠 ReasoningSection）
          if (reasoningStartedAt !== null) {
            reasoningDurationMs.value = Date.now() - reasoningStartedAt
          }
          isReasoningActive.value = false
          const finalContent = resolveDoneContent(event)
          const attachments = buildStreamingAttachments(event.contents, event.attachments)
          const finalReasoningContent = reasoningBuffer.value
          const finalReasoningDurationMs = reasoningDurationMs.value
          const shouldCheckMemoryChanges = shouldRefreshMemoryChanges(event, turnId)
          const finalArtifactRefs = mergeArtifactRefs(streamingArtifactRefs.value, event.artifactRefs)
          const recoverableTaskStatus = resolveRecoverableTaskStatus(event)
          const effectiveTurnStatus = event.turnStatus ?? recoverableTaskStatus ?? undefined
          const effectiveCompletionMode = event.completionMode ?? recoverableTaskStatus ?? undefined
          const memoryChangeStatus: MemoryChangeStatus | undefined = event.memoryChanges?.length
            ? 'settled'
            : (shouldCheckMemoryChanges && currentMemoryChangeStatusVisible ? 'checking' : undefined)
          if (effectiveTurnStatus === 'CANCELLED' && !finalContent && attachments.length === 0) {
            if (currentUserMessageId) {
              chatStore.updateMessage(currentUserMessageId, {
                status: 'success',
                traceId: event.traceId,
                turnId,
                turnStatus: 'CANCELLED',
                errorMessage: undefined,
              })
            }
            chatStore.resetStreaming()
            a2uiStore.clearComponents()
            break
          }
          const assistantMessage = {
            id: event.entryId,
            turnId,
            role: 'assistant' as const,
            content: finalContent,
            reasoningSummary: event.reasoningSummary,
            reasoningContent: finalReasoningContent.length > 0 ? finalReasoningContent : undefined,
            reasoningDurationMs: finalReasoningDurationMs > 0 ? finalReasoningDurationMs : undefined,
            reasoningEvents: reasoningEvents.value.length > 0 ? [...reasoningEvents.value] : undefined,
            a2uiComponents: event.a2uiComponents?.length
              ? [...event.a2uiComponents]
              : (a2uiStore.components.length > 0 ? [...a2uiStore.components] : undefined),
            timestamp: event.timestamp ?? Date.now(),
            traceId: event.traceId,
            completionMode: effectiveCompletionMode,
            resumedFromTraceId: event.resumedFromTraceId,
            turnStatus: effectiveTurnStatus,
            attachments: attachments.length > 0 ? attachments : undefined,
            artifactRefs: finalArtifactRefs.length > 0
              ? finalArtifactRefs
              : undefined,
            tokenUsage: event.tokenUsage,
            modelId: event.tokenUsage?.modelId,
            sources: event.sources,
            memoryChanges: event.memoryChanges,
            memoryChangeStatus,
            toolsSummary: event.toolsSummary,
            taskRecovery: event.taskRecovery,
            turnRecoveryContext: event.turnRecoveryContext,
            executionConstraints: event.executionConstraints,
            errorMessage: isInterruptedCompletion(event)
              ? event.terminationReason
              : undefined,
            reactSteps: event.reactSteps?.length
              ? event.reactSteps
              : (streamingReactSteps.value.length > 0 ? [...streamingReactSteps.value] : undefined),
            permissionApprovals: pendingPermissionApprovals.value.size > 0
              ? Object.fromEntries(pendingPermissionApprovals.value)
              : undefined,
            permissionApprovalResolutions: pendingPermissionApprovalResolutions.value.size > 0
              ? Object.fromEntries(pendingPermissionApprovalResolutions.value)
              : undefined,
          }

          chatStore.upsertMessage(assistantMessage)
          if (shouldCheckMemoryChanges) {
            const projectIdForMemoryRefresh = currentTurnProjectId ?? activeSessionProjectId.value
            scheduleMemoryChangeRefresh(assistantMessage.id, turnId, projectIdForMemoryRefresh)
          }

          if (event.tokenUsage) {
            lastTokenUsage.value = event.tokenUsage
            lastModelId.value = event.tokenUsage.modelId ?? null
          } else if (event.usage) {
            lastTokenUsage.value = {
              promptTokens: event.usage.inputTokens,
              completionTokens: event.usage.outputTokens,
              totalTokens: event.usage.totalTokens,
              modelId: lastModelId.value ?? 'unknown',
            }
          }

          if (currentUserMessageId) {
            chatStore.updateMessage(currentUserMessageId, {
              status: 'success',
              traceId: event.traceId,
              turnId,
              turnStatus: effectiveTurnStatus ?? 'SUCCESS',
              errorMessage: undefined,
            })
          }
          chatStore.resetStreaming()
          a2uiStore.clearComponents()
          break
        }
        case SSE_EVENT_TYPES.AGENT_SUSPENDED: {
          const event: SseAgentSuspendedEvent = JSON.parse(data)
          currentTurnId = event.turnId ?? currentTurnId
          const suspendReasonDetail = event.reasonDetail?.trim() || event.terminationReason?.trim()
          flushStreamingText()
          const suspendedContent = event.content?.trim()
            || buildInterruptedAssistantContent('SUSPENDED', suspendReasonDetail)

          finalizeInterruptedTurn({
            id: buildTerminalAssistantId(event.turnId, event.traceId, 'suspended'),
            turnId: event.turnId ?? currentTurnId ?? undefined,
            traceId: event.traceId,
            turnStatus: event.turnStatus ?? 'SUSPENDED',
            completionMode: 'SUSPENDED',
            terminationReason: suspendReasonDetail,
            suspendReasonType: event.reasonType,
            suspendReasonSourceId: event.reasonSourceId,
            taskRecovery: event.taskRecovery,
            executionConstraints: event.executionConstraints,
            content: suspendedContent,
            allowWithoutProgress: true,
          })

          // BrowserTakeover 挂起需要额外唤起前端弹窗引导用户去浏览器完成操作，
          // reasonSourceId 由后端 AgentOrchestrator.resolveSuspendReasonSourceId 返回
          // BrowserTakeover.sessionId()，与恢复接口匹配键一致。
          if (event.reasonType === 'BrowserTakeover' && event.reasonSourceId) {
            const resolvedTurnId = event.turnId ?? currentTurnId
            if (resolvedTurnId) {
              activeBrowserTakeover.value = {
                turnId: resolvedTurnId,
                sessionId: event.reasonSourceId,
                reason: suspendReasonDetail || '需要你在浏览器中完成操作',
                // 优先使用后端下发的配置值，缺省时回退到本地 300 秒默认值
                timeoutSeconds: event.timeoutSeconds ?? 300,
              }
            }
          }
          break
        }
        case SSE_EVENT_TYPES.ERROR: {
          const event: SseErrorEvent = JSON.parse(data)
          currentTurnId = event.turnId ?? currentTurnId

          flushStreamingText()
          if (!finalizeInterruptedTurn({
            id: buildTerminalAssistantId(event.turnId, event.traceId, 'error'),
            turnId: event.turnId ?? currentTurnId ?? undefined,
            traceId: event.traceId,
            turnStatus: event.turnStatus ?? 'FAILED',
            completionMode: undefined,
            terminationReason: event.message,
            content: buildInterruptedAssistantContent('FAILED', event.message),
          })) {
            markCurrentTurnFailed(event.message, event.traceId, event.turnStatus ?? 'FAILED')
            chatStore.resetStreaming()
            a2uiStore.clearComponents()
          }
          break
        }
        case SSE_EVENT_TYPES.HEARTBEAT:
          break
        case SSE_EVENT_TYPES.PERMISSION_APPROVAL_REQUEST: {
          const payload: PermissionApprovalRequest = JSON.parse(data)
          pendingPermissionApprovals.value.set(payload.requestId, payload)
          break
        }
        default:
          logger.warn('未知的 SSE 事件类型:', eventType)
      }
    } catch (e) {
      if (
        eventType === SSE_EVENT_TYPES.DONE
        || eventType === SSE_EVENT_TYPES.ERROR
        || eventType === SSE_EVENT_TYPES.AGENT_SUSPENDED
      ) {
        clearStreamingTextBuffer()
        markCurrentTurnFailed(e instanceof Error ? e.message : '事件解析失败')
        chatStore.resetStreaming()
        a2uiStore.clearComponents()
      }
      logger.error('SSE 事件解析失败:', eventType, data, e)
    }
  }

  function resolveDoneContent(event: SseDoneEvent) {
    const eventText = resolveDoneEventText(event)
    const streamedContent = chatStore.streamingContent.trim()

    if (event.contentRole === 'PROGRESS') {
      return streamedContent || eventText
    }

    if (!isInterruptedCompletion(event)) {
      return eventText || streamedContent
    }

    if (eventText) {
      if (streamedContent && !containsNormalizedText(eventText, streamedContent)) {
        const terminalNotice = extractTerminalNotice(eventText, event.terminationReason)
        return appendTerminalNotice(streamedContent, terminalNotice)
      }
      return eventText
    }

    if (streamedContent) {
      return appendTerminalNotice(
        streamedContent,
        buildInterruptedNotice(resolveInterruptedStatus(event), event.terminationReason),
      )
    }

    return ''
  }
  function resolveDoneEventText(event: SseDoneEvent) {
    if (event.contents && event.contents.length > 0) {
      const textParts = event.contents
        .filter(item => item.type === 'TEXT' && item.text)
        .map(item => item.text as string)
      return (textParts.join('\n') || event.content)?.trim() ?? ''
    }
    return event.content?.trim() ?? ''
  }

  function buildStreamingAttachments(
    contents?: SseDoneEvent['contents'],
    doneAttachments?: SseDoneEvent['attachments'],
  ) {
    const attachments: ChatAttachment[] = []

    for (const attachment of doneAttachments ?? []) {
      attachments.push({
        fileId: attachment.fileId,
        url: attachment.url ?? '',
        filename: attachment.filename,
        size: attachment.size ?? 0,
        type: attachment.type ?? 'application/octet-stream',
        isImage: attachment.isImage ?? attachment.type?.startsWith('image/') ?? false,
      })
    }

    for (const item of contents ?? []) {
      if (item.type === 'AUDIO' && item.url) {
        attachments.push({
          fileId: crypto.randomUUID(),
          url: item.url,
          filename: `audio-response.${item.mimeType?.split('/')[1] ?? 'mp3'}`,
          size: 0,
          type: item.mimeType ?? 'audio/mpeg',
          isImage: false,
        })
      }
    }

    for (const media of streamingMedia.value) {
      const mimeType = media.mimeType ?? 'application/octet-stream'
      attachments.push({
        fileId: crypto.randomUUID(),
        url: `data:${mimeType};base64,${media.data ?? ''}`,
        filename: `${media.field ?? 'media'}.${mimeType.split('/')[1] ?? 'bin'}`,
        size: Math.round((media.data?.length ?? 0) * 0.75),
        type: mimeType,
        isImage: mimeType.startsWith('image/'),
      })
    }

    return attachments
  }

  function finalizeInterruptedTurn(options: {
    id: string
    turnId?: string
    traceId?: string
    turnStatus: ChatTurnStatus
    completionMode?: 'DEGRADED' | 'SUSPENDED'
    terminationReason?: string
    suspendReasonType?: string
    suspendReasonSourceId?: string
    taskRecovery?: SseDoneEvent['taskRecovery']
    executionConstraints?: SseDoneEvent['executionConstraints']
    content: string
    allowWithoutProgress?: boolean
  }) {
    const hasVisibleProgress = chatStore.streamingContent.trim().length > 0
      || a2uiStore.components.length > 0
      || streamingMedia.value.length > 0

    if (!hasVisibleProgress && !options.allowWithoutProgress) {
      return false
    }

    // 中断也保留已收集的推理流，保证用户能看到模型「思考过程」
    if (reasoningStartedAt !== null && reasoningDurationMs.value === 0) {
      reasoningDurationMs.value = Date.now() - reasoningStartedAt
    }
    isReasoningActive.value = false

    chatStore.upsertMessage({
      id: options.id,
      turnId: options.turnId,
      role: 'assistant',
      content: options.content,
      timestamp: Date.now(),
      traceId: options.traceId,
      completionMode: options.completionMode,
      turnStatus: options.turnStatus,
      a2uiComponents: a2uiStore.components.length > 0 ? [...a2uiStore.components] : undefined,
      reasoningContent: reasoningBuffer.value.length > 0 ? reasoningBuffer.value : undefined,
      reasoningDurationMs: reasoningDurationMs.value > 0 ? reasoningDurationMs.value : undefined,
      reasoningEvents: reasoningEvents.value.length > 0 ? [...reasoningEvents.value] : undefined,
      reactSteps: streamingReactSteps.value.length > 0 ? [...streamingReactSteps.value] : undefined,
      attachments: (() => {
        const attachments = buildStreamingAttachments(undefined)
        return attachments.length > 0 ? attachments : undefined
      })(),
      permissionApprovals: pendingPermissionApprovals.value.size > 0
        ? Object.fromEntries(pendingPermissionApprovals.value)
        : undefined,
      permissionApprovalResolutions: pendingPermissionApprovalResolutions.value.size > 0
        ? Object.fromEntries(pendingPermissionApprovalResolutions.value)
        : undefined,
      errorMessage: options.terminationReason,
      suspendReasonType: options.suspendReasonType,
      suspendReasonSourceId: options.suspendReasonSourceId,
      taskRecovery: options.taskRecovery,
      executionConstraints: options.executionConstraints,
    })

    if (currentUserMessageId) {
      chatStore.updateMessage(currentUserMessageId, {
        status: 'success',
        errorMessage: undefined,
        traceId: options.traceId,
        turnId: options.turnId ?? currentTurnId ?? undefined,
        turnStatus: options.turnStatus,
      })
    }

    error.value = null
    isStreaming.value = false
    chatStore.resetStreaming()
    a2uiStore.clearComponents()
    return true
  }

  function buildInterruptedAssistantContent(
    status: 'FAILED' | 'DEGRADED' | 'SUSPENDED',
    reason?: string,
  ) {
    const visibleContent = chatStore.streamingContent.trim()
    const notice = buildInterruptedNotice(status, reason)
    if (!visibleContent) {
      return notice
    }
    return appendTerminalNotice(visibleContent, notice)
  }

  function buildInterruptedNotice(
    status: ChatTurnStatus | 'DEGRADED' | 'SUSPENDED' | 'FAILED' | 'CANCELLED',
    reason?: string,
  ) {
    const detail = humanizeTerminationReason(reason)

    if (status === 'SUSPENDED') {
      return [
        '我先停在这里等你补充。',
        `原因：${detail}`,
        '你直接回复就行，我会沿着刚才的进度继续处理。',
      ].join('\n')
    }

    if (status === 'FAILED') {
      return [
        '这轮处理被打断了。',
        `原因：${detail}`,
        '前面已经生成的内容我会保留着。你可以换个说法继续补充，或者让我重试这一轮。',
      ].join('\n')
    }

    if (status === 'CANCELLED') {
      return [
        '这轮已停止。',
        `原因：${detail}`,
        '前面已经生成的内容我会保留着。你可以直接继续补充。',
      ].join('\n')
    }

    return [
      '这轮我先停在这里。',
      `原因：${detail}`,
      '当前进度还在。你可以直接继续补充，或者让我重新开始这一轮。',
    ].join('\n')
  }

  function humanizeTerminationReason(reason?: string) {
    const detail = reason?.trim()
    if (!detail) {
      return '系统暂时没有返回更多细节。'
    }

    if (detail.includes('未满足结束协议') || detail.includes('拒绝提前结束')) {
      return '这轮任务看起来还没真正形成一个可确认的结果，所以我先停在这里。'
    }

    if (detail.includes('时间预算耗尽')) {
      return '这轮处理时间有点长，我先停在这里，避免一直卡住。'
    }

    if (detail.includes('步骤预算耗尽')) {
      return '这轮任务走的步骤有点多，我先停在这里，避免继续绕下去。'
    }

    if (detail.includes('连续 LLM 调用失败') || detail.includes('连续空响应')) {
      return '模型这边连续几次都没稳定返回结果，所以我先停下来。'
    }

    return detail
  }

  function appendTerminalNotice(content: string, notice: string) {
    const trimmedContent = content.trim()
    const trimmedNotice = notice.trim()
    if (!trimmedContent) {
      return trimmedNotice
    }
    if (!trimmedNotice || containsNormalizedText(trimmedContent, trimmedNotice)) {
      return trimmedContent
    }
    return `${trimmedContent}\n\n---\n${trimmedNotice}`
  }

  function extractTerminalNotice(content: string, terminationReason?: string) {
    const divider = '\n\n---\n'
    const dividerIndex = content.indexOf(divider)
    if (dividerIndex >= 0) {
      return content.slice(dividerIndex + divider.length).trim()
    }
    return buildInterruptedNotice('DEGRADED', terminationReason || content)
  }

  function containsNormalizedText(text: string, target: string) {
    const normalizedText = normalizeDisplayText(text)
    const normalizedTarget = normalizeDisplayText(target)
    return normalizedText.includes(normalizedTarget)
  }

  function normalizeDisplayText(content: string) {
    return content
      .replace(/\r/g, '')
      .replace(/\n/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()
      .toLowerCase()
  }

  function isInterruptedCompletion(
    state?: InterruptedCompletionState | null,
  ) {
    return state?.turnStatus === 'CANCELLED'
      || resolveRecoverableTaskStatus(state) !== null
  }

  function resolveInterruptedStatus(
    state?: InterruptedCompletionState | null,
  ): ChatTurnStatus | 'DEGRADED' | 'SUSPENDED' | 'FAILED' | 'CANCELLED' {
    const recoverableStatus = resolveRecoverableTaskStatus(state)
    const turnStatus = state?.turnStatus
    if (turnStatus === 'DEGRADED' || turnStatus === 'SUSPENDED'
      || turnStatus === 'FAILED' || turnStatus === 'CANCELLED') {
      return turnStatus
    }
    if (recoverableStatus) {
      return recoverableStatus
    }
    return 'DEGRADED'
  }

  function buildTerminalAssistantId(turnId?: string, traceId?: string, suffix = 'terminal') {
    if (traceId) {
      return traceId
    }
    if (turnId) {
      return `${suffix}:${turnId}`
    }
    return `${suffix}:${crypto.randomUUID()}`
  }

  function markCurrentTurnFailed(
    rawMessage: string,
    traceId?: string,
    turnStatus: ChatTurnStatus = 'FAILED',
  ) {
    let uiMessage: string
    if (rawMessage.includes('429')) {
      uiMessage = '请求过于频繁，请稍后再试。'
    } else if (rawMessage.includes('500') || rawMessage.includes('503') || rawMessage.includes('504')) {
      uiMessage = '服务暂时不可用，请稍后重试。'
    } else {
      uiMessage = rawMessage || '对话过程中发生未知错误'
    }

    error.value = uiMessage

    if (currentUserMessageId) {
      chatStore.updateMessage(currentUserMessageId, {
        status: 'error',
        errorMessage: uiMessage,
        traceId,
        turnId: currentTurnId ?? undefined,
        turnStatus,
      })
    }
  }

  function buildReactStepFromEvent(event: ReasoningEvent, index: number): ReactStepDto | null {
    const extra = event.extra ?? {}
    const stepIndex = readExtraNumber(extra, 'stepIndex', 'step_index') ?? readExtraNumber(extra, 'index') ?? index
    switch (event.type) {
      case 'PROGRESS':
        return { type: 'PROGRESS', index: stepIndex, content: event.description ?? event.title }
      case 'THOUGHT':
        return { type: 'THOUGHT', index: stepIndex, content: event.description ?? event.title }
      case 'TOOL_CALL':
        return {
          type: 'TOOL_CALL',
          index: stepIndex,
          toolId: readExtraString(extra, 'toolId', 'tool_id') ?? event.toolName ?? 'unknown',
          toolName: readExtraString(extra, 'toolName', 'tool_name') ?? event.toolName ?? undefined,
          callId: readExtraString(extra, 'callId', 'call_id') ?? undefined,
          inputSummary: readExtraString(extra, 'inputSummary', 'input_summary') ?? event.description ?? '',
          inputDetail: readExtraString(extra, 'inputDetail', 'input_detail') ?? undefined,
          latencyMs: readExtraNumber(extra, 'latencyMs', 'latency_ms') ?? 0,
          subjectLabel: readExtraString(extra, 'subjectLabel', 'subject_label') ?? undefined,
          subjectNames: readExtraStringArray(extra, 'subjectNames', 'subject_names') ?? undefined,
        }
      case 'OBSERVATION':
        return {
          type: 'OBSERVATION',
          index: stepIndex,
          toolId: readExtraString(extra, 'toolId', 'tool_id') ?? event.toolName ?? 'unknown',
          toolName: readExtraString(extra, 'toolName', 'tool_name') ?? event.toolName ?? undefined,
          callId: readExtraString(extra, 'callId', 'call_id') ?? undefined,
          success: readExtraBoolean(extra, 'success') ?? !event.title.includes('失败'),
          outputSummary: readExtraString(extra, 'outputSummary', 'output_summary') ?? event.description ?? '',
          tokensUsed: readExtraNumber(extra, 'tokensUsed', 'tokens_used') ?? 0,
          subjectLabel: readExtraString(extra, 'subjectLabel', 'subject_label') ?? undefined,
          subjectNames: readExtraStringArray(extra, 'subjectNames', 'subject_names') ?? undefined,
          generatedFilePath: readExtraString(extra, 'generatedFilePath', 'generated_file_path') ?? undefined,
          workingDirectory: readExtraString(extra, 'workingDirectory', 'working_directory') ?? undefined,
          outputDetail: readExtraString(extra, 'outputDetail', 'output_detail') ?? undefined,
          output: extra.output,
        }
      case 'ANSWER':
        return { type: 'ANSWER', index: stepIndex, content: event.description ?? event.title }
      case 'SUSPEND':
        return { type: 'SUSPEND', index: stepIndex, reason: event.description ?? event.title, suspendedAt: event.createdAt }
      case 'RESUME':
        return { type: 'RESUME', index: stepIndex, resumedAt: event.createdAt, suspendDurationMs: 0 }
      default:
        return null
    }
  }

  function readExtraString(extra: Record<string, any>, ...keys: string[]) {
    for (const key of keys) {
      const value = extra[key]
      if (typeof value === 'string' && value.trim()) {
        return value.trim()
      }
    }
    return null
  }

  function mergeArtifactRefs(
    ...groups: Array<Array<ArtifactRefPayload | Partial<ArtifactRefPayload>> | undefined>
  ): ArtifactRefPayload[] {
    const merged = new Map<string, ArtifactRefPayload>()
    for (const group of groups) {
      for (const raw of group ?? []) {
        const ref = normalizeArtifactRef(raw)
        if (ref && !merged.has(ref.artifactId)) {
          merged.set(ref.artifactId, ref)
        }
      }
    }
    return Array.from(merged.values())
  }

  function normalizeArtifactRef(raw: unknown): ArtifactRefPayload | null {
    if (!raw || typeof raw !== 'object') {
      return null
    }
    const ref = raw as Record<string, unknown>
    const artifactId = readArtifactRefText(ref, 'artifactId', 'artifact_id', 'id')
    if (!artifactId) {
      return null
    }
    const typeText = readArtifactRefText(ref, 'type')
    const mimeType = readArtifactRefText(
      ref,
      'mimeType',
      'mime_type',
      'contentType',
      'content_type',
      'mediaType',
      'media_type',
    ) ?? (typeText?.includes('/') ? typeText : undefined)
      ?? 'application/octet-stream'
    const kindText = readArtifactRefText(ref, 'kind') ?? (typeText && !typeText.includes('/') ? typeText : null)
    return {
      artifactId,
      fileName: readArtifactRefText(ref, 'fileName', 'file_name', 'filename', 'name') ?? artifactId,
      mimeType,
      kind: isImageArtifact(kindText, mimeType) ? 'IMAGE' : 'FILE',
      size: readArtifactRefSize(ref.size) ?? 0,
      downloadUrl: readArtifactRefText(ref, 'downloadUrl', 'download_url', 'url')
        ?? buildArtifactDownloadUrl(artifactId),
    }
  }

  function readArtifactRefText(ref: Record<string, unknown>, ...keys: string[]): string | null {
    for (const key of keys) {
      const value = ref[key]
      if (typeof value === 'string' && value.trim()) {
        return value.trim()
      }
    }
    return null
  }

  function readArtifactRefSize(value: unknown): number | null {
    if (typeof value === 'number' && Number.isFinite(value) && value >= 0) {
      return value
    }
    if (typeof value === 'string' && value.trim()) {
      const parsed = Number(value.trim())
      if (Number.isFinite(parsed) && parsed >= 0) {
        return parsed
      }
    }
    return null
  }

  function isImageArtifact(kindText: string | null, mimeType: string): boolean {
    if (kindText) {
      const normalized = kindText.trim().toUpperCase()
      if (normalized === 'IMAGE') {
        return true
      }
      if (normalized === 'FILE') {
        return false
      }
    }
    return mimeType.toLowerCase().startsWith('image/')
  }

  function readExtraNumber(extra: Record<string, any>, ...keys: string[]) {
    for (const key of keys) {
      const value = extra[key]
      if (typeof value === 'number' && Number.isFinite(value)) {
        return value
      }
    }
    return null
  }

  function readExtraBoolean(extra: Record<string, any>, key: string) {
    const value = extra[key]
    return typeof value === 'boolean' ? value : null
  }

  function readExtraStringArray(extra: Record<string, any>, ...keys: string[]) {
    for (const key of keys) {
      const value = extra[key]
      if (!Array.isArray(value)) continue
      const items = value.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
        .map(item => item.trim())
      if (items.length > 0) {
        return items
      }
    }
    return null
  }

  function mapReasoningStatus(event: ReasoningEvent) {
    switch (event.type) {
      case 'AGENT_START':
        return '加载上下文中'
      case 'PROGRESS':
        return event.description ?? event.title ?? '处理中'
      case 'THOUGHT':
        return '推理中'
      case 'TOOL_CALL':
        return event.toolName ? `正在调用工具：${event.toolName}` : '正在调用工具'
      case 'OBSERVATION':
        return event.toolName ? `工具 ${event.toolName} 调用完成` : '工具调用完成'
      case 'ANSWER':
        return '生成回复中'
      case 'SUSPEND':
        return '等待继续执行'
      case 'RESUME':
        return '继续执行中'
      case 'ANSWER_FINALIZED':
        return '本轮回答已完成'
      default:
        return '处理中'
    }
  }

  function findUserMessageByTurnId(turnId: string) {
    for (let index = chatStore.messages.length - 1; index >= 0; index -= 1) {
      const message = chatStore.messages[index]
      if (message.role === 'user' && message.turnId === turnId) {
        return message
      }
    }
    return null
  }

  function findLatestSuspendedTurn() {
    for (let index = chatStore.messages.length - 1; index >= 0; index -= 1) {
      const message = chatStore.messages[index]
      if (message.role !== 'assistant') {
        continue
      }
      if (
        message.turnStatus === 'SUSPENDED'
        || message.completionMode === 'SUSPENDED'
        || message.taskRecovery?.status === 'SUSPENDED'
      ) {
        return shouldAutoResumeSuspendedMessage(message) ? message : null
      }
      break
    }
    return null
  }

  function buildAutoResumeRecoveryAction(message: {
    taskRecovery?: SseDoneEvent['taskRecovery']
    toolsSummary?: ToolCallSummary[]
    artifactRefs?: ArtifactRefPayload[]
  }): ToolRecoveryAction | undefined {
    const recovery = message.taskRecovery
    const failedTool = findFirstFailedToolSummary(message.toolsSummary)
    const toolAction = failedTool ? buildToolSummaryRecoveryAction(failedTool) : undefined
    const artifactRefs = compactRecoveryArtifactRefs(
      recovery?.checkpoint?.artifactRefs,
      toolAction?.artifactRefs,
      message.artifactRefs,
    )
    if (!recovery) {
      return toolAction
        ? {
            ...toolAction,
            id: 'task-recovery-user-reply',
            mode: 'resume',
            ...(artifactRefs ? { artifactRefs } : {}),
          }
        : undefined
    }
    const checkpoint = recovery.checkpoint
    const awaitingUserReply = isUserReplyRecovery(recovery)
    const recoveryLabel = awaitingUserReply && recovery.actionLabel === '等待'
      ? '补充后继续'
      : recovery.actionLabel
    return {
      id: 'task-recovery-user-reply',
      label: recoveryLabel ?? toolAction?.label ?? '补充后继续',
      mode: 'resume',
      category: checkpoint?.failureCategory ?? toolAction?.category,
      toolId: checkpoint?.toolId ?? toolAction?.toolId,
      callId: checkpoint?.callId ?? toolAction?.callId,
      toolName: checkpoint?.toolName ?? toolAction?.toolName,
      executionKind: checkpoint?.executionKind ?? toolAction?.executionKind,
      action: checkpoint?.action ?? toolAction?.action,
      interrupted: checkpoint?.interrupted ?? toolAction?.interrupted,
      subjectLabel: checkpoint?.subjectLabel ?? toolAction?.subjectLabel,
      subjectNames: checkpoint?.subjectNames ?? toolAction?.subjectNames,
      inputSummary: checkpoint?.inputSummary ?? toolAction?.inputSummary,
      inputDetail: checkpoint?.inputDetail ?? toolAction?.inputDetail,
      outputSummary: checkpoint?.outputSummary ?? toolAction?.outputSummary,
      outputDetail: checkpoint?.outputDetail ?? toolAction?.outputDetail,
      workingDirectory: checkpoint?.workingDirectory ?? toolAction?.workingDirectory,
      generatedFilePath: checkpoint?.generatedFilePath ?? toolAction?.generatedFilePath,
      ...(artifactRefs ? { artifactRefs } : {}),
      missingCapabilities: checkpoint?.missingCapabilities ?? toolAction?.missingCapabilities,
      recoveryHint: recovery.detail ?? toolAction?.recoveryHint,
      nextActions: recovery.nextActions?.filter(Boolean).slice(0, 3) ?? toolAction?.nextActions,
    }
  }

  function findFirstFailedToolSummary(tools?: ToolCallSummary[]): ToolCallSummary | undefined {
    return tools?.find(tool => tool.status === 'FAILED' || tool.success === false)
  }

  function buildToolSummaryRecoveryAction(tool: ToolCallSummary): ToolRecoveryAction {
    const fallbackAction = buildToolRecoveryActions(tool.toolId, tool)
      .find(action => action.mode !== 'restart')
    const action = tool.recoveryActions?.find(item => item.mode !== 'restart')
      ?? tool.recoveryActions?.[0]
      ?? fallbackAction
    const failureText = [tool.outputSummary, tool.outputDetail].filter(Boolean).join(' ')
    const category = action?.category ?? tool.failureCategory ?? resolveToolFailureCategory(tool.toolId, failureText)
    const executionKind = action?.executionKind ?? tool.executionKind ?? resolveToolExecutionKind(tool.toolId)
    const artifactRefs = compactRecoveryArtifactRefs(action?.artifactRefs, tool.artifactRefs)
    return {
      id: action?.id ?? 'resume',
      label: action?.label ?? '补充后继续',
      description: action?.description,
      mode: 'resume',
      category,
      toolId: action?.toolId ?? tool.toolId,
      callId: action?.callId ?? tool.callId,
      toolName: action?.toolName ?? tool.toolName,
      executionKind,
      action: action?.action ?? tool.action ?? resolveToolAction(tool.toolId),
      interrupted: action?.interrupted ?? tool.interrupted,
      subjectLabel: action?.subjectLabel ?? tool.subjectLabel,
      subjectNames: action?.subjectNames ?? tool.subjectNames,
      inputSummary: action?.inputSummary ?? tool.inputSummary,
      inputDetail: action?.inputDetail ?? tool.inputDetail,
      outputSummary: action?.outputSummary ?? tool.outputSummary,
      outputDetail: action?.outputDetail ?? tool.outputDetail,
      workingDirectory: action?.workingDirectory ?? tool.workingDirectory,
      generatedFilePath: action?.generatedFilePath ?? tool.generatedFilePath,
      ...(artifactRefs ? { artifactRefs } : {}),
      missingCapabilities: action?.missingCapabilities ?? tool.missingCapabilities,
      recoveryHint: action?.recoveryHint ?? tool.recoveryHint ?? resolveToolRecoveryHint(tool.toolId, failureText),
      nextActions: action?.nextActions ?? buildToolRecoveryPlan({
        toolId: tool.toolId,
        failureCategory: category,
        executionKind,
        subjectNames: tool.subjectNames,
      }).slice(0, 3),
    }
  }

  function compactRecoveryArtifactRefs(
    ...groups: Array<Array<ArtifactRefPayload | Partial<ArtifactRefPayload>> | undefined | null>
  ): ArtifactRefPayload[] | undefined {
    const refs = mergeArtifactRefs(...groups.map(group => group ?? undefined)).slice(0, 8)
    return refs.length > 0 ? refs : undefined
  }

  function shouldAutoResumeSuspendedMessage(message: {
    taskRecovery?: SseDoneEvent['taskRecovery']
    suspendReasonType?: string
    suspendReasonSourceId?: string
    content?: string
    errorMessage?: string
  }) {
    const recovery = message.taskRecovery
    const resumeMode = recovery?.resumeMode
    if (resumeMode) {
      return isUserReplyRecovery(recovery)
    }

    const reasonSourceId = recovery?.reasonSourceId ?? message.suspendReasonSourceId
    if (reasonSourceId === '__await_user_input__') {
      return true
    }

    const reasonType = message.taskRecovery?.reasonType ?? message.suspendReasonType
    if (hasUserReplyResumeHint(message)) {
      return true
    }
    if (reasonType === 'ExternalDataWait') {
      return false
    }
    return false
  }

  function hasUserReplyResumeHint(message: {
    taskRecovery?: SseDoneEvent['taskRecovery']
    content?: string
    errorMessage?: string
  }) {
    const text = [
      message.taskRecovery?.title,
      message.taskRecovery?.detail,
      message.content,
      message.errorMessage,
    ]
      .filter(Boolean)
      .join(' ')
    return /(等待你补充|等你补充|请补充|还缺|直接回复|回复后.*继续|补充后.*继续|发送后.*继续|提供.*后.*继续)/.test(text)
  }

  function abort() {
    // 先通知后端真实取消（fire-and-forget），再断 HTTP 连接；
    // 单独断 HTTP 不够——后端可能在长 LLM 调用或工具执行中，
    // 必须驱动 CancellationToken.cancel 让 ReactAgentLoop 尽早退出。
    const sessionId = chatStore.activeSessionId
    if (sessionId) {
      void chatApi.cancelTurn(sessionId).catch(err => {
        logger.warn('通知后端取消当前轮失败，继续断 HTTP 连接:', err)
      })
    }
    abortController?.abort()
    // 不在这里 resetStreaming / clearStreamingTextBuffer —
    // 让 parseSseStream 的 catch 块检测到中止后正确收尾（保留部分内容）
    a2uiStore.clearComponents()
  }

  function resolvePermissionApproval(
    requestId: string,
    resolution: 'approved' | 'rejected' | 'expired',
    subjectType?: string,
  ) {
    const request = pendingPermissionApprovals.value.get(requestId)
    if (request && subjectType) {
      pendingPermissionApprovals.value.set(requestId, {
        ...request,
        recommendedSubjectType: subjectType,
      })
    }
    pendingPermissionApprovalResolutions.value.set(requestId, resolution)
  }

  /** 用户确认已在浏览器完成操作 → 通知后端恢复 Agent。失败时保留 modal 让用户重试。 */
  async function confirmBrowserTakeover() {
    const takeover = activeBrowserTakeover.value
    if (!takeover) return
    try {
      await browserTakeoverApi.resume(takeover.turnId, takeover.sessionId, false)
      activeBrowserTakeover.value = null
      browserTakeoverError.value = null
    } catch (err) {
      logger.error('浏览器接管恢复请求失败:', err)
      browserTakeoverError.value = err instanceof Error ? err.message : String(err)
      // 不清空 activeBrowserTakeover，让用户能在 modal 中重试
    }
  }

  /** 用户放弃本轮任务 → 通知后端以取消语义恢复 Agent。失败时保留 modal 让用户重试。 */
  async function cancelBrowserTakeover() {
    const takeover = activeBrowserTakeover.value
    if (!takeover) return
    try {
      await browserTakeoverApi.resume(takeover.turnId, takeover.sessionId, true)
      activeBrowserTakeover.value = null
      browserTakeoverError.value = null
    } catch (err) {
      logger.error('浏览器接管取消请求失败:', err)
      browserTakeoverError.value = err instanceof Error ? err.message : String(err)
      // 不清空 activeBrowserTakeover，让用户能在 modal 中重试
    }
  }

  function mapToRecord<K, V>(map: Map<K, V>): Record<string, V> {
    const result: Record<string, V> = {}
    map.forEach((value, key) => {
      result[String(key)] = value
    })
    return result
  }

  function scheduleMemoryChangeRefresh(messageId: string, turnId?: string, projectId?: string | null) {
    if (!turnId) return
    let resolved = false
    for (const [index, delay] of MEMORY_CHANGE_REFRESH_DELAYS_MS.entries()) {
      window.setTimeout(async () => {
        if (resolved) return
        try {
          const statusInfo = await memoryApi.getTurnMemoryChangesStatus(turnId, projectId)
          const changes = statusInfo.changes ?? []
          const message = chatStore.messages.find(item => item.id === messageId)
          if (!message) {
            resolved = true
            return
          }
          if (changes.length > 0 || statusInfo.status === 'SETTLED') {
            const merged = mergeMemoryChanges(message.memoryChanges ?? [], changes)
            chatStore.updateMessage(messageId, {
              memoryChanges: merged.length > 0 ? merged : undefined,
              memoryChangeStatus: merged.length > 0 ? 'settled' : undefined,
              memoryChangeReason: undefined,
            })
            resolved = true
            return
          }
          if (statusInfo.status === 'CHECKED_EMPTY') {
            if (message.memoryChangeStatus === 'checking') {
              chatStore.updateMessage(messageId, {
                memoryChangeStatus: 'checked-empty',
                memoryChangeReason: statusInfo.reason ?? undefined,
              })
              scheduleMemoryChangeEmptyClear(messageId)
            }
            resolved = true
            return
          }
          if (statusInfo.status === 'FAILED') {
            if (message.memoryChangeStatus === 'checking') {
              chatStore.updateMessage(messageId, {
                memoryChangeStatus: 'failed',
                memoryChangeReason: statusInfo.reason ?? undefined,
              })
            }
            resolved = true
            return
          }
          if (statusInfo.status === 'DISABLED') {
            if (message.memoryChangeStatus === 'checking') {
              chatStore.updateMessage(messageId, {
                memoryChangeStatus: 'disabled',
                memoryChangeReason: statusInfo.reason ?? undefined,
              })
            }
            resolved = true
            return
          }
          if (index === MEMORY_CHANGE_REFRESH_DELAYS_MS.length - 1
            && message.memoryChangeStatus === 'checking') {
            chatStore.updateMessage(messageId, {
              memoryChangeStatus: 'failed',
              memoryChangeReason: 'memory_status_timeout',
            })
            resolved = true
          }
        } catch (err) {
          logger.debug('刷新轮次记忆沉淀失败:', err)
          if (index === MEMORY_CHANGE_REFRESH_DELAYS_MS.length - 1) {
            const message = chatStore.messages.find(item => item.id === messageId)
            if (message?.memoryChangeStatus === 'checking') {
              chatStore.updateMessage(messageId, {
                memoryChangeStatus: 'failed',
                memoryChangeReason: 'memory_status_refresh_failed',
              })
            }
            resolved = true
          }
        }
      }, delay)
    }
  }

  function clearTransientMemoryChangeStatus(messageId: string) {
    const message = chatStore.messages.find(item => item.id === messageId)
    if (message?.memoryChangeStatus === 'checking' && !message.memoryChanges?.length) {
      chatStore.updateMessage(messageId, {
        memoryChangeStatus: undefined,
        memoryChangeReason: undefined,
      })
    }
  }

  function scheduleMemoryChangeEmptyClear(messageId: string) {
    window.setTimeout(() => {
      const message = chatStore.messages.find(item => item.id === messageId)
      if (message?.memoryChangeStatus === 'checked-empty' && !message.memoryChanges?.length) {
        chatStore.updateMessage(messageId, {
          memoryChangeStatus: undefined,
          memoryChangeReason: undefined,
        })
      }
    }, MEMORY_CHANGE_EMPTY_VISIBLE_MS)
  }

  function shouldRefreshMemoryChanges(event: SseDoneEvent, turnId?: string) {
    return !!turnId
      && !(event.memoryChanges?.length)
      && !isInterruptedCompletion(event)
      && event.contentRole !== 'PROGRESS'
  }

  function shouldSurfaceMemoryChangeStatus(content: string) {
    const text = content.trim().toLowerCase()
    if (!text) return false
    return MEMORY_STATUS_EXPLICIT_PATTERN.test(text)
      || MEMORY_STATUS_PROFILE_PATTERN.test(text)
      || isLikelyReusableContext(text)
  }

  function isLikelyReusableContext(text: string) {
    return text.length >= 16 && MEMORY_STATUS_CONTEXT_PATTERN.test(text)
  }

  function mergeMemoryChanges(existing: SourceSummary[], incoming: SourceSummary[]) {
    const result = [...existing]
    const seen = new Set(existing.map(item => item.id))
    for (const item of incoming) {
      if (seen.has(item.id)) continue
      seen.add(item.id)
      result.push(item)
    }
    return result
  }

  function normalizeProjectId(value: unknown) {
    if (Array.isArray(value)) {
      return normalizeProjectId(value[0])
    }
    return typeof value === 'string' && value.trim() ? value.trim() : null
  }

  return {
    sendMessage,
    executeTurn,
    isStreaming,
    error,
    abort,
    lastPrompt,
    lastModelId,
    lastTokenUsage,
    reasoningEvents,
    reasoningStatusText,
    reasoningBuffer,
    isReasoningActive,
    reasoningDurationMs,
    streamingReactSteps,
    streamingMedia,
    streamingArtifactRefs,
    streamingA2uiComponents: a2uiStore.components,
    pendingPermissionApprovals: computed(() => mapToRecord(pendingPermissionApprovals.value)),
    pendingPermissionApprovalResolutions: computed(() => mapToRecord(pendingPermissionApprovalResolutions.value)),
    resolvePermissionApproval,
    activeBrowserTakeover,
    browserTakeoverError,
    confirmBrowserTakeover,
    cancelBrowserTakeover,
  }
}
