import { describe, expect, it } from 'vitest'
import {
  buildDeletedMemorySource,
  buildMemoryEntityMessagePatch,
  buildMemoryEntityRemovalMessagePatch,
  buildMemoryChangeImpact,
  buildMemoryChangeOverview,
  buildMemorySourceExplanation,
  buildMemoryTrustText,
  buildMemoryUsageImpact,
} from './memorySource'
import type { EntityDetail, Message } from '@/types'

function entity(overrides: Partial<EntityDetail> = {}): EntityDetail {
  return {
    id: 'memory-1',
    type: 'PREFERENCE',
    typeLabel: '偏好',
    name: '轻量主界面偏好',
    description: '用户偏好更轻、更少入口的主界面。',
    spaceId: null,
    memoryScope: 'USER_PROFILE',
    realityType: 'REAL',
    properties: {},
    version: 2,
    isCurrent: true,
    validFrom: '2026-07-04T00:00:00Z',
    validTo: null,
    sourceConversationId: null,
    lifecycleState: 'ACTIVE',
    lifecycleReason: null,
    expiresAt: null,
    temporality: 'PERSISTENT',
    succeededBy: null,
    isDerived: false,
    derivationSources: [],
    evidenceKind: 'USER_EXPLICIT',
    trustLevel: 'EXPLICIT',
    trustScore: 0.91,
    evidenceCount: 3,
    lastVerifiedAt: '2026-07-04T01:00:00Z',
    extractionConfidence: 0.9,
    importanceScore: 0.8,
    accessCount: 1,
    lastAccessedAt: null,
    createdAt: '2026-07-04T00:00:00Z',
    updatedAt: '2026-07-04T02:00:00Z',
    ...overrides,
  }
}

describe('memorySource', () => {
  it('为参考记忆生成自然解释', () => {
    const source = {
      type: 'memory' as const,
      id: 'memory-1',
      name: '主界面偏好',
      extra: {
        entityTypeLabel: '偏好',
        description: '用户偏好更轻、更少入口的主界面。',
        trustLevel: 'EXPLICIT',
        trustScore: 0.91,
      },
    }

    expect(buildMemorySourceExplanation(source)).toBe(
      '知微参考它来贴合你的偏好：用户偏好更轻、更少入口的主界面。',
    )
    expect(buildMemoryTrustText(source)).toBe('用户明确 · 91%')
  })

  it('优先展示后端给出的实际使用原因', () => {
    const source = {
      type: 'memory' as const,
      id: 'memory-1',
      name: '主界面偏好',
      extra: {
        entityTypeLabel: '偏好',
        usageReason: '这条回答实际参考了这条记忆，用来延续与你相关的上下文。',
        description: '用户偏好更轻、更少入口的主界面。',
      },
    }

    expect(buildMemorySourceExplanation(source)).toBe(
      '这条回答实际参考了这条记忆，用来延续与你相关的上下文。',
    )
  })

  it('为本轮沉淀记忆生成自然解释', () => {
    const source = {
      type: 'memory' as const,
      id: 'memory-1',
      name: '主界面偏好',
      extra: {
        operationLabel: '新增',
        entityTypeLabel: '偏好',
        evidenceExcerpt: '主界面做轻，做好交互',
      },
    }

    expect(buildMemorySourceExplanation(source, 'change')).toBe(
      '因为你本轮提到「主界面做轻，做好交互」，知微已新增这条偏好。',
    )
  })

  it('本轮沉淀记忆带出项目上下文解释', () => {
    const source = {
      type: 'memory' as const,
      id: 'memory-1',
      name: '主界面偏好',
      extra: {
        operationLabel: '新增',
        entityTypeLabel: '偏好',
        evidenceExcerpt: '主界面做轻，做好交互',
        sourceKindLabel: '项目上下文',
      },
    }

    expect(buildMemorySourceExplanation(source, 'change')).toBe(
      '因为你本轮提到「主界面做轻，做好交互」，知微已在项目上下文新增这条偏好。',
    )
  })

  it('为记忆生成后续使用影响说明', () => {
    expect(buildMemoryUsageImpact(entity())).toBe(
      '会跨对话用于个人化回复；会影响语气、方案取舍和界面建议；长期生效；优先级较高',
    )
    expect(buildMemoryUsageImpact(entity({
      type: 'PROJECT_CONTEXT',
      typeLabel: '项目背景',
      memoryScope: 'DOMAIN_MEMORY',
      temporality: 'TEMPORARY',
      importanceScore: 0.2,
    }))).toBe(
      '只在相关资料或项目场景中使用；会帮助知微延续项目背景；短期参考；低频参考',
    )
  })

  it('为本轮沉淀记忆生成后续影响说明', () => {
    const source = {
      type: 'memory' as const,
      id: 'memory-1',
      name: '主界面偏好',
      extra: {
        entityType: 'PREFERENCE',
        entityTypeLabel: '偏好',
        temporality: 'PERSISTENT',
        importanceScore: 0.82,
      },
    }

    expect(buildMemoryChangeImpact(source)).toBe(
      '会影响语气、方案取舍和界面建议；长期生效；优先级较高',
    )
  })

  it('为本轮忘记记忆生成自然解释和后续影响', () => {
    const source = {
      type: 'memory' as const,
      id: 'memory-1',
      name: '旧提醒偏好',
      extra: {
        operation: 'DELETE',
        operationLabel: '忘记',
        entityType: 'PREFERENCE',
        entityTypeLabel: '偏好',
        evidenceExcerpt: '以后不要再提醒我下午5点检查日志',
      },
    }

    expect(buildMemorySourceExplanation(source, 'change')).toBe(
      '因为你本轮提到「以后不要再提醒我下午5点检查日志」，知微已忘记这条偏好。',
    )
    expect(buildMemoryChangeImpact(source)).toBe(
      '后续不再默认参考这条记忆；需要时可以重新告诉知微要记住什么',
    )
    expect(buildMemoryChangeOverview([source])).toEqual({
      action: '忘记 1 条偏好',
      detail: '因为你本轮提到「以后不要再提醒我下午5点检查日志」，知微已忘记这条偏好。',
      impact: '后续不再默认参考这条记忆；需要时可以重新告诉知微要记住什么',
    })
  })

  it('为多条本轮沉淀记忆生成可解释概览', () => {
    const overview = buildMemoryChangeOverview([
      {
        type: 'memory',
        id: 'memory-1',
        name: '主界面偏好',
        extra: {
          operationLabel: '新增',
          entityType: 'PREFERENCE',
          entityTypeLabel: '偏好',
          evidenceExcerpt: '主界面做轻，做好交互',
          temporality: 'PERSISTENT',
          importanceScore: 0.82,
        },
      },
      {
        type: 'memory',
        id: 'memory-2',
        name: '工具使用偏好',
        extra: {
          operationLabel: '新增',
          entityType: 'PROCEDURE',
          entityTypeLabel: '经验',
          description: '用户希望 tool/skill 能恢复任务。',
          temporality: 'PERSISTENT',
          importanceScore: 0.7,
        },
      },
    ])

    expect(overview).toEqual({
      action: '新增 1 条偏好、新增 1 条经验',
      detail: '本轮共沉淀 2 条记忆，包括 主界面偏好：因为「主界面做轻，做好交互」；工具使用偏好：用户希望 tool/skill 能恢复任务。',
      impact: '会影响语气、方案取舍和界面建议；长期生效；优先级较高；会帮助知微复用做事步骤',
    })
  })

  it('技能型沉淀概览使用技能而不是记忆', () => {
    const overview = buildMemoryChangeOverview([
      {
        type: 'memory',
        id: 'skill-1',
        name: '后端开发',
        extra: {
          operationLabel: '新增',
          entityType: 'SKILL',
          entityTypeLabel: '技能',
          evidenceExcerpt: '擅长 Spring Boot 后端开发',
        },
      },
      {
        type: 'memory',
        id: 'skill-2',
        name: '算法工程',
        extra: {
          operationLabel: '新增',
          entityType: 'SKILL',
          entityTypeLabel: '技能',
          description: '熟悉推荐系统和检索排序。',
        },
      },
    ])

    expect(overview).toMatchObject({
      action: '新增 2 条技能',
      detail: '本轮共沉淀 2 条技能，包括 后端开发：因为「擅长 Spring Boot 后端开发」；算法工程：熟悉推荐系统和检索排序。',
    })
  })

  it('把编辑后的记忆同步回消息来源和沉淀标签', () => {
    const message: Message = {
      id: 'assistant-1',
      role: 'assistant',
      content: '已完成',
      timestamp: Date.now(),
      sources: [
        { type: 'memory', id: 'memory-1', name: '旧偏好', extra: { entityTypeLabel: '偏好' } },
      ],
      memoryChanges: [
        {
          type: 'memory',
          id: 'memory-1',
          name: '旧偏好',
          extra: {
            operationLabel: '更新',
            evidenceExcerpt: '主界面做轻',
          },
        },
      ],
    }

    const patch = buildMemoryEntityMessagePatch(message, entity())

    expect(patch?.sources?.[0]).toMatchObject({
      id: 'memory-1',
      name: '轻量主界面偏好',
      extra: {
        description: '用户偏好更轻、更少入口的主界面。',
        entityTypeLabel: '偏好',
        memoryScope: 'USER_PROFILE',
        temporality: 'PERSISTENT',
        expiresAt: undefined,
        importanceScore: 0.8,
        trustLevel: 'EXPLICIT',
        trustScore: 0.91,
      },
    })
    expect(patch?.memoryChanges?.[0]).toMatchObject({
      id: 'memory-1',
      name: '轻量主界面偏好',
      extra: {
        operationLabel: '更新',
        evidenceExcerpt: '主界面做轻',
        temporality: 'PERSISTENT',
        importanceScore: 0.8,
        evidenceCount: 3,
      },
    })
    expect(buildMemoryChangeImpact(patch?.memoryChanges?.[0] as any)).toBe(
      '会影响语气、方案取舍和界面建议；长期生效；优先级较高',
    )
  })

  it('没有引用该记忆时不产生消息补丁', () => {
    const message: Message = {
      id: 'assistant-1',
      role: 'assistant',
      content: '已完成',
      timestamp: Date.now(),
      sources: [{ type: 'memory', id: 'memory-other', name: '其他记忆' }],
    }

    expect(buildMemoryEntityMessagePatch(message, entity())).toBeNull()
  })

  it('构造忘记后的解释来源并保留可读上下文', () => {
    const source = buildDeletedMemorySource(
      {
        type: 'memory',
        id: 'memory-1',
        name: '旧偏好',
        extra: {
          evidenceExcerpt: '主界面做轻',
        },
      },
      entity({
        name: '轻量主界面偏好',
        description: '用户偏好更轻、更少入口的主界面。',
        typeLabel: '偏好',
      }),
    )

    expect(source).toMatchObject({
      type: 'memory',
      id: 'memory-1',
      name: '轻量主界面偏好',
      extra: {
        operation: 'DELETE',
        operationLabel: '忘记',
        entityTypeLabel: '偏好',
        evidenceExcerpt: '主界面做轻',
        description: '用户偏好更轻、更少入口的主界面。',
      },
    })
    expect(buildMemorySourceExplanation(source, 'change')).toBe(
      '因为你本轮提到「主界面做轻」，知微已忘记这条偏好。',
    )
    expect(buildMemoryChangeImpact(source)).toBe(
      '后续不再默认参考这条记忆；需要时可以重新告诉知微要记住什么',
    )
  })

  it('忘记记忆后从消息来源和沉淀标签中移除', () => {
    const message: Message = {
      id: 'assistant-1',
      role: 'assistant',
      content: '已完成',
      timestamp: Date.now(),
      sources: [
        { type: 'memory', id: 'memory-1', name: '旧偏好' },
        { type: 'knowledgeBase', id: 'kb-1', name: '产品资料' },
      ],
      memoryChanges: [
        { type: 'memory', id: 'memory-1', name: '旧偏好' },
        { type: 'memory', id: 'memory-2', name: '其他记忆' },
      ],
    }

    const patch = buildMemoryEntityRemovalMessagePatch(message, 'memory-1')

    expect(patch?.sources).toEqual([
      { type: 'knowledgeBase', id: 'kb-1', name: '产品资料' },
    ])
    expect(patch?.memoryChanges).toEqual([
      { type: 'memory', id: 'memory-2', name: '其他记忆' },
    ])
  })

  it('忘记最后一条本轮沉淀记忆后清理沉淀状态', () => {
    const message: Message = {
      id: 'assistant-1',
      role: 'assistant',
      content: '已完成',
      timestamp: Date.now(),
      memoryChanges: [
        { type: 'memory', id: 'memory-1', name: '旧偏好' },
      ],
      memoryChangeStatus: 'settled',
    }

    const patch = buildMemoryEntityRemovalMessagePatch(message, 'memory-1')

    expect(patch).toEqual({
      memoryChanges: undefined,
      memoryChangeStatus: undefined,
      memoryChangeReason: undefined,
    })
  })
})
