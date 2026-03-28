import { describe, expect, it } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import SessionSidebar from './SessionSidebar.vue'

function mountSidebar() {
  return shallowMount(SessionSidebar, {
    props: {
      session: {
        id: 'session-1',
        title: '小说创作',
        createdAt: '2026-03-28T01:00:00Z',
        updatedAt: '2026-03-28T01:05:00Z',
        pinned: false,
        archived: false,
        messageCount: 12,
        totalTokens: 0,
        knowledgeBaseIds: ['kb-2'],
        datastoreIds: [],
        compactionStatus: {
          enabled: true,
          activeTranscriptTokens: 48000,
          triggerThresholdTokens: 98304,
          triggerThresholdPercent: 75,
          remainingTokens: 50304,
          activeTurnCount: 4,
          minTurnCount: 6,
          keepRecentTurns: 2,
          thresholdReached: false,
          minTurnsReached: false,
          readyToCompact: false,
          compactionCount: 1,
          lastCompactedAt: '2026-03-28T00:50:00Z',
        },
      },
      knowledgeBases: [
        {
          id: 'kb-1',
          name: '通用资料库',
          description: '通用知识库',
          embeddingModel: 'text-embedding-3-large',
          documentCount: 3,
          totalChunks: 12,
          createdAt: '2026-03-28T00:00:00Z',
          updatedAt: '2026-03-28T00:00:00Z',
          datastoreIds: [],
        },
        {
          id: 'kb-2',
          name: '小说设定集',
          description: '小说设定知识库',
          embeddingModel: 'text-embedding-3-large',
          documentCount: 8,
          totalChunks: 35,
          createdAt: '2026-03-28T00:00:00Z',
          updatedAt: '2026-03-28T00:00:00Z',
          datastoreIds: [],
        },
      ],
      messageCount: 12,
      searchQuery: '',
      statusText: '就绪',
      contextCount: 1,
      matchedMessageCount: 12,
    },
    global: {
      stubs: {
        InspectorRail: {
          template: '<div><slot name="eyebrow" /><slot /></div>',
        },
        RouterLink: {
          template: '<a><slot /></a>',
        },
        Input: {
          props: ['modelValue'],
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" @blur="$emit(\'blur\')" />',
        },
        Label: { template: '<label><slot /></label>' },
      },
    },
  })
}

describe('SessionSidebar', () => {
  it('展示上下文压缩进度与当前绑定知识库', () => {
    const wrapper = mountSidebar()

    expect(wrapper.text()).toContain('上下文压缩')
    expect(wrapper.text()).toContain('49%')
    expect(wrapper.text()).toContain('48,000')
    expect(wrapper.text()).toContain('98,304')
    expect(wrapper.text()).toContain('小说设定集')
    expect(wrapper.text()).not.toContain('通用资料库')
  })

  it('标题失焦时会提交更新事件', async () => {
    const wrapper = mountSidebar()
    const input = wrapper.findAll('input')[1]

    await input.setValue('新的会话标题')
    await input.trigger('blur')

    expect(wrapper.emitted('updateTitle')).toContainEqual(['新的会话标题'])
  })
})
