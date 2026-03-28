<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { AcceptableValue } from 'reka-ui'
import { modelServiceApi, type CreateModelServiceRequest, type ModelService } from '@/api/client'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { useUiStore } from '@/stores/ui'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
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

type SelectValue = AcceptableValue | undefined

const emit = defineEmits<{ close: [] }>()
const uiStore = useUiStore()

const services = ref<ModelService[]>([])
const loading = ref(false)
const showForm = ref(false)
const showDeleteConfirm = ref(false)
const deletingServiceId = ref<string | null>(null)
const isEditing = ref(false)

const kindOptions = [
  { value: 'GENERATION', label: '生成服务' },
  { value: 'EMBEDDING', label: '向量服务' },
  { value: 'RERANK', label: '精排服务' },
]

const typeOptions = [
  { value: 'OLLAMA', label: 'Ollama（本地）' },
  { value: 'DEEPSEEK', label: 'DeepSeek' },
  { value: 'QWEN', label: 'Qwen' },
  { value: 'GLM', label: 'GLM' },
  { value: 'WENXIN', label: 'Wenxin' },
  { value: 'TEI', label: 'TEI（本地推理）' },
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI 兼容' },
  { value: 'ANTHROPIC', label: 'Anthropic' },
]

const generationCapabilityOptions = [
  { value: 'CHAT', label: '对话' },
  { value: 'STRUCTURED_OUTPUT', label: '结构化输出' },
  { value: 'FUNCTION_CALLING', label: '函数调用' },
  { value: 'STREAMING', label: '流式输出' },
  { value: 'VISION', label: '视觉理解' },
  { value: 'NATIVE_AUDIO', label: '原生音频' },
  { value: 'NATIVE_VIDEO', label: '原生视频' },
]

const generationSceneOptions = [
  { value: 'chat', label: '通用对话' },
  { value: 'agent_react', label: 'Agent 推理' },
  { value: 'knowledge_extraction', label: '知识提取' },
  { value: 'memory_compression', label: '记忆压缩' },
  { value: 'skill_generation', label: '技能生成' },
]

const formData = ref<CreateModelServiceRequest>({
  id: '',
  kind: 'GENERATION',
  type: 'OPENAI_COMPATIBLE',
  apiUrl: '',
  apiKey: '',
  modelName: '',
  timeoutSeconds: 30,
  priority: 0,
  scenes: ['chat', 'agent_react'],
  capabilities: ['CHAT'],
  enabled: true,
  costPerInputToken: 0,
  costPerOutputToken: 0,
  maxContextWindow: 131072,
  embeddingDimension: undefined,
  supportsStreaming: true,
  displayName: '',
  description: '',
})

const errors = ref<Record<string, string>>({})

const deleteConfirmMessage = computed(() => {
  if (!deletingServiceId.value) return ''
  return `确认删除模型服务“${deletingServiceId.value}”吗？此操作不可恢复。`
})

const groupedServices = computed(() => {
  const groups = new Map<string, ModelService[]>()
  for (const option of kindOptions) {
    groups.set(option.value, [])
  }
  for (const service of services.value) {
    const bucket = groups.get(service.kind) ?? []
    bucket.push(service)
    groups.set(service.kind, bucket)
  }
  return kindOptions.map(option => ({
    ...option,
    items: (groups.get(option.value) ?? []).sort((left, right) => {
      if ((left.priority ?? 0) !== (right.priority ?? 0)) {
        return (left.priority ?? 0) - (right.priority ?? 0)
      }
      return left.id.localeCompare(right.id)
    }),
  }))
})

const isGenerationKind = computed(() => formData.value.kind === 'GENERATION')
const isEmbeddingKind = computed(() => formData.value.kind === 'EMBEDDING')
const apiUrlPlaceholder = computed(() => {
  if (formData.value.type === 'OPENAI_COMPATIBLE') {
    return 'https://api.example.com'
  }
  if (formData.value.type === 'ANTHROPIC') {
    return 'https://api.anthropic.com'
  }
  return 'https://api.example.com/v1'
})

function normalizeSelectValue(value: SelectValue): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number') return String(value)
  return ''
}

function resetForm() {
  formData.value = {
    id: '',
    kind: 'GENERATION',
    type: 'OPENAI_COMPATIBLE',
    apiUrl: '',
    apiKey: '',
    modelName: '',
    timeoutSeconds: 30,
    priority: 0,
    scenes: ['chat', 'agent_react'],
    capabilities: ['CHAT'],
    enabled: true,
    costPerInputToken: 0,
    costPerOutputToken: 0,
    maxContextWindow: 131072,
    embeddingDimension: undefined,
    supportsStreaming: true,
    displayName: '',
    description: '',
  }
  errors.value = {}
}

function normalizeFormForKind() {
  if (formData.value.kind === 'GENERATION') {
    formData.value.capabilities = formData.value.capabilities?.length
      ? formData.value.capabilities
      : ['CHAT']
    formData.value.scenes = formData.value.scenes?.length
      ? formData.value.scenes
      : ['chat', 'agent_react']
    if (formData.value.supportsStreaming == null) {
      formData.value.supportsStreaming = formData.value.capabilities.includes('STREAMING')
    }
    return
  }

  formData.value.scenes = []
  formData.value.capabilities = []
  formData.value.supportsStreaming = false

  if (formData.value.kind === 'RERANK') {
    formData.value.embeddingDimension = undefined
  }
}

async function loadServices() {
  loading.value = true
  try {
    services.value = await modelServiceApi.listServices()
  } catch (error) {
    console.error('加载模型服务失败:', error)
    uiStore.showToast('error', '加载模型服务失败')
  } finally {
    loading.value = false
  }
}

function openCreateForm() {
  isEditing.value = false
  resetForm()
  showForm.value = true
}

function openEditForm(service: ModelService) {
  isEditing.value = true
  formData.value = {
    id: service.id,
    kind: service.kind,
    type: service.type,
    apiUrl: service.apiUrl ?? '',
    apiKey: '',
    modelName: service.modelName,
    timeoutSeconds: service.timeoutSeconds ?? 30,
    priority: service.priority ?? 0,
    scenes: [...(service.scenes ?? [])],
    capabilities: [...(service.capabilities ?? [])],
    enabled: service.enabled ?? true,
    costPerInputToken: service.costPerInputToken ?? 0,
    costPerOutputToken: service.costPerOutputToken ?? 0,
    maxContextWindow: service.maxContextWindow ?? 131072,
    embeddingDimension: service.embeddingDimension,
    supportsStreaming: service.supportsStreaming ?? false,
    displayName: service.displayName ?? '',
    description: service.description ?? '',
  }
  normalizeFormForKind()
  errors.value = {}
  showForm.value = true
}

function validate() {
  errors.value = {}

  if (!formData.value.id.trim()) {
    errors.value.id = '服务 ID 不能为空。'
  }
  if (!formData.value.apiUrl.trim()) {
    errors.value.apiUrl = 'API 地址不能为空。'
  }
  if (!formData.value.modelName.trim()) {
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
  normalizeFormForKind()
  if (!validate()) return

  loading.value = true
  try {
    const payload = { ...formData.value }
    if (isEditing.value && !payload.apiKey) {
      delete payload.apiKey
    }

    if (isEditing.value) {
      await modelServiceApi.updateService(payload.id, payload)
    } else {
      await modelServiceApi.createService(payload)
    }

    await loadServices()
    showForm.value = false
    uiStore.showToast('success', '模型服务已保存')
  } catch (error: any) {
    console.error('保存模型服务失败:', error)
    errors.value._general = error?.message || '保存模型服务失败。'
  } finally {
    loading.value = false
  }
}

async function toggleEnabled(service: ModelService) {
  loading.value = true
  try {
    await modelServiceApi.updateService(service.id, { enabled: !service.enabled })
    await loadServices()
  } catch (error) {
    console.error('更新模型服务状态失败:', error)
    uiStore.showToast('error', '更新模型服务状态失败')
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
  } catch (error: any) {
    console.error('删除模型服务失败:', error)
    uiStore.showToast('error', error?.message || '删除模型服务失败')
  } finally {
    loading.value = false
    showDeleteConfirm.value = false
    deletingServiceId.value = null
  }
}

function updateKind(value: SelectValue) {
  formData.value.kind = normalizeSelectValue(value) || 'GENERATION'
  normalizeFormForKind()
}

function updateType(value: SelectValue) {
  formData.value.type = normalizeSelectValue(value) || 'OPENAI_COMPATIBLE'
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

function kindLabel(kind: string) {
  return kindOptions.find(option => option.value === kind)?.label ?? kind
}

onMounted(() => {
  void loadServices()
})
</script>

<template>
  <Dialog :open="true" @update:open="(value: boolean) => { if (!value) emit('close') }">
    <DialogContent class="flex max-h-[90vh] max-w-6xl flex-col overflow-hidden">
      <DialogHeader>
        <DialogTitle>模型服务管理</DialogTitle>
        <DialogDescription>
          统一管理生成、向量和精排服务。服务保存后会立即参与运行时路由。
        </DialogDescription>
      </DialogHeader>

      <div class="flex-1 overflow-y-auto px-1 py-2">
        <div class="mb-4 flex items-center justify-between gap-4">
          <div>
            <h3 class="text-lg font-medium text-foreground">已注册服务</h3>
            <p class="text-sm text-muted-foreground">
              按服务类型分组展示，支持直接启用、编辑和删除。
            </p>
          </div>
          <Button @click="openCreateForm">
            新建模型服务
          </Button>
        </div>

        <div class="space-y-6">
          <section
            v-for="group in groupedServices"
            :key="group.value"
            class="space-y-3"
          >
            <div class="flex items-center gap-2">
              <h4 class="text-sm font-semibold text-foreground">{{ group.label }}</h4>
              <Badge variant="outline">{{ group.items.length }}</Badge>
            </div>

            <div v-if="group.items.length > 0" class="space-y-3">
              <article
                v-for="service in group.items"
                :key="service.id"
                class="flex flex-col gap-4 rounded-[calc(var(--radius)+8px)] border border-border/70 bg-background/72 p-4 lg:flex-row lg:items-center lg:justify-between"
              >
                <div class="min-w-0 flex-1">
                  <div class="flex flex-wrap items-center gap-2">
                    <span class="font-medium text-foreground">
                      {{ service.displayName || service.id }}
                    </span>
                    <Badge variant="outline">{{ kindLabel(service.kind) }}</Badge>
                    <Badge :variant="service.enabled ? 'default' : 'secondary'">
                      {{ service.enabled ? '已启用' : '已禁用' }}
                    </Badge>
                  </div>
                  <p class="mt-1 text-sm text-muted-foreground">
                    {{ service.type }} / {{ service.modelName }}
                  </p>
                  <p v-if="service.description" class="mt-2 text-sm text-muted-foreground">
                    {{ service.description }}
                  </p>
                </div>

                <div class="flex flex-wrap items-center gap-2">
                  <Button variant="ghost" size="sm" @click="openEditForm(service)">
                    编辑
                  </Button>
                  <Button variant="outline" size="sm" @click="toggleEnabled(service)">
                    {{ service.enabled ? '禁用' : '启用' }}
                  </Button>
                  <Button variant="destructive" size="sm" @click="confirmDelete(service)">
                    删除
                  </Button>
                </div>
              </article>
            </div>

            <div
              v-else
              class="rounded-[calc(var(--radius)+8px)] border border-dashed border-border/70 bg-background/60 px-5 py-6 text-sm text-muted-foreground"
            >
              当前没有{{ group.label }}。
            </div>
          </section>
        </div>
      </div>
    </DialogContent>
  </Dialog>

  <Dialog v-model:open="showForm">
    <DialogContent class="max-h-[90vh] overflow-y-auto sm:max-w-[760px]">
      <DialogHeader>
        <DialogTitle>{{ isEditing ? '编辑模型服务' : '新建模型服务' }}</DialogTitle>
        <DialogDescription>
          配置服务类型、底层模型、支持场景和能力边界。
        </DialogDescription>
      </DialogHeader>

      <form class="space-y-4" @submit.prevent="saveService">
        <div v-if="errors._general" class="rounded-md bg-destructive/10 p-3 text-sm text-destructive">
          {{ errors._general }}
        </div>

        <div v-if="!isEditing" class="space-y-2">
          <Label>服务 ID <span class="text-destructive">*</span></Label>
          <Input v-model="formData.id" placeholder="例如：openai-gpt5-chat" :class="{ 'border-destructive': errors.id }" />
          <p v-if="errors.id" class="text-sm text-destructive">{{ errors.id }}</p>
        </div>

        <div class="grid gap-4 md:grid-cols-2">
          <div class="space-y-2">
            <Label>服务类型 <span class="text-destructive">*</span></Label>
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
            <Label>底层类型 <span class="text-destructive">*</span></Label>
            <Select :model-value="formData.type" @update:model-value="updateType">
              <SelectTrigger>
                <SelectValue placeholder="选择底层类型" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem v-for="option in typeOptions" :key="option.value" :value="option.value">
                  {{ option.label }}
                </SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>

        <div class="grid gap-4 md:grid-cols-2">
          <div class="space-y-2">
            <Label>显示名称</Label>
            <Input v-model="formData.displayName" placeholder="例如：GPT-5 生产路由" />
          </div>
          <div class="space-y-2">
            <Label>模型名称 <span class="text-destructive">*</span></Label>
            <Input v-model="formData.modelName" placeholder="例如：gpt-5" :class="{ 'border-destructive': errors.modelName }" />
            <p v-if="errors.modelName" class="text-sm text-destructive">{{ errors.modelName }}</p>
          </div>
        </div>

        <div class="space-y-2">
          <Label>API 地址 <span class="text-destructive">*</span></Label>
          <Input v-model="formData.apiUrl" :placeholder="apiUrlPlaceholder" :class="{ 'border-destructive': errors.apiUrl }" />
          <p v-if="errors.apiUrl" class="text-sm text-destructive">{{ errors.apiUrl }}</p>
        </div>

        <div class="space-y-2">
          <Label>API 密钥</Label>
          <Input
            v-model="formData.apiKey"
            type="password"
            :placeholder="isEditing ? '留空则保留当前密钥' : '输入 API 密钥'"
          />
        </div>

        <div class="grid gap-4 md:grid-cols-3">
          <div class="space-y-2">
            <Label>超时时间（秒）</Label>
            <Input v-model.number="formData.timeoutSeconds" type="number" :min="1" />
            <p v-if="errors.timeoutSeconds" class="text-sm text-destructive">{{ errors.timeoutSeconds }}</p>
          </div>
          <div class="space-y-2">
            <Label>优先级</Label>
            <Input v-model.number="formData.priority" type="number" />
          </div>
          <div class="flex items-center justify-between rounded-lg border border-border/70 px-3 py-2">
            <div class="space-y-0.5">
              <Label>启用服务</Label>
              <p class="text-xs text-muted-foreground">关闭后不会参与运行时路由。</p>
            </div>
            <Switch :model-value="formData.enabled" @update:model-value="updateEnabled" />
          </div>
        </div>

        <div class="grid gap-4 md:grid-cols-3">
          <div class="space-y-2">
            <Label>输入成本（每百万 token）</Label>
            <Input v-model.number="formData.costPerInputToken" type="number" :min="0" />
          </div>
          <div class="space-y-2">
            <Label>输出成本（每百万 token）</Label>
            <Input v-model.number="formData.costPerOutputToken" type="number" :min="0" />
          </div>
          <div class="space-y-2">
            <Label>最大上下文窗口</Label>
            <Input v-model.number="formData.maxContextWindow" type="number" :min="0" />
          </div>
        </div>

        <div v-if="isEmbeddingKind" class="space-y-2">
          <Label>向量维度</Label>
          <Input v-model.number="formData.embeddingDimension" type="number" :min="1" />
        </div>

        <div v-if="isGenerationKind" class="space-y-4 rounded-lg border border-border/70 p-4">
          <div class="space-y-2">
            <Label>生成能力</Label>
            <div class="grid gap-3 md:grid-cols-2">
              <label
                v-for="option in generationCapabilityOptions"
                :key="option.value"
                class="flex items-center gap-3 rounded-md border border-border/60 px-3 py-2"
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
            <div class="grid gap-3 md:grid-cols-2">
              <label
                v-for="option in generationSceneOptions"
                :key="option.value"
                class="flex items-center gap-3 rounded-md border border-border/60 px-3 py-2"
              >
                <Checkbox
                  :model-value="formData.scenes?.includes(option.value)"
                  @update:model-value="() => toggleScene(option.value)"
                />
                <span class="text-sm">{{ option.label }}</span>
              </label>
            </div>
          </div>

          <div class="flex items-center justify-between rounded-lg border border-border/70 px-3 py-2">
            <div class="space-y-0.5">
              <Label>支持流式输出</Label>
              <p class="text-xs text-muted-foreground">仅对生成服务生效。</p>
            </div>
            <Switch :model-value="formData.supportsStreaming" @update:model-value="updateSupportsStreaming" />
          </div>
        </div>

        <div class="space-y-2">
          <Label>描述</Label>
          <Textarea v-model="formData.description" rows="4" placeholder="补充服务用途、约束或运维说明。" />
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" @click="showForm = false">取消</Button>
          <Button type="submit" :disabled="loading">
            {{ isEditing ? '保存修改' : '创建服务' }}
          </Button>
        </DialogFooter>
      </form>
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
