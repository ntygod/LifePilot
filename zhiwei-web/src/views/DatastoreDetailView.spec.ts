import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import DatastoreDetailView from './DatastoreDetailView.vue'

const mocks = vi.hoisted(() => ({
  datastoreApi: {
    get: vi.fn(),
    listKnowledgeBases: vi.fn(),
    listRecords: vi.fn(),
    listDocuments: vi.fn(),
    uploadDocument: vi.fn(),
  },
  datastoreStore: {
    deleteDatastore: vi.fn(),
  },
  memoryApi: {
    listEntities: vi.fn(),
    listRecentProvenances: vi.fn(),
  },
  uiStore: {
    showToast: vi.fn(),
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
  memoryApi: mocks.memoryApi,
}))

vi.mock('@/stores/datastore', () => ({
  useDatastoreStore: () => mocks.datastoreStore,
}))

vi.mock('@/stores/ui', () => ({
  useUiStore: () => mocks.uiStore,
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
        ConfirmDialog: {
          props: ['show', 'message'],
          template: `
            <div data-test="confirm-dialog" :data-show="String(show)" :data-message="message">
              <button data-test="confirm-delete" @click="$emit('confirm')">confirm</button>
            </div>
          `,
        },
        Skeleton: { template: '<div />' },
      },
    },
  })
}

beforeEach(() => {
  mocks.datastoreApi.get.mockReset()
  mocks.datastoreApi.listKnowledgeBases.mockReset()
  mocks.datastoreApi.listRecords.mockReset()
  mocks.datastoreApi.listDocuments.mockReset()
  mocks.datastoreApi.uploadDocument.mockReset()
  mocks.datastoreStore.deleteDatastore.mockReset()
  mocks.memoryApi.listEntities.mockReset()
  mocks.memoryApi.listRecentProvenances.mockReset()
  mocks.uiStore.showToast.mockReset()
  mocks.router.push.mockReset()
  mocks.route.params.id = 'ds-1'
  mocks.datastoreApi.get.mockResolvedValue({
    id: 'ds-1',
    name: 'novel-workspace',
    description: '小说创作素材库',
    timeSeries: false,
    fieldHintsJson: JSON.stringify([
      { name: 'title', type: 'TEXT', description: '标题' },
      { name: 'chapter', type: 'NUMBER', description: '章节号' },
    ]),
    defaultKnowledgeBaseId: 'kb-internal',
    createdBy: 'tester',
    createdAt: '2026-03-27T00:00:00Z',
    updatedAt: '2026-03-27T01:00:00Z',
  })
  mocks.datastoreApi.listKnowledgeBases.mockResolvedValue([
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
  ])
  mocks.datastoreApi.listRecords.mockResolvedValue([
    {
      id: 'record-1',
      knowledgeBaseId: 'kb-internal',
      fileName: '人物设定',
      content: '林夜是主角',
      metadataJson: '{"title":"人物设定"}',
      recordedAt: null,
      sourceDatastoreId: 'ds-1',
      status: 'READY',
      createdAt: '2026-03-27T02:00:00Z',
      updatedAt: '2026-03-27T02:30:00Z',
    },
  ])
  mocks.datastoreApi.listDocuments.mockResolvedValue([
    {
      id: 'doc-1',
      knowledgeBaseId: 'kb-1',
      fileName: '人物设定.md',
      filePath: '/docs/人物设定.md',
      fileSize: 2048,
      mimeType: 'text/markdown',
      checksum: 'hash-1',
      status: 'READY',
      chunkCount: 8,
      processingDurationMs: 10,
      errorMessage: null,
      metadataJson: '{}',
      createdAt: '2026-03-27T02:00:00Z',
      updatedAt: '2026-03-27T02:30:00Z',
      sourceType: 'FILE',
      sourceKey: 'FILE:doc-1',
      sourceDatastoreId: 'ds-1',
      sourceCollectionId: null,
      sourceRefJson: '{}',
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
    expect(mocks.datastoreApi.listKnowledgeBases).toHaveBeenCalledWith('ds-1')
    expect(mocks.datastoreApi.listRecords).toHaveBeenCalledWith('ds-1')
    expect(mocks.datastoreApi.listDocuments).toHaveBeenCalledWith('ds-1')
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
    expect(wrapper.text()).toContain('普通集合')
    expect(wrapper.text()).toContain('title')
    expect(wrapper.text()).toContain('chapter')
    expect(wrapper.text()).toContain('tester')
    expect(wrapper.text()).toContain('kb-internal')
    expect(wrapper.text()).toContain('世界观资料库')
    expect(wrapper.text()).toContain('人物设定.md')
    expect(wrapper.text()).toContain('人物设定')
    expect(wrapper.text()).toContain('林夜是主角')

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

  it('支持删除当前 Datastore 并返回列表页', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-test="open-delete-datastore"]').trigger('click')

    expect(wrapper.get('[data-test="confirm-dialog"]').attributes('data-show')).toBe('true')
    expect(wrapper.get('[data-test="confirm-dialog"]').attributes('data-message')).toContain('novel-workspace')

    await wrapper.get('[data-test="confirm-delete"]').trigger('click')
    await flushPromises()

    expect(mocks.datastoreStore.deleteDatastore).toHaveBeenCalledWith('ds-1')
    expect(mocks.router.push).toHaveBeenCalledWith({ name: 'datastores' })
  })
})
