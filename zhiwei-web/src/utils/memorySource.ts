import type { EntityDetail, Message, SourceSummary } from '@/types'

type MemorySourceVariant = 'source' | 'change'

export interface MemoryChangeOverview {
  action: string
  detail: string
  impact: string
}

function readString(value: unknown) {
  return typeof value === 'string' && value.trim() ? value.trim() : ''
}

function readNumber(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

export function isSkillLikeMemorySource(source: SourceSummary) {
  const entityType = readString(source.extra?.entityType).toUpperCase()
  const entityTypeLabel = readString(source.extra?.entityTypeLabel)
  return entityType.includes('SKILL') || entityTypeLabel.includes('技能')
}

export function memorySourceCollectionNoun(sources: SourceSummary[]) {
  const skillCount = sources.filter(isSkillLikeMemorySource).length
  if (skillCount === sources.length && skillCount > 0) {
    return '技能'
  }
  if (skillCount > 0) {
    return '上下文'
  }
  return '记忆'
}

export function memorySourceInspectableNoun(source: SourceSummary) {
  return isSkillLikeMemorySource(source) ? '技能' : '记忆'
}

export function isDeleteMemoryChange(source: SourceSummary) {
  const operation = readString(source.extra?.operation).toUpperCase()
  const operationLabel = readString(source.extra?.operationLabel)
  return operation === 'DELETE'
    || ['忘记', '删除', '移除', '取消'].some(label => operationLabel.includes(label))
}

function truncateReasonText(value: string, maxLength = 72) {
  if (value.length <= maxLength) return value
  return `${value.slice(0, maxLength - 1)}…`
}

function sentenceText(value: string) {
  return /[。！？.!?]$/.test(value) ? value : `${value}。`
}

export function formatMemoryTrustLevel(value: unknown) {
  const text = readString(value)
  if (!text) return ''
  return ({
    VERIFIED: '已验证',
    EXPLICIT: '用户明确',
    INFERRED: '推断',
    DERIVED: '派生',
    UNVERIFIED: '未验证',
  } as Record<string, string>)[text] ?? text
}

export function formatMemoryTrustScore(value: unknown) {
  if (typeof value !== 'number' || Number.isNaN(value)) return ''
  return `${(Math.max(0, Math.min(1, value)) * 100).toFixed(0)}%`
}

export function buildMemoryTrustText(source: SourceSummary) {
  const level = formatMemoryTrustLevel(source.extra?.trustLevel)
  const score = formatMemoryTrustScore(source.extra?.trustScore)
  if (level && score) return `${level} · ${score}`
  return level || score
}

export function buildMemorySourceExplanation(
  source: SourceSummary,
  variant: MemorySourceVariant = 'source',
) {
  const entityTypeLabel = readString(source.extra?.entityTypeLabel) || '记忆'
  const evidenceExcerpt = readString(source.extra?.evidenceExcerpt)
  const description = readString(source.extra?.description)
  const operationLabel = readString(source.extra?.operationLabel) || '沉淀'
  const usageReason = readString(source.extra?.usageReason)
  const sourceKindLabel = readString(source.extra?.sourceKindLabel)
  const contextPrefix = sourceKindLabel ? `在${sourceKindLabel}` : ''

  if (variant === 'change') {
    if (evidenceExcerpt) {
      return `因为你本轮提到「${truncateReasonText(evidenceExcerpt)}」，知微已${contextPrefix}${operationLabel}这条${entityTypeLabel}。`
    }
    if (description) {
      return `知微把本轮可长期复用的信息${contextPrefix}${operationLabel}为${entityTypeLabel}：${sentenceText(truncateReasonText(description))}`
    }
    return `知微把本轮可长期复用的信息${contextPrefix}${operationLabel}为这条${entityTypeLabel}。`
  }

  if (usageReason) {
    return sentenceText(truncateReasonText(usageReason))
  }
  if (description) {
    return `知微参考它来贴合你的${entityTypeLabel}：${sentenceText(truncateReasonText(description))}`
  }
  if (evidenceExcerpt) {
    return `知微参考它，是因为它来自你之前提到的「${truncateReasonText(evidenceExcerpt)}」。`
  }
  return `这条${entityTypeLabel}帮助知微延续对你的理解。`
}

function formatMemoryScopeImpact(scope?: string | null) {
  return ({
    USER_PROFILE: '会跨对话用于个人化回复',
    USER_FACT: '会跨对话用于减少重复询问',
    AGENT_EXPERIENCE: '会帮助知微复用做事经验',
    DOMAIN_MEMORY: '只在相关资料或项目场景中使用',
    PROJECT: '只在当前项目上下文中使用',
    SESSION: '主要在当前会话中参考',
  } as Record<string, string>)[scope ?? ''] ?? '会在相关上下文中参考'
}

function formatTemporalityImpact(temporality?: string | null, expiresAt?: string | null) {
  if (expiresAt) {
    return `有效到 ${new Date(expiresAt).toLocaleDateString('zh-CN')}`
  }
  return ({
    PERSISTENT: '长期生效',
    PERMANENT: '长期生效',
    TEMPORARY: '短期参考',
    EPHEMERAL: '本轮优先参考',
  } as Record<string, string>)[temporality ?? ''] ?? '按上下文生效'
}

function formatImportanceImpact(score?: number | null) {
  if (typeof score !== 'number' || Number.isNaN(score)) {
    return '按上下文需要使用'
  }
  if (score >= 0.75) return '优先级较高'
  if (score <= 0.35) return '低频参考'
  return '按上下文需要使用'
}

function formatTypeImpact(entity: Pick<EntityDetail, 'type' | 'typeLabel'>) {
  const type = readString(entity.type).toUpperCase()
  const label = readString(entity.typeLabel)
  if (type.includes('PREFERENCE') || label.includes('偏好')) {
    return '会影响语气、方案取舍和界面建议'
  }
  if (type.includes('PERSON') || label.includes('用户')) {
    return '会帮助知微识别你是谁、偏好什么'
  }
  if (type.includes('PROJECT') || label.includes('项目')) {
    return '会帮助知微延续项目背景'
  }
  if (type.includes('PROCEDURE') || label.includes('流程') || label.includes('经验')) {
    return '会帮助知微复用做事步骤'
  }
  return '会帮助知微延续对这件事的理解'
}

export function buildMemoryUsageImpact(entity: Pick<EntityDetail,
  'type'
  | 'typeLabel'
  | 'memoryScope'
  | 'temporality'
  | 'expiresAt'
  | 'importanceScore'
>) {
  return [
    formatMemoryScopeImpact(entity.memoryScope),
    formatTypeImpact(entity),
    formatTemporalityImpact(entity.temporality, entity.expiresAt),
    formatImportanceImpact(entity.importanceScore),
  ].join('；')
}

export function buildMemoryChangeImpact(source: SourceSummary) {
  if (isDeleteMemoryChange(source)) {
    return `后续不再默认参考这条${memorySourceInspectableNoun(source)}；需要时可以重新告诉知微要记住什么`
  }

  const impacts = [
    formatTypeImpact({
      type: readString(source.extra?.entityType),
      typeLabel: readString(source.extra?.entityTypeLabel),
    }),
  ]
  const temporality = readString(source.extra?.temporality)
  const expiresAt = readString(source.extra?.expiresAt)
  if (temporality || expiresAt) {
    impacts.push(formatTemporalityImpact(temporality, expiresAt))
  }
  const importanceScore = readNumber(source.extra?.importanceScore)
  if (importanceScore !== null) {
    impacts.push(formatImportanceImpact(importanceScore))
  }
  return impacts.filter(Boolean).join('；')
}

function changeTypeLabel(source: SourceSummary) {
  return readString(source.extra?.entityTypeLabel) || '记忆'
}

function changeOperationLabel(source: SourceSummary) {
  return readString(source.extra?.operationLabel) || '沉淀'
}

function summarizeChangeAction(sources: SourceSummary[]) {
  if (sources.length === 1) {
    const first = sources[0]
    return `${changeOperationLabel(first)} 1 条${changeTypeLabel(first)}`
  }

  const groups = new Map<string, { operation: string, type: string, count: number }>()
  for (const source of sources) {
    const operation = changeOperationLabel(source)
    const type = changeTypeLabel(source)
    const key = `${operation}:${type}`
    const current = groups.get(key)
    if (current) {
      current.count += 1
    } else {
      groups.set(key, { operation, type, count: 1 })
    }
  }

  const visibleGroups = Array.from(groups.values()).slice(0, 2)
  const suffix = groups.size > visibleGroups.length ? `等 ${sources.length} 条` : ''
  return `${visibleGroups.map(group => `${group.operation} ${group.count} 条${group.type}`).join('、')}${suffix}`
}

function summarizeChangeReason(source: SourceSummary) {
  const name = readString(source.name) || changeTypeLabel(source)
  const evidenceExcerpt = readString(source.extra?.evidenceExcerpt)
  const description = readString(source.extra?.description)
  if (evidenceExcerpt) {
    return `${name}：因为「${truncateReasonText(evidenceExcerpt, 32)}」`
  }
  if (description) {
    return `${name}：${sentenceText(truncateReasonText(description, 36))}`
  }
  return `${name}：可在后续相关上下文中参考`
}

function summarizeChangeImpact(sources: SourceSummary[]) {
  const impacts = sources
    .flatMap(source => buildMemoryChangeImpact(source).split('；'))
    .map(readString)
    .filter(Boolean)
  return uniqueText(impacts).slice(0, 4).join('；')
}

function uniqueText(values: string[]) {
  return Array.from(new Set(values))
}

export function buildMemoryChangeOverview(sources: SourceSummary[]): MemoryChangeOverview | null {
  const memoryChanges = sources.filter(source => source.type === 'memory')
  if (memoryChanges.length === 0) {
    return null
  }

  if (memoryChanges.length === 1) {
    const first = memoryChanges[0]
    return {
      action: summarizeChangeAction(memoryChanges),
      detail: buildMemorySourceExplanation(first, 'change'),
      impact: buildMemoryChangeImpact(first),
    }
  }

  const visibleReasons = memoryChanges.slice(0, 3).map(summarizeChangeReason)
  const hiddenCount = memoryChanges.length - visibleReasons.length
  const hiddenText = hiddenCount > 0 ? `；另有 ${hiddenCount} 条可在下方逐条查看和编辑` : ''
  const reasonText = `${visibleReasons.join('；')}${hiddenText}`
  const noun = memorySourceCollectionNoun(memoryChanges)
  return {
    action: summarizeChangeAction(memoryChanges),
    detail: `本轮共沉淀 ${memoryChanges.length} 条${noun}，包括 ${sentenceText(reasonText)}`,
    impact: summarizeChangeImpact(memoryChanges),
  }
}

export function applyEntityToMemorySource(source: SourceSummary, entity: EntityDetail): SourceSummary {
  if (source.type !== 'memory' || source.id !== entity.id) {
    return source
  }

  return {
    ...source,
    name: entity.name,
    extra: {
      ...(source.extra ?? {}),
      description: entity.description ?? undefined,
      entityType: entity.type,
      entityTypeLabel: entity.typeLabel,
      evidenceKind: entity.evidenceKind,
      trustLevel: entity.trustLevel,
      trustScore: entity.trustScore,
      evidenceCount: entity.evidenceCount,
      memoryScope: entity.memoryScope ?? undefined,
      temporality: entity.temporality,
      expiresAt: entity.expiresAt ?? undefined,
      importanceScore: entity.importanceScore,
      updatedAt: entity.updatedAt,
    },
  }
}

export function buildDeletedMemorySource(
  source: SourceSummary,
  fallback?: Pick<EntityDetail, 'name' | 'description' | 'typeLabel'> | null,
): SourceSummary {
  const extra = { ...(source.extra ?? {}) }
  if (!readString(extra.operation)) {
    extra.operation = 'DELETE'
  }
  if (!readString(extra.operationLabel)) {
    extra.operationLabel = '忘记'
  }
  if (!readString(extra.description) && fallback?.description) {
    extra.description = fallback.description
  }
  if (!readString(extra.entityTypeLabel) && fallback?.typeLabel) {
    extra.entityTypeLabel = fallback.typeLabel
  }

  return {
    ...source,
    type: 'memory',
    name: fallback?.name || source.name,
    extra,
  }
}

export function updateMemorySourceList(
  sources: SourceSummary[] | undefined,
  entity: EntityDetail,
): SourceSummary[] | undefined {
  if (!sources?.length) return sources

  let changed = false
  const next = sources.map(source => {
    const updated = applyEntityToMemorySource(source, entity)
    if (updated !== source) changed = true
    return updated
  })
  return changed ? next : sources
}

export function buildMemoryEntityMessagePatch(
  message: Message,
  entity: EntityDetail,
): Partial<Message> | null {
  const sources = updateMemorySourceList(message.sources, entity)
  const memoryChanges = updateMemorySourceList(message.memoryChanges, entity)
  const patch: Partial<Message> = {}

  if (sources !== message.sources) {
    patch.sources = sources
  }
  if (memoryChanges !== message.memoryChanges) {
    patch.memoryChanges = memoryChanges
    if (!memoryChanges && message.memoryChangeStatus) {
      patch.memoryChangeStatus = undefined
      patch.memoryChangeReason = undefined
    }
  }

  return Object.keys(patch).length ? patch : null
}

function removeMemorySourceList(
  sources: SourceSummary[] | undefined,
  entityId: string,
): SourceSummary[] | undefined {
  if (!sources?.length) return sources

  const next = sources.filter(source => source.type !== 'memory' || source.id !== entityId)
  if (next.length === sources.length) return sources
  return next.length ? next : undefined
}

export function buildMemoryEntityRemovalMessagePatch(
  message: Message,
  entityId: string,
): Partial<Message> | null {
  const sources = removeMemorySourceList(message.sources, entityId)
  const memoryChanges = removeMemorySourceList(message.memoryChanges, entityId)
  const patch: Partial<Message> = {}

  if (sources !== message.sources) {
    patch.sources = sources
  }
  if (memoryChanges !== message.memoryChanges) {
    patch.memoryChanges = memoryChanges
    if (!memoryChanges && message.memoryChangeStatus) {
      patch.memoryChangeStatus = undefined
      patch.memoryChangeReason = undefined
    }
  }

  return Object.keys(patch).length ? patch : null
}
