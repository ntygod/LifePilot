<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useSettings } from '@/composables/useSettings'
import { settingsApi, llmProviderApi, type LlmProvider } from '@/api/client'
import type { ProviderCapability } from '@/types'
import SettingSection from '@/components/settings/SettingSection.vue'
import SettingItem from '@/components/settings/SettingItem.vue'
import SettingSwitch from '@/components/settings/SettingSwitch.vue'
import SettingSelect from '@/components/settings/SettingSelect.vue'
import SettingInput from '@/components/settings/SettingInput.vue'
import SettingSlider from '@/components/settings/SettingSlider.vue'
import LlmProviderManager from '@/components/settings/LlmProviderManager.vue'

const { settings, loading, error, loadSettings, saveSettings } = useSettings()

const form = ref({
  theme: 'system' as 'light' | 'dark' | 'system',
  language: 'zh-CN',
  llmProvider: '',
  enableStreaming: true,
  enableFunctionCall: true,
  enableKnowledgeBase: true,
  enableToolCall: true,
  // 会话设置（仅显示，待后端支持）
  sessionTimeout: 30,
  maxRecentTurns: 10,
  // 高级设置（仅显示，待后端支持）
  workingMemoryBudget: 8000,
  compressionThreshold: 4000,
  maxRetentionDays: 180,
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
const showProviderManager = ref(false)

// Provider 配置表单
const providerConfig = ref({
  apiUrl: '',
  apiKey: '',
  modelName: '',
  timeoutSeconds: 30,
})

// 系统信息
const systemInfo = ref({
  version: '1.0.0',
  buildTime: new Date().toLocaleDateString(),
  javaVersion: '22',
  uptime: '0 天 0 小时',
})

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
    // 从新的 API 加载已启用的 Provider
    providers.value = await llmProviderApi.listEnabledProviders()
    // 如果已选择 Provider，更新详细信息
    if (form.value.llmProvider) {
      updateSelectedProviderDetail()
    }
  } catch (err) {
    console.error('加载 Provider 列表失败:', err)
    // 降级到旧 API
    try {
      providers.value = await settingsApi.getProviders()
    } catch (e) {
      console.error('降级加载 Provider 列表也失败:', e)
    }
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
async function updateSelectedProviderDetail() {
  if (!form.value.llmProvider) {
    selectedProviderDetail.value = null
    providerConfig.value = {
      apiUrl: '',
      apiKey: '',
      modelName: '',
      timeoutSeconds: 30,
    }
    return
  }
  const provider = providers.value.find(p => p.id === form.value.llmProvider)
  if (provider) {
    selectedProviderDetail.value = provider
    // 尝试获取更详细的 Provider 信息（包含 apiUrl）
    try {
      const detail = await llmProviderApi.getProvider(form.value.llmProvider)
      // 更新配置表单
      providerConfig.value = {
        apiUrl: detail.apiUrl || provider.apiUrl || '',
        apiKey: '', // API Key 不返回，需要用户填写
        modelName: detail.modelName || provider.modelName || '',
        timeoutSeconds: detail.timeoutSeconds || provider.timeoutSeconds || 30,
      }
      // 更新 selectedProviderDetail 以包含详细信息
      selectedProviderDetail.value = { ...provider, ...detail }
    } catch (err) {
      console.warn('获取 Provider 详细信息失败，使用基础信息:', err)
      // 使用基础信息
      providerConfig.value = {
        apiUrl: provider.apiUrl || '',
        apiKey: '', // API Key 不返回，需要用户填写
        modelName: provider.modelName || '',
        timeoutSeconds: provider.timeoutSeconds || 30,
      }
    }
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
    providerConfig.value = {
      apiUrl: '',
      apiKey: '',
      modelName: '',
      timeoutSeconds: 30,
    }
  }
}

// 监听 Provider 选择变化
watch(() => form.value.llmProvider, () => {
  updateSelectedProviderDetail()
  // 重新加载健康状态（异步，不阻塞）
  loadProviderHealth().catch(err => {
    console.error('加载健康状态失败:', err)
  })
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
  // 先并行加载设置和 Provider 列表（不阻塞下拉列表显示）
  await Promise.all([loadSettings(), loadProviders()])
  if (settings.value) {
    form.value = {
      theme: settings.value.theme ?? form.value.theme,
      language: settings.value.language ?? form.value.language,
      llmProvider: settings.value.llmProvider ?? form.value.llmProvider,
      enableStreaming: settings.value.enableStreaming ?? true,
      enableFunctionCall: settings.value.enableFunctionCall ?? true,
      enableKnowledgeBase: settings.value.enableKnowledgeBase ?? true,
      enableToolCall: settings.value.enableToolCall ?? true,
      // 会话设置（仅显示，待后端支持）
      sessionTimeout: settings.value.sessionTimeout ?? form.value.sessionTimeout,
      maxRecentTurns: settings.value.maxRecentTurns ?? form.value.maxRecentTurns,
      // 高级设置（仅显示，待后端支持）
      workingMemoryBudget: settings.value.workingMemoryBudget ?? form.value.workingMemoryBudget,
      compressionThreshold: settings.value.compressionThreshold ?? form.value.compressionThreshold,
      maxRetentionDays: settings.value.maxRetentionDays ?? form.value.maxRetentionDays,
    }
    updateSelectedProviderDetail()
  }
  // 健康检查在后台异步执行，不阻塞下拉列表的显示
  loadProviderHealth().catch(err => {
    console.error('后台加载健康状态失败:', err)
  })
})

async function handleSave() {
  // 验证必填字段
  if (!form.value.theme || !form.value.theme.trim()) {
    saveError.value = '主题不能为空'
    return
  }
  if (!form.value.language || !form.value.language.trim()) {
    saveError.value = '语言不能为空'
    return
  }
  if (!form.value.llmProvider || !form.value.llmProvider.trim()) {
    saveError.value = 'LLM Provider 不能为空，请先选择一个 Provider'
    return
  }

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

const languageOptions = [
  { value: 'zh-CN', label: '简体中文' },
  { value: 'zh-TW', label: '繁体中文' },
  { value: 'en-US', label: 'English' },
  { value: 'ja-JP', label: '日本語' },
] as const
</script>

<template>
  <div class="flex flex-col h-full overflow-y-auto">
    <div class="sticky top-0 z-10 border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div class="container mx-auto px-6 py-4">
        <h2 class="text-2xl font-semibold text-foreground">设置</h2>
        <p class="text-sm text-muted-foreground mt-1">管理您的应用偏好和系统配置</p>
      </div>
    </div>

    <div class="container mx-auto px-6 py-6 space-y-6 max-w-4xl">
      <!-- 加载中 -->
      <div v-if="loading" class="flex items-center justify-center py-12">
        <div class="text-sm text-muted-foreground">加载中...</div>
      </div>

      <!-- 加载失败 -->
      <div v-else-if="error" class="rounded-lg border border-destructive bg-destructive/10 p-4">
        <div class="text-sm text-destructive">{{ error }}</div>
      </div>

      <!-- 设置表单 -->
      <form v-else @submit.prevent="handleSave" class="space-y-6">
        <!-- 外观设置 -->
        <SettingSection title="外观设置" icon="🎨" description="自定义界面主题和语言偏好">
          <SettingItem label="主题" description="选择您偏好的界面主题">
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
          </SettingItem>

          <SettingItem label="语言" description="选择界面显示语言">
            <SettingSelect
              v-model="form.language"
              :options="languageOptions"
              placeholder="请选择语言"
            />
          </SettingItem>
        </SettingSection>

        <!-- LLM 设置 -->
        <SettingSection title="LLM 设置" icon="🤖" description="配置大语言模型提供商和功能">
          <SettingItem label="LLM Provider" description="选择用于对话的 LLM 提供商" required>
            <div class="flex items-center gap-2 mb-2">
              <button
                type="button"
                class="px-3 py-1.5 text-sm border border-border rounded-md hover:bg-accent transition-colors"
                @click="showProviderManager = true"
              >
                管理 Provider
              </button>
            </div>
            <div class="flex items-center gap-2">
              <div class="flex-1">
                <SettingSelect
                  v-model="form.llmProvider"
                  :options="providers.map(p => ({ value: p.id, label: p.displayName || p.id }))"
                  :disabled="loadingProviders"
                  placeholder="请选择 Provider"
                />
              </div>
              <!-- 健康状态指示器 -->
              <div
                v-if="form.llmProvider && providerHealthStatus[form.llmProvider] !== undefined"
                class="flex items-center gap-1.5 px-2 py-1 rounded-md"
                :class="providerHealthStatus[form.llmProvider] ? 'bg-green-500/10' : 'bg-red-500/10'"
                :title="providerHealthStatus[form.llmProvider] ? '健康' : '不健康'"
              >
                <span
                  class="w-2 h-2 rounded-full"
                  :class="providerHealthStatus[form.llmProvider] ? 'bg-green-500' : 'bg-red-500'"
                ></span>
                <span class="text-xs"
                  :class="providerHealthStatus[form.llmProvider] ? 'text-green-700 dark:text-green-400' : 'text-red-700 dark:text-red-400'"
                >
                  {{ providerHealthStatus[form.llmProvider] ? '健康' : '异常' }}
                </span>
              </div>
            </div>
            <span v-if="loadingProviders" class="text-xs text-muted-foreground mt-1">加载中...</span>
          </SettingItem>

          <!-- Provider 配置表单 -->
          <div
            v-if="selectedProviderDetail"
            class="rounded-lg border border-border bg-muted/30 p-4 space-y-4"
          >
            <h4 class="text-sm font-medium">Provider 配置</h4>
            <div class="space-y-4">
              <!-- API URL -->
              <SettingItem
                label="API URL"
                description="LLM 服务的 API 端点地址"
              >
                <SettingInput
                  v-model="providerConfig.apiUrl"
                  type="url"
                  placeholder="例如: https://api.deepseek.com/v1"
                />
              </SettingItem>

              <!-- API Key -->
              <SettingItem
                label="API Key"
                description="LLM 服务的 API 密钥（敏感信息，请妥善保管）"
              >
                <SettingInput
                  v-model="providerConfig.apiKey"
                  type="password"
                  placeholder="请输入 API Key"
                />
              </SettingItem>

              <!-- Model Name -->
              <SettingItem
                label="模型名称"
                description="要使用的模型名称"
              >
                <SettingInput
                  v-model="providerConfig.modelName"
                  placeholder="例如: deepseek-chat"
                />
              </SettingItem>

              <!-- Timeout -->
              <SettingItem
                label="超时时间"
                description="请求超时时间（秒）"
              >
                <div class="flex items-center gap-3">
                  <div class="flex-1">
                    <SettingSlider
                      v-model="providerConfig.timeoutSeconds"
                      :min="10"
                      :max="300"
                      :step="10"
                    />
                  </div>
                  <span class="text-sm text-muted-foreground min-w-[4rem]">{{ providerConfig.timeoutSeconds }} 秒</span>
                </div>
              </SettingItem>
            </div>
          </div>

          <!-- Provider 详细信息卡片 -->
          <div
            v-if="selectedProviderDetail"
            class="rounded-lg border border-border bg-muted/30 p-4 space-y-3"
          >
            <h4 class="text-sm font-medium">Provider 详细信息</h4>
            <div class="grid grid-cols-2 gap-3 text-sm">
              <div>
                <span class="text-muted-foreground">类型：</span>
                <span class="ml-1">{{ selectedProviderDetail.type }}</span>
              </div>
              <div>
                <span class="text-muted-foreground">模型：</span>
                <span class="ml-1">{{ selectedProviderDetail.modelName }}</span>
              </div>
              <div>
                <span class="text-muted-foreground">优先级：</span>
                <span class="ml-1">{{ selectedProviderDetail.priority ?? 'N/A' }}</span>
              </div>
              <div>
                <span class="text-muted-foreground">成本：</span>
                <span class="ml-1">
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
                <span class="ml-1">{{ formatCapabilities(selectedProviderDetail.capabilities) }}</span>
              </div>
              <div
                v-if="selectedProviderDetail.scenes && selectedProviderDetail.scenes.length > 0"
                class="col-span-2"
              >
                <span class="text-muted-foreground">支持场景：</span>
                <span class="ml-1">{{ selectedProviderDetail.scenes.join('、') }}</span>
              </div>
              <div v-if="selectedProviderDetail.maxContextWindow" class="col-span-2">
                <span class="text-muted-foreground">最大上下文窗口：</span>
                <span class="ml-1">{{ selectedProviderDetail.maxContextWindow.toLocaleString() }} Tokens</span>
              </div>
            </div>
          </div>

          <!-- 功能开关 -->
          <SettingItem label="功能开关" description="启用或禁用特定功能">
            <div class="space-y-3">
              <!-- 流式响应 -->
              <div class="flex items-center justify-between">
                <div class="flex flex-col">
                  <span class="text-sm font-medium">流式响应</span>
                  <span
                    v-if="isFeatureDisabled.enableStreaming"
                    class="text-xs text-muted-foreground mt-0.5"
                  >
                    当前 Provider 不支持此功能
                  </span>
                  <span v-else class="text-xs text-muted-foreground mt-0.5">
                    实时流式输出响应内容
                  </span>
                </div>
                <SettingSwitch
                  v-model="form.enableStreaming"
                  :disabled="isFeatureDisabled.enableStreaming"
                />
              </div>

              <!-- 函数调用 -->
              <div class="flex items-center justify-between">
                <div class="flex flex-col">
                  <span class="text-sm font-medium">启用函数调用</span>
                  <span
                    v-if="isFeatureDisabled.enableFunctionCall"
                    class="text-xs text-muted-foreground mt-0.5"
                  >
                    当前 Provider 不支持此功能
                  </span>
                  <span v-else class="text-xs text-muted-foreground mt-0.5">
                    允许 LLM 调用预定义的函数
                  </span>
                </div>
                <SettingSwitch
                  v-model="form.enableFunctionCall"
                  :disabled="isFeatureDisabled.enableFunctionCall"
                />
              </div>

              <!-- 知识库检索 -->
              <div class="flex items-center justify-between">
                <div class="flex flex-col">
                  <span class="text-sm font-medium">启用知识库检索</span>
                  <span
                    v-if="isFeatureDisabled.enableKnowledgeBase"
                    class="text-xs text-muted-foreground mt-0.5"
                  >
                    当前 Provider 不支持此功能
                  </span>
                  <span v-else class="text-xs text-muted-foreground mt-0.5">
                    从知识库中检索相关信息
                  </span>
                </div>
                <SettingSwitch
                  v-model="form.enableKnowledgeBase"
                  :disabled="isFeatureDisabled.enableKnowledgeBase"
                />
              </div>

              <!-- 工具调用 -->
              <div class="flex items-center justify-between">
                <div class="flex flex-col">
                  <span class="text-sm font-medium">启用工具调用</span>
                  <span
                    v-if="isFeatureDisabled.enableToolCall"
                    class="text-xs text-muted-foreground mt-0.5"
                  >
                    当前 Provider 不支持此功能
                  </span>
                  <span v-else class="text-xs text-muted-foreground mt-0.5">
                    允许 LLM 调用外部工具和技能
                  </span>
                </div>
                <SettingSwitch
                  v-model="form.enableToolCall"
                  :disabled="isFeatureDisabled.enableToolCall"
                />
              </div>
            </div>
          </SettingItem>
        </SettingSection>

        <!-- 会话设置 -->
        <SettingSection title="会话设置" icon="💬" description="配置对话会话的行为和限制">
          <SettingItem
            label="会话超时时间"
            description="会话空闲多少分钟后自动关闭（仅显示，待后端支持）"
          >
            <div class="flex items-center gap-3">
              <div class="flex-1">
                <SettingSlider
                  v-model="form.sessionTimeout"
                  :min="5"
                  :max="120"
                  :step="5"
                  :disabled="true"
                />
              </div>
              <span class="text-sm text-muted-foreground min-w-[4rem]">分钟</span>
            </div>
          </SettingItem>

          <SettingItem
            label="最大最近轮次"
            description="会话中保留的最大对话轮次数（仅显示，待后端支持）"
          >
            <div class="flex items-center gap-3">
              <div class="flex-1">
                <SettingSlider
                  v-model="form.maxRecentTurns"
                  :min="5"
                  :max="50"
                  :step="5"
                  :disabled="true"
                />
              </div>
              <span class="text-sm text-muted-foreground min-w-[4rem]">轮次</span>
            </div>
          </SettingItem>
        </SettingSection>

        <!-- 高级设置 -->
        <SettingSection title="高级设置" icon="⚙️" description="记忆系统和性能优化配置">
          <SettingItem
            label="工作记忆 Token 预算"
            description="工作记忆的最大 Token 数量（仅显示，待后端支持）"
          >
            <div class="flex items-center gap-3">
              <div class="flex-1">
                <SettingSlider
                  v-model="form.workingMemoryBudget"
                  :min="1000"
                  :max="20000"
                  :step="1000"
                  :disabled="true"
                />
              </div>
              <span class="text-sm text-muted-foreground min-w-[4rem]">Tokens</span>
            </div>
          </SettingItem>

          <SettingItem
            label="压缩阈值"
            description="触发记忆压缩的 Token 阈值（仅显示，待后端支持）"
          >
            <div class="flex items-center gap-3">
              <div class="flex-1">
                <SettingSlider
                  v-model="form.compressionThreshold"
                  :min="1000"
                  :max="10000"
                  :step="500"
                  :disabled="true"
                />
              </div>
              <span class="text-sm text-muted-foreground min-w-[4rem]">Tokens</span>
            </div>
          </SettingItem>

          <SettingItem
            label="最大保留天数"
            description="记忆的最大保留天数（仅显示，待后端支持）"
          >
            <div class="flex items-center gap-3">
              <div class="flex-1">
                <SettingSlider
                  v-model="form.maxRetentionDays"
                  :min="30"
                  :max="365"
                  :step="30"
                  :disabled="true"
                />
              </div>
              <span class="text-sm text-muted-foreground min-w-[4rem]">天</span>
            </div>
          </SettingItem>
        </SettingSection>

        <!-- 系统信息 -->
        <SettingSection title="系统信息" icon="ℹ️" description="查看系统版本和运行状态">
          <SettingItem label="版本" description="当前应用版本">
            <div class="text-sm text-muted-foreground">{{ systemInfo.version }}</div>
          </SettingItem>

          <SettingItem label="构建时间" description="应用构建时间">
            <div class="text-sm text-muted-foreground">{{ systemInfo.buildTime }}</div>
          </SettingItem>

          <SettingItem label="Java 版本" description="运行环境 Java 版本">
            <div class="text-sm text-muted-foreground">{{ systemInfo.javaVersion }}</div>
          </SettingItem>

          <SettingItem label="运行时间" description="系统运行时长">
            <div class="text-sm text-muted-foreground">{{ systemInfo.uptime }}</div>
          </SettingItem>
        </SettingSection>

        <!-- 保存按钮 -->
        <div class="sticky bottom-0 bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60 border-t py-4 -mx-6 px-6">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-3">
              <button
                type="submit"
                :disabled="saving"
                class="inline-flex items-center justify-center rounded-md text-sm font-medium h-10 px-6 bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {{ saving ? '保存中...' : '保存设置' }}
              </button>

              <span v-if="saveSuccess" class="text-sm text-green-600 dark:text-green-400 flex items-center gap-1">
                <span class="w-1.5 h-1.5 rounded-full bg-green-600 dark:bg-green-400"></span>
                已保存
              </span>
              <span v-if="saveError" class="text-sm text-destructive">{{ saveError }}</span>
            </div>
            <div class="text-xs text-muted-foreground">
              部分高级设置需要后端 API 支持
            </div>
          </div>
        </div>
      </form>
    </div>

    <!-- LLM Provider 管理对话框 -->
    <LlmProviderManager v-if="showProviderManager" @close="showProviderManager = false" />
  </div>
</template>
