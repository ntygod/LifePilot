import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { chatApi } from '@/api/client'
import { useChat } from '@/composables/useChat'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'
import { useA2uiStore } from '@/stores/a2ui'
import { useChatStore } from '@/stores/chat'
import type { A2uiComponent } from '@/types'

vi.mock('@/api/client', () => ({
  chatApi: {
    sendMessageStream: vi.fn(),
    getSessionMessages: vi.fn(),
    updateSessionConfig: vi.fn(),
    respondInteraction: vi.fn(),
  },
}))

function createComponent(id: string, type = 'Text'): A2uiComponent {
  return {
    id,
    type,
    properties: {},
    children: [],
  }
}

function createSseStream(events: Array<{ type: string; payload: unknown }>): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  const body = events
    .map(({ type, payload }) => `event: ${type}\ndata: ${JSON.stringify(payload)}\n\n`)
    .join('')

  return new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(encoder.encode(body))
      controller.close()
    },
  })
}

function createDeferredSseStream(events: Array<{ type: string; payload: unknown }>) {
  const encoder = new TextEncoder()
  let close: (() => void) | null = null

  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      const body = events
        .map(({ type, payload }) => `event: ${type}\ndata: ${JSON.stringify(payload)}\n\n`)
        .join('')
      controller.enqueue(encoder.encode(body))
      close = () => controller.close()
    },
  })

  return {
    stream,
    close() {
      close?.()
    },
  }
}

function createManualSseStream() {
  const encoder = new TextEncoder()
  let controllerRef: ReadableStreamDefaultController<Uint8Array> | null = null

  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controllerRef = controller
    },
  })

  return {
    stream,
    push(event: { type: string; payload: unknown }) {
      controllerRef?.enqueue(
        encoder.encode(`event: ${event.type}\ndata: ${JSON.stringify(event.payload)}\n\n`),
      )
    },
    close() {
      controllerRef?.close()
    },
  }
}

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
}

describe('useChat A2UI integration', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.useRealTimers()
    vi.mocked(chatApi.getSessionMessages).mockResolvedValue([])
    vi.mocked(chatApi.updateSessionConfig).mockResolvedValue(undefined)
    vi.mocked(chatApi.respondInteraction).mockResolvedValue(undefined)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('persists A2UI from the DONE event payload into the final assistant message', async () => {
    const streamedComponents = [createComponent('card-stream', 'Card')]
    const finalComponents = [createComponent('card-final', 'Card')]

    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        { type: SSE_EVENT_TYPES.UI, payload: { components: streamedComponents } },
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-1',
            sessionId: 'session-1',
            content: '这是最终面板',
            a2uiComponents: finalComponents,
            traceId: 'trace-1',
            timestamp: 1_741_683_200_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const a2uiStore = useA2uiStore()
    const { sendMessage, isStreaming } = useChat()

    await sendMessage('展示今天的待办')

    expect(chatStore.messages).toHaveLength(2)

    const assistant = chatStore.messages.find(message => message.role === 'assistant')
    const user = chatStore.messages.find(message => message.role === 'user')

    expect(assistant).toMatchObject({
      id: 'assistant-1',
      content: '这是最终面板',
      traceId: 'trace-1',
    })
    expect(assistant?.a2uiComponents?.[0]?.id).toBe('card-final')
    expect(user?.status).toBe('success')
    expect(a2uiStore.components).toEqual([])
    expect(isStreaming.value).toBe(false)
  })

  it('falls back to the streamed A2UI snapshot when DONE omits explicit components', async () => {
    const streamedComponents = [createComponent('card-stream', 'Card')]

    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        { type: SSE_EVENT_TYPES.UI, payload: { components: streamedComponents } },
        { type: SSE_EVENT_TYPES.TOKEN, payload: { content: '面板已生成' } },
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-2',
            sessionId: 'session-1',
            content: '面板已生成',
            traceId: 'trace-2',
            timestamp: 1_741_683_260_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()

    await sendMessage('继续刷新面板')

    const assistant = chatStore.messages.find(message => message.id === 'assistant-2')
    expect(assistant?.content).toBe('面板已生成')
    expect(assistant?.a2uiComponents?.[0]?.id).toBe('card-stream')
  })

  it('buffers token chunks locally and flushes them on the short timer or terminal DONE', async () => {
    vi.useFakeTimers()
    const manualStream = createManualSseStream()

    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(manualStream.stream)

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    const pendingSend = sendMessage('开始流式回答')
    await flushUi()

    manualStream.push({
      type: SSE_EVENT_TYPES.TOKEN,
      payload: { content: '第一段' },
    })
    await flushUi()

    expect(chatStore.streamingContent).toBe('')

    vi.advanceTimersByTime(40)
    await flushUi()

    expect(chatStore.streamingContent).toBe('第一段')

    manualStream.push({
      type: SSE_EVENT_TYPES.TOKEN,
      payload: { content: '第二段' },
    })
    manualStream.push({
      type: SSE_EVENT_TYPES.DONE,
      payload: {
        entryId: 'assistant-buffered',
        sessionId: 'session-1',
        content: '第一段第二段',
        timestamp: 1_741_683_261_000,
      },
    })
    manualStream.close()

    await pendingSend

    const assistant = chatStore.messages.find(message => message.id === 'assistant-buffered')
    expect(assistant?.content).toBe('第一段第二段')
    expect(chatStore.streamingContent).toBe('')
  })

  it('allows attachment-only sends when attachmentIds exist', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-attachment',
            sessionId: 'session-1',
            content: '已处理附件',
            timestamp: 1_741_683_300_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('', ['file-1'])

    expect(vi.mocked(chatApi.sendMessageStream)).toHaveBeenCalledWith(
      '',
      'session-1',
      ['file-1'],
      expect.any(String),
      'SEND',
      expect.any(AbortSignal),
    )
    expect(chatStore.messages.find(message => message.id === 'assistant-attachment')?.content).toBe('已处理附件')
  })

  it('preserves streamed assistant text when a degraded DONE arrives', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        { type: SSE_EVENT_TYPES.TOKEN, payload: { content: '我先说明当前的质量保障逻辑。' } },
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-degraded',
            sessionId: 'session-1',
            content: '本轮处理已中断。\n\n原因：文件读取失败',
            traceId: 'trace-degraded',
            completionMode: 'DEGRADED',
            turnStatus: 'DEGRADED',
            terminationReason: '文件读取失败',
            timestamp: 1_741_683_320_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('解释质量保障')

    const assistant = chatStore.messages.find(message => message.id === 'assistant-degraded')
    expect(assistant?.content).toContain('我先说明当前的质量保障逻辑。')
    expect(assistant?.content).toContain('原因：文件读取失败')
    expect(assistant?.turnStatus).toBe('DEGRADED')
  })

  it('keeps streamed assistant text when DONE only carries a progress placeholder', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        { type: SSE_EVENT_TYPES.TOKEN, payload: { content: '长篇创作会依赖章节摘要和世界观卡片来续写。' } },
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-progress-only',
            sessionId: 'session-1',
            content: '正在思考回答…',
            contentRole: 'PROGRESS',
            traceId: 'trace-progress-only',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_321_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('继续解释长篇上下文管理')

    const assistant = chatStore.messages.find(message => message.id === 'assistant-progress-only')
    expect(assistant?.content).toBe('长篇创作会依赖章节摘要和世界观卡片来续写。')
  })

  it('keeps streamed assistant text instead of collapsing to a user error on SSE ERROR', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        { type: SSE_EVENT_TYPES.TOKEN, payload: { content: '我已经完成前半段分析，接下来准备继续验证。' } },
        {
          type: SSE_EVENT_TYPES.ERROR,
          payload: {
            code: 500,
            message: '处理失败: 模型服务暂时不可用',
            traceId: 'trace-error',
            turnStatus: 'FAILED',
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage, error } = useChat()
    await sendMessage('继续执行')

    const assistant = chatStore.messages.find(message => message.id === 'trace-error')
    const user = chatStore.messages.find(message => message.role === 'user')

    expect(assistant?.content).toContain('我已经完成前半段分析')
    expect(assistant?.content).toContain('模型服务暂时不可用')
    expect(user?.status).toBe('success')
    expect(error.value).toBeNull()
  })

  it('turns agent-suspended into a resumable assistant message without wiping streamed text', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        { type: SSE_EVENT_TYPES.TOKEN, payload: { content: '我需要你补充仓库地址后再继续。' } },
        {
          type: SSE_EVENT_TYPES.AGENT_SUSPENDED,
          payload: {
            traceId: 'trace-suspended',
            sessionId: 'session-1',
            turnId: 'turn-suspended',
            completionMode: 'SUSPENDED',
            turnStatus: 'SUSPENDED',
            reasonDetail: '等待你提供仓库地址',
            terminationReason: '等待你提供仓库地址',
            suspendedAt: '2026-03-25T12:00:00Z',
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('继续执行自动化链路')

    const assistant = chatStore.messages.find(message => message.id === 'trace-suspended')
    const user = chatStore.messages.find(message => message.role === 'user')

    expect(assistant?.content).toContain('我需要你补充仓库地址后再继续。')
    expect(assistant?.content).toContain('等待你提供仓库地址')
    expect(assistant?.turnStatus).toBe('SUSPENDED')
    expect(assistant?.completionMode).toBe('SUSPENDED')
    expect(user?.status).toBe('success')
  })

  it('stores interaction SSE events locally and submits through the interaction API', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.INTERACTION,
          payload: {
            interactionId: 'interaction-1',
            type: 'INPUT',
            sessionId: 'session-1',
            streamId: 'stream-1',
            message: '请输入仓库地址',
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage, activeInteraction, interactionError, submitInteraction } = useChat()
    await sendMessage('继续执行')

    expect(activeInteraction.value).toMatchObject({
      interactionId: 'interaction-1',
      type: 'INPUT',
      message: '请输入仓库地址',
    })

    await submitInteraction('https://github.com/acme/demo.git')

    expect(vi.mocked(chatApi.respondInteraction)).toHaveBeenCalledWith('interaction-1', {
      type: 'INPUT',
      value: 'https://github.com/acme/demo.git',
      confirmed: true,
      timedOut: false,
    })
    expect(activeInteraction.value).toBeNull()
    expect(interactionError.value).toBeNull()
  })

  it('routes the next plain user reply to the pending interaction without opening a new turn', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.INTERACTION,
          payload: {
            interactionId: 'interaction-inline',
            type: 'INPUT',
            sessionId: 'session-1',
            streamId: 'stream-1',
            message: '请补充仓库地址，我收到后继续处理',
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage, activeInteraction } = useChat()
    await sendMessage('继续执行')

    expect(activeInteraction.value?.interactionId).toBe('interaction-inline')

    await sendMessage('https://github.com/acme/demo.git')

    expect(vi.mocked(chatApi.sendMessageStream)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(chatApi.respondInteraction)).toHaveBeenCalledWith('interaction-inline', {
      type: 'INPUT',
      value: 'https://github.com/acme/demo.git',
      confirmed: true,
      timedOut: false,
    })

    const userReplies = chatStore.messages.filter(message =>
      message.role === 'user' && message.content === 'https://github.com/acme/demo.git')
    expect(userReplies).toHaveLength(1)
    expect(userReplies[0]?.status).toBe('success')
  })

  it('allows canceling an interaction dialog and reports timeout to the backend', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.INTERACTION,
          payload: {
            interactionId: 'interaction-cancel',
            type: 'CONFIRM',
            sessionId: 'session-1',
            streamId: 'stream-2',
            message: '是否继续执行？',
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage, activeInteraction, cancelInteraction } = useChat()
    await sendMessage('继续执行')

    expect(activeInteraction.value?.interactionId).toBe('interaction-cancel')

    await cancelInteraction()

    expect(vi.mocked(chatApi.respondInteraction)).toHaveBeenCalledWith('interaction-cancel', {
      type: 'CONFIRM',
      value: null,
      confirmed: false,
      timedOut: true,
    })
    expect(activeInteraction.value).toBeNull()
  })

  it('still shows a suspended assistant message when no streamed text has arrived yet', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.AGENT_SUSPENDED,
          payload: {
            traceId: 'trace-suspended-empty',
            sessionId: 'session-1',
            turnId: 'turn-suspended-empty',
            completionMode: 'SUSPENDED',
            turnStatus: 'SUSPENDED',
            reasonDetail: '等待你补充仓库地址',
            terminationReason: '等待你补充仓库地址',
            suspendedAt: '2026-03-25T12:00:00Z',
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('继续执行自动化链路')

    const assistant = chatStore.messages.find(message => message.id === 'trace-suspended-empty')

    expect(assistant?.content).toContain('我先停在这里等你补充。')
    expect(assistant?.content).toContain('等待你补充仓库地址')
    expect(assistant?.content).toContain('你直接回复就行')
    expect(assistant?.turnStatus).toBe('SUSPENDED')
    expect(assistant?.completionMode).toBe('SUSPENDED')
  })

  it('auto-resumes the latest suspended turn when the user provides follow-up information', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-resumed',
            sessionId: 'session-1',
            turnId: 'turn-suspended',
            content: '已完成：我已根据你补充的仓库地址继续执行。',
            traceId: 'trace-resumed',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_360_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()
    chatStore.addMessage({
      id: 'assistant-suspended-existing',
      turnId: 'turn-suspended',
      role: 'assistant',
      content: '本轮处理已挂起，请补充仓库地址。',
      timestamp: 1_741_683_350_000,
      turnStatus: 'SUSPENDED',
      completionMode: 'SUSPENDED',
    })

    const { sendMessage } = useChat()
    await sendMessage('仓库地址是 https://github.com/acme/demo.git')

    expect(vi.mocked(chatApi.sendMessageStream)).toHaveBeenCalledWith(
      '仓库地址是 https://github.com/acme/demo.git',
      'session-1',
      undefined,
      'turn-suspended',
      'RESUME',
      expect.any(AbortSignal),
    )

    const resumedUser = chatStore.messages.find(message =>
      message.role === 'user' && message.content === '仓库地址是 https://github.com/acme/demo.git')
    expect(resumedUser?.turnId).toBe('turn-suspended')
  })

  it('restores natural input immediately after agent-suspended, even before the old stream fully closes', async () => {
    const suspendedStream = createDeferredSseStream([
      {
        type: SSE_EVENT_TYPES.AGENT_SUSPENDED,
        payload: {
          traceId: 'trace-suspended-live',
          sessionId: 'session-1',
          turnId: 'turn-suspended-live',
          completionMode: 'SUSPENDED',
          turnStatus: 'SUSPENDED',
          content: '我还缺仓库地址。你直接回复后，我会接着刚才的进度继续处理。',
          reasonDetail: '等待你补充仓库地址',
          terminationReason: '等待你补充仓库地址',
          suspendedAt: '2026-03-25T18:40:00Z',
        },
      },
    ])

    vi.mocked(chatApi.sendMessageStream)
      .mockResolvedValueOnce(suspendedStream.stream)
      .mockResolvedValueOnce(
        createSseStream([
          {
            type: SSE_EVENT_TYPES.DONE,
            payload: {
              entryId: 'assistant-after-resume',
              sessionId: 'session-1',
              turnId: 'turn-suspended-live',
              content: '已完成：我已根据你补充的仓库地址继续处理。',
              traceId: 'trace-after-resume',
              turnStatus: 'SUCCESS',
              timestamp: 1_741_683_380_000,
            },
          },
        ]),
      )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage, isStreaming } = useChat()
    const firstSend = sendMessage('继续执行')
    await flushUi()
    await new Promise(resolve => setTimeout(resolve, 0))
    await flushUi()

    expect(isStreaming.value).toBe(false)

    await sendMessage('仓库地址是 https://github.com/acme/demo.git')

    expect(vi.mocked(chatApi.sendMessageStream)).toHaveBeenNthCalledWith(
      2,
      '仓库地址是 https://github.com/acme/demo.git',
      'session-1',
      undefined,
      'turn-suspended-live',
      'RESUME',
      expect.any(AbortSignal),
    )

    suspendedStream.close()
    await firstSend
    await flushUi()

    const resumedAssistant = chatStore.messages.find(message => message.id === 'assistant-after-resume')
    expect(resumedAssistant?.content).toContain('已完成：我已根据你补充的仓库地址继续处理。')
  })

  it('skips sending when both content and attachmentIds are empty', async () => {
    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('   ')

    expect(vi.mocked(chatApi.sendMessageStream)).not.toHaveBeenCalled()
    expect(chatStore.messages).toHaveLength(0)
  })

  // Phase 9：reasoning 流式 SSE 事件按 payload 形态分流
  it('累计 reasoning delta 到 buffer 并在 DONE 时落入 reasoningContent', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.REASONING,
          payload: { sessionId: 'session-1', turnId: 'turn-1', delta: '让我想想，' },
        },
        {
          type: SSE_EVENT_TYPES.REASONING,
          payload: { sessionId: 'session-1', turnId: 'turn-1', delta: '这需要分两步处理。' },
        },
        { type: SSE_EVENT_TYPES.TOKEN, payload: { content: '答案是 42', index: 0 } },
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-r1',
            sessionId: 'session-1',
            content: '答案是 42',
            traceId: 'trace-r1',
            timestamp: 1_745_000_000_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage, reasoningBuffer, isReasoningActive } = useChat()
    await sendMessage('帮我算个题')

    const assistant = chatStore.messages.find(message => message.role === 'assistant')
    expect(assistant?.reasoningContent).toBe('让我想想，这需要分两步处理。')
    // durationMs 在所有事件同帧到达时可能为 0（被过滤为 undefined）— 不强制断言数值
    // 流结束 → active 转回 false
    expect(isReasoningActive.value).toBe(false)
    // 单词 buffer 累计无丢失
    expect(reasoningBuffer.value).toBe('让我想想，这需要分两步处理。')
  })

  it('忽略 reasoning event payload（ReAct 步骤）但不污染 reasoningBuffer', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.REASONING,
          payload: {
            sessionId: 'session-1',
            turnId: 'turn-1',
            event: {
              id: 'evt-1',
              type: 'PROGRESS',
              title: '加载上下文',
              description: '准备工具',
              createdAt: '2026-04-27T00:00:00.000Z',
            },
          },
        },
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-r2',
            sessionId: 'session-1',
            content: '完成',
            traceId: 'trace-r2',
            timestamp: 1_745_000_000_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage, reasoningEvents, reasoningBuffer } = useChat()
    await sendMessage('开始')

    // ReAct 事件落入 reasoningEvents，buffer 保持空（不被 event 污染）
    expect(reasoningEvents.value.length).toBe(1)
    expect(reasoningBuffer.value).toBe('')
    const assistant = chatStore.messages.find(message => message.role === 'assistant')
    expect(assistant?.reasoningContent).toBeUndefined()
  })
})
