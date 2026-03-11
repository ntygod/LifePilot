import { ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useA2uiStore } from '@/stores/a2ui'
import { chatApi } from '@/api/client'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'
import type {
  SseTokenEvent,
  SseDoneEvent,
  SseErrorEvent,
  SseMediaEvent,
  A2uiComponent,
  TokenUsage,
  ReasoningEvent,
  ChatAttachment,
} from '@/types'

/**
 * 对话 composable，封装 SSE 流式请求和消息管理。
 *
 * 核心流程：
 * 1. fetch POST /api/chat/messages/stream
 * 2. 通过 ReadableStream 逐行读取 SSE 事件
 * 3. token 事件 → chatStore.streamingContent 增量拼接
   * 4. ui 事件 → a2uiStore.updateComponents()
   * 5. done 事件 → 完整消息存入 chatStore.messages，并记录 Token 统计 / 模型等调试信息
   * 6. error 事件 → 设置 error ref
 */
export function useChat() {
  const chatStore = useChatStore()
  const a2uiStore = useA2uiStore()
  const isStreaming = ref(false)
  const error = ref<string | null>(null)
  // 最近一轮对话的调试 / 统计信息
  const lastPrompt = ref<string | null>(null)
  const lastModelId = ref<string | null>(null)
  const lastTokenUsage = ref<TokenUsage | null>(null)
  // 当前轮推理事件流与状态文案
  const reasoningEvents = ref<ReasoningEvent[]>([])
  const reasoningStatusText = ref<string | null>(null)
  // 当前轮流式媒体数据（截图等），DONE 事件时合并到消息附件
  const streamingMedia = ref<SseMediaEvent[]>([])
  let abortController: AbortController | null = null
  // 当前这轮请求对应的用户消息 ID，用于在错误 / 完成时回写状态
  let currentUserMessageId: string | null = null

  /** 发送消息（流式），支持可选附件 ID 列表与会话配置（模型/知识库等）。 */
  async function sendMessage(
    content: string,
    attachmentIds?: string[],
    attachments?: ChatAttachment[],
    sessionConfig?: {
      modelId?: string
      temperature?: number
      maxTokens?: number
      knowledgeBaseIds?: string[]
    }
  ) {
    if (!content.trim()) return

    // 会话已在打开新对话时预创建，此处 activeSessionId 必定非空
    if (!chatStore.activeSessionId) {
      error.value = '会话未创建，请先打开新对话'
      return
    }

    // 若本轮携带会话配置，先写回后端（确保首条消息也能按配置检索知识库/路由模型）
    if (chatStore.activeSessionId && sessionConfig) {
      try {
        await chatApi.updateSessionConfig(chatStore.activeSessionId, sessionConfig)
      } catch (e) {
        // 配置写回失败不阻塞对话主流程，但提示用户配置可能未生效
        console.warn('更新会话配置失败，将继续发送消息:', e)
      }
    }

    // 添加用户消息到列表
    const userMessageId = crypto.randomUUID()
    chatStore.addMessage({
      id: userMessageId,
      role: 'user',
      content,
      timestamp: Date.now(),
      status: 'pending',
      attachments
    })
    currentUserMessageId = userMessageId

    // 重置状态
    isStreaming.value = true
    chatStore.isStreaming = true
    chatStore.streamingContent = ''
    error.value = null
    // 清空上一轮统计信息
    lastPrompt.value = null
    lastModelId.value = null
    lastTokenUsage.value = null
    reasoningEvents.value = []
    reasoningStatusText.value = null
    streamingMedia.value = []
    a2uiStore.clearComponents()
    abortController = new AbortController()

    try {
      // 记录本轮基础 Prompt 摘要（当前仅记录用户输入，后续可由后端返回整合后的 Prompt）
      lastPrompt.value = content

      const stream = await chatApi.sendMessageStream(
        content,
        chatStore.activeSessionId ?? undefined,
        attachmentIds,
        abortController.signal
      )
      await parseSseStream(stream)
    } catch (e: unknown) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        // 用户主动取消，不更新消息状态
        return
      }
      // 网络 / HTTP 级错误，视为本条消息发送失败
      const message = e instanceof Error ? e.message : '请求失败'
      // 网络 / HTTP 级错误统一归为"网络异常"
      error.value = `网络异常：${message}`
      if (currentUserMessageId) {
        chatStore.updateMessage(currentUserMessageId, {
          status: 'error',
          errorMessage: message
        })
      }
      // 确保流式状态被重置
      chatStore.resetStreaming()
      a2uiStore.clearComponents()
    } finally {
      isStreaming.value = false
      chatStore.isStreaming = false
      abortController = null
      currentUserMessageId = null
    }
  }

  /** 解析 SSE 流 */
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
        // 保留最后一行（可能不完整）
        buffer = lines.pop() ?? ''

        for (const line of lines) {
          if (line.startsWith('event:')) {
            // 如果之前有未处理的数据，先处理
            if (currentData && currentEvent) {
              handleSseEvent(currentEvent, currentData)
              currentData = ''
            }
            currentEvent = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            const data = line.slice(5)
            // 支持多行数据：如果 data 行以空格开头，表示是上一行的续行
            if (data.startsWith(' ')) {
              currentData += '\n' + data.slice(1)
            } else {
              // 如果之前有未处理的数据，先处理
              if (currentData && currentEvent) {
                handleSseEvent(currentEvent, currentData)
              }
              currentData = data.trim()
            }
          } else if (line === '') {
            // 空行表示事件结束，处理当前事件
            if (currentData && currentEvent) {
              handleSseEvent(currentEvent, currentData)
              currentData = ''
              currentEvent = ''
            }
          }
        }
      }
      // 处理最后剩余的数据
      if (currentData && currentEvent) {
        handleSseEvent(currentEvent, currentData)
      }
    } catch (e) {
      // SSE 解析错误，更新用户消息状态
      const message = e instanceof Error ? e.message : 'SSE 解析失败'
      console.error('SSE 解析错误:', e)
      error.value = `流式响应解析失败：${message}`
      if (currentUserMessageId) {
        chatStore.updateMessage(currentUserMessageId, {
          status: 'error',
          errorMessage: message
        })
      }
      chatStore.resetStreaming()
      a2uiStore.clearComponents()
    } finally {
      reader.releaseLock()
    }
  }

  /** 处理单个 SSE 事件 */
  function handleSseEvent(eventType: string, data: string) {
    try {
      switch (eventType) {
        case SSE_EVENT_TYPES.TRACE_START: {
          const payload: { sessionId?: string; turnId?: string; traceId?: string; timestamp?: number; userMessageId?: string } = JSON.parse(data)
          if (payload.traceId && currentUserMessageId) {
            chatStore.updateMessage(currentUserMessageId, { traceId: payload.traceId })
            a2uiStore.setCurrentTraceId(payload.traceId)
          }
          // 用后端返回的 userMessageId 替换前端临时 ID，确保前后端 ID 一致
          if (payload.userMessageId && currentUserMessageId) {
            chatStore.replaceMessageId(currentUserMessageId, payload.userMessageId)
            currentUserMessageId = payload.userMessageId
          }
          break
        }
        case SSE_EVENT_TYPES.REASONING: {
          const payload: { sessionId?: string; turnId?: string; event: ReasoningEvent } = JSON.parse(data)
          const ev = payload.event
          reasoningEvents.value.push(ev)
          reasoningStatusText.value = mapReasoningStatus(ev)
          break
        }
        case SSE_EVENT_TYPES.TOKEN: {
          const event: SseTokenEvent = JSON.parse(data)
          chatStore.streamingContent += event.content
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
          // 后端同步写入后返回 messageId，不再需要前端兜底生成
          const messageId = event.messageId
          // 同步会话ID：如果后端返回了 sessionId，更新 activeSessionId
          if (event.sessionId && event.sessionId !== chatStore.activeSessionId) {
            chatStore.activeSessionId = event.sessionId
          }

          // 解析多模态 contents 数组（TEXT/AUDIO）
          let finalContent: string
          const extraAttachments: ChatAttachment[] = []

          if (event.contents && event.contents.length > 0) {
            const textParts: string[] = []
            for (const item of event.contents) {
              if (item.type === 'TEXT' && item.text) {
                textParts.push(item.text)
              } else if (item.type === 'AUDIO' && item.url) {
                extraAttachments.push({
                  fileId: crypto.randomUUID(),
                  url: item.url,
                  filename: 'audio-response.' + (item.mimeType?.split('/')[1] ?? 'mp3'),
                  size: 0,
                  type: item.mimeType ?? 'audio/mpeg',
                  isImage: false
                })
              }
            }
            // 若 contents 中没有 TEXT，则回退到 event.content 或 streamingContent
            finalContent = (textParts.join('\n') || event.content) ?? chatStore.streamingContent
          } else {
            // 非多模态：保持现有行为，优先使用 event.content（非流式响应），否则使用 streamingContent（流式响应）
            finalContent = event.content ?? chatStore.streamingContent
          }

          // 优先使用后端返回的时间戳，否则使用当前时间
          const timestamp = event.timestamp ?? Date.now()
          // 合并流式媒体数据到附件列表（截图等通过 MEDIA 事件独立传输的二进制数据）
          for (const media of streamingMedia.value) {
            extraAttachments.push({
              fileId: crypto.randomUUID(),
              url: `data:${media.mimeType};base64,${media.data}`,
              filename: `${media.field}.${media.mimeType.split('/')[1] ?? 'bin'}`,
              size: Math.round(media.data.length * 0.75),
              type: media.mimeType,
              isImage: media.mimeType.startsWith('image/')
            })
          }
          // 将完整消息存入消息列表
          chatStore.addMessage({
            id: messageId,
            role: 'assistant',
            content: finalContent,
            reasoningSummary: event.reasoningSummary,
            reasoningEvents: reasoningEvents.value.length > 0
              ? [...reasoningEvents.value]
              : undefined,
            a2uiComponents: a2uiStore.components.length > 0
              ? [...a2uiStore.components]
              : undefined,
            timestamp,
            traceId: event.traceId,
            attachments: extraAttachments.length > 0 ? extraAttachments : undefined,
            tokenUsage: event.tokenUsage,
            modelId: event.tokenUsage?.modelId,
            sources: event.sources,
            toolsSummary: event.toolsSummary,
          })
          // 记录本轮统计信息（若后端未返回则保持上一次或使用 usage 字段兜底）
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
          // 标记本轮用户消息为成功
          if (currentUserMessageId) {
            chatStore.updateMessage(currentUserMessageId, { status: 'success' })
          }
          chatStore.resetStreaming()
          a2uiStore.clearComponents()
          break
        }
        case SSE_EVENT_TYPES.ERROR: {
          const event: SseErrorEvent = JSON.parse(data)
          // 按错误码粗分类，提供更友好的提示
          let uiMessage: string
          if (event.code === 429) {
            uiMessage = '请求过于频繁，已触发限流，请稍后再试。'
          } else if (event.code >= 500 && event.code < 600) {
            uiMessage = '服务器暂时出现问题，请稍后重试。如果多次出现，可在轨迹页查看详情。'
          } else if (event.code >= 400 && event.code < 500) {
            uiMessage = '请求无法完成，可能是参数或权限问题：' + event.message
          } else {
            uiMessage = event.message || '对话过程中发生未知错误'
          }

          if (event.traceId) {
            uiMessage += '（可前往"轨迹"页面查看该次执行详情）'
          }

          error.value = uiMessage
          // 标记本轮用户消息失败，并挂上错误与 TraceId
          if (currentUserMessageId) {
            chatStore.updateMessage(currentUserMessageId, {
              status: 'error',
              errorMessage: uiMessage,
              traceId: event.traceId
            })
          }
          chatStore.resetStreaming()
          a2uiStore.clearComponents()
          break
        }
        case SSE_EVENT_TYPES.HEARTBEAT:
          // 心跳事件，忽略
          break
        default:
          // 未知事件类型，记录警告但不影响流程
          console.warn('未知的 SSE 事件类型:', eventType)
      }
    } catch (e) {
      // JSON 解析失败或其他错误
      const errorMessage = e instanceof Error ? e.message : '事件解析失败'
      console.error('SSE 事件解析失败:', eventType, data, e)
      // 如果是关键事件（done/error）解析失败，更新用户消息状态
      if (eventType === SSE_EVENT_TYPES.DONE || eventType === SSE_EVENT_TYPES.ERROR) {
        if (currentUserMessageId) {
          chatStore.updateMessage(currentUserMessageId, {
            status: 'error',
            errorMessage: `事件解析失败：${errorMessage}`
          })
        }
        chatStore.resetStreaming()
        a2uiStore.clearComponents()
      }
    }
  }

  /** 将 ReasoningEvent 映射为顶部状态条文案 */
  function mapReasoningStatus(ev: ReasoningEvent): string {
    switch (ev.type) {
      case 'AGENT_START':
        return '正在准备上下文与预算…'
      case 'CONTEXT_LOADING':
        return '正在分析问题与上下文…'
      case 'MEMORY_RETRIEVAL':
        return '正在检索相关记忆…'
      case 'TOOL_CALL_START':
        return ev.toolName ? `正在调用工具：${ev.toolName}…` : '正在调用外部工具…'
      case 'TOOL_CALL_END':
        return ev.toolName ? `工具 ${ev.toolName} 调用完成` : '工具调用已完成'
      case 'THINKING_STEP':
        return '正在思考解决方案…'
      case 'PLAN_UPDATED':
        return '已更新执行计划…'
      case 'ANSWER_DRAFTING':
        return '正在整理最终答案…'
      case 'ANSWER_FINALIZED':
        return '本轮回答已完成'
      case 'ERROR':
        return '推理过程中发生错误'
      default:
        return '正在处理中…'
    }
  }

  /** 取消流式请求 */
  function abort() {
    abortController?.abort()
    chatStore.resetStreaming()
    a2uiStore.clearComponents()
  }

  return {
    sendMessage,
    isStreaming,
    error,
    abort,
    // 最近一轮对话的调试 / 统计信息，用于主对话页顶部摘要和后续调试视图
    lastPrompt,
    lastModelId,
    lastTokenUsage,
    reasoningEvents,
    reasoningStatusText,
    // 当前轮流式媒体数据（截图等），供组件实时预览
    streamingMedia,
    streamingA2uiComponents: a2uiStore.components,
  }
}
