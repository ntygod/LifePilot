import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import DatastoreDetailView from './DatastoreDetailView.vue'

const mocks = vi.hoisted(() => ({
  datastoreApi: {
    get: vi.fn(),
  },
  knowledgeBaseApi: {
    list: vi.fn(),
  },
  memoryApi: {
    listEntities: vi.fn(),
    listRecentProvenances: vi.fn(),
  },
  route: {
    params: { id: 'ds-1' as string },
  },
  router: {
    push: vi.fn(),
  },
}))

vi.mock('@/api/client', () => ({
  datastoreApi: mocks.datastoreApi,
  knowledgeBaseApi: mocks.knowledgeBaseApi,
  memoryApi: mocks.memoryApi,
}))

vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router')
  return {
    ...actual,
    useRoute: () => mocks.route,
    useRouter: () => mocks.router,
  }
})

function mountView() {
  return shallowMount(DatastoreDetailView, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        Breadcrumb: { template: '<div><slot /></div>' },
        PageContainer: { template: '<div><slot /></div>' },
        PageHeader: {
          props: ['eyebrow', 'title', 'description'],
          template: '<div>{{ eyebrow }}{{ title }}{{ description }}<slot name="actions" /><slot name="meta" /><slot /></div>',
        },
        PageSection: { template: '<div><slot /></div>' },
        MetricCard: { template: '<div><slot name="icon" /><slot /></div>' },
        StatePanel: { template: '<div><slot name="icon" /><slot /></div>' },
        Badge: { template: '<span><slot /></span>' },
        Button: {
          template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>',
        },
        Skeleton: { template: '<div />' },
      },
    },
  })
}

beforeEach(() => {
  mocks.datastoreApi.get.mockReset()
  mocks.knowledgeBaseApi.list.mockReset()
  mocks.memoryApi.listEntities.mockReset()
  mocks.memoryApi.listRecentProvenances.mockReset()
  mocks.router.push.mockReset()
  mocks.route.params.id = 'ds-1'
  mocks.datastoreApi.get.mockResolvedValue({
    id: 'ds-1',
    name: 'novel-workspace',
    description: '小说创作素材库',
    type: 'DOCUMENT',
    propertiesJson: JSON.stringify([
      { name: 'title', type: 'TEXT', required: true },
      { name: 'chapter', type: 'NUMBER', required: false },
    ]),
    projectionConfigJson: '{"scalarPaths":["title"],"bodyPaths":["content"]}',
    metadataJson: '{"owner":"writer"}',
    createdBy: 'tester',
    createdAt: '2026-03-27T00:00:00Z',
    updatedAt: '2026-03-27T01:00:00Z',
  })
  mocks.knowledgeBaseApi.list.mockResolvedValue([
    {
      id: 'kb-1',
      name: '世界观资料库',
      description: '小说设定与人物资料',
      embeddingModel: 'bge-m3',
      rerankerModel: null,
      chunkingStrategy: 'smart',
      tags: [],
      documentCount: 12,
      totalChunks: 220,
      createdAt: '2026-03-27T00:00:00Z',
      updatedAt: '2026-03-27T02:00:00Z',
      datastoreIds: ['ds-1'],
    },
    {
      id: 'kb-2',
      name: '无关知识库',
      description: '不应出现在当前详情页',
      embeddingModel: 'bge-m3',
      rerankerModel: null,
      chunkingStrategy: 'smart',
      tags: [],
      documentCount: 1,
      totalChunks: 10,
      createdAt: '2026-03-27T00:00:00Z',
      updatedAt: '2026-03-27T02:00:00Z',
      datastoreIds: ['ds-other'],
    },
  ])
  mocks.memoryApi.listEntities.mockResolvedValue({
    items: [
      {
        id: 'entity-1',
        type: 'PERSON',
        typeLabel: '人物',
        name: '林夜',
        description: '主角设定',
        importanceScore: 0.95,
        accessCount: 18,
        version: 2,
        spaceId: 'datastore:ds-1',
        memoryScope: 'DOMAIN_MEMORY',
        realityType: 'FICTIONAL',
        createdAt: '2026-03-27T00:00:00Z',
        updatedAt: '2026-03-27T02:30:00Z',
      },
    ],
    page: 0,
    size: 6,
    total: 1,
  })
  mocks.memoryApi.listRecentProvenances.mockResolvedValue([
    {
      entityId: 'entity-1',
      entityName: '林夜',
      entityType: 'PERSON',
      entityTypeLabel: '人物',
      entityMemoryScope: 'DOMAIN_MEMORY',
      entityRealityType: 'FICTIONAL',
      originType: 'KNOWLEDGE_BASE_DOCUMENT',
      sourceReference: '人物设定手册',
      sourceConversationId: null,
      sourceSessionId: 'session-1',
      sourceTurnId: 'turn-1',
      sourceEntryId: 'entry-1',
      sourceDocumentId: 'doc-1',
      sourceDocumentName: '人物设定.md',
      sourceKnowledgeBaseId: 'kb-1',
      sourceKnowledgeBaseName: '世界观资料库',
      sourceDatastoreId: 'ds-1',
      sourceDatastoreName: 'novel-workspace',
      sourceCollectionId: 'collection-1',
      sourceCollectionName: '角色设定集合',
      confidence: 0.97,
      createdAt: '2026-03-27T03:00:00Z',
    },
  ])
})

describe('DatastoreDetailView', () => {
  it('加载并展示 Datastore 结构、配置与关联知识库', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(mocks.datastoreApi.get).toHaveBeenCalledWith('ds-1')
    expect(mocks.knowledgeBaseApi.list).toHaveBeenCalled()
    expect(mocks.memoryApi.listEntities).toHaveBeenCalledWith({
      page: 0,
      size: 6,
      sourceDatastoreId: 'ds-1',
      sortBy: 'importanceScore',
      order: 'desc',
    })
    expect(mocks.memoryApi.listRecentProvenances).toHaveBeenCalledWith({
      sourceDatastoreId: 'ds-1',
      limit: 6,
    })
    expect(wrapper.text()).toContain('novel-workspace')
    expect(wrapper.text()).toContain('DOCUMENT')
    expect(wrapper.text()).toContain('title')
    expect(wrapper.text()).toContain('chapter')
    expect(wrapper.text()).toContain('tester')
    expect(wrapper.text()).toContain('scalarPaths')
    expect(wrapper.text()).toContain('owner')
    expect(wrapper.text()).toContain('世界观资料库')
    expect(wrapper.text()).not.toContain('无关知识库')

    await wrapper.get('[data-test="related-kb-card"]').trigger('click')
    expect(mocks.router.push).toHaveBeenCalledWith({
      name: 'knowledgeBaseDetail',
      params: { id: 'kb-1' },
    })
    expect(wrapper.text()).toContain('林夜')
    expect(wrapper.text()).toContain('领域记忆')
    expect(wrapper.text()).toContain('人物设定.md')
    expect(wrapper.text()).toContain('世界观资料库')
  })

  it('支持跳转到按 Datastore 来源筛选的记忆列表', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-test="open-datastore-memories"]').trigger('click')

    expect(mocks.router.push).toHaveBeenCalledWith({
      name: 'memories',
      query: {
        tab: 'entities',
        sourceDatastoreId: 'ds-1',
      },
    })
  })

  it('支持从关联记忆预览直接打开实体详情', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-test="related-memory-card"]').trigger('click')

    expect(mocks.router.push).toHaveBeenCalledWith({
      name: 'memories',
      query: {
        tab: 'entities',
        sourceDatastoreId: 'ds-1',
        entityId: 'entity-1',
      },
    })
  })

  it('支持从最近来源直接打开会话、文档和实体详情', async () => {
    const wrapper = mountView()
    await flushPromises()

    const buttons = wrapper.findAll('button')
    const openEntityButton = buttons.find(button => button.attributes('data-test') === 'open-recent-provenance-entity')
    const openSessionButton = buttons.find(button => button.attributes('data-test') === 'open-recent-provenance-session')
    const openDocumentButton = buttons.find(button => button.attributes('data-test') === 'open-recent-provenance-document')

    expect(openEntityButton).toBeTruthy()
    expect(openSessionButton).toBeTruthy()
    expect(openDocumentButton).toBeTruthy()

    await openEntityButton!.trigger('click')
    expect(mocks.router.push).toHaveBeenCalledWith({
      name: 'memories',
      query: {
        tab: 'entities',
        sourceDatastoreId: 'ds-1',
        entityId: 'entity-1',
      },
    })

    await openSessionButton!.trigger('click')
    expect(mocks.router.push).toHaveBeenCalledWith({
      name: 'conversationDetail',
      params: { sessionId: 'session-1' },
    })

    await openDocumentButton!.trigger('click')
    expect(mocks.router.push).toHaveBeenCalledWith({
      name: 'knowledgeBaseDocumentDetail',
      params: { id: 'kb-1', docId: 'doc-1' },
    })
  })
})
