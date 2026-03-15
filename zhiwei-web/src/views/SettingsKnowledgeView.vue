<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { settingsApi } from '@/api/client'
import type { KnowledgeSettings } from '@/api/client'
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

const form = ref({
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

onMounted(() => loadSettings())

async function loadSettings() {
  loading.value = true
  loadError.value = null
  try {
    const data: KnowledgeSettings = await settingsApi.getKnowledgeSettings()
    form.value = {
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
  } catch (e) {
    console.error('加载知识库配置失败:', e)
    loadError.value = '暂时无法读取知识库配置，请稍后重试。'
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  saving.value = true
  saveError.value = null
  saveSuccess.value = false
  try {
    const data = await settingsApi.updateKnowledgeSettings(form.value)
    form.value = {
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
    saveSuccess.value = true
    window.setTimeout(() => { saveSuccess.value = false }, 2200)
  } catch (e: any) {
    console.error('保存知识库配置失败:', e)
    saveError.value = e?.message || '保存失败，请稍后重试。'
  } finally {
    saving.value = false
  }
}

const maxFileSizeMB = {
  get() { return Math.round(form.value.maxFileSize / 1048576) },
  set(v: number) { form.value.maxFileSize = v * 1048576 },
}
</script>

<template>
  <div>
    <div v-if="loading" class="space-y-6">
      <Skeleton class="h-8 w-48" />
      <Skeleton class="h-40 w-full" />
      <Skeleton class="h-40 w-full" />
    </div>

    <div v-else-if="loadError" class="detail-card px-6 py-8 text-center">
      <p class="text-sm text-muted-foreground">{{ loadError }}</p>
      <Button variant="outline" class="mt-4" @click="loadSettings">重试</Button>
    </div>

    <form v-else class="space-y-8" @submit.prevent="handleSave">
      <!-- 基础配置 -->
      <SettingSection title="基础配置" icon="📚" description="知识库全局开关和文件大小限制。">
        <SettingItem label="启用知识库" description="关闭后，所有知识库检索将被跳过。">
          <Switch v-model="form.enabled" />
        </SettingItem>
        <SettingItem label="最大文件大小 (MB)" description="单个文档允许的最大上传大小。">
          <Input :model-value="maxFileSizeMB.get()" @update:model-value="maxFileSizeMB.set(Number($event))" type="number" :min="1" :max="1024" class="w-28" />
        </SettingItem>
      </SettingSection>

      <!-- 分块配置 -->
      <SettingSection title="分块配置" icon="✂️" description="控制文档如何被切分为检索单元。">
        <SettingItem label="分块策略" description="smart 自动选择最佳策略，fixed 固定大小切分。">
          <Select v-model="form.chunkingStrategy">
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
          <Input v-model.number="form.chunkSize" type="number" :min="128" :max="8192" class="w-28" />
        </SettingItem>
        <SettingItem label="重叠大小" description="相邻分块之间的重叠字符数，保证上下文连续性。">
          <Input v-model.number="form.overlapSize" type="number" :min="0" :max="1024" class="w-28" />
        </SettingItem>
        <SettingItem label="最大分块 Token 数" description="单个分块的 Token 上限，超出将被截断。">
          <Input v-model.number="form.maxChunkTokens" type="number" :min="64" :max="4096" class="w-28" />
        </SettingItem>
      </SettingSection>

      <!-- 检索配置 -->
      <SettingSection title="检索配置" icon="🔍" description="控制混合检索的权重和过滤阈值。">
        <SettingItem label="返回数量 (TopK)" description="检索返回的最大结果数。">
          <Input v-model.number="form.retrievalTopK" type="number" :min="1" :max="100" class="w-28" />
        </SettingItem>
        <SettingItem label="向量权重" description="向量检索在混合评分中的权重（0-1）。">
          <Input v-model.number="form.vectorWeight" type="number" :min="0" :max="1" :step="0.05" class="w-28" />
        </SettingItem>
        <SettingItem label="全文检索权重" description="FTS5 全文检索在混合评分中的权重（0-1）。">
          <Input v-model.number="form.ftsWeight" type="number" :min="0" :max="1" :step="0.05" class="w-28" />
        </SettingItem>
        <SettingItem label="最低相关性分数" description="低于此分数的结果将被过滤。">
          <Input v-model.number="form.minRelevanceScore" type="number" :min="0" :max="1" :step="0.05" class="w-28" />
        </SettingItem>
      </SettingSection>

      <!-- 向量索引配置 -->
      <SettingSection title="向量索引" icon="🧮" description="Embedding 维度和批量处理参数。">
        <SettingItem label="Embedding 维度" description="向量嵌入的维度，需与所用模型一致。">
          <Input v-model.number="form.embeddingDimension" type="number" :min="128" :max="4096" class="w-28" />
        </SettingItem>
        <SettingItem label="批量大小" description="向量索引时每批处理的文档数。">
          <Input v-model.number="form.vectorBatchSize" type="number" :min="1" :max="256" class="w-28" />
        </SettingItem>
      </SettingSection>

      <!-- 保存栏 -->
      <div class="sticky bottom-0 z-10 pb-2 pt-4">
        <div class="detail-card bg-background/92 px-4 py-4 backdrop-blur">
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div class="space-y-1">
              <div class="text-sm font-medium text-foreground">保存知识库配置</div>
              <p class="text-sm leading-6 text-muted-foreground">
                配置会在保存后立即生效，影响所有知识库的检索行为。
              </p>
            </div>
            <div class="flex flex-wrap items-center gap-3">
              <span v-if="saveSuccess" class="text-sm text-emerald-600 dark:text-emerald-400">已保存</span>
              <span v-if="saveError" class="text-sm text-destructive">{{ saveError }}</span>
              <Button type="submit" :disabled="saving">
                {{ saving ? '保存中...' : '保存知识库设置' }}
              </Button>
            </div>
          </div>
        </div>
      </div>
    </form>
  </div>
</template>