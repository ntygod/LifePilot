import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import MarketplaceView from './MarketplaceView.vue'

const mocks = vi.hoisted(() => ({
  marketplaceApi: {
    getSkills: vi.fn(),
    getUpdates: vi.fn(),
    refreshIndex: vi.fn(),
    getInstallation: vi.fn(),
    getInstallationAssetText: vi.fn(),
    getInstallationAssetUrl: vi.fn(),
  },
}))

vi.mock('@/api/marketplace', () => ({
  marketplaceApi: mocks.marketplaceApi,
}))

function mountView() {
  return shallowMount(MarketplaceView, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        MetricCard: { template: '<div><slot name="icon" /><slot /></div>' },
        Pagination: { template: '<div />' },
        StatePanel: { template: '<div><slot name="icon" /><slot name="actions" /><slot /></div>' },
        PageContainer: { template: '<div><slot /></div>' },
        PageHeader: { template: '<div><slot name="actions" /><slot name="meta" /></div>' },
        PageSection: { template: '<section><slot /></section>' },
        MarketplaceFilters: { template: '<div />' },
        SkillCard: {
          props: ['skill', 'selected'],
          emits: ['inspect', 'refresh'],
          template: '<div class="skill-card-stub">{{ skill.name }}<button @click="$emit(\'inspect\', skill.id)">详情</button></div>',
        },
        Button: {
          template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>',
        },
        Skeleton: { template: '<div />' },
      },
    },
  })
}

beforeEach(() => {
  mocks.marketplaceApi.getSkills.mockReset()
  mocks.marketplaceApi.getUpdates.mockReset()
  mocks.marketplaceApi.refreshIndex.mockReset()
  mocks.marketplaceApi.getInstallation.mockReset()
  mocks.marketplaceApi.getInstallationAssetText.mockReset()
  mocks.marketplaceApi.getInstallationAssetUrl.mockReset()

  mocks.marketplaceApi.getSkills.mockResolvedValue({
    content: [
      {
        id: 'feishu',
        name: '飞书插件',
        type: 'CHANNEL',
        description: '飞书渠道插件',
        version: '1.0.0',
        author: 'zhiwei-official',
        repoUrl: 'https://example.com/feishu',
        filePath: 'plugins/channels/feishu/channel-plugin.json',
        tags: ['channel'],
        requirements: ['飞书应用'],
        minZhiweiVersion: '1.0.0',
        createdAt: '2026-03-29T00:00:00Z',
        updatedAt: '2026-03-29T00:00:00Z',
        downloads: 10,
        verified: true,
        installedVersion: '1.0.0',
        installed: true,
      },
    ],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
  })
  mocks.marketplaceApi.getUpdates.mockResolvedValue([])
  mocks.marketplaceApi.getInstallation.mockResolvedValue({
    packageId: 'feishu',
    type: 'CHANNEL',
    name: '飞书插件',
    version: '1.0.0',
    entryPath: 'D:/plugins/feishu/channel-plugin.json',
    installRootPath: 'D:/plugins/feishu',
    assets: [
      {
        kind: 'README',
        relativePath: 'docs/README.md',
        localPath: 'D:/plugins/feishu/docs/README.md',
      },
      {
        kind: 'EXAMPLE',
        relativePath: 'examples/websocket.json',
        localPath: 'D:/plugins/feishu/examples/websocket.json',
      },
    ],
  })
  mocks.marketplaceApi.getInstallationAssetText.mockResolvedValue('# 飞书插件\n默认使用 WebSocket。')
  mocks.marketplaceApi.getInstallationAssetUrl.mockImplementation(
    (id: string, relativePath: string) => `/api/marketplace/extensions/${id}/assets/file?path=${encodeURIComponent(relativePath)}`,
  )
})

describe('MarketplaceView', () => {
  it('已安装渠道插件会自动展示安装快照与 README 预览', async () => {
    const wrapper = mountView()

    await flushPromises()
    await flushPromises()

    expect(mocks.marketplaceApi.getInstallation).toHaveBeenCalledWith('feishu')
    expect(mocks.marketplaceApi.getInstallationAssetText).toHaveBeenCalledWith('feishu', 'docs/README.md')
    expect(wrapper.text()).toContain('本地安装资产')
    expect(wrapper.text()).toContain('D:/plugins/feishu')
    expect(wrapper.text()).toContain('README / 示例预览')
    expect(wrapper.text()).toContain('# 飞书插件')
  })
})
