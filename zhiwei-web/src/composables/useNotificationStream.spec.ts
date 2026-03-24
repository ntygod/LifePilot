import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, nextTick } from 'vue'
import { useNotificationStream } from '@/composables/useNotificationStream'
import { useChatStore } from '@/stores/chat'
import { chatApi } from '@/api/client'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'

vi.mock('@/api/client', () => ({
  chatApi: {
    getSessionMessages: vi.fn(),
  },
  notificationApi: {
    listNotifications: vi.fn(),
    markAsRead: vi.fn(),
    markAllAsRead: vi.fn(),
  },
}))

class MockEventSource {
  static instances: MockEventSource[] = []

  readonly listeners = new Map<string, Set<(event: MessageEvent) => void>>()
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  closed = false

  constructor(public readonly url: string) {
    MockEventSource.instances.push(this)
  }

  addEventListener(type: string, handler: (event: MessageEvent) => void) {
    const handlers = this.listeners.get(type) ?? new Set()
    handlers.add(handler)
    this.listeners.set(type, handlers)
  }

  emit(type: string, payload: unknown) {
    const handlers = this.listeners.get(type)
    if (!handlers) return
    const event = { data: JSON.stringify(payload) } as MessageEvent
    handlers.forEach(handler => handler(event))
  }

  close() {
    this.closed = true
  }
}

const TestComponent = defineComponent({
  name: 'UseNotificationStreamHarness',
  setup() {
    useNotificationStream()
    return () => null
  },
})

describe('useNotificationStream', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    MockEventSource.instances = []
    vi.stubGlobal('EventSource', MockEventSource)
    vi.mocked(chatApi.getSessionMessages).mockResolvedValue([])
  })

  it('updates the latest pending user message when a transcription event arrives for the active session', async () => {
    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await nextTick()
    chatStore.addMessage({
      id: 'user-temp-1',
      role: 'user',
      content: '',
      timestamp: Date.now(),
      status: 'pending',
    })

    const wrapper = mount(TestComponent)

    const source = MockEventSource.instances[0]
    expect(source?.url).toBe('/api/notifications/stream?userId=default')

    source.emit(SSE_EVENT_TYPES.TRANSCRIPTION, {
      sessionId: 'session-1',
      text: '这是转录后的文本',
    })

    expect(chatStore.messages[0]?.content).toBe('这是转录后的文本')

    wrapper.unmount()
  })

  it('ignores transcription events for other sessions', async () => {
    const chatStore = useChatStore()
    chatStore.activeSessionId = 'session-1'
    await nextTick()
    chatStore.addMessage({
      id: 'user-temp-1',
      role: 'user',
      content: '原始内容',
      timestamp: Date.now(),
      status: 'pending',
    })

    const wrapper = mount(TestComponent)

    const source = MockEventSource.instances[0]
    source.emit(SSE_EVENT_TYPES.TRANSCRIPTION, {
      sessionId: 'session-2',
      text: '不应覆盖',
    })

    expect(chatStore.messages[0]?.content).toBe('原始内容')

    wrapper.unmount()
  })
})
