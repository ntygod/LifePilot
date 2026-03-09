<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { knowledgeBaseApi } from '@/api/client'
import type { KbDocument, DocumentChunk } from '@/types'
import { ArrowLeft, FileText, Download, RefreshCw, FileIcon } from 'lucide-vue-next'
import Breadcrumb from '@/components/global/Breadcrumb.vue'
import type { BreadcrumbItem } from '@/components/global/Breadcrumb.vue'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { ScrollArea } from '@/components/ui/scroll-area'

const route = useRoute()
const router = useRouter()

const kbId = computed(() => route.params.id as string)
const docId = computed(() => route.params.docId as string)

const document = ref<KbDocument | null>(null)
const chunks = ref<DocumentChunk[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const loadingChunks = ref(false)

// 知识库信息（面包屑用）
const kb = ref<{ id: string; name: string } | null>(null)

// 面包屑导航（三级：知识库 > 知识库名称 > 文档名称）
const breadcrumbItems = computed<BreadcrumbItem[]>(() => [
  { label: '知识库', to: { name: 'knowledgeBases' } },
  { label: kb.value?.name ?? '...', to: { name: 'knowledgeBaseDetail', params: { id: kbId.value } } },
  { label: document.value?.fileName ?? '...' }
])

// 分页
const chunkOffset = ref(0)
const chunkLimit = 50
const hasMoreChunks = ref(true)

onMounted(async () => {
  await loadDocument()
  await loadChunks()
})

watch(() => route.params.docId, async () => {
  await loadDocument()
  await loadChunks()
})

async function loadDocument() {
  if (!kbId.value || !docId.value) return
  loading.value = true
  error.value = null
  try {
    kb.value = await knowledgeBaseApi.get(kbId.value)
    const docs = await knowledgeBaseApi.listDocuments(kbId.value)
    document.value = docs.find(d => d.id === docId.value) || null
    if (!document.value) {
      error.value = '文档不存在'
    }
  } catch (e: any) {
    error.value = e.message ?? '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadChunks(append = false) {
  if (!kbId.value || !docId.value || loadingChunks.value) return
  loadingChunks.value = true
  try {
    const newChunks = await knowledgeBaseApi.getDocumentChunks(
      kbId.value,
      docId.value,
      append ? chunkOffset.value : 0,
      chunkLimit
    )
    if (append) {
      chunks.value.push(...newChunks)
    } else {
      chunks.value = newChunks
      chunkOffset.value = 0
    }
    hasMoreChunks.value = newChunks.length === chunkLimit
    chunkOffset.value += newChunks.length
  } catch (e: any) {
    error.value = e.message ?? '加载分块失败'
  } finally {
    loadingChunks.value = false
  }
}

async function loadMoreChunks() {
  await loadChunks(true)
}

async function downloadDocument() {
  if (!document.value) return
  try {
    const blob = await knowledgeBaseApi.downloadDocument(kbId.value, docId.value)
    const url = URL.createObjectURL(blob)
    const a = window.document.createElement('a')
    a.href = url
    a.download = document.value.fileName
    a.click()
    URL.revokeObjectURL(url)
  } catch (e: any) {
    error.value = e.message ?? '下载失败'
  }
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString('zh-CN')
}

function scrollToChunk(chunkIndex: number) {
  const element = window.document.getElementById(`chunk-${chunkIndex}`)
  if (element) {
    element.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 顶部导航栏 -->
    <div class="flex-shrink-0 border-b border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md">
        <Breadcrumb :items="breadcrumbItems" class="mb-2" />
        <div class="flex items-center gap-3">
          <div class="flex-1 min-w-0">
            <h1 class="text-xl font-semibold text-foreground truncate">
              {{ document?.fileName || '文档详情' }}
            </h1>
            <div v-if="document" class="flex items-center gap-4 mt-1 text-xs text-muted-foreground">
              <span>{{ document.chunkCount }} 个分段</span>
              <span>上传于 {{ formatDate(document.createdAt) }}</span>
            </div>
          </div>
          <Button variant="outline" @click="downloadDocument">
            <Download :size="16" />
            下载
          </Button>
        </div>
      </div>
    </div>

    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg flex flex-col gap-md h-full">
        <!-- 错误提示 -->
        <div v-if="error" class="p-3 rounded-md bg-destructive/10 text-destructive text-sm">
          {{ error }}
        </div>

        <!-- 主要内容区域 -->
        <div class="flex-1 overflow-hidden flex">
          <!-- 左侧：分段列表 -->
          <div class="w-80 border-r border-border overflow-hidden flex flex-col bg-muted/10">
            <div class="p-4 border-b border-border flex-shrink-0">
              <h2 class="text-sm font-semibold text-foreground mb-2">分段列表</h2>
              <div class="text-xs text-muted-foreground">
                共 {{ document?.chunkCount || 0 }} 个分段
              </div>
            </div>
            <!-- Skeleton 加载占位符 -->
            <div v-if="loadingChunks && chunks.length === 0" class="p-4 space-y-3">
              <div v-for="i in 6" :key="i" class="space-y-2">
                <Skeleton class="h-3 w-1/3" />
                <Skeleton class="h-3 w-full" />
                <Skeleton class="h-3 w-2/3" />
              </div>
            </div>
            <div v-else-if="chunks.length === 0" class="p-4 text-sm text-muted-foreground">
              暂无分段
            </div>
            <ScrollArea v-else class="flex-1">
              <div class="divide-y divide-border">
                <button
                  v-for="chunk in chunks"
                  :key="chunk.id"
                  :id="`chunk-nav-${chunk.chunkIndex}`"
                  class="w-full p-3 text-left hover:bg-accent transition-colors"
                  @click="scrollToChunk(chunk.chunkIndex)"
                >
                  <div class="flex items-start justify-between mb-1">
                    <span class="text-xs font-medium text-foreground">分段 #{{ chunk.chunkIndex + 1 }}</span>
                    <span class="text-xs text-muted-foreground">{{ chunk.tokenCount }} tokens</span>
                  </div>
                  <p class="text-xs text-muted-foreground line-clamp-2">{{ chunk.content }}</p>
                  <div v-if="chunk.headingHierarchy && chunk.headingHierarchy.length > 0" class="mt-1">
                    <span class="text-xs text-muted-foreground">
                      {{ chunk.headingHierarchy.join(' > ') }}
                    </span>
                  </div>
                </button>
                <div v-if="hasMoreChunks" class="p-4">
                  <Button
                    variant="outline"
                    class="w-full"
                    :disabled="loadingChunks"
                    @click="loadMoreChunks"
                  >
                    {{ loadingChunks ? '加载中...' : '加载更多' }}
                  </Button>
                </div>
              </div>
            </ScrollArea>
          </div>

          <!-- 右侧：分段内容预览 -->
          <div class="flex-1 overflow-y-auto p-6">
            <!-- Skeleton 加载占位符 -->
            <div v-if="loading && !document" class="max-w-4xl mx-auto space-y-6">
              <Card v-for="i in 3" :key="i">
                <CardHeader>
                  <Skeleton class="h-4 w-1/4" />
                  <Skeleton class="h-3 w-1/3 mt-2" />
                </CardHeader>
                <CardContent>
                  <Skeleton class="h-4 w-full mb-2" />
                  <Skeleton class="h-4 w-full mb-2" />
                  <Skeleton class="h-4 w-3/4" />
                </CardContent>
              </Card>
            </div>
            <div v-else-if="!document" class="text-sm text-muted-foreground">文档不存在</div>
            <div v-else-if="chunks.length === 0" class="text-sm text-muted-foreground">
              暂无分段内容
            </div>
            <div v-else class="max-w-4xl mx-auto space-y-6">
              <Card
                v-for="chunk in chunks"
                :id="`chunk-${chunk.chunkIndex}`"
                :key="chunk.id"
              >
                <!-- 分段头部信息 -->
                <CardHeader class="pb-4 border-b border-border">
                  <div class="flex items-start justify-between">
                    <div>
                      <h3 class="text-sm font-semibold text-foreground mb-1">
                        分段 #{{ chunk.chunkIndex + 1 }}
                      </h3>
                      <div class="flex items-center gap-4 text-xs text-muted-foreground">
                        <span>{{ chunk.tokenCount }} tokens</span>
                        <span v-if="chunk.pageNumber">第 {{ chunk.pageNumber }} 页</span>
                        <span>位置: {{ chunk.startOffset }}-{{ chunk.endOffset }}</span>
                      </div>
                      <div v-if="chunk.headingHierarchy && chunk.headingHierarchy.length > 0" class="mt-2">
                        <div class="text-xs text-muted-foreground">
                          <span class="font-medium">路径:</span>
                          {{ chunk.headingHierarchy.join(' > ') }}
                        </div>
                      </div>
                    </div>
                  </div>
                </CardHeader>

                <!-- 分段内容 -->
                <CardContent class="pt-4">
                  <div class="prose prose-sm max-w-none">
                    <div v-if="chunk.contextPrefix" class="mb-2 text-sm text-muted-foreground italic">
                      {{ chunk.contextPrefix }}
                    </div>
                    <div class="text-sm text-foreground whitespace-pre-wrap leading-relaxed">
                      {{ chunk.content }}
                    </div>
                  </div>

                  <!-- 元数据 -->
                  <div v-if="chunk.metadata && Object.keys(chunk.metadata).length > 0" class="mt-4 pt-4 border-t border-border">
                    <details class="text-xs">
                      <summary class="cursor-pointer text-muted-foreground hover:text-foreground">
                        元数据
                      </summary>
                      <pre class="mt-2 p-2 rounded bg-muted/30 text-xs overflow-x-auto">{{ JSON.stringify(chunk.metadata, null, 2) }}</pre>
                    </details>
                  </div>
                </CardContent>
              </Card>

              <!-- 加载更多按钮 -->
              <div v-if="hasMoreChunks" class="text-center">
                <Button
                  variant="outline"
                  :disabled="loadingChunks"
                  @click="loadMoreChunks"
                >
                  {{ loadingChunks ? '加载中...' : '加载更多分段' }}
                </Button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.prose {
  color: inherit;
}
</style>
