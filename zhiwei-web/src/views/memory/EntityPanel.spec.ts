import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import EntityPanel from './EntityPanel.vue'

const mocks = vi.hoisted(() => ({
  listEntities: vi.fn(),
  getEntity: vi.fn(),
  getEntityProvenances: vi.fn(),
  resolveEntityRevalidation: vi.fn(),
  getEntityHistory: vi.fn(),
  getRelatedEntities: vi.fn(),
  createEntity: vi.fn(),
  updateEntity: vi.fn(),
  deleteEntity: vi.fn(),
  router: {
    push: vi.fn(),
  },
  route: {
    query: {} as Record<string, unknown>,
  },
  memoryStore: {
    entityDetailRequest: null as { id: string; requestedAt: number } | null,
    clearEntityDetailRequest: vi.fn(),
  },
  uiStore: {
    showToast: vi.fn(),
  },
}))

vi.mock('@/api/client', () => ({
  memoryApi: {
    listEntities: mocks.listEntities,
    getEntity: mocks.getEntity,
    getEntityProvenances: mocks.getEntityProvenances,
    resolveEntityRevalidation: mocks.resolveEntityRevalidation,
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
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" @keydown.enter="$emit(\'keydown.enter\', $event)" />',
        },
        Skeleton: { template: '<div />' },
        Select: { template: '<div><slot /></div>' },
        SelectContent: { template: '<div><slot /></div>' },
        SelectItem: { template: '<div><slot /></div>' },
        SelectTrigger: { template: '<div><slot /></div>' },
        SelectValue: { template: '<div><slot /></div>' },
        DatePicker: { template: '<div />' },
        Pagination: { template: '<div />' },
        Sheet: { template: '<div><slot /></div>' },
        SheetContent: { template: '<div><slot /></div>' },
        SheetHeader: { template: '<div><slot /></div>' },
        SheetTitle: { template: '<h2><slot /></h2>' },
        SheetDescription: { template: '<p><slot /></p>' },
        Dialog: { template: '<div><slot /></div>' },
        DialogContent: { template: '<div><slot /></div>' },
        DialogDescription: { template: '<p><slot /></p>' },
        DialogFooter: { template: '<div><slot /></div>' },
        DialogHeader: { template: '<div><slot /></div>' },
        DialogTitle: { template: '<h2><slot /></h2>' },
        Tabs: { template: '<div><slot /></div>' },
        TabsList: { template: '<div><slot /></div>' },
        TabsTrigger: { template: '<button><slot /></button>' },
        TabsContent: { template: '<div><slot /></div>' },
      },
    },
  })
}

beforeEach(() => {
  mocks.listEntities.mockReset()
  mocks.getEntity.mockReset()
  mocks.getEntityProvenances.mockReset()
  mocks.resolveEntityRevalidation.mockReset()
  mocks.getEntityHistory.mockReset()
  mocks.getRelatedEntities.mockReset()
  mocks.createEntity.mockReset()
  mocks.updateEntity.mockReset()
  mocks.deleteEntity.mockReset()
  mocks.router.push.mockReset()
  mocks.memoryStore.clearEntityDetailRequest.mockReset()
  mocks.memoryStore.entityDetailRequest = null
  mocks.route.query = {}

  const createdAt = '2026-03-27T00:00:00Z'
  mocks.listEntities.mockResolvedValue({
    items: [
      {
        id: 'entity-profile',
        type: 'PREFERENCE',
        typeLabel: '偏好',
        name: '输出偏好',
        description: '用户明确要求简洁输出',
        importanceScore: 0.81,
        accessCount: 7,
        version: 3,
        spaceId: 'personal:default',
        memoryScope: 'USER_PROFILE',
        realityType: 'REAL',
        lifecycleState: 'ACTIVE',
        temporality: 'PERMANENT',
        expiresAt: null,
        evidenceKind: 'USER_EXPLICIT',
        trustLevel: 'EXPLICIT',
        trustScore: 0.9,
        evidenceCount: 2,
        lastVerifiedAt: createdAt,
        createdAt,
        updatedAt: createdAt,
      },
      {
        id: 'entity-domain',
        type: 'PERSON',
        typeLabel: '人物',
        name: '林夜',
        description: '小说主角',
        importanceScore: 0.73,
        accessCount: 3,
        version: 1,
        spaceId: 'domain:knowledge-base:novel-workspace',
        memoryScope: 'DOMAIN_MEMORY',
        realityType: 'FICTIONAL',
        lifecycleState: 'ACTIVE',
        temporality: 'PERMANENT',
        expiresAt: null,
        evidenceKind: 'DOCUMENT_GROUNDED',
        trustLevel: 'VERIFIED',
        trustScore: 0.82,
        evidenceCount: 1,
        lastVerifiedAt: createdAt,
        createdAt,
        updatedAt: createdAt,
      },
    ],
    page: 0,
    size: 20,
    total: 2,
  })

  mocks.getEntity.mockResolvedValue({
    id: 'entity-domain',
    type: 'PERSON',
    typeLabel: '人物',
    name: '林夜',
    description: '小说主角',
    spaceId: 'domain:knowledge-base:novel-workspace',
    memoryScope: 'DOMAIN_MEMORY',
    realityType: 'FICTIONAL',
    properties: { alias: '夜' },
    version: 1,
    isCurrent: true,
    validFrom: createdAt,
    validTo: null,
    sourceConversationId: null,
    lifecycleState: 'ACTIVE',
    lifecycleReason: null,
    expiresAt: null,
    temporality: 'PERMANENT',
    succeededBy: null,
    isDerived: false,
    derivationSources: [],
    evidenceKind: 'DOCUMENT_GROUNDED',
    trustLevel: 'VERIFIED',
    trustScore: 0.82,
    evidenceCount: 1,
    lastVerifiedAt: createdAt,
    extractionConfidence: 0.76,
    importanceScore: 0.73,
    accessCount: 3,
    lastAccessedAt: null,
    createdAt,
    updatedAt: createdAt,
  })

  mocks.getEntityProvenances.mockResolvedValue([
    {
      originType: 'KNOWLEDGE_BASE_DOCUMENT',
      sourceReference: 'chapter-1',
      sourceConversationId: null,
      sourceSessionId: null,
      sourceTurnId: null,
      sourceEntryId: 'chunk-7',
      sourceDocumentId: 'doc-1',
      sourceDocumentName: '第一章.md',
      sourceKnowledgeBaseId: 'kb-1',
      sourceKnowledgeBaseName: '小说知识库',
      evidenceKind: 'DOCUMENT_GROUNDED',
      trustLevel: 'VERIFIED',
      trustScore: 0.82,
      evidenceExcerpt: '林夜进入青牛宗。',
      confidence: 0.76,
      status: 'VALID',
      invalidatedAt: null,
      revalidationStatus: null,
      createdAt,
    },
  ])
  mocks.getEntityHistory.mockResolvedValue([])
  mocks.getRelatedEntities.mockResolvedValue([])
})

describe('EntityPanel 治理字段展示', () => {
  it('会在实体列表展示热摘要候选、KB 冷召回和质量状态', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(mocks.listEntities).toHaveBeenCalledWith({
      page: 0,
      size: 20,
      sortBy: 'createdAt',
      order: 'desc',
    })
    expect(wrapper.text()).toContain('热摘要候选')
    expect(wrapper.text()).toContain('KB 图谱冷召回')
    expect(wrapper.text()).toContain('可召回')
    expect(wrapper.text()).toContain('用户明确')
    expect(wrapper.text()).toContain('文档证据')
    expect(wrapper.text()).toContain('个人记忆空间')
    expect(wrapper.text()).toContain('KB 图谱空间')
    expect(wrapper.text()).toContain('用户明示 · 可信度 90% · 2 份证据')
  })

  it('会在实体详情和来源明细展示后端返回的生命周期与证据字段', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    const domainRow = wrapper.findAll('tbody tr').find(row => row.text().includes('林夜'))
    expect(domainRow).toBeTruthy()
    await domainRow!.trigger('click')
    await flushPromises()

    expect(mocks.getEntity).toHaveBeenCalledWith('entity-domain', undefined)
    expect(mocks.getEntityProvenances).toHaveBeenCalledWith('entity-domain', {})
    expect(wrapper.text()).toContain('消费边界')
    expect(wrapper.text()).toContain('KB 图谱冷召回')
    expect(wrapper.text()).toContain('为什么知微会用这条记忆')
    expect(wrapper.text()).toContain('回答影响')
    expect(wrapper.text()).toContain('作为资料库或项目里的领域事实参与相关问题，不写入个人偏好。')
    expect(wrapper.text()).toContain('证据依据')
    expect(wrapper.text()).toContain('文档证据 · 已验证 · 可信度 82% · 1 份证据 · 抽取置信度 76%')
    expect(wrapper.text()).toContain('来源状态')
    expect(wrapper.text()).toContain('知识库文档 · 第一章.md · 有效')
    expect(wrapper.text()).toContain('信任等级')
    expect(wrapper.text()).toContain('已验证')
    expect(wrapper.text()).toContain('证据类型')
    expect(wrapper.text()).toContain('文档证据')
    expect(wrapper.text()).toContain('可信分数')
    expect(wrapper.text()).toContain('82%')
    expect(wrapper.text()).toContain('Chunk ID')
    expect(wrapper.text()).toContain('chunk-7')
    expect(wrapper.text()).toContain('证据片段')
    expect(wrapper.text()).toContain('林夜进入青牛宗。')
  })

  it('对话来源可以从记忆详情跳回原始会话位置', async () => {
    mocks.getEntityProvenances.mockResolvedValueOnce([{
      originType: 'CHAT',
      sourceReference: 'session-1',
      sourceConversationId: 'conversation-1',
      sourceSessionId: 'session-1',
      sourceSessionTitle: '主界面体验讨论',
      sourceTurnId: 'turn-1',
      sourceEntryId: 'entry-1',
      sourceDocumentId: null,
      sourceDocumentName: null,
      sourceKnowledgeBaseId: null,
      sourceKnowledgeBaseName: null,
      evidenceKind: 'USER_EXPLICIT',
      trustLevel: 'EXPLICIT',
      trustScore: 0.91,
      evidenceExcerpt: '我希望主界面保持轻量，能力在对话中自然浮现。',
      confidence: 0.89,
      status: 'VALID',
      invalidatedAt: null,
      revalidationStatus: null,
      createdAt: '2026-07-04T00:00:00Z',
    }])

    const wrapper = mountPanel()
    await flushPromises()

    const domainRow = wrapper.findAll('tbody tr').find(row => row.text().includes('林夜'))
    expect(domainRow).toBeTruthy()
    await domainRow!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('对话抽取 · 主界面体验讨论 · 有效')
    expect(wrapper.text()).toContain('会话标题')
    expect(wrapper.text()).toContain('主界面体验讨论')
    const sourceButton = wrapper.find('[data-test="open-provenance-conversation"]')
    expect(sourceButton.exists()).toBe(true)
    await sourceButton.trigger('click')

    expect(mocks.router.push).toHaveBeenCalledWith({
      name: 'conversationDetail',
      params: { sessionId: 'session-1' },
      query: {
        turnId: 'turn-1',
        entryId: 'entry-1',
      },
    })
  })

  it('来源明细会标出已失效来源并提示复核', async () => {
    mocks.getEntityProvenances.mockResolvedValueOnce([{
      originType: 'CHAT',
      sourceReference: 'session-old',
      sourceConversationId: 'session-old',
      sourceSessionId: 'session-old',
      sourceTurnId: 'turn-old',
      sourceEntryId: 'entry-old',
      sourceDocumentId: null,
      sourceDocumentName: null,
      sourceKnowledgeBaseId: null,
      sourceKnowledgeBaseName: null,
      evidenceKind: 'USER_EXPLICIT',
      trustLevel: 'EXPLICIT',
      trustScore: 0.91,
      evidenceExcerpt: '我希望主界面保持轻量。',
      confidence: 0.89,
      status: 'STALE',
      invalidatedAt: '2026-07-05T08:00:00Z',
      revalidationStatus: 'PENDING',
      createdAt: '2026-07-04T00:00:00Z',
    }])

    const wrapper = mountPanel()
    await flushPromises()

    const domainRow = wrapper.findAll('tbody tr').find(row => row.text().includes('林夜'))
    expect(domainRow).toBeTruthy()
    await domainRow!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('需复核')
    expect(wrapper.text()).toContain('来源已变更或不可完整追溯')
    expect(wrapper.text()).toContain('后续使用这条记忆时需要复核')
    expect(wrapper.text()).toContain('确认有效')
  })

  it('来源待复核时可人工确认有效并刷新来源状态', async () => {
    mocks.route.query = { projectId: 'project-1' }
    const stale = {
      originType: 'CHAT',
      sourceReference: 'session-old',
      sourceConversationId: 'session-old',
      sourceSessionId: 'session-old',
      sourceTurnId: 'turn-old',
      sourceEntryId: 'entry-old',
      sourceDocumentId: null,
      sourceDocumentName: null,
      sourceKnowledgeBaseId: null,
      sourceKnowledgeBaseName: null,
      evidenceKind: 'USER_EXPLICIT',
      trustLevel: 'EXPLICIT',
      trustScore: 0.91,
      evidenceExcerpt: '我希望主界面保持轻量。',
      confidence: 0.89,
      status: 'STALE',
      invalidatedAt: '2026-07-05T08:00:00Z',
      revalidationStatus: 'PENDING',
      createdAt: '2026-07-04T00:00:00Z',
    }
    mocks.getEntityProvenances
      .mockResolvedValueOnce([stale])
      .mockResolvedValueOnce([{
        ...stale,
        revalidationStatus: 'RESOLVED',
      }])
    mocks.resolveEntityRevalidation.mockResolvedValueOnce({ resolvedCount: 1, status: 'RESOLVED' })

    const wrapper = mountPanel()
    await flushPromises()

    const domainRow = wrapper.findAll('tbody tr').find(row => row.text().includes('林夜'))
    expect(domainRow).toBeTruthy()
    await domainRow!.trigger('click')
    await flushPromises()

    await wrapper.find('[data-test="resolve-entity-revalidation"]').trigger('click')
    await flushPromises()

    expect(mocks.resolveEntityRevalidation).toHaveBeenCalledWith('entity-domain', 'project-1')
    expect(mocks.getEntityProvenances).toHaveBeenCalledWith('entity-domain', { projectId: 'project-1' })
    expect(mocks.getEntityProvenances).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('已复核')
    expect(wrapper.text()).toContain('你已确认这条记忆当前仍有效')
  })

  it('项目上下文会贯通实体列表、详情、来源和维护操作', async () => {
    mocks.route.query = { projectId: 'project-1' }
    mocks.createEntity.mockResolvedValueOnce({})
    mocks.deleteEntity.mockResolvedValueOnce(undefined)

    const wrapper = mountPanel()
    await flushPromises()

    expect(mocks.listEntities).toHaveBeenCalledWith(expect.objectContaining({
      page: 0,
      size: 20,
      projectId: 'project-1',
    }))

    const domainRow = wrapper.findAll('tbody tr').find(row => row.text().includes('林夜'))
    expect(domainRow).toBeTruthy()
    await domainRow!.trigger('click')
    await flushPromises()

    expect(mocks.getEntity).toHaveBeenCalledWith('entity-domain', 'project-1')
    expect(mocks.getEntityProvenances).toHaveBeenCalledWith('entity-domain', { projectId: 'project-1' })

    const vm = wrapper.vm as any
    vm.createForm = { name: '项目实体', type: 'PERSON', description: '', properties: {}, importanceScore: 0.5 }
    vm.createPropsText = '{}'
    await vm.handleCreate()
    await flushPromises()

    expect(mocks.createEntity).toHaveBeenCalledWith(
      expect.objectContaining({ name: '项目实体' }),
      'project-1',
    )

    const loadedDetail = await mocks.getEntity.mock.results.at(-1)!.value
    mocks.updateEntity.mockResolvedValueOnce({
      ...loadedDetail,
      description: '项目内修订',
      importanceScore: 0.8,
    })
    vm.editEntityId = 'entity-domain'
    vm.editForm = { description: '项目内修订', properties: {}, importanceScore: 0.8 }
    vm.editPropsText = '{}'
    await vm.handleEdit()
    await flushPromises()

    expect(mocks.updateEntity).toHaveBeenCalledWith(
      'entity-domain',
      expect.objectContaining({ description: '项目内修订' }),
      'project-1',
    )

    vm.archiveEntityId = 'entity-domain'
    await vm.handleArchive()
    await flushPromises()

    expect(mocks.deleteEntity).toHaveBeenCalledWith('entity-domain', 'project-1')
  })

  it('从外部入口打开实体详情失败时显示原因并支持重试', async () => {
    mocks.memoryStore.entityDetailRequest = { id: 'entity-missing', requestedAt: Date.now() }
    mocks.getEntity.mockRejectedValueOnce(new Error('记忆不存在或已被归档'))

    const wrapper = mountPanel()
    await flushPromises()

    expect(mocks.getEntity).toHaveBeenCalledWith('entity-missing', undefined)
    expect(mocks.memoryStore.clearEntityDetailRequest).toHaveBeenCalled()
    expect(wrapper.text()).toContain('这条记忆暂时打不开')
    expect(wrapper.text()).toContain('记忆不存在或已被归档')

    const retryButton = wrapper.findAll('button').find(button => button.text() === '重试')
    expect(retryButton).toBeTruthy()
    const retryTargetCallsBeforeClick = mocks.getEntity.mock.calls
      .filter(([entityId]) => entityId === 'entity-missing')
      .length
    await retryButton!.trigger('click')
    await flushPromises()

    expect(mocks.getEntity).toHaveBeenLastCalledWith('entity-missing', undefined)
    expect(mocks.getEntity.mock.calls.filter(([entityId]) => entityId === 'entity-missing').length)
      .toBeGreaterThan(retryTargetCallsBeforeClick)
    expect(wrapper.text()).toContain('来源明细')
  })
})
