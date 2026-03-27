import { beforeEach, describe, expect, it, vi } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import DatastoreView from './DatastoreView.vue'

const mocks = vi.hoisted(() => ({
  datastoreStore: {
    list: [] as Array<{
      id: string
      name: string
      description?: string | null
      type: string
      propertiesJson?: string | null
      projectionConfigJson?: string | null
      metadataJson?: string | null
      createdBy?: string | null
      createdAt: string
      updatedAt: string
    }>,
    loading: false,
    error: null as string | null,
    fetchList: vi.fn(),
  },
  router: {
    push: vi.fn(),
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
  mocks.datastoreStore.list = [
    {
      id: 'ds-1',
      name: 'novel-workspace',
      description: '小说创作素材库',
      type: 'DOCUMENT',
      propertiesJson: JSON.stringify([
        { name: 'title', type: 'TEXT', required: true },
        { name: 'chapter', type: 'NUMBER', required: false },
      ]),
      projectionConfigJson: '{"scalarPaths":["title"]}',
      metadataJson: null,
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
    expect(wrapper.text()).toContain('DOCUMENT')
    expect(wrapper.text()).toContain('title、chapter')

    await wrapper.get('article').trigger('click')

    expect(mocks.router.push).toHaveBeenCalledWith({
      name: 'datastoreDetail',
      params: { id: 'ds-1' },
    })
  })
})
