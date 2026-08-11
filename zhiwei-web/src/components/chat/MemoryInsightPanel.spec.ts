import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MemoryInsightPanel from './MemoryInsightPanel.vue'
import { Slider } from '@/components/ui/slider'
import type { EntityDetail, EntityProvenance, SourceSummary } from '@/types'

const mocks = vi.hoisted(() => ({
  getEntity: vi.fn(),
  getEntityProvenances: vi.fn(),
  resolveEntityRevalidation: vi.fn(),
  updateEntity: vi.fn(),
  deleteEntity: vi.fn(),
  push: vi.fn(),
}))

vi.mock('@/api/client', () => ({
  memoryApi: {
    getEntity: mocks.getEntity,
    getEntityProvenances: mocks.getEntityProvenances,
    resolveEntityRevalidation: mocks.resolveEntityRevalidation,
    updateEntity: mocks.updateEntity,
    deleteEntity: mocks.deleteEntity,
  },
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({
    push: mocks.push,
  }),
}))

vi.mock('@/utils/logger', () => ({
  logger: {
    error: vi.fn(),
    warn: vi.fn(),
    info: vi.fn(),
    debug: vi.fn(),
  },
}))

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}

vi.stubGlobal('ResizeObserver', ResizeObserverMock)

const source: SourceSummary = {
  type: 'memory',
  id: 'memory-1',
  name: '主界面偏好',
  extra: {
    entityTypeLabel: '偏好',
    description: '用户希望主界面保持轻量。',
  },
}

function entity(overrides: Partial<EntityDetail> = {}): EntityDetail {
  return {
    id: 'memory-1',
    type: 'PREFERENCE',
    typeLabel: '偏好',
    name: '主界面偏好',
    description: '用户希望主界面保持轻量。',
    spaceId: null,
    memoryScope: 'USER_PROFILE',
    realityType: 'REAL',
    properties: { surface: 'light' },
    version: 1,
    isCurrent: true,
    validFrom: '2026-07-04T00:00:00Z',
    validTo: null,
    sourceConversationId: 'conversation-1',
    lifecycleState: 'ACTIVE',
    lifecycleReason: null,
    expiresAt: null,
    temporality: 'PERSISTENT',
    succeededBy: null,
    isDerived: false,
    derivationSources: [],
    evidenceKind: 'USER_EXPLICIT',
    trustLevel: 'EXPLICIT',
    trustScore: 0.92,
    evidenceCount: 2,
    lastVerifiedAt: '2026-07-04T00:00:00Z',
    extractionConfidence: 0.9,
    importanceScore: 0.7,
    accessCount: 3,
    lastAccessedAt: null,
    createdAt: '2026-07-04T00:00:00Z',
    updatedAt: '2026-07-04T01:00:00Z',
    ...overrides,
  }
}

const provenance: EntityProvenance = {
  originType: 'CHAT',
  sourceReference: null,
  sourceConversationId: 'conversation-1',
  sourceSessionId: 'session-1',
  sourceTurnId: 'turn-1',
  sourceEntryId: 'entry-1',
  sourceDocumentId: null,
  sourceDocumentName: null,
  sourceKnowledgeBaseId: null,
  sourceKnowledgeBaseName: null,
  evidenceKind: 'USER_EXPLICIT',
  trustLevel: 'EXPLICIT',
  trustScore: 0.92,
  evidenceExcerpt: '我喜欢轻量主界面',
  confidence: 0.88,
  status: 'VALID',
  invalidatedAt: null,
  revalidationStatus: null,
  createdAt: '2026-07-04T00:00:00Z',
}

function findButtonByText(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find(button => button.text().includes(text))
}

describe('MemoryInsightPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mocks.getEntity.mockReset()
    mocks.getEntityProvenances.mockReset()
    mocks.resolveEntityRevalidation.mockReset()
    mocks.updateEntity.mockReset()
    mocks.deleteEntity.mockReset()
    mocks.push.mockReset()
  })

  it('在对话内展示记忆详情和来源证据', async () => {
    mocks.getEntity.mockResolvedValueOnce(entity())
    mocks.getEntityProvenances.mockResolvedValueOnce([provenance])

    const wrapper = mount(MemoryInsightPanel, {
      props: { source },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    expect(mocks.getEntity).toHaveBeenCalledWith('memory-1', undefined)
    expect(mocks.getEntityProvenances).toHaveBeenCalledWith('memory-1', { projectId: undefined })
    expect(wrapper.text()).toContain('本轮参考的记忆')
    expect(wrapper.text()).toContain('主界面偏好')
    expect(wrapper.text()).toContain('为什么出现')
    expect(wrapper.text()).toContain('知微参考它来贴合你的偏好：用户希望主界面保持轻量。')
    expect(wrapper.text()).toContain('使用影响')
    expect(wrapper.text()).toContain('会跨对话用于个人化回复；会影响语气、方案取舍和界面建议；长期生效；按上下文需要使用')
    expect(wrapper.text()).toContain('用户希望主界面保持轻量。')
    expect(wrapper.text()).toContain('来源：对话抽取；证据：用户明示；可信度：用户明确 92%；2 条证据')
    expect(wrapper.text()).toContain('我喜欢轻量主界面')
    expect(wrapper.text()).toContain('打开来源会话')
    expect(wrapper.text()).toContain('可信度')
    expect(wrapper.text()).toContain('更多细节')
    expect(wrapper.text()).not.toContain('"surface"')
    expect(wrapper.text()).not.toContain('v1')
  })

  it('来源失效时提示需要复核而不是继续假装来源完整', async () => {
    mocks.getEntity.mockResolvedValueOnce(entity())
    mocks.getEntityProvenances.mockResolvedValueOnce([{
      ...provenance,
      status: 'STALE',
      invalidatedAt: '2026-07-05T08:00:00Z',
      revalidationStatus: 'PENDING',
    }])

    const wrapper = mount(MemoryInsightPanel, {
      props: { source },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('需复核')
    expect(wrapper.text()).toContain('来源已变更或不可完整追溯')
    expect(wrapper.text()).toContain('知微后续使用这条记忆时需要复核')
    expect(wrapper.text()).toContain('确认仍有效')
  })

  it('来源待复核时可确认记忆仍有效并刷新来源状态', async () => {
    const stale = {
      ...provenance,
      status: 'STALE',
      invalidatedAt: '2026-07-05T08:00:00Z',
      revalidationStatus: 'PENDING',
    } satisfies EntityProvenance
    mocks.getEntity.mockResolvedValueOnce(entity())
    mocks.getEntityProvenances
      .mockResolvedValueOnce([stale])
      .mockResolvedValueOnce([{
        ...stale,
        revalidationStatus: 'RESOLVED',
      }])
    mocks.resolveEntityRevalidation.mockResolvedValueOnce({ resolvedCount: 1, status: 'RESOLVED' })

    const wrapper = mount(MemoryInsightPanel, {
      props: { source },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    await wrapper.find('[data-test="resolve-memory-revalidation"]').trigger('click')
    await flushPromises()

    expect(mocks.resolveEntityRevalidation).toHaveBeenCalledWith('memory-1', undefined)
    expect(mocks.getEntityProvenances).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('已复核')
    expect(wrapper.text()).toContain('你已确认这条记忆当前仍有效')
  })

  it('默认隐藏属性和版本等底层细节，展开后再展示', async () => {
    mocks.getEntity.mockResolvedValueOnce(entity())
    mocks.getEntityProvenances.mockResolvedValueOnce([provenance])

    const wrapper = mount(MemoryInsightPanel, {
      props: { source },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('"surface"')
    expect(wrapper.text()).not.toContain('v1')

    await findButtonByText(wrapper, '更多细节')?.trigger('click')

    expect(wrapper.text()).toContain('属性')
    expect(wrapper.text()).toContain('"surface": "light"')
    expect(wrapper.text()).toContain('v1')
    expect(wrapper.text()).toContain('可用')
  })

  it('展示后端来源类型和实际使用原因', async () => {
    mocks.getEntity.mockResolvedValueOnce(entity())
    mocks.getEntityProvenances.mockResolvedValueOnce([provenance])

    const wrapper = mount(MemoryInsightPanel, {
      props: {
        source: {
          ...source,
          extra: {
            ...source.extra,
            sourceKindLabel: '本轮实际参考',
            usageReason: '这条回答实际参考了这条记忆，用来延续与你相关的上下文。',
          },
        },
      },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('本轮实际参考的记忆')
    expect(wrapper.text()).toContain('这条回答实际参考了这条记忆，用来延续与你相关的上下文。')
  })

  it('头部提供明确关闭入口', async () => {
    mocks.getEntity.mockResolvedValueOnce(entity())
    mocks.getEntityProvenances.mockResolvedValueOnce([provenance])

    const wrapper = mount(MemoryInsightPanel, {
      props: { source },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    const closeButton = wrapper.find('button[aria-label="关闭记忆详情"]')
    expect(closeButton.exists()).toBe(true)

    await closeButton.trigger('click')

    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('来源证据多于一条时可在对话内展开核对全部依据', async () => {
    mocks.getEntity.mockResolvedValueOnce(entity({ evidenceCount: 2 }))
    mocks.getEntityProvenances.mockResolvedValueOnce([
      provenance,
      {
        ...provenance,
        sourceEntryId: 'entry-0',
        evidenceExcerpt: '昨天说主界面不要堆能力卡片',
        confidence: 0.76,
        createdAt: '2026-07-03T00:00:00Z',
      },
    ])

    const wrapper = mount(MemoryInsightPanel, {
      props: { source },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('我喜欢轻量主界面')
    expect(wrapper.text()).toContain('展开全部 2 条')
    expect(wrapper.text()).not.toContain('昨天说主界面不要堆能力卡片')

    await findButtonByText(wrapper, '展开全部 2 条')?.trigger('click')

    expect(wrapper.text()).toContain('收起')
    expect(wrapper.text()).toContain('昨天说主界面不要堆能力卡片')
    expect(wrapper.text()).toContain('置信度 76%')
  })

  it('来源证据加载失败时明确提示并可单独重试', async () => {
    mocks.getEntity.mockResolvedValueOnce(entity())
    mocks.getEntityProvenances
      .mockRejectedValueOnce(new Error('来源索引暂不可用'))
      .mockResolvedValueOnce([provenance])

    const wrapper = mount(MemoryInsightPanel, {
      props: { source },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('主界面偏好')
    expect(wrapper.text()).toContain('用户希望主界面保持轻量。')
    expect(wrapper.text()).toContain('来源证据暂时无法加载：来源索引暂不可用')
    expect(wrapper.text()).not.toContain('暂无来源证据')

    await findButtonByText(wrapper, '编辑')?.trigger('click')

    expect(wrapper.text()).toContain('参考强度')
    expect(wrapper.find('input:not([type="number"])').exists()).toBe(true)

    await findButtonByText(wrapper, '取消')?.trigger('click')
    await wrapper.find('button[aria-label="重新加载来源证据"]').trigger('click')
    await flushPromises()

    expect(mocks.getEntity).toHaveBeenCalledTimes(1)
    expect(mocks.getEntityProvenances).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('我喜欢轻量主界面')
    expect(wrapper.text()).not.toContain('来源证据暂时无法加载')
  })

  it('记忆详情加载失败时仍保留对话摘要并允许重试定位', async () => {
    mocks.getEntity
      .mockRejectedValueOnce(new Error('实体索引暂不可用'))
      .mockResolvedValueOnce(entity())
    mocks.getEntityProvenances
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([provenance])

    const wrapper = mount(MemoryInsightPanel, {
      props: { source },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('实体索引暂不可用')
    expect(wrapper.text()).toContain('对话摘要')
    expect(wrapper.text()).toContain('为什么出现')
    expect(wrapper.text()).toContain('知微参考它来贴合你的偏好：用户希望主界面保持轻量。')
    expect(wrapper.text()).toContain('后续影响')
    expect(wrapper.text()).toContain('会影响语气、方案取舍和界面建议')
    expect(wrapper.text()).toContain('用户希望主界面保持轻量。')

    await findButtonByText(wrapper, '记忆管理')?.trigger('click')

    expect(mocks.push).toHaveBeenCalledWith({
      name: 'memories',
      query: {
        tab: 'entities',
        entityId: 'memory-1',
      },
    })

    await findButtonByText(wrapper, '重试')?.trigger('click')
    await flushPromises()

    expect(mocks.getEntity).toHaveBeenCalledTimes(2)
    expect(mocks.getEntityProvenances).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('来源：对话抽取')
    expect(wrapper.text()).not.toContain('实体索引暂不可用')
  })

  it('来源证据可以跳回原始会话位置', async () => {
    mocks.getEntity.mockResolvedValueOnce(entity())
    mocks.getEntityProvenances.mockResolvedValueOnce([provenance])

    const wrapper = mount(MemoryInsightPanel, {
      props: { source },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    await findButtonByText(wrapper, '打开来源会话')?.trigger('click')

    expect(mocks.push).toHaveBeenCalledWith({
      name: 'conversationDetail',
      params: { sessionId: 'session-1' },
      query: {
        turnId: 'turn-1',
        entryId: 'entry-1',
      },
    })
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('知识库来源证据可以跳回原始文档', async () => {
    mocks.getEntity.mockResolvedValueOnce(entity())
    mocks.getEntityProvenances.mockResolvedValueOnce([{
      ...provenance,
      originType: 'KNOWLEDGE_BASE_DOCUMENT',
      sourceSessionId: null,
      sourceTurnId: null,
      sourceEntryId: 'chunk-7',
      sourceDocumentId: 'doc-1',
      sourceDocumentName: '第一章.md',
      sourceKnowledgeBaseId: 'kb-1',
      sourceKnowledgeBaseName: '小说知识库',
      evidenceExcerpt: '主角偏好低饱和界面',
    }])

    const wrapper = mount(MemoryInsightPanel, {
      props: { source },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('打开来源文档')

    await findButtonByText(wrapper, '打开来源文档')?.trigger('click')

    expect(mocks.push).toHaveBeenCalledWith({
      name: 'knowledgeBaseDocumentDetail',
      params: { id: 'kb-1', docId: 'doc-1' },
      query: { chunkId: 'chunk-7' },
    })
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('本轮沉淀记忆展示项目上下文归属', async () => {
    mocks.getEntity.mockResolvedValueOnce(entity({ spaceId: 'space-project' }))
    mocks.getEntityProvenances.mockResolvedValueOnce([provenance])

    const wrapper = mount(MemoryInsightPanel, {
      props: {
        source: {
          ...source,
          extra: {
            ...source.extra,
            operationLabel: '新增',
            sourceKindLabel: '项目上下文',
            evidenceExcerpt: '主界面做轻',
          },
        },
        projectId: 'project-1',
      },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    expect(mocks.getEntity).toHaveBeenCalledWith('memory-1', 'project-1')
    expect(wrapper.text()).toContain('项目上下文 · 本轮新增的记忆')
    expect(wrapper.text()).toContain('因为你本轮提到「主界面做轻」，知微已在项目上下文新增这条偏好。')
  })

  it('本轮忘记记忆时直接展示解释而不请求已归档实体', async () => {
    const wrapper = mount(MemoryInsightPanel, {
      props: {
        source: {
          ...source,
          extra: {
            entityTypeLabel: '偏好',
            operation: 'DELETE',
            operationLabel: '忘记',
            evidenceExcerpt: '以后不要再提醒我下午5点检查日志',
          },
        },
      },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    expect(mocks.getEntity).not.toHaveBeenCalled()
    expect(mocks.getEntityProvenances).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('本轮忘记的记忆')
    expect(wrapper.text()).toContain('主界面偏好')
    expect(wrapper.text()).toContain('已忘记')
    expect(wrapper.text()).toContain('因为你本轮提到「以后不要再提醒我下午5点检查日志」，知微已忘记这条偏好。')
    expect(wrapper.text()).toContain('后续不再默认参考这条记忆；需要时可以重新告诉知微要记住什么')
    expect(wrapper.text()).toContain('这条记忆已经从后续默认参考中移除')
    expect(findButtonByText(wrapper, '编辑')).toBeUndefined()
    expect(findButtonByText(wrapper, '忘记')).toBeUndefined()
  })

  it('可以直接编辑记忆名称描述和属性', async () => {
    mocks.getEntity.mockResolvedValueOnce(entity())
    mocks.getEntityProvenances.mockResolvedValueOnce([provenance])
    mocks.updateEntity.mockResolvedValueOnce(entity({
      name: '极简主界面偏好',
      description: '用户偏好极简、轻量的主界面。',
      properties: { surface: 'minimal' },
      importanceScore: 0.85,
      version: 2,
    }))

    const wrapper = mount(MemoryInsightPanel, {
      props: { source },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    await findButtonByText(wrapper, '编辑')?.trigger('click')
    expect(wrapper.text()).toContain('高级属性')
    expect(wrapper.text()).toContain('参考强度')
    expect(wrapper.text()).toContain('70% · 按需参考')
    expect(wrapper.text()).not.toContain('改动预览')
    expect(findButtonByText(wrapper, '保存')?.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('属性 JSON')
    expect(wrapper.findAll('textarea')).toHaveLength(1)
    expect(wrapper.find('input[type="number"]').exists()).toBe(false)

    await findButtonByText(wrapper, '高级属性')?.trigger('click')
    expect(wrapper.text()).toContain('属性 JSON')
    expect(wrapper.find('button[aria-expanded="true"]').exists()).toBe(true)

    await wrapper.find('input:not([type="number"])').setValue('极简主界面偏好')
    const textareas = wrapper.findAll('textarea')
    await textareas[0].setValue('用户偏好极简、轻量的主界面。')
    await textareas[1].setValue('{"surface":"minimal"}')
    await wrapper.findComponent(Slider).vm.$emit('update:modelValue', [85])

    expect(wrapper.text()).toContain('保存后影响')
    expect(wrapper.text()).toContain('85% · 优先参考')
    expect(wrapper.text()).toContain('优先级较高')
    expect(wrapper.text()).toContain('用户偏好极简、轻量的主界面。')
    expect(wrapper.text()).toContain('改动预览')
    expect(wrapper.text()).toContain('将更新：名称、描述、参考强度、高级属性')
    expect(findButtonByText(wrapper, '保存')?.attributes('disabled')).toBeUndefined()

    await findButtonByText(wrapper, '保存')?.trigger('click')
    await flushPromises()

    expect(mocks.updateEntity).toHaveBeenCalledWith(
      'memory-1',
      {
        name: '极简主界面偏好',
        description: '用户偏好极简、轻量的主界面。',
        properties: { surface: 'minimal' },
        importanceScore: 0.85,
      },
      undefined,
    )
    expect(wrapper.emitted('updated')?.[0][0]).toMatchObject({
      id: 'memory-1',
      name: '极简主界面偏好',
      description: '用户偏好极简、轻量的主界面。',
      version: 2,
    })
    expect(wrapper.text()).toContain('极简主界面偏好')
    expect(wrapper.text()).toContain('用户偏好极简、轻量的主界面。')
  })

  it('并发加载记忆详情和来源证据，避免来源等待详情返回', async () => {
    let resolveEntity!: (value: EntityDetail) => void
    mocks.getEntity.mockReturnValueOnce(new Promise<EntityDetail>((resolve) => {
      resolveEntity = resolve
    }))
    mocks.getEntityProvenances.mockResolvedValueOnce([provenance])

    const wrapper = mount(MemoryInsightPanel, {
      props: { source },
      global: {
        plugins: [createPinia()],
      },
    })

    expect(mocks.getEntity).toHaveBeenCalledWith('memory-1', undefined)
    expect(mocks.getEntityProvenances).toHaveBeenCalledWith('memory-1', { projectId: undefined })

    resolveEntity(entity())
    await flushPromises()

    expect(wrapper.text()).toContain('主界面偏好')
    expect(wrapper.text()).toContain('我喜欢轻量主界面')
  })

  it('快速切换记忆时忽略旧请求的迟到结果', async () => {
    let resolveFirst!: (value: EntityDetail) => void
    const nextSource: SourceSummary = {
      type: 'memory',
      id: 'memory-2',
      name: '写作偏好',
      extra: {
        entityTypeLabel: '偏好',
        description: '用户希望写作简洁。',
      },
    }

    mocks.getEntity.mockReturnValueOnce(new Promise<EntityDetail>((resolve) => {
      resolveFirst = resolve
    }))
    mocks.getEntityProvenances.mockResolvedValueOnce([])
    mocks.getEntity.mockResolvedValueOnce(entity({
      id: 'memory-2',
      name: '写作偏好',
      description: '用户希望写作简洁。',
    }))
    mocks.getEntityProvenances.mockResolvedValueOnce([])

    const wrapper = mount(MemoryInsightPanel, {
      props: { source },
      global: {
        plugins: [createPinia()],
      },
    })

    await wrapper.setProps({ source: nextSource })
    await flushPromises()

    expect(wrapper.text()).toContain('写作偏好')

    resolveFirst(entity({ name: '迟到的主界面偏好' }))
    await flushPromises()

    expect(wrapper.text()).toContain('写作偏好')
    expect(wrapper.text()).not.toContain('迟到的主界面偏好')
  })

  it('可以在对话内确认忘记当前记忆', async () => {
    mocks.getEntity.mockResolvedValueOnce(entity())
    mocks.getEntityProvenances.mockResolvedValueOnce([provenance])
    mocks.deleteEntity.mockResolvedValueOnce(undefined)

    const wrapper = mount(MemoryInsightPanel, {
      props: { source },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    await findButtonByText(wrapper, '忘记')?.trigger('click')
    expect(wrapper.text()).toContain('这会把当前记忆从可用记忆中归档')

    await findButtonByText(wrapper, '确认忘记')?.trigger('click')
    await flushPromises()

    expect(mocks.deleteEntity).toHaveBeenCalledWith('memory-1', undefined)
    expect(wrapper.emitted('deleted')?.[0]).toEqual([
      'memory-1',
      expect.objectContaining({
        id: 'memory-1',
        name: '主界面偏好',
        extra: expect.objectContaining({
          operation: 'DELETE',
          operationLabel: '忘记',
          description: '用户希望主界面保持轻量。',
          entityTypeLabel: '偏好',
        }),
      }),
    ])
    expect(wrapper.text()).toContain('已忘记')
    expect(wrapper.text()).toContain('后续不再默认参考这条记忆')
    expect(wrapper.text()).toContain('需要重新启用时，直接在对话里告诉知微新的偏好或事实即可')
    expect(findButtonByText(wrapper, '确认忘记')).toBeUndefined()
  })

  it('项目会话内查看编辑和忘记记忆时带上项目上下文', async () => {
    mocks.getEntity.mockResolvedValue(entity())
    mocks.getEntityProvenances.mockResolvedValue([provenance])
    mocks.updateEntity.mockResolvedValueOnce(entity({
      name: '项目内主界面偏好',
      description: '项目里希望主界面保持轻量。',
    }))
    mocks.deleteEntity.mockResolvedValueOnce(undefined)

    const wrapper = mount(MemoryInsightPanel, {
      props: { source, projectId: 'project-1' },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    expect(mocks.getEntity).toHaveBeenCalledWith('memory-1', 'project-1')
    expect(mocks.getEntityProvenances).toHaveBeenCalledWith('memory-1', { projectId: 'project-1' })

    await findButtonByText(wrapper, '编辑')?.trigger('click')
    await wrapper.find('input:not([type="number"])').setValue('项目内主界面偏好')
    await wrapper.find('textarea').setValue('项目里希望主界面保持轻量。')
    await findButtonByText(wrapper, '保存')?.trigger('click')
    await flushPromises()

    expect(mocks.updateEntity).toHaveBeenCalledWith(
      'memory-1',
      expect.objectContaining({
        name: '项目内主界面偏好',
        description: '项目里希望主界面保持轻量。',
      }),
      'project-1',
    )

    await findButtonByText(wrapper, '忘记')?.trigger('click')
    await findButtonByText(wrapper, '确认忘记')?.trigger('click')
    await flushPromises()

    expect(mocks.deleteEntity).toHaveBeenCalledWith('memory-1', 'project-1')
  })

  it('项目会话内打开记忆管理时保留项目上下文', async () => {
    mocks.getEntity.mockResolvedValueOnce(entity())
    mocks.getEntityProvenances.mockResolvedValueOnce([provenance])

    const wrapper = mount(MemoryInsightPanel, {
      props: { source, projectId: 'project-1' },
      global: {
        plugins: [createPinia()],
      },
    })
    await flushPromises()

    await findButtonByText(wrapper, '打开记忆管理')?.trigger('click')

    expect(mocks.push).toHaveBeenCalledWith({
      name: 'memories',
      query: {
        tab: 'entities',
        entityId: 'memory-1',
        projectId: 'project-1',
      },
    })
  })
})
