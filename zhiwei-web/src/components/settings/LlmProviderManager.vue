<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { llmProviderApi, type LlmProvider, type CreateProviderRequest, type UpdateProviderRequest } from '@/api/client'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import { Badge } from '@/components/ui/badge'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog'

const emit = defineEmits<{ close: [] }>()

const providers = ref<LlmProvider[]>([])
const presets = ref<LlmProvider[]>([])
const loading = ref(false)
const showForm = ref(false)
const showDeleteConfirm = ref(false)
const editingProvider = ref<LlmProvider | null>(null)
const deletingProviderId = ref<string | null>(null)
const formMode = ref<'create' | 'edit' | 'preset'>('create')

const formData = ref<CreateProviderRequest>({
  id: '', type: 'OPENAI_COMPATIBLE', apiUrl: '', apiKey: '', modelName: '',
  timeoutSeconds: 30, priority: 0, scenes: [], capabilities: ['CHAT'],
  enabled: true, costPerInputToken: 0, costPerOutputToken: 0,
  maxContextWindow: 4096, supportsStreaming: false, displayName: '', description: ''
})

const errors = ref<Record<string, string>>({})

const providerTypes = [
  { value: 'OLLAMA', label: 'Ollama（本地）' },
  { value: 'DEEPSEEK', label: 'DeepSeek' },
  { value: 'QWEN', label: '通义千问' },
  { value: 'GLM', label: '智谱 GLM' },
  { value: 'WENXIN', label: '文心一言' },
  { value: 'TEI', label: 'TEI（本地 Embedding）' },
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI 兼容' }
]

const capabilityOptions = [
  { value: 'CHAT', label: '对话' }, { value: 'EMBEDDING', label: '向量嵌入' },
  { value: 'STRUCTURED_OUTPUT', label: '结构化输出' }, { value: 'FUNCTION_CALLING', label: '函数调用' },
  { value: 'STREAMING', label: '流式输出' }, { value: 'VISION', label: '视觉理解' },
  { value: 'TTS', label: '文字转语音' }, { value: 'STT', label: '语音转文字' }
]

const sceneOptions = [
  { value: 'intent_understanding', label: '意图理解' },
  { value: 'task_planning', label: '任务规划' },
  { value: 'knowledge_extraction', label: '知识提取' },
  { value: 'chat', label: '通用对话' },
  { value: 'memory_compression', label: '记忆压缩' },
  { value: 'proactive_reasoning', label: '主动推理' },
  { value: 'code_generation', label: '代码生成' },
  { value: 'embedding', label: '向量嵌入' },
  { value: 'agent-reasoning', label: 'Agent 推理' },
  { value: 'agent-tool-calling', label: 'Agent 工具调用' },
  { value: 'agent-generation', label: 'Agent 响应生成' }
]

const customProviders = computed(() => providers.value.filter(p => !p.isPreset))

async function loadProviders() {
  loading.value = true
  try {
    providers.value = await llmProviderApi.listProviders()
    presets.value = await llmProviderApi.listPresets()
  } catch (err) { console.error('加载 Provider 列表失败:', err) }
  finally { loading.value = false }
}
function openCreateForm() {
  formMode.value = 'create'
  editingProvider.value = null
  formData.value = {
    id: '', type: 'OPENAI_COMPATIBLE', apiUrl: '', apiKey: '', modelName: '',
    timeoutSeconds: 30, priority: 0,
    scenes: ['chat', 'agent-reasoning', 'agent-tool-calling', 'agent-generation'],
    capabilities: ['CHAT'], enabled: true, costPerInputToken: 0, costPerOutputToken: 0,
    maxContextWindow: 4096, supportsStreaming: false, displayName: '', description: ''
  }
  errors.value = {}
  showForm.value = true
}

function openEditForm(provider: LlmProvider) {
  formMode.value = 'edit'
  editingProvider.value = provider
  formData.value = {
    id: provider.id, type: provider.type, apiUrl: provider.apiUrl || '', apiKey: '',
    modelName: provider.modelName, timeoutSeconds: provider.timeoutSeconds || 30,
    priority: provider.priority || 0, scenes: provider.scenes || [],
    capabilities: provider.capabilities || ['CHAT'], enabled: provider.enabled ?? true,
    costPerInputToken: provider.costPerInputToken || 0,
    costPerOutputToken: provider.costPerOutputToken || 0,
    maxContextWindow: provider.maxContextWindow || 4096,
    embeddingDimension: provider.embeddingDimension,
    supportsStreaming: provider.supportsStreaming ?? false,
    displayName: provider.displayName, description: provider.description
  }
  errors.value = {}
  showForm.value = true
}

function createFromPreset(preset: LlmProvider) {
  formMode.value = 'preset'
  editingProvider.value = null
  formData.value = {
    id: preset.id + '-custom', type: preset.type, apiUrl: preset.apiUrl || '', apiKey: '',
    modelName: preset.modelName, timeoutSeconds: preset.timeoutSeconds || 30,
    priority: preset.priority || 0, scenes: preset.scenes || [],
    capabilities: preset.capabilities || ['CHAT'], enabled: true,
    costPerInputToken: preset.costPerInputToken || 0,
    costPerOutputToken: preset.costPerOutputToken || 0,
    maxContextWindow: preset.maxContextWindow || 4096,
    embeddingDimension: preset.embeddingDimension,
    supportsStreaming: preset.supportsStreaming ?? false,
    displayName: preset.displayName, description: preset.description
  }
  errors.value = {}
  showForm.value = true
}

function validate(): boolean {
  errors.value = {}
  if (!formData.value.id?.trim()) errors.value.id = 'Provider ID 不能为空'
  if (!formData.value.apiUrl?.trim()) errors.value.apiUrl = 'API URL 不能为空'
  if (!formData.value.modelName?.trim()) errors.value.modelName = '模型名称不能为空'
  if (formData.value.timeoutSeconds && formData.value.timeoutSeconds <= 0) errors.value.timeoutSeconds = '超时时间必须大于 0'
  return Object.keys(errors.value).length === 0
}

async function saveProvider() {
  if (!validate()) return
  loading.value = true
  try {
    let result
    if (formMode.value === 'create' || formMode.value === 'preset') {
      result = await llmProviderApi.saveProvider(formData.value)
    } else {
      const updateData: UpdateProviderRequest = { ...formData.value }
      delete (updateData as any).id
      if (updateData.apiKey === '') delete updateData.apiKey
      result = await llmProviderApi.updateProvider(formData.value.id, updateData)
    }
    await loadProviders()
    showForm.value = false
  } catch (err: any) {
    console.error('保存 Provider 失败:', err)
    errors.value._general = err.message || err.code || '保存失败'
  } finally { loading.value = false }
}

async function toggleEnabled(provider: LlmProvider) {
  loading.value = true
  try {
    await llmProviderApi.updateProvider(provider.id, { enabled: !provider.enabled })
    await loadProviders()
  } catch (err) { console.error('更新 Provider 状态失败:', err) }
  finally { loading.value = false }
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
  } catch (err: any) {
    console.error('删除 Provider 失败:', err)
    alert(err.message || '删除失败')
  } finally { loading.value = false }
}

const deleteConfirmMessage = computed(() => {
  if (!deletingProviderId.value) return ''
  return `确定要删除 Provider "${deletingProviderId.value}" 吗？此操作不可恢复。`
})

function toggleScene(sceneValue: string) {
  const idx = formData.value.scenes!.indexOf(sceneValue)
  if (idx >= 0) formData.value.scenes!.splice(idx, 1)
  else formData.value.scenes!.push(sceneValue)
}

function toggleCapability(capValue: string) {
  const idx = formData.value.capabilities!.indexOf(capValue)
  if (idx >= 0) formData.value.capabilities!.splice(idx, 1)
  else formData.value.capabilities!.push(capValue)
}

onMounted(() => { loadProviders() })
</script>
<template>
  <Dialog :open="true" @update:open="(v: boolean) => { if (!v) emit('close') }">
    <DialogContent class="sm:max-w-6xl max-h-[90vh] overflow-hidden flex flex-col">
      <DialogHeader>
        <DialogTitle>LLM Provider 管理</DialogTitle>
        <DialogDescription>管理系统中的 LLM Provider 配置，包括新建、编辑、启用/禁用和删除</DialogDescription>
      </DialogHeader>

      <div class="flex-1 overflow-y-auto p-6">
        <div class="space-y-6">
          <div class="flex justify-between items-center">
            <h3 class="text-lg font-medium text-foreground">自定义 Provider</h3>
            <Button @click="openCreateForm">+ 新建 Provider</Button>
          </div>

          <!-- 自定义 Provider 列表 -->
          <div v-if="customProviders.length > 0" class="space-y-2">
            <div v-for="provider in customProviders" :key="provider.id"
              class="flex items-center justify-between p-4 border border-border rounded-lg hover:bg-muted/50 transition-colors">
              <div class="flex-1">
                <div class="flex items-center gap-2">
                  <span class="font-medium">{{ provider.displayName || provider.id }}</span>
                  <Badge :variant="provider.enabled ? 'default' : 'secondary'">{{ provider.enabled ? '已启用' : '已禁用' }}</Badge>
                  <span v-if="provider.healthy !== undefined" class="w-2 h-2 rounded-full"
                    :class="provider.healthy ? 'bg-green-500' : 'bg-red-500'" :title="provider.healthy ? '健康' : '不健康'"></span>
                </div>
                <p class="text-sm text-muted-foreground mt-1">{{ provider.type }} / {{ provider.modelName }}</p>
              </div>
              <div class="flex items-center gap-2">
                <Button variant="ghost" size="sm" class="action-btn-link" @click="openEditForm(provider)">编辑</Button>
                <Button variant="outline" size="sm" :class="provider.enabled ? 'status-btn-active' : 'status-btn-inactive'" @click="toggleEnabled(provider)">{{ provider.enabled ? '禁用' : '启用' }}</Button>
                <Button variant="destructive" size="sm" @click="confirmDelete(provider)">删除</Button>
              </div>
            </div>
          </div>
          <div v-else class="text-center py-8 text-muted-foreground">暂无自定义 Provider，点击上方按钮创建</div>

          <!-- 预设置 Provider -->
          <div>
            <h3 class="text-lg font-medium text-foreground mb-4">预设置 Provider</h3>
            <div v-if="presets.length > 0" class="space-y-2">
              <div v-for="preset in presets" :key="preset.id"
                class="flex items-center justify-between p-4 border border-border rounded-lg hover:bg-muted/50 transition-colors">
                <div class="flex-1">
                  <div class="flex items-center gap-2">
                    <span class="font-medium">{{ preset.displayName || preset.id }}</span>
                    <Badge variant="outline">预设</Badge>
                    <Badge :variant="preset.enabled ? 'default' : 'secondary'">{{ preset.enabled ? '已启用' : '已禁用' }}</Badge>
                    <span v-if="preset.healthy !== undefined" class="w-2 h-2 rounded-full"
                      :class="preset.healthy ? 'bg-green-500' : 'bg-red-500'" :title="preset.healthy ? '健康' : '不健康'"></span>
                  </div>
                  <p class="text-sm text-muted-foreground mt-1">{{ preset.description }}</p>
                  <p class="text-xs text-muted-foreground mt-1">{{ preset.type }} / {{ preset.modelName }}</p>
                </div>
                <div class="flex items-center gap-2">
                  <Button variant="ghost" size="sm" class="action-btn-link" @click="openEditForm(preset)">编辑</Button>
                  <Button variant="outline" size="sm" :class="preset.enabled ? 'status-btn-active' : 'status-btn-inactive'" @click="toggleEnabled(preset)">{{ preset.enabled ? '禁用' : '启用' }}</Button>
                  <Button variant="destructive" size="sm" @click="confirmDelete(preset)">删除</Button>
                </div>
              </div>
            </div>
            <div v-else class="text-center py-8 text-muted-foreground">暂无预设置 Provider</div>
          </div>
        </div>
      </div>
    </DialogContent>
  </Dialog>
  <!-- Provider 表单对话框 -->
  <Dialog v-model:open="showForm">
    <DialogContent class="sm:max-w-[672px] max-h-[90vh] overflow-y-auto">
      <DialogHeader>
        <DialogTitle>{{ formMode === 'create' ? '新建 Provider' : formMode === 'preset' ? '从预设创建' : '编辑 Provider' }}</DialogTitle>
        <DialogDescription>填写 Provider 的基本信息、API 配置和支持的能力</DialogDescription>
      </DialogHeader>

      <form @submit.prevent="saveProvider" class="space-y-4">
        <div v-if="errors._general" class="p-3 bg-destructive/10 text-destructive rounded-md text-sm">{{ errors._general }}</div>

        <div v-if="formMode === 'create' || formMode === 'preset'" class="space-y-2">
          <Label>Provider ID <span class="text-destructive">*</span></Label>
          <Input v-model="formData.id" placeholder="例如: my-custom-provider" :class="{ 'border-destructive': errors.id }" />
          <p v-if="errors.id" class="text-sm text-destructive">{{ errors.id }}</p>
        </div>

        <div class="space-y-2">
          <Label>Provider 类型 <span class="text-destructive">*</span></Label>
          <Select v-model="formData.type">
            <SelectTrigger><SelectValue placeholder="选择类型" /></SelectTrigger>
            <SelectContent>
              <SelectItem v-for="opt in providerTypes" :key="opt.value" :value="opt.value">{{ opt.label }}</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div class="space-y-2">
          <Label>API URL <span class="text-destructive">*</span></Label>
          <Input v-model="formData.apiUrl" placeholder="例如: https://api.deepseek.com/v1" :class="{ 'border-destructive': errors.apiUrl }" />
          <p v-if="errors.apiUrl" class="text-sm text-destructive">{{ errors.apiUrl }}</p>
        </div>

        <div class="space-y-2">
          <Label>API Key</Label>
          <Input v-model="formData.apiKey" type="password" placeholder="请输入 API Key（留空则不更新）" />
        </div>

        <div class="space-y-2">
          <Label>模型名称 <span class="text-destructive">*</span></Label>
          <Input v-model="formData.modelName" placeholder="例如: deepseek-chat" :class="{ 'border-destructive': errors.modelName }" />
          <p v-if="errors.modelName" class="text-sm text-destructive">{{ errors.modelName }}</p>
        </div>

        <div class="space-y-2">
          <Label>超时时间（秒）</Label>
          <Input v-model.number="formData.timeoutSeconds" type="number" :min="1" :class="{ 'border-destructive': errors.timeoutSeconds }" />
          <p v-if="errors.timeoutSeconds" class="text-sm text-destructive">{{ errors.timeoutSeconds }}</p>
        </div>

        <div class="space-y-2">
          <Label>优先级（数值越小优先级越高）</Label>
          <Input v-model.number="formData.priority" type="number" :min="0" />
        </div>

        <div class="space-y-2">
          <Label>支持的场景</Label>
          <div class="flex flex-wrap gap-2">
            <label v-for="scene in sceneOptions" :key="scene.value"
              class="flex items-center gap-2 px-3 py-2 border border-border rounded-md hover:bg-accent cursor-pointer">
              <Checkbox :checked="formData.scenes?.includes(scene.value)" @update:checked="() => toggleScene(scene.value)" />
              <span class="text-sm">{{ scene.label }}</span>
            </label>
          </div>
          <p class="text-xs text-muted-foreground mt-2">提示：Agent 功能需要至少包含 agent-reasoning、agent-tool-calling 或 agent-generation 场景之一</p>
        </div>

        <div class="space-y-2">
          <Label>支持的能力</Label>
          <div class="flex flex-wrap gap-2">
            <label v-for="cap in capabilityOptions" :key="cap.value"
              class="flex items-center gap-2 px-3 py-2 border border-border rounded-md hover:bg-accent cursor-pointer">
              <Checkbox :checked="formData.capabilities?.includes(cap.value)" @update:checked="() => toggleCapability(cap.value)" />
              <span class="text-sm">{{ cap.label }}</span>
            </label>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <Checkbox :checked="formData.enabled" @update:checked="(v: boolean) => formData.enabled = v" />
          <Label class="cursor-pointer">启用此 Provider</Label>
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" @click="showForm = false">取消</Button>
          <Button type="submit" :disabled="loading">{{ loading ? '保存中...' : '保存' }}</Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>

  <!-- 删除确认对话框 -->
  <ConfirmDialog
    v-model:show="showDeleteConfirm"
    title="确认删除"
    :message="deleteConfirmMessage"
    confirm-label="删除"
    cancel-label="取消"
    confirm-variant="destructive"
    @confirm="handleDelete"
  />
</template>