import { ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useA2uiStore } from '@/stores/a2ui'
import { chatApi } from '@/api/client'
import type { SseTokenEvent, SseDoneEvent, SseErrorEvent, A2uiComponent, TokenUsage } from '@/types'

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
  let abortController: AbortController | null = null
  // 当前这轮请求对应的用户消息 ID，用于在错误 / 完成时回写状态
  let currentUserMessageId: string | null = null

  /** 发送消息（流式） */
  async function sendMessage(content: string) {
    if (!content.trim()) return

    // 添加用户消息到列表
    const userMessageId = crypto.randomUUID()
    chatStore.addMessage({
      id: userMessageId,
      role: 'user',
      content,
      timestamp: Date.now(),
      status: 'pending'
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
    a2uiStore.clearComponents()
    abortController = new AbortController()

    try {
      // 记录本轮基础 Prompt 摘要（当前仅记录用户输入，后续可由后端返回整合后的 Prompt）
      lastPrompt.value = content

      const stream = await chatApi.sendMessageStream(
        content,
        chatStore.activeSessionId ?? undefined,
        abortController.signal
      )
      await parseSseStream(stream)
    } catch (e: unknown) {
      if (e instanceof DOMException && e.name === 'AbortError') return
      // 网络 / HTTP 级错误，视为本条消息发送失败
      const message = e instanceof Error ? e.message : '请求失败'
      // 网络 / HTTP 级错误统一归为“网络异常”
      error.value = `网络异常：${message}`
      if (currentUserMessageId) {
        chatStore.updateMessage(currentUserMessageId, {
          status: 'error',
          errorMessage: message
        })
      }
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
            currentEvent = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            const data = line.slice(5).trim()
            if (data) handleSseEvent(currentEvent, data)
          }
          // 空行表示事件结束
          if (line === '') currentEvent = ''
        }
      }
    } finally {
      reader.releaseLock()
    }
  }

  /** 处理单个 SSE 事件 */
  function handleSseEvent(eventType: string, data: string) {
    try {
      switch (eventType) {
        case 'token': {
          const event: SseTokenEvent = JSON.parse(data)
          chatStore.streamingContent += event.content
          break
        }
        case 'ui': {
          const event: { components: A2uiComponent[] } = JSON.parse(data)
          a2uiStore.updateComponents(event.components)
          break
        }
        case 'done': {
          const event: SseDoneEvent = JSON.parse(data)
          // 将完整消息存入消息列表
          chatStore.addMessage({
            id: event.messageId,
            role: 'assistant',
            content: chatStore.streamingContent,
            a2uiComponents: a2uiStore.components.length > 0
              ? [...a2uiStore.components]
              : undefined,
            timestamp: Date.now(),
            traceId: event.traceId
          })
          // 记录本轮统计信息
          lastTokenUsage.value = event.tokenUsage
          lastModelId.value = event.tokenUsage?.modelId ?? null
          // 标记本轮用户消息为成功
          if (currentUserMessageId) {
            chatStore.updateMessage(currentUserMessageId, { status: 'success' })
          }
          chatStore.resetStreaming()
          break
        }
        case 'error': {
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
            uiMessage += '（可前往“轨迹”页面查看该次执行详情）'
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
          break
        }
        case 'heartbeat':
          // 心跳事件，忽略
          break
      }
    } catch {
      console.warn('SSE 事件解析失败:', eventType, data)
    }
  }

  /** 取消流式请求 */
  function abort() {
    abortController?.abort()
    chatStore.resetStreaming()
  }

  return {
    sendMessage,
    isStreaming,
    error,
    abort,
    // 最近一轮对话的调试 / 统计信息，用于主对话页顶部摘要和后续调试视图
    lastPrompt,
    lastModelId,
    lastTokenUsage
  }
}
