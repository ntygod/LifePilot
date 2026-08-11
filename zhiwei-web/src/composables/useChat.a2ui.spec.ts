import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { chatApi, memoryApi } from '@/api/client'
import { useChat } from '@/composables/useChat'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'
import { useA2uiStore } from '@/stores/a2ui'
import { useChatStore } from '@/stores/chat'
import type { A2uiComponent } from '@/types'

vi.mock('@/api/client', () => ({
  chatApi: {
    createSession: vi.fn(),
    sendMessageStream: vi.fn(),
    getSessionMessages: vi.fn(),
    updateSessionConfig: vi.fn(),
  },
  memoryApi: {
    getTurnMemoryChangesStatus: vi.fn(),
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
    vi.mocked(chatApi.createSession).mockResolvedValue({
      id: 'session-created',
      title: '新对话',
      pinned: false,
      archived: false,
      createdAt: '2026-07-05T00:00:00Z',
      updatedAt: '2026-07-05T00:00:00Z',
    })
    vi.mocked(chatApi.getSessionMessages).mockResolvedValue([])
    vi.mocked(chatApi.updateSessionConfig).mockResolvedValue(undefined)
    vi.mocked(memoryApi.getTurnMemoryChangesStatus).mockResolvedValue({ status: 'PENDING', changes: [] })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('首轮慢建会话时先显示用户消息，再继续流式执行', async () => {
    let resolveCreateSession: (value: Awaited<ReturnType<typeof chatApi.createSession>>) => void
    const createSessionPromise = new Promise<Awaited<ReturnType<typeof chatApi.createSession>>>((resolve) => {
      resolveCreateSession = resolve
    })
    vi.mocked(chatApi.createSession).mockReturnValueOnce(createSessionPromise)
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-after-create',
            sessionId: 'session-created',
            content: '已开始处理。',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_250_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    expect(chatStore.activeSessionId).toBeNull()

    const { sendMessage, isStreaming } = useChat()
    const pending = sendMessage('帮我整理这段资料')
    await flushUi()

    const optimisticUser = chatStore.messages.find(message => message.role === 'user')
    expect(optimisticUser).toMatchObject({
      content: '帮我整理这段资料',
      status: 'pending',
      turnStatus: 'PENDING',
    })
    expect(isStreaming.value).toBe(true)
    expect(vi.mocked(chatApi.sendMessageStream)).not.toHaveBeenCalled()

    resolveCreateSession!({
      id: 'session-created',
      title: '新对话',
      pinned: false,
      archived: false,
      createdAt: '2026-07-05T00:00:00Z',
      updatedAt: '2026-07-05T00:00:00Z',
    })
    await pending

    expect(chatStore.activeSessionId).toBe('session-created')
    expect(chatStore.messages.find(message => message.role === 'user')?.content).toBe('帮我整理这段资料')
    expect(chatStore.messages.find(message => message.id === 'assistant-after-create')?.content).toBe('已开始处理。')
  })

  it('发送消息时把单轮记忆上下文选择保留在用户消息上', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-memory-context',
            sessionId: 'session-1',
            content: '已按当前消息处理。',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_260_000,
          },
        },
      ]),
    )
    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'

    const { sendMessage } = useChat()
    await sendMessage(
      '只根据这段文字总结',
      undefined,
      undefined,
      { memoryContextMode: 'off' },
    )

    const user = chatStore.messages.find(message => message.role === 'user')
    expect(user?.singleTurnOverride).toEqual({ memoryContextMode: 'off' })
    expect(vi.mocked(chatApi.sendMessageStream).mock.calls[0][6]).toEqual({ memoryContextMode: 'off' })
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

  it('不再暴露能力预发现状态', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-capability',
            sessionId: 'session-1',
            content: '我会查资料并调用合适技能处理。',
            traceId: 'trace-capability',
            timestamp: 1_741_683_260_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const chat = useChat()
    await chat.sendMessage('帮我调研一下今天的 AI 最新资讯')

    expect('capabilitySuggestions' in chat).toBe(false)
    expect(chatStore.messages.find(message => message.id === 'assistant-capability')?.content)
      .toBe('我会查资料并调用合适技能处理。')
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
      null,
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

  it('仅有任务恢复摘要时也按中断 DONE 落地', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        { type: SSE_EVENT_TYPES.TOKEN, payload: { content: '我已经完成了前两步检查。' } },
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-task-recovery-only-degraded',
            sessionId: 'session-1',
            turnId: 'turn-task-recovery-only-degraded',
            content: '任务卡在资料下载。',
            traceId: 'trace-task-recovery-only-degraded',
            terminationReason: '资料下载失败',
            timestamp: 1_741_683_330_000,
            taskRecovery: {
              status: 'DEGRADED',
              title: '资料下载中断',
              detail: '资料下载失败，可以保留已完成检查继续。',
              actionLabel: '继续处理',
              canResume: true,
              canRestart: true,
              resumeMode: 'manual',
              nextActions: ['复用已完成检查', '重新下载失败资料'],
            },
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('帮我查资料')

    const assistant = chatStore.messages.find(message => message.id === 'assistant-task-recovery-only-degraded')
    const user = chatStore.messages.find(message => message.role === 'user')
    expect(assistant?.content).toContain('我已经完成了前两步检查。')
    expect(assistant?.content).toContain('原因：资料下载失败')
    expect(assistant?.turnStatus).toBe('DEGRADED')
    expect(assistant?.completionMode).toBe('DEGRADED')
    expect(assistant?.errorMessage).toBe('资料下载失败')
    expect(assistant?.taskRecovery?.title).toBe('资料下载中断')
    expect(user?.turnStatus).toBe('DEGRADED')
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

  it('正常完成后后台检查记忆沉淀，无新增时短暂说明已检查', async () => {
    vi.useFakeTimers()
    vi.mocked(memoryApi.getTurnMemoryChangesStatus).mockResolvedValue({ status: 'CHECKED_EMPTY', changes: [] })
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-memory-checking',
            sessionId: 'session-1',
            turnId: 'turn-memory-checking',
            content: '我记下你的偏好了。',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_322_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('我喜欢轻量主界面')

    const assistant = chatStore.messages.find(message => message.id === 'assistant-memory-checking')
    expect(assistant?.memoryChangeStatus).toBe('checking')

    await vi.advanceTimersByTimeAsync(600)
    await flushUi()

    expect(memoryApi.getTurnMemoryChangesStatus).toHaveBeenCalledTimes(1)
    expect(chatStore.messages.find(message => message.id === 'assistant-memory-checking')?.memoryChangeStatus)
      .toBe('checked-empty')

    await vi.advanceTimersByTimeAsync(2400)
    await flushUi()

    expect(chatStore.messages.find(message => message.id === 'assistant-memory-checking')?.memoryChangeStatus)
      .toBeUndefined()
  })

  it('用户补充稳定个人事实时浮现后台记忆沉淀状态', async () => {
    vi.useFakeTimers()
    vi.mocked(memoryApi.getTurnMemoryChangesStatus).mockResolvedValue({ status: 'PENDING', changes: [] })
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-profile-memory-checking',
            sessionId: 'session-1',
            turnId: 'turn-profile-memory-checking',
            content: '好的，我会按这个称呼来记。',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_322_200,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('我叫林一，以后叫我一一，我的生日是 5 月 8 日')

    const assistant = chatStore.messages.find(message => message.id === 'assistant-profile-memory-checking')
    expect(assistant?.memoryChangeStatus).toBe('checking')
  })

  it('用户补充项目定位这类可复用上下文时浮现后台记忆沉淀状态', async () => {
    vi.useFakeTimers()
    vi.mocked(memoryApi.getTurnMemoryChangesStatus).mockResolvedValue({ status: 'PENDING', changes: [] })
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-context-memory-checking',
            sessionId: 'session-1',
            turnId: 'turn-context-memory-checking',
            content: '明白，知微会按这个产品定位来理解后续方案。',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_322_300,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('这个应用的定位是本地个人 APP，主界面做轻，做好交互。')

    const assistant = chatStore.messages.find(message => message.id === 'assistant-context-memory-checking')
    expect(assistant?.memoryChangeStatus).toBe('checking')
  })

  it('用户明确要求写入记忆时浮现后台记忆沉淀状态', async () => {
    vi.useFakeTimers()
    vi.mocked(memoryApi.getTurnMemoryChangesStatus).mockResolvedValue({ status: 'PENDING', changes: [] })
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-explicit-memory-checking',
            sessionId: 'session-1',
            turnId: 'turn-explicit-memory-checking',
            content: '好的，我会按这条记忆来保持后续判断。',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_322_400,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('请把“主界面保持轻量，能力在对话中自然浮现”写入长期记忆')

    const assistant = chatStore.messages.find(message => message.id === 'assistant-explicit-memory-checking')
    expect(assistant?.memoryChangeStatus).toBe('checking')
  })

  it('普通完成轮次后台检查记忆但不浮现检查状态', async () => {
    vi.useFakeTimers()
    vi.mocked(memoryApi.getTurnMemoryChangesStatus).mockResolvedValue({ status: 'CHECKED_EMPTY', changes: [] })
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-quiet-memory-check',
            sessionId: 'session-1',
            turnId: 'turn-quiet-memory-check',
            content: '这段资料可以分成三个要点。',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_322_500,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('帮我总结这段资料')

    const assistant = chatStore.messages.find(message => message.id === 'assistant-quiet-memory-check')
    expect(assistant?.memoryChangeStatus).toBeUndefined()

    await vi.advanceTimersByTimeAsync(600)
    await flushUi()

    expect(memoryApi.getTurnMemoryChangesStatus).toHaveBeenCalledTimes(1)
    expect(chatStore.messages.find(message => message.id === 'assistant-quiet-memory-check')?.memoryChangeStatus).toBeUndefined()
  })

  it('后台拿到本轮沉淀后把检查状态更新为已沉淀', async () => {
    vi.useFakeTimers()
    vi.mocked(memoryApi.getTurnMemoryChangesStatus)
      .mockResolvedValueOnce({ status: 'PENDING', changes: [] })
      .mockResolvedValueOnce({
        status: 'SETTLED',
        changes: [
          {
            type: 'memory',
            id: 'memory-new-1',
            name: '主界面偏好',
            extra: {
              operationLabel: '新增',
              evidenceExcerpt: '我喜欢轻量主界面',
            },
          },
        ],
      })
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-memory-settled',
            sessionId: 'session-1',
            turnId: 'turn-memory-settled',
            content: '我记下来了。',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_323_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('我喜欢轻量主界面')

    expect(chatStore.messages.find(message => message.id === 'assistant-memory-settled')?.memoryChangeStatus).toBe('checking')

    await vi.advanceTimersByTimeAsync(1800)
    await flushUi()

    const assistant = chatStore.messages.find(message => message.id === 'assistant-memory-settled')
    expect(assistant?.memoryChangeStatus).toBe('settled')
    expect(assistant?.memoryChanges?.[0]?.name).toBe('主界面偏好')
  })

  it('后台记忆整理失败时保留可解释失败状态', async () => {
    vi.useFakeTimers()
    vi.mocked(memoryApi.getTurnMemoryChangesStatus).mockResolvedValue({ status: 'FAILED', reason: 'llm_timeout', changes: [] })
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-memory-failed',
            sessionId: 'session-1',
            turnId: 'turn-memory-failed',
            content: '我会记住这个偏好。',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_323_100,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('我喜欢轻量主界面')

    await vi.advanceTimersByTimeAsync(600)
    await flushUi()

    expect(chatStore.messages.find(message => message.id === 'assistant-memory-failed')?.memoryChangeStatus)
      .toBe('failed')
  })

  it('后台记忆沉淀一直未完成时保留可解释超时状态', async () => {
    vi.useFakeTimers()
    vi.mocked(memoryApi.getTurnMemoryChangesStatus).mockResolvedValue({ status: 'PENDING', changes: [] })
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-memory-pending-timeout',
            sessionId: 'session-1',
            turnId: 'turn-memory-pending-timeout',
            content: '我会记住这个偏好。',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_323_150,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('我喜欢轻量主界面')

    const messageId = 'assistant-memory-pending-timeout'
    expect(chatStore.messages.find(message => message.id === messageId)?.memoryChangeStatus).toBe('checking')

    await vi.advanceTimersByTimeAsync(12_000)
    await flushUi()

    const assistant = chatStore.messages.find(message => message.id === messageId)
    expect(memoryApi.getTurnMemoryChangesStatus).toHaveBeenCalledTimes(5)
    expect(assistant?.memoryChangeStatus).toBe('failed')
    expect(assistant?.memoryChangeReason).toBe('memory_status_timeout')
  })

  it('后台记忆状态接口持续失败时保留可解释失败状态', async () => {
    vi.useFakeTimers()
    vi.mocked(memoryApi.getTurnMemoryChangesStatus).mockRejectedValue(new Error('network busy'))
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-memory-refresh-failed',
            sessionId: 'session-1',
            turnId: 'turn-memory-refresh-failed',
            content: '我会记住这个偏好。',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_323_180,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('我喜欢轻量主界面')

    const messageId = 'assistant-memory-refresh-failed'
    expect(chatStore.messages.find(message => message.id === messageId)?.memoryChangeStatus).toBe('checking')

    await vi.advanceTimersByTimeAsync(12_000)
    await flushUi()

    const assistant = chatStore.messages.find(message => message.id === messageId)
    expect(memoryApi.getTurnMemoryChangesStatus).toHaveBeenCalledTimes(5)
    expect(assistant?.memoryChangeStatus).toBe('failed')
    expect(assistant?.memoryChangeReason).toBe('memory_status_refresh_failed')
  })

  it('后台记忆沉淀不可用时保留可解释跳过状态', async () => {
    vi.useFakeTimers()
    vi.mocked(memoryApi.getTurnMemoryChangesStatus).mockResolvedValue({
      status: 'DISABLED',
      reason: 'memory_repository_unavailable',
      changes: [],
    })
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-memory-disabled',
            sessionId: 'session-1',
            turnId: 'turn-memory-disabled',
            content: '我先按这个偏好来回答。',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_323_200,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('我喜欢轻量主界面')

    await vi.advanceTimersByTimeAsync(600)
    await flushUi()

    const assistant = chatStore.messages.find(message => message.id === 'assistant-memory-disabled')
    expect(assistant?.memoryChangeStatus).toBe('disabled')
    expect(assistant?.memoryChangeReason).toBe('memory_repository_unavailable')
  })

  it('项目会话后台补齐记忆沉淀时会带上 projectId', async () => {
    vi.useFakeTimers()
    vi.mocked(memoryApi.getTurnMemoryChangesStatus).mockResolvedValue({ status: 'PENDING', changes: [] })
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-memory-project',
            sessionId: 'session-1',
            turnId: 'turn-memory-project',
            content: '我记下项目里的偏好了。',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_323_500,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.sessions = [{
      id: 'session-1',
      title: '项目对话',
      createdAt: '2026-07-05T00:00:00Z',
      updatedAt: '2026-07-05T00:00:00Z',
      projectId: 'project-1',
    }]
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('我喜欢项目里用轻量主界面')

    await vi.advanceTimersByTimeAsync(600)
    await flushUi()

    expect(memoryApi.getTurnMemoryChangesStatus).toHaveBeenCalledWith('turn-memory-project', 'project-1')
  })

  it('后台补齐记忆沉淀使用发送时的项目上下文快照', async () => {
    vi.useFakeTimers()
    vi.mocked(memoryApi.getTurnMemoryChangesStatus).mockResolvedValue({ status: 'PENDING', changes: [] })
    const manualStream = createManualSseStream()
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(manualStream.stream)

    const chatStore = useChatStore()
    chatStore.sessions = [{
      id: 'session-1',
      title: '项目对话',
      createdAt: '2026-07-05T00:00:00Z',
      updatedAt: '2026-07-05T00:00:00Z',
      projectId: 'project-1',
    }]
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    const pending = sendMessage('我喜欢项目里用轻量主界面')
    await flushUi()

    chatStore.sessions = [{
      id: 'session-1',
      title: '项目对话',
      createdAt: '2026-07-05T00:00:00Z',
      updatedAt: '2026-07-05T00:00:00Z',
      projectId: null,
    }]

    manualStream.push({
      type: SSE_EVENT_TYPES.DONE,
      payload: {
        entryId: 'assistant-memory-project-snapshot',
        sessionId: 'session-1',
        turnId: 'turn-memory-project-snapshot',
        content: '我记下项目里的偏好了。',
        turnStatus: 'SUCCESS',
        timestamp: 1_741_683_323_600,
      },
    })
    manualStream.close()
    await pending

    await vi.advanceTimersByTimeAsync(600)
    await flushUi()

    expect(memoryApi.getTurnMemoryChangesStatus)
      .toHaveBeenCalledWith('turn-memory-project-snapshot', 'project-1')
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
            taskRecovery: {
              status: 'SUSPENDED',
              title: '等待你补充信息',
              detail: '你直接回复补充内容，知微会接着当前进度继续。',
              actionLabel: '等待',
              canResume: false,
              canRestart: true,
              reasonType: 'ExternalDataWait',
              reasonSourceId: '__await_user_input__',
              resumeMode: 'user_reply',
            },
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
    expect(assistant?.taskRecovery?.resumeMode).toBe('user_reply')
    expect(assistant?.taskRecovery?.canResume).toBe(false)
    expect(user?.status).toBe('success')
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

  it('明确等待用户补充时会把输入自动续接到挂起轮次', async () => {
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
      suspendReasonSourceId: '__await_user_input__',
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
      null,
    )

    const resumedUser = chatStore.messages.find(message =>
      message.role === 'user' && message.content === '仓库地址是 https://github.com/acme/demo.git')
    expect(resumedUser?.turnId).toBe('turn-suspended')
  })

  it('缺少 resumeMode 但来源明确等待用户输入时仍会自动续接', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-legacy-await-recovered',
            sessionId: 'session-1',
            turnId: 'turn-legacy-await',
            content: '已接上你补充的信息继续执行。',
            traceId: 'trace-legacy-await-recovered',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_360_500,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()
    chatStore.addMessage({
      id: 'assistant-legacy-await',
      turnId: 'turn-legacy-await',
      role: 'assistant',
      content: '还缺一个资料库名称。',
      timestamp: 1_741_683_350_500,
      turnStatus: 'SUSPENDED',
      completionMode: 'SUSPENDED',
      taskRecovery: {
        status: 'SUSPENDED',
        title: '等待你补充信息',
        detail: '补充资料库名称后继续。',
        actionLabel: '等待',
        canResume: false,
        canRestart: true,
        reasonType: 'ExternalDataWait',
        reasonSourceId: '__await_user_input__',
        nextActions: ['使用补充名称定位资料库', '继续处理剩余任务'],
      },
    })

    const { sendMessage } = useChat()
    await sendMessage('资料库叫产品资料')

    expect(vi.mocked(chatApi.sendMessageStream)).toHaveBeenCalledWith(
      '资料库叫产品资料',
      'session-1',
      undefined,
      'turn-legacy-await',
      'RESUME',
      expect.any(AbortSignal),
      null,
      undefined,
      expect.objectContaining({
        id: 'task-recovery-user-reply',
        label: '补充后继续',
        mode: 'resume',
        recoveryHint: '补充资料库名称后继续。',
        nextActions: ['使用补充名称定位资料库', '继续处理剩余任务'],
      }),
    )
  })

  it('没有明确等待用户补充的旧挂起不会自动吞掉新问题', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-new-after-legacy-suspended',
            sessionId: 'session-1',
            content: '这是新问题的回答。',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_360_800,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()
    chatStore.addMessage({
      id: 'assistant-legacy-suspended',
      turnId: 'turn-legacy-suspended',
      role: 'assistant',
      content: '任务暂停了。',
      timestamp: 1_741_683_350_800,
      turnStatus: 'SUSPENDED',
      completionMode: 'SUSPENDED',
    })

    const { sendMessage } = useChat()
    await sendMessage('先问一个新的问题')

    expect(vi.mocked(chatApi.sendMessageStream)).toHaveBeenCalledWith(
      '先问一个新的问题',
      'session-1',
      undefined,
      expect.any(String),
      'SEND',
      expect.any(AbortSignal),
      null,
    )
    expect(vi.mocked(chatApi.sendMessageStream)).not.toHaveBeenCalledWith(
      '先问一个新的问题',
      'session-1',
      undefined,
      'turn-legacy-suspended',
      'RESUME',
      expect.any(AbortSignal),
      null,
    )
    const newUser = chatStore.messages.find(message =>
      message.role === 'user' && message.content === '先问一个新的问题')
    expect(newUser?.turnId).not.toBe('turn-legacy-suspended')
  })

  it('用户补充自动续接时携带挂起任务的恢复上下文', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-user-reply-recovered',
            sessionId: 'session-1',
            turnId: 'turn-await-repo',
            content: '已根据仓库地址继续执行。',
            traceId: 'trace-user-reply-recovered',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_361_000,
            turnRecoveryContext: {
              action: 'RESUME',
              sourceTraceId: 'trace-await-repo',
              title: '补充后继续',
              detail: '你直接回复仓库地址，知微会接着当前进度继续。',
              nextActions: ['使用仓库地址准备工作区', '继续执行后续检查'],
            },
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()
    chatStore.addMessage({
      id: 'assistant-await-repo',
      turnId: 'turn-await-repo',
      role: 'assistant',
      content: '请补充仓库地址。',
      timestamp: 1_741_683_350_000,
      turnStatus: 'SUSPENDED',
      completionMode: 'SUSPENDED',
      artifactRefs: [
        {
          artifactId: 'artifact-1',
          fileName: 'repo-notes.md',
          mimeType: 'text/markdown',
          kind: 'FILE',
          size: 128,
          downloadUrl: '/api/artifacts/artifact-1/download',
        },
      ],
      taskRecovery: {
        status: 'SUSPENDED',
        title: '等待你补充信息',
        detail: '你直接回复仓库地址，知微会接着当前进度继续。',
        actionLabel: '等待',
        canResume: false,
        canRestart: true,
        reasonType: 'ExternalDataWait',
        reasonSourceId: '__await_user_input__',
        resumeMode: 'user_reply',
        checkpoint: {
          kind: 'SUSPEND',
          toolId: 'git.clone',
          toolName: '仓库准备',
          executionKind: 'TOOL',
          action: '读取仓库',
          failureCategory: 'UNKNOWN',
          inputSummary: '等待仓库地址',
          outputSummary: '用户尚未提供仓库 URL',
        },
        nextActions: ['使用仓库地址准备工作区', '继续执行后续检查'],
      },
    })

    const { sendMessage } = useChat()
    await sendMessage('仓库地址是 https://github.com/acme/demo.git')

    expect(vi.mocked(chatApi.sendMessageStream)).toHaveBeenCalledWith(
      '仓库地址是 https://github.com/acme/demo.git',
      'session-1',
      undefined,
      'turn-await-repo',
      'RESUME',
      expect.any(AbortSignal),
      null,
      undefined,
      expect.objectContaining({
        id: 'task-recovery-user-reply',
        label: '补充后继续',
        mode: 'resume',
        toolId: 'git.clone',
        toolName: '仓库准备',
        executionKind: 'TOOL',
        action: '读取仓库',
        category: 'UNKNOWN',
        inputSummary: '等待仓库地址',
        outputSummary: '用户尚未提供仓库 URL',
        recoveryHint: '你直接回复仓库地址，知微会接着当前进度继续。',
        artifactRefs: [
          {
            artifactId: 'artifact-1',
            fileName: 'repo-notes.md',
            mimeType: 'text/markdown',
            kind: 'FILE',
            size: 128,
            downloadUrl: '/api/artifacts/artifact-1/download',
          },
        ],
        nextActions: ['使用仓库地址准备工作区', '继续执行后续检查'],
      }),
    )
    const assistant = chatStore.messages.find(message => message.id === 'assistant-user-reply-recovered')
    expect(assistant?.turnRecoveryContext).toMatchObject({
      action: 'RESUME',
      sourceTraceId: 'trace-await-repo',
      title: '补充后继续',
      nextActions: ['使用仓库地址准备工作区', '继续执行后续检查'],
    })
  })

  it('仅有任务恢复状态标记挂起时也会把用户补充接回原轮次', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-task-recovery-only-resumed',
            sessionId: 'session-1',
            turnId: 'turn-task-recovery-only',
            content: '已根据补充信息继续完成。',
            traceId: 'trace-task-recovery-only-resumed',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_361_500,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()
    chatStore.addMessage({
      id: 'assistant-task-recovery-only',
      turnId: 'turn-task-recovery-only',
      role: 'assistant',
      content: '还缺一个发布渠道。',
      timestamp: 1_741_683_350_500,
      taskRecovery: {
        status: 'SUSPENDED',
        title: '等待你补充信息',
        detail: '补充发布渠道后继续。',
        actionLabel: '等待',
        canResume: false,
        canRestart: true,
        reasonType: 'ExternalDataWait',
        reasonSourceId: '__await_user_input__',
        nextActions: ['根据发布渠道调整文案', '继续生成最终版本'],
      },
    })

    const { sendMessage } = useChat()
    await sendMessage('发布到公众号')

    expect(vi.mocked(chatApi.sendMessageStream)).toHaveBeenCalledWith(
      '发布到公众号',
      'session-1',
      undefined,
      'turn-task-recovery-only',
      'RESUME',
      expect.any(AbortSignal),
      null,
      undefined,
      expect.objectContaining({
        id: 'task-recovery-user-reply',
        label: '补充后继续',
        mode: 'resume',
        recoveryHint: '补充发布渠道后继续。',
        nextActions: ['根据发布渠道调整文案', '继续生成最终版本'],
      }),
    )
    const resumedUser = chatStore.messages.find(message =>
      message.role === 'user' && message.content === '发布到公众号')
    expect(resumedUser?.turnId).toBe('turn-task-recovery-only')
  })

  it('用户补充自动续接时从旧工具摘要兜底带上技能断点', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-user-reply-skill-recovered',
            sessionId: 'session-1',
            turnId: 'turn-await-skill',
            content: '已重新加载技能并继续执行。',
            traceId: 'trace-user-reply-skill-recovered',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_362_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()
    chatStore.addMessage({
      id: 'assistant-await-skill',
      turnId: 'turn-await-skill',
      role: 'assistant',
      content: '请补充 skill 文件后继续。',
      timestamp: 1_741_683_351_000,
      turnStatus: 'SUSPENDED',
      completionMode: 'SUSPENDED',
      suspendReasonSourceId: '__await_user_input__',
      toolsSummary: [
        {
          toolId: 'skill.load',
          toolName: '加载 Skill',
          executionKind: 'SKILL',
          status: 'FAILED',
          success: false,
          latencyMs: 12,
          action: '加载技能',
          failureCategory: 'SKILL',
          subjectLabel: '技能',
          subjectNames: ['research-assistant'],
          inputSummary: '加载技能「research-assistant」',
          outputSummary: '技能 research-assistant 不存在',
          artifactRefs: [
            {
              id: 'artifact-skill-log',
              filename: 'skill-load.log',
              contentType: 'text/plain',
              type: 'FILE',
              size: '128',
              url: '/api/artifacts/artifact-skill-log/download',
            },
          ],
        },
      ],
    } as any)

    const { sendMessage } = useChat()
    await sendMessage('skill 文件已经补上了')

    expect(vi.mocked(chatApi.sendMessageStream)).toHaveBeenCalledWith(
      'skill 文件已经补上了',
      'session-1',
      undefined,
      'turn-await-skill',
      'RESUME',
      expect.any(AbortSignal),
      null,
      undefined,
      expect.objectContaining({
        id: 'task-recovery-user-reply',
        label: '检查技能后继续',
        mode: 'resume',
        toolId: 'skill.load',
        toolName: '加载 Skill',
        executionKind: 'SKILL',
        action: '加载技能',
        category: 'SKILL',
        subjectLabel: '技能',
        subjectNames: ['research-assistant'],
        inputSummary: '加载技能「research-assistant」',
        outputSummary: '技能 research-assistant 不存在',
        artifactRefs: [
          {
            artifactId: 'artifact-skill-log',
            fileName: 'skill-load.log',
            mimeType: 'text/plain',
            kind: 'FILE',
            size: 128,
            downloadUrl: '/api/artifacts/artifact-skill-log/download',
          },
        ],
        recoveryHint: '技能加载没有完成，可以检查技能名称或依赖后继续。',
        nextActions: ['确认技能 research-assistant 的名称和依赖是否可用', '重新加载技能后继续当前任务'],
      }),
    )
  })

  it('非用户补充型挂起不会自动吞掉新的用户问题', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-new-after-browser-wait',
            sessionId: 'session-1',
            content: '这是新问题的回答。',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_365_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()
    chatStore.addMessage({
      id: 'assistant-browser-wait',
      turnId: 'turn-browser-wait',
      role: 'assistant',
      content: '等待浏览器操作完成。',
      timestamp: 1_741_683_350_000,
      turnStatus: 'SUSPENDED',
      completionMode: 'SUSPENDED',
      suspendReasonType: 'BrowserTakeover',
      taskRecovery: {
        status: 'SUSPENDED',
        title: '等待浏览器操作',
        detail: '完成浏览器里的操作后，可以回来继续当前任务。',
        actionLabel: '继续',
        canResume: true,
        canRestart: true,
        reasonType: 'BrowserTakeover',
        reasonSourceId: 'browser-session-1',
        resumeMode: 'browser',
      },
    })

    const { sendMessage } = useChat()
    await sendMessage('先问一个新的问题')

    expect(vi.mocked(chatApi.sendMessageStream)).toHaveBeenCalledWith(
      '先问一个新的问题',
      'session-1',
      undefined,
      expect.any(String),
      'SEND',
      expect.any(AbortSignal),
      null,
    )
    expect(vi.mocked(chatApi.sendMessageStream)).not.toHaveBeenCalledWith(
      '先问一个新的问题',
      'session-1',
      undefined,
      'turn-browser-wait',
      'RESUME',
      expect.any(AbortSignal),
      null,
    )
    const newUser = chatStore.messages.find(message =>
      message.role === 'user' && message.content === '先问一个新的问题')
    expect(newUser?.turnId).not.toBe('turn-browser-wait')
  })

  it('按钮恢复携带内部说明时不覆盖原始用户消息', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-resume-action',
            sessionId: 'session-1',
            turnId: 'turn-action',
            content: '已从失败命令后继续完成。',
            traceId: 'trace-resume-action',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_370_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()
    chatStore.addMessage({
      id: 'user-action',
      turnId: 'turn-action',
      role: 'user',
      content: '帮我跑测试',
      timestamp: 1_741_683_360_000,
    })

    const { executeTurn } = useChat()
    await executeTurn('turn-action', 'RESUME', {
      content: '继续执行：修正后继续。目标：Shell 执行（命令执行）。',
      userMessageId: 'user-action',
    })

    expect(vi.mocked(chatApi.sendMessageStream)).toHaveBeenCalledWith(
      '继续执行：修正后继续。目标：Shell 执行（命令执行）。',
      'session-1',
      undefined,
      'turn-action',
      'RESUME',
      expect.any(AbortSignal),
      null,
    )
    expect(chatStore.messages.find(message => message.id === 'user-action')?.content).toBe('帮我跑测试')
  })

  it('结构化恢复动作会独立传给后端且不污染用户消息', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-recovery-action',
            sessionId: 'session-1',
            turnId: 'turn-action',
            content: '已从断点继续完成。',
            traceId: 'trace-recovery-action',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_370_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()
    chatStore.addMessage({
      id: 'user-action',
      turnId: 'turn-action',
      role: 'user',
      content: '帮我跑测试',
      timestamp: 1_741_683_360_000,
    })

    const recoveryAction = {
      id: 'resume',
      label: '修正后继续',
      mode: 'resume' as const,
      category: 'COMMAND' as const,
      toolId: 'shell.exec',
      callId: 'call-shell-1',
      toolName: 'Shell 执行',
      interrupted: true,
      outputSummary: '测试失败',
      nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证'],
    }
    const { executeTurn } = useChat()
    await executeTurn('turn-action', 'RESUME', {
      userMessageId: 'user-action',
      preserveUserMessageContent: true,
      recoveryAction,
    })

    expect(vi.mocked(chatApi.sendMessageStream)).toHaveBeenCalledWith(
      '',
      'session-1',
      undefined,
      'turn-action',
      'RESUME',
      expect.any(AbortSignal),
      null,
      undefined,
      recoveryAction,
    )
    expect(chatStore.messages.find(message => message.id === 'user-action')?.content).toBe('帮我跑测试')
  })

  it('手动继续时先移除上一条失败助手消息并进入执行态', async () => {
    const manualStream = createManualSseStream()
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(manualStream.stream)

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()
    chatStore.addMessage({
      id: 'user-action',
      turnId: 'turn-action',
      role: 'user',
      content: '帮我跑测试',
      timestamp: 1_741_683_360_000,
    })
    chatStore.addMessage({
      id: 'assistant-failed-before-resume',
      turnId: 'turn-action',
      role: 'assistant',
      content: '测试失败，可以继续。',
      timestamp: 1_741_683_361_000,
      turnStatus: 'DEGRADED',
      taskRecovery: {
        status: 'DEGRADED',
        title: '验证没有完成',
        detail: '测试失败，可以修正后继续。',
        actionLabel: '继续',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
      },
    })

    const { executeTurn, isStreaming } = useChat()
    const pending = executeTurn('turn-action', 'RESUME', {
      userMessageId: 'user-action',
      preserveUserMessageContent: true,
      recoveryAction: {
        id: 'resume',
        label: '修正后继续',
        mode: 'resume',
        category: 'COMMAND',
        toolId: 'shell.exec',
        outputSummary: '测试失败',
      },
    })
    await flushUi()

    expect(isStreaming.value).toBe(true)
    expect(chatStore.messages.find(message => message.id === 'assistant-failed-before-resume')).toBeUndefined()
    expect(chatStore.messages.find(message => message.id === 'user-action')).toMatchObject({
      content: '帮我跑测试',
      status: 'pending',
      turnStatus: 'PENDING',
    })

    manualStream.push({
      type: SSE_EVENT_TYPES.DONE,
      payload: {
        entryId: 'assistant-after-resume',
        sessionId: 'session-1',
        turnId: 'turn-action',
        content: '已从失败点继续完成。',
        traceId: 'trace-after-resume',
        turnStatus: 'SUCCESS',
        timestamp: 1_741_683_370_000,
      },
    })
    manualStream.close()
    await pending

    expect(chatStore.messages.find(message => message.id === 'assistant-after-resume')?.content).toBe('已从失败点继续完成。')
    expect(chatStore.messages.find(message => message.id === 'assistant-failed-before-resume')).toBeUndefined()
  })

  it('按钮重新开始携带内部说明时不覆盖原始用户消息', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-restart-action',
            sessionId: 'session-1',
            turnId: 'turn-action',
            content: '已重新执行并完成。',
            traceId: 'trace-restart-action',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_380_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()
    chatStore.addMessage({
      id: 'user-action',
      turnId: 'turn-action',
      role: 'user',
      content: '帮我跑测试',
      timestamp: 1_741_683_360_000,
    })

    const { executeTurn } = useChat()
    await executeTurn('turn-action', 'RESTART', {
      content: '重新开始：重新开始。目标：Shell 执行（命令执行）。',
      visibleContent: '帮我跑测试',
      userMessageId: 'user-action',
      preserveUserMessageContent: true,
    })

    expect(vi.mocked(chatApi.sendMessageStream)).toHaveBeenCalledWith(
      '重新开始：重新开始。目标：Shell 执行（命令执行）。',
      'session-1',
      undefined,
      'turn-action',
      'RESTART',
      expect.any(AbortSignal),
      null,
      '帮我跑测试',
    )
    expect(chatStore.messages.find(message => message.id === 'user-action')?.content).toBe('帮我跑测试')
  })

  it('重新开始没有既有用户消息时只新增可见原始问题', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-restart-orphan',
            sessionId: 'session-1',
            turnId: 'turn-orphan',
            content: '已重新执行并完成。',
            traceId: 'trace-restart-orphan',
            turnStatus: 'SUCCESS',
            timestamp: 1_741_683_385_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { executeTurn, lastPrompt } = useChat()
    await executeTurn('turn-orphan', 'RESTART', {
      content: '重新开始：重新开始。目标：Shell 执行（命令执行）。',
      visibleContent: '帮我跑测试',
      preserveUserMessageContent: true,
    })

    expect(vi.mocked(chatApi.sendMessageStream)).toHaveBeenCalledWith(
      '重新开始：重新开始。目标：Shell 执行（命令执行）。',
      'session-1',
      undefined,
      'turn-orphan',
      'RESTART',
      expect.any(AbortSignal),
      null,
      '帮我跑测试',
    )
    const user = chatStore.messages.find(message => message.role === 'user' && message.turnId === 'turn-orphan')
    expect(user?.content).toBe('帮我跑测试')
    expect(user?.content).not.toContain('重新开始：重新开始')
    expect(lastPrompt.value).toBe('帮我跑测试')
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
      null,
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

  it('流式 ReAct 事件会保留恢复需要的工具和技能字段', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.REASONING,
          payload: {
            sessionId: 'session-1',
            turnId: 'turn-streaming-step-fields',
            event: {
              id: 'evt-tool-call',
              type: 'TOOL_CALL',
              title: '调用工具: 加载 Skill',
              description: '正在执行工具 加载 Skill',
              toolName: '加载 Skill',
              createdAt: '2026-04-27T00:00:00.000Z',
              extra: {
                stepIndex: 0,
                toolId: 'skill.load',
                toolName: '加载 Skill',
                callId: 'call-skill-load-1',
                inputSummary: '加载技能「research-assistant」',
                latencyMs: 12,
                subjectLabel: '技能',
                subjectNames: ['research-assistant'],
              },
            },
          },
        },
        {
          type: SSE_EVENT_TYPES.REASONING,
          payload: {
            sessionId: 'session-1',
            turnId: 'turn-streaming-step-fields',
            event: {
              id: 'evt-observation',
              type: 'OBSERVATION',
              title: '工具失败: 加载 Skill',
              description: '技能 research-assistant 不存在',
              toolName: '加载 Skill',
              createdAt: '2026-04-27T00:00:01.000Z',
              extra: {
                stepIndex: 1,
                toolId: 'skill.load',
                toolName: '加载 Skill',
                callId: 'call-skill-load-1',
                success: false,
                outputSummary: '技能 research-assistant 不存在',
                outputDetail: '没有找到名为 research-assistant 的技能。',
                tokensUsed: 0,
                subjectLabel: '技能',
                subjectNames: ['research-assistant'],
              },
            },
          },
        },
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-streaming-step-fields',
            sessionId: 'session-1',
            turnId: 'turn-streaming-step-fields',
            content: '技能没有加载成功，可以检查后继续。',
            turnStatus: 'DEGRADED',
            completionMode: 'DEGRADED',
            timestamp: 1_745_000_100_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('加载 research-assistant 技能')

    const assistant = chatStore.messages.find(message => message.id === 'assistant-streaming-step-fields')
    expect(assistant?.reactSteps).toEqual([
      expect.objectContaining({
        type: 'TOOL_CALL',
        index: 0,
        toolId: 'skill.load',
        toolName: '加载 Skill',
        callId: 'call-skill-load-1',
        inputSummary: '加载技能「research-assistant」',
        latencyMs: 12,
        subjectLabel: '技能',
        subjectNames: ['research-assistant'],
      }),
      expect.objectContaining({
        type: 'OBSERVATION',
        index: 1,
        toolId: 'skill.load',
        toolName: '加载 Skill',
        callId: 'call-skill-load-1',
        success: false,
        outputSummary: '技能 research-assistant 不存在',
        outputDetail: '没有找到名为 research-assistant 的技能。',
        subjectLabel: '技能',
        subjectNames: ['research-assistant'],
      }),
    ])
  })

  it('流式 ReAct 事件会归一化 snake_case 工具字段', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.REASONING,
          payload: {
            sessionId: 'session-1',
            turnId: 'turn-streaming-snake-case-fields',
            event: {
              id: 'evt-tool-call-snake',
              type: 'TOOL_CALL',
              title: '调用工具: Shell 执行',
              description: '正在执行命令',
              toolName: 'Shell 执行',
              createdAt: '2026-04-27T00:00:00.000Z',
              extra: {
                step_index: 0,
                tool_id: 'shell.exec',
                tool_name: 'Shell 执行',
                call_id: 'call-shell-snake-1',
                input_summary: '执行 `npm test`',
                latency_ms: 32,
                subject_label: '命令',
                subject_names: ['npm test'],
              },
            },
          },
        },
        {
          type: SSE_EVENT_TYPES.REASONING,
          payload: {
            sessionId: 'session-1',
            turnId: 'turn-streaming-snake-case-fields',
            event: {
              id: 'evt-observation-snake',
              type: 'OBSERVATION',
              title: '工具失败: Shell 执行',
              description: '测试失败',
              toolName: 'Shell 执行',
              createdAt: '2026-04-27T00:00:01.000Z',
              extra: {
                step_index: 1,
                tool_id: 'shell.exec',
                tool_name: 'Shell 执行',
                call_id: 'call-shell-snake-1',
                success: false,
                output_summary: '测试失败',
                output_detail: 'AssertionError: expected true to be false',
                tokens_used: 0,
                working_directory: 'D:\\WorkSpace\\Project\\News',
                generated_file_path: 'D:\\WorkSpace\\Project\\News\\report.txt',
                subject_label: '命令',
                subject_names: ['npm test'],
              },
            },
          },
        },
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-streaming-snake-case-fields',
            sessionId: 'session-1',
            turnId: 'turn-streaming-snake-case-fields',
            content: '命令没有完成，可以检查后继续。',
            turnStatus: 'DEGRADED',
            completionMode: 'DEGRADED',
            timestamp: 1_745_000_110_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('运行测试')

    const assistant = chatStore.messages.find(message => message.id === 'assistant-streaming-snake-case-fields')
    expect(assistant?.reactSteps).toEqual([
      expect.objectContaining({
        type: 'TOOL_CALL',
        index: 0,
        toolId: 'shell.exec',
        toolName: 'Shell 执行',
        callId: 'call-shell-snake-1',
        inputSummary: '执行 `npm test`',
        latencyMs: 32,
        subjectLabel: '命令',
        subjectNames: ['npm test'],
      }),
      expect.objectContaining({
        type: 'OBSERVATION',
        index: 1,
        toolId: 'shell.exec',
        toolName: 'Shell 执行',
        callId: 'call-shell-snake-1',
        success: false,
        outputSummary: '测试失败',
        outputDetail: 'AssertionError: expected true to be false',
        workingDirectory: 'D:\\WorkSpace\\Project\\News',
        generatedFilePath: 'D:\\WorkSpace\\Project\\News\\report.txt',
        subjectLabel: '命令',
        subjectNames: ['npm test'],
      }),
    ])
  })

  it('DONE事件中的产物引用应沉淀到最终消息并与流式产物去重', async () => {
    vi.mocked(chatApi.sendMessageStream).mockResolvedValue(
      createSseStream([
        {
          type: SSE_EVENT_TYPES.ARTIFACT_REF,
          payload: {
            artifactId: 'artifact-1',
            fileName: 'report.md',
            mimeType: 'text/markdown',
            kind: 'FILE',
            size: 128,
            downloadUrl: '/api/artifacts/artifact-1/download',
          },
        },
        {
          type: SSE_EVENT_TYPES.DONE,
          payload: {
            entryId: 'assistant-with-artifacts',
            sessionId: 'session-1',
            turnId: 'turn-with-artifacts',
            content: '报告已生成。',
            turnStatus: 'SUCCESS',
            completionMode: 'NORMAL',
            artifactRefs: [
              {
                artifactId: 'artifact-1',
                fileName: 'report.md',
                mimeType: 'text/markdown',
                kind: 'FILE',
                size: 128,
                downloadUrl: '/api/artifacts/artifact-1/download',
              },
              {
                artifactId: 'artifact-2',
                fileName: 'chart.png',
                mimeType: 'image/png',
                kind: 'IMAGE',
                size: 256,
                downloadUrl: '/api/artifacts/artifact-2/download',
              },
            ],
            timestamp: 1_745_000_120_000,
          },
        },
      ]),
    )

    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await flushUi()

    const { sendMessage } = useChat()
    await sendMessage('生成报告')

    const assistant = chatStore.messages.find(message => message.id === 'assistant-with-artifacts')
    expect(assistant?.artifactRefs).toEqual([
      expect.objectContaining({
        artifactId: 'artifact-1',
        fileName: 'report.md',
      }),
      expect.objectContaining({
        artifactId: 'artifact-2',
        fileName: 'chart.png',
        kind: 'IMAGE',
      }),
    ])
  })
})
