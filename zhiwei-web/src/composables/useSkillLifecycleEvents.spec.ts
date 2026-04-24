import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'
import { useSkillLifecycleEvents } from '@/composables/useSkillLifecycleEvents'
import { useSkillStore } from '@/stores/skill'
import { useUiStore } from '@/stores/ui'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'
import { skillApi } from '@/api/client'

vi.mock('@/api/client', () => ({
  skillApi: {
    list: vi.fn(),
  },
  mcpApi: {
    listServers: vi.fn(),
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
  name: 'UseSkillLifecycleEventsHarness',
  setup() {
    useSkillLifecycleEvents()
    return () => null
  },
})

describe('useSkillLifecycleEvents', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    MockEventSource.instances = []
    vi.stubGlobal('EventSource', MockEventSource)
    vi.mocked(skillApi.list).mockReset()
    vi.mocked(skillApi.list).mockResolvedValue([])
  })

  it('连接到 /api/skills/events 端点', () => {
    const wrapper = mount(TestComponent)

    const source = MockEventSource.instances[0]
    expect(source?.url).toBe('/api/skills/events')

    wrapper.unmount()
  })

  it('收到 SKILL_GENERATED 事件时弹 toast 并刷新技能列表', () => {
    const wrapper = mount(TestComponent)
    const uiStore = useUiStore()
    const showToastSpy = vi.spyOn(uiStore, 'showToast')

    const source = MockEventSource.instances[0]
    source.emit(SSE_EVENT_TYPES.SKILL_GENERATED, {
      type: 'SKILL_GENERATED',
      skillName: 'daily-summary',
      sourceType: 'AUTO_GENERATED',
      at: '2026-04-24T10:00:00Z',
    })

    expect(showToastSpy).toHaveBeenCalledWith(
      'success',
      'ZhiWei 已为你生成技能：daily-summary',
    )
    expect(skillApi.list).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  it('payload.type 不匹配时忽略事件', () => {
    const wrapper = mount(TestComponent)
    const uiStore = useUiStore()
    const showToastSpy = vi.spyOn(uiStore, 'showToast')

    const source = MockEventSource.instances[0]
    source.emit(SSE_EVENT_TYPES.SKILL_GENERATED, {
      type: 'SKILL_IMPORTED',
      skillName: 'x',
      sourceType: 'USER_IMPORTED',
      at: '2026-04-24T10:00:00Z',
    })

    expect(showToastSpy).not.toHaveBeenCalled()
    expect(skillApi.list).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('组件卸载时关闭 EventSource 连接', () => {
    const wrapper = mount(TestComponent)
    const source = MockEventSource.instances[0]

    wrapper.unmount()

    expect(source.closed).toBe(true)
  })

  it('消费不合法 JSON 时不抛异常', () => {
    const wrapper = mount(TestComponent)
    const source = MockEventSource.instances[0]

    // 直接注入畸形 data 绕过 emit 的 JSON.stringify
    const handlers = source.listeners.get(SSE_EVENT_TYPES.SKILL_GENERATED)
    expect(handlers).toBeDefined()
    expect(() => {
      handlers?.forEach(handler => handler({ data: 'not json' } as MessageEvent))
    }).not.toThrow()

    // 使用 useSkillStore 避免 unused import 警告
    const skillStore = useSkillStore()
    expect(skillStore.skills).toEqual([])

    wrapper.unmount()
  })
})
