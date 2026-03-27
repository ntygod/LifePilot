import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import DatastoreDetailView from './DatastoreDetailView.vue'

const mocks = vi.hoisted(() => ({
  datastoreApi: {
    get: vi.fn(),
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
})

describe('DatastoreDetailView', () => {
  it('加载并展示 Datastore 结构与配置', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(mocks.datastoreApi.get).toHaveBeenCalledWith('ds-1')
    expect(wrapper.text()).toContain('novel-workspace')
    expect(wrapper.text()).toContain('DOCUMENT')
    expect(wrapper.text()).toContain('title')
    expect(wrapper.text()).toContain('chapter')
    expect(wrapper.text()).toContain('tester')
    expect(wrapper.text()).toContain('scalarPaths')
    expect(wrapper.text()).toContain('owner')
  })
})
