<script setup lang="ts">
import { onMounted, ref, computed, watch } from 'vue'
import { settingsApi, llmProviderApi } from '@/api/client'
import type { RerankerSettings, RerankerSettingsRequest, LlmProvider } from '@/api/client'
import SettingSection from '@/components/settings/SettingSection.vue'
import SettingItem from '@/components/settings/SettingItem.vue'
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

const loading = ref(true)
const loadError = ref<string | null>(null)
const saving = ref(false)
const saveError = ref<string | null>(null)
const saveSuccess = ref(false)

// 已启用的 LLM Provider 列表，用于模型选择下拉
const providers = ref<LlmProvider[]>([])
const loadingProviders = ref(false)

const form = ref<RerankerSettingsRequest>({
  enabled: false,
  type: 'llm',
  model: '',
  topK: 10,
  llmMode: 'pointwise',
  apiProvider: 'jina',
  apiKey: '',
  apiEndpoint: '',
  apiTimeoutMs: 30000,
  memoryRerankEnabled: false,
  memoryRerankTopK: 10,
})

const isLlmType = computed(() => form.value.type === 'llm')
const isApiType = computed(() => form.value.type === 'api')

onMounted(async () => {
  await Promise.all([loadRerankerSettings(), loadProviders()])
})

async function loadProviders() {
  loadingProviders.value = true
  try {
    providers.value = await llmProviderApi.listEnabledProviders()
  } catch (e) {
    console.error('加载 LLM Provider 列表失败:', e)
    providers.value = []
  } finally {
    loadingProviders.value = false
  }
}

async function loadRerankerSettings() {
  loading.value = true
  loadError.value = null
  try {
    const data = await settingsApi.getRerankerSettings()
    form.value = {
      enabled: data.enabled,
      type: data.type || 'llm',
      model: data.model || '',
      topK: data.topK || 10,
      llmMode: data.llmMode || 'pointwise',
      apiProvider: data.apiProvider || 'jina',
      apiKey: data.apiKey || '',
      apiEndpoint: data.apiEndpoint || '',
      apiTimeoutMs: data.apiTimeoutMs || 30000,
      memoryRerankEnabled: data.memoryRerankEnabled,
      memoryRerankTopK: data.memoryRerankTopK || 10,
    }
  } catch (e) {
    console.error('加载精排配置失败:', e)
    loadError.value = '暂时无法读取精排配置，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  saving.value = true
  saveError.value = null
  saveSuccess.value = false

  try {
    const data = await settingsApi.updateRerankerSettings(form.value)
    // 用响应更新表单（apiKey 会被掩码）
    form.value = {
      enabled: data.enabled,
      type: data.type || 'llm',
      model: data.model || '',
      topK: data.topK || 10,
      llmMode: data.llmMode || 'pointwise',
      apiProvider: data.apiProvider || 'jina',
      apiKey: data.apiKey || '',
      apiEndpoint: data.apiEndpoint || '',
      apiTimeoutMs: data.apiTimeoutMs || 30000,
      memoryRerankEnabled: data.memoryRerankEnabled,
      memoryRerankTopK: data.memoryRerankTopK || 10,
    }
    saveSuccess.value = true
    window.setTimeout(() => { saveSuccess.value = false }, 2200)
  } catch (e: any) {
    console.error('保存精排配置失败:', e)
    saveError.value = e?.message || '保存失败，请稍后重试。'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <!-- 加载骨架 -->
    <div v-if="loading" class="space-y-6">
      <Skeleton class="h-8 w-48" />
      <Skeleton class="h-40 w-full" />
      <Skeleton class="h-40 w-full" />
    </div>

    <!-- 加载失败 -->
    <div v-else-if="loadError" class="detail-card px-6 py-8 text-center">
      <p class="text-sm text-muted-foreground">{{ loadError }}</p>
      <Button variant="outline" class="mt-4" @click="loadRerankerSettings">重试</Button>
    </div>

    <!-- 表单 -->
    <form v-else class="space-y-8" @submit.prevent="handleSave">
      <!-- 全局精排配置 -->
      <SettingSection title="全局精排" icon="🔀" description="控制检索结果的二次排序策略，提升相关性。">
        <SettingItem label="启用精排" description="开启后，检索结果将经过精排模型二次排序。">
          <Switch
            :checked="form.enabled"
            @update:checked="(v: boolean) => form.enabled = v"
          />
        </SettingItem>

        <SettingItem label="精排类型" description="选择使用 LLM 或外部 API 进行精排。">
          <Select v-model="form.type">
            <SelectTrigger class="w-40">
              <SelectValue placeholder="选择类型" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="llm">LLM</SelectItem>
              <SelectItem value="api">API</SelectItem>
            </SelectContent>
          </Select>
        </SettingItem>

        <SettingItem label="精排模型" description="选择用于精排的 LLM 模型，或在 API 模式下手动输入。">
          <template v-if="isLlmType">
            <Select v-model="form.model">
              <SelectTrigger class="w-56">
                <SelectValue placeholder="选择模型" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem v-for="p in providers" :key="p.id" :value="p.modelName">
                  {{ p.displayName || p.modelName }}
                </SelectItem>
              </SelectContent>
            </Select>
          </template>
          <Input v-else v-model="form.model" placeholder="如 rerank-v3" class="w-56" />
        </SettingItem>

        <SettingItem label="返回数量" description="精排后保留的最大结果数。">
          <Input v-model.number="form.topK" type="number" :min="1" :max="100" class="w-28" />
        </SettingItem>
      </SettingSection>

      <!-- LLM 模式配置 -->
      <SettingSection v-if="isLlmType" title="LLM 精排配置" icon="🧠" description="使用 LLM 对候选结果逐条或批量评分。">
        <SettingItem label="评分模式" description="Pointwise 逐条评分精度高，Listwise 批量评分速度快。">
          <Select v-model="form.llmMode">
            <SelectTrigger class="w-40">
              <SelectValue placeholder="选择模式" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="pointwise">Pointwise</SelectItem>
              <SelectItem value="listwise">Listwise</SelectItem>
            </SelectContent>
          </Select>
        </SettingItem>
      </SettingSection>

      <!-- API 配置 -->
      <SettingSection v-if="isApiType" title="API 精排配置" icon="🌐" description="使用 Jina 或 Cohere 等外部精排 API。">
        <SettingItem label="API 提供商" description="选择精排 API 服务商。">
          <Select v-model="form.apiProvider">
            <SelectTrigger class="w-40">
              <SelectValue placeholder="选择提供商" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="jina">Jina</SelectItem>
              <SelectItem value="cohere">Cohere</SelectItem>
            </SelectContent>
          </Select>
        </SettingItem>

        <SettingItem label="API Key" description="精排 API 的访问密钥。">
          <Input v-model="form.apiKey" type="password" placeholder="输入 API Key" class="w-56" />
        </SettingItem>

        <SettingItem label="API 端点" description="自定义 API 地址，留空使用默认端点。">
          <Input v-model="form.apiEndpoint" placeholder="https://api.example.com/rerank" class="w-72" />
        </SettingItem>

        <SettingItem label="超时时间 (ms)" description="API 请求超时时间，单位毫秒。">
          <Input v-model.number="form.apiTimeoutMs" type="number" :min="1000" :max="120000" class="w-28" />
        </SettingItem>
      </SettingSection>

      <!-- 记忆精排配置 -->
      <SettingSection title="记忆精排" icon="💾" description="控制记忆检索结果是否经过精排，需先启用全局精排。">
        <SettingItem label="启用记忆精排" description="开启后，记忆检索的融合结果将经过 Reranker 二次排序。">
          <Switch
            :checked="form.memoryRerankEnabled"
            @update:checked="(v: boolean) => form.memoryRerankEnabled = v"
          />
        </SettingItem>

        <SettingItem label="记忆精排 TopK" description="记忆精排后保留的最大结果数。">
          <Input v-model.number="form.memoryRerankTopK" type="number" :min="1" :max="100" class="w-28" />
        </SettingItem>
      </SettingSection>

      <!-- 保存栏 -->
      <div class="sticky bottom-0 z-10 pb-2 pt-4">
        <div class="detail-card bg-background/92 px-4 py-4 backdrop-blur">
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div class="space-y-1">
              <div class="text-sm font-medium text-foreground">保存精排配置</div>
              <p class="text-sm leading-6 text-muted-foreground">
                全局精排和记忆精排配置会在保存后立即生效。
              </p>
            </div>
            <div class="flex flex-wrap items-center gap-3">
              <span v-if="saveSuccess" class="text-sm text-emerald-600 dark:text-emerald-400">已保存</span>
              <span v-if="saveError" class="text-sm text-destructive">{{ saveError }}</span>
              <Button type="submit" :disabled="saving">
                {{ saving ? '保存中...' : '保存精排设置' }}
              </Button>
            </div>
          </div>
        </div>
      </div>
    </form>
  </div>
</template>
