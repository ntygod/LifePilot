<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { llmProviderApi, type CreateProviderRequest, type LlmProvider } from '@/api/client'
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
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

const emit = defineEmits<{ close: [] }>()
const uiStore = useUiStore()

const providers = ref<LlmProvider[]>([])
const loading = ref(false)
const showForm = ref(false)
const showDeleteConfirm = ref(false)
const deletingProviderId = ref<string | null>(null)
const isEditing = ref(false)

const formData = ref<CreateProviderRequest>({
  id: '',
  type: 'OPENAI_COMPATIBLE',
  apiUrl: '',
  apiKey: '',
  modelName: '',
  timeoutSeconds: 30,
  priority: 0,
  scenes: [],
  capabilities: [],
  enabled: false,
  costPerInputToken: 0,
  costPerOutputToken: 0,
  maxContextWindow: 4096,
  supportsStreaming: false,
  displayName: '',
  description: '',
})

const errors = ref<Record<string, string>>({})

const providerTypes = [
  { value: 'OLLAMA', label: 'Ollama（本地）' },
  { value: 'DEEPSEEK', label: 'DeepSeek' },
  { value: 'QWEN', label: 'Qwen' },
  { value: 'GLM', label: 'GLM' },
  { value: 'WENXIN', label: 'Wenxin' },
  { value: 'TEI', label: 'TEI（本地向量）' },
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI 兼容' },
]

const capabilityOptions = [
  { value: 'CHAT', label: '对话' },
  { value: 'EMBEDDING', label: '向量化' },
  { value: 'STRUCTURED_OUTPUT', label: '结构化输出' },
  { value: 'FUNCTION_CALLING', label: '函数调用' },
  { value: 'STREAMING', label: '流式输出' },
  { value: 'VISION', label: '视觉理解' },
  { value: 'TTS', label: '文本转语音' },
  { value: 'STT', label: '语音转文本' },
]

const sceneOptions = [
  { value: 'chat', label: '通用对话' },
  { value: 'agent_react', label: 'Agent 推理' },
  { value: 'knowledge_extraction', label: '知识提取' },
  { value: 'knowledge_rerank', label: '知识库精排' },
  { value: 'memory_compression', label: '记忆压缩' },
  { value: 'proactive_reasoning', label: '主动推理' },
  { value: 'skill_generation', label: '技能生成' },
  { value: 'embedding', label: '向量化' },
]

const deleteConfirmMessage = computed(() => {
  if (!deletingProviderId.value) return ''
  return `确定删除模型服务「${deletingProviderId.value}」吗？此操作不可恢复。`
})

function showErrorToast(message: string) {
  uiStore.showToast('error', message)
}

function resetForm() {
  formData.value = {
    id: '',
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
    maxContextWindow: 4096,
    supportsStreaming: false,
    displayName: '',
    description: '',
  }
  errors.value = {}
}

async function loadProviders() {
  loading.value = true
  try {
    providers.value = await llmProviderApi.listProviders()
  } catch (error) {
    console.error('加载提供商失败:', error)
  } finally {
    loading.value = false
  }
}

function openCreateForm() {
  isEditing.value = false
  resetForm()
  showForm.value = true
}

function openEditForm(provider: LlmProvider) {
  isEditing.value = true
  formData.value = {
    id: provider.id,
    type: provider.type,
    apiUrl: provider.apiUrl ?? '',
    apiKey: '',
    modelName: provider.modelName,
    timeoutSeconds: provider.timeoutSeconds ?? 30,
    priority: provider.priority ?? 0,
    scenes: [...(provider.scenes ?? [])],
    capabilities: [...(provider.capabilities ?? [])],
    enabled: provider.enabled ?? false,
    costPerInputToken: provider.costPerInputToken ?? 0,
    costPerOutputToken: provider.costPerOutputToken ?? 0,
    maxContextWindow: provider.maxContextWindow ?? 4096,
    embeddingDimension: provider.embeddingDimension,
    supportsStreaming: provider.supportsStreaming ?? false,
    displayName: provider.displayName ?? '',
    description: provider.description ?? '',
  }
  errors.value = {}
  showForm.value = true
}

function validate() {
  errors.value = {}

  if (!formData.value.id?.trim()) errors.value.id = '必须填写服务 ID。'
  if (!formData.value.apiUrl?.trim()) errors.value.apiUrl = '必须填写 API 地址。'
  if (!formData.value.modelName?.trim()) errors.value.modelName = '必须填写模型名称。'
  if (formData.value.timeoutSeconds && formData.value.timeoutSeconds <= 0) {
    errors.value.timeoutSeconds = '超时时间必须大于 0。'
  }

  return Object.keys(errors.value).length === 0
}

async function saveProvider() {
  if (!validate()) return

  loading.value = true
  try {
    const payload = { ...formData.value }
    if (isEditing.value && !payload.apiKey) {
      delete payload.apiKey
    }

    await llmProviderApi.saveProvider(payload)
    await loadProviders()
    showForm.value = false
  } catch (error: any) {
    console.error('保存模型服务失败:', error)
    errors.value._general = error?.message || error?.code || '保存模型服务失败。'
  } finally {
    loading.value = false
  }
}

async function toggleEnabled(provider: LlmProvider) {
  loading.value = true
  try {
    await llmProviderApi.updateProvider(provider.id, { enabled: !provider.enabled })
    await loadProviders()
  } catch (error) {
    console.error('更新模型服务状态失败:', error)
  } finally {
    loading.value = false
  }
}

function confirmDelete(provider: LlmProvider) {
  deletingProviderId.value = provider.id
  showDeleteConfirm.value = true
}

async function handleDelete() {
  if (!deletingProviderId.value) return

  loading.value = true
  try {
    await llmProviderApi.deleteProvider(deletingProviderId.value)
    await loadProviders()
    showDeleteConfirm.value = false
    deletingProviderId.value = null
  } catch (error: any) {
    console.error('删除模型服务失败:', error)
    showErrorToast(error?.message || '删除模型服务失败。')
  } finally {
    loading.value = false
  }
}

function toggleScene(sceneValue: string) {
  const scenes = formData.value.scenes ?? []
  const index = scenes.indexOf(sceneValue)
  if (index >= 0) scenes.splice(index, 1)
  else scenes.push(sceneValue)
}

function toggleCapability(capabilityValue: string) {
  const capabilities = formData.value.capabilities ?? []
  const index = capabilities.indexOf(capabilityValue)
  if (index >= 0) capabilities.splice(index, 1)
  else capabilities.push(capabilityValue)
}

function updateEnabled(value: boolean | 'indeterminate') {
  formData.value.enabled = value === true
}

onMounted(() => {
  void loadProviders()
})
</script>

<template>
  <Dialog :open="true" @update:open="(value: boolean) => { if (!value) emit('close') }">
    <DialogContent class="flex max-h-[90vh] max-w-6xl flex-col overflow-hidden">
      <DialogHeader>
        <DialogTitle>模型服务管理</DialogTitle>
        <DialogDescription>
          查看已接入的模型服务，并进行启用、编辑或删除。
        </DialogDescription>
      </DialogHeader>

      <div class="flex-1 overflow-y-auto p-6">
        <div class="space-y-4">
          <div class="flex items-center justify-between gap-4">
            <div>
              <h3 class="text-lg font-medium text-foreground">
                已注册模型服务
              </h3>
              <p class="text-sm text-muted-foreground">
                管理已接入的模型服务，可随时启用、编辑或删除。
              </p>
            </div>
            <Button @click="openCreateForm">
              新建模型服务
            </Button>
          </div>

          <div v-if="providers.length > 0" class="space-y-3">
            <article
              v-for="provider in providers"
              :key="provider.id"
              class="flex flex-col gap-4 rounded-[calc(var(--radius)+8px)] border border-border/70 bg-background/72 p-4 lg:flex-row lg:items-center lg:justify-between"
            >
              <div class="min-w-0 flex-1">
                <div class="flex flex-wrap items-center gap-2">
                  <span class="font-medium text-foreground">
                    {{ provider.displayName || provider.id }}
                  </span>
                  <Badge v-if="provider.isPreset" variant="outline">
                    预置
                  </Badge>
                  <Badge :variant="provider.enabled ? 'default' : 'secondary'">
                    {{ provider.enabled ? '已启用' : '已禁用' }}
                  </Badge>
                </div>
                <p class="mt-1 text-sm text-muted-foreground">
                  {{ provider.type }} / {{ provider.modelName }}
                </p>
              </div>

              <div class="flex flex-wrap items-center gap-2">
                <Button variant="ghost" size="sm" @click="openEditForm(provider)">
                  编辑
                </Button>
                <Button variant="outline" size="sm" @click="toggleEnabled(provider)">
                  {{ provider.enabled ? '禁用' : '启用' }}
                </Button>
                <Button variant="destructive" size="sm" @click="confirmDelete(provider)">
                  删除
                </Button>
              </div>
            </article>
          </div>

          <div v-else class="rounded-[calc(var(--radius)+8px)] border border-dashed border-border/70 bg-background/60 px-5 py-8 text-center text-sm text-muted-foreground">
            还没有模型服务，点击上方按钮开始添加。
          </div>
        </div>
      </div>
    </DialogContent>
  </Dialog>

  <Dialog v-model:open="showForm">
    <DialogContent class="max-h-[90vh] overflow-y-auto sm:max-w-[720px]">
      <DialogHeader>
        <DialogTitle>{{ isEditing ? '编辑模型服务' : '新建模型服务' }}</DialogTitle>
        <DialogDescription>
          配置服务标识、API 访问方式、路由场景与支持能力。
        </DialogDescription>
      </DialogHeader>

      <form class="space-y-4" @submit.prevent="saveProvider">
        <div v-if="errors._general" class="rounded-md bg-destructive/10 p-3 text-sm text-destructive">
          {{ errors._general }}
        </div>

        <div v-if="!isEditing" class="space-y-2">
          <Label>服务 ID <span class="text-destructive">*</span></Label>
          <Input
            v-model="formData.id"
            placeholder="例如：my-custom-provider"
            :class="{ 'border-destructive': errors.id }"
          />
          <p v-if="errors.id" class="text-sm text-destructive">
            {{ errors.id }}
          </p>
        </div>

        <div class="space-y-2">
          <Label>服务类型 <span class="text-destructive">*</span></Label>
          <Select v-model="formData.type">
            <SelectTrigger>
              <SelectValue placeholder="选择服务类型" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem v-for="option in providerTypes" :key="option.value" :value="option.value">
                {{ option.label }}
              </SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div class="space-y-2">
          <Label>API 地址 <span class="text-destructive">*</span></Label>
          <Input
            v-model="formData.apiUrl"
            placeholder="https://api.example.com/v1"
            :class="{ 'border-destructive': errors.apiUrl }"
          />
          <p v-if="errors.apiUrl" class="text-sm text-destructive">
            {{ errors.apiUrl }}
          </p>
        </div>

        <div class="space-y-2">
          <Label>API 密钥</Label>
          <Input
            v-model="formData.apiKey"
            type="password"
            :placeholder="isEditing ? '留空则保留当前密钥' : '输入 API 密钥'"
          />
        </div>

        <div class="space-y-2">
          <Label>模型名称 <span class="text-destructive">*</span></Label>
          <Input
            v-model="formData.modelName"
            placeholder="例如：deepseek-chat"
            :class="{ 'border-destructive': errors.modelName }"
          />
          <p v-if="errors.modelName" class="text-sm text-destructive">
            {{ errors.modelName }}
          </p>
        </div>

        <div class="grid gap-4 md:grid-cols-2">
          <div class="space-y-2">
            <Label>超时时间（秒）</Label>
            <Input
              v-model.number="formData.timeoutSeconds"
              type="number"
              :min="1"
              :class="{ 'border-destructive': errors.timeoutSeconds }"
            />
            <p v-if="errors.timeoutSeconds" class="text-sm text-destructive">
              {{ errors.timeoutSeconds }}
            </p>
          </div>

          <div class="space-y-2">
            <Label>优先级</Label>
            <Input v-model.number="formData.priority" type="number" :min="0" />
          </div>
        </div>

        <div class="space-y-2">
          <Label>适用场景</Label>
          <div class="flex flex-wrap gap-2">
            <label
              v-for="scene in sceneOptions"
              :key="scene.value"
              class="flex cursor-pointer items-center gap-2 rounded-md border border-border px-3 py-2 hover:bg-accent"
            >
              <Checkbox
                :model-value="formData.scenes?.includes(scene.value)"
                @update:model-value="() => toggleScene(scene.value)"
              />
              <span class="text-sm">{{ scene.label }}</span>
            </label>
          </div>
        </div>

        <div class="space-y-2">
          <Label>支持能力</Label>
          <div class="flex flex-wrap gap-2">
            <label
              v-for="capability in capabilityOptions"
              :key="capability.value"
              class="flex cursor-pointer items-center gap-2 rounded-md border border-border px-3 py-2 hover:bg-accent"
            >
              <Checkbox
                :model-value="formData.capabilities?.includes(capability.value)"
                @update:model-value="() => toggleCapability(capability.value)"
              />
              <span class="text-sm">{{ capability.label }}</span>
            </label>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <Checkbox :model-value="formData.enabled" @update:model-value="updateEnabled" />
          <Label class="cursor-pointer">启用该模型服务</Label>
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" @click="showForm = false">
            取消
          </Button>
          <Button type="submit" :disabled="loading">
            {{ loading ? '保存中...' : '保存模型服务' }}
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
    cancel-label="取消"
    confirm-variant="destructive"
    @confirm="handleDelete"
  />
</template>
