<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { knowledgeBaseApi } from '@/api/client'
import type { KbDocument, KbStats, ProcessingLog, TestRetrievalResult, UploadFileItem } from '@/types'
import { SUPPORTED_TYPES } from '@/utils/fileUtils'
import DropZone from '@/components/knowledge/DropZone.vue'
import UploadProgress from '@/components/knowledge/UploadProgress.vue'
import LoadingSpinner from '@/components/common/LoadingSpinner.vue'
import ErrorState from '@/components/common/ErrorState.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import {
  Search, Filter, Upload, FileText, RefreshCw, Trash2, Eye,
  Download, CheckCircle, Clock, XCircle, FileIcon,
  ArrowLeft, TestTube, X
} from 'lucide-vue-next'

const route = useRoute()
const router = useRouter()
const store = useKnowledgeBaseStore()

const kbId = computed(() => route.params.id as string)
const kb = ref<{ id: string; name: string; description?: string; tags?: string[] } | null>(null)
const stats = ref<KbStats | null>(null)
const documents = ref<KbDocument[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

// 搜索和过滤
const searchQuery = ref('')
const filterType = ref<string>('all')
const filterStatus = ref<string>('all')
const filterTimeRange = ref<'all' | '7d' | '30d'>('all')
const showFilters = ref(false)

// 文档操作
const selectedDoc = ref<KbDocument | null>(null)
const showLogs = ref(false)
const processingLogs = ref<ProcessingLog[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const deleteTarget = ref<{ doc: KbDocument } | null>(null)

// 测试检索
const showTestRetrieval = ref(false)
const testQuery = ref('')
const testResult = ref<TestRetrievalResult | null>(null)
const testing = ref(false)

// 拖拽上传队列
const uploadQueue = ref<UploadFileItem[]>([])
const isUploading = ref(false)

// Toast 提示
const toastMessage = ref('')
const toastVisible = ref(false)
let toastTimer: ReturnType<typeof setTimeout> | null = null

// 批量选择
const selectedDocIds = ref<Set<string>>(new Set())
const showBatchDeleteConfirm = ref(false)
const batchDeleting = ref(false)

// 支持的 MIME 类型列表（传给 DropZone）
const acceptTypes = Array.from(SUPPORTED_TYPES)

// 文件类型映射
const fileTypeMap: Record<string, string> = {
  'application/pdf': 'PDF',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'Word',
  'application/msword': 'Word',
  'text/markdown': 'Markdown',
  'text/plain': 'Text',
  'text/html': 'HTML'
}

const statusMap: Record<string, { label: string; icon: any; class: string }> = {
  PENDING: { label: '等待中', icon: Clock, class: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/20 dark:text-yellow-400' },
  PROCESSING: { label: '处理中', icon: RefreshCw, class: 'bg-blue-100 text-blue-800 dark:bg-blue-900/20 dark:text-blue-400' },
  COMPLETED: { label: '已完成', icon: CheckCircle, class: 'bg-green-100 text-green-800 dark:bg-green-900/20 dark:text-green-400' },
  ERROR: { label: '失败', icon: XCircle, class: 'bg-red-100 text-red-800 dark:bg-red-900/20 dark:text-red-400' }
}

onMounted(async () => {
  await loadData()
})

watch(() => route.params.id, async () => {
  await loadData()
})

async function loadData() {
  if (!kbId.value) return
  loading.value = true
  error.value = null
  try {
    kb.value = await knowledgeBaseApi.get(kbId.value)
    stats.value = await knowledgeBaseApi.getStats(kbId.value)
    documents.value = await knowledgeBaseApi.listDocuments(kbId.value)
  } catch (e: any) {
    error.value = e.message ?? '加载失败'
  } finally {
    loading.value = false
  }
}

// 过滤后的文档列表
const filteredDocuments = computed(() => {
  let result = [...documents.value]
  if (searchQuery.value.trim()) {
    const query = searchQuery.value.toLowerCase()
    result = result.filter(doc => doc.fileName.toLowerCase().includes(query))
  }
  if (filterType.value !== 'all') {
    result = result.filter(doc => {
      const type = fileTypeMap[doc.mimeType] || 'Other'
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

// 所有文件类型
const allFileTypes = computed(() => {
  const types = new Set<string>()
  documents.value.forEach(doc => {
    types.add(fileTypeMap[doc.mimeType] || 'Other')
  })
  return Array.from(types)
})

// 全选状态
const isAllSelected = computed(() =>
  filteredDocuments.value.length > 0 &&
  filteredDocuments.value.every(doc => selectedDocIds.value.has(doc.id))
)

// 是否有选中文档
const hasSelection = computed(() => selectedDocIds.value.size > 0)

// ========== Toast 工具 ==========
function showToast(msg: string) {
  toastMessage.value = msg
  toastVisible.value = true
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toastVisible.value = false }, 3000)
}

// ========== 格式化工具 ==========
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString('zh-CN')
}

// ========== 文件上传 ==========
function triggerUpload() {
  fileInput.value?.click()
}

async function handleFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files
  if (!files || !kbId.value) return
  await handleBatchUpload(Array.from(files))
  input.value = ''
}

// 拖拽上传：有效文件
function handleDropFiles(files: File[]) {
  handleBatchUpload(files)
}

// 拖拽上传：不支持格式
function handleRejectFiles(fileNames: string[]) {
  showToast(`已跳过不支持的文件格式：${fileNames.join(', ')}`)
}

// 批量上传队列逻辑
async function handleBatchUpload(files: File[]) {
  const items: UploadFileItem[] = files.map(f => ({
    id: crypto.randomUUID(),
    file: f,
    fileName: f.name,
    status: 'waiting' as const
  }))
  uploadQueue.value = items
  isUploading.value = true

  for (const item of items) {
    item.status = 'uploading'
    try {
      await knowledgeBaseApi.uploadDocument(kbId.value, item.file)
      item.status = 'success'
    } catch (e: any) {
      item.status = 'error'
      item.errorMessage = e.message ?? '上传失败'
    }
  }

  isUploading.value = false
  await loadData()
}

// 重试单个失败上传
async function handleRetryUpload(fileId: string) {
  const item = uploadQueue.value.find(f => f.id === fileId)
  if (!item) return
  item.status = 'uploading'
  item.errorMessage = undefined
  try {
    await knowledgeBaseApi.uploadDocument(kbId.value, item.file)
    item.status = 'success'
    await loadData()
  } catch (e: any) {
    item.status = 'error'
    item.errorMessage = e.message ?? '上传失败'
  }
}

// 关闭上传进度面板
function handleDismissUpload() {
  uploadQueue.value = []
}

// ========== 批量选择 ==========
function toggleDocSelection(docId: string) {
  const newSet = new Set(selectedDocIds.value)
  if (newSet.has(docId)) {
    newSet.delete(docId)
  } else {
    newSet.add(docId)
  }
  selectedDocIds.value = newSet
}

function toggleSelectAll() {
  if (isAllSelected.value) {
    selectedDocIds.value = new Set()
  } else {
    selectedDocIds.value = new Set(filteredDocuments.value.map(d => d.id))
  }
}

// ========== 批量删除 ==========
async function handleBatchDelete() {
  batchDeleting.value = true
  let failCount = 0
  const ids = Array.from(selectedDocIds.value)

  for (const docId of ids) {
    try {
      await knowledgeBaseApi.deleteDocument(kbId.value, docId)
    } catch {
      failCount++
    }
  }

  selectedDocIds.value = new Set()
  showBatchDeleteConfirm.value = false
  batchDeleting.value = false

  if (failCount > 0) {
    showToast(`部分文档删除失败（${failCount} 个）`)
  }
  await loadData()
}

// ========== 文档操作 ==========
async function handleRetry(doc: KbDocument) {
  try {
    await knowledgeBaseApi.retryDocument(kbId.value, doc.id)
    await loadData()
  } catch (e: any) {
    showToast('重试失败')
  }
}

async function handleRechunk(doc: KbDocument) {
  try {
    await knowledgeBaseApi.rechunkDocument(kbId.value, doc.id)
    await loadData()
  } catch (e: any) {
    error.value = e.message ?? '重新分块失败'
  }
}

async function handleDelete() {
  if (!deleteTarget.value) return
  try {
    await store.removeDocument(kbId.value, deleteTarget.value.doc.id)
    deleteTarget.value = null
    await loadData()
  } catch (e: any) {
    error.value = e.message ?? '删除失败'
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
    const a = document.createElement('a')
    a.href = url
    a.download = doc.fileName
    a.click()
    URL.revokeObjectURL(url)
  } catch (e: any) {
    error.value = e.message ?? '下载失败'
  }
}

async function testRetrieval() {
  if (!testQuery.value.trim()) return
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await knowledgeBaseApi.testRetrieval(kbId.value, testQuery.value)
  } catch (e: any) {
    error.value = e.message ?? '测试检索失败'
  } finally {
    testing.value = false
  }
}
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 顶部导航栏 -->
    <div class="flex-shrink-0 border-b border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md flex items-center gap-3">
        <button
          class="text-sm text-muted-foreground hover:text-foreground transition-colors"
          @click="router.push('/knowledge-bases')"
        >
          <ArrowLeft :size="16" class="inline mr-1" />
          返回
        </button>
        <h1 class="text-xl font-semibold text-foreground flex-1">
          {{ kb?.name || '知识库详情' }}
        </h1>
        <button
          class="inline-flex items-center gap-2 rounded-md text-sm font-medium h-9 px-md bg-primary text-primary-foreground hover:bg-primary/90 hover:shadow-md transition-all duration-200"
          @click="triggerUpload"
        >
          <Upload :size="16" />
          上传文档
        </button>
        <input
          ref="fileInput"
          type="file"
          multiple
          accept=".pdf,.docx,.doc,.md,.txt,.html,.htm"
          class="hidden"
          @change="handleFileChange"
        />
      </div>
    </div>

    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg">
        <!-- 错误提示 -->
        <div
          v-if="error && !loading"
          class="mb-md p-3 rounded-md bg-destructive/10 text-destructive text-sm flex items-center justify-between"
        >
          <span>{{ error }}</span>
          <button @click="error = null" class="text-destructive hover:text-destructive/80">
            <X :size="16" />
          </button>
        </div>

        <!-- 加载中 -->
        <LoadingSpinner v-if="loading" text="加载中..." />

        <!-- 加载失败（无数据时展示 ErrorState） -->
        <ErrorState
          v-else-if="!kb && error"
          :description="error ?? '加载知识库详情失败'"
          action-label="重试"
          :show-action="true"
          @action="loadData"
        />

        <!-- 知识库信息 -->
        <template v-else-if="kb">
          <!-- 基本信息卡片 -->
          <div class="mb-6 p-4 rounded-lg border border-border bg-card">
            <div class="flex items-start justify-between mb-4">
              <div class="flex-1">
                <h2 class="text-lg font-semibold text-foreground mb-1">{{ kb.name }}</h2>
                <p v-if="kb.description" class="text-sm text-muted-foreground">{{ kb.description }}</p>
              </div>
            </div>
            <!-- 统计信息 -->
            <div v-if="stats" class="grid grid-cols-2 md:grid-cols-4 gap-4 mt-4">
              <div class="p-3 rounded-md bg-muted/30">
                <div class="text-xs text-muted-foreground mb-1">文档数</div>
                <div class="text-lg font-semibold text-foreground">{{ stats.documentCount }}</div>
              </div>
              <div class="p-3 rounded-md bg-muted/30">
                <div class="text-xs text-muted-foreground mb-1">分段数</div>
                <div class="text-lg font-semibold text-foreground">{{ stats.totalChunks }}</div>
              </div>
              <div class="p-3 rounded-md bg-muted/30">
                <div class="text-xs text-muted-foreground mb-1">总大小</div>
                <div class="text-lg font-semibold text-foreground">{{ formatSize(stats.totalSize) }}</div>
              </div>
              <div class="p-3 rounded-md bg-muted/30">
                <div class="text-xs text-muted-foreground mb-1">索引状态</div>
                <div class="text-lg font-semibold text-foreground">
                  <span v-if="stats.indexStatus === 'HEALTHY'" class="text-green-600">健康</span>
                  <span v-else-if="stats.indexStatus === 'PROCESSING'" class="text-blue-600">处理中</span>
                  <span v-else class="text-yellow-600">部分失败</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 上传进度面板 -->
          <div v-if="uploadQueue.length > 0" class="mb-4">
            <UploadProgress
              :files="uploadQueue"
              @retry="handleRetryUpload"
              @dismiss="handleDismissUpload"
            />
          </div>

          <!-- 拖拽上传区域包裹文档列表 -->
          <DropZone
            :accept-types="acceptTypes"
            :disabled="isUploading"
            @drop="handleDropFiles"
            @reject="handleRejectFiles"
          >
            <!-- 搜索和过滤栏 -->
            <div class="p-4 space-y-2">
              <div class="flex items-center gap-2">
                <div class="relative flex-1">
                  <Search class="absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground" :size="16" />
                  <input
                    v-model="searchQuery"
                    type="search"
                    placeholder="搜索文档名称…"
                    class="w-full h-9 pl-8 pr-3 rounded-md border border-input bg-background text-sm
                           placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  />
                </div>
                <button
                  class="inline-flex items-center gap-1 h-9 px-3 rounded-md border border-input bg-background
                         hover:bg-accent transition-colors"
                  :class="showFilters ? 'bg-accent' : ''"
                  @click="showFilters = !showFilters"
                >
                  <Filter :size="16" />
                  <span class="text-sm">过滤</span>
                </button>
                <button
                  class="inline-flex items-center gap-1 h-9 px-3 rounded-md border border-input bg-background
                         hover:bg-accent transition-colors"
                  @click="showTestRetrieval = !showTestRetrieval"
                >
                  <TestTube :size="16" />
                  <span class="text-sm">测试检索</span>
                </button>
              </div>

              <!-- 过滤选项 -->
              <div v-if="showFilters" class="p-3 rounded-md border border-border bg-muted/30 space-y-3">
                <div class="grid grid-cols-1 md:grid-cols-3 gap-3">
                  <div>
                    <label class="text-xs font-medium text-muted-foreground mb-1 block">文件类型</label>
                    <select
                      v-model="filterType"
                      class="w-full h-8 rounded-md border border-input bg-background px-2 text-xs
                             focus:outline-none focus:ring-1 focus:ring-ring"
                    >
                      <option value="all">全部</option>
                      <option v-for="type in allFileTypes" :key="type" :value="type">{{ type }}</option>
                    </select>
                  </div>
                  <div>
                    <label class="text-xs font-medium text-muted-foreground mb-1 block">处理状态</label>
                    <select
                      v-model="filterStatus"
                      class="w-full h-8 rounded-md border border-input bg-background px-2 text-xs
                             focus:outline-none focus:ring-1 focus:ring-ring"
                    >
                      <option value="all">全部</option>
                      <option value="PENDING">等待中</option>
                      <option value="PROCESSING">处理中</option>
                      <option value="COMPLETED">已完成</option>
                      <option value="ERROR">失败</option>
                    </select>
                  </div>
                  <div>
                    <label class="text-xs font-medium text-muted-foreground mb-1 block">上传时间</label>
                    <select
                      v-model="filterTimeRange"
                      class="w-full h-8 rounded-md border border-input bg-background px-2 text-xs
                             focus:outline-none focus:ring-1 focus:ring-ring"
                    >
                      <option value="all">全部</option>
                      <option value="7d">最近7天</option>
                      <option value="30d">最近30天</option>
                    </select>
                  </div>
                </div>
              </div>
            </div>

            <!-- 批量操作栏 -->
            <div
              v-if="hasSelection"
              class="mx-4 mb-2 p-3 rounded-md bg-primary/5 border border-primary/20 flex items-center justify-between"
            >
              <span class="text-sm text-foreground">
                已选择 <span class="font-semibold">{{ selectedDocIds.size }}</span> 个文档
              </span>
              <button
                class="inline-flex items-center gap-1 h-8 px-3 rounded-md text-sm bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors"
                @click="showBatchDeleteConfirm = true"
              >
                <Trash2 :size="14" />
                批量删除
              </button>
            </div>

            <!-- 文档列表表格 -->
            <div class="mx-4 mb-4 border border-border rounded-lg overflow-hidden">
              <div v-if="filteredDocuments.length === 0" class="p-8 text-center text-sm text-muted-foreground">
                {{ searchQuery || filterType !== 'all' || filterStatus !== 'all' || filterTimeRange !== 'all'
                  ? '没有找到匹配的文档'
                  : '暂无文档，拖拽文件到此处或点击上方按钮上传' }}
              </div>
              <table v-else class="w-full">
                <thead class="bg-muted/30 border-b border-border">
                  <tr>
                    <th class="w-10 p-3">
                      <input
                        type="checkbox"
                        :checked="isAllSelected"
                        class="rounded border-input"
                        @change="toggleSelectAll"
                      />
                    </th>
                    <th class="text-left p-3 text-xs font-medium text-muted-foreground">文档名称</th>
                    <th class="text-left p-3 text-xs font-medium text-muted-foreground">类型</th>
                    <th class="text-left p-3 text-xs font-medium text-muted-foreground">大小</th>
                    <th class="text-left p-3 text-xs font-medium text-muted-foreground">上传时间</th>
                    <th class="text-left p-3 text-xs font-medium text-muted-foreground">处理状态</th>
                    <th class="text-left p-3 text-xs font-medium text-muted-foreground">分段数</th>
                    <th class="text-right p-3 text-xs font-medium text-muted-foreground">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="doc in filteredDocuments"
                    :key="doc.id"
                    class="border-b border-border hover:bg-muted/30 transition-colors"
                  >
                    <td class="w-10 p-3">
                      <input
                        type="checkbox"
                        :checked="selectedDocIds.has(doc.id)"
                        class="rounded border-input"
                        @change="toggleDocSelection(doc.id)"
                      />
                    </td>
                    <td class="p-3">
                      <div class="flex items-center gap-2">
                        <FileIcon :size="16" class="text-muted-foreground shrink-0" />
                        <span class="text-sm text-foreground truncate max-w-[320px]">{{ doc.fileName }}</span>
                      </div>
                    </td>
                    <td class="p-3 text-sm text-muted-foreground">
                      {{ fileTypeMap[doc.mimeType] || 'Other' }}
                    </td>
                    <td class="p-3 text-sm text-muted-foreground">
                      {{ formatSize(doc.fileSize) }}
                    </td>
                    <td class="p-3 text-sm text-muted-foreground">
                      {{ formatDate(doc.createdAt) }}
                    </td>
                    <td class="p-3">
                      <span
                        class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium"
                        :class="statusMap[doc.status]?.class ?? 'bg-gray-100 text-gray-800'"
                      >
                        <component :is="statusMap[doc.status]?.icon" :size="12" />
                        {{ statusMap[doc.status]?.label ?? doc.status }}
                      </span>
                    </td>
                    <td class="p-3 text-sm text-muted-foreground">
                      {{ doc.chunkCount }}
                    </td>
                    <td class="p-3">
                      <div class="flex items-center justify-end gap-1">
                        <button
                          class="p-1.5 text-muted-foreground hover:text-foreground transition-colors"
                          title="查看详情"
                          @click="viewDocument(doc)"
                        >
                          <Eye :size="14" />
                        </button>
                        <button
                          class="p-1.5 text-muted-foreground hover:text-foreground transition-colors"
                          title="下载"
                          @click="downloadDocument(doc)"
                        >
                          <Download :size="14" />
                        </button>
                        <button
                          v-if="doc.status === 'ERROR'"
                          class="p-1.5 text-muted-foreground hover:text-blue-600 transition-colors"
                          title="重试"
                          @click="handleRetry(doc)"
                        >
                          <RefreshCw :size="14" />
                        </button>
                        <button
                          v-if="doc.status === 'COMPLETED'"
                          class="p-1.5 text-muted-foreground hover:text-blue-600 transition-colors"
                          title="重新分块"
                          @click="handleRechunk(doc)"
                        >
                          <RefreshCw :size="14" />
                        </button>
                        <button
                          class="p-1.5 text-muted-foreground hover:text-foreground transition-colors"
                          title="查看日志"
                          @click="viewLogs(doc)"
                        >
                          <FileText :size="14" />
                        </button>
                        <button
                          class="p-1.5 text-muted-foreground hover:text-destructive transition-colors"
                          title="删除"
                          @click="deleteTarget = { doc }"
                        >
                          <Trash2 :size="14" />
                        </button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </DropZone>

          <!-- 测试检索区域 -->
          <div v-if="showTestRetrieval" class="mt-6 p-4 rounded-lg border border-border bg-card">
            <h3 class="text-sm font-semibold text-foreground mb-3">测试检索</h3>
            <div class="space-y-3">
              <div class="flex gap-2">
                <input
                  v-model="testQuery"
                  type="text"
                  placeholder="输入测试问题…"
                  class="flex-1 h-9 rounded-md border border-input bg-background px-3 text-sm
                         placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                  @keyup.enter="testRetrieval"
                />
                <button
                  class="h-9 px-4 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors text-sm"
                  :disabled="testing || !testQuery.trim()"
                  @click="testRetrieval"
                >
                  {{ testing ? '检索中...' : '检索' }}
                </button>
              </div>
              <!-- 检索结果 -->
              <div v-if="testResult" class="space-y-3">
                <div v-if="testResult.chunks.length > 0">
                  <h4 class="text-xs font-medium text-muted-foreground mb-2">命中的分块 ({{ testResult.chunks.length }})</h4>
                  <div class="space-y-2">
                    <div
                      v-for="chunk in testResult.chunks"
                      :key="chunk.chunkId"
                      class="p-3 rounded-md border border-border bg-muted/20"
                    >
                      <div class="flex items-start justify-between mb-2">
                        <span class="text-xs font-medium text-foreground">{{ chunk.documentName }}</span>
                        <span class="text-xs text-muted-foreground">相似度: {{ (chunk.similarity * 100).toFixed(1) }}%</span>
                      </div>
                      <p class="text-sm text-foreground line-clamp-3">{{ chunk.content }}</p>
                    </div>
                  </div>
                </div>
                <div v-if="testResult.answer" class="p-3 rounded-md border border-border bg-primary/5">
                  <h4 class="text-xs font-medium text-muted-foreground mb-2">基于知识库的回答</h4>
                  <p class="text-sm text-foreground whitespace-pre-wrap">{{ testResult.answer }}</p>
                </div>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- Toast 提示 -->
    <Transition name="toast">
      <div
        v-if="toastVisible"
        class="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 px-4 py-2 rounded-lg bg-foreground text-background text-sm shadow-lg"
      >
        {{ toastMessage }}
      </div>
    </Transition>

    <!-- 处理日志对话框 -->
    <div v-if="showLogs && selectedDoc" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="showLogs = false">
      <div class="bg-card border border-border rounded-lg p-6 w-full max-w-[672px] max-h-[80vh] overflow-y-auto shadow-lg">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-lg font-semibold text-foreground">处理日志 - {{ selectedDoc.fileName }}</h3>
          <button class="text-muted-foreground hover:text-foreground" @click="showLogs = false">
            <X :size="20" />
          </button>
        </div>
        <div v-if="processingLogs.length === 0" class="text-sm text-muted-foreground">暂无日志</div>
        <div v-else class="space-y-2">
          <div
            v-for="log in processingLogs"
            :key="log.id"
            class="p-3 rounded-md border border-border"
            :class="log.stage === 'ERROR' ? 'bg-red-50 dark:bg-red-900/10' : 'bg-muted/30'"
          >
            <div class="flex items-start justify-between mb-1">
              <span class="text-xs font-medium text-foreground">{{ log.stage }}</span>
              <span class="text-xs text-muted-foreground">{{ formatDate(log.timestamp) }}</span>
            </div>
            <p class="text-sm text-foreground">{{ log.message }}</p>
            <p v-if="log.error" class="text-xs text-destructive mt-1">{{ log.error }}</p>
          </div>
        </div>
      </div>
    </div>

    <!-- 单个删除确认对话框 -->
    <div v-if="deleteTarget" class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="deleteTarget = null">
      <div class="bg-card border border-border rounded-lg p-6 w-full max-w-[384px] shadow-lg">
        <h3 class="text-lg font-semibold text-foreground mb-2">确认删除</h3>
        <p class="text-sm text-muted-foreground mb-4">
          确定要删除文档「{{ deleteTarget.doc.fileName }}」吗？此操作不可撤销。
        </p>
        <div class="flex justify-end gap-2">
          <button
            class="h-9 px-4 rounded-md text-sm border border-input hover:bg-accent transition-colors"
            @click="deleteTarget = null"
          >取消</button>
          <button
            class="h-9 px-4 rounded-md text-sm bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors"
            @click="handleDelete"
          >删除</button>
        </div>
      </div>
    </div>

    <!-- 批量删除确认对话框 -->
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

<style scoped>
.toast-enter-active,
.toast-leave-active {
  transition: all 0.3s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translate(-50%, 10px);
}
</style>
