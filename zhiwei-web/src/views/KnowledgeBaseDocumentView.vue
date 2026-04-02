<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Download, FileText, Layers3 } from 'lucide-vue-next'
import { knowledgeBaseApi } from '@/api/client'
import type { DocumentChunk, KbDocument } from '@/types'
import Breadcrumb from '@/components/global/Breadcrumb.vue'
import type { BreadcrumbItem } from '@/components/global/Breadcrumb.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Skeleton } from '@/components/ui/skeleton'

const route = useRoute()
const router = useRouter()

const kbId = computed(() => route.params.id as string)
const docId = computed(() => route.params.docId as string)

const knowledgeBase = ref<{ id: string; name: string } | null>(null)
const documentItem = ref<KbDocument | null>(null)
const chunks = ref<DocumentChunk[]>([])
const loading = ref(false)
const loadingChunks = ref(false)
const error = ref<string | null>(null)
const chunkOffset = ref(0)
const chunkLimit = 50
const hasMoreChunks = ref(true)

const breadcrumbItems = computed<BreadcrumbItem[]>(() => [
  { label: '知识库', to: { name: 'knowledgeBases' } },
  { label: knowledgeBase.value?.name ?? '知识库', to: { name: 'knowledgeBaseDetail', params: { id: kbId.value } } },
  { label: documentItem.value?.fileName ?? '文档' },
])

const statusConfig: Record<string, { label: string; badgeClass: string }> = {
  READY: {
    label: '已完成',
    badgeClass: 'border-emerald-200/80 bg-emerald-50/80 text-emerald-700 dark:border-emerald-500/20 dark:bg-emerald-500/10 dark:text-emerald-200',
  },
  ERROR: {
    label: '失败',
    badgeClass: 'border-destructive/25 bg-destructive/10 text-destructive',
  },
  UPLOADING: {
    label: '上传中',
    badgeClass: 'border-sky-200/80 bg-sky-50/80 text-sky-700 dark:border-sky-500/20 dark:bg-sky-500/10 dark:text-sky-200',
  },
  PARSING: {
    label: '解析中',
    badgeClass: 'border-sky-200/80 bg-sky-50/80 text-sky-700 dark:border-sky-500/20 dark:bg-sky-500/10 dark:text-sky-200',
  },
  CHUNKING: {
    label: '分块中',
    badgeClass: 'border-sky-200/80 bg-sky-50/80 text-sky-700 dark:border-sky-500/20 dark:bg-sky-500/10 dark:text-sky-200',
  },
  INDEXING: {
    label: '索引中',
    badgeClass: 'border-amber-200/80 bg-amber-50/80 text-amber-700 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-200',
  },
  EXTRACTING: {
    label: '提取中',
    badgeClass: 'border-amber-200/80 bg-amber-50/80 text-amber-700 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-200',
  },
  UPDATING: {
    label: '更新中',
    badgeClass: 'border-amber-200/80 bg-amber-50/80 text-amber-700 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-200',
  },
  DELETING: {
    label: '删除中',
    badgeClass: 'border-slate-200/80 bg-slate-50/80 text-slate-700 dark:border-slate-500/20 dark:bg-slate-500/10 dark:text-slate-200',
  },
}

const documentStatus = computed(() => {
  const status = documentItem.value?.status
  if (!status) return null

  return statusConfig[status] ?? {
    label: status,
    badgeClass: 'border-border/70 bg-background/72 text-foreground',
  }
})

const summaryItems = computed(() => [
  {
    label: '分块数',
    value: documentItem.value?.chunkCount ?? 0,
    description: '当前已经生成的可检索分块。',
  },
  {
    label: '文件大小',
    value: documentItem.value ? formatBytes(documentItem.value.fileSize) : '—',
    description: '上传文件大小。',
  },
  {
    label: '状态',
    value: documentStatus.value?.label ?? '未加载',
    description: '当前处理进度。',
  },
  {
    label: '上传时间',
    value: documentItem.value ? formatDate(documentItem.value.createdAt) : '—',
    description: '文档写入知识库的时间。',
  },
])

function formatDate(value?: string) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN')
}

function formatBytes(size: number) {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`
  return `${(size / 1024 / 1024 / 1024).toFixed(1)} GB`
}

async function loadDocument() {
  if (!kbId.value || !docId.value) return

  loading.value = true
  error.value = null

  try {
    knowledgeBase.value = await knowledgeBaseApi.get(kbId.value)
    const documents = await knowledgeBaseApi.listDocuments(kbId.value)
    documentItem.value = documents.find(item => item.id === docId.value) ?? null

    if (!documentItem.value) {
      error.value = '未找到请求的文档。'
    }
  } catch (requestError: any) {
    error.value = requestError?.message ?? '加载文档失败。'
  } finally {
    loading.value = false
  }
}

async function loadChunks(append = false) {
  if (!kbId.value || !docId.value || loadingChunks.value) return

  loadingChunks.value = true

  try {
    const nextChunks = await knowledgeBaseApi.getDocumentChunks(
      kbId.value,
      docId.value,
      append ? chunkOffset.value : 0,
      chunkLimit,
    )

    if (append) {
      chunks.value.push(...nextChunks)
    } else {
      chunks.value = nextChunks
      chunkOffset.value = 0
    }

    hasMoreChunks.value = nextChunks.length === chunkLimit
    chunkOffset.value += nextChunks.length
  } catch (requestError: any) {
    error.value = requestError?.message ?? '加载文档分块失败。'
  } finally {
    loadingChunks.value = false
  }
}

async function refreshDocumentWorkspace() {
  chunks.value = []
  chunkOffset.value = 0
  hasMoreChunks.value = true
  await loadDocument()
  await loadChunks()
}

async function loadMoreChunks() {
  await loadChunks(true)
}

async function downloadDocument() {
  if (!documentItem.value) return

  try {
    const blob = await knowledgeBaseApi.downloadDocument(kbId.value, docId.value)
    const url = URL.createObjectURL(blob)
    const anchor = window.document.createElement('a')
    anchor.href = url
    anchor.download = documentItem.value.fileName
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (requestError: any) {
    error.value = requestError?.message ?? '下载文档失败。'
  }
}

function scrollToChunk(chunkIndex: number) {
  const element = window.document.getElementById(`chunk-${chunkIndex}`)
  element?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

watch(
  () => [kbId.value, docId.value],
  () => {
    void refreshDocumentWorkspace()
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
            <Button variant="ghost" @click="router.push({ name: 'knowledgeBaseDetail', params: { id: kbId } })">
              <ArrowLeft class="size-4" />
              返回知识库
            </Button>
            <Button :disabled="!documentItem" @click="downloadDocument">
              <Download class="size-4" />
              下载原文
            </Button>
          </div>
        </div>

        <header class="space-y-5 border-b border-border/70 pb-6">
          <div class="space-y-3">
            <div class="surface-label">知识库文档</div>
            <div class="space-y-2">
              <div class="flex flex-wrap items-center gap-2">
                <h1 class="text-3xl font-semibold tracking-tight text-foreground">
                  {{ documentItem?.fileName ?? '文档加载中...' }}
                </h1>
                <Badge
                  v-if="documentStatus"
                  variant="outline"
                  :class="documentStatus.badgeClass"
                >
                  {{ documentStatus.label }}
                </Badge>
              </div>
              <p class="max-w-[54rem] text-sm leading-7 text-muted-foreground">
                {{ knowledgeBase
                  ? `来自 ${knowledgeBase.name}，可查看分段内容、元数据和上传文件。`
                  : '查看分段内容、元数据和上传文件。' }}
              </p>
            </div>
          </div>

          <section v-if="loading && !documentItem" class="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <Skeleton v-for="index in 4" :key="index" class="h-28 rounded-[calc(var(--radius)+6px)]" />
          </section>

          <section v-else class="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <article
              v-for="item in summaryItems"
              :key="item.label"
              class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/58 px-4 py-3"
            >
              <div class="text-sm font-medium text-foreground">{{ item.label }}</div>
              <div class="text-2xl font-semibold tracking-tight text-foreground">{{ item.value }}</div>
              <p class="text-sm text-muted-foreground">{{ item.description }}</p>
            </article>
          </section>
        </header>

        <StatePanel
          v-if="error && !documentItem && !loading"
          title="无法打开文档详情"
          :description="error"
          tone="danger"
        >
          <template #icon>
            <FileText class="size-5" />
          </template>
        </StatePanel>

        <div v-else class="flex flex-col gap-5 xl:flex-row xl:items-start">
          <div class="min-w-0 flex-1 space-y-5">
            <StatePanel
              v-if="error && documentItem"
              title="部分文档数据加载失败"
              :description="error"
              tone="warning"
            >
              <template #icon>
                <FileText class="size-5" />
              </template>
            </StatePanel>

            <section class="detail-card overflow-hidden">
              <div class="flex flex-col gap-3 border-b border-border/70 px-5 py-4 lg:flex-row lg:items-center lg:justify-between">
                <div class="space-y-1">
                  <h2 class="section-title text-foreground">
                    分块内容
                  </h2>
                  <p class="text-sm text-muted-foreground">
                    按文档顺序检查解析结果、偏移量和标题层级。
                  </p>
                </div>

                <div class="flex flex-wrap items-center gap-3 text-sm text-muted-foreground">
                  <span>已加载 {{ chunks.length }} 个分块</span>
                  <Button
                    v-if="hasMoreChunks && documentItem"
                    variant="outline"
                    size="sm"
                    :disabled="loadingChunks"
                    @click="loadMoreChunks"
                  >
                    {{ loadingChunks ? '加载中...' : '继续加载' }}
                  </Button>
                </div>
              </div>

              <div class="p-5">
                <div v-if="loading && !documentItem" class="space-y-4">
                  <div
                    v-for="index in 3"
                    :key="index"
                    class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/72 p-5"
                  >
                    <div class="space-y-3">
                      <Skeleton class="h-5 w-28" />
                      <Skeleton class="h-4 w-40" />
                      <Skeleton class="h-4 w-full" />
                      <Skeleton class="h-4 w-full" />
                      <Skeleton class="h-4 w-2/3" />
                    </div>
                  </div>
                </div>

                <StatePanel
                  v-else-if="!documentItem"
                  title="当前文档不可用"
                  description="所选文档已不存在于当前知识库中。"
                >
                  <template #icon>
                    <FileText class="size-5" />
                  </template>
                </StatePanel>

                <StatePanel
                  v-else-if="!loadingChunks && chunks.length === 0"
                  title="暂时还没有可用分块"
                  description="该文档可能仍在处理中，或分块提取尚未完成。"
                >
                  <template #icon>
                    <Layers3 class="size-5" />
                  </template>
                </StatePanel>

                <div v-else class="space-y-4">
                  <article
                    v-for="chunk in chunks"
                    :id="`chunk-${chunk.chunkIndex}`"
                    :key="chunk.id"
                    class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/72 p-5"
                  >
                    <div class="space-y-3">
                      <div class="flex flex-wrap items-center gap-2">
                        <h3 class="text-sm font-semibold text-foreground">
                          分块 #{{ chunk.chunkIndex + 1 }}
                        </h3>
                        <Badge variant="outline">
                          {{ chunk.tokenCount }} token
                        </Badge>
                        <Badge v-if="chunk.pageNumber" variant="outline">
                          第 {{ chunk.pageNumber }} 页
                        </Badge>
                      </div>

                      <div class="flex flex-wrap items-center gap-4 text-xs text-muted-foreground">
                        <span>偏移量 {{ chunk.startOffset }} - {{ chunk.endOffset }}</span>
                        <span v-if="chunk.headingHierarchy?.length">
                          {{ chunk.headingHierarchy.join(' / ') }}
                        </span>
                      </div>

                      <p
                        v-if="chunk.contextPrefix"
                        class="rounded-2xl border border-border/60 bg-muted/40 px-4 py-3 text-sm italic text-muted-foreground"
                      >
                        {{ chunk.contextPrefix }}
                      </p>

                      <div class="rounded-2xl border border-border/60 bg-background/70 px-4 py-4 text-sm leading-7 text-foreground whitespace-pre-wrap">
                        {{ chunk.content }}
                      </div>

                      <details
                        v-if="chunk.metadata && Object.keys(chunk.metadata).length > 0"
                        class="rounded-2xl border border-border/60 bg-background/70 px-4 py-3 text-sm"
                      >
                        <summary class="cursor-pointer font-medium text-foreground">
                          分块元数据
                        </summary>
                        <pre class="mt-3 overflow-x-auto text-xs text-muted-foreground">{{ JSON.stringify(chunk.metadata, null, 2) }}</pre>
                      </details>
                    </div>
                  </article>

                  <div v-if="hasMoreChunks" class="flex justify-center pt-2">
                    <Button variant="outline" :disabled="loadingChunks" @click="loadMoreChunks">
                      {{ loadingChunks ? '加载中...' : '加载更多分块' }}
                    </Button>
                  </div>
                </div>
              </div>
            </section>
          </div>

          <aside class="w-full space-y-4 xl:sticky xl:top-6 xl:w-[320px] xl:shrink-0">
            <div v-if="loading && !documentItem" class="space-y-4">
              <Skeleton class="h-32 w-full rounded-[calc(var(--radius)+6px)]" />
              <Skeleton class="h-24 w-full rounded-[calc(var(--radius)+6px)]" />
              <Skeleton class="h-[420px] w-full rounded-[calc(var(--radius)+6px)]" />
            </div>

            <template v-else>
              <section class="detail-card p-4">
                <div class="surface-label mb-3">文档信息</div>
                <div class="space-y-3 text-sm text-muted-foreground">
                  <div class="flex items-center justify-between gap-3">
                    <span>知识库</span>
                    <span class="text-right font-medium text-foreground">{{ knowledgeBase?.name ?? '—' }}</span>
                  </div>
                  <div class="flex items-center justify-between gap-3">
                    <span>MIME 类型</span>
                    <span class="text-right text-foreground">{{ documentItem?.mimeType ?? '—' }}</span>
                  </div>
                  <div class="flex items-center justify-between gap-3">
                    <span>更新时间</span>
                    <span class="text-right text-foreground">{{ formatDate(documentItem?.updatedAt) }}</span>
                  </div>
                  <div class="flex items-center justify-between gap-3">
                    <span>文档状态</span>
                    <Badge
                      v-if="documentStatus"
                      variant="outline"
                      :class="documentStatus.badgeClass"
                    >
                      {{ documentStatus.label }}
                    </Badge>
                    <span v-else class="text-foreground">未加载</span>
                  </div>
                </div>
              </section>

              <section class="detail-card overflow-hidden">
                <div class="border-b border-border/70 px-4 py-4">
                  <div class="text-sm font-medium text-foreground">分块目录</div>
                  <p class="mt-1 text-sm text-muted-foreground">
                    快速跳到已经加载的分块位置。
                  </p>
                </div>

                <div v-if="loadingChunks && chunks.length === 0" class="space-y-2 p-4">
                  <Skeleton v-for="index in 6" :key="index" class="h-14 w-full rounded-2xl" />
                </div>

                <div v-else-if="chunks.length === 0" class="px-4 py-5 text-sm text-muted-foreground">
                  暂时还没有可用的分块索引。
                </div>

                <ScrollArea v-else class="h-[440px]">
                  <div class="space-y-2 p-3">
                    <button
                      v-for="chunk in chunks"
                      :key="`nav-${chunk.id}`"
                      type="button"
                      class="w-full rounded-2xl border border-border/60 bg-background/72 px-3 py-3 text-left transition-colors hover:border-primary/30 hover:bg-background"
                      @click="scrollToChunk(chunk.chunkIndex)"
                    >
                      <div class="flex items-center justify-between gap-3">
                        <span class="text-sm font-medium text-foreground">
                          分块 #{{ chunk.chunkIndex + 1 }}
                        </span>
                        <span class="text-xs text-muted-foreground">
                          {{ chunk.tokenCount }} token
                        </span>
                      </div>
                      <p class="mt-1 line-clamp-2 text-xs leading-5 text-muted-foreground">
                        {{ chunk.content }}
                      </p>
                    </button>
                  </div>
                </ScrollArea>

                <div v-if="hasMoreChunks" class="border-t border-border/70 p-4">
                  <Button
                    variant="outline"
                    class="w-full"
                    :disabled="loadingChunks"
                    @click="loadMoreChunks"
                  >
                    {{ loadingChunks ? '加载中...' : '加载更多' }}
                  </Button>
                </div>
              </section>
            </template>
          </aside>
        </div>
      </div>
    </PageContainer>
  </div>
</template>
