<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowUpRight,
  Database,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  SlidersHorizontal,
  Trash2,
  X,
} from 'lucide-vue-next'
import type { Datastore, PropertyDefinitionDto } from '@/types'
import { useDatastoreStore } from '@/stores/datastore'
import { useUiStore } from '@/stores/ui'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import FormSheetShell from '@/components/common/FormSheetShell.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
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
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'

type PropertyDefinition = {
  name?: string
  type?: string
  required?: boolean
}

const COLLECTION_TYPES = [
  { value: 'DOCUMENT', label: '结构化列表', desc: '书单、购物清单、联系人等' },
  { value: 'NOTE', label: '非结构化笔记', desc: '日记、灵感，支持全文搜索' },
  { value: 'METRIC', label: '时序指标', desc: '体重、运动量，支持聚合查询' },
]

const PROPERTY_TYPES = ['TEXT', 'NUMBER', 'BOOLEAN', 'DATE', 'DATETIME', 'SELECT', 'MULTI_SELECT', 'JSON']

const datastoreStore = useDatastoreStore()
const uiStore = useUiStore()
const router = useRouter()

const searchQuery = ref('')
const typeFilter = ref('all')
const showFilters = ref(false)
const deleteTarget = ref<Datastore | null>(null)
const deletingDatastoreId = ref<string | null>(null)

// ─── 新建表单 ───
const showCreate = ref(false)
const creating = ref(false)
const createForm = ref({
  name: '',
  type: 'DOCUMENT',
  description: '',
  properties: [] as PropertyDefinitionDto[],
})

// ─── 编辑表单 ───
const editingDatastore = ref<Datastore | null>(null)
const updating = ref(false)
const editForm = ref({
  description: '',
  projectionConfigJson: '',
})

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

// ─── 新建 ───
function resetCreateForm() {
  createForm.value = { name: '', type: 'DOCUMENT', description: '', properties: [] }
}

function addProperty() {
  createForm.value.properties.push({ name: '', type: 'TEXT', required: false, description: null })
}

function removeProperty(index: number) {
  createForm.value.properties.splice(index, 1)
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
      type: form.type,
      description: form.description.trim() || null,
      properties: form.properties.length > 0 ? form.properties.filter(p => p.name.trim()) : null,
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

// ─── 编辑 ───
function openEditDialog(datastore: Datastore) {
  editingDatastore.value = datastore
  editForm.value = {
    description: datastore.description ?? '',
    projectionConfigJson: datastore.projectionConfigJson ?? '',
  }
}

function closeEditDialog() {
  editingDatastore.value = null
  editForm.value = { description: '', projectionConfigJson: '' }
}

async function handleUpdate() {
  if (updating.value || !editingDatastore.value) return
  updating.value = true
  try {
    const desc = editForm.value.description.trim()
    const projection = editForm.value.projectionConfigJson.trim()
    await datastoreStore.updateDatastore(editingDatastore.value.id, {
      description: desc || null,
      projectionConfigJson: projection || null,
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

function typeLabel(type: string) {
  return COLLECTION_TYPES.find(t => t.value === type)?.label ?? type
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-4 sm:py-5">
      <div class="page-stack">
        <PageHeader
          eyebrow="Datastore"
          title="Datastore"
          :description="`${datastoreStore.list.length} 个 Datastore，${totalPropertyCount} 个结构字段，${projectionEnabledCount} 个已配置投影`"
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
                <Input
                  v-model="searchQuery"
                  class="pl-9"
                  placeholder="按名称或描述搜索 Datastore"
                />
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
                <option v-for="type in allTypes" :key="type" :value="type">{{ type }}</option>
              </select>
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
          <template v-if="!hasFilters" #actions>
            <Button type="button" @click="showCreate = true">
              <Plus class="size-4" />
              新建 Datastore
            </Button>
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
                  <Badge variant="outline">{{ typeLabel(datastore.type) }}</Badge>
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
                  title="编辑 Datastore"
                  aria-label="编辑 Datastore"
                  @click.stop="openEditDialog(datastore)"
                >
                  <Pencil class="size-4" />
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  title="删除 Datastore"
                  aria-label="删除 Datastore"
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

    <!-- 删除确认 -->
    <ConfirmDialog
      v-model:show="showDeleteConfirm"
      title="确认删除 Datastore"
      :message="deleteTarget ? `确定要删除 Datastore「${deleteTarget.name}」吗？集合内文档也会一并删除。` : ''"
      :confirm-label="deletingDatastoreId ? '删除中...' : '删除'"
      confirm-variant="destructive"
      @confirm="handleDeleteDatastore"
      @cancel="deleteTarget = null"
    />

    <!-- 新建 Datastore -->
    <FormSheetShell
      :open="showCreate"
      title="新建 Datastore"
      description="创建一个新的资料仓库来管理结构化数据、笔记或时序指标。"
      @update:open="(value: boolean) => { showCreate = value; if (!value) resetCreateForm() }"
      @close="showCreate = false; resetCreateForm()"
    >
      <form class="grid gap-4" @submit.prevent="handleCreate">
        <div class="space-y-2">
          <Label for="ds-name">名称</Label>
          <Input
            id="ds-name"
            v-model="createForm.name"
            placeholder="输入 Datastore 名称"
          />
        </div>

        <div class="space-y-2">
          <Label>类型</Label>
          <Select v-model="createForm.type">
            <SelectTrigger class="w-full">
              <SelectValue placeholder="选择类型" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem
                v-for="ct in COLLECTION_TYPES"
                :key="ct.value"
                :value="ct.value"
              >
                {{ ct.label }}
              </SelectItem>
            </SelectContent>
          </Select>
          <p class="text-xs text-muted-foreground">
            {{ COLLECTION_TYPES.find(t => t.value === createForm.type)?.desc }}
          </p>
        </div>

        <div class="space-y-2">
          <Label for="ds-desc">描述</Label>
          <Textarea
            id="ds-desc"
            v-model="createForm.description"
            :rows="3"
            placeholder="输入 Datastore 描述（可选）"
            class="resize-none"
          />
        </div>

        <!-- 属性定义（仅 DOCUMENT 类型有意义） -->
        <div v-if="createForm.type === 'DOCUMENT'" class="space-y-2">
          <div class="flex items-center justify-between">
            <Label>字段定义</Label>
            <Button type="button" variant="ghost" size="sm" @click="addProperty">
              <Plus class="size-4" />
              添加字段
            </Button>
          </div>
          <p class="text-xs text-muted-foreground">定义结构化数据的字段模式，创建后不可修改。</p>

          <div v-if="createForm.properties.length > 0" class="space-y-2">
            <div
              v-for="(prop, index) in createForm.properties"
              :key="index"
              class="flex items-start gap-2 rounded-lg border border-border/50 bg-background/50 p-sm"
            >
              <div class="flex-1 space-y-2">
                <div class="flex gap-2">
                  <Input
                    v-model="prop.name"
                    placeholder="字段名"
                    class="flex-1"
                  />
                  <Select v-model="prop.type">
                    <SelectTrigger class="w-32">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem
                        v-for="pt in PROPERTY_TYPES"
                        :key="pt"
                        :value="pt"
                      >
                        {{ pt }}
                      </SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <div class="flex items-center gap-2">
                  <label class="flex items-center gap-xs text-xs text-muted-foreground">
                    <Checkbox
                      :model-value="prop.required"
                      @update:model-value="(val: boolean | 'indeterminate') => prop.required = val === true"
                    />
                    必填
                  </label>
                </div>
              </div>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                class="shrink-0"
                @click="removeProperty(index)"
              >
                <X class="size-4" />
              </Button>
            </div>
          </div>
        </div>
      </form>

      <template #footer>
        <div class="flex items-center justify-end gap-2">
          <Button type="button" variant="outline" @click="showCreate = false; resetCreateForm()">
            取消
          </Button>
          <Button :disabled="creating" @click="handleCreate">
            {{ creating ? '创建中...' : '创建' }}
          </Button>
        </div>
      </template>
    </FormSheetShell>

    <!-- 编辑 Datastore -->
    <FormSheetShell
      :open="!!editingDatastore"
      title="编辑 Datastore"
      description="修改描述和投影配置。名称、类型和字段定义创建后不可更改。"
      @update:open="(value: boolean) => { if (!value) closeEditDialog() }"
      @close="closeEditDialog"
    >
      <form class="grid gap-4" @submit.prevent="handleUpdate">
        <div class="space-y-2">
          <Label>名称</Label>
          <Input :model-value="editingDatastore?.name" disabled />
        </div>

        <div class="space-y-2">
          <Label>类型</Label>
          <Input :model-value="editingDatastore ? typeLabel(editingDatastore.type) : ''" disabled />
        </div>

        <div class="space-y-2">
          <Label for="edit-ds-desc">描述</Label>
          <Textarea
            id="edit-ds-desc"
            v-model="editForm.description"
            :rows="3"
            placeholder="输入 Datastore 描述"
            class="resize-none"
          />
        </div>

        <div class="space-y-2">
          <Label for="edit-ds-projection">投影配置</Label>
          <Textarea
            id="edit-ds-projection"
            v-model="editForm.projectionConfigJson"
            :rows="4"
            placeholder="{}"
            class="resize-none font-mono text-xs"
          />
          <p class="text-xs text-muted-foreground">JSON 格式的向量投影配置，留空使用默认规则。</p>
        </div>
      </form>

      <template #footer>
        <div class="flex items-center justify-end gap-2">
          <Button type="button" variant="outline" @click="closeEditDialog">
            取消
          </Button>
          <Button :disabled="updating" @click="handleUpdate">
            {{ updating ? '保存中...' : '保存' }}
          </Button>
        </div>
      </template>
    </FormSheetShell>
  </div>
</template>
