<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { AcceptableValue } from 'reka-ui'
import { Activity, CircleAlert, Globe, Play, Plus, RefreshCw, RotateCcw, Server, Square, Trash2 } from 'lucide-vue-next'
import { channelApi } from '@/api/client'
import { logger } from '@/utils/logger'
import { marketplaceApi } from '@/api/marketplace'
import type {
  ChannelConfigSchemaProperty,
  ChannelHealthStatus,
  ChannelInstance,
  ChannelInstanceEvent,
  ChannelPluginDescriptor,
  ExtensionInstallation,
  InstalledExtensionAsset,
} from '@/types'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import FormSheetShell from '@/components/common/FormSheetShell.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import SettingItem from '@/components/settings/SettingItem.vue'
import SettingSection from '@/components/settings/SettingSection.vue'
import { useUiStore } from '@/stores/ui'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

type SelectValueType = AcceptableValue | undefined
type FieldRecord = Record<string, unknown>

interface NormalizedChannelField {
  name: string
  label: string
  description?: string
  type: 'string' | 'number' | 'integer' | 'boolean'
  options: string[]
  secret: boolean
  required: boolean
  defaultValue?: unknown
}

const INVALID_JSON = Symbol('invalid-json')
const uiStore = useUiStore()

const loading = ref(true)
const refreshing = ref(false)
const creating = ref(false)
const instanceAction = ref<'save' | 'start' | 'stop' | 'reload' | 'health' | 'delete' | null>(null)
const showDeleteConfirm = ref(false)
const eventsLoading = ref(false)
const installationLoading = ref(false)
const assetPreviewLoading = ref(false)

const plugins = ref<ChannelPluginDescriptor[]>([])
const instances = ref<ChannelInstance[]>([])
const instanceEvents = ref<ChannelInstanceEvent[]>([])
const selectedInstanceId = ref<string | null>(null)
const createPluginId = ref<string | null>(null)
const healthStatus = ref<ChannelHealthStatus | null>(null)
const pluginInstallation = ref<ExtensionInstallation | null>(null)
const installationMessage = ref<string | null>(null)
const assetPreviewPath = ref<string | null>(null)
const assetPreviewContent = ref('')
const assetPreviewError = ref<string | null>(null)

const createDisplayName = ref('')
const createInstanceId = ref('')
const createEnabled = ref(false)
const createUseCustomBaseUrl = ref(false)
const createConfig = ref<FieldRecord>({})
const createSecrets = ref<FieldRecord>({})
const createRoutingPolicyText = ref('')

const editDisplayName = ref('')
const editEnabled = ref(false)
const editUseCustomBaseUrl = ref(false)
const editConfig = ref<FieldRecord>({})
const editSecrets = ref<FieldRecord>({})
const editInitialSecrets = ref<FieldRecord>({})
const editRoutingPolicyText = ref('')

const statusLabelMap: Record<ChannelInstance['status'], string> = {
  CREATED: '已创建',
  STARTING: '启动中',
  RUNNING: '运行中',
  STOPPING: '停止中',
  STOPPED: '已停止',
  ERROR: '异常',
}

const pluginMap = computed(() => new Map(plugins.value.map(plugin => [plugin.pluginId, plugin])))
const selectedInstance = computed(() => (
  instances.value.find(instance => instance.instanceId === selectedInstanceId.value) ?? null
))
const selectedPlugin = computed(() => (
  selectedInstance.value ? pluginMap.value.get(selectedInstance.value.pluginId) ?? null : null
))
const createPlugin = computed(() => (
  createPluginId.value ? pluginMap.value.get(createPluginId.value) ?? null : null
))
const createPluginFields = computed(() => normalizePluginFields(createPlugin.value, {
  includeConnectorBaseUrl: shouldShowConnectorBaseUrlField(createPlugin.value, createUseCustomBaseUrl.value),
}))
const selectedPluginFields = computed(() => normalizePluginFields(selectedPlugin.value, {
  includeConnectorBaseUrl: shouldShowConnectorBaseUrlField(selectedPlugin.value, editUseCustomBaseUrl.value),
}))
const selectedInstanceLocked = computed(() => selectedInstance.value?.instanceId === 'web.default')
const installationAssets = computed(() => pluginInstallation.value?.assets ?? [])
const installationReadmeAsset = computed(() => findInstallationAsset('README'))
const installationIconAsset = computed(() => findInstallationAsset('ICON'))
const installationExampleAssets = computed(() => filterInstallationAssets('EXAMPLE'))
const installationExtraAssets = computed(() => filterInstallationAssets('ASSET'))

let installationRequestId = 0
let assetPreviewRequestId = 0

const summaryItems = computed(() => ([
  { label: '可用插件', value: String(plugins.value.length), hint: '支持接入的消息平台数量。' },
  { label: '已创建实例', value: String(instances.value.length), hint: '每个实例对应一个平台的接入配置。' },
  { label: '运行中', value: String(instances.value.filter(instance => instance.status === 'RUNNING').length), hint: '当前正在接收和投递消息的实例。' },
  { label: '需要外部服务', value: String(plugins.value.filter(plugin => plugin.connectorMode === 'EXTERNAL').length), hint: '飞书、企微、钉钉等需要启动对应的连接服务。' },
]))

watch(createPlugin, (plugin) => {
  resetCreateForm(plugin)
}, { immediate: true })

watch([selectedInstance, selectedPlugin], ([instance, plugin]) => {
  resetEditForm(instance, plugin)
  healthStatus.value = null
}, { immediate: true })

watch(selectedPlugin, (plugin) => {
  void loadPluginInstallation(plugin)
}, { immediate: true })

onMounted(() => {
  void loadData()
})

function normalizeSelectValue(value: SelectValueType): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number') return String(value)
  return ''
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function formatDateTime(value?: string | null, fallback = '未记录') {
  if (!value) return fallback
  return new Date(value).toLocaleString('zh-CN')
}

function connectorModeLabel(mode: ChannelPluginDescriptor['connectorMode']) {
  return mode === 'LOCAL' ? '内建' : '外部'
}

function connectorModeVariant(mode: ChannelPluginDescriptor['connectorMode']) {
  return mode === 'LOCAL' ? 'secondary' : 'outline'
}

function statusLabel(status: ChannelInstance['status']) {
  return statusLabelMap[status] ?? status
}

function statusVariant(status: ChannelInstance['status']) {
  if (status === 'RUNNING') return 'secondary'
  if (status === 'ERROR') return 'destructive'
  return 'outline'
}

function eventTypeLabel(eventType: string) {
  const labels: Record<string, string> = {
    INSTANCE_CREATED: '实例已创建',
    INSTANCE_UPDATED: '实例已更新',
    INSTANCE_STARTED: '实例已启动',
    INSTANCE_STOPPED: '实例已停止',
    INSTANCE_RELOADED: '实例已重载',
    INSTANCE_HEARTBEAT: '收到心跳',
    INSTANCE_ERROR: '实例异常',
    HEALTH_CHECK: '健康检查',
    INGRESS_EVENT_PROCESSED: '入站事件完成',
    INGRESS_EVENT_FAILED: '入站事件失败',
    DELIVERY_DISPATCHED: '出站投递完成',
  }
  return labels[eventType] ?? eventType
}

function eventVariant(eventType: string) {
  if (eventType.endsWith('_FAILED') || eventType.endsWith('_ERROR') || eventType === 'INSTANCE_ERROR') {
    return 'destructive'
  }
  if (eventType === 'INSTANCE_STARTED' || eventType === 'HEALTH_CHECK' || eventType === 'INGRESS_EVENT_PROCESSED') {
    return 'secondary'
  }
  return 'outline'
}

function hasEventPayload(event: ChannelInstanceEvent) {
  return Boolean(event.payload) && Object.keys(event.payload ?? {}).length > 0
}

function healthTone() {
  if (!healthStatus.value) return 'default'
  return healthStatus.value.healthy ? 'default' : 'danger'
}

function getErrorMessage(error: unknown, fallback: string) {
  if (error && typeof error === 'object' && 'message' in error && typeof error.message === 'string' && error.message.trim()) {
    return error.message
  }
  return fallback
}

function pluginGuideSteps(plugin: ChannelPluginDescriptor | null) {
  const steps = plugin?.setupGuide?.steps
  if (!Array.isArray(steps)) return []
  return steps.filter((step): step is string => typeof step === 'string' && step.trim().length > 0)
}

function pluginGuideTitle(plugin: ChannelPluginDescriptor | null) {
  return typeof plugin?.setupGuide?.title === 'string' && plugin.setupGuide.title.trim()
    ? plugin.setupGuide.title
    : '接入说明'
}

function managedConnectorConfig(plugin: ChannelPluginDescriptor | null) {
  const raw = plugin?.connectorSpec?.managed
  return isPlainObject(raw) ? raw : null
}

function hasManagedConnector(plugin: ChannelPluginDescriptor | null) {
  return Boolean(managedConnectorConfig(plugin))
}

function managedConnectorAvailable(plugin: ChannelPluginDescriptor | null) {
  return managedConnectorConfig(plugin)?.available === true
}

function hasManualConnectorBaseUrl(config?: Record<string, unknown> | null) {
  return typeof config?.baseUrl === 'string' && config.baseUrl.trim().length > 0
    || typeof config?.connectorBaseUrl === 'string' && config.connectorBaseUrl.trim().length > 0
}

function shouldShowConnectorBaseUrlField(plugin: ChannelPluginDescriptor | null, useCustomBaseUrl: boolean) {
  if (!plugin || plugin.connectorMode !== 'EXTERNAL') {
    // 非外部连接模式不显示切换提示
    return true
  }
  if (!hasManagedConnector(plugin)) {
    return true
  }
  if (!managedConnectorAvailable(plugin)) {
    return true
  }
  return useCustomBaseUrl
}

function updateCreateCustomBaseUrl(nextValue: boolean) {
  if (createPlugin.value && hasManagedConnector(createPlugin.value) && !managedConnectorAvailable(createPlugin.value)) {
    createUseCustomBaseUrl.value = true
    return
  }
  createUseCustomBaseUrl.value = nextValue
}

function updateEditCustomBaseUrl(nextValue: boolean) {
  if (selectedPlugin.value && hasManagedConnector(selectedPlugin.value) && !managedConnectorAvailable(selectedPlugin.value)) {
    editUseCustomBaseUrl.value = true
    return
  }
  editUseCustomBaseUrl.value = nextValue
}

function managedConnectorToggleDescription(plugin: ChannelPluginDescriptor | null) {
  if (!plugin || plugin.connectorMode !== 'EXTERNAL' || !hasManagedConnector(plugin)) {
    return '该平台需要通过外部连接服务接入。'
  }
  if (managedConnectorAvailable(plugin)) {
    return '连接服务已就绪，可自动运行。开启此选项后需手动填写自定义服务地址。'
  }
  return '连接服务暂未就绪，请先安装对应插件或手动填写服务地址。'
}

function isNotFoundError(error: unknown) {
  if (!error || typeof error !== 'object' || !('code' in error)) {
    return false
  }
  return Number((error as { code?: unknown }).code) === 404
}

function assetKindLabel(kind: string) {
  const labelMap: Record<string, string> = {
    README: 'README',
    ICON: '图标',
    EXAMPLE: '示例',
    ASSET: '附加资产',
  }
  return labelMap[kind] ?? kind
}

function assetFileName(asset: InstalledExtensionAsset) {
  const segments = asset.relativePath.split(/[\\/]/).filter(Boolean)
  return segments[segments.length - 1] ?? asset.relativePath
}

function installationAssetUrl(asset: InstalledExtensionAsset) {
  return marketplaceApi.getInstallationAssetUrl(assetKey(), asset.relativePath)
}

function assetKey() {
  return pluginInstallation.value?.packageId ?? selectedPlugin.value?.pluginId ?? ''
}

function isTextPreviewable(asset: InstalledExtensionAsset) {
  if (asset.kind === 'README' || asset.kind === 'EXAMPLE') return true
  const lowerPath = asset.relativePath.toLowerCase()
  return [
    '.md',
    '.txt',
    '.json',
    '.yaml',
    '.yml',
    '.toml',
    '.properties',
    '.env',
    '.xml',
    '.html',
  ].some(suffix => lowerPath.endsWith(suffix))
}

function findInstallationAsset(kind: string) {
  return installationAssets.value.find(asset => asset.kind === kind) ?? null
}

function filterInstallationAssets(kind: string) {
  return installationAssets.value.filter(asset => asset.kind === kind)
}

function resetInstallationPreview() {
  assetPreviewRequestId += 1
  assetPreviewPath.value = null
  assetPreviewContent.value = ''
  assetPreviewError.value = null
  assetPreviewLoading.value = false
}

async function loadPluginInstallation(plugin: ChannelPluginDescriptor | null) {
  const currentRequestId = ++installationRequestId
  installationLoading.value = Boolean(plugin)
  pluginInstallation.value = null
  installationMessage.value = null
  resetInstallationPreview()

  if (!plugin) {
    installationLoading.value = false
    return
  }

  if (plugin.connectorMode === 'LOCAL') {
    installationMessage.value = '当前插件是系统内建渠道，不使用 Marketplace 安装快照。'
    installationLoading.value = false
    return
  }

  try {
    const installation = await marketplaceApi.getInstallation(plugin.pluginId)
    if (currentRequestId !== installationRequestId) return
    pluginInstallation.value = installation
    installationMessage.value = installation.assets?.length
      ? null
      : '当前插件已安装，但没有声明额外的 README、图标或示例资产。'
    const previewAsset = installation.assets?.find(asset => asset.kind === 'README')
      ?? installation.assets?.find(asset => asset.kind === 'EXAMPLE')
      ?? installation.assets?.find(asset => isTextPreviewable(asset))
      ?? null
    if (previewAsset) {
      await previewInstallationAsset(previewAsset)
    }
  } catch (error) {
    if (currentRequestId !== installationRequestId) return
    if (isNotFoundError(error)) {
      installationMessage.value = '当前插件没有 Marketplace 安装快照，通常表示这是内建插件。'
      return
    }
    logger.error('加载插件安装快照失败:', error)
    installationMessage.value = getErrorMessage(error, '加载插件安装快照失败')
    uiStore.showToast('error', installationMessage.value)
  } finally {
    if (currentRequestId === installationRequestId) {
      installationLoading.value = false
    }
  }
}

async function previewInstallationAsset(asset: InstalledExtensionAsset) {
  if (!pluginInstallation.value) return

  assetPreviewPath.value = asset.relativePath
  assetPreviewContent.value = ''
  assetPreviewError.value = null

  if (!isTextPreviewable(asset)) {
    assetPreviewLoading.value = false
    assetPreviewError.value = '该资产为二进制文件，请通过下方链接直接打开。'
    return
  }

  const currentRequestId = ++assetPreviewRequestId
  assetPreviewLoading.value = true
  try {
    const content = await marketplaceApi.getInstallationAssetText(pluginInstallation.value.packageId, asset.relativePath)
    if (currentRequestId !== assetPreviewRequestId) return
    assetPreviewContent.value = content
  } catch (error) {
    if (currentRequestId !== assetPreviewRequestId) return
    logger.error('读取插件安装资产失败:', error)
    assetPreviewError.value = getErrorMessage(error, '读取插件安装资产失败')
    uiStore.showToast('error', assetPreviewError.value)
  } finally {
    if (currentRequestId === assetPreviewRequestId) {
      assetPreviewLoading.value = false
    }
  }
}

function sortPlugins(items: ChannelPluginDescriptor[]) {
  return [...items].sort((left, right) => left.name.localeCompare(right.name, 'zh-CN'))
}

function sortInstances(items: ChannelInstance[]) {
  return [...items].sort((left, right) => {
    if (left.instanceId === 'web.default') return -1
    if (right.instanceId === 'web.default') return 1
    if (left.enabled !== right.enabled) return left.enabled ? -1 : 1
    return left.displayName.localeCompare(right.displayName, 'zh-CN')
  })
}

async function loadData(showSuccessToast = false) {
  const firstLoad = loading.value
  if (!firstLoad) {
    refreshing.value = true
  }
  try {
    const [pluginList, instanceList] = await Promise.all([
      channelApi.listPlugins(),
      channelApi.listInstances(),
    ])
    plugins.value = sortPlugins(pluginList)
    instances.value = sortInstances(instanceList)
    if (!selectedInstanceId.value || !instances.value.some(instance => instance.instanceId === selectedInstanceId.value)) {
      selectedInstanceId.value = instances.value[0]?.instanceId ?? null
    }
    if (createPluginId.value && !plugins.value.some(plugin => plugin.pluginId === createPluginId.value)) {
      createPluginId.value = null
    }
    if (selectedInstanceId.value) {
      await loadInstanceEvents(selectedInstanceId.value)
    } else {
      instanceEvents.value = []
    }
    if (showSuccessToast) {
      uiStore.showToast('success', '渠道信息已刷新')
    }
  } catch (error) {
    logger.error('加载渠道信息失败:', error)
    uiStore.showToast('error', getErrorMessage(error, '加载渠道信息失败'))
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

async function loadInstanceEvents(instanceId: string) {
  eventsLoading.value = true
  try {
    instanceEvents.value = await channelApi.listInstanceEvents(instanceId, 20)
  } catch (error) {
    logger.error('加载渠道实例事件失败:', error)
    instanceEvents.value = []
    uiStore.showToast('error', getErrorMessage(error, '加载渠道实例事件失败'))
  } finally {
    eventsLoading.value = false
  }
}

function normalizePluginFields(plugin: ChannelPluginDescriptor | null, options?: { includeConnectorBaseUrl?: boolean }): NormalizedChannelField[] {
  const schema = plugin?.configSchema
  if (!schema || !isPlainObject(schema.properties)) {
    return []
  }
  const requiredFields = new Set(Array.isArray(schema.required) ? schema.required : [])
  const priorityFields = ['baseUrl', 'connectorBaseUrl', 'connectionMode']
  const includeConnectorBaseUrl = options?.includeConnectorBaseUrl ?? true

  return Object.entries(schema.properties)
    .map(([name, rawProperty]) => {
      const property = isPlainObject(rawProperty) ? rawProperty as ChannelConfigSchemaProperty : {}
      const type = property.type === 'boolean' || property.type === 'number' || property.type === 'integer'
        ? property.type
        : 'string'
      const options = Array.isArray(property.enum)
        ? property.enum.map(option => String(option))
        : []

      return {
        name,
        label: typeof property.title === 'string' && property.title.trim() ? property.title : name,
        description: typeof property.description === 'string' ? property.description : undefined,
        type,
        options,
        secret: Boolean(property.secret) || Boolean(plugin?.secretFields.includes(name)),
        required: requiredFields.has(name),
        defaultValue: property.default,
      } satisfies NormalizedChannelField
    })
    .filter(field => includeConnectorBaseUrl || (field.name !== 'baseUrl' && field.name !== 'connectorBaseUrl'))
    .sort((left, right) => {
      const leftPriority = priorityFields.indexOf(left.name)
      const rightPriority = priorityFields.indexOf(right.name)
      if (leftPriority !== rightPriority) {
        if (leftPriority === -1) return 1
        if (rightPriority === -1) return -1
        return leftPriority - rightPriority
      }
      if (left.required !== right.required) {
        return left.required ? -1 : 1
      }
      return left.label.localeCompare(right.label, 'zh-CN')
    })
}

function defaultFieldValue(field: NormalizedChannelField) {
  if (field.defaultValue !== undefined) {
    return field.defaultValue
  }
  return field.type === 'boolean' ? false : ''
}

function buildFieldState(
  plugin: ChannelPluginDescriptor | null,
  config?: Record<string, unknown> | null,
  secretConfig?: Record<string, unknown> | null,
) {
  const configValues: FieldRecord = {}
  const secretValues: FieldRecord = {}
  const initialSecretValues: FieldRecord = {}

  for (const field of normalizePluginFields(plugin)) {
    const source = field.secret ? secretConfig : config
    const value = source?.[field.name] ?? defaultFieldValue(field)
    if (field.secret) {
      secretValues[field.name] = value
      initialSecretValues[field.name] = value
    } else {
      configValues[field.name] = value
    }
  }

  return { configValues, secretValues, initialSecretValues }
}

function resetCreateForm(plugin: ChannelPluginDescriptor | null) {
  createDisplayName.value = plugin ? `${plugin.name} 实例` : ''
  createInstanceId.value = ''
  createEnabled.value = plugin?.connectorMode === 'LOCAL' || managedConnectorAvailable(plugin)
  createUseCustomBaseUrl.value = Boolean(plugin && hasManagedConnector(plugin) && !managedConnectorAvailable(plugin))
  createRoutingPolicyText.value = ''
  const state = buildFieldState(plugin)
  createConfig.value = state.configValues
  createSecrets.value = state.secretValues
}

function resetEditForm(instance: ChannelInstance | null, plugin: ChannelPluginDescriptor | null) {
  if (!instance) {
    editDisplayName.value = ''
    editEnabled.value = false
    editConfig.value = {}
    editSecrets.value = {}
    editInitialSecrets.value = {}
    editRoutingPolicyText.value = ''
    return
  }

  editDisplayName.value = instance.displayName
  editEnabled.value = instance.enabled
  editUseCustomBaseUrl.value = hasManualConnectorBaseUrl(instance.config)
    || Boolean(plugin && hasManagedConnector(plugin) && !managedConnectorAvailable(plugin))
  editRoutingPolicyText.value = instance.routingPolicy ? JSON.stringify(instance.routingPolicy, null, 2) : ''
  const state = buildFieldState(plugin, instance.config, instance.secretConfig)
  editConfig.value = state.configValues
  editSecrets.value = state.secretValues
  editInitialSecrets.value = state.initialSecretValues
}

function fieldTextValue(record: FieldRecord, field: NormalizedChannelField) {
  const value = record[field.name]
  if (field.type === 'number' || field.type === 'integer') {
    if (typeof value === 'number') return value
    if (typeof value === 'string') return value
    return value == null ? '' : String(value)
  }
  return typeof value === 'string' ? value : (value == null ? '' : String(value))
}

function fieldSelectValue(record: FieldRecord, field: NormalizedChannelField) {
  const value = record[field.name]
  if (value == null || value === '') {
    return '__empty__'
  }
  return String(value)
}

function fieldBooleanValue(record: FieldRecord, field: NormalizedChannelField) {
  return record[field.name] === true
}

function updateField(recordRef: { value: FieldRecord }, field: NormalizedChannelField, rawValue: unknown) {
  let nextValue: unknown = rawValue
  if (field.type === 'boolean') {
    nextValue = rawValue === true
  } else if (field.type === 'number' || field.type === 'integer') {
    if (rawValue === '' || rawValue == null) {
      nextValue = ''
    } else {
      const parsed = Number(rawValue)
      nextValue = Number.isFinite(parsed) ? (field.type === 'integer' ? Math.round(parsed) : parsed) : rawValue
    }
  } else {
    nextValue = rawValue == null ? '' : String(rawValue)
  }

  recordRef.value = {
    ...recordRef.value,
    [field.name]: nextValue,
  }
}

function updateCreateField(field: NormalizedChannelField, rawValue: unknown) {
  updateField(field.secret ? createSecrets : createConfig, field, rawValue)
}

function updateEditField(field: NormalizedChannelField, rawValue: unknown) {
  updateField(field.secret ? editSecrets : editConfig, field, rawValue)
}

function isFieldEmpty(field: NormalizedChannelField, value: unknown) {
  if (field.type === 'boolean') return false
  if (field.type === 'number' || field.type === 'integer') {
    if (value === '' || value == null) return true
    return !Number.isFinite(Number(value))
  }
  return String(value ?? '').trim() === ''
}

function validateRequiredFields(fields: NormalizedChannelField[], configValues: FieldRecord, secretValues: FieldRecord) {
  for (const field of fields) {
    if (!field.required) continue
    const value = field.secret ? secretValues[field.name] : configValues[field.name]
    if (isFieldEmpty(field, value)) {
      return `${field.label} 不能为空`
    }
  }
  return null
}

function normalizePayloadValue(field: NormalizedChannelField, rawValue: unknown) {
  if (field.type === 'boolean') {
    return rawValue === true
  }
  if (field.type === 'number' || field.type === 'integer') {
    if (rawValue === '' || rawValue == null) return ''
    const parsed = Number(rawValue)
    return Number.isFinite(parsed) ? (field.type === 'integer' ? Math.round(parsed) : parsed) : rawValue
  }
  return typeof rawValue === 'string' ? rawValue.trim() : (rawValue == null ? '' : String(rawValue))
}

function buildCreateConfigPayload(fields: NormalizedChannelField[], configValues: FieldRecord) {
  const payload: FieldRecord = {}
  for (const field of fields) {
    if (field.secret) continue
    const normalized = normalizePayloadValue(field, configValues[field.name])
    if (field.type === 'boolean' || field.type === 'number' || field.type === 'integer') {
      payload[field.name] = normalized
    } else if (String(normalized).trim() !== '') {
      payload[field.name] = normalized
    }
  }
  return payload
}

function buildCreateSecretPayload(fields: NormalizedChannelField[], secretValues: FieldRecord) {
  const payload: FieldRecord = {}
  for (const field of fields) {
    if (!field.secret) continue
    const normalized = normalizePayloadValue(field, secretValues[field.name])
    if (String(normalized ?? '').trim() !== '') {
      payload[field.name] = normalized
    }
  }
  return payload
}

function buildUpdateConfigPayload(instance: ChannelInstance, fields: NormalizedChannelField[], configValues: FieldRecord) {
  const payload: FieldRecord = { ...(instance.config ?? {}) }
  for (const field of fields) {
    if (field.secret) continue
    payload[field.name] = normalizePayloadValue(field, configValues[field.name])
  }
  const exposesBaseUrl = fields.some(field => field.name === 'baseUrl' || field.name === 'connectorBaseUrl')
  if (!exposesBaseUrl) {
    delete payload.baseUrl
    delete payload.connectorBaseUrl
  }
  return payload
}

function buildUpdateSecretPayload(
  fields: NormalizedChannelField[],
  secretValues: FieldRecord,
  initialSecretValues: FieldRecord,
) {
  const payload: FieldRecord = {}
  for (const field of fields) {
    if (!field.secret) continue
    const current = normalizePayloadValue(field, secretValues[field.name])
    const initial = normalizePayloadValue(field, initialSecretValues[field.name])
    if (String(current ?? '') !== String(initial ?? '')) {
      payload[field.name] = current
    }
  }
  return payload
}

function parseJsonObject(text: string, fieldLabel: string) {
  if (!text.trim()) {
    return null
  }
  try {
    const parsed = JSON.parse(text)
    if (!isPlainObject(parsed)) {
      uiStore.showToast('error', `${fieldLabel} 必须是 JSON 对象`)
      return INVALID_JSON
    }
    return parsed
  } catch (error) {
    logger.error(`解析 ${fieldLabel} 失败:`, error)
    uiStore.showToast('error', `${fieldLabel} 格式不合法`)
    return INVALID_JSON
  }
}

function startCreate(pluginId: string) {
  createPluginId.value = pluginId
}

function cancelCreate() {
  createPluginId.value = null
}

async function createInstance() {
  if (!createPlugin.value) return

  const validationMessage = validateRequiredFields(createPluginFields.value, createConfig.value, createSecrets.value)
  if (validationMessage) {
    uiStore.showToast('error', validationMessage)
    return
  }

  const routingPolicy = parseJsonObject(createRoutingPolicyText.value, '新建实例的路由策略 JSON')
  if (routingPolicy === INVALID_JSON) return

  creating.value = true
  try {
    const created = await channelApi.createInstance({
      pluginId: createPlugin.value.pluginId,
      instanceId: createInstanceId.value.trim() || undefined,
      displayName: createDisplayName.value.trim() || undefined,
      enabled: createEnabled.value,
      config: buildCreateConfigPayload(createPluginFields.value, createConfig.value),
      secretConfig: buildCreateSecretPayload(createPluginFields.value, createSecrets.value),
      routingPolicy,
    })
    uiStore.showToast('success', '渠道实例已创建')
    createPluginId.value = null
    await loadData()
    selectedInstanceId.value = created.instanceId
    await loadInstanceEvents(created.instanceId)
  } catch (error) {
    logger.error('创建渠道实例失败:', error)
    uiStore.showToast('error', getErrorMessage(error, '创建渠道实例失败'))
    await loadData()
  } finally {
    creating.value = false
  }
}

async function saveSelectedInstance() {
  if (!selectedInstance.value || !selectedPlugin.value) return

  const validationMessage = validateRequiredFields(selectedPluginFields.value, editConfig.value, editSecrets.value)
  if (validationMessage) {
    uiStore.showToast('error', validationMessage)
    return
  }

  const routingPolicy = parseJsonObject(editRoutingPolicyText.value, '实例路由策略 JSON')
  if (routingPolicy === INVALID_JSON) return

  instanceAction.value = 'save'
  try {
    const updated = await channelApi.updateInstance(selectedInstance.value.instanceId, {
      displayName: editDisplayName.value.trim() || selectedInstance.value.displayName,
      enabled: editEnabled.value,
      config: buildUpdateConfigPayload(selectedInstance.value, selectedPluginFields.value, editConfig.value),
      secretConfig: buildUpdateSecretPayload(selectedPluginFields.value, editSecrets.value, editInitialSecrets.value),
      routingPolicy,
    })
    uiStore.showToast('success', '实例配置已保存')
    await loadData()
    selectedInstanceId.value = updated.instanceId
    await loadInstanceEvents(updated.instanceId)
  } catch (error) {
    logger.error('保存渠道实例失败:', error)
    uiStore.showToast('error', getErrorMessage(error, '保存渠道实例失败'))
  } finally {
    instanceAction.value = null
  }
}

async function runInstanceAction(action: 'start' | 'stop' | 'reload' | 'health' | 'delete') {
  if (!selectedInstance.value) return
  if (action === 'delete') {
    showDeleteConfirm.value = true
    return
  }

  instanceAction.value = action
  try {
    if (action === 'health') {
      healthStatus.value = await channelApi.getHealth(selectedInstance.value.instanceId)
      await loadInstanceEvents(selectedInstance.value.instanceId)
      uiStore.showToast('success', healthStatus.value.healthy ? '健康检查通过' : '健康检查返回异常')
      return
    }

    if (action === 'start') {
      await channelApi.startInstance(selectedInstance.value.instanceId)
      uiStore.showToast('success', '实例已启动')
    } else if (action === 'stop') {
      await channelApi.stopInstance(selectedInstance.value.instanceId)
      uiStore.showToast('success', '实例已停止')
    } else if (action === 'reload') {
      await channelApi.reloadInstance(selectedInstance.value.instanceId)
      uiStore.showToast('success', '实例已重载')
    }

    const currentInstanceId = selectedInstance.value.instanceId
    await loadData()
    selectedInstanceId.value = currentInstanceId
    await loadInstanceEvents(currentInstanceId)
  } catch (error) {
    logger.error(`执行实例动作失败: ${action}`, error)
    uiStore.showToast('error', getErrorMessage(error, '实例操作失败'))
  } finally {
    instanceAction.value = null
  }
}

async function confirmDeleteInstance() {
  if (!selectedInstance.value) return
  instanceAction.value = 'delete'
  try {
    await channelApi.deleteInstance(selectedInstance.value.instanceId)
    uiStore.showToast('success', '实例已删除')
    await loadData()
  } catch (error) {
    logger.error('删除实例失败', error)
    uiStore.showToast('error', getErrorMessage(error, '删除实例失败'))
  } finally {
    instanceAction.value = null
  }
}

function selectInstance(instanceId: string) {
  selectedInstanceId.value = instanceId
  void loadInstanceEvents(instanceId)
}

function fieldDescription(field: NormalizedChannelField, mode: 'create' | 'edit') {
  if (field.description) return field.description
  if (field.secret && mode === 'edit') return '敏感字段默认显示脱敏值，不修改即可保留原值。'
  if (field.secret) return '敏感信息将加密保存，不会明文显示。'
  return '由插件 schema 驱动的实例配置字段。'
}

function connectorHint(plugin: ChannelPluginDescriptor | null) {
  if (!plugin) return ''
  if (plugin.connectorMode === 'LOCAL') {
    return '内建插件，无需额外配置即可使用。'
  }
  if (hasManagedConnector(plugin) && managedConnectorAvailable(plugin)) {
    const resolution = managedConnectorConfig(plugin)?.resolution
    if (resolution === 'installed-artifact') {
      return '已安装连接服务，创建实例后可直接启用。'
    }
    return '连接服务可自动运行，通常无需手动配置。'
  }
  if (hasManagedConnector(plugin)) {
    return '连接服务暂不可用，请安装对应插件或手动填写服务地址。'
  }
  return '需要先启动对应平台的连接服务，再创建实例。'
}
</script>

<template>
  <div class="space-y-6">
    <StatePanel
      title="渠道管理"
      description="管理消息平台的接入配置，将知微连接到飞书、钉钉、企微等平台。"
    >
      <template #icon>
        <Activity class="size-5" />
      </template>
      <template #actions>
        <Button variant="outline" class="gap-2" :disabled="refreshing" @click="loadData(true)">
          <RefreshCw class="size-4" />
          {{ refreshing ? '刷新中...' : '刷新' }}
        </Button>
      </template>

      <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <div
          v-for="item in summaryItems"
          :key="item.label"
          class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4"
        >
          <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">{{ item.label }}</div>
          <div class="mt-2 text-xl font-semibold text-foreground">{{ item.value }}</div>
          <div class="mt-1 text-sm leading-6 text-muted-foreground">{{ item.hint }}</div>
        </div>
      </div>
    </StatePanel>

    <div v-if="loading" class="space-y-4">
      <Skeleton class="h-44 w-full rounded-[calc(var(--radius)+8px)]" />
      <Skeleton class="h-80 w-full rounded-[calc(var(--radius)+8px)]" />
      <Skeleton class="h-96 w-full rounded-[calc(var(--radius)+8px)]" />
    </div>

    <template v-else>
      <section class="detail-card p-6">
        <div class="flex flex-col gap-4 border-b border-border/70 pb-4 lg:flex-row lg:items-start lg:justify-between">
          <div class="space-y-1">
            <div class="surface-label">平台插件</div>
            <h3 class="text-lg font-semibold text-foreground">可接入的平台</h3>
            <p class="max-w-[52rem] text-sm leading-6 text-muted-foreground">
              选择要接入的消息平台，创建实例后配置对应的凭证即可开始使用。
            </p>
          </div>
        </div>

        <div class="mt-6 grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <article
            v-for="plugin in plugins"
            :key="plugin.pluginId"
            class="rounded-[calc(var(--radius)+8px)] border border-border/70 bg-background/70 px-4 py-3"
          >
            <div class="flex items-center justify-between gap-2">
              <div class="flex min-w-0 flex-wrap items-center gap-2">
                <h4 class="text-sm font-semibold text-foreground">{{ plugin.name }}</h4>
                <Badge :variant="connectorModeVariant(plugin.connectorMode)" class="text-[0.65rem]">
                  {{ connectorModeLabel(plugin.connectorMode) }}
                </Badge>
                <Badge variant="outline" class="text-[0.65rem]">{{ plugin.platform }}</Badge>
                <Badge variant="outline" class="text-[0.65rem]">{{ plugin.version }}</Badge>
              </div>
              <Button size="sm" class="shrink-0 gap-1.5 text-xs" @click="startCreate(plugin.pluginId)">
                <Plus class="size-3.5" />
                创建实例
              </Button>
            </div>

            <details class="mt-2">
              <summary class="cursor-pointer select-none text-xs text-muted-foreground hover:text-foreground">
                查看详情
              </summary>
              <div class="mt-3 space-y-3 border-t border-border/50 pt-3">
                <p class="text-sm leading-6 text-muted-foreground">
                  {{ connectorHint(plugin) }}
                </p>

                <div class="space-y-1.5">
                  <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">能力面</div>
                  <div class="flex flex-wrap gap-1.5">
                    <Badge v-for="capability in plugin.capabilities" :key="capability" variant="outline" class="text-[0.65rem]">
                      {{ capability }}
                    </Badge>
                  </div>
                </div>

                <div v-if="pluginGuideSteps(plugin).length" class="space-y-1.5">
                  <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">{{ pluginGuideTitle(plugin) }}</div>
                  <ol class="space-y-1 text-sm leading-6 text-muted-foreground">
                    <li
                      v-for="(step, index) in pluginGuideSteps(plugin)"
                      :key="`${plugin.pluginId}-${index}`"
                      class="flex gap-2"
                    >
                      <span class="font-medium text-foreground">{{ index + 1 }}.</span>
                      <span>{{ step }}</span>
                    </li>
                  </ol>
                </div>
              </div>
            </details>
          </article>
        </div>
      </section>

      <FormSheetShell
        v-if="createPlugin"
        :open="!!createPluginId"
        :title="`新建 ${createPlugin.name} 实例`"
        :description="connectorHint(createPlugin)"
        @update:open="value => { if (!value) cancelCreate() }"
      >
        <div class="divide-y divide-border/60">
          <SettingItem label="实例名称" description="显示在侧边栏和通知中的名称。" required>
            <Input v-model="createDisplayName" class="w-[320px]" placeholder="例如：飞书生产机器人" />
          </SettingItem>

          <SettingItem label="实例 ID" description="可选。留空时由系统自动生成稳定实例 ID。">
            <Input v-model="createInstanceId" class="w-[320px]" placeholder="例如：feishu.prod" />
          </SettingItem>

          <SettingItem
            label="创建后启用"
            :description="managedConnectorAvailable(createPlugin)
              ? '连接服务已就绪，创建后可直接启用。'
              : '建议先配置好服务地址再启用。'"
          >
            <Switch
              :model-value="createEnabled"
              @update:model-value="value => createEnabled = value === true"
            />
          </SettingItem>

          <SettingItem
            v-if="hasManagedConnector(createPlugin)"
            label="使用自定义 Connector 地址"
            :description="managedConnectorToggleDescription(createPlugin)"
          >
            <Switch
              :model-value="createUseCustomBaseUrl"
              @update:model-value="value => updateCreateCustomBaseUrl(value === true)"
            />
          </SettingItem>

          <template v-if="createPluginFields.length > 0">
            <SettingItem
              v-for="field in createPluginFields"
              :key="`create-${field.name}`"
              :label="field.label"
              :description="fieldDescription(field, 'create')"
              :required="field.required"
            >
              <template v-if="field.options.length > 0">
                <Select
                  :model-value="fieldSelectValue(field.secret ? createSecrets : createConfig, field)"
                  @update:model-value="value => updateCreateField(field, normalizeSelectValue(value) === '__empty__' ? '' : normalizeSelectValue(value))"
                >
                  <SelectTrigger class="w-[280px]">
                    <SelectValue placeholder="请选择" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem v-if="!field.required" value="__empty__">未设置</SelectItem>
                    <SelectItem v-for="option in field.options" :key="option" :value="option">
                      {{ option }}
                    </SelectItem>
                  </SelectContent>
                </Select>
              </template>

              <template v-else-if="field.type === 'boolean'">
                <Switch
                  :model-value="fieldBooleanValue(field.secret ? createSecrets : createConfig, field)"
                  @update:model-value="value => updateCreateField(field, value === true)"
                />
              </template>

              <template v-else>
                <Input
                  :model-value="fieldTextValue(field.secret ? createSecrets : createConfig, field)"
                  :type="field.secret ? 'password' : (field.type === 'number' || field.type === 'integer' ? 'number' : 'text')"
                  class="w-[320px]"
                  :placeholder="field.label"
                  @update:model-value="value => updateCreateField(field, value)"
                />
              </template>
            </SettingItem>
          </template>

          <div v-else class="py-4 text-sm text-muted-foreground">
            该插件无需额外配置，创建后即可使用。
          </div>

          <div class="pt-2">
            <details class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-muted/20 px-4 py-4">
              <summary class="cursor-pointer select-none text-sm font-medium text-foreground">
                高级选项
              </summary>
              <div class="mt-4 space-y-4">
                <SettingItem label="路由策略 JSON" description="可选。用于覆盖默认的实例级路由策略。">
                  <Textarea
                    v-model="createRoutingPolicyText"
                    rows="5"
                    class="max-w-[680px] font-mono text-xs"
                    placeholder="{&#10;  &quot;defaultAgentId&quot;: &quot;assistant&quot;&#10;}"
                  />
                </SettingItem>
              </div>
            </details>
          </div>
        </div>

        <template #footer>
          <div class="flex items-center justify-between gap-3">
            <Badge :variant="connectorModeVariant(createPlugin.connectorMode)">
              {{ connectorModeLabel(createPlugin.connectorMode) }}
            </Badge>
            <div class="flex items-center gap-2">
              <Button variant="outline" size="sm" @click="cancelCreate">取消</Button>
              <Button size="sm" :disabled="creating" @click="createInstance">
                {{ creating ? '创建中...' : '创建实例' }}
              </Button>
            </div>
          </div>
        </template>
      </FormSheetShell>

      <div class="grid gap-6 xl:grid-cols-[360px_minmax(0,1fr)]">
        <section class="detail-card p-6">
          <div class="flex items-center justify-between border-b border-border/70 pb-4">
            <div>
              <div class="surface-label">实例列表</div>
              <h3 class="text-lg font-semibold text-foreground">已创建连接</h3>
            </div>
            <Badge variant="outline">{{ instances.length }} 个实例</Badge>
          </div>

          <div class="mt-4 space-y-1.5">
            <button
              v-for="instance in instances"
              :key="instance.instanceId"
              type="button"
              class="flex w-full items-center gap-2 rounded-[calc(var(--radius)+6px)] border px-3 py-2.5 text-left transition-colors"
              :class="selectedInstanceId === instance.instanceId
                ? 'border-primary/30 bg-primary/5'
                : 'border-border/70 bg-background/70 hover:border-primary/20 hover:bg-muted/20'"
              @click="selectInstance(instance.instanceId)"
            >
              <div class="min-w-0 flex-1 truncate text-sm font-medium text-foreground">{{ instance.displayName }}</div>
              <Badge :variant="statusVariant(instance.status)" class="shrink-0 text-[0.65rem]">{{ statusLabel(instance.status) }}</Badge>
              <Badge variant="outline" class="shrink-0 text-[0.65rem]">{{ instance.platform }}</Badge>
            </button>
          </div>
        </section>

        <section class="detail-card p-6">
          <div v-if="selectedInstance && selectedPlugin" class="space-y-6">
            <div class="flex flex-col gap-4 border-b border-border/70 pb-4 xl:flex-row xl:items-start xl:justify-between">
              <div class="space-y-2">
                <div class="surface-label">实例详情</div>
                <div class="flex flex-wrap items-center gap-2">
                  <h3 class="text-lg font-semibold text-foreground">{{ selectedInstance.displayName }}</h3>
                  <Badge :variant="statusVariant(selectedInstance.status)">{{ statusLabel(selectedInstance.status) }}</Badge>
                  <Badge :variant="connectorModeVariant(selectedPlugin.connectorMode)">
                    {{ connectorModeLabel(selectedPlugin.connectorMode) }}
                  </Badge>
                  <Badge variant="outline">{{ selectedInstance.platform }}</Badge>
                </div>
                <p class="text-sm leading-6 text-muted-foreground">
                  实例 ID：{{ selectedInstance.instanceId }}，插件：{{ selectedPlugin.name }}。{{ connectorHint(selectedPlugin) }}
                </p>
              </div>

              <div class="flex flex-wrap items-center gap-2">
                <Button variant="outline" size="sm" :disabled="instanceAction !== null" @click="runInstanceAction('health')">
                  健康检查
                </Button>
                <Button
                  v-if="selectedInstance.status !== 'RUNNING'"
                  variant="outline"
                  size="sm"
                  class="gap-2"
                  :disabled="instanceAction !== null"
                  @click="runInstanceAction('start')"
                >
                  <Play class="size-4" />
                  启动
                </Button>
                <Button
                  v-else
                  variant="outline"
                  size="sm"
                  class="gap-2"
                  :disabled="instanceAction !== null"
                  @click="runInstanceAction('stop')"
                >
                  <Square class="size-4" />
                  停止
                </Button>
                <Button variant="outline" size="sm" class="gap-2" :disabled="instanceAction !== null" @click="runInstanceAction('reload')">
                  <RotateCcw class="size-4" />
                  重载
                </Button>
                <Button variant="destructive" size="sm" class="gap-2" :disabled="instanceAction !== null || selectedInstanceLocked" @click="runInstanceAction('delete')">
                  <Trash2 class="size-4" />
                  删除
                </Button>
              </div>
            </div>

            <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
              <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4">
                <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">插件</div>
                <div class="mt-2 text-sm font-medium text-foreground">{{ selectedPlugin.name }}</div>
              </div>
              <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4">
                <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">最近心跳</div>
                <div class="mt-2 text-sm font-medium text-foreground">{{ formatDateTime(selectedInstance.lastHeartbeatAt, '暂无') }}</div>
              </div>
              <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4">
                <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">创建时间</div>
                <div class="mt-2 text-sm font-medium text-foreground">{{ formatDateTime(selectedInstance.createdAt) }}</div>
              </div>
              <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4">
                <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">最近更新</div>
                <div class="mt-2 text-sm font-medium text-foreground">{{ formatDateTime(selectedInstance.updatedAt) }}</div>
              </div>
            </div>

            <Tabs default-value="config" class="w-full">
              <TabsList class="w-full justify-start">
                <TabsTrigger value="config">实例配置</TabsTrigger>
                <TabsTrigger value="assets">插件资产</TabsTrigger>
                <TabsTrigger value="events">
                  最近事件
                  <Badge v-if="instanceEvents.length > 0" variant="secondary" class="ml-1.5 text-[0.6rem] leading-none">{{ instanceEvents.length }}</Badge>
                </TabsTrigger>
              </TabsList>

              <TabsContent value="config" class="mt-4">
              <section class="rounded-[calc(var(--radius)+8px)] border border-border/70 bg-background/70 p-4">
              <SettingSection
                title="实例配置"
                description="根据插件定义的配置项进行设置。"
              >
                <template #header-actions>
                  <Badge v-if="selectedInstanceLocked" variant="outline">内建实例</Badge>
                  <Button size="sm" :disabled="instanceAction !== null" @click="saveSelectedInstance">
                    {{ instanceAction === 'save' ? '保存中...' : '保存实例配置' }}
                  </Button>
                </template>

                <SettingItem label="实例名称" description="显示在侧边栏和通知中的名称。" required>
                  <Input v-model="editDisplayName" class="w-[320px]" placeholder="实例名称" />
                </SettingItem>

                <SettingItem label="启用实例" description="关闭后仍保留配置，但不会继续接入或投递消息。">
                  <Switch
                    :model-value="editEnabled"
                    @update:model-value="value => editEnabled = value === true"
                  />
                </SettingItem>

                <SettingItem
                  v-if="hasManagedConnector(selectedPlugin)"
                  label="使用自定义 Connector 地址"
                  :description="managedConnectorToggleDescription(selectedPlugin)"
                >
                  <Switch
                    :model-value="editUseCustomBaseUrl"
                    @update:model-value="value => updateEditCustomBaseUrl(value === true)"
                  />
                </SettingItem>

                <template v-if="selectedPluginFields.length > 0">
                  <SettingItem
                    v-for="field in selectedPluginFields"
                    :key="`edit-${field.name}`"
                    :label="field.label"
                    :description="fieldDescription(field, 'edit')"
                    :required="field.required"
                  >
                    <template v-if="field.options.length > 0">
                      <Select
                        :model-value="fieldSelectValue(field.secret ? editSecrets : editConfig, field)"
                        @update:model-value="value => updateEditField(field, normalizeSelectValue(value) === '__empty__' ? '' : normalizeSelectValue(value))"
                      >
                        <SelectTrigger class="w-[280px]">
                          <SelectValue placeholder="请选择" />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem v-if="!field.required" value="__empty__">未设置</SelectItem>
                          <SelectItem v-for="option in field.options" :key="option" :value="option">
                            {{ option }}
                          </SelectItem>
                        </SelectContent>
                      </Select>
                    </template>

                    <template v-else-if="field.type === 'boolean'">
                      <Switch
                        :model-value="fieldBooleanValue(field.secret ? editSecrets : editConfig, field)"
                        @update:model-value="value => updateEditField(field, value === true)"
                      />
                    </template>

                    <template v-else>
                      <Input
                        :model-value="fieldTextValue(field.secret ? editSecrets : editConfig, field)"
                        :type="field.secret ? 'password' : (field.type === 'number' || field.type === 'integer' ? 'number' : 'text')"
                        class="w-[320px]"
                        :placeholder="field.label"
                        @update:model-value="value => updateEditField(field, value)"
                      />
                    </template>
                  </SettingItem>
                </template>

                <div v-else class="py-4 text-sm text-muted-foreground">
                  当前插件无需额外配置，使用默认设置即可。
                </div>

                <div class="pt-2">
                  <details class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-muted/20 px-4 py-4">
                    <summary class="cursor-pointer select-none text-sm font-medium text-foreground">
                      高级选项
                    </summary>
                    <div class="mt-4 space-y-4">
                      <SettingItem label="路由策略 JSON" description="可选。用于配置默认 Agent、策略覆盖或特定实例级路由规则。">
                        <Textarea
                          v-model="editRoutingPolicyText"
                          rows="5"
                          class="max-w-[760px] font-mono text-xs"
                          placeholder="{&#10;  &quot;defaultAgentId&quot;: &quot;assistant&quot;&#10;}"
                        />
                      </SettingItem>
                    </div>
                  </details>
                </div>
              </SettingSection>
            </section>

            <StatePanel
              v-if="selectedInstance.lastError"
              title="最近错误"
              :description="selectedInstance.lastError"
              tone="danger"
            >
              <template #icon>
                <CircleAlert class="size-5" />
              </template>
            </StatePanel>

            <StatePanel
              v-if="healthStatus"
              title="健康检查结果"
              :description="healthStatus.healthy ? '服务连接正常。' : '连接异常，请检查服务是否正常运行。'"
              :tone="healthTone()"
            >
              <template #icon>
                <Server class="size-5" />
              </template>
              <template #actions>
                <Badge :variant="healthStatus.healthy ? 'secondary' : 'destructive'">
                  {{ healthStatus.healthy ? 'Healthy' : 'Unhealthy' }}
                </Badge>
              </template>

              <div class="grid gap-4 md:grid-cols-2">
                <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4">
                  <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">状态</div>
                  <div class="mt-2 text-sm font-medium text-foreground">{{ statusLabel(healthStatus.status) }}</div>
                </div>
                <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4">
                  <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">连接模式</div>
                  <div class="mt-2 text-sm font-medium text-foreground">{{ connectorModeLabel(healthStatus.connectorMode) }}</div>
                </div>
              </div>

              <pre class="mt-4 overflow-x-auto rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/80 px-4 py-4 text-xs text-foreground">{{ JSON.stringify(healthStatus.details ?? {}, null, 2) }}</pre>
            </StatePanel>
              </TabsContent>

              <TabsContent value="assets" class="mt-4">
            <section class="rounded-[calc(var(--radius)+8px)] border border-border/70 bg-background/70 p-4">
              <div class="flex flex-wrap items-start justify-between gap-4">
                <div>
                  <div class="text-sm font-semibold text-foreground">插件安装资产</div>
                  <p class="mt-1 text-sm leading-6 text-muted-foreground">
                    README、图标和示例文件来自 Marketplace 的本地安装目录。内建插件通常不会生成这部分安装快照。
                  </p>
                </div>
                <Badge v-if="pluginInstallation" variant="outline">
                  {{ installationAssets.length }} 项资产
                </Badge>
              </div>

              <div v-if="installationLoading" class="mt-4 space-y-4">
                <Skeleton class="h-28 w-full rounded-[calc(var(--radius)+6px)]" />
                <Skeleton class="h-40 w-full rounded-[calc(var(--radius)+6px)]" />
              </div>

              <div
                v-else-if="!pluginInstallation"
                class="mt-4 rounded-[calc(var(--radius)+6px)] border border-dashed border-border/70 bg-muted/20 px-4 py-6 text-sm leading-6 text-muted-foreground"
              >
                {{ installationMessage ?? '当前插件还没有可展示的安装资产。' }}
              </div>

              <div v-else class="mt-4 grid gap-4 xl:grid-cols-[240px_minmax(0,1fr)]">
                <div class="space-y-4">
                  <div
                    v-if="installationIconAsset"
                    class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/80 px-4 py-4"
                  >
                    <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">插件图标</div>
                    <div class="mt-4 flex items-center justify-center rounded-[calc(var(--radius)+4px)] border border-dashed border-border/70 bg-muted/20 p-4">
                      <img
                        :src="installationAssetUrl(installationIconAsset)"
                        :alt="`${selectedPlugin.name} 图标`"
                        class="max-h-24 w-auto rounded-md object-contain"
                      />
                    </div>
                  </div>

                  <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/80 px-4 py-4">
                    <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">安装位置</div>
                    <div class="mt-2 break-all font-mono text-xs text-foreground">{{ pluginInstallation.installRootPath }}</div>

                    <div class="mt-4 text-xs uppercase tracking-[0.16em] text-muted-foreground">入口文件</div>
                    <div class="mt-2 break-all font-mono text-xs text-foreground">{{ pluginInstallation.entryPath }}</div>
                  </div>

                  <div
                    v-if="installationExtraAssets.length > 0"
                    class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/80 px-4 py-4"
                  >
                    <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">附加资产</div>
                    <div class="mt-4 space-y-2">
                      <a
                        v-for="asset in installationExtraAssets"
                        :key="`asset-link-${asset.relativePath}`"
                        :href="installationAssetUrl(asset)"
                        target="_blank"
                        rel="noreferrer"
                        class="block text-xs text-primary transition-colors hover:text-primary/80 hover:underline"
                      >
                        {{ assetKindLabel(asset.kind) }} · {{ assetFileName(asset) }}
                      </a>
                    </div>
                  </div>
                </div>

                <div class="space-y-4">
                  <div class="space-y-2">
                    <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">可预览文件</div>
                    <div class="flex flex-wrap gap-2">
                      <Button
                        v-if="installationReadmeAsset"
                        :variant="assetPreviewPath === installationReadmeAsset.relativePath ? 'default' : 'outline'"
                        size="sm"
                        @click="previewInstallationAsset(installationReadmeAsset)"
                      >
                        README
                      </Button>
                      <Button
                        v-for="asset in installationExampleAssets"
                        :key="`preview-${asset.relativePath}`"
                        :variant="assetPreviewPath === asset.relativePath ? 'default' : 'outline'"
                        size="sm"
                        @click="previewInstallationAsset(asset)"
                      >
                        {{ assetFileName(asset) }}
                      </Button>
                    </div>
                  </div>

                  <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/80 px-4 py-4">
                    <div class="flex flex-wrap items-center justify-between gap-2">
                      <div>
                        <div class="text-sm font-semibold text-foreground">文件预览</div>
                        <p class="mt-1 text-xs text-muted-foreground">
                          {{ assetPreviewPath ?? installationMessage ?? '当前插件没有可直接预览的文本资产。' }}
                        </p>
                      </div>
                      <a
                        v-if="assetPreviewPath"
                        :href="marketplaceApi.getInstallationAssetUrl(pluginInstallation.packageId, assetPreviewPath)"
                        target="_blank"
                        rel="noreferrer"
                        class="text-xs text-primary transition-colors hover:text-primary/80 hover:underline"
                      >
                        打开原文件
                      </a>
                    </div>

                    <div v-if="assetPreviewLoading" class="mt-4 space-y-4">
                      <Skeleton class="h-5 w-48 rounded-md" />
                      <Skeleton class="h-48 w-full rounded-[calc(var(--radius)+6px)]" />
                    </div>

                    <div
                      v-else-if="assetPreviewError"
                      class="mt-4 rounded-[calc(var(--radius)+6px)] border border-dashed border-border/70 bg-muted/20 px-4 py-6 text-sm leading-6 text-muted-foreground"
                    >
                      {{ assetPreviewError }}
                    </div>

                    <pre
                      v-else-if="assetPreviewContent"
                      class="mt-4 max-h-[420px] overflow-auto rounded-[calc(var(--radius)+6px)] border border-border/70 bg-muted/20 px-4 py-4 text-xs leading-6 text-foreground whitespace-pre-wrap"
                    >{{ assetPreviewContent }}</pre>

                    <div
                      v-else
                      class="mt-4 rounded-[calc(var(--radius)+6px)] border border-dashed border-border/70 bg-muted/20 px-4 py-6 text-sm leading-6 text-muted-foreground"
                    >
                      当前插件没有可直接预览的文本资产，可以通过上方按钮切换 README 或示例文件。
                    </div>
                  </div>
                </div>
              </div>
            </section>
              </TabsContent>

              <TabsContent value="events" class="mt-4">
            <section class="rounded-[calc(var(--radius)+8px)] border border-border/70 bg-background/70 p-4">
              <div class="flex items-center justify-between gap-4">
                <div>
                  <div class="text-sm font-semibold text-foreground">最近事件</div>
                  <p class="mt-1 text-sm leading-6 text-muted-foreground">
                    实例的启停、消息收发和异常都会记录在这里。
                  </p>
                </div>
                <Badge variant="outline">{{ instanceEvents.length }} 条</Badge>
              </div>

              <div v-if="eventsLoading" class="mt-4 space-y-4">
                <Skeleton class="h-20 w-full rounded-[calc(var(--radius)+6px)]" />
                <Skeleton class="h-20 w-full rounded-[calc(var(--radius)+6px)]" />
              </div>

              <div v-else-if="instanceEvents.length === 0" class="mt-4 rounded-[calc(var(--radius)+6px)] border border-dashed border-border/70 bg-muted/20 px-4 py-6 text-sm text-muted-foreground">
                当前实例还没有运行事件，启动实例或执行一次健康检查后会在这里看到轨迹。
              </div>

              <div v-else class="mt-4 space-y-4">
                <article
                  v-for="event in instanceEvents"
                  :key="event.id"
                  class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/80 px-4 py-4"
                >
                  <div class="flex flex-wrap items-center justify-between gap-2">
                    <div class="flex flex-wrap items-center gap-2">
                      <Badge :variant="eventVariant(event.eventType)">
                        {{ eventTypeLabel(event.eventType) }}
                      </Badge>
                      <div class="text-xs text-muted-foreground">{{ event.eventType }}</div>
                    </div>
                    <div class="text-xs text-muted-foreground">{{ formatDateTime(event.createdAt) }}</div>
                  </div>

                  <div v-if="event.message" class="mt-4 text-sm leading-6 text-foreground">
                    {{ event.message }}
                  </div>

                  <pre
                    v-if="hasEventPayload(event)"
                    class="mt-4 overflow-x-auto rounded-[calc(var(--radius)+6px)] border border-border/70 bg-muted/20 px-4 py-4 text-xs text-foreground"
                  >{{ JSON.stringify(event.payload ?? {}, null, 2) }}</pre>
                </article>
              </div>
            </section>

            <section class="rounded-[calc(var(--radius)+8px)] border border-border/70 bg-background/70 p-4">
              <div class="flex items-center gap-2">
                <Globe class="size-4 text-primary" />
                <div class="text-sm font-semibold text-foreground">{{ pluginGuideTitle(selectedPlugin) }}</div>
              </div>
              <ol v-if="pluginGuideSteps(selectedPlugin).length" class="mt-4 space-y-2 text-sm leading-6 text-muted-foreground">
                <li
                  v-for="(step, index) in pluginGuideSteps(selectedPlugin)"
                  :key="`guide-${selectedPlugin.pluginId}-${index}`"
                  class="flex gap-2"
                >
                  <span class="font-medium text-foreground">{{ index + 1 }}.</span>
                  <span>{{ step }}</span>
                </li>
              </ol>
              <p v-else class="mt-4 text-sm leading-6 text-muted-foreground">
                当前插件没有额外接入说明。
              </p>
            </section>
              </TabsContent>
            </Tabs>
          </div>

          <StatePanel
            v-else
            title="还没有选中实例"
            description="从左侧选择一个实例查看详情，或在上方创建新实例。"
            tone="warning"
          >
            <template #icon>
              <Plus class="size-5" />
            </template>
          </StatePanel>
        </section>
      </div>
    </template>
    <ConfirmDialog
      v-if="selectedInstance"
      :show="showDeleteConfirm"
      title="删除实例"
      :message="`确定删除渠道实例「${selectedInstance.displayName}」吗？此操作不可恢复。`"
      confirm-label="删除"
      confirm-variant="destructive"
      @confirm="confirmDeleteInstance"
      @cancel="showDeleteConfirm = false"
      @update:show="showDeleteConfirm = $event"
    />
  </div>
</template>
