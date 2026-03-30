import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import SettingsChannelsView from './SettingsChannelsView.vue'

const mocks = vi.hoisted(() => ({
  uiStore: {
    showToast: vi.fn(),
  },
  channelApi: {
    listPlugins: vi.fn(),
    listInstances: vi.fn(),
    listInstanceEvents: vi.fn(),
    createInstance: vi.fn(),
    updateInstance: vi.fn(),
    startInstance: vi.fn(),
    stopInstance: vi.fn(),
    reloadInstance: vi.fn(),
    checkHealth: vi.fn(),
    deleteInstance: vi.fn(),
  },
  marketplaceApi: {
    getInstallation: vi.fn(),
    getInstallationAssetUrl: vi.fn(),
    getInstallationAssetText: vi.fn(),
  },
}))

vi.mock('@/api/client', () => ({
  channelApi: mocks.channelApi,
}))

vi.mock('@/api/marketplace', () => ({
  marketplaceApi: mocks.marketplaceApi,
}))

vi.mock('@/stores/ui', () => ({
  useUiStore: () => mocks.uiStore,
}))

function mountView() {
  return shallowMount(SettingsChannelsView, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        Badge: { template: '<span><slot /></span>' },
        Button: {
          template: '<button v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>',
        },
        Input: {
          props: ['modelValue'],
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
        Select: { template: '<div><slot /></div>' },
        SelectContent: { template: '<div><slot /></div>' },
        SelectItem: { template: '<div><slot /></div>' },
        SelectTrigger: { template: '<div><slot /></div>' },
        SelectValue: { template: '<div><slot /></div>' },
        Skeleton: { template: '<div />' },
        StatePanel: { template: '<div><slot name="icon" /><slot name="actions" /><slot /></div>' },
        SettingItem: { template: '<div><slot /></div>' },
        SettingSection: { template: '<section><slot name="header-actions" /><slot /></section>' },
        Switch: {
          props: ['modelValue'],
          template: '<button type="button" @click="$emit(\'update:modelValue\', !modelValue)"><slot /></button>',
        },
        Textarea: {
          props: ['modelValue'],
          template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
      },
    },
  })
}

beforeEach(() => {
  mocks.uiStore.showToast.mockReset()
  mocks.channelApi.listPlugins.mockReset()
  mocks.channelApi.listInstances.mockReset()
  mocks.channelApi.listInstanceEvents.mockReset()
  mocks.marketplaceApi.getInstallation.mockReset()
  mocks.marketplaceApi.getInstallationAssetUrl.mockReset()
  mocks.marketplaceApi.getInstallationAssetText.mockReset()

  mocks.channelApi.listPlugins.mockResolvedValue([
    {
      pluginId: 'feishu',
      name: '飞书',
      version: '1.0.0',
      vendor: 'zhiwei-official',
      platform: 'feishu',
      connectorMode: 'EXTERNAL',
      connectorSpec: {
        managed: {
          strategy: 'installed-jar',
          artifactPath: 'dist/feishu-connector.jar',
          healthPath: '/actuator/health',
          preferredPort: 19091,
          available: true,
          resolution: 'installed-artifact',
        },
      },
      capabilities: ['receive', 'send'],
      configSchema: { type: 'object', properties: {}, required: [] },
      secretFields: [],
      setupGuide: {
        title: '飞书接入',
        steps: ['创建应用', '开启事件订阅'],
      },
      resources: {
        readmePath: 'docs/README.md',
        iconPath: null,
        examplePaths: ['examples/websocket.json'],
        assetPaths: [],
      },
    },
  ])
  mocks.channelApi.listInstances.mockResolvedValue([
    {
      instanceId: 'feishu.prod',
      pluginId: 'feishu',
      platform: 'feishu',
      displayName: '飞书生产机器人',
      enabled: true,
      status: 'RUNNING',
      config: {},
      secretConfig: {},
      routingPolicy: null,
      lastHeartbeatAt: '2026-03-29T00:00:00Z',
      lastError: null,
      createdAt: '2026-03-29T00:00:00Z',
      updatedAt: '2026-03-29T00:00:00Z',
    },
  ])
  mocks.channelApi.listInstanceEvents.mockResolvedValue([])
  mocks.marketplaceApi.getInstallation.mockResolvedValue({
    packageId: 'feishu',
    type: 'CHANNEL',
    name: '飞书',
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
  mocks.marketplaceApi.getInstallationAssetText.mockResolvedValue('# 飞书接入\n请启用 WebSocket 模式。')
  mocks.marketplaceApi.getInstallationAssetUrl.mockImplementation(
    (id: string, relativePath: string) => `/api/marketplace/extensions/${id}/assets/file?path=${encodeURIComponent(relativePath)}`
  )
})

describe('SettingsChannelsView', () => {
  it('选中实例后会展示插件安装资产并自动预览 README', async () => {
    const wrapper = mountView()

    await flushPromises()
    await flushPromises()

    expect(mocks.marketplaceApi.getInstallation).toHaveBeenCalledWith('feishu')
    expect(mocks.marketplaceApi.getInstallationAssetText).toHaveBeenCalledWith('feishu', 'docs/README.md')
    expect(wrapper.text()).toContain('插件安装资产')
    expect(wrapper.text()).toContain('D:/plugins/feishu')
    expect(wrapper.text()).toContain('channel-plugin.json')
    expect(wrapper.text()).toContain('websocket.json')
    expect(wrapper.text()).toContain('# 飞书接入')
  })

  it('没有安装快照时显示内建插件提示且不弹错误 Toast', async () => {
    mocks.marketplaceApi.getInstallation.mockRejectedValueOnce({
      code: 404,
      message: 'not found',
      timestamp: '2026-03-29T00:00:00Z',
    })

    const wrapper = mountView()

    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('当前插件没有 Marketplace 安装快照')
    expect(mocks.uiStore.showToast).not.toHaveBeenCalled()
  })

  it('内建本地插件不会请求安装快照', async () => {
    mocks.channelApi.listPlugins.mockResolvedValueOnce([
      {
        pluginId: 'webui',
        name: 'Web UI',
        version: '1.0.0',
        vendor: 'zhiwei',
        platform: 'web',
        connectorMode: 'LOCAL',
        connectorSpec: {
          transport: 'http+sse',
        },
        capabilities: ['receive', 'send'],
        configSchema: { type: 'object', properties: {}, required: [] },
        secretFields: [],
        setupGuide: {
          title: 'Web UI 内建渠道',
          steps: ['无需额外安装。'],
        },
        resources: null,
      },
    ])
    mocks.channelApi.listInstances.mockResolvedValueOnce([
      {
        instanceId: 'web.default',
        pluginId: 'webui',
        platform: 'web',
        displayName: '默认 Web UI',
        enabled: true,
        status: 'RUNNING',
        config: {},
        secretConfig: {},
        routingPolicy: null,
        lastHeartbeatAt: '2026-03-29T00:00:00Z',
        lastError: null,
        createdAt: '2026-03-29T00:00:00Z',
        updatedAt: '2026-03-29T00:00:00Z',
      },
    ])

    const wrapper = mountView()

    await flushPromises()
    await flushPromises()

    expect(mocks.marketplaceApi.getInstallation).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('当前插件是系统内建渠道，不使用 Marketplace 安装快照。')
  })

  it('官方托管插件会展示自动托管提示', async () => {
    const wrapper = mountView()

    await flushPromises()
    await flushPromises()

    expect(wrapper.text()).toContain('已附带可执行 connector 产物')
  })
})
