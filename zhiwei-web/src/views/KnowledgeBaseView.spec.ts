import { beforeEach, describe, expect, it, vi } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import KnowledgeBaseView from './KnowledgeBaseView.vue'

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  fetchList: vi.fn(),
  remove: vi.fn(),
  create: vi.fn(),
  removeDocument: vi.fn(),
  datastoreFetchList: vi.fn(),
  listEnabledServices: vi.fn().mockResolvedValue([]),
  updateKnowledgeBase: vi.fn(),
  knowledgeBaseStore: {
    list: [
      {
        id: 'kb-1',
        name: '知天命',
        description: '用于测试删除交互',
        embeddingModel: '',
        tags: [],
        documentCount: 2,
        totalChunks: 6,
        createdAt: '2026-03-27T00:00:00Z',
        updatedAt: '2026-03-27T00:00:00Z',
        datastoreIds: [],
      },
    ],
    current: null,
    documents: [],
    loading: false,
    error: null as string | null,
    fetchList: undefined as unknown,
    create: undefined as unknown,
    remove: undefined as unknown,
    removeDocument: undefined as unknown,
  },
  datastoreStore: {
    list: [],
    fetchList: undefined as unknown,
  },
}))

mocks.knowledgeBaseStore.fetchList = mocks.fetchList
mocks.knowledgeBaseStore.create = mocks.create
mocks.knowledgeBaseStore.remove = mocks.remove
mocks.knowledgeBaseStore.removeDocument = mocks.removeDocument
mocks.datastoreStore.fetchList = mocks.datastoreFetchList

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push }),
}))

vi.mock('@/stores/knowledgeBase', () => ({
  useKnowledgeBaseStore: () => mocks.knowledgeBaseStore,
}))

vi.mock('@/stores/datastore', () => ({
  useDatastoreStore: () => mocks.datastoreStore,
}))

vi.mock('@/api/client', () => ({
  knowledgeBaseApi: {
    update: mocks.updateKnowledgeBase,
  },
  modelServiceApi: {
    listEnabledServices: mocks.listEnabledServices,
  },
}))

function mountView() {
  return shallowMount(KnowledgeBaseView, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        Button: {
          template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>',
        },
        Badge: { template: '<span><slot /></span>' },
        MetricCard: { template: '<div><slot name="icon" /><slot /></div>' },
        StatePanel: { template: '<div><slot name="actions" /><slot /></div>' },
        PageContainer: { template: '<div><slot /></div>' },
        PageSection: { template: '<section><slot name="actions" /><slot /></section>' },
        Dialog: { template: '<div><slot /></div>' },
        DialogContent: { template: '<div><slot /></div>' },
        DialogDescription: { template: '<div><slot /></div>' },
        DialogFooter: { template: '<div><slot /></div>' },
        DialogHeader: { template: '<div><slot /></div>' },
        DialogTitle: { template: '<div><slot /></div>' },
        Input: { template: '<input v-bind="$attrs" />' },
        Label: { template: '<label><slot /></label>' },
        Select: { template: '<div><slot /></div>' },
        SelectContent: { template: '<div><slot /></div>' },
        SelectItem: { template: '<div><slot /></div>' },
        SelectTrigger: { template: '<div><slot /></div>' },
        SelectValue: { template: '<div><slot /></div>' },
        Skeleton: { template: '<div />' },
        Textarea: { template: '<textarea v-bind="$attrs" />' },
        Checkbox: { template: '<button type="button" data-test="checkbox"><slot /></button>' },
        ConfirmDialog: {
          props: ['show', 'message'],
          template: '<div data-test="confirm-dialog" :data-show="String(show)" :data-message="message"></div>',
        },
      },
    },
  })
}

beforeEach(() => {
  mocks.push.mockReset()
  mocks.fetchList.mockReset()
  mocks.remove.mockReset()
  mocks.create.mockReset()
  mocks.removeDocument.mockReset()
  mocks.datastoreFetchList.mockReset()
  mocks.listEnabledServices.mockClear()
  mocks.updateKnowledgeBase.mockReset()
  mocks.knowledgeBaseStore.error = null
})

describe('KnowledgeBaseView 删除交互', () => {
  it('点击删除按钮会打开确认框，且不会误触发进入详情', async () => {
    const wrapper = mountView()

    const deleteButton = wrapper.find('button[title="删除"]')
    expect(deleteButton.exists()).toBe(true)

    await deleteButton.trigger('click')

    expect(mocks.push).not.toHaveBeenCalled()
    const confirmDialog = wrapper.get('[data-test="confirm-dialog"]')
    expect(confirmDialog.attributes('data-show')).toBe('true')
    expect(confirmDialog.attributes('data-message')).toContain('知天命')
  })
})
