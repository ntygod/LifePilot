<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  ArrowUpRight,
  Database,
  FileText,
  FileJson2,
  Files,
  MessageSquare,
  RefreshCw,
  Settings2,
  Trash2,
  Upload,
} from 'lucide-vue-next'
import { datastoreApi, memoryApi } from '@/api/client'
import type { Datastore, DatastoreDocument, EntitySummary, KbDocument, KnowledgeBase, MemoryProvenanceSummary } from '@/types'
import { useDatastoreStore } from '@/stores/datastore'
import { useUiStore } from '@/stores/ui'
import Breadcrumb from '@/components/global/Breadcrumb.vue'
import type { BreadcrumbItem } from '@/components/global/Breadcrumb.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import PageSection from '@/components/layout/PageSection.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'

type FieldHint = {
  name?: string
  type?: string
  description?: string
}

const route = useRoute()
const router = useRouter()
const datastoreStore = useDatastoreStore()
const uiStore = useUiStore()

const datastoreId = computed(() => route.params.id as string)
const datastore = ref<Datastore | null>(null)
const relatedKnowledgeBases = ref<KnowledgeBase[]>([])
const datastoreRecords = ref<DatastoreDocument[]>([])
const datastoreDomainDocuments = ref<KbDocument[]>([])
const relatedMemoryEntities = ref<EntitySummary[]>([])
const recentProvenanceItems = ref<MemoryProvenanceSummary[]>([])
const relatedMemoryTotal = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)
const recordsLoading = ref(false)
const recordsError = ref<string | null>(null)
const domainDocumentsLoading = ref(false)
const domainDocumentsError = ref<string | null>(null)
const relatedMemoryLoading = ref(false)
const relatedMemoryError = ref<string | null>(null)
const recentProvenanceLoading = ref(false)
const recentProvenanceError = ref<string | null>(null)
const deleteDialogOpen = ref(false)
const deletingDatastore = ref(false)
const uploadInput = ref<HTMLInputElement | null>(null)
const uploadingDocument = ref(false)

const breadcrumbItems = computed<BreadcrumbItem[]>(() => [
  { label: 'Datastore', to: { name: 'datastores' } },
  { label: datastore.value?.name ?? '详情' },
])

const fieldHints = computed(() => {
  if (!datastore.value?.fieldHintsJson?.trim()) {
    return [] as FieldHint[]
  }
  try {
    const parsed = JSON.parse(datastore.value.fieldHintsJson)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return [] as FieldHint[]
  }
})

const MEMORY_SCOPE_LABELS: Record<string, string> = {
  USER_PROFILE: '用户画像',
  USER_FACT: '用户事实',
  AGENT_EXPERIENCE: '执行经验',
  DOMAIN_MEMORY: '领域记忆',
}

const REALITY_TYPE_LABELS: Record<string, string> = {
  REAL: '真实',
  FICTIONAL: '虚构',
  SIMULATED: '模拟',
  UNKNOWN: '未标注',
}

const ORIGIN_TYPE_LABELS: Record<string, string> = {
  CHAT: '对话抽取',
  KNOWLEDGE_BASE_DOCUMENT: '知识库文档',
  DATASTORE_DOCUMENT: 'Datastore 文档',
  MANUAL: '手动维护',
  TOOL: '工具写入',
  CONSOLIDATION: '记忆巩固',
  UNKNOWN: '未标注',
}

async function loadDatastore() {
  if (!datastoreId.value) return
  loading.value = true
  error.value = null
  try {
    const [datastoreDetail, knowledgeBases] = await Promise.all([
      datastoreApi.get(datastoreId.value),
      datastoreApi.listKnowledgeBases(datastoreId.value),
    ])
    datastore.value = datastoreDetail
    relatedKnowledgeBases.value = knowledgeBases
    await Promise.all([
      loadDatastoreRecords(),
      loadDatastoreDomainDocuments(),
      loadRelatedMemories(),
      loadRecentProvenances(),
    ])
  } catch (requestError: any) {
    error.value = requestError?.message ?? '加载 Datastore 详情失败。'
    datastore.value = null
    relatedKnowledgeBases.value = []
    datastoreRecords.value = []
    datastoreDomainDocuments.value = []
    recordsError.value = null
    domainDocumentsError.value = null
    relatedMemoryEntities.value = []
    relatedMemoryTotal.value = 0
    relatedMemoryError.value = null
    recentProvenanceItems.value = []
    recentProvenanceError.value = null
  } finally {
    loading.value = false
  }
}

async function loadDatastoreRecords() {
  if (!datastoreId.value) return
  recordsLoading.value = true
  recordsError.value = null
  try {
    datastoreRecords.value = await datastoreApi.listRecords(datastoreId.value)
  } catch (requestError: any) {
    datastoreRecords.value = []
    recordsError.value = requestError?.message ?? '加载文档列表失败。'
  } finally {
    recordsLoading.value = false
  }
}

async function loadDatastoreDomainDocuments() {
  if (!datastoreId.value) return
  domainDocumentsLoading.value = true
  domainDocumentsError.value = null
  try {
    datastoreDomainDocuments.value = await datastoreApi.listDocuments(datastoreId.value)
  } catch (requestError: any) {
    datastoreDomainDocuments.value = []
    domainDocumentsError.value = requestError?.message ?? '加载领域文档失败。'
  } finally {
    domainDocumentsLoading.value = false
  }
}

async function loadRelatedMemories() {
  if (!datastoreId.value) return
  relatedMemoryLoading.value = true
  relatedMemoryError.value = null
  try {
    const result = await memoryApi.listEntities({
      page: 0,
      size: 6,
      sourceDatastoreId: datastoreId.value,
      sortBy: 'importanceScore',
      order: 'desc',
    })
    relatedMemoryEntities.value = result.items
    relatedMemoryTotal.value = result.total
  } catch (requestError: any) {
    relatedMemoryEntities.value = []
    relatedMemoryTotal.value = 0
    relatedMemoryError.value = requestError?.message ?? '加载关联记忆失败。'
  } finally {
    relatedMemoryLoading.value = false
  }
}

async function loadRecentProvenances() {
  if (!datastoreId.value) return
  recentProvenanceLoading.value = true
  recentProvenanceError.value = null
  try {
    recentProvenanceItems.value = await memoryApi.listRecentProvenances({
      sourceDatastoreId: datastoreId.value,
      limit: 6,
    })
  } catch (requestError: any) {
    recentProvenanceItems.value = []
    recentProvenanceError.value = requestError?.message ?? '加载最近来源失败。'
  } finally {
    recentProvenanceLoading.value = false
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

function parseMetadataJson(raw?: string | null): Record<string, unknown> | null {
  if (!raw?.trim()) return null
  try {
    const parsed = JSON.parse(raw)
    return typeof parsed === 'object' && parsed !== null ? parsed : null
  } catch {
    return null
  }
}

function truncateContent(content?: string | null, maxLength = 200): string {
  if (!content) return '无内容'
  if (content.length <= maxLength) return content
  return content.slice(0, maxLength) + '...'
}

function openKnowledgeBase(knowledgeBaseId: string) {
  void router.push({
    name: 'knowledgeBaseDetail',
    params: { id: knowledgeBaseId },
  })
}

function openKnowledgeBaseDocument(knowledgeBaseId: string, documentId: string) {
  void router.push({
    name: 'knowledgeBaseDocumentDetail',
    params: { id: knowledgeBaseId, docId: documentId },
  })
}

function openDatastoreDocument(document: KbDocument) {
  void router.push({
    name: 'knowledgeBaseDocumentDetail',
    params: { id: document.knowledgeBaseId, docId: document.id },
  })
}

function openConversation(sessionId: string) {
  void router.push({
    name: 'conversationDetail',
    params: { sessionId },
  })
}

function openDatastoreMemories() {
  if (!datastoreId.value) return
  void router.push({
    name: 'memories',
    query: {
      tab: 'entities',
      sourceDatastoreId: datastoreId.value,
    },
  })
}

function openMemoryEntity(entityId: string) {
  if (!datastoreId.value) return
  void router.push({
    name: 'memories',
    query: {
      tab: 'entities',
      sourceDatastoreId: datastoreId.value,
      entityId,
    },
  })
}

function formatMemoryScope(value?: string | null) {
  if (!value) return '未分配'
  return MEMORY_SCOPE_LABELS[value] || value
}

function formatRealityType(value?: string | null) {
  if (!value) return '未标注'
  return REALITY_TYPE_LABELS[value] || value
}

function formatOriginType(value?: string | null) {
  if (!value) return '未知来源'
  return ORIGIN_TYPE_LABELS[value] || value
}

function buildRecentProvenanceSummary(item: MemoryProvenanceSummary) {
  if (item.sourceDocumentName) {
    if (item.sourceKnowledgeBaseName) {
      return `来自文档 ${item.sourceDocumentName} / ${item.sourceKnowledgeBaseName}`
    }
    return `来自文档 ${item.sourceDocumentName}`
  }
  if (item.sourceKnowledgeBaseName) {
    return `来自知识库 ${item.sourceKnowledgeBaseName}`
  }
  if (item.sourceSessionId) {
    return `来自会话 ${item.sourceSessionId}`
  }
  if (item.sourceReference) {
    return item.sourceReference
  }
  return '来源信息未标注'
}

function openDeleteDialog() {
  deleteDialogOpen.value = true
}

function triggerDocumentUpload() {
  uploadInput.value?.click()
}

async function handleDocumentSelected(event: Event) {
  const target = event.target as HTMLInputElement | null
  const file = target?.files?.[0]
  if (!file || !datastoreId.value || uploadingDocument.value) {
    if (target) target.value = ''
    return
  }
  uploadingDocument.value = true
  try {
    await datastoreApi.uploadDocument(datastoreId.value, file)
    uiStore.showToast('success', `文档「${file.name}」已提交处理`)
    await loadDatastoreDomainDocuments()
  } catch (requestError: any) {
    uiStore.showToast('error', requestError?.message ?? 'Datastore 文档上传失败')
  } finally {
    uploadingDocument.value = false
    if (target) target.value = ''
  }
}

async function handleDeleteDatastore() {
  const target = datastore.value
  if (!target || deletingDatastore.value) {
    return
  }
  deletingDatastore.value = true
  try {
    await datastoreStore.deleteDatastore(target.id)
    uiStore.showToast('success', `Datastore「${target.name}」已删除`)
    await router.push({ name: 'datastores' })
  } catch (event: any) {
    uiStore.showToast('error', event?.message ?? '删除 Datastore 失败')
  } finally {
    deletingDatastore.value = false
    deleteDialogOpen.value = false
  }
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
    <PageContainer size="wide" class="py-4 sm:py-5">
      <div class="page-stack">
        <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <Breadcrumb :items="breadcrumbItems" class="min-w-0" />
          <div class="flex flex-wrap items-center gap-2">
            <Button type="button" variant="outline" data-test="open-datastore-memories" @click="openDatastoreMemories">
              <ArrowUpRight class="size-4" />
              查看关联记忆
            </Button>
            <Button
              type="button"
              variant="destructive"
              data-test="open-delete-datastore"
              @click="openDeleteDialog"
            >
              <Trash2 class="size-4" />
              删除 Datastore
            </Button>
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
          :description="datastore?.description || '查看这个领域数据容器的索引字段与文档列表。'"
        >
          <template #actions>
            <Badge v-if="datastore" variant="outline">{{ datastore.timeSeries ? '时序集合' : '普通集合' }}</Badge>
          </template>

          <template #meta>
            <MetricCard label="字段数量" :value="fieldHints.length" hint="当前 Datastore 已声明的索引字段数。">
              <template #icon>
                <FileJson2 class="size-5" />
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
          <input
            ref="uploadInput"
            type="file"
            class="hidden"
            accept=".pdf,.docx,.md,.txt"
            @change="handleDocumentSelected"
          >

          <PageSection title="基础信息" description="确认 Datastore 的类型、创建者和更新时间。">
            <div class="detail-card p-4">
              <div class="grid gap-4 text-sm md:grid-cols-2 xl:grid-cols-4">
                <div>
                  <div class="text-muted-foreground">ID</div>
                  <div class="mt-1 break-all font-medium text-foreground">{{ datastore.id }}</div>
                </div>
                <div>
                  <div class="text-muted-foreground">类型</div>
                  <div class="mt-1 font-medium text-foreground">{{ datastore.timeSeries ? '时序集合' : '普通集合' }}</div>
                </div>
                <div>
                  <div class="text-muted-foreground">创建者</div>
                  <div class="mt-1 font-medium text-foreground">{{ datastore.createdBy || '未标注' }}</div>
                </div>
                <div>
                  <div class="text-muted-foreground">创建时间</div>
                  <div class="mt-1 font-medium text-foreground">{{ formatDate(datastore.createdAt) }}</div>
                </div>
                <div>
                  <div class="text-muted-foreground">内部知识库</div>
                  <div class="mt-1 break-all font-medium text-foreground">{{ datastore.defaultKnowledgeBaseId || '系统待创建' }}</div>
                </div>
              </div>
            </div>
          </PageSection>

          <PageSection title="索引字段" description="已声明的索引字段，用于加速结构化查询。">
            <div class="detail-card overflow-hidden">
              <div v-if="fieldHints.length === 0" class="px-5 py-10 text-sm text-muted-foreground">
                当前 Datastore 没有声明索引字段。
              </div>
              <table v-else class="w-full text-sm">
                <thead>
                  <tr class="border-b border-border/60">
                    <th class="px-5 py-3 text-left font-medium text-muted-foreground">字段名</th>
                    <th class="px-5 py-3 text-left font-medium text-muted-foreground">类型</th>
                    <th class="px-5 py-3 text-left font-medium text-muted-foreground">语义描述</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="field in fieldHints"
                    :key="field.name || field.type"
                    class="border-b border-border/40"
                  >
                    <td class="px-5 py-3 font-medium text-foreground">{{ field.name || '未命名字段' }}</td>
                    <td class="px-5 py-3 text-muted-foreground">{{ field.type || '未标注' }}</td>
                    <td class="px-5 py-3 text-muted-foreground">{{ field.description || '—' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </PageSection>

          <PageSection title="领域文档" description="这里直接管理当前 Datastore 的资料文件，系统会自动写入内部知识库并参与检索。">
            <div class="space-y-4">
              <div class="flex flex-wrap items-center justify-between gap-3">
                <div class="text-sm text-muted-foreground">
                  当前共 {{ datastoreDomainDocuments.length }} 个领域文档，上传入口已经固定绑定到这个 Datastore。
                </div>
                <div class="flex flex-wrap items-center gap-2">
                  <Button type="button" variant="outline" size="sm" :disabled="uploadingDocument" @click="loadDatastoreDomainDocuments">
                    <RefreshCw class="size-4" />
                    刷新文档
                  </Button>
                  <Button type="button" size="sm" :disabled="uploadingDocument" @click="triggerDocumentUpload">
                    <Upload class="size-4" />
                    {{ uploadingDocument ? '上传中...' : '上传文档' }}
                  </Button>
                </div>
              </div>

              <div v-if="domainDocumentsLoading" class="space-y-3">
                <Skeleton v-for="index in 3" :key="`domain-doc-${index}`" class="h-24 rounded-[calc(var(--radius)+6px)]" />
              </div>
              <div v-else-if="domainDocumentsError" class="detail-card p-4 text-sm text-muted-foreground">
                {{ domainDocumentsError }}
              </div>
              <div v-else-if="datastoreDomainDocuments.length === 0" class="detail-card p-4 text-sm text-muted-foreground">
                当前还没有上传到这个 Datastore 的领域文档。
              </div>
              <div v-else class="space-y-3">
                <article
                  v-for="document in datastoreDomainDocuments"
                  :key="document.id"
                  class="detail-card cursor-pointer p-5 transition-colors hover:border-primary/35 hover:bg-muted/20"
                  @click="openDatastoreDocument(document)"
                >
                  <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                    <div class="min-w-0">
                      <div class="flex flex-wrap items-center gap-2">
                        <h3 class="truncate text-base font-semibold text-foreground">{{ document.fileName }}</h3>
                        <Badge variant="outline">{{ document.status }}</Badge>
                      </div>
                      <div class="mt-3 flex flex-wrap gap-4 text-sm text-muted-foreground">
                        <span>分块 {{ document.chunkCount }}</span>
                        <span>大小 {{ Math.max(1, Math.round(document.fileSize / 1024)) }} KB</span>
                        <span>上传于 {{ formatDate(document.createdAt) }}</span>
                      </div>
                      <p v-if="document.errorMessage" class="mt-2 text-sm text-destructive">
                        {{ document.errorMessage }}
                      </p>
                    </div>
                    <ArrowUpRight class="mt-1 size-4 shrink-0 text-muted-foreground" />
                  </div>
                </article>
              </div>
            </div>
          </PageSection>

          <PageSection title="文档列表" description="Datastore 中的文档，以富文本为主表示，结构化元数据为副索引。">
            <div class="space-y-4">
              <div class="flex flex-wrap items-center justify-between gap-3">
                <div class="text-sm text-muted-foreground">
                  当前共 {{ datastoreRecords.length }} 篇文档，语义检索会基于文档内容与元数据索引执行。
                </div>
                <Button type="button" variant="outline" size="sm" @click="loadDatastoreRecords">
                  <RefreshCw class="size-4" />
                  刷新数据
                </Button>
              </div>

              <div v-if="recordsLoading" class="space-y-3">
                <Skeleton v-for="index in 3" :key="`record-${index}`" class="h-32 rounded-[calc(var(--radius)+6px)]" />
              </div>
              <div v-else-if="recordsError" class="detail-card p-4 text-sm text-muted-foreground">
                {{ recordsError }}
              </div>
              <div v-else-if="datastoreRecords.length === 0" class="detail-card p-4 text-sm text-muted-foreground">
                当前还没有文档记录。
              </div>
              <div v-else class="space-y-3">
                <article
                  v-for="record in datastoreRecords"
                  :key="record.id"
                  class="detail-card p-4"
                >
                  <div class="flex flex-wrap items-center justify-between gap-3">
                    <div class="flex flex-wrap items-center gap-2">
                      <h3 class="text-sm font-medium text-foreground">{{ record.fileName }}</h3>
                      <Badge variant="outline">{{ record.status }}</Badge>
                    </div>
                    <div class="text-xs text-muted-foreground">
                      {{ formatDate(record.updatedAt) }}
                    </div>
                  </div>
                  <p class="mt-3 text-sm leading-6 text-muted-foreground">
                    {{ truncateContent(record.content) }}
                  </p>
                  <div v-if="parseMetadataJson(record.metadataJson)" class="mt-3 flex flex-wrap gap-2">
                    <Badge
                      v-for="(value, key) in parseMetadataJson(record.metadataJson)!"
                      :key="String(key)"
                      variant="secondary"
                    >
                      {{ key }}: {{ value }}
                    </Badge>
                  </div>
                  <div class="mt-3 flex flex-wrap gap-4 text-xs text-muted-foreground">
                    <span v-if="record.recordedAt">记录时间 {{ formatDate(record.recordedAt) }}</span>
                    <span>创建于 {{ formatDate(record.createdAt) }}</span>
                  </div>
                </article>
              </div>
            </div>
          </PageSection>

          <PageSection title="关联记忆" description="这些实体来自当前 Datastore，可直接跳到记忆页继续排查来源或查看详情。">
            <div class="space-y-4">
              <div class="flex flex-wrap items-center justify-between gap-3">
                <div class="text-sm text-muted-foreground">
                  当前共关联 {{ relatedMemoryTotal }} 条记忆实体，按重要性优先展示前 6 条。
                </div>
                <Button type="button" variant="outline" size="sm" data-test="open-all-related-memories" @click="openDatastoreMemories">
                  <ArrowUpRight class="size-4" />
                  查看全部
                </Button>
              </div>

              <div v-if="relatedMemoryLoading" class="grid gap-4 md:grid-cols-2">
                <Skeleton v-for="index in 4" :key="index" class="h-36 rounded-[calc(var(--radius)+6px)]" />
              </div>
              <div v-else-if="relatedMemoryError" class="detail-card p-4 text-sm text-muted-foreground">
                {{ relatedMemoryError }}
              </div>
              <div v-else-if="relatedMemoryEntities.length === 0" class="detail-card p-4 text-sm text-muted-foreground">
                当前还没有关联到这个 Datastore 的记忆实体。
              </div>
              <div v-else class="grid gap-4 md:grid-cols-2">
                <article
                  v-for="entity in relatedMemoryEntities"
                  :key="entity.id"
                  class="detail-card cursor-pointer p-5 transition-colors hover:border-primary/35 hover:bg-muted/20"
                  data-test="related-memory-card"
                  @click="openMemoryEntity(entity.id)"
                >
                  <div class="flex items-start justify-between gap-3">
                    <div class="min-w-0">
                      <div class="flex flex-wrap items-center gap-2">
                        <h3 class="truncate text-base font-semibold text-foreground">{{ entity.name }}</h3>
                        <Badge variant="outline">{{ entity.typeLabel }}</Badge>
                      </div>
                      <p v-if="entity.description" class="mt-2 line-clamp-2 text-sm leading-6 text-muted-foreground">
                        {{ entity.description }}
                      </p>
                    </div>
                    <ArrowUpRight class="mt-1 size-4 shrink-0 text-muted-foreground" />
                  </div>

                  <div class="mt-4 flex flex-wrap items-center gap-2 text-xs">
                    <Badge variant="outline">{{ formatMemoryScope(entity.memoryScope) }}</Badge>
                    <Badge variant="secondary">{{ formatRealityType(entity.realityType) }}</Badge>
                    <span class="text-muted-foreground">重要性 {{ entity.importanceScore.toFixed(2) }}</span>
                  </div>
                  <div class="mt-3 text-xs text-muted-foreground">
                    更新时间 {{ formatDate(entity.updatedAt) }}
                  </div>
                </article>
              </div>
            </div>
          </PageSection>

          <PageSection title="最近来源" description="最近有哪些知识库文档或会话 turn 正在把内容写入这批领域记忆。">
            <div class="space-y-4">
              <div class="flex flex-wrap items-center justify-between gap-3">
                <div class="text-sm text-muted-foreground">
                  按写入时间倒序展示最近 6 条来源记录，方便快速排查数据从哪里进入了当前 Datastore 的记忆空间。
                </div>
                <Button type="button" variant="outline" size="sm" data-test="refresh-recent-provenances" @click="loadRecentProvenances">
                  <RefreshCw class="size-4" />
                  刷新来源
                </Button>
              </div>

              <div v-if="recentProvenanceLoading" class="space-y-3">
                <Skeleton v-for="index in 4" :key="index" class="h-28 rounded-[calc(var(--radius)+6px)]" />
              </div>
              <div v-else-if="recentProvenanceError" class="detail-card p-4 text-sm text-muted-foreground">
                {{ recentProvenanceError }}
              </div>
              <div v-else-if="recentProvenanceItems.length === 0" class="detail-card p-4 text-sm text-muted-foreground">
                当前还没有关联到这个 Datastore 的来源写入记录。
              </div>
              <div v-else class="space-y-3">
                <article
                  v-for="item in recentProvenanceItems"
                  :key="`${item.entityId}-${item.createdAt}-${item.sourceTurnId || item.sourceDocumentId || item.sourceSessionId || item.originType}`"
                  class="detail-card p-4"
                  data-test="recent-provenance-card"
                >
                  <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                    <div class="min-w-0 space-y-3">
                      <div class="flex flex-wrap items-center gap-2">
                        <h3 class="truncate text-base font-semibold text-foreground">{{ item.entityName }}</h3>
                        <Badge variant="outline">{{ item.entityTypeLabel }}</Badge>
                        <Badge variant="secondary">{{ formatOriginType(item.originType) }}</Badge>
                      </div>
                      <p class="text-sm leading-6 text-muted-foreground">
                        {{ buildRecentProvenanceSummary(item) }}
                      </p>
                      <div class="flex flex-wrap items-center gap-2 text-xs">
                        <Badge variant="outline">{{ formatMemoryScope(item.entityMemoryScope) }}</Badge>
                        <Badge variant="secondary">{{ formatRealityType(item.entityRealityType) }}</Badge>
                        <span class="text-muted-foreground">置信度 {{ (item.confidence * 100).toFixed(0) }}%</span>
                        <span class="text-muted-foreground">写入于 {{ formatDate(item.createdAt) }}</span>
                      </div>
                      <div class="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
                        <span v-if="item.sourceTurnId">Turn {{ item.sourceTurnId }}</span>
                        <span v-if="item.sourceEntryId">消息 {{ item.sourceEntryId }}</span>
                        <span v-if="item.sourceCollectionName">Collection {{ item.sourceCollectionName }}</span>
                      </div>
                    </div>

                    <div class="flex flex-wrap items-center gap-2">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        data-test="open-recent-provenance-entity"
                        @click="openMemoryEntity(item.entityId)"
                      >
                        <ArrowUpRight class="size-4" />
                        查看实体
                      </Button>
                      <Button
                        v-if="item.sourceSessionId"
                        type="button"
                        variant="outline"
                        size="sm"
                        data-test="open-recent-provenance-session"
                        @click="openConversation(item.sourceSessionId)"
                      >
                        <MessageSquare class="size-4" />
                        打开会话
                      </Button>
                      <Button
                        v-if="item.sourceKnowledgeBaseId && item.sourceDocumentId"
                        type="button"
                        variant="outline"
                        size="sm"
                        data-test="open-recent-provenance-document"
                        @click="openKnowledgeBaseDocument(item.sourceKnowledgeBaseId, item.sourceDocumentId)"
                      >
                        <ArrowUpRight class="size-4" />
                        查看文档
                      </Button>
                      <Button
                        v-else-if="item.sourceKnowledgeBaseId"
                        type="button"
                        variant="outline"
                        size="sm"
                        data-test="open-recent-provenance-kb"
                        @click="openKnowledgeBase(item.sourceKnowledgeBaseId)"
                      >
                        <ArrowUpRight class="size-4" />
                        查看知识库
                      </Button>
                    </div>
                  </div>
                </article>
              </div>
            </div>
          </PageSection>

          <PageSection title="关联知识库" description="这里会展示服务当前 Datastore 的全部知识库，包含系统自动维护的内部知识库。">
            <div v-if="relatedKnowledgeBases.length === 0" class="detail-card p-4 text-sm text-muted-foreground">
              当前还没有知识库挂载这个 Datastore。
            </div>
            <div v-else class="grid gap-4 md:grid-cols-2">
              <article
                v-for="knowledgeBase in relatedKnowledgeBases"
                :key="knowledgeBase.id"
                class="detail-card cursor-pointer p-5 transition-colors hover:border-primary/35 hover:bg-muted/20"
                data-test="related-kb-card"
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

    <ConfirmDialog
      v-model:show="deleteDialogOpen"
      title="确认删除 Datastore"
      :message="datastore ? `确定要删除 Datastore「${datastore.name}」吗？集合内文档也会一并删除。` : ''"
      :confirm-label="deletingDatastore ? '删除中...' : '删除'"
      confirm-variant="destructive"
      @confirm="handleDeleteDatastore"
      @cancel="deleteDialogOpen = false"
    />
  </div>
</template>
