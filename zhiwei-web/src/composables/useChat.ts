import { computed, ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useA2uiStore } from '@/stores/a2ui'
import { chatApi } from '@/api/client'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'
import { logger } from '@/utils/logger'
import type {
  A2uiComponent,
  ChatAttachment,
  ChatTurnAction,
  ChatTurnStatus,
  ReasoningEvent,
  ReactStepDto,
  SseAgentSuspendedEvent,
  SseDoneEvent,
  SseErrorEvent,
  SseInteractionEvent,
  SseMediaEvent,
  SseTokenEvent,
  TokenUsage,
  PermissionApprovalRequest,
  SessionConfig,
} from '@/types'

type ExecuteTurnOptions = {
  content?: string
  attachmentIds?: string[]
  attachments?: ChatAttachment[]
  sessionConfig?: SessionConfig
  restoreSessionConfig?: SessionConfig
  userMessageId?: string | null
}

const TOKEN_FLUSH_INTERVAL_MS = 24
const TOKEN_FLUSH_CHAR_THRESHOLD = 96

export function useChat() {
  const chatStore = useChatStore()
  const a2uiStore = useA2uiStore()

  const isStreaming = ref(false)
  const error = ref<string | null>(null)
  const lastPrompt = ref<string | null>(null)
  const lastModelId = ref<string | null>(null)
  const lastTokenUsage = ref<TokenUsage | null>(null)
  const reasoningEvents = ref<ReasoningEvent[]>([])
  const reasoningStatusText = ref<string | null>(null)
  const streamingReactSteps = ref<ReactStepDto[]>([])
  const streamingMedia = ref<SseMediaEvent[]>([])
  const pendingPermissionApprovals = ref<Map<string, PermissionApprovalRequest>>(new Map())
  const pendingPermissionApprovalResolutions = ref<Map<string, 'approved' | 'rejected' | 'expired'>>(new Map())
  const activeInteraction = ref<SseInteractionEvent | null>(null)
  const interactionSubmitting = ref(false)
  const interactionError = ref<string | null>(null)

  let abortController: AbortController | null = null
  let currentTurnId: string | null = null
  let currentUserMessageId: string | null = null
  let currentExecutionSeq = 0
  let pendingStreamingText = ''
  let pendingStreamingFlushTimer: ReturnType<typeof setTimeout> | null = null

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
    sessionConfig?: SessionConfig,
    restoreSessionConfig?: SessionConfig,
  ) {
    if (activeInteraction.value) {
      if ((attachmentIds?.length ?? 0) > 0 || (attachments?.length ?? 0) > 0) {
        error.value = '当前正在等待一条文本补充，请先直接回复文本内容'
        return
      }

      const reply = content.trim()
      if (!reply) {
        return
      }

      const interactionReplyId = crypto.randomUUID()
      chatStore.addMessage({
        id: interactionReplyId,
        turnId: currentTurnId ?? undefined,
        role: 'user',
        content: reply,
        timestamp: Date.now(),
        status: 'pending',
      })

      await submitInteraction(reply)

      if (activeInteraction.value === null && !interactionError.value) {
        chatStore.updateMessage(interactionReplyId, {
          status: 'success',
          errorMessage: undefined,
        })
      } else {
        chatStore.updateMessage(interactionReplyId, {
          status: 'error',
          errorMessage: interactionError.value ?? '补充信息提交失败',
        })
      }
      return
    }

    const suspendedTurn = findLatestSuspendedTurn()
    if (suspendedTurn?.turnId) {
      await executeTurn(suspendedTurn.turnId, 'RESUME', {
        content,
        attachmentIds,
        attachments,
        sessionConfig,
        restoreSessionConfig,
      })
      return
    }

    const turnId = crypto.randomUUID()
    await executeTurn(turnId, 'SEND', {
      content,
      attachmentIds,
      attachments,
      sessionConfig,
      restoreSessionConfig,
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
    const restoreSessionConfig = options.restoreSessionConfig

    if (action === 'SEND' && !hasContent && !hasAttachments) {
      return
    }

    if (!chatStore.activeSessionId) {
      error.value = '会话未创建，请先打开新对话'
      return
    }

    if (options.sessionConfig) {
      try {
        await chatApi.updateSessionConfig(chatStore.activeSessionId, options.sessionConfig)
      } catch (e) {
        logger.warn('更新会话配置失败，将继续发送消息', e)
      }
    }

    const userMessageId = prepareTurnMessages(turnId, action, content, options.attachments, options.userMessageId)
    currentTurnId = turnId
    currentUserMessageId = userMessageId

    resetStreamingState(content)
    abortController = new AbortController()

    try {
      const stream = await chatApi.sendMessageStream(
        content,
        chatStore.activeSessionId ?? undefined,
        options.attachmentIds,
        turnId,
        action,
        abortController.signal,
      )
      await parseSseStream(stream)
    } catch (e: unknown) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        return
      }
      clearStreamingTextBuffer()
      markCurrentTurnFailed(e instanceof Error ? e.message : '请求失败')
      activeInteraction.value = null
      interactionSubmitting.value = false
      interactionError.value = null
      chatStore.resetStreaming()
      a2uiStore.clearComponents()
    } finally {
      if (restoreSessionConfig && chatStore.activeSessionId) {
        try {
          await chatApi.updateSessionConfig(chatStore.activeSessionId, restoreSessionConfig)
        } catch (restoreError) {
          logger.warn('恢复会话配置失败', restoreError)
        }
      }
      if (currentExecutionSeq === executionSeq) {
        isStreaming.value = false
        chatStore.isStreaming = false
        abortController = null
        currentTurnId = null
        currentUserMessageId = null
      }
    }
  }

  function prepareTurnMessages(
    turnId: string,
    action: ChatTurnAction,
    content: string,
    attachments?: ChatAttachment[],
    explicitUserMessageId?: string | null,
  ) {
    const shouldAppendResumeMessage = action === 'RESUME'
      && !explicitUserMessageId
      && (content.trim().length > 0 || (attachments?.length ?? 0) > 0)

    const existingUserMessage = explicitUserMessageId
      ? chatStore.messages.find(message => message.id === explicitUserMessageId)
      : findUserMessageByTurnId(turnId)

    if (action === 'RETRY' || action === 'RESTART') {
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
        content,
        timestamp: Date.now(),
        status: 'pending',
        turnStatus: 'PENDING',
        attachments,
      })
      return userMessageId
    }

    chatStore.updateMessage(existingUserMessage.id, {
      content: content || existingUserMessage.content,
      attachments: attachments ?? existingUserMessage.attachments,
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
    streamingReactSteps.value = []
    streamingMedia.value = []
    pendingPermissionApprovals.value = new Map()
    pendingPermissionApprovalResolutions.value = new Map()
    activeInteraction.value = null
    interactionSubmitting.value = false
    interactionError.value = null
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
      clearStreamingTextBuffer()
      markCurrentTurnFailed(e instanceof Error ? e.message : 'SSE 解析失败')
      activeInteraction.value = null
      interactionSubmitting.value = false
      interactionError.value = null
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
          const payload: { event: ReasoningEvent } = JSON.parse(data)
          const event = payload.event
          reasoningEvents.value.push(event)
          reasoningStatusText.value = mapReasoningStatus(event)
          const reactStep = buildReactStepFromEvent(event, streamingReactSteps.value.length)
          if (reactStep) {
            streamingReactSteps.value.push(reactStep)
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
        case SSE_EVENT_TYPES.DONE: {
          const event: SseDoneEvent = JSON.parse(data)
          const turnId = event.turnId ?? currentTurnId ?? undefined

          if (event.sessionId && event.sessionId !== chatStore.activeSessionId) {
            chatStore.activeSessionId = event.sessionId
          }

          flushStreamingText()
          const finalContent = resolveDoneContent(event)
          const attachments = buildStreamingAttachments(event.contents)
          const assistantMessage = {
            id: event.entryId,
            turnId,
            role: 'assistant' as const,
            content: finalContent,
            reasoningSummary: event.reasoningSummary,
            reasoningEvents: reasoningEvents.value.length > 0 ? [...reasoningEvents.value] : undefined,
            a2uiComponents: event.a2uiComponents?.length
              ? [...event.a2uiComponents]
              : (a2uiStore.components.length > 0 ? [...a2uiStore.components] : undefined),
            timestamp: event.timestamp ?? Date.now(),
            traceId: event.traceId,
            completionMode: event.completionMode,
            resumedFromTraceId: event.resumedFromTraceId,
            turnStatus: event.turnStatus,
            attachments: attachments.length > 0 ? attachments : undefined,
            tokenUsage: event.tokenUsage,
            modelId: event.tokenUsage?.modelId,
            sources: event.sources,
            toolsSummary: event.toolsSummary,
            errorMessage: isInterruptedCompletion(event.turnStatus, event.completionMode)
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
              turnStatus: event.turnStatus ?? 'SUCCESS',
              errorMessage: undefined,
            })
          }

          activeInteraction.value = null
          interactionSubmitting.value = false
          interactionError.value = null
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
            content: suspendedContent,
            allowWithoutProgress: true,
          })
          activeInteraction.value = null
          interactionSubmitting.value = false
          interactionError.value = null
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
            activeInteraction.value = null
            interactionSubmitting.value = false
            interactionError.value = null
            chatStore.resetStreaming()
            a2uiStore.clearComponents()
          }
          activeInteraction.value = null
          interactionSubmitting.value = false
          interactionError.value = null
          break
        }
        case SSE_EVENT_TYPES.HEARTBEAT:
          break
        case SSE_EVENT_TYPES.PERMISSION_APPROVAL_REQUEST: {
          const payload: PermissionApprovalRequest = JSON.parse(data)
          pendingPermissionApprovals.value.set(payload.requestId, payload)
          break
        }
        case SSE_EVENT_TYPES.INTERACTION: {
          const payload: SseInteractionEvent = JSON.parse(data)
          void handleInteractionRequest(payload)
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
        activeInteraction.value = null
        interactionSubmitting.value = false
        interactionError.value = null
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

    if (!isInterruptedCompletion(event.turnStatus, event.completionMode)) {
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
        buildInterruptedNotice(resolveInterruptedStatus(event.turnStatus, event.completionMode), event.terminationReason),
      )
    }

    return ''
  }

  async function handleInteractionRequest(event: SseInteractionEvent) {
    if (event.type === 'NOTIFY') {
      return
    }

    activeInteraction.value = {
      ...event,
      options: event.options ?? undefined,
    }
    interactionSubmitting.value = false
    interactionError.value = null

    const promptContent = event.message?.trim()
    if (!promptContent) {
      return
    }

    chatStore.upsertMessage({
      id: `interaction-${event.interactionId}`,
      turnId: currentTurnId ?? undefined,
      role: 'assistant',
      content: promptContent,
      timestamp: Date.now(),
      status: 'success',
    })
  }

  async function resolveInteractionResponse(payload: {
    value?: string | null
    confirmed: boolean
    timedOut?: boolean
  }) {
    const interaction = activeInteraction.value
    if (!interaction) {
      return
    }

    interactionSubmitting.value = true
    interactionError.value = null

    try {
      await chatApi.respondInteraction(interaction.interactionId, {
        type: interaction.type,
        value: payload.value ?? null,
        confirmed: payload.confirmed,
        timedOut: payload.timedOut ?? false,
      })
      activeInteraction.value = null
    } catch (submitError) {
      logger.error('回传交互失败:', submitError)
      interactionError.value = submitError instanceof Error
        ? submitError.message
        : '交互回传失败，请重试'
    } finally {
      interactionSubmitting.value = false
    }
  }

  async function submitInteraction(value?: string) {
    const interaction = activeInteraction.value
    if (!interaction || interactionSubmitting.value) {
      return
    }

    if (interaction.type === 'CONFIRM') {
      const normalizedValue = value?.trim() ?? ''
      const confirmed = resolveConfirmationReply(normalizedValue)
      if (confirmed === null && normalizedValue) {
        interactionError.value = '请直接回复“继续”“确认”或“取消”“停止”这类明确态度'
        return
      }
      await resolveInteractionResponse({
        value: normalizedValue || null,
        confirmed: confirmed ?? true,
        timedOut: false,
      })
      return
    }

    const normalizedValue = value?.trim() ?? ''
    if (!normalizedValue) {
      interactionError.value = interaction.type === 'CHOOSE'
        ? '请选择或输入一个可用选项'
        : '请输入需要补充的内容'
      return
    }

    await resolveInteractionResponse({
      value: normalizedValue,
      confirmed: true,
      timedOut: false,
    })
  }

  async function cancelInteraction() {
    if (!activeInteraction.value || interactionSubmitting.value) {
      return
    }

    await resolveInteractionResponse({
      confirmed: false,
      timedOut: true,
    })
  }

  function resolveConfirmationReply(value: string) {
    if (!value) {
      return null
    }

    const normalized = value.trim().toLowerCase()
    if (!normalized) {
      return null
    }

    const positiveMarkers = ['继续', '确认', '同意', '允许', '是', '好的', 'ok', 'yes', 'y']
    if (positiveMarkers.some(marker => normalized.includes(marker))) {
      return true
    }

    const negativeMarkers = ['取消', '停止', '不要', '不同意', '拒绝', '否', '不用', 'no', 'n']
    if (negativeMarkers.some(marker => normalized.includes(marker))) {
      return false
    }

    return null
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

  function buildStreamingAttachments(contents?: SseDoneEvent['contents']) {
    const attachments: ChatAttachment[] = []

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
    content: string
    allowWithoutProgress?: boolean
  }) {
    const hasVisibleProgress = chatStore.streamingContent.trim().length > 0
      || a2uiStore.components.length > 0
      || streamingMedia.value.length > 0

    if (!hasVisibleProgress && !options.allowWithoutProgress) {
      return false
    }

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
    status: ChatTurnStatus | 'DEGRADED' | 'SUSPENDED' | 'FAILED',
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
    turnStatus?: ChatTurnStatus,
    completionMode?: SseDoneEvent['completionMode'],
  ) {
    return turnStatus === 'DEGRADED'
      || turnStatus === 'SUSPENDED'
      || completionMode === 'DEGRADED'
      || completionMode === 'SUSPENDED'
  }

  function resolveInterruptedStatus(
    turnStatus?: ChatTurnStatus,
    completionMode?: SseDoneEvent['completionMode'],
  ): ChatTurnStatus | 'DEGRADED' | 'SUSPENDED' | 'FAILED' {
    if (turnStatus === 'DEGRADED' || turnStatus === 'SUSPENDED' || turnStatus === 'FAILED') {
      return turnStatus
    }
    if (completionMode === 'SUSPENDED') {
      return 'SUSPENDED'
    }
    if (completionMode === 'DEGRADED') {
      return 'DEGRADED'
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
    switch (event.type) {
      case 'PROGRESS':
        return { type: 'PROGRESS', index, content: event.description ?? event.title }
      case 'THOUGHT':
        return { type: 'THOUGHT', index, content: event.description ?? event.title }
      case 'TOOL_CALL':
        return {
          type: 'TOOL_CALL',
          index,
          toolId: (event.extra?.toolId as string) ?? event.toolName ?? 'unknown',
          toolName: event.toolName ?? undefined,
          inputSummary: event.description ?? '',
          latencyMs: 0,
        }
      case 'OBSERVATION':
        return {
          type: 'OBSERVATION',
          index,
          toolId: (event.extra?.toolId as string) ?? event.toolName ?? 'unknown',
          toolName: event.toolName ?? undefined,
          success: !event.title.includes('失败'),
          outputSummary: event.description ?? '',
          tokensUsed: 0,
        }
      case 'ANSWER':
        return { type: 'ANSWER', index, content: event.description ?? event.title }
      case 'SUSPEND':
        return { type: 'SUSPEND', index, reason: event.description ?? event.title, suspendedAt: event.createdAt }
      case 'RESUME':
        return { type: 'RESUME', index, resumedAt: event.createdAt, suspendDurationMs: 0 }
      default:
        return null
    }
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
      if (message.turnStatus === 'SUSPENDED' || message.completionMode === 'SUSPENDED') {
        return message
      }
      break
    }
    return null
  }

  function abort() {
    abortController?.abort()
    clearStreamingTextBuffer()
    activeInteraction.value = null
    interactionSubmitting.value = false
    interactionError.value = null
    chatStore.resetStreaming()
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

  function mapToRecord<K, V>(map: Map<K, V>): Record<string, V> {
    const result: Record<string, V> = {}
    map.forEach((value, key) => {
      result[String(key)] = value
    })
    return result
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
    streamingReactSteps,
    streamingMedia,
    streamingA2uiComponents: a2uiStore.components,
    activeInteraction,
    interactionSubmitting,
    interactionError,
    submitInteraction,
    cancelInteraction,
    pendingPermissionApprovals: computed(() => mapToRecord(pendingPermissionApprovals.value)),
    pendingPermissionApprovalResolutions: computed(() => mapToRecord(pendingPermissionApprovalResolutions.value)),
    resolvePermissionApproval,
  }
}
