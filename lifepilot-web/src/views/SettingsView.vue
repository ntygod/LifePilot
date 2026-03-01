<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useSettings } from '@/composables/useSettings'
import { settingsApi, type LlmProvider } from '@/api/client'
import type { ProviderCapability } from '@/types'

const { settings, loading, error, loadSettings, saveSettings } = useSettings()

const form = ref({
  theme: 'system' as 'light' | 'dark' | 'system',
  language: 'zh-CN',
  llmProvider: '',
  enableStreaming: true,
  enableFunctionCall: true,
  enableKnowledgeBase: true,
  enableToolCall: true,
})

const saving = ref(false)
const saveError = ref<string | null>(null)
const saveSuccess = ref(false)

// LLM Provider 数据
const providers = ref<LlmProvider[]>([])
const providerHealthStatus = ref<Record<string, boolean>>({})
const selectedProviderDetail = ref<LlmProvider | null>(null)
const loadingProviders = ref(false)
const loadingHealth = ref(false)

// 能力映射：前端功能开关 -> Provider 能力
const capabilityMap: Record<string, ProviderCapability> = {
  enableStreaming: 'STREAMING',
  enableFunctionCall: 'FUNCTION_CALLING',
  enableToolCall: 'FUNCTION_CALLING', // 工具调用也使用 FUNCTION_CALLING
  enableKnowledgeBase: 'EMBEDDING', // 知识库需要 Embedding 能力
}

// 计算：根据选中的 Provider 的能力，判断功能开关是否可用
const isCapabilityEnabled = (capability: ProviderCapability): boolean => {
  if (!selectedProviderDetail.value?.capabilities) return false
  return selectedProviderDetail.value.capabilities.includes(capability)
}

// 计算：功能开关是否可用（根据 Provider 能力）
const isFeatureEnabled = computed(() => ({
  enableStreaming: isCapabilityEnabled('STREAMING'),
  enableFunctionCall: isCapabilityEnabled('FUNCTION_CALLING'),
  enableToolCall: isCapabilityEnabled('FUNCTION_CALLING'),
  enableKnowledgeBase: isCapabilityEnabled('EMBEDDING'),
}))

// 计算：功能开关是否禁用（Provider 不支持时禁用）
const isFeatureDisabled = computed(() => ({
  enableStreaming: !isFeatureEnabled.value.enableStreaming,
  enableFunctionCall: !isFeatureEnabled.value.enableFunctionCall,
  enableToolCall: !isFeatureEnabled.value.enableToolCall,
  enableKnowledgeBase: !isFeatureEnabled.value.enableKnowledgeBase,
}))

// 加载可用的 Provider 列表
async function loadProviders() {
  loadingProviders.value = true
  try {
    providers.value = await settingsApi.getProviders()
    // 如果已选择 Provider，更新详细信息
    if (form.value.llmProvider) {
      updateSelectedProviderDetail()
    }
  } catch (err) {
    console.error('加载 Provider 列表失败:', err)
  } finally {
    loadingProviders.value = false
  }
}

// 加载 Provider 健康状态
async function loadProviderHealth() {
  loadingHealth.value = true
  try {
    providerHealthStatus.value = await settingsApi.getProviderHealth()
  } catch (err) {
    console.error('加载 Provider 健康状态失败:', err)
  } finally {
    loadingHealth.value = false
  }
}

// 更新选中的 Provider 详细信息
function updateSelectedProviderDetail() {
  if (!form.value.llmProvider) {
    selectedProviderDetail.value = null
    return
  }
  const provider = providers.value.find(p => p.id === form.value.llmProvider)
  if (provider) {
    selectedProviderDetail.value = provider
    // 如果 Provider 不支持某项能力，自动关闭对应的开关
    if (!isFeatureEnabled.value.enableStreaming) {
      form.value.enableStreaming = false
    }
    if (!isFeatureEnabled.value.enableFunctionCall) {
      form.value.enableFunctionCall = false
    }
    if (!isFeatureEnabled.value.enableToolCall) {
      form.value.enableToolCall = false
    }
    if (!isFeatureEnabled.value.enableKnowledgeBase) {
      form.value.enableKnowledgeBase = false
    }
  } else {
    selectedProviderDetail.value = null
  }
}

// 监听 Provider 选择变化
watch(() => form.value.llmProvider, () => {
  updateSelectedProviderDetail()
  // 重新加载健康状态
  loadProviderHealth()
})

// 格式化成本显示
function formatCost(inputCost: number, outputCost: number): string {
  if (inputCost === 0 && outputCost === 0) {
    return '免费'
  }
  const input = inputCost > 0 ? `${(inputCost / 10000).toFixed(2)}元/百万输入Token` : ''
  const output = outputCost > 0 ? `${(outputCost / 10000).toFixed(2)}元/百万输出Token` : ''
  return [input, output].filter(Boolean).join('，') || '免费'
}

// 格式化能力显示
function formatCapabilities(capabilities: string[] | undefined): string {
  if (!capabilities || capabilities.length === 0) return '无'
  const capabilityNames: Record<string, string> = {
    CHAT: '对话',
    EMBEDDING: '向量嵌入',
    STRUCTURED_OUTPUT: '结构化输出',
    FUNCTION_CALLING: '函数调用',
    STREAMING: '流式输出',
    VISION: '视觉理解',
    TTS: '文字转语音',
    STT: '语音转文字',
  }
  return capabilities.map(c => capabilityNames[c] || c).join('、')
}

onMounted(async () => {
  // 并行加载设置、Provider 列表和健康状态
  await Promise.all([loadSettings(), loadProviders(), loadProviderHealth()])
  if (settings.value) {
    form.value = { ...settings.value }
    updateSelectedProviderDetail()
  }
})

async function handleSave() {
  saving.value = true
  saveError.value = null
  saveSuccess.value = false
  const snapshot = { ...form.value }
  try {
    await saveSettings(form.value)
    saveSuccess.value = true
    setTimeout(() => { saveSuccess.value = false }, 2000)
  } catch (e: unknown) {
    saveError.value = e instanceof Error ? e.message : '保存失败'
    form.value = snapshot
  } finally {
    saving.value = false
  }
}

const themeOptions = [
  { value: 'light', label: '亮色' },
  { value: 'dark', label: '暗色' },
  { value: 'system', label: '跟随系统' },
] as const
</script>

<template>
  <div class="flex flex-col h-full p-6 max-w-2xl overflow-y-auto">
    <h2 class="text-xl font-semibold text-foreground mb-6">设置</h2>

    <!-- 加载中 -->
    <div v-if="loading" class="text-sm text-muted-foreground">加载中...</div>

    <!-- 加载失败 -->
    <div v-else-if="error" class="text-sm text-destructive">{{ error }}</div>

    <!-- 设置表单 -->
    <form v-else class="space-y-6" @submit.prevent="handleSave">
      <!-- 主题 -->
      <fieldset class="space-y-2">
        <legend class="text-sm font-medium">主题</legend>
        <div class="flex gap-3">
          <label
            v-for="opt in themeOptions"
            :key="opt.value"
            class="flex items-center gap-2 cursor-pointer"
          >
            <input
              v-model="form.theme"
              type="radio"
              name="theme"
              :value="opt.value"
              class="accent-primary"
            />
            <span class="text-sm">{{ opt.label }}</span>
          </label>
        </div>
      </fieldset>

      <!-- LLM Provider -->
      <div class="space-y-2">
        <label for="llm-provider" class="text-sm font-medium">LLM Provider</label>
        <div class="flex items-center gap-2">
          <select
            id="llm-provider"
            v-model="form.llmProvider"
            :disabled="loadingProviders"
            class="flex h-9 flex-1 rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:opacity-50"
          >
            <option value="" disabled>请选择 Provider</option>
            <option
              v-for="provider in providers"
              :key="provider.id"
              :value="provider.id"
            >
              {{ provider.displayName }}
            </option>
          </select>
          <!-- 健康状态指示器 -->
          <div
            v-if="form.llmProvider && providerHealthStatus[form.llmProvider] !== undefined"
            class="flex items-center gap-1"
            :title="providerHealthStatus[form.llmProvider] ? '健康' : '不健康'"
          >
            <span
              class="w-2 h-2 rounded-full"
              :class="providerHealthStatus[form.llmProvider] ? 'bg-green-500' : 'bg-red-500'"
            ></span>
            <span class="text-xs text-muted-foreground">
              {{ providerHealthStatus[form.llmProvider] ? '健康' : '异常' }}
            </span>
          </div>
        </div>
        <span v-if="loadingProviders" class="text-xs text-muted-foreground">加载中...</span>
      </div>

      <!-- Provider 详细信息卡片 -->
      <div
        v-if="selectedProviderDetail"
        class="rounded-lg border border-border bg-muted/50 p-4 space-y-3"
      >
        <h3 class="text-sm font-medium">Provider 详细信息</h3>
        <div class="grid grid-cols-2 gap-3 text-sm">
          <div>
            <span class="text-muted-foreground">类型：</span>
            <span>{{ selectedProviderDetail.type }}</span>
          </div>
          <div>
            <span class="text-muted-foreground">模型：</span>
            <span>{{ selectedProviderDetail.modelName }}</span>
          </div>
          <div>
            <span class="text-muted-foreground">优先级：</span>
            <span>{{ selectedProviderDetail.priority ?? 'N/A' }}</span>
          </div>
          <div>
            <span class="text-muted-foreground">成本：</span>
            <span>
              {{
                formatCost(
                  selectedProviderDetail.costPerInputToken ?? 0,
                  selectedProviderDetail.costPerOutputToken ?? 0
                )
              }}
            </span>
          </div>
          <div class="col-span-2">
            <span class="text-muted-foreground">支持的能力：</span>
            <span>{{ formatCapabilities(selectedProviderDetail.capabilities) }}</span>
          </div>
          <div
            v-if="selectedProviderDetail.scenes && selectedProviderDetail.scenes.length > 0"
            class="col-span-2"
          >
            <span class="text-muted-foreground">支持场景：</span>
            <span>{{ selectedProviderDetail.scenes.join('、') }}</span>
          </div>
          <div v-if="selectedProviderDetail.maxContextWindow" class="col-span-2">
            <span class="text-muted-foreground">最大上下文窗口：</span>
            <span>{{ selectedProviderDetail.maxContextWindow.toLocaleString() }} Tokens</span>
          </div>
        </div>
      </div>

      <!-- 功能开关 -->
      <fieldset class="space-y-3">
        <legend class="text-sm font-medium">功能开关</legend>
        <div class="space-y-2">
          <!-- 流式响应 -->
          <label class="flex items-center justify-between cursor-pointer">
            <div class="flex flex-col">
              <span class="text-sm">流式响应</span>
              <span
                v-if="isFeatureDisabled.enableStreaming"
                class="text-xs text-muted-foreground"
              >
                当前 Provider 不支持此功能
              </span>
            </div>
            <input
              v-model="form.enableStreaming"
              type="checkbox"
              :disabled="isFeatureDisabled.enableStreaming"
              class="h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary disabled:opacity-50"
            />
          </label>

          <!-- 函数调用 -->
          <label class="flex items-center justify-between cursor-pointer">
            <div class="flex flex-col">
              <span class="text-sm">启用函数调用</span>
              <span
                v-if="isFeatureDisabled.enableFunctionCall"
                class="text-xs text-muted-foreground"
              >
                当前 Provider 不支持此功能
              </span>
            </div>
            <input
              v-model="form.enableFunctionCall"
              type="checkbox"
              :disabled="isFeatureDisabled.enableFunctionCall"
              class="h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary disabled:opacity-50"
            />
          </label>

          <!-- 知识库检索 -->
          <label class="flex items-center justify-between cursor-pointer">
            <div class="flex flex-col">
              <span class="text-sm">启用知识库检索</span>
              <span
                v-if="isFeatureDisabled.enableKnowledgeBase"
                class="text-xs text-muted-foreground"
              >
                当前 Provider 不支持此功能
              </span>
            </div>
            <input
              v-model="form.enableKnowledgeBase"
              type="checkbox"
              :disabled="isFeatureDisabled.enableKnowledgeBase"
              class="h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary disabled:opacity-50"
            />
          </label>

          <!-- 工具调用 -->
          <label class="flex items-center justify-between cursor-pointer">
            <div class="flex flex-col">
              <span class="text-sm">启用工具调用</span>
              <span
                v-if="isFeatureDisabled.enableToolCall"
                class="text-xs text-muted-foreground"
              >
                当前 Provider 不支持此功能
              </span>
            </div>
            <input
              v-model="form.enableToolCall"
              type="checkbox"
              :disabled="isFeatureDisabled.enableToolCall"
              class="h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary disabled:opacity-50"
            />
          </label>
        </div>
      </fieldset>

      <!-- 保存按钮 -->
      <div class="flex items-center gap-3">
        <button
          type="submit"
          :disabled="saving"
          class="inline-flex items-center justify-center rounded-md text-sm font-medium h-9 px-4 py-2 bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
        >
          {{ saving ? '保存中...' : '保存' }}
        </button>

        <span v-if="saveSuccess" class="text-sm text-green-600">已保存</span>
        <span v-if="saveError" class="text-sm text-destructive">{{ saveError }}</span>
      </div>
    </form>
  </div>
</template>
