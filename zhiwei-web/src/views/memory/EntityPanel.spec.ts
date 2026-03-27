import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import EntityPanel from './EntityPanel.vue'

const mocks = vi.hoisted(() => ({
  listEntities: vi.fn(),
  getEntity: vi.fn(),
  getEntityProvenances: vi.fn(),
  getEntityHistory: vi.fn(),
  getRelatedEntities: vi.fn(),
  createEntity: vi.fn(),
  updateEntity: vi.fn(),
  deleteEntity: vi.fn(),
  memoryStore: {
    entityDetailRequest: null as { id: string; requestedAt: number } | null,
    clearEntityDetailRequest: vi.fn(),
  },
}))

vi.mock('@/api/client', () => ({
  memoryApi: {
    listEntities: mocks.listEntities,
    getEntity: mocks.getEntity,
    getEntityProvenances: mocks.getEntityProvenances,
    getEntityHistory: mocks.getEntityHistory,
    getRelatedEntities: mocks.getRelatedEntities,
    createEntity: mocks.createEntity,
    updateEntity: mocks.updateEntity,
    deleteEntity: mocks.deleteEntity,
  },
}))

vi.mock('@/stores/memory', () => ({
  useMemoryStore: () => mocks.memoryStore,
}))

function mountPanel() {
  return shallowMount(EntityPanel, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        Badge: { template: '<span><slot /></span>' },
        Button: {
          template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>',
        },
        Input: {
          props: ['modelValue'],
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
        Skeleton: { template: '<div />' },
        Select: { template: '<div><slot /></div>' },
        SelectContent: { template: '<div><slot /></div>' },
        SelectItem: { template: '<div><slot /></div>' },
        SelectTrigger: { template: '<div><slot /></div>' },
        SelectValue: { template: '<div><slot /></div>' },
        Dialog: { template: '<div><slot /></div>' },
        DialogContent: { template: '<div><slot /></div>' },
        DialogDescription: { template: '<div><slot /></div>' },
        DialogFooter: { template: '<div><slot /></div>' },
        DialogHeader: { template: '<div><slot /></div>' },
        DialogTitle: { template: '<div><slot /></div>' },
        Sheet: { template: '<div><slot /></div>' },
        SheetContent: { template: '<div><slot /></div>' },
        SheetDescription: { template: '<div><slot /></div>' },
        SheetHeader: { template: '<div><slot /></div>' },
        SheetTitle: { template: '<div><slot /></div>' },
        Tabs: { template: '<div><slot /></div>' },
        TabsContent: { template: '<div><slot /></div>' },
        TabsList: { template: '<div><slot /></div>' },
        TabsTrigger: { template: '<button><slot /></button>' },
        DatePicker: {
          props: ['modelValue'],
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
        Pagination: { template: '<div />' },
      },
    },
  })
}

beforeEach(() => {
  mocks.listEntities.mockReset()
  mocks.getEntity.mockReset()
  mocks.getEntityProvenances.mockReset()
  mocks.getEntityHistory.mockReset()
  mocks.getRelatedEntities.mockReset()
  mocks.createEntity.mockReset()
  mocks.updateEntity.mockReset()
  mocks.deleteEntity.mockReset()
  mocks.memoryStore.entityDetailRequest = null
  mocks.memoryStore.clearEntityDetailRequest.mockReset()

  mocks.listEntities.mockResolvedValue({
    items: [
      {
        id: 'entity-1',
        type: 'PERSON',
        typeLabel: '人物',
        name: '林夜',
        description: '主角',
        importanceScore: 0.92,
        accessCount: 12,
        version: 3,
        spaceId: 'datastore:novel-workspace',
        memoryScope: 'DOMAIN_MEMORY',
        realityType: 'FICTIONAL',
        createdAt: '2026-03-27T00:00:00Z',
        updatedAt: '2026-03-27T00:00:00Z',
      },
    ],
    page: 0,
    size: 20,
    total: 1,
  })
  mocks.getEntity.mockResolvedValue({
    id: 'entity-1',
    type: 'PERSON',
    typeLabel: '人物',
    name: '林夜',
    description: '主角',
    spaceId: 'datastore:novel-workspace',
    memoryScope: 'DOMAIN_MEMORY',
    realityType: 'FICTIONAL',
    properties: { role: '主角' },
    version: 3,
    isCurrent: true,
    validFrom: '2026-03-27T00:00:00Z',
    validTo: null,
    sourceConversationId: 'conv-1',
    extractionConfidence: 0.88,
    importanceScore: 0.92,
    accessCount: 12,
    lastAccessedAt: null,
    createdAt: '2026-03-27T00:00:00Z',
    updatedAt: '2026-03-27T00:00:00Z',
  })
  mocks.getEntityProvenances.mockResolvedValue([
    {
      originType: 'KNOWLEDGE_BASE',
      sourceReference: '章节设定手册',
      sourceConversationId: null,
      sourceSessionId: null,
      sourceTurnId: null,
      sourceEntryId: null,
      sourceDocumentId: 'doc-1',
      sourceKnowledgeBaseId: 'kb-1',
      sourceDatastoreId: 'ds-1',
      sourceCollectionId: 'collection-1',
      confidence: 0.93,
      createdAt: '2026-03-27T00:00:00Z',
    },
  ])
  mocks.getEntityHistory.mockResolvedValue([])
  mocks.getRelatedEntities.mockResolvedValue([])
})

describe('EntityPanel 记忆元数据展示', () => {
  it('打开实体详情后会展示记忆归属与来源明细', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    const row = wrapper.get('tbody tr')
    await row.trigger('click')
    await flushPromises()

    expect(mocks.getEntity).toHaveBeenCalledWith('entity-1')
    expect(mocks.getEntityProvenances).toHaveBeenCalledWith('entity-1')
    expect(wrapper.text()).toContain('领域记忆')
    expect(wrapper.text()).toContain('虚构')
    expect(wrapper.text()).toContain('datastore:novel-workspace')
    expect(wrapper.text()).toContain('知识库导入')
    expect(wrapper.text()).toContain('章节设定手册')
    expect(wrapper.text()).toContain('kb-1')
  })

  it('接收到跨面板详情请求后会自动拉起实体详情', async () => {
    mocks.memoryStore.entityDetailRequest = {
      id: 'entity-1',
      requestedAt: Date.now(),
    }

    const wrapper = mountPanel()
    await flushPromises()

    expect(mocks.getEntity).toHaveBeenCalledWith('entity-1')
    expect(mocks.getEntityProvenances).toHaveBeenCalledWith('entity-1')
    expect(mocks.memoryStore.clearEntityDetailRequest).toHaveBeenCalled()
    expect(wrapper.text()).toContain('林夜')
  })
})
