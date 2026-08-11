import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ReasoningTimeline from './ReasoningTimeline.vue'
import type { ReasoningEvent } from '@/types'

function mountTimeline(props: InstanceType<typeof ReasoningTimeline>['$props']) {
  return mount(ReasoningTimeline, {
    props,
    global: {
      stubs: {
        RouterLink: true,
      },
    },
  })
}

describe('ReasoningTimeline 推理轨迹', () => {
  it('流式阶段不展示内部意图和能力判断事件', () => {
    const events: ReasoningEvent[] = [
      {
        id: 'tool-search',
        type: 'TOOL_CALL',
        title: '工具搜索',
        toolName: '工具搜索',
        createdAt: '2026-07-07T10:00:00Z',
        extra: { toolId: 'tool.search' },
      },
      {
        id: 'capability-check',
        type: 'TOOL_CALL',
        title: '能力核查',
        toolName: '能力核查',
        createdAt: '2026-07-07T10:00:01Z',
        extra: { toolId: 'capability.assess' },
      },
      {
        id: 'tool-search-humanized',
        type: 'TOOL_CALL',
        title: 'Tool Search',
        toolName: 'Tool Search',
        createdAt: '2026-07-07T10:00:01Z',
        extra: {},
      },
      {
        id: 'capability-registry-humanized',
        type: 'TOOL_CALL',
        title: '能力注册',
        toolName: '能力注册',
        createdAt: '2026-07-07T10:00:01Z',
        extra: {},
      },
      {
        id: 'progress',
        type: 'PROGRESS',
        title: '进度',
        description: '能力注册中',
        createdAt: '2026-07-07T10:00:02Z',
      },
    ]

    const wrapper = mountTimeline({ events, streaming: true })

    expect(wrapper.text()).toBe('')
  })

  it('流式阶段不展示缺少工具标识的匿名事件', () => {
    const events: ReasoningEvent[] = [
      {
        id: 'anonymous-tool',
        type: 'TOOL_CALL',
        title: '',
        toolName: '',
        createdAt: '2026-07-07T10:00:00Z',
        extra: {},
      },
    ]

    const wrapper = mountTimeline({ events, streaming: true })

    expect(wrapper.text()).toBe('')
  })

  it('完成后只把可见工具计入折叠摘要', () => {
    const events: ReasoningEvent[] = [
      {
        id: 'intent',
        type: 'TOOL_CALL',
        title: '意图识别',
        toolName: '意图识别',
        createdAt: '2026-07-07T10:00:00Z',
        extra: { toolId: 'intent.match' },
      },
      {
        id: 'web',
        type: 'TOOL_CALL',
        title: '网页搜索',
        toolName: '网页搜索',
        createdAt: '2026-07-07T10:00:01Z',
        extra: { toolId: 'web.search' },
      },
    ]

    const wrapper = mountTimeline({ events, streaming: false })

    expect(wrapper.text()).toContain('网页搜索')
    expect(wrapper.text()).not.toContain('意图识别')
  })
})
