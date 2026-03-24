import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { chatApi } from '@/api/client'
import { useA2uiSignal } from '@/composables/useA2uiSignal'
import { useA2uiStore } from '@/stores/a2ui'
import { useChatStore } from '@/stores/chat'
import { extractA2uiComponents, mapBackendMessage } from '@/utils/a2ui'
import type { A2uiComponent } from '@/types'

vi.mock('@/api/client', () => ({
  chatApi: {
    sendSignal: vi.fn(),
    getSessionMessages: vi.fn(),
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

describe('extractA2uiComponents', () => {
  it('reads the standard a2uiComponents field', () => {
    const components = [createComponent('root')]
    expect(extractA2uiComponents({ a2uiComponents: components })).toEqual(components)
  })

  it('ignores non-standard payload shapes', () => {
    const nestedComponents = [createComponent('nested', 'Card')]

    expect(extractA2uiComponents({ components: nestedComponents })).toBeNull()
    expect(extractA2uiComponents({ a2ui: { components: nestedComponents } })).toBeNull()
    expect(extractA2uiComponents({ message: { a2uiComponents: nestedComponents } })).toBeNull()
  })

  it('ignores invalid shapes safely', () => {
    expect(extractA2uiComponents(null)).toBeNull()
    expect(extractA2uiComponents({ a2uiComponents: [{ id: 'broken' }] })).toBeNull()
    expect(extractA2uiComponents({ a2uiComponents: 'not-an-array' })).toBeNull()
  })
})

describe('mapBackendMessage', () => {
  it('maps persisted history payload into the chat message shape', () => {
    const message = mapBackendMessage({
      id: 'm-1',
      role: 'assistant',
      content: '已为你生成面板',
      a2uiComponents: [createComponent('card-1', 'Card')],
      timestamp: '2026-03-11T08:00:00Z',
      reasoningSummary: '生成了一个结构化结果',
      traceId: 'trace-1',
    })

    expect(message.id).toBe('m-1')
    expect(message.timestamp).toBe(Date.parse('2026-03-11T08:00:00Z'))
    expect(message.a2uiComponents?.[0]?.id).toBe('card-1')
    expect(message.traceId).toBe('trace-1')
  })
})

describe('useA2uiSignal', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('writes assistant replies back into the chat list', async () => {
    vi.mocked(chatApi.sendSignal).mockResolvedValue({
      entryId: 'assistant-2',
      content: '好的，已更新面板',
      a2uiComponents: [createComponent('card-2', 'Card')],
      traceId: 'trace-2',
    })

    const chatStore = useChatStore()
    const a2uiStore = useA2uiStore()
    const { emitSignal, getSignalState } = useA2uiSignal()

    await emitSignal(
      { name: 'panel.refresh', payload: { section: 'todos' } },
      'session-1',
      { componentId: 'btn-1', entryId: 'assistant-1', traceId: 'trace-1' },
    )

    expect(chatStore.messages).toHaveLength(1)
    expect(chatStore.messages[0]).toMatchObject({
      id: 'assistant-2',
      role: 'assistant',
      content: '好的，已更新面板',
      traceId: 'trace-2',
    })
    expect(chatStore.messages[0]?.a2uiComponents?.[0]?.id).toBe('card-2')
    expect(a2uiStore.currentTraceId).toBe('trace-2')
    expect(getSignalState({
      componentId: 'btn-1',
      entryId: 'assistant-1',
      signalName: 'panel.refresh',
    })?.status).toBe('success')

    vi.runAllTimers()

    expect(getSignalState({
      componentId: 'btn-1',
      entryId: 'assistant-1',
      signalName: 'panel.refresh',
    })).toBeUndefined()
  })

  it('updates the originating message when a signal response only returns components', async () => {
    vi.mocked(chatApi.sendSignal).mockResolvedValue({
      entryId: 'assistant-2',
      content: '',
      a2uiComponents: [createComponent('updated-card', 'Card')],
      traceId: 'trace-2',
    })

    const chatStore = useChatStore()
    chatStore.addMessage({
      id: 'assistant-1',
      role: 'assistant',
      content: '当前面板',
      a2uiComponents: [createComponent('old-card', 'Card')],
      timestamp: Date.now(),
      traceId: 'trace-1',
    })

    const { emitSignal } = useA2uiSignal()

    await emitSignal(
      { name: 'panel.replace', payload: { section: 'todos' } },
      'session-1',
      { componentId: 'btn-1', entryId: 'assistant-1', traceId: 'trace-1' },
    )

    expect(chatStore.messages).toHaveLength(1)
    expect(chatStore.messages[0]?.id).toBe('assistant-1')
    expect(chatStore.messages[0]?.a2uiComponents?.[0]?.id).toBe('updated-card')
  })
})
