<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useSettings } from '@/composables/useSettings'
import { llmProviderApi, settingsApi } from '@/api/client'
import type { LlmProvider } from '@/api/client'
import SettingSection from '@/components/settings/SettingSection.vue'
import SettingItem from '@/components/settings/SettingItem.vue'
import SettingSelect from '@/components/settings/SettingSelect.vue'
import LlmProviderManager from '@/components/settings/LlmProviderManager.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { ChevronDown, CheckCircle2, XCircle, Loader2, RefreshCw, Trash2 } from 'lucide-vue-next'

const { settings, loading, error, loadSettings, saveSettings } = useSettings()

const providers = ref<LlmProvider[]>([])
const loadingProviders = ref(false)
const defaultProvider = ref('')
const showProviderManager = ref(false)
const expandedProviders = ref<Set<string>>(new Set())
const showDeleteConfirm = ref(false)
const deletingProviderId = ref<string | null>(null)

// 健康状态管理
const healthStatus = ref<Record<string, boolean>>({})
const checkingHealth = ref<Set<string>>(new Set())

onMounted(async () => {
  await loadSettings()
  if (settings.value) {
    defaultProvider.value = settings.value.llmProvider || ''
  }
  await loadProviders()
})

async function loadProviders() {
  loadingProviders.value = true
  try {
    providers.value = await llmProviderApi.listEnabledProviders()
  } catch (err) {
    console.error('加载 Provider 列表失败:', err)
    try {
      providers.value = await settingsApi.getProviders()
    } catch (e) {
      console.error('降级加载 Provider 列表也失败:', e)
    }
  } finally {
    loadingProviders.value = false
  }
}


// 检查单个 Provider 的健康状态
async function checkProviderHealth(providerId: string) {
  if (checkingHealth.value.has(providerId)) return
  
  checkingHealth.value.add(providerId)
  try {
    // 添加超时控制（10秒）
    const timeoutPromise = new Promise<never>((_, reject) => {
      setTimeout(() => reject(new Error('健康检查超时')), 10000)
    })
    
    const result = await Promise.race([
      llmProviderApi.getProviderHealth(providerId),
      timeoutPromise
    ])
    
    healthStatus.value[providerId] = result.healthy
    
    // 更新 providers 中的 healthy 字段
    const index = providers.value.findIndex(p => p.id === providerId)
    if (index !== -1) {
      providers.value[index] = {
        ...providers.value[index],
        healthy: result.healthy
      }
    }
  } catch (err) {
    console.error(`检查 Provider ${providerId} 健康状态失败:`, err)
    healthStatus.value[providerId] = false
    // 更新 providers 中的 healthy 字段
    const index = providers.value.findIndex(p => p.id === providerId)
    if (index !== -1) {
      providers.value[index] = {
        ...providers.value[index],
        healthy: false
      }
    }
  } finally {
    checkingHealth.value.delete(providerId)
  }
}


// 获取 Provider 的健康状态
function getProviderHealth(providerId: string): 'healthy' | 'unhealthy' | 'checking' | 'unknown' {
  if (checkingHealth.value.has(providerId)) return 'checking'
  const healthy = healthStatus.value[providerId]
  if (healthy === undefined) return 'unknown'
  return healthy ? 'healthy' : 'unhealthy'
}

async function handleProviderManagerClose() {
  showProviderManager.value = false
  // 重新加载模型列表
  await loadProviders()
}

function toggleProviderExpanded(providerId: string) {
  if (expandedProviders.value.has(providerId)) {
    expandedProviders.value.delete(providerId)
  } else {
    expandedProviders.value.add(providerId)
  }
}

async function handleSave() {
  if (!defaultProvider.value) return
  try {
    await saveSettings({ ...settings.value, llmProvider: defaultProvider.value } as any)
  } catch (e) {
    console.error('保存失败:', e)
  }
}

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

function formatCost(inputCost: number, outputCost: number): string {
  if (inputCost === 0 && outputCost === 0) {
    return '免费'
  }
  const input = inputCost > 0 ? `${(inputCost / 10000).toFixed(2)}元/百万输入Token` : ''
  const output = outputCost > 0 ? `${(outputCost / 10000).toFixed(2)}元/百万输出Token` : ''
  return [input, output].filter(Boolean).join('，') || '免费'
}

function formatScenes(scenes: string[] | undefined): string {
  if (!scenes || scenes.length === 0) return '无'
  const sceneNames: Record<string, string> = {
    intent_understanding: '意图理解',
    task_planning: '任务规划',
    knowledge_extraction: '知识提取',
    chat: '通用对话',
    memory_compression: '记忆压缩',
    proactive_reasoning: '主动推理',
    code_generation: '代码生成',
    embedding: '向量嵌入',
    document_summary: '文档摘要',
    'agent-reasoning': 'Agent 推理',
    'agent-tool-calling': 'Agent 工具调用',
    'agent-generation': 'Agent 响应生成',
    'skill-generation': 'Skill 生成',
  }
  return scenes.map(s => sceneNames[s] || s).join('、')
}

// 删除 Provider
function confirmDelete(provider: LlmProvider) {
  deletingProviderId.value = provider.id
  showDeleteConfirm.value = true
}

async function handleDelete() {
  if (!deletingProviderId.value) return
  
  loadingProviders.value = true
  try {
    await llmProviderApi.deleteProvider(deletingProviderId.value)
    await loadProviders()
    // 如果删除的是默认模型，清空默认模型设置
    if (deletingProviderId.value === defaultProvider.value) {
      defaultProvider.value = ''
      if (settings.value) {
        await saveSettings({ ...settings.value, llmProvider: '' } as any)
      }
    }
    showDeleteConfirm.value = false
    deletingProviderId.value = null
  } catch (err: any) {
    console.error('删除 Provider 失败:', err)
    alert(err.message || '删除失败')
  } finally {
    loadingProviders.value = false
  }
}

// 删除确认消息
const deleteConfirmMessage = computed(() => {
  if (!deletingProviderId.value) return ''
  const provider = providers.value.find(p => p.id === deletingProviderId.value)
  const name = provider?.displayName || provider?.id || deletingProviderId.value
  return `确定要删除 Provider "${name}" 吗？此操作不可恢复。`
})
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg space-y-6 max-w-4xl">
        <!-- 加载中 -->
        <div v-if="loading || loadingProviders" class="flex items-center justify-center py-12">
          <div class="text-sm text-muted-foreground">加载中...</div>
        </div>

        <!-- 加载失败 -->
        <div v-else-if="error" class="rounded-lg border border-destructive bg-destructive/10 p-4">
          <div class="text-sm text-destructive">{{ error }}</div>
        </div>

        <!-- 设置表单 -->
        <div v-else class="space-y-6">
        <!-- 全局默认模型设置 -->
        <SettingSection title="全局默认模型" icon="🤖" description="设置全局默认使用的模型和参数上限">
          <SettingItem label="默认模型" description="选择全局默认使用的 LLM Provider" required>
            <div class="flex items-center gap-2">
              <div class="flex-1">
                <SettingSelect
                  v-model="defaultProvider"
                  :options="providers.map(p => ({ value: p.id, label: p.displayName || p.id }))"
                  :disabled="loadingProviders"
                  placeholder="请选择默认模型"
                  @change="handleSave"
                />
              </div>
            </div>
            <span v-if="loadingProviders" class="text-xs text-muted-foreground mt-1">加载中...</span>
          </SettingItem>
        </SettingSection>

        <!-- 系统级可用模型列表 -->
        <SettingSection title="可用模型列表" icon="📋" description="查看当前系统中所有可用的模型">
          <template #header-actions>
            <button
              class="px-4 py-2 text-sm font-medium bg-primary text-primary-foreground rounded-lg 
                     transition-all duration-200
                     hover:bg-primary/90 hover:shadow-md
                     active:scale-[0.98]
                     focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              @click="showProviderManager = true"
            >
              配置模型
            </button>
          </template>
          <div v-if="providers.length === 0" class="text-sm text-muted-foreground py-4">
            暂无可用模型，请先配置 LLM Provider
          </div>
          <div v-else class="space-y-2">
            <div
              v-for="provider in providers"
              :key="provider.id"
              class="rounded-lg border border-border bg-muted/30"
            >
              <!-- 模型名称行（可点击展开） -->
              <div
                class="flex items-center justify-between p-4 cursor-pointer 
                       transition-all duration-200
                       hover:bg-muted/50 hover:shadow-sm
                       active:scale-[0.99]"
                @click="toggleProviderExpanded(provider.id)"
              >
                <div class="flex items-center gap-3 flex-1">
                  <button class="text-muted-foreground hover:text-foreground transition-colors">
                    <ChevronDown
                      :size="18"
                      :class="[
                        'transition-transform duration-200 ease-out',
                        expandedProviders.has(provider.id) && 'rotate-180'
                      ]"
                    />
                  </button>
                  <div class="flex-1">
                    <div class="flex items-center gap-2">
                      <h4 class="font-medium text-foreground">{{ provider.displayName || provider.id }}</h4>
                      <span
                        v-if="provider.id === defaultProvider"
                        class="px-2 py-0.5 rounded-sm text-xs font-medium bg-primary/10 text-primary"
                      >
                        默认
                      </span>
                      <!-- 健康状态查询按钮 -->
                      <button
                        class="px-2 py-1 text-xs font-medium rounded-lg border border-border bg-card text-foreground
                               transition-all duration-200
                               hover:bg-accent hover:text-accent-foreground hover:shadow-sm
                               active:scale-[0.98]
                               focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2
                               disabled:opacity-50 disabled:cursor-not-allowed"
                        :disabled="checkingHealth.has(provider.id)"
                        @click.stop="checkProviderHealth(provider.id)"
                        title="查询健康状态"
                      >
                        <div class="flex items-center gap-1">
                          <Loader2 v-if="getProviderHealth(provider.id) === 'checking'" :size="12" class="animate-spin" />
                          <RefreshCw v-else :size="12" />
                          <span>{{ getProviderHealth(provider.id) === 'checking' ? '检查中' : '查询状态' }}</span>
                        </div>
                      </button>
                      <!-- 健康状态指示器 -->
                      <div v-if="getProviderHealth(provider.id) !== 'unknown' && getProviderHealth(provider.id) !== 'checking'" class="flex items-center">
                        <CheckCircle2
                          v-if="getProviderHealth(provider.id) === 'healthy'"
                          :size="14"
                          class="text-green-600"
                          title="健康"
                        />
                        <XCircle
                          v-else-if="getProviderHealth(provider.id) === 'unhealthy'"
                          :size="14"
                          class="text-destructive"
                          title="不健康"
                        />
                      </div>
                    </div>
                    <p v-if="provider.description" class="text-sm text-muted-foreground mt-0.5">
                      {{ provider.description }}
                    </p>
                  </div>
                </div>
              </div>
              
              <!-- 详细信息（展开时显示） -->
              <Transition
                enter-active-class="transition-all duration-300 ease-out"
                enter-from-class="opacity-0 max-h-0 overflow-hidden"
                enter-to-class="opacity-100 max-h-[500px]"
                leave-active-class="transition-all duration-200 ease-in"
                leave-from-class="opacity-100 max-h-[500px]"
                leave-to-class="opacity-0 max-h-0 overflow-hidden"
              >
                <div
                  v-if="expandedProviders.has(provider.id)"
                  class="px-4 md:px-6 pb-4 pt-4 border-t border-border"
                >
                  <div class="grid grid-cols-1 md:grid-cols-2 gap-3 text-sm">
                  <div>
                    <span class="text-muted-foreground">类型：</span>
                    <span class="ml-1">{{ provider.type }}</span>
                  </div>
                  <div>
                    <span class="text-muted-foreground">模型：</span>
                    <span class="ml-1">{{ provider.modelName }}</span>
                  </div>
                  <div>
                    <span class="text-muted-foreground">优先级：</span>
                    <span class="ml-1">{{ provider.priority ?? 'N/A' }}</span>
                  </div>
                  <div>
                    <span class="text-muted-foreground">成本：</span>
                    <span class="ml-1">
                      {{
                        formatCost(
                          provider.costPerInputToken ?? 0,
                          provider.costPerOutputToken ?? 0
                        )
                      }}
                    </span>
                  </div>
                  <div>
                    <span class="text-muted-foreground">健康状态：</span>
                    <span class="ml-1 flex items-center gap-1">
                      <span
                        v-if="getProviderHealth(provider.id) === 'checking'"
                        class="flex items-center gap-1 text-muted-foreground"
                      >
                        <Loader2 :size="12" class="animate-spin" />
                        <span>检查中...</span>
                      </span>
                      <span
                        v-else-if="getProviderHealth(provider.id) === 'healthy'"
                        class="flex items-center gap-1 text-green-600"
                      >
                        <CheckCircle2 :size="12" />
                        <span>健康</span>
                      </span>
                      <span
                        v-else-if="getProviderHealth(provider.id) === 'unhealthy'"
                        class="flex items-center gap-1 text-destructive"
                      >
                        <XCircle :size="12" />
                        <span>不健康</span>
                      </span>
                      <span
                        v-else
                        class="flex items-center gap-1 text-muted-foreground"
                      >
                        <span>未查询</span>
                      </span>
                    </span>
                  </div>
                  <div>
                    <span class="text-muted-foreground">API 地址：</span>
                    <span class="ml-1 font-mono text-xs">{{ provider.apiUrl || 'N/A' }}</span>
                  </div>
                  <div class="col-span-2">
                    <span class="text-muted-foreground">支持的能力：</span>
                    <span class="ml-1">{{ formatCapabilities(provider.capabilities) }}</span>
                  </div>
                  <div
                    v-if="provider.scenes && provider.scenes.length > 0"
                    class="col-span-2"
                  >
                    <span class="text-muted-foreground">支持场景：</span>
                    <span class="ml-1">{{ formatScenes(provider.scenes) }}</span>
                  </div>
                  <div v-if="provider.maxContextWindow" class="col-span-2">
                    <span class="text-muted-foreground">最大上下文窗口：</span>
                    <span class="ml-1">{{ provider.maxContextWindow.toLocaleString() }} Tokens</span>
                  </div>
                  <!-- 删除按钮 -->
                  <div class="col-span-2 pt-2 border-t border-border">
                    <button
                      class="px-3 py-1.5 text-sm font-medium text-destructive border border-destructive/20 rounded-lg 
                             transition-all duration-200
                             hover:bg-destructive/10 hover:border-destructive/40 hover:shadow-sm
                             active:scale-[0.98]
                             focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-destructive focus-visible:ring-offset-2
                             disabled:opacity-50 disabled:cursor-not-allowed"
                      :disabled="loadingProviders"
                      @click.stop="confirmDelete(provider)"
                    >
                      <div class="flex items-center gap-2">
                        <Trash2 :size="14" />
                        <span>删除此 Provider</span>
                      </div>
                    </button>
                  </div>
                </div>
                </div>
              </Transition>
            </div>
          </div>
        </SettingSection>
        </div>
      </div>
    </div>

    <!-- LLM Provider 管理对话框 -->
    <LlmProviderManager v-if="showProviderManager" @close="handleProviderManagerClose" />

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
