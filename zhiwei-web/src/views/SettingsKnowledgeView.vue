<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { settingsApi } from '@/api/client'
import type { KnowledgeSettings, SearchSettings } from '@/api/client'
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

const knowledgeForm = ref({
  enabled: true,
  maxFileSize: 104857600,
  chunkingStrategy: 'smart',
  chunkSize: 1024,
  overlapSize: 128,
  maxChunkTokens: 512,
  retrievalTopK: 10,
  vectorWeight: 0.6,
  ftsWeight: 0.4,
  minRelevanceScore: 0.0,
  embeddingDimension: 1536,
  vectorBatchSize: 32,
})

const searchForm = ref({
  provider: 'tavily',
  apiKey: '',
  maxResults: 5,
  searchDepth: 'basic',
  topic: 'general',
  includeAnswer: true,
  connectTimeoutSeconds: 10,
  readTimeoutSeconds: 30,
})

const maxFileSizeMB = computed({
  get: () => Math.round(knowledgeForm.value.maxFileSize / 1048576),
  set: (value: number) => {
    knowledgeForm.value.maxFileSize = value * 1048576
  },
})

onMounted(() => {
  void loadSettings()
})

function applyKnowledgeSettings(data: KnowledgeSettings) {
  knowledgeForm.value = {
    enabled: data.enabled,
    maxFileSize: data.maxFileSize,
    chunkingStrategy: data.chunking.defaultStrategy || 'smart',
    chunkSize: data.chunking.chunkSize || 1024,
    overlapSize: data.chunking.overlapSize || 128,
    maxChunkTokens: data.chunking.maxChunkTokens || 512,
    retrievalTopK: data.retrieval.defaultTopK || 10,
    vectorWeight: data.retrieval.vectorWeight || 0.6,
    ftsWeight: data.retrieval.ftsWeight || 0.4,
    minRelevanceScore: data.retrieval.minRelevanceScore || 0.0,
    embeddingDimension: data.vectorIndexer.embeddingDimension || 1536,
    vectorBatchSize: data.vectorIndexer.batchSize || 32,
  }
}

function applySearchSettings(data: SearchSettings) {
  searchForm.value = {
    provider: data.provider || 'tavily',
    apiKey: data.apiKey || '',
    maxResults: data.maxResults || 5,
    searchDepth: data.searchDepth || 'basic',
    topic: data.topic || 'general',
    includeAnswer: data.includeAnswer ?? true,
    connectTimeoutSeconds: data.connectTimeoutSeconds || 10,
    readTimeoutSeconds: data.readTimeoutSeconds || 30,
  }
}

async function loadSettings() {
  loading.value = true
  loadError.value = null
  try {
    const [knowledgeData, searchData] = await Promise.all([
      settingsApi.getKnowledgeSettings(),
      settingsApi.getSearchSettings(),
    ])

    applyKnowledgeSettings(knowledgeData)
    applySearchSettings(searchData)
  } catch (error) {
    console.error('加载知识与检索配置失败', error)
    loadError.value = '暂时无法读取知识与检索配置，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  saving.value = true
  saveError.value = null
  saveSuccess.value = false

  try {
    const [knowledgeData, searchData] = await Promise.all([
      settingsApi.updateKnowledgeSettings(knowledgeForm.value),
      settingsApi.updateSearchSettings(searchForm.value),
    ])

    applyKnowledgeSettings(knowledgeData)
    applySearchSettings(searchData)
    saveSuccess.value = true
    window.setTimeout(() => {
      saveSuccess.value = false
    }, 2200)
  } catch (error: any) {
    console.error('保存知识与检索配置失败', error)
    saveError.value = error?.message || '保存失败，请稍后重试。'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <div v-if="loading" class="space-y-6">
      <Skeleton class="h-8 w-48" />
      <Skeleton class="h-40 w-full" />
      <Skeleton class="h-40 w-full" />
      <Skeleton class="h-40 w-full" />
    </div>

    <div v-else-if="loadError" class="detail-card px-6 py-8 text-center">
      <p class="text-sm text-muted-foreground">{{ loadError }}</p>
      <Button variant="outline" class="mt-4" @click="loadSettings">重试</Button>
    </div>

    <form v-else class="space-y-8" @submit.prevent="handleSave">
      <SettingSection title="基础配置" icon="📚" description="知识库全局开关和文件大小限制。">
        <SettingItem label="启用知识库" description="关闭后，所有知识库检索都会被跳过。">
          <Switch v-model="knowledgeForm.enabled" />
        </SettingItem>
        <SettingItem label="最大文件大小 (MB)" description="单个文档允许的最大上传大小。">
          <Input v-model.number="maxFileSizeMB" type="number" :min="1" :max="1024" class="w-28" />
        </SettingItem>
      </SettingSection>

      <SettingSection title="分块配置" icon="✂️" description="控制文档如何被切分为检索单元。">
        <SettingItem label="分块策略" description="smart 自动选择最佳策略，fixed 固定大小切分。">
          <Select v-model="knowledgeForm.chunkingStrategy">
            <SelectTrigger class="w-40">
              <SelectValue placeholder="选择策略" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="smart">Smart</SelectItem>
              <SelectItem value="fixed">Fixed</SelectItem>
              <SelectItem value="recursive">Recursive</SelectItem>
              <SelectItem value="heading">Heading</SelectItem>
              <SelectItem value="semantic">Semantic</SelectItem>
            </SelectContent>
          </Select>
        </SettingItem>
        <SettingItem label="分块大小" description="每个分块的目标字符数。">
          <Input v-model.number="knowledgeForm.chunkSize" type="number" :min="128" :max="8192" class="w-28" />
        </SettingItem>
        <SettingItem label="重叠大小" description="相邻分块之间的重叠字符数，保证上下文连续性。">
          <Input v-model.number="knowledgeForm.overlapSize" type="number" :min="0" :max="1024" class="w-28" />
        </SettingItem>
        <SettingItem label="最大分块 Token 数" description="单个分块的 Token 上限，超出时会截断。">
          <Input v-model.number="knowledgeForm.maxChunkTokens" type="number" :min="64" :max="4096" class="w-28" />
        </SettingItem>
      </SettingSection>

      <SettingSection title="检索配置" icon="🔎" description="控制知识库混合检索的权重和过滤阈值。">
        <SettingItem label="返回数量 (TopK)" description="知识库检索返回的最大结果数。">
          <Input v-model.number="knowledgeForm.retrievalTopK" type="number" :min="1" :max="100" class="w-28" />
        </SettingItem>
        <SettingItem label="向量权重" description="向量检索在混合评分中的权重，范围 0 到 1。">
          <Input v-model.number="knowledgeForm.vectorWeight" type="number" :min="0" :max="1" :step="0.05" class="w-28" />
        </SettingItem>
        <SettingItem label="全文检索权重" description="FTS5 全文检索在混合评分中的权重，范围 0 到 1。">
          <Input v-model.number="knowledgeForm.ftsWeight" type="number" :min="0" :max="1" :step="0.05" class="w-28" />
        </SettingItem>
        <SettingItem label="最低相关性分数" description="低于该分数的结果将被过滤。">
          <Input v-model.number="knowledgeForm.minRelevanceScore" type="number" :min="0" :max="1" :step="0.05" class="w-28" />
        </SettingItem>
      </SettingSection>

      <SettingSection title="联网搜索 / Tavily" icon="🌐" description="统一管理 Web 搜索工具使用的 Tavily 配置。">
        <SettingItem label="搜索提供商" description="当前搜索工具固定使用 Tavily。">
          <Input :model-value="searchForm.provider.toUpperCase()" readonly class="w-40" />
        </SettingItem>
        <SettingItem
          label="Tavily API Key"
          description="返回时会显示掩码值；保留不动可沿用原值，清空后保存可删除。"
        >
          <Input v-model="searchForm.apiKey" type="text" class="w-[320px]" placeholder="tvly-..." />
        </SettingItem>
        <SettingItem label="默认结果数" description="Web 搜索默认返回的结果条数，Tavily 最大支持 20。">
          <Input v-model.number="searchForm.maxResults" type="number" :min="1" :max="20" class="w-28" />
        </SettingItem>
        <SettingItem label="搜索深度" description="advanced 召回更强但更慢，basic 更省时。">
          <Select v-model="searchForm.searchDepth">
            <SelectTrigger class="w-40">
              <SelectValue placeholder="选择搜索深度" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="basic">Basic</SelectItem>
              <SelectItem value="advanced">Advanced</SelectItem>
            </SelectContent>
          </Select>
        </SettingItem>
        <SettingItem label="搜索主题" description="根据任务类型调整 Tavily 的检索偏好。">
          <Select v-model="searchForm.topic">
            <SelectTrigger class="w-40">
              <SelectValue placeholder="选择搜索主题" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="general">General</SelectItem>
              <SelectItem value="news">News</SelectItem>
              <SelectItem value="finance">Finance</SelectItem>
            </SelectContent>
          </Select>
        </SettingItem>
        <SettingItem label="附带答案摘要" description="启用后，搜索结果中会包含 Tavily 生成的 answer 摘要。">
          <Switch v-model="searchForm.includeAnswer" />
        </SettingItem>
        <SettingItem label="连接超时 (秒)" description="建立到 Tavily 服务连接的超时时间。">
          <Input v-model.number="searchForm.connectTimeoutSeconds" type="number" :min="1" :max="60" class="w-28" />
        </SettingItem>
        <SettingItem label="读取超时 (秒)" description="等待 Tavily 响应主体返回的超时时间。">
          <Input v-model.number="searchForm.readTimeoutSeconds" type="number" :min="1" :max="120" class="w-28" />
        </SettingItem>
      </SettingSection>

      <SettingSection title="向量索引" icon="🧠" description="Embedding 维度和批量处理参数。">
        <SettingItem label="Embedding 维度" description="向量嵌入的维度，需要与所用模型一致。">
          <Input v-model.number="knowledgeForm.embeddingDimension" type="number" :min="128" :max="4096" class="w-28" />
        </SettingItem>
        <SettingItem label="批量大小" description="向量索引时每批处理的文档数。">
          <Input v-model.number="knowledgeForm.vectorBatchSize" type="number" :min="1" :max="256" class="w-28" />
        </SettingItem>
      </SettingSection>

      <div class="sticky bottom-0 z-10 pb-2 pt-4">
        <div class="detail-card bg-background/92 px-4 py-4 backdrop-blur">
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div class="space-y-1">
              <div class="text-sm font-medium text-foreground">保存知识与检索配置</div>
              <p class="text-sm leading-6 text-muted-foreground">
                保存后会立即更新知识库参数和 Tavily 搜索工具配置，无需重启应用。
              </p>
            </div>
            <div class="flex flex-wrap items-center gap-3">
              <span v-if="saveSuccess" class="text-sm text-emerald-600 dark:text-emerald-400">已保存</span>
              <span v-if="saveError" class="text-sm text-destructive">{{ saveError }}</span>
              <Button type="submit" :disabled="saving">
                {{ saving ? '保存中...' : '保存知识与检索设置' }}
              </Button>
            </div>
          </div>
        </div>
      </div>
    </form>
  </div>
</template>
