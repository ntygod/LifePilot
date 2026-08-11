import { describe, expect, it } from 'vitest'
import { buildEmptyPromptSuggestions } from './emptyPromptSuggestions'

describe('emptyPromptSuggestions 空态建议策略', () => {
  it('默认只露出三个自然任务入口', () => {
    const suggestions = buildEmptyPromptSuggestions()

    expect(suggestions.map(item => item.id)).toEqual([
      'sort-material',
      'plan-next-step',
      'polish-writing',
    ])
  })

  it('有本地资料库时优先浮现选资料入口并保持轻量数量', () => {
    const suggestions = buildEmptyPromptSuggestions({ hasKnowledgeBases: true })

    expect(suggestions[0]).toMatchObject({ id: 'mention-knowledge', label: '选资料提问', prompt: '@' })
    expect(suggestions).toHaveLength(3)
    expect(suggestions.map(item => item.id)).toContain('plan-next-step')
    expect(suggestions.map(item => item.id)).not.toContain('remember-preference')
    expect(suggestions.map(item => item.label)).not.toContain('引用资料')
  })

  it('有可用记忆但没有资料入口时才浮现记忆轻提示', () => {
    const suggestions = buildEmptyPromptSuggestions({
      hasMemories: true,
    })

    expect(suggestions[0]).toMatchObject({
      id: 'use-memory',
      label: '按我的习惯推进',
      prompt: '请结合你记得的我的偏好、背景和已有信息，帮我推进这件事：',
    })
    expect(suggestions).toHaveLength(3)
    expect(suggestions.map(item => item.id)).toContain('sort-material')
    expect(suggestions.map(item => item.id)).not.toContain('polish-writing')
    expect(suggestions.map(item => item.label)).not.toContain('用我的记忆')
  })

  it('同时有记忆和本地资料库时只露出一个上下文入口', () => {
    const suggestions = buildEmptyPromptSuggestions({
      hasMemories: true,
      hasKnowledgeBases: true,
    })

    expect(suggestions[0]).toMatchObject({ id: 'mention-knowledge', label: '选资料提问' })
    expect(suggestions).toHaveLength(3)
    expect(suggestions.map(item => item.id)).not.toContain('use-memory')
    expect(suggestions.map(item => item.id)).toContain('plan-next-step')
  })

  it('会优先使用当前会话关联资料库并压缩长名称', () => {
    const suggestions = buildEmptyPromptSuggestions({
      linkedKnowledgeBases: [
        {
          id: 'kb-product',
          name: '很长很长的产品资料库名称',
        },
      ],
    })

    expect(suggestions[0]).toMatchObject({
      id: 'ask-linked-knowledge',
      label: '基于「很长很长的产品…」',
      prompt: '请基于「很长很长的产品资料库名称」帮我回答：',
    })
    expect(suggestions).toHaveLength(3)
  })

  it('多个关联资料库时用数量表达上下文', () => {
    const suggestions = buildEmptyPromptSuggestions({
      linkedKnowledgeBases: [
        { id: 'kb-1', name: '产品资料' },
        { id: 'kb-2', name: '竞品资料' },
      ],
    })

    expect(suggestions[0]).toMatchObject({
      id: 'ask-linked-knowledge',
      label: '基于 2 个资料库',
      prompt: '请基于当前会话关联的 2 个资料库帮我回答：',
    })
  })
})
