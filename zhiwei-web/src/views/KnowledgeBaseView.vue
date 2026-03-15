<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowUpRight,
  Clock3,
  Database,
  Edit2,
  Files,
  Filter,
  Layers3,
  Plus,
  Search,
  Tag,
  Trash2,
  X,
} from 'lucide-vue-next'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageSection from '@/components/layout/PageSection.vue'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import type { KnowledgeBase } from '@/types'

const store = useKnowledgeBaseStore()
const router = useRouter()

const showCreate = ref(false)
const createForm = ref({
  name: '',
  description: '',
  tags: [] as string[],
})

const editingKb = ref<KnowledgeBase | null>(null)
const editForm = ref({
  description: '',
  tags: [] as string[],
})

const deleteTarget = ref<{ type: 'kb' | 'doc'; id: string; kbId?: string; name: string } | null>(null)

const searchQuery = ref('')
const selectedTags = ref<string[]>([])
const timeRange = ref<string>('all')
const showFilters = ref(false)

const allTags = computed(() => {
  const tags = new Set<string>()
  store.list.forEach(() => {
    // The backend has not exposed knowledge base tags yet.
  })
  return Array.from(tags)
})

const filteredKbs = computed(() => {
  let result = [...store.list]

  if (searchQuery.value.trim()) {
    const query = searchQuery.value.toLowerCase()
    result = result.filter(kb => {
      const nameMatch = kb.name.toLowerCase().includes(query)
      const descMatch = kb.description?.toLowerCase().includes(query)
      return nameMatch || descMatch
    })
  }

  if (selectedTags.value.length > 0) {
    // The backend has not exposed knowledge base tags yet.
  }

  if (timeRange.value !== 'all') {
    const now = Date.now()
    const days = timeRange.value === '7d' ? 7 : 30
    const cutoff = now - days * 24 * 60 * 60 * 1000
    result = result.filter(kb => new Date(kb.updatedAt).getTime() >= cutoff)
  }

  return result
})

const totalDocuments = computed(() => (
  store.list.reduce((sum, kb) => sum + kb.documentCount, 0)
))

const totalChunks = computed(() => (
  store.list.reduce((sum, kb) => sum + kb.totalChunks, 0)
))

const latestUpdate = computed(() => {
  if (store.list.length === 0) return '暂无更新'
  const latest = [...store.list]
    .sort((first, second) => new Date(second.updatedAt).getTime() - new Date(first.updatedAt).getTime())[0]
  return formatDate(latest.updatedAt)
})

const hasFilters = computed(() => (
  Boolean(searchQuery.value.trim())
  || selectedTags.value.length > 0
  || timeRange.value !== 'all'
))
const timeRangeLabel = computed(() => {
  if (timeRange.value === '7d') return '最近 7 天'
  if (timeRange.value === '30d') return '最近 30 天'
  return '全部时间'
})
const filterKeywordLabel = computed(() => searchQuery.value.trim() || '未设置')

const showDeleteConfirm = computed({
  get: () => deleteTarget.value !== null,
  set: (value: boolean) => {
    if (!value) {
      deleteTarget.value = null
    }
  },
})

onMounted(() => {
  void store.fetchList()
})

function formatDate(dateStr: string) {
  return new Date(dateStr).toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

function selectKb(kb: KnowledgeBase) {
  router.push(`/knowledge-bases/${kb.id}`)
}

async function handleCreate() {
  if (!createForm.value.name.trim()) return
  const kb = await store.create(createForm.value)
  if (kb) {
    showCreate.value = false
    createForm.value = { name: '', description: '', tags: [] }
  }
}

function startEdit(kb: KnowledgeBase) {
  editingKb.value = kb
  editForm.value = {
    description: kb.description || '',
    tags: [],
  }
}

async function handleUpdate() {
  if (!editingKb.value) return
  try {
    await store.fetchList()
    editingKb.value = null
  } catch (error) {
    console.error('更新知识库失败', error)
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return

  if (deleteTarget.value.type === 'kb') {
    await store.remove(deleteTarget.value.id)
  } else if (deleteTarget.value.kbId) {
    await store.removeDocument(deleteTarget.value.kbId, deleteTarget.value.id)
  }

  deleteTarget.value = null
}

function toggleTag(tag: string) {
  const index = selectedTags.value.indexOf(tag)
  if (index === -1) {
    selectedTags.value.push(tag)
    return
  }
  selectedTags.value.splice(index, 1)
}

function clearFilters() {
  searchQuery.value = ''
  selectedTags.value = []
  timeRange.value = 'all'
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack">
        <header class="space-y-5 border-b border-border/70 pb-6">
          <div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
            <div class="space-y-3">
              <div class="surface-label">知识库</div>
              <div class="space-y-2">
                <h1 class="text-3xl font-semibold tracking-tight text-foreground">
                  知识库
                </h1>
              </div>
            </div>

            <div class="flex flex-wrap items-center gap-3">
              <Button type="button" @click="showCreate = true">
                <Plus class="size-4" />
                新建知识库
              </Button>
            </div>
          </div>

          <section class="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <MetricCard label="知识库总数" :value="store.list.length" hint="当前已经接入、可以继续维护的知识库数量。">
              <template #icon>
                <Database class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="文档总量" :value="totalDocuments" hint="所有知识库累计收录的文档数量。">
              <template #icon>
                <Files class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="分块总量" :value="totalChunks" hint="已经完成切分、可参与检索的文本分段。">
              <template #icon>
                <Layers3 class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="最近更新" :value="latestUpdate" hint="方便快速定位最近活跃的知识资产。">
              <template #icon>
                <Clock3 class="size-5" />
              </template>
            </MetricCard>
          </section>
        </header>

        <StatePanel
          v-if="store.error && store.list.length > 0"
          title="列表加载出现了问题"
          :description="store.error"
          tone="danger"
        >
          <template #icon>
            <Database class="size-5" />
          </template>
          <template #actions>
            <Button type="button" variant="outline" @click="store.error = null">
              <X class="size-4" />
              关闭提示
            </Button>
            <Button type="button" @click="store.fetchList()">
              重新加载
            </Button>
          </template>
        </StatePanel>

        <PageSection
          eyebrow="筛选"
          title="查找知识库"
        >
          <div class="grid gap-4 xl:grid-cols-[minmax(0,1fr)_300px]">
            <div class="space-y-4">
              <div class="flex flex-wrap gap-2 text-xs">
                <span class="filter-pill">关键字：{{ filterKeywordLabel }}</span>
                <span class="filter-pill">更新时间：{{ timeRangeLabel }}</span>
                <span class="filter-pill">标签：{{ selectedTags.length > 0 ? `${selectedTags.length} 个` : '未使用' }}</span>
              </div>

              <div class="flex flex-col gap-3 xl:flex-row xl:items-center">
                <div class="relative min-w-[260px] flex-1 xl:max-w-[36rem]">
                  <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    v-model="searchQuery"
                    type="search"
                    placeholder="搜索知识库名称或描述"
                    class="pl-9"
                  />
                </div>

                <div class="flex flex-wrap items-center gap-3">
                  <Select v-model="timeRange">
                    <SelectTrigger class="w-full sm:w-[180px]">
                      <SelectValue placeholder="更新时间" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="all">全部时间</SelectItem>
                      <SelectItem value="7d">最近 7 天</SelectItem>
                      <SelectItem value="30d">最近 30 天</SelectItem>
                    </SelectContent>
                  </Select>

                  <Button
                    type="button"
                    variant="outline"
                    :class="showFilters ? 'bg-accent text-accent-foreground' : ''"
                    @click="showFilters = !showFilters"
                  >
                    <Filter class="size-4" />
                    {{ showFilters ? '收起筛选' : '更多筛选' }}
                  </Button>
                </div>
              </div>

              <div
                v-if="showFilters"
                class="grid gap-4 rounded-[calc(var(--radius)+4px)] border border-border/70 bg-background/55 p-4 lg:grid-cols-[minmax(0,1fr)_220px]"
              >
                <div class="space-y-3">
                  <div class="surface-label text-[0.68rem]">标签</div>
                  <div v-if="allTags.length > 0" class="flex flex-wrap gap-2">
                    <Badge
                      v-for="tag in allTags"
                      :key="tag"
                      :variant="selectedTags.includes(tag) ? 'default' : 'outline'"
                      class="cursor-pointer px-3 py-1"
                      @click="toggleTag(tag)"
                    >
                      <Tag class="size-3.5" />
                      {{ tag }}
                    </Badge>
                  </div>
                  <div
                    v-else
                    class="rounded-[calc(var(--radius)+4px)] border border-dashed border-border/70 bg-muted/35 px-4 py-3 text-sm text-muted-foreground"
                  >
                    当前还没有可用标签，先按名称或时间筛选。
                  </div>
                </div>

                <div class="space-y-3">
                  <div class="surface-label text-[0.68rem]">当前状态</div>
                  <div class="grid gap-2 text-sm text-muted-foreground">
                    <div class="filter-pill justify-between">
                      <span>搜索关键字</span>
                      <span class="font-medium text-foreground">{{ filterKeywordLabel }}</span>
                    </div>
                    <div class="filter-pill justify-between">
                      <span>更新时间</span>
                      <span class="font-medium text-foreground">{{ timeRangeLabel }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div class="grid gap-3 text-sm text-muted-foreground">
              <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/58 px-4 py-3">
                <div class="mb-1 text-sm font-medium text-foreground">当前结果</div>
                <p>{{ filteredKbs.length }} / {{ store.list.length }}</p>
              </div>
              <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/58 px-4 py-3">
                <div class="mb-1 text-sm font-medium text-foreground">继续管理</div>
                <p>进入详情后可上传文档、重建分块和测试检索。</p>
              </div>
              <div v-if="hasFilters" class="flex justify-start">
                <Button type="button" variant="ghost" class="px-0" @click="clearFilters">
                  清空筛选
                </Button>
              </div>
            </div>
          </div>
        </PageSection>

        <PageSection
          eyebrow="目录"
          title="全部知识库"
          :description="hasFilters ? '结果已按关键字或更新时间过滤。' : ''"
        >
          <div v-if="store.loading" class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            <div
              v-for="index in 6"
              :key="index"
              class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/72 p-5"
            >
              <div class="flex items-start justify-between gap-3">
                <div class="flex items-center gap-3">
                  <Skeleton class="h-11 w-11 rounded-2xl" />
                  <div class="space-y-2">
                    <Skeleton class="h-4 w-32" />
                    <Skeleton class="h-4 w-20 rounded-full" />
                  </div>
                </div>
                <Skeleton class="h-8 w-16 rounded-full" />
              </div>
              <div class="mt-5 space-y-2">
                <Skeleton class="h-4 w-full" />
                <Skeleton class="h-4 w-3/4" />
              </div>
              <div class="mt-5 grid gap-3 sm:grid-cols-2">
                <Skeleton class="h-16 rounded-[calc(var(--radius)+4px)]" />
                <Skeleton class="h-16 rounded-[calc(var(--radius)+4px)]" />
              </div>
            </div>
          </div>

          <StatePanel
            v-else-if="store.error && filteredKbs.length === 0"
            title="知识库列表加载失败"
            :description="store.error"
            tone="danger"
          >
            <template #icon>
              <Database class="size-5" />
            </template>
            <template #actions>
              <Button type="button" variant="outline" @click="store.fetchList()">
                重试
              </Button>
            </template>
          </StatePanel>

          <StatePanel
            v-else-if="filteredKbs.length === 0 && !hasFilters"
            title="还没有知识库"
            description="创建知识库后，就可以继续上传文档、解析分块并接入问答检索。"
          >
            <template #icon>
              <Database class="size-5" />
            </template>
            <template #actions>
              <Button type="button" @click="showCreate = true">
                <Plus class="size-4" />
                新建知识库
              </Button>
            </template>
          </StatePanel>

          <StatePanel
            v-else-if="filteredKbs.length === 0"
            title="没有匹配的知识库"
            description="尝试调整搜索关键字或清空筛选条件。"
          >
            <template #icon>
              <Search class="size-5" />
            </template>
            <template #actions>
              <Button type="button" variant="outline" @click="clearFilters">
                清空筛选
              </Button>
            </template>
          </StatePanel>

          <div v-else class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            <article
              v-for="kb in filteredKbs"
              :key="kb.id"
              class="list-card group relative cursor-pointer p-5"
              @click="selectKb(kb)"
            >
              <div class="flex items-start justify-between gap-3">
                <div class="flex min-w-0 items-start gap-3">
                  <div class="flex size-12 shrink-0 items-center justify-center rounded-2xl border border-border/70 bg-background/75 text-primary transition-transform duration-200 group-hover:-translate-y-0.5">
                    <Database class="size-5" />
                  </div>
                  <div class="min-w-0 space-y-2">
                    <div class="flex flex-wrap items-center gap-2">
                      <h3 class="truncate text-base font-semibold tracking-tight text-foreground">
                        {{ kb.name }}
                      </h3>
                      <Badge variant="outline" class="text-xs">
                        {{ kb.documentCount }} 篇文档
                      </Badge>
                      <span class="surface-chip">更新于 {{ formatDate(kb.updatedAt) }}</span>
                    </div>
                    <div class="flex flex-wrap gap-2 text-xs">
                      <span class="surface-chip">向量模型 {{ kb.embeddingModel || '未配置' }}</span>
                      <span class="surface-chip">分块 {{ kb.totalChunks }}</span>
                    </div>
                  </div>
                </div>

                <div class="flex items-center gap-1 opacity-0 transition-opacity duration-200 group-hover:opacity-100">
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    class="size-8"
                    title="编辑"
                    @click.stop="startEdit(kb)"
                  >
                    <Edit2 class="size-4" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    class="size-8 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
                    title="删除"
                    @click.stop="deleteTarget = { type: 'kb', id: kb.id, name: kb.name }"
                  >
                    <Trash2 class="size-4" />
                  </Button>
                </div>
              </div>

              <p class="mt-4 line-clamp-3 text-sm leading-6 text-muted-foreground">
                {{ kb.description || '这个知识库还没有描述信息。' }}
              </p>

              <div class="mt-5 grid gap-3 sm:grid-cols-2">
                <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/58 px-4 py-3">
                  <div class="mb-1 flex items-center gap-2 text-sm font-medium text-foreground">
                    <Files class="size-4 text-primary" />
                    文档数
                  </div>
                  <p class="text-sm text-muted-foreground">{{ kb.documentCount }} 篇</p>
                </div>

                <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/58 px-4 py-3">
                  <div class="mb-1 flex items-center gap-2 text-sm font-medium text-foreground">
                    <Layers3 class="size-4 text-primary" />
                    分块数
                  </div>
                  <p class="text-sm text-muted-foreground">{{ kb.totalChunks }} 个</p>
                </div>
              </div>

              <div class="mt-5 flex items-center justify-between gap-3 text-sm">
                <span class="text-muted-foreground">适合继续补文档、检查分块和验证检索。</span>
                <span class="inline-flex items-center gap-1 font-medium text-primary transition-colors group-hover:text-primary/80">
                  进入知识库
                  <ArrowUpRight class="size-4" />
                </span>
              </div>
            </article>
          </div>
        </PageSection>
      </div>
    </PageContainer>

    <Dialog v-model:open="showCreate">
      <DialogContent class="shell-card border-border/70 sm:max-w-[520px]">
        <DialogHeader>
          <DialogTitle>新建知识库</DialogTitle>
          <DialogDescription>
            创建新的知识库容器，用来托管文档、分块和检索测试。
          </DialogDescription>
        </DialogHeader>

        <form class="grid gap-4 py-2" @submit.prevent="handleCreate">
          <div class="space-y-2">
            <Label for="kb-name">名称</Label>
            <Input
              id="kb-name"
              v-model="createForm.name"
              placeholder="输入知识库名称"
            />
          </div>

          <div class="space-y-2">
            <Label for="kb-desc">描述</Label>
            <Textarea
              id="kb-desc"
              v-model="createForm.description"
              :rows="4"
              placeholder="输入知识库描述"
              class="resize-none"
            />
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" @click="showCreate = false">
              取消
            </Button>
            <Button type="submit">
              创建
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    <Dialog :open="!!editingKb" @update:open="(value: boolean) => { if (!value) editingKb = null }">
      <DialogContent class="shell-card border-border/70 sm:max-w-[520px]">
        <DialogHeader>
          <DialogTitle>编辑知识库</DialogTitle>
          <DialogDescription>
            补充描述和标签，方便后续查找和整理。
          </DialogDescription>
        </DialogHeader>

        <form class="grid gap-4 py-2" @submit.prevent="handleUpdate">
          <div class="space-y-2">
            <Label>描述</Label>
            <Textarea
              v-model="editForm.description"
              :rows="4"
              placeholder="输入知识库描述"
              class="resize-none"
            />
          </div>

          <div class="space-y-2">
            <Label>标签</Label>
            <Input
              :model-value="editForm.tags.join(', ')"
              placeholder="输入标签，使用逗号分隔"
              @update:model-value="editForm.tags = ($event as string).split(',').map((tag: string) => tag.trim()).filter((tag: string) => tag)"
            />
            <p class="text-xs text-muted-foreground">
              标签暂不可用，后续版本会开放。
            </p>
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" @click="editingKb = null">
              取消
            </Button>
            <Button type="submit">
              保存
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    <ConfirmDialog
      v-model:show="showDeleteConfirm"
      title="删除知识库"
      :message="deleteTarget ? `确认删除「${deleteTarget.name}」吗？此操作不可撤销。` : ''"
      confirm-label="删除"
      confirm-variant="destructive"
      @confirm="confirmDelete"
      @cancel="deleteTarget = null"
    />
  </div>
</template>
