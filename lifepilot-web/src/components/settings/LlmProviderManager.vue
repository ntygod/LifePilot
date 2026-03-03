<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { llmProviderApi, type LlmProvider, type CreateProviderRequest, type UpdateProviderRequest } from '@/api/client'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'

const emit = defineEmits<{
  close: []
}>()

const providers = ref<LlmProvider[]>([])
const presets = ref<LlmProvider[]>([])
const loading = ref(false)
const showForm = ref(false)
const showDeleteConfirm = ref(false)
const editingProvider = ref<LlmProvider | null>(null)
const deletingProviderId = ref<string | null>(null)
const formMode = ref<'create' | 'edit' | 'preset'>('create')

// 表单数据
const formData = ref<CreateProviderRequest>({
  id: '',
  type: 'OPENAI_COMPATIBLE',
  apiUrl: '',
  apiKey: '',
  modelName: '',
  timeoutSeconds: 30,
  priority: 0,
  scenes: [],
  capabilities: ['CHAT'],
  enabled: true,
  costPerInputToken: 0,
  costPerOutputToken: 0,
  maxContextWindow: 4096,
  supportsStreaming: false,
  displayName: '',
  description: ''
})

const errors = ref<Record<string, string>>({})

// Provider 类型选项
const providerTypes = [
  { value: 'OLLAMA', label: 'Ollama（本地）' },
  { value: 'DEEPSEEK', label: 'DeepSeek' },
  { value: 'QWEN', label: '通义千问' },
  { value: 'GLM', label: '智谱 GLM' },
  { value: 'WENXIN', label: '文心一言' },
  { value: 'TEI', label: 'TEI（本地 Embedding）' },
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI 兼容' }
]

// 能力选项
const capabilityOptions = [
  { value: 'CHAT', label: '对话' },
  { value: 'EMBEDDING', label: '向量嵌入' },
  { value: 'STRUCTURED_OUTPUT', label: '结构化输出' },
  { value: 'FUNCTION_CALLING', label: '函数调用' },
  { value: 'STREAMING', label: '流式输出' },
  { value: 'VISION', label: '视觉理解' },
  { value: 'TTS', label: '文字转语音' },
  { value: 'STT', label: '语音转文字' }
]

// 场景选项
const sceneOptions = [
  { value: 'intent_understanding', label: '意图理解' },
  { value: 'task_planning', label: '任务规划' },
  { value: 'knowledge_extraction', label: '知识提取' },
  { value: 'chat', label: '通用对话' },
  { value: 'memory_compression', label: '记忆压缩' },
  { value: 'proactive_reasoning', label: '主动推理' },
  { value: 'code_generation', label: '代码生成' },
  { value: 'embedding', label: '向量嵌入' },
  { value: 'agent-reasoning', label: 'Agent 推理（意图理解/任务规划/反思评估）' },
  { value: 'agent-tool-calling', label: 'Agent 工具调用' },
  { value: 'agent-generation', label: 'Agent 响应生成' }
]

// 自定义 Provider（非预设置）
const customProviders = computed(() => providers.value.filter(p => !p.isPreset))

// 加载数据
async function loadProviders() {
  loading.value = true
  try {
    providers.value = await llmProviderApi.listProviders()
    presets.value = await llmProviderApi.listPresets()
  } catch (err) {
    console.error('加载 Provider 列表失败:', err)
  } finally {
    loading.value = false
  }
}

// 打开创建表单
function openCreateForm() {
  formMode.value = 'create'
  editingProvider.value = null
  formData.value = {
    id: '',
    type: 'OPENAI_COMPATIBLE',
    apiUrl: '',
    apiKey: '',
    modelName: '',
    timeoutSeconds: 30,
    priority: 0,
    scenes: ['chat', 'agent-reasoning', 'agent-tool-calling', 'agent-generation'], // 默认包含常用场景
    capabilities: ['CHAT'],
    enabled: true,
    costPerInputToken: 0,
    costPerOutputToken: 0,
    maxContextWindow: 4096,
    supportsStreaming: false,
    displayName: '',
    description: ''
  }
  errors.value = {}
  showForm.value = true
}

// 打开编辑表单
function openEditForm(provider: LlmProvider) {
  formMode.value = 'edit'
  editingProvider.value = provider
  formData.value = {
    id: provider.id,
    type: provider.type,
    apiUrl: provider.apiUrl || '',
    apiKey: '', // 不显示已有 API Key
    modelName: provider.modelName,
    timeoutSeconds: provider.timeoutSeconds || 30,
    priority: provider.priority || 0,
    scenes: provider.scenes || [],
    capabilities: provider.capabilities || ['CHAT'],
    enabled: provider.enabled ?? true,
    costPerInputToken: provider.costPerInputToken || 0,
    costPerOutputToken: provider.costPerOutputToken || 0,
    maxContextWindow: provider.maxContextWindow || 4096,
    embeddingDimension: provider.embeddingDimension,
    supportsStreaming: provider.supportsStreaming ?? false,
    displayName: provider.displayName,
    description: provider.description
  }
  errors.value = {}
  showForm.value = true
}

// 从预设置创建
function createFromPreset(preset: LlmProvider) {
  formMode.value = 'preset'
  editingProvider.value = null
  formData.value = {
    id: preset.id + '-custom',
    type: preset.type,
    apiUrl: preset.apiUrl || '',
    apiKey: '',
    modelName: preset.modelName,
    timeoutSeconds: preset.timeoutSeconds || 30,
    priority: preset.priority || 0,
    scenes: preset.scenes || [],
    capabilities: preset.capabilities || ['CHAT'],
    enabled: true,
    costPerInputToken: preset.costPerInputToken || 0,
    costPerOutputToken: preset.costPerOutputToken || 0,
    maxContextWindow: preset.maxContextWindow || 4096,
    embeddingDimension: preset.embeddingDimension,
    supportsStreaming: preset.supportsStreaming ?? false,
    displayName: preset.displayName,
    description: preset.description
  }
  errors.value = {}
  showForm.value = true
}

// 表单验证
function validate(): boolean {
  errors.value = {}
  if (!formData.value.id?.trim()) {
    errors.value.id = 'Provider ID 不能为空'
  }
  if (!formData.value.apiUrl?.trim()) {
    errors.value.apiUrl = 'API URL 不能为空'
  }
  if (!formData.value.modelName?.trim()) {
    errors.value.modelName = '模型名称不能为空'
  }
  if (formData.value.timeoutSeconds && formData.value.timeoutSeconds <= 0) {
    errors.value.timeoutSeconds = '超时时间必须大于 0'
  }
  return Object.keys(errors.value).length === 0
}

// 保存 Provider
async function saveProvider() {
  if (!validate()) return
  
  loading.value = true
  try {
    let result
    if (formMode.value === 'create' || formMode.value === 'preset') {
      console.log('保存 Provider:', formData.value)
      result = await llmProviderApi.saveProvider(formData.value)
      console.log('保存 Provider 成功:', result)
    } else {
      const updateData: UpdateProviderRequest = { ...formData.value }
      delete (updateData as any).id // 更新时不需要 ID
      // 如果 apiKey 为空字符串，表示用户未修改，不发送该字段
      if (updateData.apiKey === '') {
        delete updateData.apiKey
      }
      console.log('更新 Provider:', formData.value.id, updateData)
      result = await llmProviderApi.updateProvider(formData.value.id, updateData)
      console.log('更新 Provider 成功:', result)
    }
    // 确保响应已返回
    if (result) {
      await loadProviders()
      showForm.value = false
    } else {
      console.warn('保存 Provider 返回结果为空，但继续执行')
      await loadProviders()
      showForm.value = false
    }
  } catch (err: any) {
    console.error('保存 Provider 失败:', err)
    errors.value._general = err.message || err.code || '保存失败'
    // 即使出错也要重置 loading 状态
    loading.value = false
  } finally {
    loading.value = false
  }
}

// 切换启用状态
async function toggleEnabled(provider: LlmProvider) {
  loading.value = true
  try {
    await llmProviderApi.updateProvider(provider.id, {
      enabled: !provider.enabled
    })
    await loadProviders()
  } catch (err) {
    console.error('更新 Provider 状态失败:', err)
  } finally {
    loading.value = false
  }
}

// 删除 Provider
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
  } finally {
    loading.value = false
  }
}

// 删除确认消息
const deleteConfirmMessage = computed(() => {
  if (!deletingProviderId.value) return ''
  return `确定要删除 Provider "${deletingProviderId.value}" 吗？此操作不可恢复。`
})

onMounted(() => {
  loadProviders()
})
</script>

<template>
  <div class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="emit('close')">
    <div class="bg-card border border-border rounded-lg shadow-lg w-full max-w-6xl max-h-[90vh] overflow-hidden m-4 flex flex-col">
      <!-- 头部 -->
      <div class="p-6 border-b border-border">
        <div class="flex items-center justify-between">
          <h2 class="text-xl font-semibold text-foreground">LLM Provider 管理</h2>
          <button
            class="text-muted-foreground hover:text-foreground transition-colors text-2xl"
            @click="emit('close')"
          >×</button>
        </div>
      </div>

      <!-- 内容区域 -->
      <div class="flex-1 overflow-y-auto p-6">
        <div class="space-y-6">
          <!-- 操作按钮 -->
          <div class="flex justify-between items-center">
            <h3 class="text-lg font-medium text-foreground">自定义 Provider</h3>
            <button
              class="px-4 py-2 bg-primary text-primary-foreground rounded-md hover:bg-primary/90 transition-colors"
              @click="openCreateForm"
            >
              + 新建 Provider
            </button>
          </div>

          <!-- 自定义 Provider 列表 -->
          <div v-if="customProviders.length > 0" class="space-y-2">
            <div
              v-for="provider in customProviders"
              :key="provider.id"
              class="flex items-center justify-between p-4 border border-border rounded-lg hover:bg-muted/50 transition-colors"
            >
              <div class="flex-1">
                <div class="flex items-center gap-2">
                  <span class="font-medium">{{ provider.displayName || provider.id }}</span>
                  <span
                    class="px-2 py-0.5 text-xs rounded"
                    :class="provider.enabled ? 'bg-green-500/10 text-green-700 dark:text-green-400' : 'bg-gray-500/10 text-gray-700 dark:text-gray-400'"
                  >
                    {{ provider.enabled ? '已启用' : '已禁用' }}
                  </span>
                  <span
                    v-if="provider.healthy !== undefined"
                    class="w-2 h-2 rounded-full"
                    :class="provider.healthy ? 'bg-green-500' : 'bg-red-500'"
                    :title="provider.healthy ? '健康' : '不健康'"
                  ></span>
                </div>
                <p class="text-sm text-muted-foreground mt-1">
                  {{ provider.type }} / {{ provider.modelName }}
                </p>
              </div>
              <div class="flex items-center gap-2">
                <button
                  class="px-3 py-1 text-sm border border-border rounded hover:bg-accent transition-colors"
                  @click="openEditForm(provider)"
                >
                  编辑
                </button>
                <button
                  class="px-3 py-1 text-sm border border-border rounded hover:bg-accent transition-colors"
                  @click="toggleEnabled(provider)"
                >
                  {{ provider.enabled ? '禁用' : '启用' }}
                </button>
                <button
                  class="px-3 py-1 text-sm border border-destructive text-destructive rounded hover:bg-destructive/10 transition-colors"
                  @click="confirmDelete(provider)"
                >
                  删除
                </button>
              </div>
            </div>
          </div>
          <div v-else class="text-center py-8 text-muted-foreground">
            暂无自定义 Provider，点击上方按钮创建
          </div>

          <!-- 预设置 Provider -->
          <div>
            <h3 class="text-lg font-medium text-foreground mb-4">预设置 Provider</h3>
            <div v-if="presets.length > 0" class="space-y-2">
              <div
                v-for="preset in presets"
                :key="preset.id"
                class="flex items-center justify-between p-4 border border-border rounded-lg hover:bg-muted/50 transition-colors"
              >
                <div class="flex-1">
                  <div class="flex items-center gap-2">
                    <span class="font-medium">{{ preset.displayName || preset.id }}</span>
                    <span
                      class="px-2 py-0.5 text-xs rounded bg-blue-500/10 text-blue-700 dark:text-blue-400"
                    >
                      预设
                    </span>
                    <span
                      class="px-2 py-0.5 text-xs rounded"
                      :class="preset.enabled ? 'bg-green-500/10 text-green-700 dark:text-green-400' : 'bg-gray-500/10 text-gray-700 dark:text-gray-400'"
                    >
                      {{ preset.enabled ? '已启用' : '已禁用' }}
                    </span>
                    <span
                      v-if="preset.healthy !== undefined"
                      class="w-2 h-2 rounded-full"
                      :class="preset.healthy ? 'bg-green-500' : 'bg-red-500'"
                      :title="preset.healthy ? '健康' : '不健康'"
                    ></span>
                  </div>
                  <p class="text-sm text-muted-foreground mt-1">{{ preset.description }}</p>
                  <p class="text-xs text-muted-foreground mt-1">
                    {{ preset.type }} / {{ preset.modelName }}
                  </p>
                </div>
                <div class="flex items-center gap-2">
                  <button
                    class="px-3 py-1 text-sm border border-border rounded hover:bg-accent transition-colors"
                    @click="openEditForm(preset)"
                  >
                    编辑
                  </button>
                  <button
                    class="px-3 py-1 text-sm border border-border rounded hover:bg-accent transition-colors"
                    @click="toggleEnabled(preset)"
                  >
                    {{ preset.enabled ? '禁用' : '启用' }}
                  </button>
                  <button
                    class="px-3 py-1 text-sm border border-destructive text-destructive rounded hover:bg-destructive/10 transition-colors"
                    @click="confirmDelete(preset)"
                  >
                    删除
                  </button>
                </div>
              </div>
            </div>
            <div v-else class="text-center py-8 text-muted-foreground">
              暂无预设置 Provider
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Provider 表单对话框 -->
    <div
      v-if="showForm"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-[60]"
      @click.self="showForm = false"
    >
      <div class="bg-card border border-border rounded-lg shadow-lg w-full max-w-[672px] max-h-[90vh] overflow-y-auto m-4">
        <div class="p-6">
          <div class="flex items-center justify-between mb-6">
            <h2 class="text-xl font-semibold text-foreground">
              {{ formMode === 'create' ? '新建 Provider' : formMode === 'preset' ? '从预设创建' : '编辑 Provider' }}
            </h2>
            <button
              class="text-muted-foreground hover:text-foreground transition-colors text-2xl"
              @click="showForm = false"
            >×</button>
          </div>

          <form @submit.prevent="saveProvider" class="space-y-4">
            <!-- 错误提示 -->
            <div v-if="errors._general" class="p-3 bg-destructive/10 text-destructive rounded-md text-sm">
              {{ errors._general }}
            </div>

            <!-- Provider ID（仅创建时） -->
            <div v-if="formMode === 'create' || formMode === 'preset'">
              <label class="block text-sm font-medium text-foreground mb-1">
                Provider ID <span class="text-destructive">*</span>
              </label>
              <input
                v-model="formData.id"
                type="text"
                class="w-full px-3 py-2 bg-background border border-border rounded-md text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
                :class="{ 'border-destructive': errors.id }"
                placeholder="例如: my-custom-provider"
              />
              <p v-if="errors.id" class="text-sm text-destructive mt-1">{{ errors.id }}</p>
            </div>

            <!-- Provider 类型 -->
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">
                Provider 类型 <span class="text-destructive">*</span>
              </label>
              <select
                v-model="formData.type"
                class="w-full px-3 py-2 bg-background border border-border rounded-md text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
              >
                <option v-for="opt in providerTypes" :key="opt.value" :value="opt.value">
                  {{ opt.label }}
                </option>
              </select>
            </div>

            <!-- API URL -->
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">
                API URL <span class="text-destructive">*</span>
              </label>
              <input
                v-model="formData.apiUrl"
                type="url"
                class="w-full px-3 py-2 bg-background border border-border rounded-md text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
                :class="{ 'border-destructive': errors.apiUrl }"
                placeholder="例如: https://api.deepseek.com/v1"
              />
              <p v-if="errors.apiUrl" class="text-sm text-destructive mt-1">{{ errors.apiUrl }}</p>
            </div>

            <!-- API Key -->
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">
                API Key
              </label>
              <input
                v-model="formData.apiKey"
                type="password"
                class="w-full px-3 py-2 bg-background border border-border rounded-md text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
                placeholder="请输入 API Key（留空则不更新）"
              />
            </div>

            <!-- 模型名称 -->
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">
                模型名称 <span class="text-destructive">*</span>
              </label>
              <input
                v-model="formData.modelName"
                type="text"
                class="w-full px-3 py-2 bg-background border border-border rounded-md text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
                :class="{ 'border-destructive': errors.modelName }"
                placeholder="例如: deepseek-chat"
              />
              <p v-if="errors.modelName" class="text-sm text-destructive mt-1">{{ errors.modelName }}</p>
            </div>

            <!-- 超时时间 -->
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">
                超时时间（秒）
              </label>
              <input
                v-model.number="formData.timeoutSeconds"
                type="number"
                min="1"
                class="w-full px-3 py-2 bg-background border border-border rounded-md text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
                :class="{ 'border-destructive': errors.timeoutSeconds }"
              />
              <p v-if="errors.timeoutSeconds" class="text-sm text-destructive mt-1">{{ errors.timeoutSeconds }}</p>
            </div>

            <!-- 优先级 -->
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">
                优先级（数值越小优先级越高）
              </label>
              <input
                v-model.number="formData.priority"
                type="number"
                min="0"
                class="w-full px-3 py-2 bg-background border border-border rounded-md text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
              />
            </div>

            <!-- 场景 -->
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">
                支持的场景
              </label>
              <div class="flex flex-wrap gap-2">
                <label
                  v-for="scene in sceneOptions"
                  :key="scene.value"
                  class="flex items-center gap-2 px-3 py-2 border border-border rounded-md hover:bg-accent cursor-pointer"
                >
                  <input
                    v-model="formData.scenes"
                    type="checkbox"
                    :value="scene.value"
                    class="accent-primary"
                  />
                  <span class="text-sm">{{ scene.label }}</span>
                </label>
              </div>
              <p class="text-xs text-muted-foreground mt-2">
                提示：Agent 功能需要至少包含 agent-reasoning、agent-tool-calling 或 agent-generation 场景之一
              </p>
            </div>

            <!-- 能力 -->
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">
                支持的能力
              </label>
              <div class="flex flex-wrap gap-2">
                <label
                  v-for="cap in capabilityOptions"
                  :key="cap.value"
                  class="flex items-center gap-2 px-3 py-2 border border-border rounded-md hover:bg-accent cursor-pointer"
                >
                  <input
                    v-model="formData.capabilities"
                    type="checkbox"
                    :value="cap.value"
                    class="accent-primary"
                  />
                  <span class="text-sm">{{ cap.label }}</span>
                </label>
              </div>
            </div>

            <!-- 启用状态 -->
            <div>
              <label class="flex items-center gap-2 cursor-pointer">
                <input
                  v-model="formData.enabled"
                  type="checkbox"
                  class="accent-primary"
                />
                <span class="text-sm font-medium text-foreground">启用此 Provider</span>
              </label>
            </div>

            <!-- 操作按钮 -->
            <div class="flex justify-end gap-2 pt-4">
              <button
                type="button"
                class="px-4 py-2 border border-border rounded-md 
                       transition-all duration-200
                       hover:bg-accent hover:shadow-sm hover:border-ring
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2
                       active:scale-[0.98]"
                @click="showForm = false"
              >
                取消
              </button>
              <button
                type="submit"
                class="px-4 py-2 bg-primary text-primary-foreground rounded-md 
                       transition-all duration-200
                       hover:bg-primary/90 hover:shadow-md hover:scale-[1.02]
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2
                       active:scale-[0.98]
                       disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:scale-100 disabled:hover:shadow-none"
                :disabled="loading"
              >
                {{ loading ? '保存中...' : '保存' }}
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>

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
  </div>
</template>
