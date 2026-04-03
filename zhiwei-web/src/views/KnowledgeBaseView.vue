<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowUpRight,
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
import PageHeader from '@/components/layout/PageHeader.vue'
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
import { Checkbox } from '@/components/ui/checkbox'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import { knowledgeBaseApi, modelServiceApi } from '@/api/client'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useDatastoreStore } from '@/stores/datastore'
import type { ModelService } from '@/api/client'
import type { CreateKbRequest, KnowledgeBase } from '@/types'

const store = useKnowledgeBaseStore()
const datastoreStore = useDatastoreStore()
const router = useRouter()

const showCreate = ref(false)
const createForm = ref({
  name: '',
  description: '',
  embeddingModel: '' as string | undefined,
  tags: [] as string[],
  datastoreIds: [] as string[],
})

// Provider 列表（用于向量模型下拉选择）
const providers = ref<ModelService[]>([])
const embeddingProviders = computed(() =>
  providers.value.filter(p => p.capabilities?.includes('EMBEDDING'))
)

const editingKb = ref<KnowledgeBase | null>(null)
const editForm = ref({
  description: '',
  tags: [] as string[],
  datastoreIds: [] as string[],
})

const deleteTarget = ref<{ type: 'kb' | 'doc'; id: string; kbId?: string; name: string } | null>(null)

const searchQuery = ref('')
const selectedTags = ref<string[]>([])
const timeRange = ref<string>('all')
const showFilters = ref(false)

const allTags = computed(() => {
  const tags = new Set<string>()
  store.list.forEach(kb => (kb.tags ?? []).forEach(tag => tags.add(tag)))
  return Array.from(tags).sort()
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
    result = result.filter(kb => selectedTags.value.every(tag => (kb.tags ?? []).includes(tag)))
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
  refreshDatastores()
  modelServiceApi.listEnabledServices('EMBEDDING').then(list => { providers.value = list }).catch(() => {})
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

function refreshDatastores() {
  void datastoreStore.fetchList()
}

async function handleCreate() {
  if (!createForm.value.name.trim()) return
  const req: CreateKbRequest = {
    name: createForm.value.name,
    description: createForm.value.description,
    embeddingModel: createForm.value.embeddingModel || undefined,
    tags: createForm.value.tags,
    datastoreIds: createForm.value.datastoreIds,
  }
  const kb = await store.create(req)
  if (kb) {
    showCreate.value = false
    createForm.value = { name: '', description: '', embeddingModel: '', tags: [], datastoreIds: [] }
  }
}

function startEdit(kb: KnowledgeBase) {
  editingKb.value = kb
  editForm.value = {
    description: kb.description || '',
    tags: [...(kb.tags ?? [])],
    datastoreIds: [...(kb.datastoreIds ?? [])],
  }
}

function closeEditDialog() {
  editingKb.value = null
}

function openDeleteDialog(kb: KnowledgeBase) {
  deleteTarget.value = { type: 'kb', id: kb.id, name: kb.name }
}

async function handleUpdate() {
  if (!editingKb.value) return
  try {
    await knowledgeBaseApi.update(editingKb.value.id, {
      description: editForm.value.description || undefined,
      tags: editForm.value.tags,
      datastoreIds: editForm.value.datastoreIds,
    })
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

function toggleCreateDatastore(id: string, checked: boolean | 'indeterminate') {
  if (checked === true) {
    if (!createForm.value.datastoreIds.includes(id)) createForm.value.datastoreIds.push(id)
    return
  }
  createForm.value.datastoreIds = createForm.value.datastoreIds.filter(datastoreId => datastoreId !== id)
}

function toggleEditDatastore(id: string, checked: boolean | 'indeterminate') {
  if (checked === true) {
    if (!editForm.value.datastoreIds.includes(id)) editForm.value.datastoreIds.push(id)
    return
  }
  editForm.value.datastoreIds = editForm.value.datastoreIds.filter(datastoreId => datastoreId !== id)
}

function resolveDatastoreNames(datastoreIds: string[] | undefined) {
  if (!datastoreIds || datastoreIds.length === 0) {
    return []
  }
  const nameMap = new Map(datastoreStore.list.map(datastore => [datastore.id, datastore.name]))
  return datastoreIds.map(id => nameMap.get(id) ?? id)
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-4 sm:py-5">
      <div class="page-stack">
        <PageHeader
          eyebrow="知识库"
          title="知识库"
        >
          <template #actions>
            <Button type="button" @click="showCreate = true">
              <Plus class="size-4" />
              新建知识库
            </Button>
          </template>
          <template #meta>
            <MetricCard label="知识库" :value="store.list.length" hint="可管理的知识库总数">
              <template #icon>
                <Database class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="文档" :value="totalDocuments" hint="所有知识库的文档总数">
              <template #icon>
                <Files class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="分块" :value="totalChunks" hint="所有文档的分块总数">
              <template #icon>
                <Layers3 class="size-5" />
              </template>
            </MetricCard>
          </template>
        </PageHeader>

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
          <div class="grid gap-4 xl:grid-cols-[minmax(0,1fr)_220px]">
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
                class="kb-filter-panel grid gap-4 p-4 lg:grid-cols-[minmax(0,1fr)_220px]"
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

            <div class="kb-results-summary">
              <div class="surface-label text-[0.68rem]">结果</div>
              <div class="text-xl font-semibold tracking-tight text-foreground">
                {{ filteredKbs.length }}
              </div>
              <div class="text-xs text-muted-foreground">
                共 {{ store.list.length }}
              </div>
              <Button v-if="hasFilters" type="button" variant="ghost" class="mt-1 h-8 justify-start px-0" @click="clearFilters">
                清空筛选
              </Button>
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
              v-for="(kb, index) in filteredKbs"
              :key="kb.id"
              class="kb-card list-card group relative cursor-pointer p-4"
              @click="selectKb(kb)"
            >
              <div class="flex items-start justify-between gap-3">
                <div class="flex min-w-0 items-start gap-3">
                  <div class="kb-card-index">
                    {{ String(index + 1).padStart(2, '0') }}
                  </div>
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
                      <span class="surface-chip">Datastore {{ kb.datastoreIds?.length ?? 0 }}</span>
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
                    @click.stop="openDeleteDialog(kb)"
                  >
                    <Trash2 class="size-4" />
                  </Button>
                </div>
              </div>

              <p class="mt-4 line-clamp-3 text-sm leading-6 text-muted-foreground">
                {{ kb.description || '这个知识库还没有描述信息。' }}
              </p>

              <div v-if="(kb.datastoreIds?.length ?? 0) > 0" class="mt-4 flex flex-wrap gap-2">
                <Badge
                  v-for="name in resolveDatastoreNames(kb.datastoreIds).slice(0, 3)"
                  :key="`${kb.id}-${name}`"
                  variant="outline"
                  class="text-xs"
                >
                  {{ name }}
                </Badge>
                <Badge
                  v-if="resolveDatastoreNames(kb.datastoreIds).length > 3"
                  variant="outline"
                  class="text-xs"
                >
                  +{{ resolveDatastoreNames(kb.datastoreIds).length - 3 }}
                </Badge>
              </div>

              <div class="mt-5 grid gap-3 sm:grid-cols-2">
                <div class="kb-mini-stat">
                  <div class="mb-1 flex items-center gap-2 text-sm font-medium text-foreground">
                    <Files class="size-4 text-primary" />
                    文档数
                  </div>
                  <p class="text-sm text-muted-foreground">{{ kb.documentCount }} 篇</p>
                </div>

                <div class="kb-mini-stat">
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

          <div class="space-y-2">
            <Label>向量模型</Label>
            <Select v-model="createForm.embeddingModel">
              <SelectTrigger class="w-full">
                <SelectValue placeholder="使用系统默认" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem
                  v-for="p in embeddingProviders"
                  :key="p.id"
                  :value="p.modelName"
                >
                  {{ p.displayName || p.modelName }}
                </SelectItem>
              </SelectContent>
            </Select>
            <p class="text-xs text-muted-foreground">不选择则使用系统默认的 Embedding 模型</p>
          </div>

          <div class="space-y-2">
            <div class="flex items-center justify-between gap-3">
              <Label>关联 Datastore</Label>
              <Button type="button" variant="ghost" size="sm" @click="refreshDatastores()">
                刷新
              </Button>
            </div>
            <div v-if="datastoreStore.loading" class="kb-dialog-note rounded-md px-3 py-3 text-sm text-muted-foreground">
              正在加载 Datastore 列表...
            </div>
            <div v-else-if="datastoreStore.error" class="rounded-md border border-destructive/20 bg-destructive/5 px-3 py-3 text-sm text-destructive">
              Datastore 列表加载失败：{{ datastoreStore.error }}
            </div>
            <div v-else-if="datastoreStore.list.length > 0" class="max-h-44 space-y-1 overflow-y-auto rounded-md border border-border/60 bg-background/55 p-2">
              <label
                v-for="datastore in datastoreStore.list"
                :key="datastore.id"
                class="kb-dialog-option"
              >
                <Checkbox
                  :model-value="createForm.datastoreIds.includes(datastore.id)"
                  @update:model-value="toggleCreateDatastore(datastore.id, $event)"
                />
                <div class="min-w-0">
                  <div class="text-sm text-foreground">{{ datastore.name }}</div>
                  <div v-if="datastore.description" class="text-xs text-muted-foreground">
                    {{ datastore.description }}
                  </div>
                </div>
              </label>
            </div>
            <div v-else class="kb-dialog-note rounded-md px-3 py-3 text-sm text-muted-foreground">
              当前没有可关联的 Datastore。知识库可以先创建，后续再补充关联。
            </div>
            <p class="text-xs text-muted-foreground">绑定后，datastore 结构化数据会同步到该知识库。</p>
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

    <Dialog :open="!!editingKb" @update:open="(value: boolean) => { if (!value) closeEditDialog() }">
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
          </div>

          <div class="space-y-2">
            <div class="flex items-center justify-between gap-3">
              <Label>关联 Datastore</Label>
              <Button type="button" variant="ghost" size="sm" @click="refreshDatastores()">
                刷新
              </Button>
            </div>
            <div v-if="datastoreStore.loading" class="kb-dialog-note rounded-md px-3 py-3 text-sm text-muted-foreground">
              正在加载 Datastore 列表...
            </div>
            <div v-else-if="datastoreStore.error" class="rounded-md border border-destructive/20 bg-destructive/5 px-3 py-3 text-sm text-destructive">
              Datastore 列表加载失败：{{ datastoreStore.error }}
            </div>
            <div v-else-if="datastoreStore.list.length > 0" class="max-h-44 space-y-1 overflow-y-auto rounded-md border border-border/60 bg-background/55 p-2">
              <label
                v-for="datastore in datastoreStore.list"
                :key="datastore.id"
                class="kb-dialog-option"
              >
                <Checkbox
                  :model-value="editForm.datastoreIds.includes(datastore.id)"
                  @update:model-value="toggleEditDatastore(datastore.id, $event)"
                />
                <div class="min-w-0">
                  <div class="text-sm text-foreground">{{ datastore.name }}</div>
                  <div v-if="datastore.description" class="text-xs text-muted-foreground">
                    {{ datastore.description }}
                  </div>
                </div>
              </label>
            </div>
            <div v-else class="kb-dialog-note rounded-md px-3 py-3 text-sm text-muted-foreground">
              当前没有可关联的 Datastore。
            </div>
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" @click="closeEditDialog()">
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

<style scoped>
.kb-filter-panel {
  border-radius: calc(var(--radius) + 4px);
  border: 1px solid hsl(from var(--border) h s l / 0.64);
  background: linear-gradient(180deg, hsl(from var(--card) h s l / 0.84), hsl(from var(--background) h s l / 0.72));
}

.kb-results-summary {
  border-left: 1px solid hsl(from var(--border) h s l / 0.58);
  padding-left: 1rem;
}

.kb-card {
  background: linear-gradient(180deg, hsl(from var(--card) h s l / 0.95), hsl(from var(--background) h s l / 0.86));
}

.kb-card-index {
  display: inline-flex;
  min-width: 1.9rem;
  height: 1.55rem;
  align-items: center;
  justify-content: center;
  margin-top: 0.1rem;
  border-radius: 0.78rem;
  border: 1px solid hsl(from var(--border) h s l / 0.48);
  background: hsl(from var(--card) h s l / 0.8);
  color: hsl(from var(--muted-foreground) h s l / 0.78);
  font-family: var(--font-mono);
  font-size: 10px;
}

.kb-mini-stat {
  border-radius: calc(var(--radius) + 6px);
  border: 1px solid hsl(from var(--border) h s l / 0.54);
  background: linear-gradient(180deg, hsl(from var(--card) h s l / 0.8), hsl(from var(--background) h s l / 0.68));
  padding: 0.8rem 1rem;
}

.kb-dialog-option {
  display: flex;
  cursor: pointer;
  align-items: flex-start;
  gap: 0.5rem;
  border: 1px solid transparent;
  border-radius: 0.75rem;
  padding: 0.5rem;
  transition:
    border-color 180ms var(--ease-fluid),
    background-color 180ms var(--ease-fluid),
    transform 180ms var(--ease-fluid);
}

.kb-dialog-option:hover {
  transform: translateY(-1px);
  border-color: hsl(from var(--primary) h s l / 0.16);
  background: hsl(from var(--accent) h s l / 0.32);
}

.kb-dialog-note {
  border: 1px dashed hsl(from var(--border) h s l / 0.62);
  background: hsl(from var(--background) h s l / 0.62);
}

@media (max-width: 1279px) {
  .kb-results-summary {
    border-left: none;
    border-top: 1px solid hsl(from var(--border) h s l / 0.58);
    padding-top: 0.9rem;
    padding-left: 0;
  }
}

</style>
