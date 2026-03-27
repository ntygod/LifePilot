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
})

describe('DatastoreDetailView', () => {
  it('加载并展示 Datastore 结构、配置与关联知识库', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(mocks.datastoreApi.get).toHaveBeenCalledWith('ds-1')
    expect(mocks.knowledgeBaseApi.list).toHaveBeenCalled()
    expect(wrapper.text()).toContain('novel-workspace')
    expect(wrapper.text()).toContain('DOCUMENT')
    expect(wrapper.text()).toContain('title')
    expect(wrapper.text()).toContain('chapter')
    expect(wrapper.text()).toContain('tester')
    expect(wrapper.text()).toContain('scalarPaths')
    expect(wrapper.text()).toContain('owner')
    expect(wrapper.text()).toContain('世界观资料库')
    expect(wrapper.text()).not.toContain('无关知识库')

    await wrapper.get('article').trigger('click')
    expect(mocks.router.push).toHaveBeenCalledWith({
      name: 'knowledgeBaseDetail',
      params: { id: 'kb-1' },
    })
  })
})
