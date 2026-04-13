<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowUpRight,
  Clock,
  Database,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  SlidersHorizontal,
  Trash2,
  X,
} from 'lucide-vue-next'
import type { Datastore, FieldHintDto } from '@/types'
import { useDatastoreStore } from '@/stores/datastore'
import { useUiStore } from '@/stores/ui'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import FormSheetShell from '@/components/common/FormSheetShell.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
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
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'

const FIELD_TYPES = ['TEXT', 'NUMBER', 'BOOLEAN']

const datastoreStore = useDatastoreStore()
const uiStore = useUiStore()
const router = useRouter()

const searchQuery = ref('')
const typeFilter = ref('all')
const showFilters = ref(false)
const deleteTarget = ref<Datastore | null>(null)
const deletingDatastoreId = ref<string | null>(null)

// --- 新建表单 ---
const showCreate = ref(false)
const creating = ref(false)
const createForm = ref({
  name: '',
  timeSeries: false,
  description: '',
  fieldHints: [] as FieldHintDto[],
})

// --- 编辑表单 ---
const editingDatastore = ref<Datastore | null>(null)
const updating = ref(false)
const editForm = ref({ description: '' })

onMounted(() => {
  void datastoreStore.fetchList()
})

const filteredDatastores = computed(() => {
  let result = [...datastoreStore.list]
  if (searchQuery.value.trim()) {
    const keyword = searchQuery.value.trim().toLowerCase()
    result = result.filter(ds =>
      ds.name.toLowerCase().includes(keyword)
      || (ds.description ?? '').toLowerCase().includes(keyword),
    )
  }
  if (typeFilter.value === 'timeSeries') {
    result = result.filter(ds => ds.timeSeries)
  } else if (typeFilter.value === 'general') {
    result = result.filter(ds => !ds.timeSeries)
  }
  return result
})

const totalFieldCount = computed(() =>
  datastoreStore.list.reduce((sum, ds) => sum + parseFieldHints(ds).length, 0),
)

const timeSeriesCount = computed(() =>
  datastoreStore.list.filter(ds => ds.timeSeries).length,
)

const hasFilters = computed(() =>
  Boolean(searchQuery.value.trim()) || typeFilter.value !== 'all',
)

const showDeleteConfirm = computed({
  get: () => deleteTarget.value !== null,
  set: (value: boolean) => {
    if (!value) deleteTarget.value = null
  },
})

function openDatastore(ds: Datastore) {
  void router.push({ name: 'datastoreDetail', params: { id: ds.id } })
}

function refreshDatastores() {
  void datastoreStore.fetchList()
}

function clearFilters() {
  searchQuery.value = ''
  typeFilter.value = 'all'
}

function openDeleteDialog(ds: Datastore, event?: Event) {
  event?.stopPropagation()
  deleteTarget.value = ds
}

async function handleDeleteDatastore() {
  if (deletingDatastoreId.value) return
  const target = deleteTarget.value
  if (!target) return
  deletingDatastoreId.value = target.id
  try {
    await datastoreStore.deleteDatastore(target.id)
    uiStore.showToast('success', `Datastore「${target.name}」已删除`)
  } catch (e: any) {
    uiStore.showToast('error', e?.message ?? '删除 Datastore 失败')
  } finally {
    deletingDatastoreId.value = null
    deleteTarget.value = null
  }
}

// --- 新建 ---
function resetCreateForm() {
  createForm.value = { name: '', timeSeries: false, description: '', fieldHints: [] }
}

function addFieldHint() {
  createForm.value.fieldHints.push({ name: '', type: 'TEXT', description: null })
}

function removeFieldHint(index: number) {
  createForm.value.fieldHints.splice(index, 1)
}

async function handleCreate() {
  if (creating.value) return
  const form = createForm.value
  if (!form.name.trim()) {
    uiStore.showToast('error', '请输入 Datastore 名称')
    return
  }
  creating.value = true
  try {
    await datastoreStore.createDatastore({
      name: form.name.trim(),
      timeSeries: form.timeSeries,
      description: form.description.trim() || null,
      fieldHints: form.fieldHints.length > 0 ? form.fieldHints.filter(f => f.name.trim()) : null,
    })
    uiStore.showToast('success', `Datastore「${form.name}」创建成功`)
    showCreate.value = false
    resetCreateForm()
  } catch (e: any) {
    uiStore.showToast('error', e?.message ?? '创建 Datastore 失败')
  } finally {
    creating.value = false
  }
}

// --- 编辑 ---
function openEditDialog(ds: Datastore) {
  editingDatastore.value = ds
  editForm.value = { description: ds.description ?? '' }
}

function closeEditDialog() {
  editingDatastore.value = null
  editForm.value = { description: '' }
}

async function handleUpdate() {
  if (updating.value || !editingDatastore.value) return
  updating.value = true
  try {
    await datastoreStore.updateDatastore(editingDatastore.value.id, {
      description: editForm.value.description.trim() || null,
    })
    uiStore.showToast('success', `Datastore「${editingDatastore.value.name}」已更新`)
    closeEditDialog()
  } catch (e: any) {
    uiStore.showToast('error', e?.message ?? '更新 Datastore 失败')
  } finally {
    updating.value = false
  }
}

function formatDate(value?: string | null) {
  if (!value) return '—'
  return new Date(value).toLocaleDateString('zh-CN', {
    year: 'numeric', month: 'short', day: 'numeric',
  })
}

function parseFieldHints(ds: Datastore): FieldHintDto[] {
  if (!ds.fieldHintsJson?.trim()) return []
  try {
    const parsed = JSON.parse(ds.fieldHintsJson)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function summarizeFieldHints(ds: Datastore) {
  const hints = parseFieldHints(ds)
  if (hints.length === 0) return '未定义索引字段'
  const preview = hints.slice(0, 3).map(h => h.name || '未命名').join('、')
  return hints.length > 3 ? `${preview} 等 ${hints.length} 个字段` : preview
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-4 sm:py-5">
      <div class="page-stack">
        <PageHeader
          eyebrow="Datastore"
          title="资料仓库"
          :description="`${datastoreStore.list.length} 个集合，${totalFieldCount} 个索引字段，${timeSeriesCount} 个时序集合`"
        >
          <template #actions>
            <Button type="button" variant="outline" @click="refreshDatastores">
              <RefreshCw class="size-4" />
              刷新
            </Button>
            <Button type="button" @click="showCreate = true">
              <Plus class="size-4" />
              新建
            </Button>
          </template>
        </PageHeader>

        <section class="toolbar-strip">
          <div class="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
            <div class="flex flex-1 items-center gap-3">
              <div class="relative min-w-[240px] flex-1">
                <Search class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                <Input v-model="searchQuery" class="pl-9" placeholder="按名称或描述搜索" />
              </div>
              <Button variant="outline" size="sm" @click="showFilters = !showFilters">
                <SlidersHorizontal class="size-4" />
                筛选
              </Button>
            </div>
            <div class="flex items-center gap-3">
              <div class="toolbar-counter">
                <div class="surface-label text-[0.68rem]">结果</div>
                <div class="toolbar-counter-value">{{ filteredDatastores.length }}</div>
              </div>
              <Button v-if="hasFilters" type="button" variant="ghost" @click="clearFilters">清空</Button>
            </div>
          </div>

          <div v-if="showFilters" class="mt-sm rounded-xl border border-border/40 bg-card/60 p-md">
            <div class="w-full md:w-40">
              <select
                v-model="typeFilter"
                class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
              >
                <option value="all">全部类型</option>
                <option value="general">普通集合</option>
                <option value="timeSeries">时序集合</option>
              </select>
            </div>
          </div>
        </section>

        <div v-if="datastoreStore.loading && datastoreStore.list.length === 0" class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          <div v-for="index in 6" :key="index" class="detail-card space-y-3 p-5">
            <Skeleton class="h-6 w-32" />
            <Skeleton class="h-4 w-full" />
            <Skeleton class="h-4 w-4/5" />
            <Skeleton class="h-10 w-full" />
          </div>
        </div>

        <StatePanel
          v-else-if="datastoreStore.error"
          title="列表加载失败"
          :description="datastoreStore.error"
          tone="danger"
        >
          <template #icon><Database class="size-5" /></template>
        </StatePanel>

        <StatePanel
          v-else-if="filteredDatastores.length === 0"
          title="暂无可展示的集合"
          :description="hasFilters ? '当前筛选条件下没有匹配项。' : '当前环境还没有可浏览的集合。'"
        >
          <template #icon><Database class="size-5" /></template>
          <template v-if="!hasFilters" #actions>
            <Button type="button" @click="showCreate = true">
              <Plus class="size-4" />
              新建集合
            </Button>
          </template>
        </StatePanel>

        <div v-else class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          <article
            v-for="ds in filteredDatastores"
            :key="ds.id"
            class="detail-card cursor-pointer p-5 transition-colors hover:border-primary/35 hover:bg-muted/20"
            @click="openDatastore(ds)"
          >
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <div class="flex flex-wrap items-center gap-2">
                  <h2 class="truncate text-lg font-semibold text-foreground">{{ ds.name }}</h2>
                  <Badge v-if="ds.timeSeries" variant="outline" class="gap-1">
                    <Clock class="size-3" />
                    时序
                  </Badge>
                </div>
                <p class="mt-2 line-clamp-2 text-sm leading-6 text-muted-foreground">
                  {{ ds.description || '暂无描述。' }}
                </p>
              </div>
              <div class="flex items-center gap-1">
                <Button type="button" variant="ghost" size="icon" title="编辑" @click.stop="openEditDialog(ds)">
                  <Pencil class="size-4" />
                </Button>
                <Button
                  type="button" variant="ghost" size="icon" title="删除"
                  data-test="delete-datastore-button"
                  @click.stop="openDeleteDialog(ds, $event)"
                >
                  <Trash2 class="size-4" />
                </Button>
                <ArrowUpRight class="size-4 shrink-0 text-muted-foreground" />
              </div>
            </div>

            <div class="mt-4 grid gap-3 text-sm">
              <div class="flex items-center justify-between gap-3">
                <span class="text-muted-foreground">索引字段</span>
                <span class="text-right font-medium text-foreground">{{ summarizeFieldHints(ds) }}</span>
              </div>
              <div class="flex items-center justify-between gap-3">
                <span class="text-muted-foreground">更新时间</span>
                <span class="text-right font-medium text-foreground">{{ formatDate(ds.updatedAt) }}</span>
              </div>
            </div>
          </article>
        </div>
      </div>
    </PageContainer>

    <!-- 删除确认 -->
    <ConfirmDialog
      v-model:show="showDeleteConfirm"
      title="确认删除集合"
      :message="deleteTarget ? `确定要删除「${deleteTarget.name}」吗？集合内所有文档也会一并删除。` : ''"
      :confirm-label="deletingDatastoreId ? '删除中...' : '删除'"
      confirm-variant="destructive"
      @confirm="handleDeleteDatastore"
      @cancel="deleteTarget = null"
    />

    <!-- 新建 -->
    <FormSheetShell
      :open="showCreate"
      title="新建集合"
      description="创建一个新的资料集合来管理领域数据。"
      @update:open="(v: boolean) => { showCreate = v; if (!v) resetCreateForm() }"
      @close="showCreate = false; resetCreateForm()"
    >
      <form class="grid gap-4" @submit.prevent="handleCreate">
        <div class="space-y-2">
          <Label for="ds-name">名称</Label>
          <Input id="ds-name" v-model="createForm.name" placeholder="输入集合名称" />
        </div>

        <div class="flex items-center justify-between rounded-lg border border-border/50 p-sm">
          <div>
            <Label>时序集合</Label>
            <p class="text-xs text-muted-foreground">启用后文档需带时间戳，支持聚合查询（体重、运动量等）</p>
          </div>
          <Switch v-model:checked="createForm.timeSeries" />
        </div>

        <div class="space-y-2">
          <Label for="ds-desc">描述</Label>
          <Textarea
            id="ds-desc"
            v-model="createForm.description"
            :rows="3"
            placeholder="描述这个集合的用途，有助于语义检索质量"
            class="resize-none"
          />
        </div>

        <!-- 字段提示 -->
        <div class="space-y-2">
          <div class="flex items-center justify-between">
            <Label>索引字段（可选）</Label>
            <Button type="button" variant="ghost" size="sm" @click="addFieldHint">
              <Plus class="size-4" />
              添加字段
            </Button>
          </div>
          <p class="text-xs text-muted-foreground">声明后可按字段排序、过滤，加速结构化查询。</p>

          <div v-if="createForm.fieldHints.length > 0" class="space-y-2">
            <div
              v-for="(hint, index) in createForm.fieldHints"
              :key="index"
              class="flex items-start gap-2 rounded-lg border border-border/50 bg-background/50 p-sm"
            >
              <div class="flex-1 space-y-2">
                <div class="flex gap-2">
                  <Input v-model="hint.name" placeholder="字段名" class="flex-1" />
                  <Select v-model="hint.type">
                    <SelectTrigger class="w-32">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem v-for="ft in FIELD_TYPES" :key="ft" :value="ft">{{ ft }}</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <Input :model-value="hint.description ?? ''" placeholder="字段语义描述（可选，如「用户评分，1-5分」）" class="text-xs" @update:model-value="(v: string | number) => hint.description = String(v) || null" />
              </div>
              <Button type="button" variant="ghost" size="icon" class="shrink-0" @click="removeFieldHint(index)">
                <X class="size-4" />
              </Button>
            </div>
          </div>
        </div>
      </form>

      <template #footer>
        <div class="flex items-center justify-end gap-2">
          <Button type="button" variant="outline" @click="showCreate = false; resetCreateForm()">取消</Button>
          <Button :disabled="creating" @click="handleCreate">{{ creating ? '创建中...' : '创建' }}</Button>
        </div>
      </template>
    </FormSheetShell>

    <!-- 编辑 -->
    <FormSheetShell
      :open="!!editingDatastore"
      title="编辑集合"
      description="修改集合描述。名称和字段定义创建后不可更改。"
      @update:open="(v: boolean) => { if (!v) closeEditDialog() }"
      @close="closeEditDialog"
    >
      <form class="grid gap-4" @submit.prevent="handleUpdate">
        <div class="space-y-2">
          <Label>名称</Label>
          <Input :model-value="editingDatastore?.name" disabled />
        </div>
        <div class="space-y-2">
          <Label for="edit-ds-desc">描述</Label>
          <Textarea
            id="edit-ds-desc"
            v-model="editForm.description"
            :rows="3"
            placeholder="输入集合描述"
            class="resize-none"
          />
        </div>
      </form>

      <template #footer>
        <div class="flex items-center justify-end gap-2">
          <Button type="button" variant="outline" @click="closeEditDialog">取消</Button>
          <Button :disabled="updating" @click="handleUpdate">{{ updating ? '保存中...' : '保存' }}</Button>
        </div>
      </template>
    </FormSheetShell>
  </div>
</template>
