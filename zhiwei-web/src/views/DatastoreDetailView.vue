<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  ArrowUpRight,
  Database,
  FileJson2,
  RefreshCw,
  Settings2,
} from 'lucide-vue-next'
import { datastoreApi, knowledgeBaseApi } from '@/api/client'
import type { Datastore, KnowledgeBase } from '@/types'
import Breadcrumb from '@/components/global/Breadcrumb.vue'
import type { BreadcrumbItem } from '@/components/global/Breadcrumb.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import PageSection from '@/components/layout/PageSection.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'

type PropertyDefinition = {
  name?: string
  type?: string
  required?: boolean
}

const route = useRoute()
const router = useRouter()

const datastoreId = computed(() => route.params.id as string)
const datastore = ref<Datastore | null>(null)
const relatedKnowledgeBases = ref<KnowledgeBase[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

const breadcrumbItems = computed<BreadcrumbItem[]>(() => [
  { label: 'Datastore', to: { name: 'datastores' } },
  { label: datastore.value?.name ?? '详情' },
])

const propertyDefinitions = computed(() => {
  if (!datastore.value?.propertiesJson?.trim()) {
    return [] as PropertyDefinition[]
  }
  try {
    const parsed = JSON.parse(datastore.value.propertiesJson)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return [] as PropertyDefinition[]
  }
})

const formattedProjectionConfig = computed(() => formatJson(datastore.value?.projectionConfigJson))
const formattedMetadata = computed(() => formatJson(datastore.value?.metadataJson))

async function loadDatastore() {
  if (!datastoreId.value) return
  loading.value = true
  error.value = null
  try {
    const [datastoreDetail, knowledgeBases] = await Promise.all([
      datastoreApi.get(datastoreId.value),
      knowledgeBaseApi.list(),
    ])
    datastore.value = datastoreDetail
    relatedKnowledgeBases.value = knowledgeBases.filter(knowledgeBase =>
      (knowledgeBase.datastoreIds ?? []).includes(datastoreId.value),
    )
  } catch (requestError: any) {
    error.value = requestError?.message ?? '加载 Datastore 详情失败。'
    datastore.value = null
    relatedKnowledgeBases.value = []
  } finally {
    loading.value = false
  }
}

function formatJson(raw?: string | null) {
  if (!raw || !raw.trim()) {
    return null
  }
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

function formatDate(value?: string | null) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN')
}

function openKnowledgeBase(knowledgeBaseId: string) {
  void router.push({
    name: 'knowledgeBaseDetail',
    params: { id: knowledgeBaseId },
  })
}

watch(
  () => datastoreId.value,
  () => {
    void loadDatastore()
  },
  { immediate: true },
)
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack">
        <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <Breadcrumb :items="breadcrumbItems" class="min-w-0" />
          <div class="flex flex-wrap items-center gap-2">
            <Button type="button" variant="outline" @click="loadDatastore">
              <RefreshCw class="size-4" />
              刷新
            </Button>
            <Button type="button" variant="ghost" @click="router.push({ name: 'datastores' })">
              <ArrowLeft class="size-4" />
              返回列表
            </Button>
          </div>
        </div>

        <PageHeader
          eyebrow="Datastore"
          :title="datastore?.name || 'Datastore 详情'"
          :description="datastore?.description || '查看这个领域数据容器的结构字段、投影配置与元数据。'"
        >
          <template #actions>
            <Badge v-if="datastore" variant="outline">{{ datastore.type }}</Badge>
          </template>

          <template #meta>
            <MetricCard label="字段数量" :value="propertyDefinitions.length" hint="当前 Datastore 已声明的结构字段数。">
              <template #icon>
                <FileJson2 class="size-5" />
              </template>
            </MetricCard>
            <MetricCard
              label="投影配置"
              :value="formattedProjectionConfig ? '已配置' : '默认'"
              hint="控制结构化数据向向量检索文本的投影方式。"
            >
              <template #icon>
                <Settings2 class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="更新时间" :value="formatDate(datastore?.updatedAt)" hint="最后一次更新 Datastore 定义的时间。">
              <template #icon>
                <Database class="size-5" />
              </template>
            </MetricCard>
          </template>
        </PageHeader>

        <div v-if="loading && !datastore" class="space-y-4">
          <div class="grid gap-4 md:grid-cols-3">
            <Skeleton v-for="index in 3" :key="index" class="h-28 rounded-[calc(var(--radius)+6px)]" />
          </div>
          <Skeleton class="h-48 rounded-[calc(var(--radius)+6px)]" />
          <Skeleton class="h-48 rounded-[calc(var(--radius)+6px)]" />
        </div>

        <StatePanel
          v-else-if="error"
          title="Datastore 详情加载失败"
          :description="error"
          tone="danger"
        >
          <template #icon>
            <Database class="size-5" />
          </template>
        </StatePanel>

        <template v-else-if="datastore">
          <PageSection title="基础信息" description="确认 Datastore 的类型、创建者和更新时间。">
            <div class="detail-card p-5">
              <div class="grid gap-4 text-sm md:grid-cols-2 xl:grid-cols-4">
                <div>
                  <div class="text-muted-foreground">ID</div>
                  <div class="mt-1 break-all font-medium text-foreground">{{ datastore.id }}</div>
                </div>
                <div>
                  <div class="text-muted-foreground">类型</div>
                  <div class="mt-1 font-medium text-foreground">{{ datastore.type }}</div>
                </div>
                <div>
                  <div class="text-muted-foreground">创建者</div>
                  <div class="mt-1 font-medium text-foreground">{{ datastore.createdBy || '未标注' }}</div>
                </div>
                <div>
                  <div class="text-muted-foreground">创建时间</div>
                  <div class="mt-1 font-medium text-foreground">{{ formatDate(datastore.createdAt) }}</div>
                </div>
              </div>
            </div>
          </PageSection>

          <PageSection title="字段结构" description="Datastore 已声明的结构字段，用于校验、索引和向量投影。">
            <div class="detail-card overflow-hidden">
              <div v-if="propertyDefinitions.length === 0" class="px-5 py-10 text-sm text-muted-foreground">
                当前 Datastore 没有声明结构字段。
              </div>
              <table v-else class="w-full text-sm">
                <thead>
                  <tr class="border-b border-border/60">
                    <th class="px-5 py-3 text-left font-medium text-muted-foreground">字段名</th>
                    <th class="px-5 py-3 text-left font-medium text-muted-foreground">类型</th>
                    <th class="px-5 py-3 text-left font-medium text-muted-foreground">必填</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="field in propertyDefinitions"
                    :key="field.name || field.type"
                    class="border-b border-border/40"
                  >
                    <td class="px-5 py-3 font-medium text-foreground">{{ field.name || '未命名字段' }}</td>
                    <td class="px-5 py-3 text-muted-foreground">{{ field.type || '未标注' }}</td>
                    <td class="px-5 py-3 text-muted-foreground">{{ field.required ? '是' : '否' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </PageSection>

          <PageSection title="投影配置" description="控制 Datastore 结构化数据如何投影为可检索文本。">
            <div class="detail-card p-5">
              <pre
                v-if="formattedProjectionConfig"
                class="overflow-x-auto rounded-[calc(var(--radius)-2px)] border border-border/60 bg-background/70 p-4 text-xs leading-6 text-foreground"
              >{{ formattedProjectionConfig }}</pre>
              <div v-else class="text-sm text-muted-foreground">
                当前使用默认投影规则，没有显式 projectionConfig。
              </div>
            </div>
          </PageSection>

          <PageSection title="元数据" description="保留 Datastore 的附加元数据，用于扩展和排查。">
            <div class="detail-card p-5">
              <pre
                v-if="formattedMetadata"
                class="overflow-x-auto rounded-[calc(var(--radius)-2px)] border border-border/60 bg-background/70 p-4 text-xs leading-6 text-foreground"
              >{{ formattedMetadata }}</pre>
              <div v-else class="text-sm text-muted-foreground">
                当前没有元数据。
              </div>
            </div>
          </PageSection>

          <PageSection title="关联知识库" description="这些知识库当前挂载了该 Datastore，可直接跳转查看领域资料与文档。">
            <div v-if="relatedKnowledgeBases.length === 0" class="detail-card p-5 text-sm text-muted-foreground">
              当前还没有知识库挂载这个 Datastore。
            </div>
            <div v-else class="grid gap-4 md:grid-cols-2">
              <article
                v-for="knowledgeBase in relatedKnowledgeBases"
                :key="knowledgeBase.id"
                class="detail-card cursor-pointer p-5 transition-colors hover:border-primary/35 hover:bg-muted/20"
                @click="openKnowledgeBase(knowledgeBase.id)"
              >
                <div class="flex items-start justify-between gap-3">
                  <div class="min-w-0">
                    <div class="flex flex-wrap items-center gap-2">
                      <h3 class="truncate text-base font-semibold text-foreground">{{ knowledgeBase.name }}</h3>
                      <Badge variant="outline">{{ knowledgeBase.documentCount }} 文档</Badge>
                    </div>
                    <p class="mt-2 line-clamp-2 text-sm leading-6 text-muted-foreground">
                      {{ knowledgeBase.description || '暂无描述。' }}
                    </p>
                  </div>
                  <ArrowUpRight class="mt-1 size-4 shrink-0 text-muted-foreground" />
                </div>

                <div class="mt-4 grid gap-3 text-sm">
                  <div class="flex items-center justify-between gap-3">
                    <span class="text-muted-foreground">向量模型</span>
                    <span class="text-right font-medium text-foreground">{{ knowledgeBase.embeddingModel || '未设置' }}</span>
                  </div>
                  <div class="flex items-center justify-between gap-3">
                    <span class="text-muted-foreground">分块数</span>
                    <span class="text-right font-medium text-foreground">{{ knowledgeBase.totalChunks }}</span>
                  </div>
                  <div class="flex items-center justify-between gap-3">
                    <span class="text-muted-foreground">更新时间</span>
                    <span class="text-right font-medium text-foreground">{{ formatDate(knowledgeBase.updatedAt) }}</span>
                  </div>
                </div>
              </article>
            </div>
          </PageSection>
        </template>
      </div>
    </PageContainer>
  </div>
</template>
