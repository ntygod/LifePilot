import { ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useA2uiStore } from '@/stores/a2ui'
import { chatApi } from '@/api/client'
import type { SseTokenEvent, SseDoneEvent, SseErrorEvent, A2uiComponent } from '@/types'

/**
 * 对话 composable，封装 SSE 流式请求和消息管理。
 *
 * 核心流程：
 * 1. fetch POST /api/chat/messages/stream
 * 2. 通过 ReadableStream 逐行读取 SSE 事件
 * 3. token 事件 → chatStore.streamingContent 增量拼接
 * 4. ui 事件 → a2uiStore.updateComponents()
 * 5. done 事件 → 完整消息存入 chatStore.messages
 * 6. error 事件 → 设置 error ref
 */
export function useChat() {
  const chatStore = useChatStore()
  const a2uiStore = useA2uiStore()
  const isStreaming = ref(false)
  const error = ref<string | null>(null)
  let abortController: AbortController | null = null

  /** 发送消息（流式） */
  async function sendMessage(content: string) {
    if (!content.trim()) return

    // 添加用户消息到列表
    chatStore.addMessage({
      id: crypto.randomUUID(),
      role: 'user',
      content,
      timestamp: Date.now()
    })

    // 重置状态
    isStreaming.value = true
    chatStore.isStreaming = true
    chatStore.streamingContent = ''
    error.value = null
    a2uiStore.clearComponents()
    abortController = new AbortController()

    try {
      const stream = await chatApi.sendMessageStream(
        content,
        chatStore.activeSessionId ?? undefined,
        abortController.signal
      )
      await parseSseStream(stream)
    } catch (e: unknown) {
      if (e instanceof DOMException && e.name === 'AbortError') return
      error.value = e instanceof Error ? e.message : '请求失败'
    } finally {
      isStreaming.value = false
      chatStore.isStreaming = false
      abortController = null
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
            timestamp: Date.now()
          })
          chatStore.resetStreaming()
          break
        }
        case 'error': {
          const event: SseErrorEvent = JSON.parse(data)
          error.value = event.message
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

  return { sendMessage, isStreaming, error, abort }
}
