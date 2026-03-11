<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { CheckCircle2, ChevronDown, Cpu, Loader2, RefreshCw, Trash2, XCircle } from 'lucide-vue-next'
import { llmProviderApi, settingsApi } from '@/api/client'
import type { LlmProvider } from '@/api/client'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import LlmProviderManager from '@/components/settings/LlmProviderManager.vue'
import SettingItem from '@/components/settings/SettingItem.vue'
import SettingSection from '@/components/settings/SettingSection.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useSettings } from '@/composables/useSettings'
import { useUiStore } from '@/stores/ui'

const { settings, loading, error, loadSettings, saveSettings } = useSettings()
const uiStore = useUiStore()

const providers = ref<LlmProvider[]>([])
const loadingProviders = ref(false)
const providerLoadError = ref<string | null>(null)
const defaultProvider = ref('')
const showProviderManager = ref(false)
const expandedProviders = ref<Set<string>>(new Set())
const showDeleteConfirm = ref(false)
const deletingProviderId = ref<string | null>(null)
const healthStatus = ref<Record<string, boolean>>({})
const checkingHealth = ref<Set<string>>(new Set())

const healthyCount = computed(() => providers.value.filter(provider => getProviderHealth(provider.id) === 'healthy').length)
const checkedCount = computed(() => providers.value.filter(provider => getProviderHealth(provider.id) !== 'unknown').length)
const defaultProviderLabel = computed(() => {
  const provider = providers.value.find(item => item.id === defaultProvider.value)
  return provider?.displayName || provider?.id || defaultProvider.value || '未设置'
})
const currentStatusItems = computed(() => [
  { label: '提供商总数', value: String(providers.value.length) },
  { label: '已检查', value: String(checkedCount.value) },
  { label: '健康', value: String(healthyCount.value) },
  { label: '默认提供商', value: defaultProviderLabel.value },
])

onMounted(async () => {
  await loadSettings()
  if (settings.value) {
    defaultProvider.value = settings.value.llmProvider || ''
  }
  await loadProviders()
})

async function loadProviders() {
  loadingProviders.value = true
  providerLoadError.value = null
  try {
    providers.value = await llmProviderApi.listEnabledProviders()
  } catch (event) {
    console.error('Failed to load provider list:', event)
    try {
      providers.value = await settingsApi.getProviders()
    } catch (fallbackError) {
      console.error('Fallback provider load also failed:', fallbackError)
      providerLoadError.value = '暂时无法读取模型服务，请稍后重试。'
      providers.value = []
    }
  } finally {
    loadingProviders.value = false
  }
}

async function checkProviderHealth(providerId: string) {
  if (checkingHealth.value.has(providerId)) return

  checkingHealth.value.add(providerId)
  try {
    const timeoutPromise = new Promise<never>((_, reject) => {
      setTimeout(() => reject(new Error('健康检查超时。')), 10000)
    })

    const result = await Promise.race([
      llmProviderApi.getProviderHealth(providerId),
      timeoutPromise,
    ])

    healthStatus.value[providerId] = result.healthy
    const index = providers.value.findIndex(provider => provider.id === providerId)
    if (index !== -1) {
      providers.value[index] = {
        ...providers.value[index],
        healthy: result.healthy,
      }
    }
  } catch (event) {
    console.error(`Failed to check provider health for ${providerId}:`, event)
    healthStatus.value[providerId] = false
    const index = providers.value.findIndex(provider => provider.id === providerId)
    if (index !== -1) {
      providers.value[index] = {
        ...providers.value[index],
        healthy: false,
      }
    }
  } finally {
    checkingHealth.value.delete(providerId)
  }
}

function getProviderHealth(providerId: string): 'healthy' | 'unhealthy' | 'checking' | 'unknown' {
  if (checkingHealth.value.has(providerId)) return 'checking'
  const healthy = healthStatus.value[providerId]
  if (healthy === undefined) return 'unknown'
  return healthy ? 'healthy' : 'unhealthy'
}

function getHealthBadgeVariant(status: string): 'default' | 'secondary' | 'destructive' | 'outline' {
  switch (status) {
    case 'healthy':
      return 'default'
    case 'unhealthy':
      return 'destructive'
    default:
      return 'secondary'
  }
}

function getHealthBadgeText(status: string) {
  switch (status) {
    case 'healthy':
      return '健康'
    case 'unhealthy':
      return '异常'
    case 'checking':
      return '检查中...'
    default:
      return '未检查'
  }
}

async function handleProviderManagerClose() {
  showProviderManager.value = false
  await loadProviders()
}

function toggleProviderExpanded(providerId: string) {
  if (expandedProviders.value.has(providerId)) {
    expandedProviders.value.delete(providerId)
  } else {
    expandedProviders.value.add(providerId)
  }
}

async function handleDefaultProviderChange(value: unknown) {
  const nextValue = String(value ?? '')
  defaultProvider.value = nextValue
  if (!nextValue) return

  try {
    await saveSettings({ ...(settings.value ?? {}), llmProvider: nextValue } as never)
    uiStore.showToast('success', '默认提供商已更新。')
  } catch (event) {
    console.error('Failed to save default provider:', event)
    uiStore.showToast('error', '保存默认提供商失败。')
  }
}

function formatCapabilities(capabilities: string[] | undefined) {
  if (!capabilities || capabilities.length === 0) return '无'

  const capabilityNames: Record<string, string> = {
    CHAT: '对话',
    EMBEDDING: '向量嵌入',
    STRUCTURED_OUTPUT: '结构化输出',
    FUNCTION_CALLING: '函数调用',
    STREAMING: '流式输出',
    VISION: '视觉',
    TTS: '文本转语音',
    STT: '语音转文本',
  }

  return capabilities.map(capability => capabilityNames[capability] || capability).join('、')
}

function formatCost(inputCost: number, outputCost: number) {
  if (inputCost === 0 && outputCost === 0) {
    return '免费'
  }

  const input = inputCost > 0 ? `$${(inputCost / 10000).toFixed(2)} / 每 100 万输入词元` : ''
  const output = outputCost > 0 ? `$${(outputCost / 10000).toFixed(2)} / 每 100 万输出词元` : ''
  return [input, output].filter(Boolean).join(' | ') || '免费'
}

function formatScenes(scenes: string[] | undefined) {
  if (!scenes || scenes.length === 0) return '无'

  const sceneNames: Record<string, string> = {
    intent_understanding: '意图理解',
    task_planning: '任务规划',
    knowledge_extraction: '知识提取',
    chat: '对话',
    memory_compression: '记忆压缩',
    proactive_reasoning: '主动推理',
    code_generation: '代码生成',
    embedding: '向量嵌入',
    document_summary: '文档总结',
    'agent-reasoning': '智能体推理',
    'agent-tool-calling': '智能体工具调用',
    'agent-generation': '智能体生成',
    'skill-generation': '技能生成',
  }

  return scenes.map(scene => sceneNames[scene] || scene).join('、')
}

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

    if (deletingProviderId.value === defaultProvider.value) {
      defaultProvider.value = ''
      if (settings.value) {
        await saveSettings({ ...settings.value, llmProvider: '' } as never)
      }
    }

    showDeleteConfirm.value = false
    deletingProviderId.value = null
    uiStore.showToast('success', '提供商已删除。')
  } catch (event: any) {
    console.error('Failed to delete provider:', event)
    uiStore.showToast('error', event?.message || '删除提供商失败。')
  } finally {
    loadingProviders.value = false
  }
}

const deleteConfirmMessage = computed(() => {
  if (!deletingProviderId.value) return ''
  const provider = providers.value.find(item => item.id === deletingProviderId.value)
  const name = provider?.displayName || provider?.id || deletingProviderId.value
  return `确定删除提供商“${name}”吗？此操作不可恢复。`
})
</script>

<template>
  <div class="space-y-8">
    <template v-if="loading || loadingProviders">
      <div class="space-y-8">
        <Skeleton class="h-10 w-40 rounded-xl" />
        <div class="grid gap-8 xl:grid-cols-[minmax(0,1fr)_280px]">
          <div class="space-y-8">
            <Skeleton class="h-[180px] w-full rounded-xl" />
            <Skeleton class="h-[520px] w-full rounded-xl" />
          </div>
          <Skeleton class="h-[220px] w-full rounded-xl" />
        </div>
      </div>
    </template>

    <template v-else>
      <section class="grid gap-4 border-b border-border/60 pb-5 xl:grid-cols-[minmax(0,1fr)_280px] xl:items-start">
        <div class="space-y-2">
          <h2 class="text-xl font-semibold text-foreground">模型服务</h2>
          <p class="max-w-3xl text-sm leading-6 text-muted-foreground">
            设置默认模型提供商，查看可用状态并管理现有提供商。
          </p>
        </div>

        <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-4">
          <div class="surface-label mb-2 text-[0.68rem]">当前状态</div>
          <div class="text-sm font-medium text-foreground">{{ defaultProviderLabel }}</div>
          <p class="mt-1 text-sm leading-6 text-muted-foreground">
            已配置 {{ providers.length }} 个提供商，其中 {{ healthyCount }} 个健康。
          </p>
        </div>
      </section>

      <div
        v-if="error || providerLoadError"
        class="flex flex-col gap-3 rounded-2xl border border-amber-300/70 bg-amber-50/60 px-4 py-4 text-sm text-amber-950 dark:border-amber-900/70 dark:bg-amber-950/30 dark:text-amber-100 sm:flex-row sm:items-center sm:justify-between"
      >
        <div class="leading-6">
          {{ providerLoadError || '暂时无法读取设置服务，先显示当前设置。' }}
        </div>
        <div class="flex items-center gap-2">
          <Button type="button" variant="outline" @click="loadSettings">
            重试设置
          </Button>
          <Button type="button" variant="outline" @click="loadProviders">
            重试列表
          </Button>
        </div>
      </div>

      <div class="grid gap-8 xl:grid-cols-[minmax(0,1fr)_280px]">
        <div class="space-y-8">
          <SettingSection title="默认提供商" description="未单独指定模型时，会优先使用这里的服务。">
            <SettingItem label="默认模型提供商" description="用于需要默认模型的场景。" required>
              <Select
                :model-value="defaultProvider"
                :disabled="loadingProviders"
                @update:model-value="handleDefaultProviderChange"
              >
                <SelectTrigger class="w-56">
                  <SelectValue placeholder="选择默认提供商" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem v-for="provider in providers" :key="provider.id" :value="provider.id">
                    {{ provider.displayName || provider.id }}
                  </SelectItem>
                </SelectContent>
              </Select>
            </SettingItem>
          </SettingSection>

          <SettingSection title="提供商列表" description="展开后可查看配置摘要、健康状态和支持能力。">
            <template #header-actions>
              <Button @click="showProviderManager = true">
                配置提供商
              </Button>
            </template>

            <StatePanel
              v-if="providers.length === 0"
              title="暂无已配置提供商"
              :description="providerLoadError || '打开提供商管理器，至少添加或启用一个模型提供商。'"
            >
              <template #icon>
                <Cpu class="size-5" />
              </template>
              <template #actions>
                <Button @click="showProviderManager = true">
                  打开提供商管理器
                </Button>
              </template>
            </StatePanel>

            <div v-else class="space-y-4 py-1">
              <article
                v-for="provider in providers"
                :key="provider.id"
                class="list-card overflow-hidden"
              >
                <button
                  type="button"
                  class="flex w-full items-center justify-between gap-4 px-4 py-4 text-left"
                  @click="toggleProviderExpanded(provider.id)"
                >
                  <div class="flex min-w-0 items-start gap-3">
                    <Cpu class="mt-0.5 size-4 shrink-0 text-muted-foreground" />

                    <div class="min-w-0 space-y-1">
                      <div class="flex flex-wrap items-center gap-2">
                        <div class="text-sm font-medium text-foreground">
                          {{ provider.displayName || provider.id }}
                        </div>
                        <Badge v-if="provider.id === defaultProvider" variant="secondary">默认</Badge>
                        <Badge
                          v-if="getProviderHealth(provider.id) !== 'unknown'"
                          :variant="getHealthBadgeVariant(getProviderHealth(provider.id))"
                        >
                          <Loader2
                            v-if="getProviderHealth(provider.id) === 'checking'"
                            class="size-3.5 animate-spin"
                          />
                          <CheckCircle2 v-else-if="getProviderHealth(provider.id) === 'healthy'" class="size-3.5" />
                          <XCircle v-else-if="getProviderHealth(provider.id) === 'unhealthy'" class="size-3.5" />
                          {{ getHealthBadgeText(getProviderHealth(provider.id)) }}
                        </Badge>
                      </div>

                      <p v-if="provider.description" class="text-sm leading-6 text-muted-foreground">
                        {{ provider.description }}
                      </p>
                    </div>
                  </div>

                  <ChevronDown
                    class="size-4 shrink-0 text-muted-foreground transition-transform"
                    :class="expandedProviders.has(provider.id) ? 'rotate-180' : ''"
                  />
                </button>

                <Transition
                  enter-active-class="transition-all duration-300 ease-out"
                  enter-from-class="opacity-0 max-h-0 overflow-hidden"
                  enter-to-class="opacity-100 max-h-[720px]"
                  leave-active-class="transition-all duration-200 ease-in"
                  leave-from-class="opacity-100 max-h-[720px]"
                  leave-to-class="opacity-0 max-h-0 overflow-hidden"
                >
                  <div
                    v-if="expandedProviders.has(provider.id)"
                    class="border-t border-border/70 px-4 py-4"
                  >
                    <div class="mb-4 flex flex-wrap items-center gap-3">
                      <Button
                        variant="outline"
                        size="sm"
                        :disabled="checkingHealth.has(provider.id)"
                        @click="checkProviderHealth(provider.id)"
                      >
                        <Loader2 v-if="getProviderHealth(provider.id) === 'checking'" class="size-3.5 animate-spin" />
                        <RefreshCw v-else class="size-3.5" />
                        {{ getProviderHealth(provider.id) === 'checking' ? '检查中...' : '检查健康状态' }}
                      </Button>

                      <Button
                        variant="destructive"
                        size="sm"
                        :disabled="loadingProviders"
                        @click="confirmDelete(provider)"
                      >
                        <Trash2 class="size-3.5" />
                        删除提供商
                      </Button>
                    </div>

                    <div class="grid gap-4 xl:grid-cols-[minmax(0,1fr)_280px]">
                      <div class="detail-card p-4">
                        <div class="grid gap-3 md:grid-cols-2">
                          <div class="space-y-1">
                            <div class="text-xs text-muted-foreground">类型</div>
                            <p class="text-sm leading-6 text-foreground">{{ provider.type }}</p>
                          </div>
                          <div class="space-y-1">
                            <div class="text-xs text-muted-foreground">模型</div>
                            <p class="text-sm leading-6 text-foreground">{{ provider.modelName }}</p>
                          </div>
                          <div class="space-y-1">
                            <div class="text-xs text-muted-foreground">优先级</div>
                            <p class="text-sm leading-6 text-foreground">{{ provider.priority ?? '未设置' }}</p>
                          </div>
                          <div class="space-y-1">
                            <div class="text-xs text-muted-foreground">API 地址</div>
                            <p class="break-all font-mono text-xs leading-6 text-muted-foreground">
                              {{ provider.apiUrl || '未设置' }}
                            </p>
                          </div>
                        </div>

                        <div class="my-4 soft-divider" />

                        <div class="space-y-3">
                          <div class="space-y-1">
                            <div class="text-xs text-muted-foreground">能力</div>
                            <p class="text-sm leading-6 text-muted-foreground">
                              {{ formatCapabilities(provider.capabilities) }}
                            </p>
                          </div>

                          <div v-if="provider.scenes?.length" class="space-y-1">
                            <div class="text-xs text-muted-foreground">支持场景</div>
                            <p class="text-sm leading-6 text-muted-foreground">{{ formatScenes(provider.scenes) }}</p>
                          </div>
                        </div>
                      </div>

                      <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-4">
                        <div class="surface-label mb-3 text-[0.68rem]">摘要</div>
                        <div class="space-y-3 text-sm">
                          <div class="space-y-1">
                            <div class="text-xs text-muted-foreground">默认状态</div>
                            <div class="font-medium text-foreground">
                              {{ provider.id === defaultProvider ? '当前默认' : '备用提供商' }}
                            </div>
                          </div>
                          <div class="space-y-1">
                            <div class="text-xs text-muted-foreground">成本</div>
                            <div class="text-sm leading-6 text-muted-foreground">
                              {{ formatCost(provider.costPerInputToken ?? 0, provider.costPerOutputToken ?? 0) }}
                            </div>
                          </div>
                          <div class="space-y-1">
                            <div class="text-xs text-muted-foreground">上下文窗口</div>
                            <div class="text-sm leading-6 text-foreground">
                              {{ provider.maxContextWindow ? `${provider.maxContextWindow.toLocaleString()} 词元` : '未设置' }}
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </Transition>
              </article>
            </div>
          </SettingSection>
        </div>

        <aside class="xl:sticky xl:top-6 xl:self-start">
          <section class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 p-5">
            <h3 class="text-sm font-semibold text-foreground">当前状态</h3>
            <p class="mt-1 text-sm leading-6 text-muted-foreground">
              快速查看提供商数量、默认项和检查结果。
            </p>

            <div class="mt-4 space-y-3">
              <div
                v-for="item in currentStatusItems"
                :key="item.label"
                class="flex items-center justify-between gap-3 border-b border-border/50 pb-3 last:border-b-0 last:pb-0"
              >
                <span class="text-sm text-muted-foreground">{{ item.label }}</span>
                <span class="text-sm font-medium text-foreground">{{ item.value }}</span>
              </div>
            </div>
          </section>
        </aside>
      </div>
    </template>

    <LlmProviderManager v-if="showProviderManager" @close="handleProviderManagerClose" />

    <ConfirmDialog
      v-model:show="showDeleteConfirm"
      title="删除提供商"
      :message="deleteConfirmMessage"
      confirm-label="删除"
      cancel-label="取消"
      confirm-variant="destructive"
      @confirm="handleDelete"
    />
  </div>
</template>
