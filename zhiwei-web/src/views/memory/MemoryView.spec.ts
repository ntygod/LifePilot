import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import MemoryView from './MemoryView.vue'

const mocks = vi.hoisted(() => ({
  loadStats: vi.fn(),
  search: vi.fn(),
  triggerConsolidation: vi.fn(),
  store: {
    stats: null as unknown,
    statsLoading: false,
    statsError: null as string | null,
    activeTab: 'entities',
    consolidating: false,
    memoryDisabled: false,
    loadStats: undefined as unknown,
    search: undefined as unknown,
    triggerConsolidation: undefined as unknown,
  },
}))

mocks.store.loadStats = mocks.loadStats
mocks.store.search = mocks.search
mocks.store.triggerConsolidation = mocks.triggerConsolidation

vi.mock('@/stores/memory', () => ({
  useMemoryStore: () => mocks.store,
}))

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
  mocks.search.mockResolvedValue([
    {
      entityId: 'entity-1',
      entityType: 'PERSON',
      name: '林夜',
      description: '主角',
      relevanceScore: 0.91,
      spaceId: 'datastore:novel-workspace',
      memoryScope: 'DOMAIN_MEMORY',
      realityType: 'FICTIONAL',
    },
  ])
})

describe('MemoryView 搜索结果展示', () => {
  it('会展示结果的记忆范围、现实性与空间标识', async () => {
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
    expect(wrapper.text()).toContain('datastore:novel-workspace')
  })
})
