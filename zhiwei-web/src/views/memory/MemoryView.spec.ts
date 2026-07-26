import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import MemoryView from './MemoryView.vue'

const mocks = vi.hoisted(() => ({
  loadStats: vi.fn(),
  search: vi.fn(),
  triggerConsolidation: vi.fn(),
  route: {
    query: {} as Record<string, unknown>,
  },
  store: {
    stats: null as unknown,
    statsLoading: false,
    statsError: null as string | null,
    activeTab: 'entities',
    consolidating: false,
    memoryDisabled: false,
    entityDetailRequest: null as { id: string; requestedAt: number } | null,
    loadStats: undefined as unknown,
    search: undefined as unknown,
    triggerConsolidation: undefined as unknown,
    requestEntityDetail: undefined as unknown,
    clearEntityDetailRequest: undefined as unknown,
  },
}))

mocks.store.loadStats = mocks.loadStats
mocks.store.search = mocks.search
mocks.store.triggerConsolidation = mocks.triggerConsolidation
mocks.store.requestEntityDetail = (id: string) => {
  mocks.store.activeTab = 'entities'
  mocks.store.entityDetailRequest = { id, requestedAt: Date.now() }
}
mocks.store.clearEntityDetailRequest = () => {
  mocks.store.entityDetailRequest = null
}

vi.mock('@/stores/memory', () => ({
  useMemoryStore: () => mocks.store,
}))

vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router')
  return {
    ...actual,
    useRoute: () => mocks.route,
  }
})

function mountView() {
  return shallowMount(MemoryView, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        Badge: { template: '<span><slot /></span>' },
        Button: {
          template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>',
        },
        Input: {
          props: ['modelValue'],
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" @keydown.enter="$emit(\'keydown.enter\', $event)" />',
        },
        Skeleton: { template: '<div />' },
        Tabs: { template: '<div><slot /></div>' },
        TabsList: { template: '<div><slot /></div>' },
        TabsTrigger: { template: '<button><slot /></button>' },
        TabsContent: { template: '<div><slot /></div>' },
        PageContainer: { template: '<div><slot /></div>' },
        PageHeader: { template: '<div><slot name="actions" /><slot name="meta" /><slot /></div>' },
        MetricCard: { template: '<div><slot name="icon" /><slot /></div>' },
        EntityPanel: { template: '<div />' },
        RelationPanel: { template: '<div />' },
        ConversationPanel: { template: '<div />' },
        TemplatePanel: { template: '<div />' },
        PreferencePanel: { template: '<div />' },
        ForgettingLogPanel: { template: '<div />' },
      },
    },
  })
}

beforeEach(() => {
  mocks.loadStats.mockReset()
  mocks.search.mockReset()
  mocks.triggerConsolidation.mockReset()
  mocks.store.stats = null
  mocks.store.statsLoading = false
  mocks.store.memoryDisabled = false
  mocks.store.activeTab = 'entities'
  mocks.store.entityDetailRequest = null
  mocks.route.query = {}
  mocks.search.mockResolvedValue([
    {
      entityId: 'entity-1',
      entityType: 'PERSON',
      name: '林夜',
      description: '主角',
      relevanceScore: 0.91,
      spaceId: 'domain:knowledge-base:novel-workspace',
      memoryScope: 'DOMAIN_MEMORY',
      realityType: 'FICTIONAL',
    },
  ])
})

describe('MemoryView 搜索结果展示', () => {
  it('会根据路由 query 切换到指定页签', async () => {
    mocks.route.query = { tab: 'relations' }

    mountView()
    await flushPromises()

    expect(mocks.store.activeTab).toBe('relations')
  })

  it('会根据路由 query 直接请求打开指定实体详情', async () => {
    mocks.route.query = { tab: 'entities', entityId: 'entity-9' }

    mountView()
    await flushPromises()

    expect(mocks.store.activeTab).toBe('entities')
    expect(mocks.store.entityDetailRequest?.id).toBe('entity-9')
  })

  it('会展示结果的记忆范围、现实性与空间标识，并支持直达实体详情', async () => {
    const wrapper = mountView()
    const input = wrapper.get('input')

    await input.setValue('林夜')
    const searchButton = wrapper.findAll('button').find((button) => button.text() === '搜索')
    expect(searchButton).toBeTruthy()
    await searchButton!.trigger('click')
    await flushPromises()

    expect(mocks.search).toHaveBeenCalledWith('林夜')
    expect(wrapper.text()).toContain('领域记忆')
    expect(wrapper.text()).toContain('虚构')
    expect(wrapper.text()).toContain('KB/领域冷召回')
    expect(wrapper.text()).toContain('domain:knowledge-base:novel-workspace')

    const resultCard = wrapper.find('.list-card')
    await resultCard.trigger('click')

    expect(mocks.store.activeTab).toBe('entities')
    expect(mocks.store.entityDetailRequest?.id).toBe('entity-1')
  })

  it('项目上下文内搜索记忆时会传递 projectId 并展示范围提示', async () => {
    mocks.route.query = { projectId: 'project-1' }
    const wrapper = mountView()
    const input = wrapper.get('input')

    await input.setValue('林夜')
    const searchButton = wrapper.findAll('button').find((button) => button.text() === '搜索')
    expect(searchButton).toBeTruthy()
    await searchButton!.trigger('click')
    await flushPromises()

    expect(mocks.search).toHaveBeenCalledWith('林夜', undefined, 'project-1')
    expect(wrapper.text()).toContain('项目上下文: project-1')
  })
})
