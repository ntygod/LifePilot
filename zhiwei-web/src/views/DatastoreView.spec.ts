import { beforeEach, describe, expect, it, vi } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import DatastoreView from './DatastoreView.vue'

const mocks = vi.hoisted(() => ({
  datastoreStore: {
    list: [] as Array<{
      id: string
      name: string
      description?: string | null
      timeSeries: boolean
      fieldHintsJson?: string | null
      defaultKnowledgeBaseId?: string | null
      createdBy?: string | null
      createdAt: string
      updatedAt: string
    }>,
    loading: false,
    error: null as string | null,
    fetchList: vi.fn(),
    createDatastore: vi.fn(),
    updateDatastore: vi.fn(),
    deleteDatastore: vi.fn(),
  },
  router: {
    push: vi.fn(),
  },
  uiStore: {
    showToast: vi.fn(),
  },
}))

vi.mock('@/stores/datastore', () => ({
  useDatastoreStore: () => mocks.datastoreStore,
}))

vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router')
  return {
    ...actual,
    useRouter: () => mocks.router,
  }
})

vi.mock('@/stores/ui', () => ({
  useUiStore: () => mocks.uiStore,
}))

function mountView() {
  return shallowMount(DatastoreView, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        PageContainer: { template: '<div><slot /></div>' },
        PageHeader: { template: '<div><slot name="actions" /><slot name="meta" /><slot /></div>' },
        PageSection: { template: '<div><slot /></div>' },
        MetricCard: { template: '<div><slot name="icon" /><slot /></div>' },
        StatePanel: { template: '<div><slot name="icon" /><slot /></div>' },
        Badge: { template: '<span><slot /></span>' },
        Button: {
          template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>',
        },
        Input: {
          props: ['modelValue'],
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
        ConfirmDialog: {
          props: ['show', 'message'],
          template: `
            <div data-test="confirm-dialog" :data-show="String(show)" :data-message="message">
              <button data-test="confirm-delete" @click="$emit('confirm')">confirm</button>
            </div>
          `,
        },
        FormSheetShell: { template: '<div><slot /><slot name="footer" /></div>' },
        Label: { template: '<label><slot /></label>' },
        Select: { template: '<div><slot /></div>' },
        SelectTrigger: { template: '<div><slot /></div>' },
        SelectValue: { template: '<span />' },
        SelectContent: { template: '<div><slot /></div>' },
        SelectItem: { template: '<div><slot /></div>' },
        Textarea: { template: '<textarea />' },
        Skeleton: { template: '<div />' },
      },
    },
  })
}

beforeEach(() => {
  mocks.datastoreStore.fetchList.mockReset()
  mocks.router.push.mockReset()
  mocks.datastoreStore.loading = false
  mocks.datastoreStore.error = null
  mocks.datastoreStore.deleteDatastore.mockReset()
  mocks.datastoreStore.list = [
    {
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
      updatedAt: '2026-03-27T00:00:00Z',
    },
  ]
})

describe('DatastoreView', () => {
  it('展示 Datastore 列表并支持跳转详情', async () => {
    const wrapper = mountView()

    expect(mocks.datastoreStore.fetchList).toHaveBeenCalled()
    expect(wrapper.text()).toContain('novel-workspace')
    expect(wrapper.text()).toContain('title、chapter')
    expect(wrapper.text()).toContain('索引字段')

    await wrapper.get('article').trigger('click')

    expect(mocks.router.push).toHaveBeenCalledWith({
      name: 'datastoreDetail',
      params: { id: 'ds-1' },
    })
  })

  it('支持打开删除确认框并删除 Datastore', async () => {
    const wrapper = mountView()

    await wrapper.get('[data-test="delete-datastore-button"]').trigger('click')

    expect(mocks.router.push).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="confirm-dialog"]').attributes('data-show')).toBe('true')
    expect(wrapper.get('[data-test="confirm-dialog"]').attributes('data-message')).toContain('novel-workspace')

    await wrapper.get('[data-test="confirm-delete"]').trigger('click')

    expect(mocks.datastoreStore.deleteDatastore).toHaveBeenCalledWith('ds-1')
  })
})
