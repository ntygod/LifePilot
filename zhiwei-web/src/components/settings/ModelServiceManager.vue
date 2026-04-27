<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { AcceptableValue } from 'reka-ui'
import { ArrowLeft, Info, Loader2, RefreshCw, Trash2 } from 'lucide-vue-next'
import {
  modelServiceApi,
  type CreateModelServiceRequest,
  type ModelService,
  type ThinkingMode,
} from '@/api/client'
import { listProviderProfiles, type ProviderProfileDto } from '@/api/providerProfile'
import { probeModels, type ModelInfo } from '@/api/probeModels'
import { logger } from '@/utils/logger'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { useUiStore } from '@/stores/ui'
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
  buildEmptyModelServiceRequest,
  buildSuggestedServiceId,
  GENERATION_CAPABILITY_OPTIONS,
  GENERATION_SCENE_OPTIONS,
  KIND_OPTIONS,
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
const profiles = ref<ProviderProfileDto[]>([])
const loading = ref(true)
const detailMode = ref<DetailMode>(null)
const deletingServiceId = ref<string | null>(null)
const showDeleteConfirm = ref(false)
const serviceIdCustomized = ref(false)
const displayNameCustomized = ref(false)

// 模型探测相关 —— Phase 8 新增。点"拉取可用模型"后填充 probedModels；
// 若 probedModels 非空，UI 用 Select 让用户挑选；为空时仍可走下方手填兜底。
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

const apiUrlPlaceholder = computed(() => (
  selectedProfile.value?.defaultBaseUrl ?? 'https://api.example.com/v1'
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

function syncSuggestedFields() {
  const modelName = formData.value.modelName?.trim() ?? ''
  const profile = selectedProfile.value
  const profileLabel = profile?.displayName ?? ''
  const slugSeed = profile?.id ?? ''

  if (!displayNameCustomized.value) {
    if (profileLabel && modelName) {
      formData.value.displayName = `${profileLabel} / ${modelName}`
    } else if (modelName) {
      formData.value.displayName = modelName
    } else if (profileLabel) {
      formData.value.displayName = profileLabel
    } else {
      formData.value.displayName = ''
    }
  }
  if (!isEditing.value && !serviceIdCustomized.value) {
    formData.value.id = buildSuggestedServiceId(
      formData.value.kind as ServiceKind,
      slugSeed,
      modelName,
    )
  }
}

function normalizeFormForKind() {
  if (formData.value.kind === 'GENERATION') {
    formData.value.capabilities = formData.value.capabilities?.length
      ? formData.value.capabilities
      : ['CHAT', 'STRUCTURED_OUTPUT', 'FUNCTION_CALLING', 'STREAMING']
    formData.value.scenes = formData.value.scenes?.length
      ? formData.value.scenes
      : ['chat', 'agent_react']
    formData.value.supportsStreaming = Boolean(formData.value.supportsStreaming)
    formData.value.embeddingDimension = undefined
    return
  }

  // 切到向量 / 重排服务时清掉所有生成专属字段，避免 EMBEDDING / RERANK payload
  // 携带 GENERATION 残留的成本统计 / 上下文窗口 / 能力 / 场景 / 流式开关。
  formData.value.scenes = []
  formData.value.capabilities = []
  formData.value.supportsStreaming = false
  formData.value.costPerInputToken = 0
  formData.value.costPerOutputToken = 0
  formData.value.maxContextWindow = 0

  if (formData.value.kind !== 'EMBEDDING') {
    formData.value.embeddingDimension = undefined
  }
}

function resetForm() {
  formData.value = buildEmptyModelServiceRequest()
  errors.value = {}
  serviceIdCustomized.value = false
  displayNameCustomized.value = false
  probedModels.value = []
  probeError.value = null
}

function enterCreateView() {
  detailMode.value = 'create'
  resetForm()
}

function enterEditView(service: ModelService) {
  detailMode.value = 'edit'
  formData.value = {
    id: service.id,
    kind: service.kind,
    type: service.type,
    profileId: service.profileId,
    vendorKey: service.vendorKey,
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
    const [loadedServices, loadedProfiles] = await Promise.all([
      modelServiceApi.listServices(),
      // ProviderProfile 列表用于"选 profile → 拉模型"流程；接口失败时降级为空数组，
      // 用户仍可走"手填模型名"路径，但保存时会被后端校验拦截。
      listProviderProfiles().catch(error => {
        logger.warn('加载 Provider Profile 列表失败:', error)
        return [] as ProviderProfileDto[]
      }),
    ])
    services.value = loadedServices
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
 *
 * <p>profile 不支持思考链时，强制把推理相关字段重置为安全默认，避免脏数据
 * 被保存到后端。</p>
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
  syncSuggestedFields()
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
    // 透传后端 ResponseStatusException 的具体 reason —— probeModels 走 fetch 封装，
    // 失败时抛 { code, message, timestamp }；同时兼容 axios 形态（e.response.data.message），
    // 确保连接被拒绝 / 401 / 422 jsonpath 失败等不同错误显示为具体原因而非通用文案
    const detail = error?.response?.data?.message || error?.message || '未知错误'
    probeError.value = detail
    uiStore.showToast('error', `探测失败：${detail}`)
  } finally {
    probing.value = false
  }
}

/** 用户从探测结果下拉里挑模型 → 写回 formData，同步建议字段。 */
function selectProbedModel(value: UiSelectValue) {
  const modelId = normalizeSelectValue(value)
  if (!modelId) return
  formData.value.modelName = modelId
  syncSuggestedFields()
}

function validate() {
  errors.value = {}

  formData.value.modelName = formData.value.modelName?.trim() ?? ''
  normalizeFormForKind()

  if (!formData.value.id?.trim()) {
    errors.value.id = '服务 ID 不能为空。'
  }
  // 后端 profileId 为必填；profiles 为空表示后端 profile 接口暂不可用，跳过该校验
  // 走"手填模型名"路径（保存时后端会用 IllegalArgumentException 兜底拦截非法值）。
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
  normalizeFormForKind()
  syncSuggestedFields()
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

function handleModelNameInput(value: string | number) {
  formData.value.modelName = String(value)
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
          <div class="mb-xl flex items-center justify-between gap-md">
            <Button variant="ghost" class="gap-sm" @click="closeManager">
              <ArrowLeft class="size-4" />
              返回模型服务页
            </Button>
            <Button
              v-if="isEditing && activeService"
              variant="destructive"
              size="sm"
              class="gap-sm"
              @click="confirmDelete(activeService)"
            >
              <Trash2 class="size-4" />
              删除服务
            </Button>
          </div>

          <form class="space-y-md" @submit.prevent="saveService">
            <div v-if="errors._general" class="rounded-md bg-destructive/10 p-md text-sm text-destructive">
              {{ errors._general }}
            </div>

            <!-- 服务配置 — 单一表单容器。
                 把原来的"Provider 协议"和"基本信息"两张卡片合并，避免割裂感。
                 字段按操作顺序排列：profile → API 地址/密钥 → 拉取 → 模型名 →
                 服务类型/显示名 → 超时/向量维度 → 描述。
                 profile 接口降级时（profiles 为空）profile/拉取相关字段隐藏，
                 用户仍可手填 API 地址/密钥/模型名走兜底路径。 -->
            <section class="rounded-[calc(var(--radius)+10px)] border border-border/70 bg-background/72 p-xl">
              <div class="flex flex-wrap items-center justify-end gap-md">
                <label class="flex items-center gap-md rounded-full border border-border/70 bg-muted/20 px-md py-sm">
                  <span class="text-sm text-foreground">启用服务</span>
                  <Switch :model-value="formData.enabled" @update:model-value="updateEnabled" />
                </label>
              </div>

              <div class="mt-md space-y-md">
                <!-- Provider Profile：profiles 为空时显示降级提示，字段照常可手填。 -->
                <div v-if="profiles.length > 0" class="space-y-sm">
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
                <div v-else class="rounded-md border border-dashed border-border/60 bg-muted/20 p-md text-sm text-muted-foreground">
                  未启用 Provider Profile 模式，请直接手填下方字段。
                </div>

                <!-- API 地址 + API 密钥：两列同行，避免单列纵向拉得过长。 -->
                <div class="grid grid-cols-1 gap-md md:grid-cols-2">
                  <div class="space-y-sm">
                    <Label>API 地址</Label>
                    <Input
                      v-model="formData.apiUrl"
                      :placeholder="apiUrlPlaceholder"
                      :class="{ 'border-destructive': errors.apiUrl }"
                    />
                    <p v-if="errors.apiUrl" class="text-sm text-destructive">{{ errors.apiUrl }}</p>
                  </div>

                  <div class="space-y-sm">
                    <Label>API 密钥</Label>
                    <Input
                      v-model="formData.apiKey"
                      type="password"
                      :placeholder="isEditing ? '留空则保留当前密钥' : '输入 API 密钥'"
                    />
                  </div>
                </div>

                <!-- 拉取按钮：仅在启用 Provider Profile 模式时显示。 -->
                <div v-if="profiles.length > 0" class="space-y-sm">
                  <Button
                    type="button"
                    variant="outline"
                    class="gap-sm"
                    :disabled="probing || !formData.profileId || !formData.apiUrl?.trim()"
                    @click="handleProbeModels"
                  >
                    <Loader2 v-if="probing" class="size-4 animate-spin" />
                    <RefreshCw v-else class="size-4" />
                    {{ probing ? '探测中...' : '拉取可用模型' }}
                  </Button>
                  <p v-if="probeError" class="text-sm text-destructive">探测失败：{{ probeError }}</p>
                </div>

                <!-- 模型名称：拉取成功后 Select（探测列表）+ Input（手填覆盖）两列同行；
                     未探测走纯 Input 占满一行。两种形态都写回同一个 formData.modelName。 -->
                <div class="space-y-sm">
                  <Label>模型名称</Label>
                  <template v-if="probedModels.length > 0">
                    <div class="grid grid-cols-1 gap-md md:grid-cols-2">
                      <Select :model-value="formData.modelName" @update:model-value="selectProbedModel">
                        <SelectTrigger :class="{ 'border-destructive': errors.modelName }">
                          <SelectValue placeholder="选择模型" />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem v-for="model in probedModels" :key="model.id" :value="model.id">
                            {{ model.name }}
                          </SelectItem>
                        </SelectContent>
                      </Select>
                      <Input
                        :model-value="formData.modelName"
                        placeholder="或手动输入模型 ID 覆盖"
                        :class="{ 'border-destructive': errors.modelName }"
                        @update:model-value="handleModelNameInput"
                      />
                    </div>
                    <p class="text-sm text-muted-foreground">
                      共获取到 {{ probedModels.length }} 个模型；左侧下拉选择，或右侧手填覆盖。
                    </p>
                  </template>
                  <template v-else>
                    <Input
                      :model-value="formData.modelName"
                      :placeholder="profiles.length > 0 ? '拉取后自动填入，或手动输入模型 ID' : '输入模型 ID'"
                      :class="{ 'border-destructive': errors.modelName }"
                      @update:model-value="handleModelNameInput"
                    />
                  </template>
                  <p v-if="errors.modelName" class="text-sm text-destructive">{{ errors.modelName }}</p>
                </div>

                <!-- 服务类型 + 显示名称：两列同行。 -->
                <div class="grid grid-cols-1 gap-md md:grid-cols-2">
                  <div class="space-y-sm">
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

                  <div class="space-y-sm">
                    <Label>显示名称</Label>
                    <Input
                      :model-value="formData.displayName"
                      placeholder="例如：DeepSeek / 主力"
                      @update:model-value="handleDisplayNameInput"
                    />
                  </div>
                </div>

                <!-- 超时时间 + 向量维度：两列同行；非 EMBEDDING 时超时独占两列。 -->
                <div class="grid grid-cols-1 gap-md md:grid-cols-2">
                  <div class="space-y-sm" :class="{ 'md:col-span-2': !isEmbeddingKind }">
                    <Label>超时时间（秒）</Label>
                    <Input v-model.number="formData.timeoutSeconds" type="number" :min="1" />
                    <p v-if="errors.timeoutSeconds" class="text-sm text-destructive">{{ errors.timeoutSeconds }}</p>
                  </div>

                  <div v-if="isEmbeddingKind" class="space-y-sm">
                    <Label>向量维度</Label>
                    <Input v-model.number="formData.embeddingDimension" type="number" :min="1" />
                  </div>
                </div>

                <div class="space-y-sm">
                  <Label>描述</Label>
                  <Textarea
                    v-model="formData.description"
                    rows="3"
                    placeholder="补充用途或备注"
                  />
                </div>
              </div>
            </section>

            <!-- 推理与思考链配置 — Phase 8 新增。
                 仅在生成服务下、且所选 Provider 协议支持思考链时显示；
                 若 thinkingProtocol === 'NONE' 整张卡片连同标题一起隐藏。 -->
            <section
              v-if="isGenerationKind && profileSupportsReasoning"
              class="rounded-[calc(var(--radius)+10px)] border border-border/70 bg-background/72 p-xl"
            >
              <div class="border-b border-border/60 pb-md">
                <h3 class="text-base font-semibold text-foreground">推理模型设置</h3>
                <p class="mt-xs text-sm text-muted-foreground">
                  若所选模型支持思考链（如 DeepSeek Reasoner / Qwen QwQ / o-系列），勾选下方选项以启用 thinking 协议。
                </p>
              </div>

              <div class="mt-md space-y-md">
                <label class="flex min-h-11 items-center gap-md rounded-md border border-border/60 px-md py-sm">
                  <Checkbox
                    :model-value="formData.isReasoning ?? false"
                    @update:model-value="updateIsReasoning"
                  />
                  <span class="flex-1 text-sm text-foreground">这是推理模型（支持思考链）</span>
                </label>

                <div v-if="formData.isReasoning" class="space-y-sm">
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

            <!-- 高级参数 fold 区目前只承载生成服务专属字段（成本、上下文窗口、能力 /
                 场景 / 流式以及服务 ID / 优先级）。向量 / 重排服务暂未引入对应高级
                 字段，整张 fold 直接隐藏，避免出现一个空壳收纳区。 -->
            <details
              v-if="isGenerationKind"
              class="rounded-[calc(var(--radius)+10px)] border border-border/70 bg-muted/20 px-xl py-md"
            >
              <summary class="cursor-pointer select-none text-sm font-semibold text-foreground">高级参数</summary>
              <p class="mt-xs text-sm text-muted-foreground">服务 ID、优先级、上下文窗口、成本统计、能力标签等参数。通常已根据 Provider 协议自动填充，无需手动修改。</p>

              <div class="mt-md space-y-md">
                <section class="rounded-[calc(var(--radius)+10px)] border border-border/70 bg-background/72 p-xl">
                  <div class="grid gap-md sm:grid-cols-2">
                    <div class="space-y-sm sm:col-span-2">
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

                    <div class="space-y-sm">
                      <Label>优先级</Label>
                      <Input v-model.number="formData.priority" type="number" />
                    </div>

                    <div class="space-y-sm">
                      <Label>最大上下文窗口</Label>
                      <Input v-model.number="formData.maxContextWindow" type="number" :min="0" />
                    </div>

                    <div class="space-y-sm">
                      <Label>输入成本（每百万 token）</Label>
                      <Input
                        v-model.number="formData.costPerInputToken"
                        type="number"
                        :min="0"
                        step="0.01"
                        placeholder="如 0.14"
                      />
                    </div>

                    <div class="space-y-sm">
                      <Label>输出成本（每百万 token）</Label>
                      <Input
                        v-model.number="formData.costPerOutputToken"
                        type="number"
                        :min="0"
                        step="0.01"
                        placeholder="如 0.28"
                      />
                    </div>
                  </div>
                </section>

                <section class="rounded-[calc(var(--radius)+10px)] border border-border/70 bg-background/72 p-xl">
                  <div class="grid gap-md xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_320px]">
                    <div class="space-y-sm">
                      <Label>生成能力</Label>
                      <div class="grid gap-md sm:grid-cols-2">
                        <label
                          v-for="option in generationCapabilityOptions"
                          :key="option.value"
                          class="flex min-h-11 items-center gap-md rounded-md border border-border/60 px-md py-sm"
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

                    <div class="space-y-sm">
                      <Label>支持场景</Label>
                      <TooltipProvider :delay-duration="200">
                        <div class="grid gap-md sm:grid-cols-2">
                          <label
                            v-for="option in generationSceneOptions"
                            :key="option.value"
                            class="flex min-h-11 items-center gap-md rounded-md border border-border/60 px-md py-sm"
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

                    <div class="space-y-sm">
                      <Label>流式输出</Label>
                      <label class="flex min-h-11 items-center gap-md rounded-md border border-border/60 px-md py-sm">
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
