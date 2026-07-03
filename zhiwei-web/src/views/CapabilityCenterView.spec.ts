import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import CapabilityCenterView from './CapabilityCenterView.vue'

const mocks = vi.hoisted(() => ({
  routerPush: vi.fn(),
  chatStore: {
    activeSessionId: 'session-old' as string | null,
    pendingFirstMessage: null as string | null,
    pendingDraftMessage: null as string | null,
  },
  memoryStore: {
    stats: {
      entityCount: 0,
      relationCount: 0,
      conversationCount: 0,
      templateCount: 0,
      preferenceCount: 0,
    },
    loadStats: vi.fn(),
  },
  knowledgeBaseStore: {
    list: [] as Array<{ id: string; documentCount: number }>,
    fetchList: vi.fn(),
  },
  proactiveStore: {
    config: { enabled: false },
    queueCount: 0,
    pendingUpgrades: [] as Array<{ behaviorName: string }>,
    fetchConfig: vi.fn(),
    fetchQueue: vi.fn(),
    fetchTrustStatus: vi.fn(),
  },
  toolStore: {
    tools: [] as Array<{ id: string }>,
    fetchTools: vi.fn(),
  },
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({
    push: mocks.routerPush,
  }),
}))

vi.mock('@/stores/chat', () => ({
  useChatStore: () => mocks.chatStore,
}))

vi.mock('@/stores/memory', () => ({
  useMemoryStore: () => mocks.memoryStore,
}))

vi.mock('@/stores/knowledgeBase', () => ({
  useKnowledgeBaseStore: () => mocks.knowledgeBaseStore,
}))

vi.mock('@/stores/proactive', () => ({
  useProactiveStore: () => mocks.proactiveStore,
}))

vi.mock('@/stores/tool', () => ({
  useToolStore: () => mocks.toolStore,
}))

function mountView() {
  return shallowMount(CapabilityCenterView, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        Badge: { template: '<span><slot /></span>' },
        Button: {
          template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>',
        },
        PageContainer: { template: '<div><slot /></div>' },
        PageHeader: { template: '<header><slot name="actions" /><slot /></header>' },
        Skeleton: { template: '<div />' },
        StatePanel: { template: '<section><slot name="actions" /><slot /></section>' },
      },
    },
  })
}

beforeEach(() => {
  mocks.routerPush.mockReset()
  mocks.chatStore.activeSessionId = 'session-old'
  mocks.chatStore.pendingFirstMessage = null
  mocks.chatStore.pendingDraftMessage = null
  mocks.memoryStore.stats = {
    entityCount: 0,
    relationCount: 0,
    conversationCount: 0,
    templateCount: 0,
    preferenceCount: 0,
  }
  mocks.knowledgeBaseStore.list = []
  mocks.proactiveStore.config = { enabled: false }
  mocks.proactiveStore.queueCount = 0
  mocks.proactiveStore.pendingUpgrades = []
  mocks.toolStore.tools = []
  mocks.memoryStore.loadStats.mockReset().mockResolvedValue(undefined)
  mocks.knowledgeBaseStore.fetchList.mockReset().mockResolvedValue(undefined)
  mocks.proactiveStore.fetchConfig.mockReset().mockResolvedValue(undefined)
  mocks.proactiveStore.fetchQueue.mockReset().mockResolvedValue(undefined)
  mocks.proactiveStore.fetchTrustStatus.mockReset().mockResolvedValue(undefined)
  mocks.toolStore.fetchTools.mockReset().mockResolvedValue(undefined)
})

describe('CapabilityCenterView', () => {
  it('挂载后加载能力概览数据', async () => {
    mountView()
    await flushPromises()

    expect(mocks.memoryStore.loadStats).toHaveBeenCalled()
    expect(mocks.knowledgeBaseStore.fetchList).toHaveBeenCalled()
    expect(mocks.proactiveStore.fetchConfig).toHaveBeenCalled()
    expect(mocks.toolStore.fetchTools).toHaveBeenCalled()
  })

  it('会按当前状态展示下一步建议', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('先建立你的长期上下文')
    expect(wrapper.text()).toContain('给项目喂资料')
    expect(wrapper.text()).toContain('开启可控主动性')
  })

  it('点击场景按钮会把提示词带入新对话', async () => {
    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find(item => item.text().includes('开始校准'))
    expect(button).toBeTruthy()
    await button!.trigger('click')

    expect(mocks.chatStore.activeSessionId).toBeNull()
    expect(mocks.chatStore.pendingFirstMessage).toBeNull()
    expect(mocks.chatStore.pendingDraftMessage).toContain('审计你对我的理解')
    expect(mocks.routerPush).toHaveBeenCalledWith({ name: 'newConversation' })
  })
})
