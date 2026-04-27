<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { AcceptableValue } from 'reka-ui'
import { ArrowLeft, Info, Loader2, RefreshCw, Trash2 } from 'lucide-vue-next'
import {
  modelServiceApi,
  type CreateModelServiceRequest,
  type ModelService,
  type ModelServiceTemplate,
  type ThinkingMode,
} from '@/api/client'
import { listProviderProfiles, type ProviderProfileDto } from '@/api/providerProfile'
import { probeModels, type ModelInfo } from '@/api/probeModels'
import { logger } from '@/utils/logger'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { useUiStore } from '@/stores/ui'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip'
import {
  availableVendorsForKind,
  buildEmptyModelServiceRequest,
  buildSuggestedServiceId,
  CUSTOM_MODEL_VALUE,
  defaultModelForKind,
  findVendorTemplate,
  GENERATION_CAPABILITY_OPTIONS,
  GENERATION_SCENE_OPTIONS,
  inferVendorKey,
  KIND_OPTIONS,
  modelOptionsForKind,
  type ServiceKind,
} from './modelServiceCatalog'

type UiSelectValue = AcceptableValue | undefined
type DetailMode = 'create' | 'edit' | null

const props = withDefaults(defineProps<{
  initialMode?: 'create' | 'edit'
  initialServiceId?: string | null
}>(), {
  initialMode: undefined,
  initialServiceId: null,
})

const emit = defineEmits<{ close: [] }>()
const uiStore = useUiStore()

const services = ref<ModelService[]>([])
const templates = ref<ModelServiceTemplate[]>([])
const profiles = ref<ProviderProfileDto[]>([])
const loading = ref(true)
const detailMode = ref<DetailMode>(null)
const deletingServiceId = ref<string | null>(null)
const showDeleteConfirm = ref(false)
const vendorKey = ref<string>('openai')
const selectedModelValue = ref<string>(CUSTOM_MODEL_VALUE)
const customModelName = ref('')
const serviceIdCustomized = ref(false)
const displayNameCustomized = ref(false)

// 模型探测相关 —— Phase 8 新增。点"拉取可用模型"后填充 probedModels；
// 用户选择后通过 selectModelFromProbe() 写回 formData.modelName。
const probing = ref(false)
const probedModels = ref<ModelInfo[]>([])
const probeError = ref<string | null>(null)

const formData = ref<CreateModelServiceRequest>(buildEmptyModelServiceRequest())
const errors = ref<Record<string, string>>({})

const kindOptions = KIND_OPTIONS
const generationCapabilityOptions = GENERATION_CAPABILITY_OPTIONS
const generationSceneOptions = GENERATION_SCENE_OPTIONS

const thinkingModeOptions: Array<{ value: ThinkingMode; label: string }> = [
  { value: 'auto', label: 'auto — 用 provider 默认' },
  { value: 'enabled', label: 'enabled — 强制开启思考' },
  { value: 'disabled', label: 'disabled — 强制关闭思考' },
]

const selectedProfile = computed(() => (
  profiles.value.find(p => p.id === formData.value.profileId) ?? null
))
const profileSupportsReasoning = computed(() => (
  selectedProfile.value != null && selectedProfile.value.thinkingProtocol !== 'NONE'
))

const activeService = computed(() => (
  detailMode.value === 'edit'
    ? services.value.find(service => service.id === formData.value.id)
    : null
))

const isEditing = computed(() => detailMode.value === 'edit')
const isGenerationKind = computed(() => formData.value.kind === 'GENERATION')
const isEmbeddingKind = computed(() => formData.value.kind === 'EMBEDDING')
const vendorOptions = computed(() => availableVendorsForKind(templates.value, formData.value.kind as ServiceKind))
const currentVendorTemplate = computed(() => (
  findVendorTemplate(templates.value, vendorKey.value) ?? vendorOptions.value[0]
))
const modelOptions = computed(() => (
  modelOptionsForKind(currentVendorTemplate.value, formData.value.kind as ServiceKind)
))
const usingCustomModel = computed(() => (
  selectedModelValue.value === CUSTOM_MODEL_VALUE || modelOptions.value.length === 0
))
const apiUrlPlaceholder = computed(() => (
  currentVendorTemplate.value?.defaultApiUrl ?? 'https://api.example.com/v1'
))
const dialogTitle = computed(() => {
  if (detailMode.value === 'create') return '新建模型服务'
  if (detailMode.value === 'edit') return formData.value.displayName || formData.value.id || '模型服务详情'
  return '模型服务'
})
const deleteConfirmMessage = computed(() => {
  if (!deletingServiceId.value) return ''
  const service = services.value.find(item => item.id === deletingServiceId.value)
  return `确认删除模型服务“${service?.displayName || deletingServiceId.value}”吗？此操作不可恢复。`
})

function normalizeSelectValue(value: UiSelectValue): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number') return String(value)
  return ''
}

function defaultVendorKeyForKind(kind: ServiceKind): string | undefined {
  return availableVendorsForKind(templates.value, kind)[0]?.vendorKey ?? templates.value[0]?.vendorKey
}

function currentModelName(): string {
  return usingCustomModel.value ? customModelName.value.trim() : selectedModelValue.value.trim()
}

function syncSuggestedFields() {
  const modelName = currentModelName()
  const template = currentVendorTemplate.value
  if (!template) return

  if (!displayNameCustomized.value) {
    formData.value.displayName = modelName ? `${template.displayName} / ${modelName}` : template.displayName
  }
  if (!isEditing.value && !serviceIdCustomized.value) {
    formData.value.id = buildSuggestedServiceId(formData.value.kind as ServiceKind, template.vendorKey, modelName)
  }
}

function normalizeFormForKind() {
  if (formData.value.kind === 'GENERATION') {
    const template = currentVendorTemplate.value
    formData.value.capabilities = formData.value.capabilities?.length
      ? formData.value.capabilities
      : [...(template?.defaultCapabilities ?? ['CHAT'])]
    formData.value.scenes = formData.value.scenes?.length
      ? formData.value.scenes
      : [...(template?.defaultScenes ?? ['chat', 'agent_react'])]
    formData.value.supportsStreaming = Boolean(formData.value.supportsStreaming)
    formData.value.embeddingDimension = undefined
    return
  }

  formData.value.scenes = []
  formData.value.capabilities = []
  formData.value.supportsStreaming = false

  if (formData.value.kind !== 'EMBEDDING') {
    formData.value.embeddingDimension = undefined
  }
}

function applyPresetDefaults() {
  const template = currentVendorTemplate.value
  if (!template) return

  formData.value.vendorKey = template.vendorKey
  formData.value.type = template.providerType
  if (!formData.value.apiUrl || !isEditing.value) {
    formData.value.apiUrl = template.defaultApiUrl
  }
  if (!formData.value.timeoutSeconds || !isEditing.value) {
    formData.value.timeoutSeconds = template.defaultTimeoutSeconds
  }

  const preset = modelOptions.value.find(option => option.value === selectedModelValue.value)
  if (preset && !usingCustomModel.value) {
    formData.value.modelName = preset.value
    if (formData.value.kind === 'GENERATION') {
      formData.value.capabilities = [...(preset.capabilities?.length ? preset.capabilities : template.defaultCapabilities)]
      formData.value.scenes = [...(preset.scenes?.length ? preset.scenes : template.defaultScenes)]
      formData.value.supportsStreaming = preset.supportsStreaming ?? template.defaultSupportsStreaming ?? false
      if (preset.maxContextWindow != null) {
        formData.value.maxContextWindow = preset.maxContextWindow
      }
    }
    if (formData.value.kind === 'EMBEDDING' && preset.embeddingDimension != null) {
      formData.value.embeddingDimension = preset.embeddingDimension
    }
  } else if (usingCustomModel.value) {
    formData.value.modelName = customModelName.value.trim()
    if (formData.value.kind === 'GENERATION') {
      formData.value.capabilities = formData.value.capabilities?.length
        ? formData.value.capabilities
        : [...template.defaultCapabilities]
      formData.value.scenes = formData.value.scenes?.length
        ? formData.value.scenes
        : [...template.defaultScenes]
      formData.value.supportsStreaming = formData.value.supportsStreaming ?? template.defaultSupportsStreaming ?? false
      if (!formData.value.maxContextWindow && template.defaultMaxContextWindow != null) {
        formData.value.maxContextWindow = template.defaultMaxContextWindow
      }
    }
  }

  normalizeFormForKind()
  syncSuggestedFields()
}

function applyVendorTemplate(nextVendorKey: string, keepCurrentModel = false) {
  vendorKey.value = nextVendorKey
  const template = currentVendorTemplate.value
  if (!template) return

  formData.value.vendorKey = template.vendorKey
  formData.value.type = template.providerType
  formData.value.apiUrl = template.defaultApiUrl
  formData.value.timeoutSeconds = template.defaultTimeoutSeconds

  const presetOptions = modelOptionsForKind(template, formData.value.kind as ServiceKind)
  const currentModel = keepCurrentModel ? currentModelName() : ''
  const matchedPreset = presetOptions.find(option => option.value === currentModel)
  const fallbackPreset = matchedPreset ?? defaultModelForKind(template, formData.value.kind as ServiceKind)

  if (fallbackPreset) {
    selectedModelValue.value = fallbackPreset.value
    customModelName.value = ''
  } else {
    selectedModelValue.value = CUSTOM_MODEL_VALUE
    customModelName.value = keepCurrentModel ? currentModel : ''
  }

  applyPresetDefaults()
}

function resetForm() {
  formData.value = buildEmptyModelServiceRequest()
  errors.value = {}
  serviceIdCustomized.value = false
  displayNameCustomized.value = false
  selectedModelValue.value = CUSTOM_MODEL_VALUE
  customModelName.value = ''
  probedModels.value = []
  probeError.value = null
  vendorKey.value = defaultVendorKeyForKind('GENERATION') ?? 'custom-openai'
  if (findVendorTemplate(templates.value, vendorKey.value)) {
    applyVendorTemplate(vendorKey.value)
  }
}

function enterCreateView() {
  if (!templates.value.length) {
    uiStore.showToast('error', '模型服务模板未加载完成')
    return
  }
  detailMode.value = 'create'
  resetForm()
}

function enterEditView(service: ModelService) {
  detailMode.value = 'edit'
  vendorKey.value = inferVendorKey(service)
  formData.value = {
    id: service.id,
    kind: service.kind,
    type: service.type,
    profileId: service.profileId,
    vendorKey: vendorKey.value,
    apiUrl: service.apiUrl ?? '',
    apiKey: '',
    modelName: service.modelName,
    timeoutSeconds: service.timeoutSeconds ?? 60,
    priority: service.priority ?? 0,
    scenes: [...(service.scenes ?? [])],
    capabilities: [...(service.capabilities ?? [])],
    enabled: service.enabled ?? true,
    isReasoning: service.isReasoning ?? false,
    thinkingMode: service.thinkingMode ?? 'auto',
    costPerInputToken: service.costPerInputToken ?? 0,
    costPerOutputToken: service.costPerOutputToken ?? 0,
    maxContextWindow: service.maxContextWindow ?? 131072,
    embeddingDimension: service.embeddingDimension,
    supportsStreaming: service.supportsStreaming ?? false,
    displayName: service.displayName ?? '',
    description: service.description ?? '',
  }
  const matchedPreset = modelOptionsForKind(findVendorTemplate(templates.value, vendorKey.value), service.kind as ServiceKind)
    .find(option => option.value === service.modelName)
  selectedModelValue.value = matchedPreset?.value ?? CUSTOM_MODEL_VALUE
  customModelName.value = matchedPreset ? '' : service.modelName
  serviceIdCustomized.value = true
  displayNameCustomized.value = true
  probedModels.value = []
  probeError.value = null
  errors.value = {}
}

function closeManager() {
  errors.value = {}
  deletingServiceId.value = null
  emit('close')
}

async function loadServices() {
  services.value = await modelServiceApi.listServices()
}

async function loadInitialData() {
  try {
    const [loadedServices, loadedTemplates, loadedProfiles] = await Promise.all([
      modelServiceApi.listServices(),
      modelServiceApi.listTemplates(),
      // ProviderProfile 列表用于"选 profile → 拉模型"流程；接口失败时降级为空数组，
      // 不阻塞模板/服务的加载（用户仍可走旧的厂商模板路径）。
      listProviderProfiles().catch(error => {
        logger.warn('加载 Provider Profile 列表失败:', error)
        return [] as ProviderProfileDto[]
      }),
    ])
    services.value = loadedServices
    templates.value = loadedTemplates
    profiles.value = loadedProfiles
    if (props.initialMode === 'create') {
      enterCreateView()
      return
    }
    if (props.initialMode === 'edit' && props.initialServiceId) {
      const targetService = loadedServices.find(service => service.id === props.initialServiceId)
      if (targetService) {
        enterEditView(targetService)
        return
      }
      uiStore.showToast('error', '未找到目标模型服务')
      emit('close')
      return
    }
    enterCreateView()
  } catch (error) {
    logger.error('加载模型服务配置失败:', error)
    uiStore.showToast('error', '加载模型服务配置失败')
    emit('close')
  } finally {
    loading.value = false
  }
}

/**
 * 用户挑 profile 后，把 profile 默认 baseUrl 同步到 formData，
 * 并清空之前的探测结果（避免不同 provider 的模型混用）。
 * 编辑场景下若 baseUrl 已有值则尊重现有值，仅刷新 profileId。
 */
function applyProfileSelection(profileId: string) {
  formData.value.profileId = profileId
  const profile = profiles.value.find(p => p.id === profileId)
  if (!profile) return
  if (!isEditing.value || !formData.value.apiUrl?.trim()) {
    formData.value.apiUrl = profile.defaultBaseUrl
  }
  // 切换 profile 时清空之前的探测列表 / 错误，避免误读跨 provider 的模型
  probedModels.value = []
  probeError.value = null
  // profile 不支持思考链时，强制把推理相关字段重置为安全默认
  if (profile.thinkingProtocol === 'NONE') {
    formData.value.isReasoning = false
    formData.value.thinkingMode = 'auto'
  }
}

function updateProfile(value: UiSelectValue) {
  const profileId = normalizeSelectValue(value)
  if (!profileId) return
  applyProfileSelection(profileId)
}

function updateThinkingMode(value: UiSelectValue) {
  const mode = normalizeSelectValue(value)
  if (mode === 'auto' || mode === 'enabled' || mode === 'disabled') {
    formData.value.thinkingMode = mode
  }
}

function updateIsReasoning(value: boolean | 'indeterminate') {
  const isReasoning = value === true
  formData.value.isReasoning = isReasoning
  if (!isReasoning) {
    formData.value.thinkingMode = 'auto'
  }
}

/**
 * 调后端 /probe-models 拉取当前 profile 实际可用模型清单。
 *
 * <p>用户必须先选 profile + 填 baseUrl 才能探测；apiKey 可空（Ollama 等
 * 本地服务无鉴权）。后端按 ProviderProfile.modelDiscovery 配置发起 GET
 * 请求，毫秒级返回，不再吃 chat ping 的超时。</p>
 */
async function handleProbeModels() {
  if (!formData.value.profileId) {
    uiStore.showToast('error', '请先选择 Provider Profile')
    return
  }
  if (!formData.value.apiUrl?.trim()) {
    uiStore.showToast('error', '请先填写 API 地址')
    return
  }
  probing.value = true
  probeError.value = null
  try {
    const models = await probeModels({
      profileId: formData.value.profileId,
      baseUrl: formData.value.apiUrl.trim(),
      apiKey: formData.value.apiKey?.trim() || undefined,
    })
    probedModels.value = models
    if (models.length === 0) {
      uiStore.showToast('info', '该 Provider 未返回任何模型')
    } else {
      uiStore.showToast('success', `已获取 ${models.length} 个模型`)
    }
  } catch (error: any) {
    logger.error('探测模型清单失败:', error)
    probedModels.value = []
    probeError.value = error?.message || '探测失败'
    uiStore.showToast('error', `探测失败：${probeError.value}`)
  } finally {
    probing.value = false
  }
}

/** 用户从探测结果下拉里挑模型 → 写回 formData，同步建议字段。 */
function selectProbedModel(value: UiSelectValue) {
  const modelId = normalizeSelectValue(value)
  if (!modelId) return
  customModelName.value = modelId
  formData.value.modelName = modelId
  selectedModelValue.value = CUSTOM_MODEL_VALUE
  syncSuggestedFields()
}

function validate() {
  errors.value = {}

  formData.value.modelName = currentModelName()
  normalizeFormForKind()

  if (!formData.value.id?.trim()) {
    errors.value.id = '服务 ID 不能为空。'
  }
  // 后端 profileId 为必填；profiles 为空表示后端 profile 接口暂不可用，跳过该校验
  // 走旧厂商模板路径（保存时后端会用 IllegalArgumentException 兜底拦截非法值）。
  if (profiles.value.length > 0 && !formData.value.profileId?.trim()) {
    errors.value.profileId = '请选择 Provider Profile。'
  }
  if (!formData.value.apiUrl?.trim()) {
    errors.value.apiUrl = 'API 地址不能为空。'
  }
  if (!formData.value.modelName?.trim()) {
    errors.value.modelName = '模型名称不能为空。'
  }
  if ((formData.value.timeoutSeconds ?? 0) <= 0) {
    errors.value.timeoutSeconds = '超时时间必须大于 0。'
  }
  if (formData.value.kind === 'GENERATION' && !(formData.value.capabilities?.length)) {
    errors.value.capabilities = '生成服务至少需要选择一个能力。'
  }

  return Object.keys(errors.value).length === 0
}

async function saveService() {
  // 不在保存时调 applyPresetDefaults() — 那会把用户手动修改的
  // capabilities/scenes/supportsStreaming 等重置回模板默认值。
  // validate() 已经会调 normalizeFormForKind() 做必要的清理。
  if (!validate()) return

  loading.value = true
  try {
    const payload: CreateModelServiceRequest = { ...formData.value }
    if (isEditing.value && !payload.apiKey) {
      delete payload.apiKey
    }

    if (isEditing.value) {
      await modelServiceApi.updateService(payload.id, payload)
    } else {
      await modelServiceApi.createService(payload)
    }
    uiStore.showToast('success', isEditing.value ? '模型服务已更新' : '模型服务已创建')
    closeManager()
  } catch (error: any) {
    logger.error('保存模型服务失败:', error)
    errors.value._general = error?.message || '保存模型服务失败。'
  } finally {
    loading.value = false
  }
}

function confirmDelete(service: ModelService) {
  deletingServiceId.value = service.id
  showDeleteConfirm.value = true
}

async function handleDelete() {
  if (!deletingServiceId.value) return

  loading.value = true
  try {
    await modelServiceApi.deleteService(deletingServiceId.value)
    await loadServices()
    uiStore.showToast('success', '模型服务已删除')
    if (formData.value.id === deletingServiceId.value) {
      closeManager()
    }
  } catch (error: any) {
    logger.error('删除模型服务失败:', error)
    uiStore.showToast('error', error?.message || '删除模型服务失败')
  } finally {
    loading.value = false
    showDeleteConfirm.value = false
    deletingServiceId.value = null
  }
}

function updateKind(value: UiSelectValue) {
  formData.value.kind = (normalizeSelectValue(value) || 'GENERATION') as ServiceKind
  const supportedVendors = availableVendorsForKind(templates.value, formData.value.kind as ServiceKind)
  const nextVendor = supportedVendors.find(option => option.vendorKey === vendorKey.value)?.vendorKey ?? supportedVendors[0]?.vendorKey
  if (nextVendor) {
    applyVendorTemplate(nextVendor)
  }
}

function updateVendor(value: UiSelectValue) {
  const nextVendor = normalizeSelectValue(value)
  if (!nextVendor) return
  applyVendorTemplate(nextVendor)
}

function updateModel(value: UiSelectValue) {
  selectedModelValue.value = normalizeSelectValue(value) || CUSTOM_MODEL_VALUE
  if (selectedModelValue.value !== CUSTOM_MODEL_VALUE) {
    customModelName.value = ''
  }
  applyPresetDefaults()
}

function updateEnabled(value: boolean | 'indeterminate') {
  formData.value.enabled = value === true
}

function updateSupportsStreaming(value: boolean | 'indeterminate') {
  formData.value.supportsStreaming = value === true
}

function toggleScene(scene: string) {
  const scenes = [...(formData.value.scenes ?? [])]
  const index = scenes.indexOf(scene)
  if (index >= 0) {
    scenes.splice(index, 1)
  } else {
    scenes.push(scene)
  }
  formData.value.scenes = scenes
}

function toggleCapability(capability: string) {
  const capabilities = [...(formData.value.capabilities ?? [])]
  const index = capabilities.indexOf(capability)
  if (index >= 0) {
    capabilities.splice(index, 1)
  } else {
    capabilities.push(capability)
  }
  formData.value.capabilities = capabilities
}

function handleCustomModelInput(value: string | number) {
  customModelName.value = String(value)
  formData.value.modelName = String(value).trim()
  syncSuggestedFields()
}

function handleDisplayNameInput(value: string | number) {
  displayNameCustomized.value = true
  formData.value.displayName = String(value)
}

function handleServiceIdInput(value: string | number) {
  serviceIdCustomized.value = true
  formData.value.id = String(value)
}

onMounted(() => {
  void loadInitialData()
})
</script>

<template>
  <Dialog :open="true" @update:open="(value: boolean) => { if (!value) emit('close') }">
    <DialogContent class="flex max-h-[92vh] w-[min(1180px,calc(100vw-2rem))] max-w-[min(1180px,calc(100vw-2rem))] flex-col overflow-hidden sm:max-w-[min(1180px,calc(100vw-3rem))]">
      <DialogHeader>
        <DialogTitle>{{ dialogTitle }}</DialogTitle>
      </DialogHeader>

      <div class="flex-1 overflow-y-auto px-1 py-2">
        <div
          v-if="loading && detailMode === null"
          class="rounded-[calc(var(--radius)+8px)] border border-border/70 bg-background/60 px-6 py-10 text-sm text-muted-foreground"
        >
          正在加载模型服务配置...
        </div>

        <template v-else>
          <div class="mb-6 flex items-center justify-between gap-4">
            <Button variant="ghost" class="gap-2" @click="closeManager">
              <ArrowLeft class="size-4" />
              返回模型服务页
            </Button>
            <Button
              v-if="isEditing && activeService"
              variant="destructive"
              size="sm"
              class="gap-2"
              @click="confirmDelete(activeService)"
            >
              <Trash2 class="size-4" />
              删除服务
            </Button>
          </div>

          <form class="space-y-4" @submit.prevent="saveService">
            <div v-if="errors._general" class="rounded-md bg-destructive/10 p-4 text-sm text-destructive">
              {{ errors._general }}
            </div>

            <!-- Provider Profile 协议挑选 — Phase 8 新增。
                 用户先选 Profile（决定 thinking 协议、模型探测端点），
                 再填 baseUrl + apiKey，点"拉取可用模型"探测实际可用模型清单。
                 接口加载失败时（profiles 为空）该区块隐藏，走旧厂商模板路径。 -->
            <section
              v-if="profiles.length > 0"
              class="rounded-[calc(var(--radius)+10px)] border border-border/70 bg-background/72 p-6"
            >
              <div class="flex items-center justify-between gap-4 border-b border-border/60 pb-4">
                <div>
                  <h3 class="text-base font-semibold text-foreground">Provider 协议</h3>
                  <p class="mt-xs text-sm text-muted-foreground">
                    选定协议后填写地址与密钥，点"拉取可用模型"获取该 Provider 实际可用的模型清单。
                  </p>
                </div>
                <Badge v-if="selectedProfile" variant="outline">
                  {{ profileSupportsReasoning ? '支持推理' : '非推理' }}
                </Badge>
              </div>

              <div class="mt-4 grid gap-4 md:grid-cols-2">
                <div class="space-y-2 md:col-span-2">
                  <Label>Provider Profile</Label>
                  <Select :model-value="formData.profileId" @update:model-value="updateProfile">
                    <SelectTrigger :class="{ 'border-destructive': errors.profileId }">
                      <SelectValue placeholder="挑一个内置协议..." />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem v-for="profile in profiles" :key="profile.id" :value="profile.id">
                        {{ profile.displayName }}
                      </SelectItem>
                    </SelectContent>
                  </Select>
                  <p v-if="errors.profileId" class="text-sm text-destructive">{{ errors.profileId }}</p>
                  <p v-else-if="selectedProfile" class="text-sm text-muted-foreground">
                    思考协议：{{ selectedProfile.thinkingProtocol }} · 默认地址：{{ selectedProfile.defaultBaseUrl }}
                  </p>
                </div>

                <div class="space-y-2 md:col-span-2">
                  <Label>拉取可用模型</Label>
                  <div class="flex flex-wrap items-start gap-4">
                    <Button
                      type="button"
                      variant="outline"
                      class="gap-2"
                      :disabled="probing || !formData.profileId || !formData.apiUrl?.trim()"
                      @click="handleProbeModels"
                    >
                      <Loader2 v-if="probing" class="size-4 animate-spin" />
                      <RefreshCw v-else class="size-4" />
                      {{ probing ? '探测中...' : '拉取可用模型' }}
                    </Button>
                    <div v-if="probedModels.length > 0" class="min-w-64 flex-1 space-y-2">
                      <Select :model-value="formData.modelName" @update:model-value="selectProbedModel">
                        <SelectTrigger>
                          <SelectValue placeholder="选择模型" />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem v-for="model in probedModels" :key="model.id" :value="model.id">
                            {{ model.name }}
                          </SelectItem>
                        </SelectContent>
                      </Select>
                      <p class="text-sm text-muted-foreground">
                        共获取到 {{ probedModels.length }} 个模型，亦可在下方"自定义模型名"手动覆盖。
                      </p>
                    </div>
                  </div>
                  <p v-if="probeError" class="text-sm text-destructive">探测失败：{{ probeError }}</p>
                </div>
              </div>
            </section>

            <div class="grid gap-4 2xl:grid-cols-[minmax(0,1.65fr)_minmax(360px,1fr)]">
              <section class="rounded-[calc(var(--radius)+10px)] border border-border/70 bg-background/72 p-6">
                <div class="flex flex-wrap items-center justify-between gap-4 border-b border-border/60 pb-4">
                  <div class="flex flex-wrap items-center gap-2">
                    <Badge variant="outline">{{ currentVendorTemplate?.displayName || '未选择模板' }}</Badge>
                    <Badge variant="secondary">{{ currentVendorTemplate?.providerType || formData.type }}</Badge>
                  </div>
                  <div class="flex items-center gap-4 rounded-full border border-border/70 bg-muted/20 px-4 py-2">
                    <span class="text-sm text-foreground">启用服务</span>
                    <Switch :model-value="formData.enabled" @update:model-value="updateEnabled" />
                  </div>
                </div>

                <div class="mt-4 grid gap-4 md:grid-cols-2 2xl:grid-cols-3">
                  <div class="space-y-2">
                    <Label>服务类型</Label>
                    <Select :model-value="formData.kind" @update:model-value="updateKind">
                      <SelectTrigger>
                        <SelectValue placeholder="选择服务类型" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem v-for="option in kindOptions" :key="option.value" :value="option.value">
                          {{ option.label }}
                        </SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  <div class="space-y-2">
                    <Label>厂商模板</Label>
                    <Select :model-value="vendorKey" @update:model-value="updateVendor">
                      <SelectTrigger>
                        <SelectValue placeholder="选择厂商模板" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem v-for="option in vendorOptions" :key="option.vendorKey" :value="option.vendorKey">
                          {{ option.displayName }}
                        </SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  <div class="space-y-2">
                    <Label>模型</Label>
                    <Select :model-value="selectedModelValue" @update:model-value="updateModel">
                      <SelectTrigger>
                        <SelectValue placeholder="选择模型" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem
                          v-for="option in modelOptions"
                          :key="option.value"
                          :value="option.value"
                        >
                          {{ option.label }}
                        </SelectItem>
                        <SelectItem :value="CUSTOM_MODEL_VALUE">自定义输入</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>

                  <div v-if="usingCustomModel" class="space-y-2 md:col-span-2 2xl:col-span-2">
                    <Label>自定义模型名</Label>
                    <Input
                      :model-value="customModelName"
                      placeholder="输入模型名称"
                      :class="{ 'border-destructive': errors.modelName }"
                      @update:model-value="handleCustomModelInput"
                    />
                    <p v-if="errors.modelName" class="text-sm text-destructive">{{ errors.modelName }}</p>
                  </div>

                  <div class="space-y-2">
                    <Label>显示名称</Label>
                    <Input
                      :model-value="formData.displayName"
                      placeholder="例如：OpenAI / 主力"
                      @update:model-value="handleDisplayNameInput"
                    />
                  </div>

                  <div class="space-y-2 md:col-span-2">
                    <Label>API 地址</Label>
                    <Input
                      v-model="formData.apiUrl"
                      :placeholder="apiUrlPlaceholder"
                      :class="{ 'border-destructive': errors.apiUrl }"
                    />
                    <p v-if="errors.apiUrl" class="text-sm text-destructive">{{ errors.apiUrl }}</p>
                  </div>

                  <div class="space-y-2 md:col-span-2 2xl:col-span-3">
                    <Label>API 密钥</Label>
                    <Input
                      v-model="formData.apiKey"
                      type="password"
                      :placeholder="isEditing ? '留空则保留当前密钥' : '输入 API 密钥'"
                    />
                  </div>
                </div>
              </section>

              <div class="grid content-start gap-4">
                <section class="rounded-[calc(var(--radius)+10px)] border border-border/70 bg-background/72 p-6">
                  <div class="grid gap-4 sm:grid-cols-2">
                    <div class="space-y-2">
                      <Label>超时时间（秒）</Label>
                      <Input v-model.number="formData.timeoutSeconds" type="number" :min="1" />
                      <p v-if="errors.timeoutSeconds" class="text-sm text-destructive">{{ errors.timeoutSeconds }}</p>
                    </div>

                    <div v-if="isEmbeddingKind" class="space-y-2">
                      <Label>向量维度</Label>
                      <Input v-model.number="formData.embeddingDimension" type="number" :min="1" />
                    </div>

                    <div class="space-y-2 sm:col-span-2">
                      <Label>描述</Label>
                      <Textarea
                        v-model="formData.description"
                        rows="3"
                        placeholder="补充用途或备注"
                      />
                    </div>
                  </div>
                </section>
              </div>
            </div>

            <!-- 推理与思考链配置 — Phase 8 新增。仅在生成服务下可见；
                 当所选 profile 不支持思考协议（thinkingProtocol === 'NONE'）时禁用。 -->
            <section
              v-if="isGenerationKind"
              class="rounded-[calc(var(--radius)+10px)] border border-border/70 bg-background/72 p-6"
            >
              <div class="border-b border-border/60 pb-4">
                <h3 class="text-base font-semibold text-foreground">推理模型设置</h3>
                <p class="mt-xs text-sm text-muted-foreground">
                  若所选模型支持思考链（如 DeepSeek Reasoner / Qwen QwQ / o-系列），勾选下方选项以启用 thinking 协议。
                </p>
              </div>

              <div class="mt-4 space-y-4">
                <label class="flex min-h-11 items-center gap-4 rounded-md border border-border/60 px-4 py-2">
                  <Checkbox
                    :model-value="formData.isReasoning ?? false"
                    :disabled="profiles.length > 0 && !profileSupportsReasoning"
                    @update:model-value="updateIsReasoning"
                  />
                  <span class="flex-1 text-sm text-foreground">这是推理模型（支持思考链）</span>
                  <span
                    v-if="profiles.length > 0 && !profileSupportsReasoning"
                    class="text-sm text-muted-foreground"
                  >
                    所选 Provider 协议不支持思考链
                  </span>
                </label>

                <div v-if="formData.isReasoning" class="space-y-2">
                  <Label>思考模式</Label>
                  <Select :model-value="formData.thinkingMode ?? 'auto'" @update:model-value="updateThinkingMode">
                    <SelectTrigger>
                      <SelectValue placeholder="选择思考模式" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem v-for="option in thinkingModeOptions" :key="option.value" :value="option.value">
                        {{ option.label }}
                      </SelectItem>
                    </SelectContent>
                  </Select>
                  <p class="text-sm text-muted-foreground">
                    auto 让 Provider 自决；enabled / disabled 用于显式覆盖（按 Provider 协议下发对应字段）。
                  </p>
                </div>
              </div>
            </section>

            <details class="rounded-[calc(var(--radius)+10px)] border border-border/70 bg-muted/20 px-6 py-4">
              <summary class="cursor-pointer select-none text-sm font-semibold text-foreground">高级参数</summary>
              <p class="mt-1 text-sm text-muted-foreground">服务 ID、优先级、上下文窗口、成本统计、能力标签等参数。通常由厂商模板自动填充，无需手动修改。</p>

              <div class="mt-4 space-y-4">
                <section class="rounded-[calc(var(--radius)+10px)] border border-border/70 bg-background/72 p-6">
                  <div class="grid gap-4 sm:grid-cols-2">
                    <div class="space-y-2 sm:col-span-2">
                      <Label>服务 ID</Label>
                      <Input
                        :model-value="formData.id"
                        placeholder="自动生成，可手动调整"
                        :readonly="isEditing"
                        :class="{ 'border-destructive': errors.id }"
                        @update:model-value="value => { if (!isEditing) handleServiceIdInput(value) }"
                      />
                      <p v-if="errors.id" class="text-sm text-destructive">{{ errors.id }}</p>
                    </div>

                    <div class="space-y-2">
                      <Label>优先级</Label>
                      <Input v-model.number="formData.priority" type="number" />
                    </div>

                    <div class="space-y-2">
                      <Label>最大上下文窗口</Label>
                      <Input v-model.number="formData.maxContextWindow" type="number" :min="0" />
                    </div>

                    <div class="space-y-2">
                      <Label>输入成本（每百万 token）</Label>
                      <Input v-model.number="formData.costPerInputToken" type="number" :min="0" />
                    </div>

                    <div class="space-y-2">
                      <Label>输出成本（每百万 token）</Label>
                      <Input v-model.number="formData.costPerOutputToken" type="number" :min="0" />
                    </div>
                  </div>
                </section>

                <section v-if="isGenerationKind" class="rounded-[calc(var(--radius)+10px)] border border-border/70 bg-background/72 p-6">
                  <div class="grid gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_320px]">
                    <div class="space-y-2">
                      <Label>生成能力</Label>
                      <div class="grid gap-4 sm:grid-cols-2">
                        <label
                          v-for="option in generationCapabilityOptions"
                          :key="option.value"
                          class="flex min-h-11 items-center gap-4 rounded-md border border-border/60 px-4 py-2"
                        >
                          <Checkbox
                            :model-value="formData.capabilities?.includes(option.value)"
                            @update:model-value="() => toggleCapability(option.value)"
                          />
                          <span class="text-sm">{{ option.label }}</span>
                        </label>
                      </div>
                      <p v-if="errors.capabilities" class="text-sm text-destructive">{{ errors.capabilities }}</p>
                    </div>

                    <div class="space-y-2">
                      <Label>支持场景</Label>
                      <TooltipProvider :delay-duration="200">
                        <div class="grid gap-4 sm:grid-cols-2">
                          <label
                            v-for="option in generationSceneOptions"
                            :key="option.value"
                            class="flex min-h-11 items-center gap-4 rounded-md border border-border/60 px-4 py-2"
                          >
                            <Checkbox
                              :model-value="formData.scenes?.includes(option.value)"
                              @update:model-value="() => toggleScene(option.value)"
                            />
                            <span class="flex items-center gap-sm text-sm">
                              {{ option.label }}
                              <Tooltip v-if="option.hint">
                                <TooltipTrigger as-child>
                                  <button
                                    type="button"
                                    class="inline-flex items-center text-muted-foreground hover:text-foreground"
                                    :aria-label="`${option.label} 配置建议`"
                                    @click.prevent
                                  >
                                    <Info class="h-xs w-xs" />
                                  </button>
                                </TooltipTrigger>
                                <TooltipContent class="max-w-xs text-xs">
                                  {{ option.hint }}
                                </TooltipContent>
                              </Tooltip>
                            </span>
                          </label>
                        </div>
                      </TooltipProvider>
                    </div>

                    <div class="space-y-2">
                      <Label>流式输出</Label>
                      <label class="flex min-h-11 items-center gap-4 rounded-md border border-border/60 px-4 py-2">
                        <Checkbox
                          :model-value="formData.supportsStreaming"
                          @update:model-value="updateSupportsStreaming"
                        />
                        <span class="text-sm text-foreground">支持流式输出</span>
                      </label>
                    </div>
                  </div>
                </section>
              </div>
            </details>

            <DialogFooter>
              <Button type="button" variant="outline" @click="closeManager">返回模型服务页</Button>
              <Button type="submit" :disabled="loading">
                {{ isEditing ? '保存修改' : '创建服务' }}
              </Button>
            </DialogFooter>
          </form>
        </template>
      </div>
    </DialogContent>
  </Dialog>

  <ConfirmDialog
    v-model:show="showDeleteConfirm"
    title="删除模型服务"
    :message="deleteConfirmMessage"
    confirm-label="删除"
    confirm-variant="destructive"
    @confirm="handleDelete"
  />
</template>
