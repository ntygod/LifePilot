<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  AlertTriangle,
  ArrowUpRight,
  Brain,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Pencil,
  RefreshCw,
  Save,
  Trash2,
  X,
} from 'lucide-vue-next'
import { memoryApi } from '@/api/client'
import type { EntityDetail, EntityProvenance, EntityUpdateRequest, SourceSummary } from '@/types'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Slider } from '@/components/ui/slider'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import { useUiStore } from '@/stores/ui'
import { logger } from '@/utils/logger'
import {
  buildDeletedMemorySource,
  buildMemoryChangeImpact,
  buildMemorySourceExplanation,
  buildMemoryUsageImpact,
  isDeleteMemoryChange,
} from '@/utils/memorySource'

const props = defineProps<{
  source: SourceSummary | null
  projectId?: string | null
}>()

const emit = defineEmits<{
  close: []
  updated: [entity: EntityDetail]
  deleted: [entityId: string, source?: SourceSummary]
}>()

const router = useRouter()
const uiStore = useUiStore()

const entity = ref<EntityDetail | null>(null)
const provenances = ref<EntityProvenance[]>([])
const loading = ref(false)
const loadingProvenance = ref(false)
const error = ref<string | null>(null)
const provenanceError = ref<string | null>(null)
const editing = ref(false)
const saving = ref(false)
const confirmingForget = ref(false)
const forgetting = ref(false)
const resolvingRevalidation = ref(false)
const showAdvancedProperties = ref(false)
const showTechnicalDetails = ref(false)
const showAllProvenances = ref(false)
const editName = ref('')
const editDescription = ref('')
const editPropertiesText = ref('{}')
const editImportanceScore = ref(0.5)
const deletedSource = ref<SourceSummary | null>(null)
let memoryLoadSeq = 0

const panelSource = computed(() => deletedSource.value ?? props.source)

const isDeletedMemoryChange = computed(() =>
  panelSource.value ? isDeleteMemoryChange(panelSource.value) : false,
)

const sourceKindLabel = computed(() => {
  const operation = safeString(panelSource.value?.extra?.operationLabel)
  const sourceKind = safeString(panelSource.value?.extra?.sourceKindLabel)
  if (operation) {
    return sourceKind ? `${sourceKind} · 本轮${operation}的记忆` : `本轮${operation}的记忆`
  }
  if (sourceKind) {
    return `${sourceKind}的记忆`
  }
  return '本轮参考的记忆'
})

const typeLabel = computed(() =>
  entity.value?.typeLabel
  || safeString(panelSource.value?.extra?.entityTypeLabel)
  || '记忆',
)

const sourceEvidence = computed(() =>
  safeString(panelSource.value?.extra?.evidenceExcerpt)
  || safeString(panelSource.value?.extra?.description)
  || '',
)

const sourceExplanation = computed(() =>
  panelSource.value
    ? buildMemorySourceExplanation(
      panelSource.value,
      safeString(panelSource.value.extra?.operationLabel) ? 'change' : 'source',
    )
    : '',
)

const latestProvenance = computed(() => provenances.value[0] ?? null)

const visibleProvenances = computed(() =>
  showAllProvenances.value ? provenances.value : provenances.value.slice(0, 1),
)

const hiddenProvenanceCount = computed(() =>
  Math.max(0, provenances.value.length - visibleProvenances.value.length),
)

const previewEntity = computed(() => {
  if (!entity.value) return null
  if (!editing.value) return entity.value
  return {
    ...entity.value,
    description: editDescription.value,
    importanceScore: normalizeImportanceScore(editImportanceScore.value),
  }
})

const usageImpactLabel = computed(() => editing.value ? '保存后影响' : '使用影响')

const usageImpact = computed(() =>
  previewEntity.value ? buildMemoryUsageImpact(previewEntity.value) : '',
)

const deletedMemoryImpact = computed(() =>
  panelSource.value ? buildMemoryChangeImpact(panelSource.value) : '',
)

const sourceFallbackImpact = computed(() => {
  const source = panelSource.value
  if (!source) return ''
  if (isDeletedMemoryChange.value) return deletedMemoryImpact.value
  return safeString(source.extra?.usageImpact) || buildMemoryChangeImpact(source)
})

const sourceFallbackDescription = computed(() =>
  safeString(panelSource.value?.extra?.description)
  || '这条记忆详情暂时无法加载，仍可根据对话摘要核对它为什么出现。',
)

const editImportanceSliderValue = computed(() => [
  Math.round(normalizeImportanceScore(editImportanceScore.value) * 100),
])

const editImportancePercent = computed(() =>
  `${editImportanceSliderValue.value[0]}%`,
)

const editImportanceLabel = computed(() => {
  const score = normalizeImportanceScore(editImportanceScore.value)
  if (score >= 0.75) return '优先参考'
  if (score <= 0.35) return '低频参考'
  return '按需参考'
})

const editChangeLabels = computed(() => {
  const current = entity.value
  if (!current || !editing.value) return []

  const changes: string[] = []
  if (editName.value.trim() !== current.name) {
    changes.push('名称')
  }
  if (editDescription.value !== (current.description || '')) {
    changes.push('描述')
  }
  if (Math.round(normalizeImportanceScore(editImportanceScore.value) * 100)
      !== Math.round(normalizeImportanceScore(current.importanceScore) * 100)) {
    changes.push('参考强度')
  }
  if (isPropertiesTextChanged(current)) {
    changes.push('高级属性')
  }
  return changes
})

const hasEditChanges = computed(() => editChangeLabels.value.length > 0)

const editChangeSummary = computed(() => {
  if (!hasEditChanges.value) return ''
  return `将更新：${editChangeLabels.value.join('、')}`
})

const reliabilityExplanation = computed(() => {
  const current = entity.value
  if (!current) return ''

  const origin = latestProvenance.value?.originType
    ? formatOriginType(latestProvenance.value.originType)
    : ''
  const evidence = formatEvidenceKind(current.evidenceKind)
  const trust = formatTrustLevel(current.trustLevel)
  const score = formatPercent(current.trustScore)
  const trustText = [trust, score !== '-' ? score : ''].filter(Boolean).join(' ')
  const evidenceText = Number.isFinite(current.evidenceCount)
    ? `${current.evidenceCount} 条证据`
    : ''

  return [
    origin ? `来源：${origin}` : '',
    evidence ? `证据：${evidence}` : '',
    trustText ? `可信度：${trustText}` : '',
    evidenceText,
  ].filter(Boolean).join('；')
})

const visibleDescription = computed(() =>
  previewEntity.value?.description || '暂无描述',
)

watch(
  () => [
    props.source?.id,
    props.projectId,
    props.source?.extra?.operation,
    props.source?.extra?.operationLabel,
  ] as const,
  ([id]) => {
    deletedSource.value = null
    if (!id) {
      memoryLoadSeq += 1
      resetPanelState()
      return
    }
    if (isDeletedMemoryChange.value) {
      memoryLoadSeq += 1
      resetPanelState()
      return
    }
    void loadMemory(id)
  },
  { immediate: true },
)

function resetPanelState() {
  entity.value = null
  provenances.value = []
  loading.value = false
  loadingProvenance.value = false
  error.value = null
  provenanceError.value = null
  editing.value = false
  confirmingForget.value = false
  resolvingRevalidation.value = false
  showAdvancedProperties.value = false
  showTechnicalDetails.value = false
  showAllProvenances.value = false
}

async function loadMemory(id: string) {
  const seq = ++memoryLoadSeq
  loading.value = true
  loadingProvenance.value = true
  error.value = null
  provenanceError.value = null
  editing.value = false
  confirmingForget.value = false
  resolvingRevalidation.value = false
  showAdvancedProperties.value = false
  showTechnicalDetails.value = false
  showAllProvenances.value = false
  entity.value = null
  provenances.value = []

  const entityTask = memoryApi.getEntity(id, props.projectId)
    .then(result => {
      if (seq !== memoryLoadSeq) return
      entity.value = result
      resetEditForm()
    })
    .catch((event: any) => {
      if (seq !== memoryLoadSeq) return
      logger.error('加载对话记忆详情失败:', event)
      error.value = event?.message || '加载记忆失败'
    })
    .finally(() => {
      if (seq === memoryLoadSeq) {
        loading.value = false
      }
    })

  const provenanceTask = loadProvenances(id, seq)

  await Promise.allSettled([entityTask, provenanceTask])
}

async function loadProvenances(id: string, seq = memoryLoadSeq) {
  loadingProvenance.value = true
  provenanceError.value = null
  provenances.value = []
  try {
    const result = await memoryApi.getEntityProvenances(id, { projectId: props.projectId })
    if (seq !== memoryLoadSeq) return
    provenances.value = result
  } catch (event: any) {
    if (seq !== memoryLoadSeq) return
    logger.warn('加载对话记忆来源失败:', event)
    provenanceError.value = event?.message || '来源证据暂时无法加载'
  } finally {
    if (seq === memoryLoadSeq) {
      loadingProvenance.value = false
    }
  }
}

function retryLoadProvenances() {
  const id = props.source?.id
  if (!id || loadingProvenance.value) return
  void loadProvenances(id)
}

function retryLoadMemory() {
  const id = props.source?.id
  if (!id || loading.value || isDeletedMemoryChange.value) return
  void loadMemory(id)
}

function resetEditForm() {
  if (!entity.value) return
  editName.value = entity.value.name
  editDescription.value = entity.value.description || ''
  editPropertiesText.value = JSON.stringify(entity.value.properties || {}, null, 2)
  editImportanceScore.value = entity.value.importanceScore
}

function startEdit() {
  resetEditForm()
  editing.value = true
  confirmingForget.value = false
  showAdvancedProperties.value = false
  showTechnicalDetails.value = false
}

function cancelEdit() {
  editing.value = false
  resetEditForm()
}

function startForget() {
  editing.value = false
  confirmingForget.value = true
  showAdvancedProperties.value = false
  showTechnicalDetails.value = false
}

function cancelForget() {
  confirmingForget.value = false
}

function updateImportanceScore(value: number[] | undefined) {
  const next = value?.[0]
  editImportanceScore.value = normalizeImportanceScore(
    typeof next === 'number' ? next / 100 : editImportanceScore.value,
  )
}

async function forgetMemory() {
  if (!entity.value) return
  const currentEntity = entity.value
  const entityId = currentEntity.id
  forgetting.value = true
  try {
    await memoryApi.deleteEntity(entityId, props.projectId)
    const source = buildDeletedMemorySource(
      props.source ?? { type: 'memory', id: entityId, name: currentEntity.name },
      currentEntity,
    )
    deletedSource.value = source
    entity.value = null
    provenances.value = []
    editing.value = false
    confirmingForget.value = false
    emit('deleted', entityId, source)
    uiStore.showToast('success', '已忘记这条记忆')
  } catch (event: any) {
    logger.error('忘记对话记忆失败:', event)
    uiStore.showToast('error', event?.message || '忘记记忆失败')
  } finally {
    forgetting.value = false
  }
}

async function saveEdit() {
  if (!entity.value) return
  const name = editName.value.trim()
  if (!name) {
    uiStore.showToast('error', '记忆名称不能为空')
    return
  }
  let properties: Record<string, unknown>
  try {
    properties = JSON.parse(editPropertiesText.value || '{}')
  } catch {
    uiStore.showToast('error', '属性 JSON 格式不正确')
    return
  }

  saving.value = true
  try {
    const payload: EntityUpdateRequest = {
      name,
      description: editDescription.value,
      properties,
      importanceScore: normalizeImportanceScore(editImportanceScore.value),
    }
    const updated = await memoryApi.updateEntity(entity.value.id, payload, props.projectId)
    entity.value = updated
    editing.value = false
    emit('updated', updated)
    uiStore.showToast('success', '记忆已更新')
  } catch (event: any) {
    logger.error('保存对话记忆失败:', event)
    uiStore.showToast('error', event?.message || '保存记忆失败')
  } finally {
    saving.value = false
  }
}

async function resolveRevalidation() {
  if (!entity.value || resolvingRevalidation.value) return
  const entityId = entity.value.id
  resolvingRevalidation.value = true
  try {
    await memoryApi.resolveEntityRevalidation(entityId, props.projectId)
    await loadProvenances(entityId)
    uiStore.showToast('success', '已确认这条记忆仍有效')
  } catch (event: any) {
    logger.error('确认记忆复核失败:', event)
    uiStore.showToast('error', event?.message || '确认失败')
  } finally {
    resolvingRevalidation.value = false
  }
}

function openMemoryPage() {
  if (!panelSource.value?.id) return
  const projectId = safeString(props.projectId)
  void router.push({
    name: 'memories',
    query: {
      tab: 'entities',
      entityId: panelSource.value.id,
      ...(projectId ? { projectId } : {}),
    },
  })
}

function buildProvenanceSourceRoute(item: EntityProvenance) {
  const sessionId = safeString(item.sourceSessionId)
  if (sessionId) {
    const query: Record<string, string> = {}
    const turnId = safeString(item.sourceTurnId)
    const entryId = safeString(item.sourceEntryId)
    if (turnId) query.turnId = turnId
    if (entryId) query.entryId = entryId
    return {
      label: '打开来源会话',
      route: {
        name: 'conversationDetail',
        params: { sessionId },
        query,
      },
    }
  }

  const knowledgeBaseId = safeString(item.sourceKnowledgeBaseId)
  const documentId = safeString(item.sourceDocumentId)
  if (knowledgeBaseId && documentId) {
    const query: Record<string, string> = {}
    const chunkId = safeString(item.sourceEntryId)
    if (chunkId) query.chunkId = chunkId
    return {
      label: '打开来源文档',
      route: {
        name: 'knowledgeBaseDocumentDetail',
        params: { id: knowledgeBaseId, docId: documentId },
        query,
      },
    }
  }

  if (knowledgeBaseId) {
    return {
      label: '打开来源资料库',
      route: {
        name: 'knowledgeBaseDetail',
        params: { id: knowledgeBaseId },
      },
    }
  }

  return null
}

function provenanceSourceLabel(item: EntityProvenance) {
  return buildProvenanceSourceRoute(item)?.label ?? ''
}

function provenanceReadableSource(item: EntityProvenance) {
  return safeString(item.sourceDocumentName)
    || safeString(item.sourceKnowledgeBaseName)
    || safeString(item.sourceSessionTitle)
    || safeString(item.sourceSessionId)
    || safeString(item.sourceReference)
}

function isStaleProvenance(item: EntityProvenance) {
  return safeString(item.status).toUpperCase() === 'STALE' || Boolean(item.invalidatedAt)
}

function revalidationStatus(item: EntityProvenance) {
  return safeString(item.revalidationStatus).toUpperCase()
}

function isResolvedRevalidation(item: EntityProvenance) {
  return revalidationStatus(item) === 'RESOLVED'
}

function needsRevalidationReview(item: EntityProvenance) {
  return isStaleProvenance(item) && !isResolvedRevalidation(item)
}

function formatProvenanceReviewStatus(item: EntityProvenance) {
  if (needsRevalidationReview(item)) return '需复核'
  if (isStaleProvenance(item)) return '已复核'
  return ''
}

function provenanceInvalidationText(item: EntityProvenance) {
  if (!isStaleProvenance(item)) return ''
  const invalidatedAt = safeString(item.invalidatedAt)
  const timeText = invalidatedAt ? `，${formatDate(invalidatedAt)}` : ''
  if (isResolvedRevalidation(item)) {
    return `来源曾变更或不可完整追溯${timeText}，你已确认这条记忆当前仍有效。`
  }
  return `来源已变更或不可完整追溯${timeText}，知微后续使用这条记忆时需要复核。`
}

function canOpenProvenanceSource(item: EntityProvenance) {
  return buildProvenanceSourceRoute(item) !== null
}

function openProvenanceSource(item: EntityProvenance) {
  const target = buildProvenanceSourceRoute(item)
  if (!target) return
  void router.push(target.route)
  emit('close')
}

function safeString(value: unknown) {
  return typeof value === 'string' && value.trim() ? value.trim() : ''
}

function normalizeImportanceScore(value: number) {
  if (typeof value !== 'number' || Number.isNaN(value)) return 0.5
  return Math.max(0, Math.min(1, value))
}

function isPropertiesTextChanged(current: EntityDetail) {
  const original = current.properties || {}
  try {
    const next = JSON.parse(editPropertiesText.value || '{}')
    return JSON.stringify(next) !== JSON.stringify(original)
  } catch {
    return editPropertiesText.value.trim() !== JSON.stringify(original, null, 2).trim()
  }
}

function formatDate(iso?: string | null) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatPercent(value?: number | null) {
  if (typeof value !== 'number' || Number.isNaN(value)) return '-'
  return `${(Math.max(0, Math.min(1, value)) * 100).toFixed(0)}%`
}

function formatTrustLevel(value?: string | null) {
  return ({
    VERIFIED: '已验证',
    EXPLICIT: '用户明确',
    DERIVED: '派生',
    INFERRED: '推断',
    UNVERIFIED: '未验证',
  } as Record<string, string>)[value ?? ''] ?? (value || '未验证')
}

function formatEvidenceKind(value?: string | null) {
  return ({
    DOCUMENT_GROUNDED: '文档证据',
    USER_EXPLICIT: '用户明示',
    USER_CONFIRMED: '用户确认',
    TOOL_VERIFIED: '工具验证',
    CHAT_INFERRED: '对话推断',
    BEHAVIOR_INFERRED: '行为推断',
    LLM_SUMMARIZED_EXPERIENCE: '经验总结',
    DERIVED: '派生',
  } as Record<string, string>)[value ?? ''] ?? (value || '未知')
}

function formatOriginType(value?: string | null) {
  return ({
    CHAT: '对话抽取',
    KNOWLEDGE_BASE_DOCUMENT: '知识库文档',
    MANUAL: '手动维护',
    TOOL: '工具写入',
    CONSOLIDATION: '记忆巩固',
  } as Record<string, string>)[value ?? ''] ?? (value || '未知来源')
}

function formatLifecycleState(value?: string | null) {
  return ({
    ACTIVE: '可用',
    ARCHIVED: '已归档',
    SUPERSEDED: '已替换',
    EXPIRED: '已过期',
    STALE_CANDIDATE: '可能过时',
  } as Record<string, string>)[value ?? ''] ?? (value || '未知')
}
</script>

<template>
  <div class="memory-insight" data-test="memory-insight-panel">
    <div class="memory-insight__header">
      <div class="memory-insight__mark">
        <Brain class="size-4" />
      </div>
      <div class="min-w-0 flex-1">
        <div class="memory-insight__eyebrow">{{ sourceKindLabel }}</div>
        <h2 class="memory-insight__title">{{ entity?.name || panelSource?.name || '记忆详情' }}</h2>
      </div>
      <div class="memory-insight__header-actions">
        <template v-if="entity && !editing && !confirmingForget">
          <Button type="button" size="sm" variant="outline" @click="startEdit">
            <Pencil class="size-3.5" />
            编辑
          </Button>
          <Button type="button" size="sm" variant="outline" @click="startForget">
            <Trash2 class="size-3.5" />
            忘记
          </Button>
        </template>
        <Button
          type="button"
          size="icon-sm"
          variant="ghost"
          aria-label="关闭记忆详情"
          title="关闭记忆详情"
          @click="emit('close')"
        >
          <X class="size-4" />
        </Button>
      </div>
    </div>

    <div v-if="loading" class="memory-insight__loading">
      <Skeleton class="h-8 w-2/3" />
      <Skeleton class="h-24 w-full" />
      <Skeleton class="h-20 w-full" />
    </div>

    <template v-else-if="error && panelSource">
      <div class="memory-insight__empty memory-insight__empty--inline memory-insight__empty--actionable">
        <span>{{ error }}</span>
        <div class="memory-insight__empty-actions">
          <button
            type="button"
            class="memory-insight__retry-button"
            title="重新加载记忆详情"
            aria-label="重新加载记忆详情"
            @click="retryLoadMemory"
          >
            <RefreshCw class="size-3.5" />
            <span>重试</span>
          </button>
          <button
            type="button"
            class="memory-insight__source-button"
            title="打开记忆管理"
            aria-label="打开记忆管理"
            @click="openMemoryPage"
          >
            <ArrowUpRight class="size-3.5" />
            <span>记忆管理</span>
          </button>
        </div>
      </div>
      <section class="memory-insight__section">
        <div class="memory-insight__chips">
          <Badge variant="secondary">{{ typeLabel }}</Badge>
          <Badge variant="outline">对话摘要</Badge>
        </div>
        <div v-if="sourceExplanation" class="memory-insight__why">
          <span>为什么出现</span>
          <p>{{ sourceExplanation }}</p>
        </div>
        <div v-if="sourceFallbackImpact" class="memory-insight__impact">
          <span>后续影响</span>
          <p>{{ sourceFallbackImpact }}</p>
        </div>
        <p v-if="sourceEvidence" class="memory-insight__quote">{{ sourceEvidence }}</p>
        <p class="memory-insight__text">{{ sourceFallbackDescription }}</p>
      </section>
    </template>

    <div v-else-if="error" class="memory-insight__empty">
      {{ error }}
    </div>

    <template v-else-if="panelSource && isDeletedMemoryChange">
      <section class="memory-insight__section">
        <div class="memory-insight__chips">
          <Badge variant="secondary">{{ typeLabel }}</Badge>
          <Badge variant="outline">已忘记</Badge>
        </div>
        <div v-if="sourceExplanation" class="memory-insight__why">
          <span>为什么出现</span>
          <p>{{ sourceExplanation }}</p>
        </div>
        <div v-if="deletedMemoryImpact" class="memory-insight__impact">
          <span>后续影响</span>
          <p>{{ deletedMemoryImpact }}</p>
        </div>
        <p v-if="sourceEvidence" class="memory-insight__quote">{{ sourceEvidence }}</p>
        <p class="memory-insight__text">
          这条记忆已经从后续默认参考中移除。需要重新启用时，直接在对话里告诉知微新的偏好或事实即可。
        </p>
      </section>
    </template>

    <template v-else-if="entity">
      <section class="memory-insight__section">
        <div class="memory-insight__chips">
          <Badge variant="secondary">{{ typeLabel }}</Badge>
          <Badge variant="outline">{{ formatTrustLevel(entity.trustLevel) }}</Badge>
          <Badge variant="outline">{{ formatEvidenceKind(entity.evidenceKind) }}</Badge>
        </div>
        <div v-if="sourceExplanation" class="memory-insight__why">
          <span>为什么出现</span>
          <p>{{ sourceExplanation }}</p>
        </div>
        <div v-if="usageImpact" id="memory-usage-impact-preview" class="memory-insight__impact">
          <span>{{ usageImpactLabel }}</span>
          <p>{{ usageImpact }}</p>
        </div>
        <p v-if="sourceEvidence" class="memory-insight__quote">{{ sourceEvidence }}</p>
        <p class="memory-insight__text">{{ visibleDescription }}</p>
      </section>

      <section v-if="editing" class="memory-insight__section memory-insight__edit">
        <label class="memory-insight__label" for="memory-edit-name">名称</label>
        <Input id="memory-edit-name" v-model="editName" />
        <label class="memory-insight__label" for="memory-edit-description">描述</label>
        <Textarea id="memory-edit-description" v-model="editDescription" rows="4" />
        <div class="memory-insight__importance-head">
          <label id="memory-edit-importance-label" class="memory-insight__label">
            参考强度
          </label>
          <span class="memory-insight__importance-value">
            {{ editImportancePercent }} · {{ editImportanceLabel }}
          </span>
        </div>
        <Slider
          data-test="memory-importance-slider"
          :model-value="editImportanceSliderValue"
          :min="0"
          :max="100"
          :step="5"
          aria-labelledby="memory-edit-importance-label"
          aria-describedby="memory-usage-impact-preview"
          @update:model-value="updateImportanceScore"
        />
        <div
          v-if="editChangeSummary"
          class="memory-insight__edit-summary"
          aria-live="polite"
        >
          <span>改动预览</span>
          <p>{{ editChangeSummary }}，保存后会影响后续引用这条记忆的方式。</p>
        </div>
        <button
          type="button"
          class="memory-insight__advanced-toggle"
          :aria-expanded="showAdvancedProperties"
          aria-controls="memory-advanced-properties"
          @click="showAdvancedProperties = !showAdvancedProperties"
        >
          <component :is="showAdvancedProperties ? ChevronDown : ChevronRight" class="size-3.5" />
          <span>高级属性</span>
          <span class="memory-insight__advanced-hint">JSON</span>
        </button>
        <div
          v-if="showAdvancedProperties"
          id="memory-advanced-properties"
          class="memory-insight__advanced-body"
        >
          <label class="memory-insight__label" for="memory-edit-properties">属性 JSON</label>
          <Textarea
            id="memory-edit-properties"
            v-model="editPropertiesText"
            rows="7"
            class="font-mono text-xs"
          />
        </div>
        <div class="memory-insight__actions">
          <Button type="button" variant="outline" :disabled="saving" @click="cancelEdit">取消</Button>
          <Button
            type="button"
            :disabled="saving || !hasEditChanges"
            :title="hasEditChanges ? '保存记忆改动' : '还没有可保存的改动'"
            @click="saveEdit"
          >
            <Save class="size-3.5" />
            {{ saving ? '保存中' : '保存' }}
          </Button>
        </div>
      </section>

      <section v-else-if="confirmingForget" class="memory-insight__section memory-insight__forget">
        <div>
          <div class="memory-insight__section-title">忘记这条记忆</div>
          <p class="memory-insight__forget-text">
            这会把当前记忆从可用记忆中归档。当前对话里的相关记忆标签也会同步移除。
          </p>
        </div>
        <div class="memory-insight__actions">
          <Button type="button" variant="outline" :disabled="forgetting" @click="cancelForget">取消</Button>
          <Button type="button" variant="destructive" :disabled="forgetting" @click="forgetMemory">
            <Trash2 class="size-3.5" />
            {{ forgetting ? '处理中' : '确认忘记' }}
          </Button>
        </div>
      </section>

      <section v-else class="memory-insight__section">
        <div class="memory-insight__grid">
          <div>
            <span>可信度</span>
            <strong>{{ formatPercent(entity.trustScore) }}</strong>
          </div>
          <div>
            <span>证据数</span>
            <strong>{{ entity.evidenceCount }}</strong>
          </div>
          <div>
            <span>重要性</span>
            <strong>{{ entity.importanceScore.toFixed(2) }}</strong>
          </div>
          <div>
            <span>更新时间</span>
            <strong>{{ formatDate(entity.updatedAt) }}</strong>
          </div>
          <div>
            <span>最近验证</span>
            <strong>{{ formatDate(entity.lastVerifiedAt) }}</strong>
          </div>
        </div>
      </section>

      <section v-if="!editing && !confirmingForget" class="memory-insight__section">
        <button
          type="button"
          class="memory-insight__advanced-toggle"
          :aria-expanded="showTechnicalDetails"
          aria-controls="memory-technical-details"
          @click="showTechnicalDetails = !showTechnicalDetails"
        >
          <component :is="showTechnicalDetails ? ChevronDown : ChevronRight" class="size-3.5" />
          <span>更多细节</span>
          <span class="memory-insight__advanced-hint">可展开</span>
        </button>
        <div
          v-if="showTechnicalDetails"
          id="memory-technical-details"
          class="memory-insight__advanced-body"
        >
          <div class="memory-insight__grid">
            <div>
              <span>版本</span>
              <strong>v{{ entity.version }}</strong>
            </div>
            <div>
              <span>状态</span>
              <strong>{{ formatLifecycleState(entity.lifecycleState) }}</strong>
            </div>
          </div>
          <div>
            <div class="memory-insight__section-title">属性</div>
            <pre class="memory-insight__properties">{{ JSON.stringify(entity.properties, null, 2) }}</pre>
          </div>
        </div>
      </section>

      <section class="memory-insight__section">
        <div class="memory-insight__section-head">
          <div class="memory-insight__section-title">来源证据</div>
          <button
            v-if="hiddenProvenanceCount > 0 || (showAllProvenances && provenances.length > 1)"
            type="button"
            class="memory-insight__link-button"
            :aria-expanded="showAllProvenances"
            @click="showAllProvenances = !showAllProvenances"
          >
            {{ showAllProvenances ? '收起' : `展开全部 ${provenances.length} 条` }}
          </button>
        </div>
        <div v-if="loadingProvenance" class="space-y-2">
          <Skeleton class="h-16 w-full" />
          <Skeleton class="h-16 w-full" />
        </div>
        <div
          v-else-if="provenanceError"
          class="memory-insight__empty memory-insight__empty--inline memory-insight__empty--actionable"
        >
          <span>来源证据暂时无法加载：{{ provenanceError }}</span>
          <button
            type="button"
            class="memory-insight__retry-button"
            title="重新加载来源证据"
            aria-label="重新加载来源证据"
            @click="retryLoadProvenances"
          >
            <RefreshCw class="size-3.5" />
            <span>重试</span>
          </button>
        </div>
        <div v-else-if="visibleProvenances.length" class="memory-insight__provenance-list">
          <div
            v-for="(item, index) in visibleProvenances"
            :key="`${item.sourceEntryId ?? item.sourceReference ?? item.createdAt}-${index}`"
            class="memory-insight__provenance"
          >
            <div class="memory-insight__provenance-head">
              <div class="memory-insight__chips">
                <Badge variant="outline">{{ formatOriginType(item.originType) }}</Badge>
                <Badge variant="secondary">{{ formatTrustLevel(item.trustLevel) }}</Badge>
                <Badge
                  v-if="isStaleProvenance(item)"
                  :variant="needsRevalidationReview(item) ? 'destructive' : 'outline'"
                >
                  {{ formatProvenanceReviewStatus(item) }}
                </Badge>
              </div>
              <span v-if="index === 0" class="memory-insight__latest-badge">最新</span>
            </div>
            <p v-if="index === 0 && reliabilityExplanation" class="memory-insight__reliability">
              {{ reliabilityExplanation }}
            </p>
            <div
              v-if="provenanceInvalidationText(item)"
              :class="[
                'memory-insight__stale-note',
                isResolvedRevalidation(item) ? 'memory-insight__stale-note--resolved' : '',
              ]"
            >
              <component
                :is="isResolvedRevalidation(item) ? CheckCircle2 : AlertTriangle"
                class="size-3.5"
              />
              <div class="memory-insight__stale-body">
                <span>{{ provenanceInvalidationText(item) }}</span>
                <button
                  v-if="needsRevalidationReview(item)"
                  type="button"
                  class="memory-insight__resolve-button"
                  :disabled="resolvingRevalidation"
                  data-test="resolve-memory-revalidation"
                  @click="resolveRevalidation"
                >
                  <CheckCircle2 class="size-3.5" />
                  {{ resolvingRevalidation ? '确认中' : '确认仍有效' }}
                </button>
              </div>
            </div>
            <p v-if="item.evidenceExcerpt" class="memory-insight__quote">
              {{ item.evidenceExcerpt }}
            </p>
            <div class="memory-insight__meta">
              <span>置信度 {{ formatPercent(item.confidence) }}</span>
              <span v-if="provenanceReadableSource(item)">{{ provenanceReadableSource(item) }}</span>
              <span>{{ formatDate(item.createdAt) }}</span>
            </div>
            <button
              v-if="canOpenProvenanceSource(item)"
              type="button"
              class="memory-insight__source-button"
              :title="provenanceSourceLabel(item)"
              :aria-label="provenanceSourceLabel(item)"
              @click="openProvenanceSource(item)"
            >
              <ArrowUpRight class="size-3.5" />
              {{ provenanceSourceLabel(item) }}
            </button>
          </div>
        </div>
        <div v-else class="memory-insight__empty memory-insight__empty--inline">
          暂无来源证据
        </div>
      </section>

      <Button type="button" variant="outline" class="w-full" @click="openMemoryPage">
        <ArrowUpRight class="size-3.5" />
        打开记忆管理
      </Button>
    </template>

    <div v-else class="memory-insight__empty">
      选择一条记忆后查看详情。
    </div>
  </div>
</template>

<style scoped>
.memory-insight {
  display: flex;
  min-height: 100%;
  flex-direction: column;
  gap: 14px;
}

.memory-insight__header {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.memory-insight__header-actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}

.memory-insight__mark {
  display: inline-flex;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.48);
  background: hsl(from var(--muted) h s l / 0.26);
  color: hsl(from var(--primary) h s l / 0.88);
}

.memory-insight__eyebrow {
  color: var(--muted-foreground);
  font-size: 11px;
}

.memory-insight__title {
  margin: 2px 0 0;
  overflow: hidden;
  color: var(--foreground);
  font-size: 18px;
  font-weight: 650;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.memory-insight__loading {
  display: grid;
  gap: 12px;
}

.memory-insight__section {
  display: grid;
  gap: 10px;
  border-top: 1px solid hsl(from var(--border) h s l / 0.5);
  padding-top: 14px;
}

.memory-insight__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.memory-insight__text {
  margin: 0;
  color: hsl(from var(--foreground) h s l / 0.9);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.memory-insight__quote {
  margin: 0;
  border-left: 2px solid hsl(from var(--primary) h s l / 0.36);
  padding-left: 10px;
  color: hsl(from var(--muted-foreground) h s l / 0.95);
  font-size: 12px;
  line-height: 1.65;
  white-space: pre-wrap;
}

.memory-insight__why {
  display: grid;
  gap: 4px;
  border-radius: 10px;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--muted) h s l / 0.22);
  padding: 9px 10px;
}

.memory-insight__impact {
  display: grid;
  gap: 4px;
  border-radius: 10px;
  border: 1px solid hsl(from var(--primary) h s l / 0.18);
  background: hsl(from var(--primary) h s l / 0.05);
  padding: 9px 10px;
}

.memory-insight__why span,
.memory-insight__impact span {
  color: var(--muted-foreground);
  font-size: 11px;
  font-weight: 600;
}

.memory-insight__why p,
.memory-insight__impact p {
  margin: 0;
  color: hsl(from var(--foreground) h s l / 0.86);
  font-size: 12px;
  line-height: 1.6;
}

.memory-insight__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.memory-insight__grid div {
  min-width: 0;
}

.memory-insight__grid span {
  display: block;
  color: var(--muted-foreground);
  font-size: 11px;
}

.memory-insight__grid strong {
  display: block;
  margin-top: 2px;
  overflow: hidden;
  color: hsl(from var(--foreground) h s l / 0.9);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.memory-insight__section-title,
.memory-insight__label {
  color: var(--muted-foreground);
  font-size: 11px;
  font-weight: 600;
}

.memory-insight__section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.memory-insight__link-button {
  display: inline-flex;
  min-height: 24px;
  flex: 0 0 auto;
  align-items: center;
  border-radius: 999px;
  padding: 0 8px;
  color: hsl(from var(--primary) h s l / 0.9);
  cursor: pointer;
  font-size: 11px;
  font-weight: 500;
  transition:
    background-color 140ms ease,
    color 140ms ease;
}

.memory-insight__link-button:hover {
  background: hsl(from var(--primary) h s l / 0.08);
  color: hsl(from var(--primary) h s l / 1);
}

.memory-insight__link-button:focus-visible {
  outline: 2px solid hsl(from var(--ring) h s l / 0.65);
  outline-offset: 2px;
}

.memory-insight__edit {
  gap: 8px;
}

.memory-insight__importance-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.memory-insight__importance-value {
  flex: 0 0 auto;
  color: hsl(from var(--muted-foreground) h s l / 0.9);
  font-size: 12px;
  font-weight: 500;
}

.memory-insight__edit-summary {
  display: grid;
  gap: 3px;
  border-radius: 10px;
  border: 1px solid hsl(from var(--primary) h s l / 0.16);
  background: hsl(from var(--primary) h s l / 0.045);
  padding: 8px 10px;
}

.memory-insight__edit-summary span {
  color: hsl(from var(--primary) h s l / 0.9);
  font-size: 11px;
  font-weight: 600;
}

.memory-insight__edit-summary p {
  margin: 0;
  color: hsl(from var(--foreground) h s l / 0.82);
  font-size: 12px;
  line-height: 1.55;
}

.memory-insight__advanced-toggle {
  display: inline-flex;
  width: fit-content;
  align-items: center;
  gap: 6px;
  min-height: 28px;
  margin-top: 2px;
  border-radius: 8px;
  border: 1px solid transparent;
  padding: 0 4px;
  color: hsl(from var(--muted-foreground) h s l / 0.9);
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
  transition:
    color 140ms ease,
    border-color 140ms ease,
    background-color 140ms ease;
}

.memory-insight__advanced-toggle:hover {
  border-color: hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--muted) h s l / 0.2);
  color: var(--foreground);
}

.memory-insight__advanced-toggle:focus-visible {
  outline: 2px solid var(--ring);
  outline-offset: 2px;
}

.memory-insight__advanced-hint {
  color: hsl(from var(--muted-foreground) h s l / 0.62);
  font-size: 11px;
  font-weight: 450;
}

.memory-insight__advanced-body {
  display: grid;
  gap: 8px;
}

.memory-insight__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.memory-insight__forget {
  border: 1px solid hsl(from var(--destructive) h s l / 0.22);
  border-radius: 12px;
  background: hsl(from var(--destructive) h s l / 0.04);
  padding: 12px;
}

.memory-insight__forget-text {
  margin: 6px 0 0;
  color: hsl(from var(--muted-foreground) h s l / 0.95);
  font-size: 12px;
  line-height: 1.65;
}

.memory-insight__provenance-list {
  display: grid;
  gap: 8px;
}

.memory-insight__provenance {
  display: grid;
  gap: 8px;
  border-radius: 10px;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  padding: 9px 10px;
}

.memory-insight__provenance-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.memory-insight__latest-badge {
  flex: 0 0 auto;
  border-radius: 999px;
  background: hsl(from var(--primary) h s l / 0.08);
  padding: 2px 7px;
  color: hsl(from var(--primary) h s l / 0.88);
  font-size: 11px;
  font-weight: 500;
}

.memory-insight__reliability {
  margin: 0;
  color: hsl(from var(--foreground) h s l / 0.82);
  font-size: 12px;
  line-height: 1.6;
}

.memory-insight__stale-note {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 0;
  color: hsl(from var(--destructive) h s l / 0.9);
  font-size: 12px;
  line-height: 1.6;
}

.memory-insight__stale-note--resolved {
  color: hsl(from var(--foreground) h s l / 0.68);
}

.memory-insight__stale-note svg {
  margin-top: 2px;
  flex: 0 0 auto;
}

.memory-insight__stale-body {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.memory-insight__resolve-button {
  display: inline-flex;
  width: fit-content;
  min-height: 24px;
  align-items: center;
  gap: 5px;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.48);
  background: hsl(from var(--background) h s l / 0.72);
  padding: 0 8px;
  color: hsl(from var(--foreground) h s l / 0.76);
  cursor: pointer;
  font-size: 11px;
  font-weight: 500;
}

.memory-insight__resolve-button:hover:not(:disabled) {
  border-color: hsl(from var(--primary) h s l / 0.3);
  background: hsl(from var(--primary) h s l / 0.07);
  color: hsl(from var(--primary) h s l / 0.9);
}

.memory-insight__resolve-button:disabled {
  cursor: default;
  opacity: 0.58;
}

.memory-insight__meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px;
  color: var(--muted-foreground);
  font-size: 11px;
}

.memory-insight__source-button {
  display: inline-flex;
  width: fit-content;
  min-height: 26px;
  align-items: center;
  gap: 5px;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.44);
  background: hsl(from var(--background) h s l / 0.62);
  padding: 0 8px;
  color: hsl(from var(--foreground) h s l / 0.72);
  cursor: pointer;
  font-size: 11px;
  font-weight: 500;
  transition:
    border-color 140ms ease,
    background-color 140ms ease,
    color 140ms ease;
}

.memory-insight__source-button:hover {
  border-color: hsl(from var(--primary) h s l / 0.28);
  background: hsl(from var(--primary) h s l / 0.07);
  color: hsl(from var(--primary) h s l / 0.9);
}

.memory-insight__source-button:focus-visible {
  outline: 2px solid hsl(from var(--ring) h s l / 0.65);
  outline-offset: 2px;
}

.memory-insight__retry-button {
  display: inline-flex;
  width: fit-content;
  min-height: 24px;
  align-items: center;
  justify-content: center;
  gap: 5px;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.44);
  background: hsl(from var(--background) h s l / 0.72);
  padding: 0 8px;
  color: hsl(from var(--foreground) h s l / 0.74);
  cursor: pointer;
  font-size: 11px;
  font-weight: 500;
  transition:
    border-color 140ms ease,
    background-color 140ms ease,
    color 140ms ease;
}

.memory-insight__retry-button:hover {
  border-color: hsl(from var(--primary) h s l / 0.28);
  background: hsl(from var(--primary) h s l / 0.07);
  color: hsl(from var(--primary) h s l / 0.9);
}

.memory-insight__retry-button:focus-visible {
  outline: 2px solid hsl(from var(--ring) h s l / 0.65);
  outline-offset: 2px;
}

.memory-insight__properties {
  max-height: 220px;
  overflow: auto;
  border-radius: 10px;
  background: hsl(from var(--muted) h s l / 0.3);
  padding: 10px;
  color: hsl(from var(--foreground) h s l / 0.86);
  font-size: 11px;
  line-height: 1.55;
}

.memory-insight__empty {
  border-radius: 12px;
  border: 1px dashed hsl(from var(--border) h s l / 0.55);
  padding: 28px 12px;
  text-align: center;
  color: var(--muted-foreground);
  font-size: 13px;
}

.memory-insight__empty--inline {
  padding: 18px 10px;
}

.memory-insight__empty--actionable {
  display: grid;
  justify-items: center;
  gap: 8px;
}

.memory-insight__empty-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}
</style>
