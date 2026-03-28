<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowUpRight,
  Database,
  FileJson2,
  RefreshCw,
  Search,
  SlidersHorizontal,
  Trash2,
} from 'lucide-vue-next'
import type { Datastore } from '@/types'
import { useDatastoreStore } from '@/stores/datastore'
import { useUiStore } from '@/stores/ui'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'

type PropertyDefinition = {
  name?: string
  type?: string
  required?: boolean
}

const datastoreStore = useDatastoreStore()
const uiStore = useUiStore()
const router = useRouter()

const searchQuery = ref('')
const typeFilter = ref('all')
const showFilters = ref(false)
const deleteTarget = ref<Datastore | null>(null)
const deletingDatastoreId = ref<string | null>(null)

onMounted(() => {
  void datastoreStore.fetchList()
})

const filteredDatastores = computed(() => {
  let result = [...datastoreStore.list]
  if (searchQuery.value.trim()) {
    const keyword = searchQuery.value.trim().toLowerCase()
    result = result.filter(datastore => (
      datastore.name.toLowerCase().includes(keyword)
      || (datastore.description ?? '').toLowerCase().includes(keyword)
    ))
  }
  if (typeFilter.value !== 'all') {
    result = result.filter(datastore => datastore.type === typeFilter.value)
  }
  return result
})

const allTypes = computed(() => (
  Array.from(new Set(datastoreStore.list.map(item => item.type))).sort()
))

const totalPropertyCount = computed(() => (
  datastoreStore.list.reduce((sum, datastore) => sum + parsePropertyDefinitions(datastore).length, 0)
))

const projectionEnabledCount = computed(() => (
  datastoreStore.list.filter(datastore => {
    const normalized = datastore.projectionConfigJson?.trim()
    return Boolean(normalized && normalized !== '{}' && normalized !== 'null')
  }).length
))

const hasFilters = computed(() => (
  Boolean(searchQuery.value.trim()) || typeFilter.value !== 'all'
))

const showDeleteConfirm = computed({
  get: () => deleteTarget.value !== null,
  set: (value: boolean) => {
    if (!value) {
      deleteTarget.value = null
    }
  },
})

function openDatastore(datastore: Datastore) {
  void router.push({
    name: 'datastoreDetail',
    params: { id: datastore.id },
  })
}

function refreshDatastores() {
  void datastoreStore.fetchList()
}

function clearFilters() {
  searchQuery.value = ''
  typeFilter.value = 'all'
}

function openDeleteDialog(datastore: Datastore, event?: Event) {
  event?.stopPropagation()
  deleteTarget.value = datastore
}

async function handleDeleteDatastore() {
  if (deletingDatastoreId.value) {
    return
  }
  const target = deleteTarget.value
  if (!target) {
    return
  }
  deletingDatastoreId.value = target.id
  try {
    await datastoreStore.deleteDatastore(target.id)
    uiStore.showToast('success', `Datastore「${target.name}」已删除`)
  } catch (event: any) {
    uiStore.showToast('error', event?.message ?? '删除 Datastore 失败')
  } finally {
    deletingDatastoreId.value = null
    deleteTarget.value = null
  }
}

function formatDate(value?: string | null) {
  if (!value) return '—'
  return new Date(value).toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

function parsePropertyDefinitions(datastore: Datastore): PropertyDefinition[] {
  if (!datastore.propertiesJson?.trim()) {
    return []
  }
  try {
    const parsed = JSON.parse(datastore.propertiesJson)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function summarizeProperties(datastore: Datastore) {
  const properties = parsePropertyDefinitions(datastore)
  if (properties.length === 0) {
    return '未定义结构字段'
  }
  const preview = properties
    .slice(0, 3)
    .map(item => item.name || '未命名字段')
    .join('、')
  return properties.length > 3
    ? `${preview} 等 ${properties.length} 个字段`
    : preview
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack">
        <PageHeader
          eyebrow="Datastore"
          title="Datastore"
          description="查看领域数据容器的结构、投影配置和更新时间。每个 Datastore 都会自动维护内部知识库，用来承载文档和语义检索。"
        >
          <template #actions>
            <Button type="button" variant="outline" @click="showFilters = !showFilters">
              <SlidersHorizontal class="size-4" />
              {{ showFilters ? '收起筛选' : '筛选' }}
            </Button>
            <Button type="button" variant="outline" @click="refreshDatastores">
              <RefreshCw class="size-4" />
              刷新
            </Button>
          </template>

          <template #meta>
            <MetricCard label="Datastore 数量" :value="datastoreStore.list.length" hint="当前可浏览的领域数据容器数量。">
              <template #icon>
                <Database class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="结构字段" :value="totalPropertyCount" hint="所有 Datastore 已声明字段总数。">
              <template #icon>
                <FileJson2 class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="投影配置" :value="projectionEnabledCount" hint="已启用自定义 projectionConfig 的 Datastore 数量。">
              <template #icon>
                <ArrowUpRight class="size-5" />
              </template>
            </MetricCard>
          </template>
        </PageHeader>

        <section v-if="showFilters || hasFilters" class="toolbar-strip">
          <div class="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
            <div class="flex flex-1 flex-col gap-3 md:flex-row md:items-center">
              <div class="min-w-[240px] flex-1">
                <div class="relative">
                  <Search class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    v-model="searchQuery"
                    class="pl-9"
                    placeholder="按名称或描述搜索 Datastore"
                  />
                </div>
              </div>

              <div class="w-full md:w-40">
                <select
                  v-model="typeFilter"
                  class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
                >
                  <option value="all">全部类型</option>
                  <option v-for="type in allTypes" :key="type" :value="type">{{ type }}</option>
                </select>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <div class="toolbar-counter">
                <div class="surface-label text-[0.68rem]">结果</div>
                <div class="toolbar-counter-value">{{ filteredDatastores.length }}</div>
              </div>
              <Button v-if="hasFilters" type="button" variant="ghost" @click="clearFilters">清空</Button>
            </div>
          </div>
        </section>

        <div v-if="datastoreStore.loading && datastoreStore.list.length === 0" class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          <div
            v-for="index in 6"
            :key="index"
            class="detail-card space-y-3 p-5"
          >
            <Skeleton class="h-6 w-32" />
            <Skeleton class="h-4 w-full" />
            <Skeleton class="h-4 w-4/5" />
            <Skeleton class="h-10 w-full" />
          </div>
        </div>

        <StatePanel
          v-else-if="datastoreStore.error"
          title="Datastore 列表加载失败"
          :description="datastoreStore.error"
          tone="danger"
        >
          <template #icon>
            <Database class="size-5" />
          </template>
        </StatePanel>

        <StatePanel
          v-else-if="filteredDatastores.length === 0"
          title="暂无可展示的 Datastore"
          :description="hasFilters ? '当前筛选条件下没有匹配项。' : '当前环境还没有可浏览的 Datastore。'"
        >
          <template #icon>
            <Database class="size-5" />
          </template>
        </StatePanel>

        <div v-else class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          <article
            v-for="datastore in filteredDatastores"
            :key="datastore.id"
            class="detail-card cursor-pointer p-5 transition-colors hover:border-primary/35 hover:bg-muted/20"
            @click="openDatastore(datastore)"
          >
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <div class="flex flex-wrap items-center gap-2">
                  <h2 class="truncate text-lg font-semibold text-foreground">{{ datastore.name }}</h2>
                  <Badge variant="outline">{{ datastore.type }}</Badge>
                </div>
                <p class="mt-2 line-clamp-2 text-sm leading-6 text-muted-foreground">
                  {{ datastore.description || '暂无描述。' }}
                </p>
              </div>
              <div class="flex items-center gap-1">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  title="删除 Datastore"
                  data-test="delete-datastore-button"
                  @click.stop="openDeleteDialog(datastore, $event)"
                >
                  <Trash2 class="size-4" />
                </Button>
                <ArrowUpRight class="size-4 shrink-0 text-muted-foreground" />
              </div>
            </div>

            <div class="mt-4 grid gap-3 text-sm">
              <div class="flex items-center justify-between gap-3">
                <span class="text-muted-foreground">字段结构</span>
                <span class="text-right font-medium text-foreground">{{ summarizeProperties(datastore) }}</span>
              </div>
              <div class="flex items-center justify-between gap-3">
                <span class="text-muted-foreground">投影配置</span>
                <span class="text-right font-medium text-foreground">
                  {{ datastore.projectionConfigJson && datastore.projectionConfigJson !== '{}' ? '已自定义' : '默认规则' }}
                </span>
              </div>
              <div class="flex items-center justify-between gap-3">
                <span class="text-muted-foreground">更新时间</span>
                <span class="text-right font-medium text-foreground">{{ formatDate(datastore.updatedAt) }}</span>
              </div>
            </div>
          </article>
        </div>
      </div>
    </PageContainer>

    <ConfirmDialog
      v-model:show="showDeleteConfirm"
      title="确认删除 Datastore"
      :message="deleteTarget ? `确定要删除 Datastore「${deleteTarget.name}」吗？集合内文档也会一并删除。` : ''"
      :confirm-label="deletingDatastoreId ? '删除中...' : '删除'"
      confirm-variant="destructive"
      @confirm="handleDeleteDatastore"
      @cancel="deleteTarget = null"
    />
  </div>
</template>
