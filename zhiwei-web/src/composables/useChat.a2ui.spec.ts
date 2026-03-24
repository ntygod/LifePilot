import { beforeEach, describe, expect, it, vi } from 'vitest'
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

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
}

describe('useChat A2UI integration', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(chatApi.getSessionMessages).mockResolvedValue([])
    vi.mocked(chatApi.updateSessionConfig).mockResolvedValue(undefined)
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
      undefined,
      expect.any(AbortSignal),
    )
    expect(chatStore.messages.find(message => message.id === 'assistant-attachment')?.content).toBe('已处理附件')
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
})
