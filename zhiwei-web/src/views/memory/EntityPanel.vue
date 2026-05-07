<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Search,
  Plus,
  Pencil,
  Archive,
  RotateCcw,
  ArrowUpRight,
  SlidersHorizontal,
} from 'lucide-vue-next'
import { memoryApi } from '@/api/client'
import { logger } from '@/utils/logger'
import { useUiStore } from '@/stores/ui'
import type {
  EntitySummary,
  EntityDetail,
  EntityProvenance,
  EntityProvenanceParams,
  EntityListParams,
  EntityCreateRequest,
  EntityUpdateRequest,
} from '@/types'
import { ENTITY_TYPES } from '@/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
} from '@/components/ui/sheet'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { DatePicker } from '@/components/ui/date-picker'
import Pagination from '@/components/common/Pagination.vue'
import { useMemoryStore } from '@/stores/memory'

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
  MANUAL: '手动维护',
  TOOL: '工具写入',
  CONSOLIDATION: '记忆巩固',
  UNKNOWN: '未标注',
}

const ORIGIN_TYPE_OPTIONS = [
  { value: 'CHAT', label: '对话抽取' },
  { value: 'KNOWLEDGE_BASE_DOCUMENT', label: '知识库文档' },
  { value: 'MANUAL', label: '手动维护' },
  { value: 'TOOL', label: '工具写入' },
  { value: 'CONSOLIDATION', label: '记忆巩固' },
] as const

const LIFECYCLE_STATE_LABELS: Record<string, string> = {
  ACTIVE: '可召回',
  COMPLETED: '已完成',
  REGENERATION_NEEDED: '需重建',
  EXPIRED: '已过期',
  ARCHIVED: '已归档',
  CANCELLED: '已取消',
  SUPERSEDED: '已替代',
}

const TEMPORALITY_LABELS: Record<string, string> = {
  PERMANENT: '长期',
  TEMPORARY: '临时',
  TIMELINE: '时间线',
  UNKNOWN: '未标注',
}

const TRUST_LEVEL_LABELS: Record<string, string> = {
  VERIFIED: '已验证',
  EXPLICIT: '用户明确',
  DERIVED: '派生',
  INFERRED: '推断',
  UNVERIFIED: '未验证',
}

const EVIDENCE_KIND_LABELS: Record<string, string> = {
  DOCUMENT_GROUNDED: '文档证据',
  USER_EXPLICIT: '用户明示',
  USER_CONFIRMED: '用户确认',
  TOOL_VERIFIED: '工具验证',
  CHAT_INFERRED: '对话推断',
  BEHAVIOR_INFERRED: '行为推断',
  LLM_SUMMARIZED_EXPERIENCE: '经验总结',
  DERIVED: '派生',
  UNKNOWN: '未知',
}

const HOT_DIGEST_SCOPES = new Set(['USER_PROFILE', 'USER_FACT', 'AGENT_EXPERIENCE'])
const PROMPT_CONSUMABLE_TRUST_LEVELS = new Set(['VERIFIED', 'EXPLICIT', 'DERIVED'])
const RETRIEVABLE_LIFECYCLE_STATES = new Set(['ACTIVE', 'COMPLETED', 'REGENERATION_NEEDED'])

// ── 筛选状态 ──
const filterQ = ref('')
const filterType = ref<string>('')
const filterSpaceId = ref('')
const filterMemoryScope = ref<string>('')
const filterRealityType = ref<string>('')
const filterOriginType = ref<string>('')
const filterSourceKnowledgeBaseId = ref('')
const filterSourceDocumentId = ref('')
const filterTimeFrom = ref('')
const filterTimeTo = ref('')
const filterSortBy = ref('createdAt')
const filterOrder = ref('desc')
const syncingRouteFilters = ref(false)
const advancedFiltersOpen = ref(false)

// ── 列表状态 ──
const PAGE_SIZE = 20
const currentPage = ref(0)
const items = ref<EntitySummary[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

// ── 详情面板状态 ──
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailEntity = ref<EntityDetail | null>(null)
const detailTab = ref('info')
const provenanceItems = ref<EntityProvenance[]>([])
const provenanceLoading = ref(false)
const provenanceOriginType = ref<string>('')
const provenanceKnowledgeBaseId = ref('')
const provenanceDocumentId = ref('')
const loadedProvenanceKey = ref('')

// 版本历史
const historyItems = ref<EntityDetail[]>([])
const historyLoading = ref(false)

// 关联实体
const relatedItems = ref<EntitySummary[]>([])
const relatedLoading = ref(false)

// ── 新建对话框 ──
const createOpen = ref(false)
const creating = ref(false)
const createForm = ref<EntityCreateRequest>({
  name: '',
  type: 'PERSON',
  description: '',
  properties: {},
  importanceScore: 0.5,
})
const createPropsText = ref('{}')

// ── 编辑对话框 ──
const editOpen = ref(false)
const editing = ref(false)
const editForm = ref<EntityUpdateRequest>({
  description: '',
  properties: {},
  importanceScore: 0.5,
})
const editPropsText = ref('{}')
const editEntityId = ref('')

// ── 归档确认对话框 ──
const archiveOpen = ref(false)
const archiving = ref(false)
const archiveEntityId = ref('')
const archiveEntityName = ref('')

// ── 排序选项 ──
const SORT_OPTIONS = [
  { value: 'createdAt', label: '创建时间' },
  { value: 'name', label: '名称' },
  { value: 'importanceScore', label: '重要性分数' },
  { value: 'accessCount', label: '访问次数' },
] as const

const ORDER_OPTIONS = [
  { value: 'desc', label: '降序' },
  { value: 'asc', label: '升序' },
] as const

const MEMORY_SCOPE_OPTIONS = [
  { value: 'USER_PROFILE', label: '用户画像' },
  { value: 'USER_FACT', label: '用户事实' },
  { value: 'AGENT_EXPERIENCE', label: '执行经验' },
  { value: 'DOMAIN_MEMORY', label: '领域记忆' },
] as const

const REALITY_TYPE_OPTIONS = [
  { value: 'REAL', label: '真实' },
  { value: 'FICTIONAL', label: '虚构' },
  { value: 'SIMULATED', label: '模拟' },
  { value: 'UNKNOWN', label: '未标注' },
] as const

const store = useMemoryStore()
const route = useRoute()
const router = useRouter()
const uiStore = useUiStore()
const activeAdvancedFilters = computed(() => [
  filterType.value ? `类型: ${ENTITY_TYPES.find(type => type.value === filterType.value)?.label || filterType.value}` : null,
  filterSpaceId.value.trim() ? `空间: ${filterSpaceId.value.trim()}` : null,
  filterTimeFrom.value ? `开始: ${filterTimeFrom.value}` : null,
  filterTimeTo.value ? `结束: ${filterTimeTo.value}` : null,
  filterOriginType.value ? `来源: ${formatOriginType(filterOriginType.value)}` : null,
  filterSourceKnowledgeBaseId.value.trim() ? `知识库: ${filterSourceKnowledgeBaseId.value.trim()}` : null,
  filterSourceDocumentId.value.trim() ? `文档: ${filterSourceDocumentId.value.trim()}` : null,
].filter((item): item is string => Boolean(item)))

// ── 数据加载 ──
async function loadEntities() {
  loading.value = true
  error.value = null
  try {
    const params: EntityListParams = {
      page: currentPage.value,
      size: PAGE_SIZE,
      sortBy: filterSortBy.value,
      order: filterOrder.value,
    }
    if (filterQ.value.trim()) params.q = filterQ.value.trim()
    if (filterType.value) params.type = filterType.value
    if (filterSpaceId.value.trim()) params.spaceId = filterSpaceId.value.trim()
    if (filterMemoryScope.value) params.memoryScope = filterMemoryScope.value
    if (filterRealityType.value) params.realityType = filterRealityType.value
    if (filterOriginType.value) params.originType = filterOriginType.value
    if (filterSourceKnowledgeBaseId.value.trim()) params.sourceKnowledgeBaseId = filterSourceKnowledgeBaseId.value.trim()
    if (filterSourceDocumentId.value.trim()) params.sourceDocumentId = filterSourceDocumentId.value.trim()
    if (filterTimeFrom.value) params.timeFrom = filterTimeFrom.value
    if (filterTimeTo.value) params.timeTo = filterTimeTo.value

    const result = await memoryApi.listEntities(params)
    items.value = result.items
    total.value = result.total
  } catch (e: any) {
    logger.error('加载实体列表失败:', e)
    error.value = e?.message || '加载实体列表失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 0
  loadEntities()
}

function handlePageChange(page: number) {
  currentPage.value = page
  loadEntities()
}

function normalizeQueryValue(value: unknown) {
  if (Array.isArray(value)) {
    return typeof value[0] === 'string' ? value[0].trim() : ''
  }
  return typeof value === 'string' ? value.trim() : ''
}

function syncListFiltersFromRoute() {
  syncingRouteFilters.value = true
  filterQ.value = normalizeQueryValue(route.query.q)
  filterType.value = normalizeQueryValue(route.query.type)
  filterSpaceId.value = normalizeQueryValue(route.query.spaceId)
  filterMemoryScope.value = normalizeQueryValue(route.query.memoryScope)
  filterRealityType.value = normalizeQueryValue(route.query.realityType)
  filterOriginType.value = normalizeQueryValue(route.query.originType)
  filterSourceKnowledgeBaseId.value = normalizeQueryValue(route.query.sourceKnowledgeBaseId)
  filterSourceDocumentId.value = normalizeQueryValue(route.query.sourceDocumentId)
  currentPage.value = 0
  syncingRouteFilters.value = false
  void loadEntities()
}

// 筛选条件变化时重新加载
watch([filterType, filterMemoryScope, filterRealityType, filterOriginType, filterSortBy, filterOrder, filterTimeFrom, filterTimeTo], () => {
  if (syncingRouteFilters.value) return
  currentPage.value = 0
  loadEntities()
})

watch(
  () => route.query,
  () => {
    syncListFiltersFromRoute()
  },
  { immediate: true }
)

watch(
  () => store.entityDetailRequest,
  (request) => {
    if (!request?.id) return
    void openDetailById(request.id).finally(() => {
      store.clearEntityDetailRequest()
    })
  },
  { immediate: true }
)

// ── 详情面板 ──
async function openDetail(entity: EntitySummary) {
  await openDetailById(entity.id)
}

async function openDetailById(entityId: string) {
  detailOpen.value = true
  detailLoading.value = true
  detailTab.value = 'info'
  detailEntity.value = null
  historyItems.value = []
  relatedItems.value = []
  provenanceItems.value = []
  provenanceOriginType.value = ''
  provenanceKnowledgeBaseId.value = ''
  provenanceDocumentId.value = ''
  loadedProvenanceKey.value = ''

  try {
    detailEntity.value = await memoryApi.getEntity(entityId)
    void fetchProvenances(true)
  } catch (e: any) {
    logger.error('加载实体详情失败:', e)
  } finally {
    detailLoading.value = false
  }
}

async function loadHistory() {
  if (!detailEntity.value || historyItems.value.length > 0) return
  historyLoading.value = true
  try {
    historyItems.value = await memoryApi.getEntityHistory(detailEntity.value.id)
  } catch (e: any) {
    logger.error('加载版本历史失败:', e)
  } finally {
    historyLoading.value = false
  }
}

async function loadRelated() {
  if (!detailEntity.value || relatedItems.value.length > 0) return
  relatedLoading.value = true
  try {
    relatedItems.value = await memoryApi.getRelatedEntities(detailEntity.value.id)
  } catch (e: any) {
    logger.error('加载关联实体失败:', e)
  } finally {
    relatedLoading.value = false
  }
}

async function loadProvenances() {
  return fetchProvenances(false)
}

async function fetchProvenances(force: boolean) {
  if (!detailEntity.value) return
  const currentFilterKey = buildProvenanceFilterKey()
  if (!force && loadedProvenanceKey.value === currentFilterKey) return
  provenanceLoading.value = true
  try {
    provenanceItems.value = await memoryApi.getEntityProvenances(detailEntity.value.id, buildProvenanceParams())
    loadedProvenanceKey.value = currentFilterKey
  } catch (e: any) {
    logger.error('加载来源明细失败:', e)
  } finally {
    provenanceLoading.value = false
  }
}

function handleDetailTabChange(tab: string | number) {
  const nextTab = String(tab)
  detailTab.value = nextTab
  if (nextTab === 'history') loadHistory()
  if (nextTab === 'related') loadRelated()
  if (nextTab === 'provenance') fetchProvenances(false)
}

// ── 新建实体 ──
function openCreate() {
  createForm.value = { name: '', type: 'PERSON', description: '', properties: {}, importanceScore: 0.5 }
  createPropsText.value = '{}'
  createOpen.value = true
}

async function handleCreate() {
  creating.value = true
  try {
    // 解析属性 JSON
    const props = JSON.parse(createPropsText.value || '{}')
    createForm.value.properties = props
    await memoryApi.createEntity(createForm.value)
    createOpen.value = false
    loadEntities()
  } catch (e: any) {
    logger.error('创建实体失败:', e)
    uiStore.showToast('error', e?.message || '创建实体失败')
  } finally {
    creating.value = false
  }
}

// ── 编辑实体 ──
function openEdit(entity: EntityDetail) {
  editEntityId.value = entity.id
  editForm.value = {
    description: entity.description || '',
    properties: entity.properties || {},
    importanceScore: entity.importanceScore,
  }
  editPropsText.value = JSON.stringify(entity.properties || {}, null, 2)
  // 先关闭详情面板，避免与编辑弹窗重叠
  detailOpen.value = false
  editOpen.value = true
}

async function handleEdit() {
  editing.value = true
  try {
    const props = JSON.parse(editPropsText.value || '{}')
    editForm.value.properties = props
    const updated = await memoryApi.updateEntity(editEntityId.value, editForm.value)
    editOpen.value = false
    detailEntity.value = updated
    detailOpen.value = true
    uiStore.showToast('success', '实体已更新')
    loadEntities()
  } catch (e: any) {
    logger.error('编辑实体失败:', e)
    uiStore.showToast('error', e?.message || '编辑实体失败')
  } finally {
    editing.value = false
  }
}

// 编辑弹窗关闭时（取消），重新打开详情面板
function handleEditClose(open: boolean) {
  editOpen.value = open
  if (!open && detailEntity.value) {
    detailOpen.value = true
  }
}

// ── 归档实体 ──
function openArchive(id: string, name: string) {
  archiveEntityId.value = id
  archiveEntityName.value = name
  // 先关闭详情面板，避免与确认弹窗重叠
  detailOpen.value = false
  archiveOpen.value = true
}

async function handleArchive() {
  archiving.value = true
  try {
    await memoryApi.deleteEntity(archiveEntityId.value)
    archiveOpen.value = false
    detailEntity.value = null
    uiStore.showToast('success', '实体已归档')
    loadEntities()
  } catch (e: any) {
    logger.error('归档实体失败:', e)
    uiStore.showToast('error', e?.message || '归档实体失败')
  } finally {
    archiving.value = false
  }
}

// 归档弹窗关闭时（取消），重新打开详情面板
function handleArchiveClose(open: boolean) {
  archiveOpen.value = open
  if (!open && !archiving.value && detailEntity.value) {
    detailOpen.value = true
  }
}

// ── 辅助函数 ──
function formatDate(iso: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

function formatMemoryScope(scope?: string | null) {
  if (!scope) return '未分配'
  return MEMORY_SCOPE_LABELS[scope] || scope
}

function formatRealityType(realityType?: string | null) {
  if (!realityType) return '未标注'
  return REALITY_TYPE_LABELS[realityType] || realityType
}

function formatOriginType(originType?: string | null) {
  if (!originType) return '未知来源'
  return ORIGIN_TYPE_LABELS[originType] || originType
}

function formatSpaceId(spaceId?: string | null) {
  if (!spaceId) return '默认空间'
  return spaceId
}

function abbreviateSpaceId(spaceId?: string | null) {
  if (!spaceId) return '默认空间'
  if (spaceId.length <= 18) return spaceId
  return `${spaceId.slice(0, 8)}...${spaceId.slice(-4)}`
}

function formatListSpaceLabel(item: { memoryScope?: string | null; spaceId?: string | null }) {
  if (!item.spaceId) return '默认空间'
  if (isKnowledgeBaseMemory(item)) return 'KB 图谱空间'
  if (item.memoryScope === 'AGENT_EXPERIENCE') return '经验记忆空间'
  if (item.memoryScope === 'USER_PROFILE' || item.memoryScope === 'USER_FACT') return '个人记忆空间'
  return abbreviateSpaceId(item.spaceId)
}

function shouldShowRealityType(realityType?: string | null) {
  return Boolean(realityType && realityType !== 'UNKNOWN')
}

function formatLifecycleState(state?: string | null) {
  if (!state) return '未知状态'
  return LIFECYCLE_STATE_LABELS[state] || state
}

function formatTemporality(temporality?: string | null) {
  if (!temporality) return '未标注'
  return TEMPORALITY_LABELS[temporality] || temporality
}

function formatTrustLevel(trustLevel?: string | null) {
  if (!trustLevel) return '未验证'
  return TRUST_LEVEL_LABELS[trustLevel] || trustLevel
}

function formatEvidenceKind(evidenceKind?: string | null) {
  if (!evidenceKind) return '未知'
  return EVIDENCE_KIND_LABELS[evidenceKind] || evidenceKind
}

function formatPercentScore(score?: number | null) {
  if (typeof score !== 'number' || Number.isNaN(score)) return '-'
  return `${(Math.max(0, Math.min(1, score)) * 100).toFixed(0)}%`
}

function formatOptionalDate(iso?: string | null) {
  return iso ? formatDate(iso) : '-'
}

function formatBoolean(value?: boolean | null) {
  return value ? '是' : '否'
}

function formatDerivationSources(sources?: string[] | null) {
  if (!sources || sources.length === 0) return '无'
  return sources.join(', ')
}

function isKnowledgeBaseMemory(item: { memoryScope?: string | null; spaceId?: string | null }) {
  return item.memoryScope === 'DOMAIN_MEMORY' || item.spaceId?.startsWith('domain:knowledge-base:')
}

function isHotDigestCandidate(item: {
  memoryScope?: string | null
  lifecycleState?: string | null
  trustLevel?: string | null
}) {
  return Boolean(
    item.memoryScope
    && HOT_DIGEST_SCOPES.has(item.memoryScope)
    && item.lifecycleState
    && RETRIEVABLE_LIFECYCLE_STATES.has(item.lifecycleState)
    && item.trustLevel
    && PROMPT_CONSUMABLE_TRUST_LEVELS.has(item.trustLevel)
  )
}

function formatConsumptionBoundary(item: {
  memoryScope?: string | null
  spaceId?: string | null
  lifecycleState?: string | null
  trustLevel?: string | null
}) {
  if (isKnowledgeBaseMemory(item)) return 'KB 图谱冷召回'
  if (isHotDigestCandidate(item)) return '热摘要候选'
  if (item.memoryScope && HOT_DIGEST_SCOPES.has(item.memoryScope)) return '长期记忆冷召回'
  return '冷召回'
}

function buildProvenanceParams(): EntityProvenanceParams {
  const params: EntityProvenanceParams = {}
  if (provenanceOriginType.value) params.originType = provenanceOriginType.value
  if (provenanceKnowledgeBaseId.value.trim()) params.sourceKnowledgeBaseId = provenanceKnowledgeBaseId.value.trim()
  if (provenanceDocumentId.value.trim()) params.sourceDocumentId = provenanceDocumentId.value.trim()
  return params
}

function buildProvenanceFilterKey() {
  const params = buildProvenanceParams()
  return JSON.stringify(params)
}

function applyProvenanceFilters() {
  loadedProvenanceKey.value = ''
  void fetchProvenances(true)
}

function resetProvenanceFilters() {
  provenanceOriginType.value = ''
  provenanceKnowledgeBaseId.value = ''
  provenanceDocumentId.value = ''
  loadedProvenanceKey.value = ''
  void fetchProvenances(true)
}

function buildNamedReference(name?: string | null, id?: string | null) {
  const normalizedName = name?.trim()
  const normalizedId = id?.trim()
  if (normalizedName && normalizedId && normalizedName !== normalizedId) {
    return `${normalizedName} (${normalizedId})`
  }
  return normalizedName || normalizedId || null
}

function isKnowledgeBaseProvenance(item: EntityProvenance) {
  return item.originType === 'KNOWLEDGE_BASE_DOCUMENT'
    || Boolean(item.sourceKnowledgeBaseId || item.sourceDocumentId)
}

function formatSourceEntryLabel(item: EntityProvenance) {
  return isKnowledgeBaseProvenance(item) ? 'Chunk ID' : '消息 ID'
}

function buildProvenanceDetails(item: EntityProvenance) {
  const details: Array<{ label: string; value: string | null }> = [
    {
      label: '来源引用',
      value: item.sourceReference && item.sourceReference !== item.sourceDocumentId
        ? item.sourceReference
        : null,
    },
    { label: '对话 ID', value: item.sourceConversationId },
    { label: '会话 ID', value: item.sourceSessionId },
    { label: 'Turn ID', value: item.sourceTurnId },
    { label: formatSourceEntryLabel(item), value: item.sourceEntryId },
    { label: '知识库', value: buildNamedReference(item.sourceKnowledgeBaseName, item.sourceKnowledgeBaseId) },
    { label: '文档', value: buildNamedReference(item.sourceDocumentName, item.sourceDocumentId) },
  ]
  return details.filter((entry): entry is { label: string; value: string } => Boolean(entry.value))
}

function canOpenKnowledgeBase(item: EntityProvenance) {
  return Boolean(item.sourceKnowledgeBaseId)
}

function canOpenDocument(item: EntityProvenance) {
  return Boolean(item.sourceKnowledgeBaseId && item.sourceDocumentId)
}

function openKnowledgeBase(item: EntityProvenance) {
  if (!item.sourceKnowledgeBaseId) return
  void router.push({
    name: 'knowledgeBaseDetail',
    params: { id: item.sourceKnowledgeBaseId },
  })
}

function openDocument(item: EntityProvenance) {
  if (!item.sourceKnowledgeBaseId || !item.sourceDocumentId) return
  void router.push({
    name: 'knowledgeBaseDocumentDetail',
    params: {
      id: item.sourceKnowledgeBaseId,
      docId: item.sourceDocumentId,
    },
  })
}

</script>

<template>
  <div class="space-y-4">
    <!-- 筛选栏 -->
    <div class="detail-card p-3">
      <div class="space-y-3">
        <div class="grid items-end gap-3 xl:grid-cols-[minmax(18rem,1fr)_9rem_9rem_9rem_7rem_auto]">
          <!-- 关键词搜索 -->
          <div>
            <label class="text-xs text-muted-foreground mb-1 block">关键词搜索</label>
            <div class="relative">
              <Search class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                v-model="filterQ"
                placeholder="按名称或描述搜索..."
                class="pl-9"
                @keydown.enter="handleSearch"
              />
            </div>
          </div>

          <!-- 记忆范围 -->
          <div>
            <label class="text-xs text-muted-foreground mb-1 block">记忆范围</label>
            <Select
              :model-value="filterMemoryScope || '__all__'"
              @update:model-value="(value) => filterMemoryScope = String(value ?? '') === '__all__' ? '' : String(value ?? '')"
            >
              <SelectTrigger>
                <SelectValue placeholder="全部范围" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__all__">全部范围</SelectItem>
                <SelectItem v-for="scope in MEMORY_SCOPE_OPTIONS" :key="scope.value" :value="scope.value">
                  {{ scope.label }}
                </SelectItem>
              </SelectContent>
            </Select>
          </div>

          <!-- 现实性 -->
          <div>
            <label class="text-xs text-muted-foreground mb-1 block">现实性</label>
            <Select
              :model-value="filterRealityType || '__all__'"
              @update:model-value="(value) => filterRealityType = String(value ?? '') === '__all__' ? '' : String(value ?? '')"
            >
              <SelectTrigger>
                <SelectValue placeholder="全部现实性" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__all__">全部现实性</SelectItem>
                <SelectItem v-for="reality in REALITY_TYPE_OPTIONS" :key="reality.value" :value="reality.value">
                  {{ reality.label }}
                </SelectItem>
              </SelectContent>
            </Select>
          </div>

          <!-- 排序 -->
          <div>
            <label class="text-xs text-muted-foreground mb-1 block">排序字段</label>
            <Select v-model="filterSortBy">
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem v-for="s in SORT_OPTIONS" :key="s.value" :value="s.value">
                  {{ s.label }}
                </SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div>
            <label class="text-xs text-muted-foreground mb-1 block">排序方向</label>
            <Select v-model="filterOrder">
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem v-for="o in ORDER_OPTIONS" :key="o.value" :value="o.value">
                  {{ o.label }}
                </SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div class="flex items-end gap-2">
            <Button @click="handleSearch">搜索</Button>
            <Button
              variant="outline"
              :aria-expanded="advancedFiltersOpen"
              @click="advancedFiltersOpen = !advancedFiltersOpen"
            >
              <SlidersHorizontal class="mr-1.5 size-4" />
              高级筛选
              <span v-if="activeAdvancedFilters.length > 0" class="ml-1 text-xs">
                {{ activeAdvancedFilters.length }}
              </span>
            </Button>
            <Button variant="outline" @click="openCreate">
              <Plus class="mr-1.5 size-4" />
              新建实体
            </Button>
          </div>
        </div>

        <div v-if="activeAdvancedFilters.length > 0" class="flex flex-wrap gap-2">
          <Badge
            v-for="item in activeAdvancedFilters"
            :key="item"
            variant="outline"
            class="max-w-[18rem] truncate text-[0.72rem]"
          >
            {{ item }}
          </Badge>
        </div>

        <div v-if="advancedFiltersOpen" class="rounded-md border border-border/60 p-3">
          <div class="grid gap-3 lg:grid-cols-2 xl:grid-cols-[12rem_minmax(0,1fr)_9rem_9rem_12rem_minmax(0,1fr)_minmax(0,1fr)]">
            <!-- 实体类型 -->
            <div>
              <label class="text-xs text-muted-foreground mb-1 block">实体类型</label>
              <Select
                :model-value="filterType || '__all__'"
                @update:model-value="(value) => filterType = String(value ?? '') === '__all__' ? '' : String(value ?? '')"
              >
                <SelectTrigger>
                  <SelectValue placeholder="全部类型" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="__all__">全部类型</SelectItem>
                  <SelectItem v-for="t in ENTITY_TYPES" :key="t.value" :value="t.value">
                    {{ t.label }}
                  </SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div>
              <label class="text-xs text-muted-foreground mb-1 block">空间标识</label>
              <Input
                v-model="filterSpaceId"
                placeholder="完整 spaceId"
                @keydown.enter="handleSearch"
              />
            </div>
            <div>
              <label class="text-xs text-muted-foreground mb-1 block">开始时间</label>
              <DatePicker v-model="filterTimeFrom" placeholder="开始日期" class="w-full" />
            </div>
            <div>
              <label class="text-xs text-muted-foreground mb-1 block">结束时间</label>
              <DatePicker v-model="filterTimeTo" placeholder="结束日期" class="w-full" />
            </div>
            <div>
              <label class="mb-1 block text-xs text-muted-foreground">来源类型</label>
              <Select
                :model-value="filterOriginType || '__all__'"
                @update:model-value="(value) => filterOriginType = String(value ?? '') === '__all__' ? '' : String(value ?? '')"
              >
                <SelectTrigger>
                  <SelectValue placeholder="全部来源" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="__all__">全部来源</SelectItem>
                  <SelectItem v-for="origin in ORIGIN_TYPE_OPTIONS" :key="origin.value" :value="origin.value">
                    {{ origin.label }}
                  </SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div>
              <label class="mb-1 block text-xs text-muted-foreground">知识库 ID</label>
              <Input
                v-model="filterSourceKnowledgeBaseId"
                data-test="list-source-kb-id"
                placeholder="可选"
                @keydown.enter="handleSearch"
              />
            </div>
            <div>
              <label class="mb-1 block text-xs text-muted-foreground">文档 ID</label>
              <Input
                v-model="filterSourceDocumentId"
                data-test="list-source-document-id"
                placeholder="可选"
                @keydown.enter="handleSearch"
              />
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 加载中骨架屏 -->
    <div v-if="loading" class="detail-card p-4 space-y-3">
      <Skeleton v-for="i in 6" :key="i" class="h-10 w-full" />
    </div>

    <!-- 错误状态 -->
    <div v-else-if="error" class="detail-card px-6 py-8 text-center">
      <p class="text-sm text-muted-foreground">{{ error }}</p>
      <Button variant="outline" class="mt-4" @click="loadEntities">
        <RotateCcw class="mr-1.5 size-4" />
        重试
      </Button>
    </div>

    <!-- 空状态 -->
    <div v-else-if="items.length === 0" class="detail-card px-6 py-12 text-center">
      <p class="text-sm text-muted-foreground">暂无实体数据</p>
      <p class="mt-1 text-xs text-muted-foreground">实体会在对话过程中自动提取，或点击上方「新建实体」手动创建。</p>
    </div>

    <!-- 实体表格 -->
    <div v-else class="detail-card overflow-x-auto">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-border/60">
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">名称</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">类型</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">归属</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">治理状态</th>
            <th class="px-4 py-3 text-right font-medium text-muted-foreground">重要性</th>
            <th class="px-4 py-3 text-right font-medium text-muted-foreground">版本</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">创建时间</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="entity in items"
            :key="entity.id"
            class="border-b border-border/40 cursor-pointer transition-colors hover:bg-muted/50"
            @click="openDetail(entity)"
          >
            <td class="px-4 py-3 font-medium text-foreground">{{ entity.name }}</td>
            <td class="px-4 py-3">
              <span class="rounded-md bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                {{ entity.typeLabel }}
              </span>
            </td>
            <td class="px-4 py-3">
              <div class="flex flex-wrap items-center gap-1.5">
                <Badge variant="outline">{{ formatMemoryScope(entity.memoryScope) }}</Badge>
                <Badge variant="outline">{{ formatConsumptionBoundary(entity) }}</Badge>
                <Badge v-if="shouldShowRealityType(entity.realityType)" variant="secondary">
                  {{ formatRealityType(entity.realityType) }}
                </Badge>
              </div>
              <p class="mt-1 text-xs text-muted-foreground" :title="formatSpaceId(entity.spaceId)">
                {{ formatListSpaceLabel(entity) }}
              </p>
            </td>
            <td class="px-4 py-3">
              <div class="flex flex-wrap items-center gap-1.5">
                <Badge variant="outline">{{ formatLifecycleState(entity.lifecycleState) }}</Badge>
                <Badge variant="secondary">{{ formatTrustLevel(entity.trustLevel) }}</Badge>
              </div>
              <p class="mt-1 text-xs text-muted-foreground">
                {{ formatEvidenceKind(entity.evidenceKind) }} · 可信度 {{ formatPercentScore(entity.trustScore) }} · {{ entity.evidenceCount }} 份证据
              </p>
              <p v-if="entity.expiresAt" class="mt-0.5 text-xs text-muted-foreground">
                过期 {{ formatOptionalDate(entity.expiresAt) }}
              </p>
            </td>
            <td class="px-4 py-3 text-right tabular-nums">{{ entity.importanceScore.toFixed(2) }}</td>
            <td class="px-4 py-3 text-right tabular-nums">v{{ entity.version }}</td>
            <td class="px-4 py-3 text-muted-foreground">{{ formatDate(entity.createdAt) }}</td>
          </tr>
        </tbody>
      </table>

      <!-- 分页控件 -->
      <div class="flex items-center justify-between border-t border-border/60 px-4 py-3">
        <span class="text-xs text-muted-foreground">共 {{ total }} 条</span>
        <Pagination
          v-if="pageCount > 1"
          :page="currentPage"
          :page-count="pageCount"
          size="sm"
          @change="handlePageChange"
        />
      </div>
    </div>

    <!-- 详情 Sheet -->
    <Sheet v-model:open="detailOpen">
      <SheetContent class="w-full max-w-2xl overflow-y-auto p-6">
        <SheetHeader>
          <SheetTitle>{{ detailEntity?.name || '实体详情' }}</SheetTitle>
          <SheetDescription>
            {{ detailEntity?.typeLabel || '' }} · v{{ detailEntity?.version || 0 }}
          </SheetDescription>
        </SheetHeader>

        <!-- 详情加载中 -->
        <div v-if="detailLoading" class="mt-6 space-y-3">
          <Skeleton class="h-5 w-40" />
          <Skeleton class="h-4 w-full" />
          <Skeleton class="h-4 w-3/4" />
          <Skeleton class="h-20 w-full" />
        </div>

        <!-- 详情内容 -->
        <div v-else-if="detailEntity" class="mt-6 space-y-5">
          <!-- 操作按钮 -->
          <div class="flex gap-2">
            <Button size="sm" variant="outline" @click="openEdit(detailEntity!)">
              <Pencil class="mr-1.5 size-3.5" />
              编辑
            </Button>
            <Button
              size="sm"
              variant="outline"
              class="text-destructive hover:text-destructive"
              @click="openArchive(detailEntity!.id, detailEntity!.name)"
            >
              <Archive class="mr-1.5 size-3.5" />
              归档
            </Button>
          </div>

          <div class="flex flex-wrap items-center gap-1.5">
            <Badge variant="outline">{{ formatMemoryScope(detailEntity.memoryScope) }}</Badge>
            <Badge variant="secondary">{{ formatRealityType(detailEntity.realityType) }}</Badge>
            <Badge variant="outline">{{ formatConsumptionBoundary(detailEntity) }}</Badge>
            <Badge variant="outline">{{ formatLifecycleState(detailEntity.lifecycleState) }}</Badge>
            <Badge variant="secondary">{{ formatTrustLevel(detailEntity.trustLevel) }}</Badge>
            <Badge variant="outline">{{ formatEvidenceKind(detailEntity.evidenceKind) }}</Badge>
          </div>

          <!-- Tab 切换：基本信息 / 版本历史 / 关联实体 -->
          <Tabs :model-value="detailTab" @update:model-value="handleDetailTabChange">
            <TabsList class="w-full justify-start">
              <TabsTrigger value="info">基本信息</TabsTrigger>
              <TabsTrigger value="provenance">来源明细</TabsTrigger>
              <TabsTrigger value="history">版本历史</TabsTrigger>
              <TabsTrigger value="related">关联实体</TabsTrigger>
            </TabsList>

            <!-- 基本信息 -->
            <TabsContent value="info" class="space-y-4 mt-4">
              <div class="grid grid-cols-2 gap-3 text-sm">
                <div>
                  <span class="text-muted-foreground">类型</span>
                  <p class="font-medium">{{ detailEntity.typeLabel }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">记忆范围</span>
                  <p class="font-medium">{{ formatMemoryScope(detailEntity.memoryScope) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">现实性</span>
                  <p class="font-medium">{{ formatRealityType(detailEntity.realityType) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">记忆空间</span>
                  <p class="font-medium break-all">{{ formatSpaceId(detailEntity.spaceId) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">消费边界</span>
                  <p class="font-medium">{{ formatConsumptionBoundary(detailEntity) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">重要性分数</span>
                  <p class="font-medium">{{ detailEntity.importanceScore.toFixed(2) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">访问次数</span>
                  <p class="font-medium">{{ detailEntity.accessCount }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">生命周期</span>
                  <p class="font-medium">{{ formatLifecycleState(detailEntity.lifecycleState) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">时效性</span>
                  <p class="font-medium">{{ formatTemporality(detailEntity.temporality) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">过期时间</span>
                  <p class="font-medium">{{ formatOptionalDate(detailEntity.expiresAt) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">生命周期原因</span>
                  <p class="font-medium">{{ detailEntity.lifecycleReason || '-' }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">信任等级</span>
                  <p class="font-medium">{{ formatTrustLevel(detailEntity.trustLevel) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">可信分数</span>
                  <p class="font-medium">{{ formatPercentScore(detailEntity.trustScore) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">证据类型</span>
                  <p class="font-medium">{{ formatEvidenceKind(detailEntity.evidenceKind) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">证据数量</span>
                  <p class="font-medium">{{ detailEntity.evidenceCount }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">最近验证</span>
                  <p class="font-medium">{{ formatOptionalDate(detailEntity.lastVerifiedAt) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">提取置信度</span>
                  <p class="font-medium">{{ formatPercentScore(detailEntity.extractionConfidence) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">来源对话 ID</span>
                  <p class="font-medium truncate">{{ detailEntity.sourceConversationId || '-' }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">当前版本</span>
                  <p class="font-medium">{{ formatBoolean(detailEntity.isCurrent) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">有效开始</span>
                  <p class="font-medium">{{ formatDate(detailEntity.validFrom) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">有效结束</span>
                  <p class="font-medium">{{ formatOptionalDate(detailEntity.validTo) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">派生实体</span>
                  <p class="font-medium">{{ formatBoolean(detailEntity.isDerived) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">替代实体</span>
                  <p class="font-medium truncate">{{ detailEntity.succeededBy || '-' }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">最近访问</span>
                  <p class="font-medium">{{ formatOptionalDate(detailEntity.lastAccessedAt) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">版本</span>
                  <p class="font-medium">v{{ detailEntity.version }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">创建时间</span>
                  <p class="font-medium">{{ formatDate(detailEntity.createdAt) }}</p>
                </div>
                <div>
                  <span class="text-muted-foreground">更新时间</span>
                  <p class="font-medium">{{ formatDate(detailEntity.updatedAt) }}</p>
                </div>
                <div class="col-span-2">
                  <span class="text-muted-foreground">派生来源</span>
                  <p class="font-medium break-all">{{ formatDerivationSources(detailEntity.derivationSources) }}</p>
                </div>
              </div>

              <!-- 描述 -->
              <div v-if="detailEntity.description" class="text-sm">
                <span class="text-muted-foreground">描述</span>
                <p class="mt-1">{{ detailEntity.description }}</p>
              </div>

              <!-- 属性 -->
              <div class="text-sm">
                <span class="text-muted-foreground">属性</span>
                <pre class="mt-1 rounded-md bg-muted/50 p-3 text-xs overflow-x-auto">{{ JSON.stringify(detailEntity.properties, null, 2) }}</pre>
              </div>
            </TabsContent>

            <!-- 来源明细 -->
            <TabsContent value="provenance" class="mt-4">
              <div class="mb-4 rounded-md border border-border/60 p-3">
                <div class="grid gap-3 lg:grid-cols-[12rem_minmax(0,1fr)_minmax(0,1fr)_auto_auto]">
                  <div>
                    <label class="mb-1 block text-xs text-muted-foreground">来源类型</label>
                    <Select
                      :model-value="provenanceOriginType || '__all__'"
                      @update:model-value="(value) => provenanceOriginType = String(value ?? '') === '__all__' ? '' : String(value ?? '')"
                    >
                      <SelectTrigger>
                        <SelectValue placeholder="全部来源" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="__all__">全部来源</SelectItem>
                        <SelectItem v-for="origin in ORIGIN_TYPE_OPTIONS" :key="origin.value" :value="origin.value">
                          {{ origin.label }}
                        </SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div>
                    <label class="mb-1 block text-xs text-muted-foreground">知识库 ID</label>
                    <Input
                      v-model="provenanceKnowledgeBaseId"
                      data-test="provenance-kb-id"
                      placeholder="可选"
                      @keydown.enter="applyProvenanceFilters"
                    />
                  </div>
                  <div>
                    <label class="mb-1 block text-xs text-muted-foreground">文档 ID</label>
                    <Input
                      v-model="provenanceDocumentId"
                      data-test="provenance-document-id"
                      placeholder="可选"
                      @keydown.enter="applyProvenanceFilters"
                    />
                  </div>
                  <div class="flex items-end">
                    <Button data-test="apply-provenance-filters" size="sm" @click="applyProvenanceFilters">筛选</Button>
                  </div>
                  <div class="flex items-end">
                    <Button data-test="reset-provenance-filters" size="sm" variant="outline" @click="resetProvenanceFilters">重置</Button>
                  </div>
                </div>
              </div>
              <div v-if="provenanceLoading" class="space-y-2">
                <Skeleton v-for="i in 3" :key="i" class="h-20 w-full" />
              </div>
              <div v-else-if="provenanceItems.length === 0" class="py-6 text-center text-sm text-muted-foreground">
                暂无来源明细
              </div>
              <div v-else class="space-y-3">
                <div
                  v-for="(item, idx) in provenanceItems"
                  :key="`${item.originType}-${item.createdAt}-${idx}`"
                  class="rounded-md border border-border/60 p-3"
                >
                  <div class="flex flex-wrap items-center justify-between gap-2">
                    <div class="flex flex-wrap items-center gap-2">
                      <Badge variant="outline">{{ formatOriginType(item.originType) }}</Badge>
                      <Badge variant="secondary">{{ formatTrustLevel(item.trustLevel) }}</Badge>
                      <Badge variant="outline">{{ formatEvidenceKind(item.evidenceKind) }}</Badge>
                      <span class="text-xs text-muted-foreground">
                        可信 {{ formatPercentScore(item.trustScore) }} · 置信度 {{ formatPercentScore(item.confidence) }}
                      </span>
                    </div>
                    <div class="flex flex-wrap items-center gap-2">
                      <Button
                        v-if="canOpenKnowledgeBase(item)"
                        size="sm"
                        variant="outline"
                        class="h-7 px-2 text-xs"
                        data-test="open-provenance-kb"
                        @click="openKnowledgeBase(item)"
                      >
                        <ArrowUpRight class="size-3.5" />
                        查看知识库
                      </Button>
                      <Button
                        v-if="canOpenDocument(item)"
                        size="sm"
                        variant="outline"
                        class="h-7 px-2 text-xs"
                        data-test="open-provenance-doc"
                        @click="openDocument(item)"
                      >
                        <ArrowUpRight class="size-3.5" />
                        查看文档
                      </Button>
                      <span class="text-xs text-muted-foreground">{{ formatDate(item.createdAt) }}</span>
                    </div>
                  </div>
                  <div class="mt-3 grid gap-2 text-sm">
                    <div
                      v-for="entry in buildProvenanceDetails(item)"
                      :key="`${item.createdAt}-${entry.label}`"
                      class="grid gap-1 sm:grid-cols-[7rem_minmax(0,1fr)] sm:items-start"
                    >
                      <span class="text-muted-foreground">{{ entry.label }}</span>
                      <span class="break-all font-medium">{{ entry.value }}</span>
                    </div>
                  </div>
                  <div v-if="item.evidenceExcerpt" class="mt-3 rounded-md bg-muted/50 p-2 text-sm">
                    <span class="text-muted-foreground">证据片段</span>
                    <p class="mt-1 text-foreground">{{ item.evidenceExcerpt }}</p>
                  </div>
                </div>
              </div>
            </TabsContent>

            <!-- 版本历史 -->
            <TabsContent value="history" class="mt-4">
              <div v-if="historyLoading" class="space-y-2">
                <Skeleton v-for="i in 3" :key="i" class="h-8 w-full" />
              </div>
              <div v-else-if="historyItems.length === 0" class="py-6 text-center text-sm text-muted-foreground">
                暂无版本历史
              </div>
              <div v-else class="space-y-3">
                <div
                  v-for="(h, idx) in historyItems"
                  :key="h.id + '-' + idx"
                  class="rounded-md border border-border/60 p-3 text-sm"
                >
                  <div class="flex items-center justify-between">
                    <span class="font-medium">v{{ h.version }}</span>
                    <span class="text-xs text-muted-foreground">{{ formatDate(h.validFrom) }}</span>
                  </div>
                  <p v-if="h.description" class="mt-1 text-muted-foreground">{{ h.description }}</p>
                </div>
              </div>
            </TabsContent>

            <!-- 关联实体 -->
            <TabsContent value="related" class="mt-4">
              <div v-if="relatedLoading" class="space-y-2">
                <Skeleton v-for="i in 3" :key="i" class="h-8 w-full" />
              </div>
              <div v-else-if="relatedItems.length === 0" class="py-6 text-center text-sm text-muted-foreground">
                暂无关联实体
              </div>
              <div v-else class="space-y-2">
                <div
                  v-for="r in relatedItems"
                  :key="r.id"
                  class="flex items-center justify-between rounded-md border border-border/60 px-3 py-2 text-sm"
                >
                  <div class="min-w-0">
                    <div class="flex flex-wrap items-center gap-2">
                      <span class="font-medium">{{ r.name }}</span>
                      <span class="rounded-md bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                        {{ r.typeLabel }}
                      </span>
                    </div>
                    <div class="mt-1 flex flex-wrap items-center gap-1.5">
                      <Badge variant="outline">{{ formatConsumptionBoundary(r) }}</Badge>
                      <Badge variant="outline">{{ formatLifecycleState(r.lifecycleState) }}</Badge>
                      <Badge variant="secondary">{{ formatTrustLevel(r.trustLevel) }}</Badge>
                    </div>
                  </div>
                  <span class="text-xs text-muted-foreground tabular-nums">
                    重要性 {{ r.importanceScore.toFixed(2) }}
                  </span>
                </div>
              </div>
            </TabsContent>
          </Tabs>
        </div>
      </SheetContent>
    </Sheet>

    <!-- 新建实体 Sheet -->
    <Sheet v-model:open="createOpen">
      <SheetContent class="w-full max-w-xl overflow-y-auto p-6">
        <SheetHeader>
          <SheetTitle>新建实体</SheetTitle>
          <SheetDescription>手动创建一个知识实体。</SheetDescription>
        </SheetHeader>
        <form class="mt-6 space-y-5" @submit.prevent="handleCreate">
          <div>
            <label class="text-sm font-medium block mb-1.5">名称</label>
            <Input v-model="createForm.name" placeholder="实体名称" required />
          </div>
          <div>
            <label class="text-sm font-medium block mb-1.5">类型</label>
            <Select v-model="createForm.type">
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem v-for="t in ENTITY_TYPES" :key="t.value" :value="t.value">
                  {{ t.label }}
                </SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div>
            <label class="text-sm font-medium block mb-1.5">描述</label>
            <textarea
              v-model="createForm.description"
              class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring resize-y"
              rows="2"
              placeholder="可选描述"
            />
          </div>
          <div>
            <label class="text-sm font-medium block mb-1.5">属性 (JSON)</label>
            <textarea
              v-model="createPropsText"
              class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring resize-y"
              rows="4"
              placeholder='{}'
            />
          </div>
          <div>
            <label class="text-sm font-medium block mb-1.5">重要性分数</label>
            <Input
              v-model.number="createForm.importanceScore"
              type="number"
              :min="0"
              :max="1"
              :step="0.05"
              class="w-32"
            />
          </div>
          <div class="flex gap-2 pt-2">
            <Button type="button" variant="outline" @click="createOpen = false">取消</Button>
            <Button type="submit" :disabled="creating || !createForm.name">
              {{ creating ? '创建中...' : '创建' }}
            </Button>
          </div>
        </form>
      </SheetContent>
    </Sheet>

    <!-- 编辑实体 Sheet -->
    <Sheet :open="editOpen" @update:open="handleEditClose">
      <SheetContent class="w-full max-w-xl overflow-y-auto p-6">
        <SheetHeader>
          <SheetTitle>编辑实体</SheetTitle>
          <SheetDescription>修改实体的描述、属性和重要性分数。</SheetDescription>
        </SheetHeader>
        <form class="mt-6 space-y-5" @submit.prevent="handleEdit">
          <div>
            <label class="text-sm font-medium block mb-1.5">描述</label>
            <textarea
              v-model="editForm.description"
              class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring resize-y"
              rows="3"
              placeholder="实体描述"
            />
          </div>
          <div>
            <label class="text-sm font-medium block mb-1.5">属性 (JSON)</label>
            <textarea
              v-model="editPropsText"
              class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring resize-y"
              rows="8"
              placeholder='{}'
            />
          </div>
          <div>
            <label class="text-sm font-medium block mb-1.5">重要性分数</label>
            <Input
              v-model.number="editForm.importanceScore"
              type="number"
              :min="0"
              :max="1"
              :step="0.05"
              class="w-32"
            />
          </div>
          <div class="flex gap-2 pt-2">
            <Button type="button" variant="outline" @click="handleEditClose(false)">取消</Button>
            <Button type="submit" :disabled="editing">
              {{ editing ? '保存中...' : '保存' }}
            </Button>
          </div>
        </form>
      </SheetContent>
    </Sheet>

    <!-- 归档确认对话框 -->
    <Dialog :open="archiveOpen" @update:open="handleArchiveClose">
      <DialogContent class="sm:max-w-[384px]">
        <DialogHeader>
          <DialogTitle>确认归档</DialogTitle>
          <DialogDescription>
            确定要归档实体「{{ archiveEntityName }}」吗？归档后该实体将不再出现在列表中。
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" @click="archiveOpen = false">取消</Button>
          <Button variant="destructive" :disabled="archiving" @click="handleArchive">
            {{ archiving ? '归档中...' : '确认归档' }}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>
