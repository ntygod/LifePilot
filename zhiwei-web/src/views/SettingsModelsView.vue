<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { AcceptableValue } from 'reka-ui'
import { Cpu, Database, GitCompareArrows, RefreshCw } from 'lucide-vue-next'
import {
  modelRoutingApi,
  modelServiceApi,
  type EmbeddingRoutingSettings,
  type GenerationRoutingSettings,
  type ModelService,
  type RerankRoutingSettings,
} from '@/api/client'
import ModelServiceManager from '@/components/settings/ModelServiceManager.vue'
import SettingItem from '@/components/settings/SettingItem.vue'
import SettingSection from '@/components/settings/SettingSection.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import { useUiStore } from '@/stores/ui'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

type SelectValue = AcceptableValue | undefined

const uiStore = useUiStore()

const services = ref<ModelService[]>([])
const loading = ref(true)
const showManager = ref(false)

const generationSettings = ref<GenerationRoutingSettings>({
  defaultServiceId: undefined,
  sceneServiceBindings: {},
})

const embeddingSettings = ref<EmbeddingRoutingSettings>({
  defaultServiceId: undefined,
  knowledgeBaseServiceId: undefined,
  memoryServiceId: undefined,
})

const rerankSettings = ref<RerankRoutingSettings>({
  enabled: false,
  mode: 'DISABLED',
  nativeServiceId: undefined,
  llmServiceId: undefined,
  knowledgeTopK: 5,
  memoryEnabled: false,
  memoryTopK: 10,
})

const generationSceneOptions = [
  { value: 'chat', label: '通用对话' },
  { value: 'agent_react', label: 'Agent 推理' },
  { value: 'knowledge_extraction', label: '知识提取' },
  { value: 'memory_compression', label: '记忆压缩' },
  { value: 'skill_generation', label: '技能生成' },
]

const rerankModeOptions = [
  { value: 'DISABLED', label: '关闭' },
  { value: 'NATIVE', label: '原生精排' },
  { value: 'LLM_POINTWISE', label: 'LLM 逐条评分' },
  { value: 'LLM_LISTWISE', label: 'LLM 批量排序' },
]

const generationServices = computed(() =>
  services.value.filter(service => service.kind === 'GENERATION'),
)

const embeddingServices = computed(() =>
  services.value.filter(service => service.kind === 'EMBEDDING'),
)

const rerankServices = computed(() =>
  services.value.filter(service => service.kind === 'RERANK'),
)

const rerankLlmServices = computed(() =>
  generationServices.value.filter(service =>
    !service.capabilities
    || service.capabilities.length === 0
    || service.capabilities.includes('CHAT')
    || service.capabilities.includes('STRUCTURED_OUTPUT'),
  ),
)

const summaryItems = computed(() => [
  { label: '模型服务', value: String(services.value.length) },
  { label: '生成服务', value: String(generationServices.value.length) },
  { label: '向量服务', value: String(embeddingServices.value.length) },
  { label: '精排服务', value: String(rerankServices.value.length) },
])

const rerankModeLabel = computed(() => (
  rerankModeOptions.find(option => option.value === rerankSettings.value.mode)?.label ?? rerankSettings.value.mode
))

function normalizeSelectValue(value: SelectValue): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number') return String(value)
  return ''
}

function valueOrUndefined(value: SelectValue, sentinel = '__none__'): string | undefined {
  const normalized = normalizeSelectValue(value)
  if (!normalized || normalized === sentinel) {
    return undefined
  }
  return normalized
}

function serviceLabel(serviceId?: string) {
  if (!serviceId) return '自动选择'
  const service = services.value.find(item => item.id === serviceId)
  return service?.displayName || service?.modelName || serviceId
}

async function loadData() {
  loading.value = true
  try {
    const [allServices, generation, embedding, rerank] = await Promise.all([
      modelServiceApi.listServices(),
      modelRoutingApi.getGenerationSettings(),
      modelRoutingApi.getEmbeddingSettings(),
      modelRoutingApi.getRerankSettings(),
    ])
    services.value = allServices
    generationSettings.value = generation
    embeddingSettings.value = embedding
    rerankSettings.value = rerank
  } catch (error) {
    console.error('加载模型与路由设置失败:', error)
    uiStore.showToast('error', '加载模型与路由设置失败')
  } finally {
    loading.value = false
  }
}

async function saveGenerationSettings() {
  try {
    generationSettings.value = await modelRoutingApi.updateGenerationSettings({
      defaultServiceId: generationSettings.value.defaultServiceId,
      sceneServiceBindings: generationSettings.value.sceneServiceBindings,
    })
    uiStore.showToast('success', '生成路由已保存')
  } catch (error) {
    console.error('保存生成路由失败:', error)
    uiStore.showToast('error', '保存生成路由失败')
    await loadData()
  }
}

async function saveEmbeddingSettings() {
  try {
    embeddingSettings.value = await modelRoutingApi.updateEmbeddingSettings({
      defaultServiceId: embeddingSettings.value.defaultServiceId,
      knowledgeBaseServiceId: embeddingSettings.value.knowledgeBaseServiceId,
      memoryServiceId: embeddingSettings.value.memoryServiceId,
    })
    uiStore.showToast('success', '向量路由已保存')
  } catch (error) {
    console.error('保存向量路由失败:', error)
    uiStore.showToast('error', '保存向量路由失败')
    await loadData()
  }
}

async function saveRerankSettings() {
  try {
    rerankSettings.value = await modelRoutingApi.updateRerankSettings({
      enabled: rerankSettings.value.enabled,
      mode: rerankSettings.value.mode,
      nativeServiceId: rerankSettings.value.nativeServiceId,
      llmServiceId: rerankSettings.value.llmServiceId,
      knowledgeTopK: rerankSettings.value.knowledgeTopK,
      memoryEnabled: rerankSettings.value.memoryEnabled,
      memoryTopK: rerankSettings.value.memoryTopK,
    })
    uiStore.showToast('success', '精排路由已保存')
  } catch (error) {
    console.error('保存精排路由失败:', error)
    uiStore.showToast('error', '保存精排路由失败')
    await loadData()
  }
}

function updateGenerationDefault(value: SelectValue) {
  generationSettings.value = {
    ...generationSettings.value,
    defaultServiceId: valueOrUndefined(value),
  }
}

function updateGenerationScene(scene: string, value: SelectValue) {
  const normalized = normalizeSelectValue(value)
  const bindings = { ...generationSettings.value.sceneServiceBindings }
  if (!normalized || normalized === '__inherit__') {
    delete bindings[scene]
  } else {
    bindings[scene] = normalized
  }
  generationSettings.value = {
    ...generationSettings.value,
    sceneServiceBindings: bindings,
  }
}

function updateEmbeddingField(field: keyof EmbeddingRoutingSettings, value: SelectValue) {
  embeddingSettings.value = {
    ...embeddingSettings.value,
    [field]: valueOrUndefined(value),
  }
}

function updateRerankMode(value: SelectValue) {
  const mode = normalizeSelectValue(value) || 'DISABLED'
  rerankSettings.value = {
    ...rerankSettings.value,
    mode,
    nativeServiceId: mode === 'NATIVE' ? rerankSettings.value.nativeServiceId : undefined,
    llmServiceId: mode === 'LLM_POINTWISE' || mode === 'LLM_LISTWISE'
      ? rerankSettings.value.llmServiceId
      : undefined,
  }
}

function updateRerankNativeService(value: SelectValue) {
  rerankSettings.value = {
    ...rerankSettings.value,
    nativeServiceId: valueOrUndefined(value),
  }
}

function updateRerankLlmService(value: SelectValue) {
  rerankSettings.value = {
    ...rerankSettings.value,
    llmServiceId: valueOrUndefined(value),
  }
}

function updateRerankSwitch(key: 'enabled' | 'memoryEnabled', value: boolean | 'indeterminate') {
  rerankSettings.value = {
    ...rerankSettings.value,
    [key]: value === true,
  }
}

function updateRerankNumber(key: 'knowledgeTopK' | 'memoryTopK', value: string | number) {
  const parsed = Number(value)
  rerankSettings.value = {
    ...rerankSettings.value,
    [key]: Number.isFinite(parsed) ? Math.max(1, Math.round(parsed)) : rerankSettings.value[key],
  }
}

async function handleManagerClose() {
  showManager.value = false
  await loadData()
}

onMounted(() => {
  void loadData()
})
</script>

<template>
  <div class="space-y-6">
    <StatePanel
      title="模型与路由"
      description="生成、向量和精排已拆成三条独立配置链路。这里保存的是运行时真正生效的模型服务选择。"
    >
      <template #icon>
        <Cpu class="size-5" />
      </template>
      <template #actions>
        <Button variant="outline" class="gap-2" @click="loadData">
          <RefreshCw class="size-4" />
          刷新
        </Button>
        <Button @click="showManager = true">
          管理模型服务
        </Button>
      </template>

      <div class="grid gap-3 md:grid-cols-4">
        <div
          v-for="item in summaryItems"
          :key="item.label"
          class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4"
        >
          <div class="text-xs uppercase tracking-[0.16em] text-muted-foreground">{{ item.label }}</div>
          <div class="mt-2 text-2xl font-semibold text-foreground">{{ item.value }}</div>
        </div>
      </div>
    </StatePanel>

    <div v-if="loading" class="space-y-6">
      <Skeleton class="h-56 w-full rounded-[calc(var(--radius)+8px)]" />
      <Skeleton class="h-56 w-full rounded-[calc(var(--radius)+8px)]" />
      <Skeleton class="h-56 w-full rounded-[calc(var(--radius)+8px)]" />
    </div>

    <template v-else>
      <section class="detail-card p-5">
        <SettingSection
          title="生成路由"
          description="管理对话、Agent 推理和结构化输出场景使用的生成服务。"
        >
          <template #header-actions>
            <Badge variant="outline">{{ serviceLabel(generationSettings.defaultServiceId) }}</Badge>
            <Button size="sm" @click="saveGenerationSettings">保存生成路由</Button>
          </template>

          <SettingItem
            label="默认生成服务"
            description="未命中场景绑定时使用的全局默认生成服务。"
          >
            <Select
              :model-value="generationSettings.defaultServiceId || '__none__'"
              @update:model-value="updateGenerationDefault"
            >
              <SelectTrigger class="w-[260px]">
                <SelectValue placeholder="选择默认生成服务" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__none__">自动选择</SelectItem>
                <SelectItem v-for="service in generationServices" :key="service.id" :value="service.id">
                  {{ service.displayName || service.modelName || service.id }}
                </SelectItem>
              </SelectContent>
            </Select>
          </SettingItem>

          <SettingItem
            v-for="scene in generationSceneOptions"
            :key="scene.value"
            :label="scene.label"
            description="为该场景单独绑定生成服务。未设置时回落到默认生成服务。"
          >
            <Select
              :model-value="generationSettings.sceneServiceBindings[scene.value] || '__inherit__'"
              @update:model-value="value => updateGenerationScene(scene.value, value)"
            >
              <SelectTrigger class="w-[260px]">
                <SelectValue placeholder="继承默认生成服务" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__inherit__">继承默认生成服务</SelectItem>
                <SelectItem v-for="service in generationServices" :key="service.id" :value="service.id">
                  {{ service.displayName || service.modelName || service.id }}
                </SelectItem>
              </SelectContent>
            </Select>
          </SettingItem>
        </SettingSection>
      </section>

      <section class="detail-card p-5">
        <SettingSection
          title="向量路由"
          description="单独控制知识库和记忆检索使用的向量服务。"
        >
          <template #header-actions>
            <Button size="sm" @click="saveEmbeddingSettings">保存向量路由</Button>
          </template>

          <SettingItem
            label="默认向量服务"
            description="未指定用途时使用的默认向量服务。"
          >
            <Select
              :model-value="embeddingSettings.defaultServiceId || '__none__'"
              @update:model-value="value => updateEmbeddingField('defaultServiceId', value)"
            >
              <SelectTrigger class="w-[260px]">
                <SelectValue placeholder="选择默认向量服务" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__none__">自动选择</SelectItem>
                <SelectItem v-for="service in embeddingServices" :key="service.id" :value="service.id">
                  {{ service.displayName || service.modelName || service.id }}
                </SelectItem>
              </SelectContent>
            </Select>
          </SettingItem>

          <SettingItem
            label="知识库向量服务"
            description="知识库构建和检索优先使用的向量服务。"
          >
            <Select
              :model-value="embeddingSettings.knowledgeBaseServiceId || '__none__'"
              @update:model-value="value => updateEmbeddingField('knowledgeBaseServiceId', value)"
            >
              <SelectTrigger class="w-[260px]">
                <SelectValue placeholder="选择知识库向量服务" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__none__">继承默认向量服务</SelectItem>
                <SelectItem v-for="service in embeddingServices" :key="service.id" :value="service.id">
                  {{ service.displayName || service.modelName || service.id }}
                </SelectItem>
              </SelectContent>
            </Select>
          </SettingItem>

          <SettingItem
            label="记忆向量服务"
            description="记忆索引、召回和语义检索优先使用的向量服务。"
          >
            <Select
              :model-value="embeddingSettings.memoryServiceId || '__none__'"
              @update:model-value="value => updateEmbeddingField('memoryServiceId', value)"
            >
              <SelectTrigger class="w-[260px]">
                <SelectValue placeholder="选择记忆向量服务" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__none__">继承默认向量服务</SelectItem>
                <SelectItem v-for="service in embeddingServices" :key="service.id" :value="service.id">
                  {{ service.displayName || service.modelName || service.id }}
                </SelectItem>
              </SelectContent>
            </Select>
          </SettingItem>
        </SettingSection>
      </section>

      <section class="detail-card p-5">
        <SettingSection
          title="精排路由"
          description="精排模式与模型服务分离配置，支持原生精排和 LLM 精排两条执行链。"
        >
          <template #header-actions>
            <Badge variant="outline">{{ rerankModeLabel }}</Badge>
            <Button size="sm" @click="saveRerankSettings">保存精排路由</Button>
          </template>

          <SettingItem
            label="启用精排"
            description="关闭后知识库和记忆检索都不会执行二次精排。"
          >
            <Switch
              :model-value="rerankSettings.enabled"
              @update:model-value="value => updateRerankSwitch('enabled', value)"
            />
          </SettingItem>

          <SettingItem
            label="执行模式"
            description="原生精排使用 RERANK 服务；LLM 模式使用生成服务进行评分或排序。"
          >
            <Select :model-value="rerankSettings.mode" @update:model-value="updateRerankMode">
              <SelectTrigger class="w-[260px]">
                <SelectValue placeholder="选择精排模式" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem v-for="option in rerankModeOptions" :key="option.value" :value="option.value">
                  {{ option.label }}
                </SelectItem>
              </SelectContent>
            </Select>
          </SettingItem>

          <SettingItem
            v-if="rerankSettings.mode === 'NATIVE'"
            label="原生精排服务"
            description="只显示声明为 RERANK 的服务。"
          >
            <Select
              :model-value="rerankSettings.nativeServiceId || '__none__'"
              @update:model-value="updateRerankNativeService"
            >
              <SelectTrigger class="w-[260px]">
                <SelectValue placeholder="选择原生精排服务" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__none__">未设置</SelectItem>
                <SelectItem v-for="service in rerankServices" :key="service.id" :value="service.id">
                  {{ service.displayName || service.modelName || service.id }}
                </SelectItem>
              </SelectContent>
            </Select>
          </SettingItem>

          <SettingItem
            v-if="rerankSettings.mode === 'LLM_POINTWISE' || rerankSettings.mode === 'LLM_LISTWISE'"
            label="LLM 精排服务"
            description="用于 pointwise 或 listwise 的生成服务。"
          >
            <Select
              :model-value="rerankSettings.llmServiceId || '__none__'"
              @update:model-value="updateRerankLlmService"
            >
              <SelectTrigger class="w-[260px]">
                <SelectValue placeholder="选择 LLM 精排服务" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__none__">未设置</SelectItem>
                <SelectItem v-for="service in rerankLlmServices" :key="service.id" :value="service.id">
                  {{ service.displayName || service.modelName || service.id }}
                </SelectItem>
              </SelectContent>
            </Select>
          </SettingItem>

          <SettingItem
            label="知识库 TopK"
            description="知识库精排后保留的最大结果数。"
          >
            <Input
              class="w-[120px]"
              type="number"
              :min="1"
              :model-value="rerankSettings.knowledgeTopK"
              @update:model-value="value => updateRerankNumber('knowledgeTopK', value)"
            />
          </SettingItem>

          <SettingItem
            label="记忆精排"
            description="控制记忆召回是否执行二次精排。"
          >
            <Switch
              :model-value="rerankSettings.memoryEnabled"
              @update:model-value="value => updateRerankSwitch('memoryEnabled', value)"
            />
          </SettingItem>

          <SettingItem
            label="记忆 TopK"
            description="记忆精排后保留的最大结果数。"
          >
            <Input
              class="w-[120px]"
              type="number"
              :min="1"
              :model-value="rerankSettings.memoryTopK"
              @update:model-value="value => updateRerankNumber('memoryTopK', value)"
            />
          </SettingItem>
        </SettingSection>
      </section>

      <StatePanel
        title="当前生效摘要"
        description="这里展示的是新路由表中的当前绑定结果，不再依赖用户设置里的旧 Provider 字段。"
      >
        <template #icon>
          <GitCompareArrows class="size-5" />
        </template>

        <div class="grid gap-3 md:grid-cols-3">
          <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4">
            <div class="mb-2 flex items-center gap-2 text-sm font-medium text-foreground">
              <Cpu class="size-4" />
              生成路由
            </div>
            <p class="text-sm text-muted-foreground">
              默认：{{ serviceLabel(generationSettings.defaultServiceId) }}
            </p>
          </div>
          <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4">
            <div class="mb-2 flex items-center gap-2 text-sm font-medium text-foreground">
              <Database class="size-4" />
              向量路由
            </div>
            <p class="text-sm text-muted-foreground">
              知识库：{{ serviceLabel(embeddingSettings.knowledgeBaseServiceId || embeddingSettings.defaultServiceId) }}
            </p>
          </div>
          <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 px-4 py-4">
            <div class="mb-2 flex items-center gap-2 text-sm font-medium text-foreground">
              <GitCompareArrows class="size-4" />
              精排路由
            </div>
            <p class="text-sm text-muted-foreground">
              {{ rerankSettings.enabled ? rerankModeLabel : '已关闭' }}
            </p>
          </div>
        </div>
      </StatePanel>
    </template>

    <ModelServiceManager v-if="showManager" @close="handleManagerClose" />
  </div>
</template>
