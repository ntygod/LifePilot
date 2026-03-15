<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  CheckCircle,
  Clock,
  Database,
  Download,
  Eye,
  FileIcon,
  FileText,
  Files,
  Filter,
  RefreshCw,
  Search,
  TestTube,
  Trash2,
  TriangleAlert,
  Upload,
  XCircle,
} from 'lucide-vue-next'
import { knowledgeBaseApi } from '@/api/client'
import type {
  KbDocument,
  KbStats,
  ProcessingLog,
  TestRetrievalResult,
  UploadFileItem,
} from '@/types'
import { SUPPORTED_TYPES } from '@/utils/fileUtils'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import UploadProgress from '@/components/knowledge/UploadProgress.vue'
import DropZone from '@/components/knowledge/DropZone.vue'
import Breadcrumb from '@/components/global/Breadcrumb.vue'
import type { BreadcrumbItem } from '@/components/global/Breadcrumb.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import PageSection from '@/components/layout/PageSection.vue'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { Skeleton } from '@/components/ui/skeleton'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useUiStore } from '@/stores/ui'

const route = useRoute()
const router = useRouter()
const store = useKnowledgeBaseStore()
const uiStore = useUiStore()

const kbId = computed(() => route.params.id as string)
const kb = ref<{
  id: string
  name: string
  description?: string
  tags?: string[]
  embeddingModel?: string
  rerankerModel?: string
  updatedAt?: string
} | null>(null)
const stats = ref<KbStats | null>(null)
const documents = ref<KbDocument[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

const searchQuery = ref('')
const filterType = ref<string>('all')
const filterStatus = ref<string>('all')
const filterTimeRange = ref<string>('all')
const showFilters = ref(false)

const selectedDoc = ref<KbDocument | null>(null)
const showLogs = ref(false)
const processingLogs = ref<ProcessingLog[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const deleteTarget = ref<{ doc: KbDocument } | null>(null)

const showTestRetrieval = ref(false)
const testQuery = ref('')
const testResult = ref<TestRetrievalResult | null>(null)
const testing = ref(false)

const uploadQueue = ref<UploadFileItem[]>([])
const isUploading = ref(false)

const selectedDocIds = ref<Set<string>>(new Set())
const showBatchDeleteConfirm = ref(false)
const batchDeleting = ref(false)

const acceptTypes = Array.from(SUPPORTED_TYPES)

const breadcrumbItems = computed<BreadcrumbItem[]>(() => [
  { label: '知识库', to: { name: 'knowledgeBases' } },
  { label: kb.value?.name ?? '详情' },
])

const fileTypeMap: Record<string, string> = {
  'application/pdf': 'PDF',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'Word',
  'application/msword': 'Word',
  'text/markdown': 'Markdown',
  'text/plain': 'Text',
  'text/html': 'HTML',
}

const statusMap: Record<KbDocument['status'], { label: string; icon: any; class: string }> = {
  UPLOADING: { label: '上传中', icon: Clock, class: 'border-amber-200/70 bg-amber-50 text-amber-800 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-300' },
  PARSING: { label: '解析中', icon: RefreshCw, class: 'border-sky-200/70 bg-sky-50 text-sky-800 dark:border-sky-500/20 dark:bg-sky-500/10 dark:text-sky-300' },
  CHUNKING: { label: '分块中', icon: RefreshCw, class: 'border-sky-200/70 bg-sky-50 text-sky-800 dark:border-sky-500/20 dark:bg-sky-500/10 dark:text-sky-300' },
  INDEXING: { label: '索引中', icon: RefreshCw, class: 'border-sky-200/70 bg-sky-50 text-sky-800 dark:border-sky-500/20 dark:bg-sky-500/10 dark:text-sky-300' },
  EXTRACTING: { label: '提取中', icon: RefreshCw, class: 'border-sky-200/70 bg-sky-50 text-sky-800 dark:border-sky-500/20 dark:bg-sky-500/10 dark:text-sky-300' },
  READY: { label: '已完成', icon: CheckCircle, class: 'border-emerald-200/70 bg-emerald-50 text-emerald-800 dark:border-emerald-500/20 dark:bg-emerald-500/10 dark:text-emerald-300' },
  UPDATING: { label: '更新中', icon: RefreshCw, class: 'border-sky-200/70 bg-sky-50 text-sky-800 dark:border-sky-500/20 dark:bg-sky-500/10 dark:text-sky-300' },
  DELETING: { label: '删除中', icon: RefreshCw, class: 'border-amber-200/70 bg-amber-50 text-amber-800 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-300' },
  ERROR: { label: '失败', icon: XCircle, class: 'border-destructive/25 bg-destructive/8 text-destructive' },
}

const showDeleteConfirm = computed({
  get: () => deleteTarget.value !== null,
  set: (value: boolean) => {
    if (!value) {
      deleteTarget.value = null
    }
  },
})

const filteredDocuments = computed(() => {
  let result = [...documents.value]

  if (searchQuery.value.trim()) {
    const query = searchQuery.value.toLowerCase()
    result = result.filter(doc => doc.fileName.toLowerCase().includes(query))
  }

  if (filterType.value !== 'all') {
    result = result.filter(doc => {
      const type = fileTypeMap[doc.mimeType] || '其他'
      return type.toLowerCase() === filterType.value.toLowerCase()
    })
  }

  if (filterStatus.value !== 'all') {
    result = result.filter(doc => doc.status === filterStatus.value)
  }

  if (filterTimeRange.value !== 'all') {
    const now = Date.now()
    const days = filterTimeRange.value === '7d' ? 7 : 30
    const cutoff = now - days * 24 * 60 * 60 * 1000
    result = result.filter(doc => new Date(doc.createdAt).getTime() >= cutoff)
  }

  return result
})

const allFileTypes = computed(() => {
  const types = new Set<string>()
  documents.value.forEach(doc => {
    types.add(fileTypeMap[doc.mimeType] || '其他')
  })
  return Array.from(types)
})

const isAllSelected = computed(() => (
  filteredDocuments.value.length > 0
  && filteredDocuments.value.every(doc => selectedDocIds.value.has(doc.id))
))

const hasSelection = computed(() => selectedDocIds.value.size > 0)

const hasDocumentFilters = computed(() => (
  Boolean(searchQuery.value.trim())
  || filterType.value !== 'all'
  || filterStatus.value !== 'all'
  || filterTimeRange.value !== 'all'
))

const documentCount = computed(() => stats.value?.documentCount ?? documents.value.length)
const chunkCount = computed(() => stats.value?.totalChunks ?? documents.value.reduce((sum, doc) => sum + doc.chunkCount, 0))
const totalSize = computed(() => stats.value?.totalSize ?? documents.value.reduce((sum, doc) => sum + doc.fileSize, 0))
const processingCount = computed(() => {
  if (stats.value) return stats.value.processingDocuments
  return documents.value.filter(doc => ['UPLOADING', 'PARSING', 'CHUNKING', 'INDEXING', 'EXTRACTING', 'UPDATING', 'DELETING'].includes(doc.status)).length
})
const errorCount = computed(() => {
  if (stats.value) return stats.value.errorDocuments
  return documents.value.filter(doc => doc.status === 'ERROR').length
})

const indexStatusLabel = computed(() => {
  if (!stats.value) return '未加载'
  if (stats.value.indexStatus === 'HEALTHY') return '健康'
  if (stats.value.indexStatus === 'PROCESSING') return '处理中'
  return '部分失败'
})

const indexStatusClass = computed(() => {
  if (!stats.value) return 'status-btn-inactive'
  if (stats.value.indexStatus === 'HEALTHY') return 'status-btn-active'
  if (stats.value.indexStatus === 'PROCESSING') return 'border-sky-200/70 bg-sky-50 text-sky-800 dark:border-sky-500/20 dark:bg-sky-500/10 dark:text-sky-300'
  return 'border-amber-200/70 bg-amber-50 text-amber-800 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-300'
})

const acceptedFileTypes = computed(() => ['PDF', 'DOCX', 'DOC', 'MD', 'TXT', 'HTML'])

onMounted(async () => {
  await loadData()
})

watch(() => route.params.id, async () => {
  selectedDocIds.value = new Set()
  testResult.value = null
  uploadQueue.value = []
  await loadData()
})

async function loadData() {
  if (!kbId.value) return
  loading.value = true
  error.value = null

  try {
    const [kbResponse, statsResponse, documentsResponse] = await Promise.all([
      knowledgeBaseApi.get(kbId.value),
      knowledgeBaseApi.getStats(kbId.value),
      knowledgeBaseApi.listDocuments(kbId.value),
    ])
    kb.value = kbResponse
    stats.value = statsResponse
    documents.value = documentsResponse
  } catch (event: any) {
    error.value = event.message ?? '加载失败'
  } finally {
    loading.value = false
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString('zh-CN')
}

function triggerUpload() {
  fileInput.value?.click()
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const files = input.files
  if (!files || !kbId.value) return
  await handleBatchUpload(Array.from(files))
  input.value = ''
}

function handleDropFiles(files: File[]) {
  void handleBatchUpload(files)
}

function handleRejectFiles(fileNames: string[]) {
  uiStore.showToast('error', `已跳过不支持的文件格式：${fileNames.join('、')}`)
}

async function handleBatchUpload(files: File[]) {
  if (!kbId.value || files.length === 0) return

  const items: UploadFileItem[] = files.map(file => ({
    id: crypto.randomUUID(),
    file,
    fileName: file.name,
    status: 'waiting',
  }))

  uploadQueue.value = items
  isUploading.value = true
 
  for (const item of items) {
    item.status = 'uploading'
    try {
      await knowledgeBaseApi.uploadDocument(kbId.value, item.file)
      item.status = 'success'
    } catch (event: any) {
      item.status = 'error'
      item.errorMessage = event.message ?? '上传失败'
    }
  }

  isUploading.value = false
  await loadData()
}

async function handleRetryUpload(fileId: string) {
  const item = uploadQueue.value.find(file => file.id === fileId)
  if (!item) return

  item.status = 'uploading'
  item.errorMessage = undefined

  try {
    await knowledgeBaseApi.uploadDocument(kbId.value, item.file)
    item.status = 'success'
    await loadData()
  } catch (event: any) {
    item.status = 'error'
    item.errorMessage = event.message ?? '上传失败'
  }
}

function handleDismissUpload() {
  uploadQueue.value = []
}

function toggleDocSelection(docId: string) {
  const nextValue = new Set(selectedDocIds.value)
  if (nextValue.has(docId)) {
    nextValue.delete(docId)
  } else {
    nextValue.add(docId)
  }
  selectedDocIds.value = nextValue
}

function toggleSelectAll() {
  if (isAllSelected.value) {
    selectedDocIds.value = new Set()
    return
  }
  selectedDocIds.value = new Set(filteredDocuments.value.map(document => document.id))
}

async function handleBatchDelete() {
  batchDeleting.value = true
  let failCount = 0
  const ids = Array.from(selectedDocIds.value)

  for (const docId of ids) {
    try {
      await knowledgeBaseApi.deleteDocument(kbId.value, docId)
    } catch {
      failCount += 1
    }
  }

  selectedDocIds.value = new Set()
  showBatchDeleteConfirm.value = false
  batchDeleting.value = false

  if (failCount > 0) {
    uiStore.showToast('error', `部分文档删除失败（${failCount} 个）`)
  }

  await loadData()
}

async function handleRetry(doc: KbDocument) {
  try {
    await knowledgeBaseApi.retryDocument(kbId.value, doc.id)
    await loadData()
  } catch {
    uiStore.showToast('error', '重试失败')
  }
}

async function handleRechunk(doc: KbDocument) {
  try {
    await knowledgeBaseApi.rechunkDocument(kbId.value, doc.id)
    await loadData()
  } catch (event: any) {
    error.value = event.message ?? '重新分块失败'
  }
}

async function handleDelete() {
  if (!deleteTarget.value) return

  try {
    await store.removeDocument(kbId.value, deleteTarget.value.doc.id)
    deleteTarget.value = null
    await loadData()
  } catch (event: any) {
    error.value = event.message ?? '删除失败'
  }
}

function viewDocument(doc: KbDocument) {
  router.push(`/knowledge-bases/${kbId.value}/documents/${doc.id}`)
}

async function viewLogs(doc: KbDocument) {
  selectedDoc.value = doc
  showLogs.value = true
  try {
    processingLogs.value = await knowledgeBaseApi.getDocumentLogs(kbId.value, doc.id)
  } catch {
    processingLogs.value = []
  }
}

async function downloadDocument(doc: KbDocument) {
  try {
    const blob = await knowledgeBaseApi.downloadDocument(kbId.value, doc.id)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = doc.fileName
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (event: any) {
    error.value = event.message ?? '下载失败'
  }
}

async function testRetrieval() {
  if (!testQuery.value.trim()) return

  testing.value = true
  testResult.value = null

  try {
    testResult.value = await knowledgeBaseApi.testRetrieval(kbId.value, testQuery.value)
  } catch (event: any) {
    error.value = event.message ?? '测试检索失败'
  } finally {
    testing.value = false
  }
}

function clearDocumentFilters() {
  searchQuery.value = ''
  filterType.value = 'all'
  filterStatus.value = 'all'
  filterTimeRange.value = 'all'
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack">
        <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <Breadcrumb :items="breadcrumbItems" class="min-w-0" />
          <Button type="button" variant="ghost" class="w-fit" @click="router.push('/knowledge-bases')">
            <ArrowLeft class="size-4" />
            返回知识库列表
          </Button>
        </div>

        <PageHeader
          eyebrow="知识库"
          :title="kb?.name || '知识库详情'"
          :description="kb?.description || '继续上传文档、处理失败项，或直接验证当前知识库的召回效果。'"
        >
          <template #actions>
            <Button type="button" variant="outline" @click="loadData">
              <RefreshCw class="size-4" />
              刷新
            </Button>
            <Button type="button" variant="outline" @click="showTestRetrieval = !showTestRetrieval">
              <TestTube class="size-4" />
              {{ showTestRetrieval ? '收起检索验证' : '检索验证' }}
            </Button>
            <Button type="button" @click="triggerUpload">
              <Upload class="size-4" />
              上传文档
            </Button>
            <input
              ref="fileInput"
              type="file"
              multiple
              accept=".pdf,.docx,.doc,.md,.txt,.html,.htm"
              class="hidden"
              @change="handleFileChange"
            />
          </template>

          <template #meta>
            <MetricCard label="文档总数" :value="documentCount" hint="当前知识库已收录的文档数量。">
              <template #icon>
                <Database class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="分块总数" :value="chunkCount" hint="已可用于检索的文本分段数量。">
              <template #icon>
                <FileText class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="总大小" :value="formatSize(totalSize)" hint="文档总大小，可用来估算当前规模。">
              <template #icon>
                <Files class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="索引状态" :value="indexStatusLabel" hint="根据当前统计汇总出来的索引健康情况。">
              <template #icon>
                <RefreshCw class="size-5" />
              </template>
            </MetricCard>
          </template>
        </PageHeader>

        <div class="flex flex-wrap gap-2">
          <Badge variant="outline" :class="indexStatusClass">
            {{ indexStatusLabel }}
          </Badge>
          <Badge variant="outline" class="status-btn-inactive">
            处理中 {{ processingCount }}
          </Badge>
          <Badge
            v-if="errorCount > 0"
            variant="outline"
            class="border-destructive/25 bg-destructive/8 text-destructive"
          >
            失败 {{ errorCount }}
          </Badge>
        </div>

        <StatePanel
          v-if="error && !loading && kb"
          title="当前知识库有一项操作未完成"
          :description="error"
          tone="danger"
        >
          <template #icon>
            <TriangleAlert class="size-5" />
          </template>
          <template #actions>
            <Button type="button" variant="outline" @click="error = null">
              关闭提示
            </Button>
            <Button type="button" @click="loadData">
              重新加载
            </Button>
          </template>
        </StatePanel>

        <div class="flex flex-col gap-5 xl:flex-row">
          <div class="min-w-0 flex-1 space-y-5">
            <template v-if="loading">
              <div class="grid grid-cols-1 gap-5">
                <div class="detail-card p-5">
                  <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
                    <Skeleton v-for="index in 4" :key="index" class="h-28 rounded-[calc(var(--radius)+6px)]" />
                  </div>
                </div>
                <div class="detail-card p-5">
                  <div class="space-y-3">
                    <Skeleton class="h-10 w-full rounded-2xl" />
                    <Skeleton class="h-10 w-full rounded-2xl" />
                    <Skeleton class="h-10 w-full rounded-2xl" />
                    <Skeleton class="h-64 w-full rounded-[calc(var(--radius)+6px)]" />
                  </div>
                </div>
              </div>
            </template>

            <StatePanel
              v-else-if="!kb && error"
              title="知识库详情加载失败"
              :description="error"
              tone="danger"
            >
              <template #icon>
                <Database class="size-5" />
              </template>
              <template #actions>
                <Button type="button" variant="outline" @click="router.push('/knowledge-bases')">
                  返回列表
                </Button>
                <Button type="button" @click="loadData">
                  重试
                </Button>
              </template>
            </StatePanel>

            <template v-else-if="kb">
              <PageSection
                eyebrow="库信息"
                title="基础配置"
                description="先确认这个库的模型、格式范围和当前处理压力，再进入文档操作。"
              >
                <div class="grid gap-4 lg:grid-cols-[minmax(0,1.25fr)_minmax(280px,0.75fr)]">
                  <div class="detail-card p-4 sm:p-5">
                    <div class="space-y-4">
                      <div>
                        <div class="surface-label mb-3 text-[0.68rem]">知识库说明</div>
                        <p class="text-sm leading-7 text-foreground">
                          {{ kb.description || '这个知识库暂时还没有补充说明。' }}
                        </p>
                      </div>

                      <div
                        v-if="kb.tags?.length"
                        class="border-t border-border/60 pt-4"
                      >
                        <div class="surface-label mb-3 text-[0.68rem]">标签</div>
                        <div class="flex flex-wrap gap-2">
                          <Badge v-for="tag in kb.tags" :key="tag" variant="secondary">
                            {{ tag }}
                          </Badge>
                        </div>
                      </div>
                    </div>
                  </div>

                  <div class="rounded-[calc(var(--radius)+2px)] border border-dashed border-border/60 bg-background/48 p-4">
                    <div class="space-y-4">
                      <div>
                        <div class="surface-label text-[0.68rem]">当前配置</div>
                        <p class="mt-2 text-sm leading-6 text-muted-foreground">
                          先确认向量模型、支持格式和更新时间，再回到文档列表处理上传、重试和删除动作。
                        </p>
                      </div>

                      <div class="flex flex-wrap gap-2 text-xs text-muted-foreground">
                        <span class="surface-chip">向量模型：{{ kb.embeddingModel || '未配置' }}</span>
                        <span class="surface-chip">精排模型：{{ kb.rerankerModel || '使用全局配置' }}</span>
                        <span class="surface-chip">更新于 {{ kb.updatedAt ? formatDate(kb.updatedAt) : '暂无' }}</span>
                        <span class="surface-chip">{{ hasDocumentFilters ? '文档筛选已启用' : '文档筛选未启用' }}</span>
                      </div>

                      <div class="space-y-2">
                        <div class="surface-label text-[0.68rem]">支持格式</div>
                        <div class="flex flex-wrap gap-2 text-xs text-muted-foreground">
                          <span v-for="type in acceptedFileTypes" :key="type" class="surface-chip">
                            {{ type }}
                          </span>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </PageSection>

              <div v-if="uploadQueue.length > 0">
                <UploadProgress
                  :files="uploadQueue"
                  @retry="handleRetryUpload"
                  @dismiss="handleDismissUpload"
                />
              </div>

              <PageSection
                eyebrow="文档"
                title="文档列表"
                :description="hasDocumentFilters ? '文档列表已按关键字、类型、状态或时间筛选。' : '上传文档后，可在这里筛选、批量删除、重试和查看日志。'"
              >
                <template #actions>
                  <div class="text-sm text-muted-foreground">
                    当前结果 {{ filteredDocuments.length }}
                  </div>
                </template>

                <DropZone
                  :accept-types="acceptTypes"
                  :disabled="isUploading"
                  @drop="handleDropFiles"
                  @reject="handleRejectFiles"
                >
                  <div class="detail-card overflow-hidden">
                    <div class="border-b border-border/70 px-5 py-5">
                      <div class="flex flex-col gap-4">
                        <div class="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
                          <div class="flex flex-1 flex-col gap-3 lg:flex-row lg:items-center">
                <div class="relative min-w-[220px] flex-1 xl:max-w-[36rem]">
                              <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                              <Input
                                v-model="searchQuery"
                                type="search"
                                placeholder="搜索文档名称"
                                class="pl-9"
                              />
                            </div>

                            <div class="flex flex-wrap items-center gap-3">
                              <Button
                                type="button"
                                variant="outline"
                                :class="showFilters ? 'bg-accent text-accent-foreground' : ''"
                                @click="showFilters = !showFilters"
                              >
                                <Filter class="size-4" />
                                筛选
                              </Button>
                            </div>
                          </div>

                          <div class="flex items-center gap-3 text-sm text-muted-foreground">
                            <span>已选择 {{ selectedDocIds.size }}</span>
                            <Button v-if="hasDocumentFilters" type="button" variant="ghost" @click="clearDocumentFilters">
                              清空筛选
                            </Button>
                          </div>
                        </div>

                        <div
                          v-if="showFilters"
                          class="grid gap-4 rounded-[calc(var(--radius)+4px)] border border-border/70 bg-background/55 p-4 lg:grid-cols-3"
                        >
                          <div class="space-y-2">
                            <Label class="text-xs text-muted-foreground">文件类型</Label>
                            <Select v-model="filterType">
                              <SelectTrigger class="w-full">
                                <SelectValue placeholder="全部类型" />
                              </SelectTrigger>
                              <SelectContent>
                                <SelectItem value="all">全部类型</SelectItem>
                                <SelectItem v-for="type in allFileTypes" :key="type" :value="type">{{ type }}</SelectItem>
                              </SelectContent>
                            </Select>
                          </div>

                          <div class="space-y-2">
                            <Label class="text-xs text-muted-foreground">处理状态</Label>
                            <Select v-model="filterStatus">
                              <SelectTrigger class="w-full">
                                <SelectValue placeholder="全部状态" />
                              </SelectTrigger>
                              <SelectContent>
                                <SelectItem value="all">全部状态</SelectItem>
                                <SelectItem value="UPLOADING">上传中</SelectItem>
                                <SelectItem value="PARSING">解析中</SelectItem>
                                <SelectItem value="CHUNKING">分块中</SelectItem>
                                <SelectItem value="INDEXING">索引中</SelectItem>
                                <SelectItem value="EXTRACTING">提取中</SelectItem>
                                <SelectItem value="READY">已完成</SelectItem>
                                <SelectItem value="ERROR">失败</SelectItem>
                              </SelectContent>
                            </Select>
                          </div>

                          <div class="space-y-2">
                            <Label class="text-xs text-muted-foreground">上传时间</Label>
                            <Select v-model="filterTimeRange">
                              <SelectTrigger class="w-full">
                                <SelectValue placeholder="全部时间" />
                              </SelectTrigger>
                              <SelectContent>
                                <SelectItem value="all">全部时间</SelectItem>
                                <SelectItem value="7d">最近 7 天</SelectItem>
                                <SelectItem value="30d">最近 30 天</SelectItem>
                              </SelectContent>
                            </Select>
                          </div>
                        </div>
                      </div>
                    </div>

                    <div
                      v-if="hasSelection"
                      class="border-b border-border/70 bg-primary/6 px-5 py-3"
                    >
                      <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                        <span class="text-sm text-foreground">
                          已选择 <span class="font-semibold">{{ selectedDocIds.size }}</span> 个文档
                        </span>
                        <Button
                          type="button"
                          variant="destructive"
                          size="sm"
                          :disabled="batchDeleting"
                          @click="showBatchDeleteConfirm = true"
                        >
                          <Trash2 class="size-4" />
                          {{ batchDeleting ? '删除中...' : '批量删除' }}
                        </Button>
                      </div>
                    </div>

                    <div v-if="filteredDocuments.length === 0" class="px-5 py-10">
                      <StatePanel
                        :title="hasDocumentFilters ? '没有匹配的文档' : '还没有上传文档'"
                        :description="hasDocumentFilters
                          ? '调整筛选条件后再试，或者清空筛选查看全部文档。'
                          : '把文件拖到这里，或使用上方按钮选择本地文件上传。'"
                      >
                        <template #icon>
                          <Files class="size-5" />
                        </template>
                        <template #actions>
                          <Button v-if="hasDocumentFilters" type="button" variant="outline" @click="clearDocumentFilters">
                            清空筛选
                          </Button>
                          <Button v-else type="button" @click="triggerUpload">
                            <Upload class="size-4" />
                            上传文档
                          </Button>
                        </template>
                      </StatePanel>
                    </div>

                    <div v-else class="overflow-x-auto">
                      <table class="min-w-full border-separate border-spacing-0">
                        <thead class="bg-muted/35">
                          <tr>
                            <th class="w-12 px-5 py-4 text-left">
                              <Checkbox
                                :model-value="isAllSelected"
                                @update:model-value="toggleSelectAll"
                              />
                            </th>
                            <th class="px-2 py-4 text-left text-xs font-semibold uppercase tracking-[0.18em] text-muted-foreground">文档</th>
                            <th class="px-2 py-4 text-left text-xs font-semibold uppercase tracking-[0.18em] text-muted-foreground">类型</th>
                            <th class="px-2 py-4 text-left text-xs font-semibold uppercase tracking-[0.18em] text-muted-foreground">大小</th>
                            <th class="px-2 py-4 text-left text-xs font-semibold uppercase tracking-[0.18em] text-muted-foreground">上传时间</th>
                            <th class="px-2 py-4 text-left text-xs font-semibold uppercase tracking-[0.18em] text-muted-foreground">状态</th>
                            <th class="px-2 py-4 text-left text-xs font-semibold uppercase tracking-[0.18em] text-muted-foreground">分块</th>
                            <th class="px-5 py-4 text-right text-xs font-semibold uppercase tracking-[0.18em] text-muted-foreground">操作</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr
                            v-for="doc in filteredDocuments"
                            :key="doc.id"
                            class="group border-t border-border/60 transition-colors hover:bg-muted/25"
                          >
                            <td class="px-5 py-4 align-top">
                              <Checkbox
                                :model-value="selectedDocIds.has(doc.id)"
                                @update:model-value="toggleDocSelection(doc.id)"
                              />
                            </td>
                            <td class="px-2 py-4 align-top">
                              <div class="flex min-w-[240px] items-start gap-3">
                                <div class="mt-0.5 flex size-10 shrink-0 items-center justify-center rounded-2xl border border-border/70 bg-background/75 text-primary">
                                  <FileIcon class="size-4" />
                                </div>
                                <div class="min-w-0 space-y-1">
                                  <div class="truncate text-sm font-medium text-foreground">
                                    {{ doc.fileName }}
                                  </div>
                                  <div v-if="doc.errorMessage" class="text-xs text-destructive">
                                    {{ doc.errorMessage }}
                                  </div>
                                </div>
                              </div>
                            </td>
                            <td class="px-2 py-4 align-top text-sm text-muted-foreground">
                              {{ fileTypeMap[doc.mimeType] || '其他' }}
                            </td>
                            <td class="px-2 py-4 align-top text-sm text-muted-foreground">
                              {{ formatSize(doc.fileSize) }}
                            </td>
                            <td class="px-2 py-4 align-top text-sm text-muted-foreground">
                              {{ formatDate(doc.createdAt) }}
                            </td>
                            <td class="px-2 py-4 align-top">
                              <Badge variant="outline" :class="statusMap[doc.status]?.class">
                                <component :is="statusMap[doc.status]?.icon" class="size-3.5" />
                                {{ statusMap[doc.status]?.label ?? doc.status }}
                              </Badge>
                            </td>
                            <td class="px-2 py-4 align-top text-sm text-muted-foreground">
                              {{ doc.chunkCount }}
                            </td>
                            <td class="px-5 py-4 align-top">
                              <div class="flex items-center justify-end gap-1 opacity-80 transition-opacity group-hover:opacity-100">
                                <Button type="button" variant="ghost" size="icon-sm" class="size-8" title="查看详情" @click="viewDocument(doc)">
                                  <Eye class="size-4" />
                                </Button>
                                <Button type="button" variant="ghost" size="icon-sm" class="size-8" title="下载" @click="downloadDocument(doc)">
                                  <Download class="size-4" />
                                </Button>
                                <Button v-if="doc.status === 'ERROR'" type="button" variant="ghost" size="icon-sm" class="size-8" title="重试" @click="handleRetry(doc)">
                                  <RefreshCw class="size-4" />
                                </Button>
                                <Button v-if="doc.status === 'READY'" type="button" variant="ghost" size="icon-sm" class="size-8" title="重新分块" @click="handleRechunk(doc)">
                                  <RefreshCw class="size-4" />
                                </Button>
                                <Button type="button" variant="ghost" size="icon-sm" class="size-8" title="查看日志" @click="viewLogs(doc)">
                                  <FileText class="size-4" />
                                </Button>
                                <Button
                                  type="button"
                                  variant="ghost"
                                  size="icon-sm"
                                  class="size-8 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
                                  title="删除"
                                  @click="deleteTarget = { doc }"
                                >
                                  <Trash2 class="size-4" />
                                </Button>
                              </div>
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </div>
                </DropZone>
              </PageSection>

              <PageSection
                v-if="showTestRetrieval"
                eyebrow="验证"
                title="检索验证"
                description="输入测试问题，快速查看命中的文档分块和基于知识库的回答。"
              >
                <div class="space-y-4">
                  <div class="flex flex-col gap-3 sm:flex-row">
                    <Input
                      v-model="testQuery"
                      placeholder="输入测试问题"
                      @keyup.enter="testRetrieval"
                    />
                    <Button
                      type="button"
                      class="sm:w-[140px]"
                      :disabled="testing || !testQuery.trim()"
                      @click="testRetrieval"
                    >
                      {{ testing ? '检索中...' : '开始检索' }}
                    </Button>
                  </div>

                  <StatePanel
                    v-if="testResult && testResult.chunks.length === 0 && !testResult.answer"
                    title="没有命中结果"
                    description="当前问题没有召回到可用分块，可以尝试换一个描述方式或补充更多文档。"
                  >
                    <template #icon>
                      <Search class="size-5" />
                    </template>
                  </StatePanel>

                  <div v-else-if="testResult" class="grid gap-4 xl:grid-cols-[minmax(0,1.2fr)_minmax(280px,0.8fr)]">
                    <div class="space-y-3">
                      <div class="surface-label text-[0.68rem]">命中的分块（{{ testResult.chunks.length }}）</div>
                      <div class="space-y-3">
                        <article
                          v-for="chunk in testResult.chunks"
                          :key="chunk.chunkId"
                          class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/72 p-4"
                        >
                          <div class="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
                            <div class="text-sm font-medium text-foreground">{{ chunk.documentName }}</div>
                            <Badge variant="outline" class="w-fit">
                              相似度 {{ (chunk.similarity * 100).toFixed(1) }}%
                            </Badge>
                          </div>
                          <p class="mt-3 text-sm leading-6 text-muted-foreground">
                            {{ chunk.content }}
                          </p>
                        </article>
                      </div>
                    </div>

                    <div class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-primary/6 p-5">
                      <div class="surface-label mb-3 text-[0.68rem]">检索回答</div>
                      <p class="whitespace-pre-wrap text-sm leading-7 text-foreground">
                        {{ testResult.answer || '这次检索没有生成回答，可先查看命中的分块内容。' }}
                      </p>
                    </div>
                  </div>
                </div>
              </PageSection>
            </template>
          </div>
        </div>
      </div>
    </PageContainer>

    <Sheet :open="showLogs && !!selectedDoc" @update:open="(value: boolean) => { if (!value) { showLogs = false; selectedDoc = null } }">
      <SheetContent side="right" class="w-full overflow-y-auto sm:max-w-[700px]">
        <SheetHeader>
          <SheetTitle>处理日志</SheetTitle>
          <SheetDescription>{{ selectedDoc?.fileName }}</SheetDescription>
        </SheetHeader>

        <div class="mt-5">
          <StatePanel
            v-if="processingLogs.length === 0"
            title="暂无日志"
            description="当前文档还没有返回可展示的处理日志。"
          >
            <template #icon>
              <FileText class="size-5" />
            </template>
          </StatePanel>

          <div v-else class="space-y-3">
            <article
              v-for="log in processingLogs"
              :key="log.id"
              class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/70 p-4"
              :class="log.stage === 'ERROR' ? 'border-destructive/20 bg-destructive/6' : ''"
            >
              <div class="flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between">
                <div class="text-sm font-medium text-foreground">{{ log.stage }}</div>
                <div class="text-xs text-muted-foreground">{{ formatDate(log.timestamp) }}</div>
              </div>
              <p class="mt-3 text-sm leading-6 text-foreground">{{ log.message }}</p>
              <p v-if="log.error" class="mt-2 text-xs text-destructive">{{ log.error }}</p>
            </article>
          </div>
        </div>
      </SheetContent>
    </Sheet>

    <ConfirmDialog
      v-model:show="showDeleteConfirm"
      title="确认删除"
      :message="deleteTarget ? `确定要删除文档「${deleteTarget.doc.fileName}」吗？此操作不可撤销。` : ''"
      confirm-label="删除"
      confirm-variant="destructive"
      @confirm="handleDelete"
      @cancel="deleteTarget = null"
    />

    <ConfirmDialog
      v-model:show="showBatchDeleteConfirm"
      title="批量删除文档"
      :message="`确定要删除选中的 ${selectedDocIds.size} 个文档吗？此操作不可撤销。`"
      confirm-label="删除"
      confirm-variant="destructive"
      @confirm="handleBatchDelete"
      @cancel="showBatchDeleteConfirm = false"
    />
  </div>
</template>
