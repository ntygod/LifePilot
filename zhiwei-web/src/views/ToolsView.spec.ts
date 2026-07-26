import { flushPromises, shallowMount } from '@vue/test-utils'
import ToolsView from './ToolsView.vue'

const mocks = vi.hoisted(() => ({
  route: {
    query: {} as Record<string, string>,
  },
  routerPush: vi.fn(),
  toolStore: {
    loading: false,
    error: null as string | null,
    tools: [
      {
        id: 'web.search',
        name: 'web.search',
        displayName: '联网搜索',
        description: '搜索公开网页资料',
        source: 'builtin',
        riskLevel: 'LOW',
        type: 'QUERY',
        idempotent: true,
      },
      {
        id: 'file.read',
        name: 'file.read',
        displayName: '读取文件',
        description: '读取本地文件内容',
        source: 'builtin',
        riskLevel: 'LOW',
        type: 'FILE',
        idempotent: true,
      },
    ],
    fetchTools: vi.fn(),
  },
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({
    push: mocks.routerPush,
  }),
}))

vi.mock('@/stores/tool', () => ({
  useToolStore: () => mocks.toolStore,
}))

function mountView() {
  return shallowMount(ToolsView, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        Badge: { template: '<span><slot /></span>' },
        Button: {
          template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>',
        },
        PageContainer: { template: '<div><slot /></div>' },
        PageHeader: {
          props: ['title', 'description'],
          template: '<header><h1>{{ title }}</h1><p>{{ description }}</p></header>',
        },
        SearchBar: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<input :value="modelValue" aria-label="工具搜索" />',
        },
        Select: { template: '<div><slot /></div>' },
        SelectContent: { template: '<div><slot /></div>' },
        SelectItem: { template: '<div><slot /></div>' },
        SelectTrigger: { template: '<button><slot /></button>' },
        SelectValue: { template: '<span><slot /></span>' },
        Skeleton: { template: '<div />' },
        StatePanel: { template: '<section><slot name="actions" /><slot /></section>' },
      },
    },
  })
}

beforeEach(() => {
  mocks.route.query = {}
  mocks.routerPush.mockReset()
  mocks.toolStore.loading = false
  mocks.toolStore.error = null
  mocks.toolStore.fetchTools.mockReset().mockResolvedValue(undefined)
})

describe('ToolsView', () => {
  it('会用路由 query 初始化工具搜索', async () => {
    mocks.route.query = {
      query: 'web.search',
    }

    const wrapper = mountView()
    await flushPromises()

    expect(mocks.toolStore.fetchTools).toHaveBeenCalled()
    expect(wrapper.find('input[aria-label="工具搜索"]').attributes('value')).toBe('web.search')
    expect(wrapper.text()).toContain('结果 1')
    expect(wrapper.text()).toContain('联网搜索')
    expect(wrapper.text()).not.toContain('读取文件')
  })
})
