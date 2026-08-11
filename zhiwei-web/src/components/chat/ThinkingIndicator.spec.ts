import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ThinkingIndicator from './ThinkingIndicator.vue'

describe('ThinkingIndicator 流式任务进展', () => {
  it('流式工具调用显示自然进展，不直接暴露工具名', async () => {
    const wrapper = mount(ThinkingIndicator, {
      props: {
        streaming: true,
        reactSteps: [
          {
            type: 'TOOL_CALL',
            index: 0,
            toolId: 'shell.exec',
            toolName: 'Shell 执行',
            inputSummary: '执行 `npm test`',
            latencyMs: 12,
          },
        ],
      },
    })

    expect(wrapper.text()).toContain('正在执行验证')
    expect(wrapper.text()).not.toContain('Shell 执行')

    await wrapper.find('button').trigger('click')

    expect(wrapper.emitted('show-trace')).toHaveLength(1)
  })

  it('reasoning 事件里的网页工具显示查资料进展', () => {
    const wrapper = mount(ThinkingIndicator, {
      props: {
        streaming: true,
        reasoningEvents: [
          {
            id: 'event-1',
            type: 'TOOL_CALL',
            title: '网页搜索',
            toolName: '网页搜索',
            createdAt: '2026-07-04T10:00:00Z',
            extra: {
              toolId: 'web.search',
            },
          },
        ],
      },
    })

    expect(wrapper.text()).toContain('正在查资料')
    expect(wrapper.text()).not.toContain('网页搜索')
  })

  it('资料和连接器工具显示具体自然进展', () => {
    const knowledge = mount(ThinkingIndicator, {
      props: {
        streaming: true,
        reactSteps: [
          {
            type: 'TOOL_CALL',
            index: 0,
            toolId: 'knowledge.search',
            toolName: '知识库检索',
            inputSummary: '检索本地资料',
            latencyMs: 12,
          },
        ],
      },
    })
    const integration = mount(ThinkingIndicator, {
      props: {
        streaming: true,
        reasoningEvents: [
          {
            id: 'event-mcp',
            type: 'TOOL_CALL',
            title: 'MCP 调用',
            toolName: 'MCP 调用',
            createdAt: '2026-07-04T10:00:00Z',
            extra: {
              toolId: 'mcp.call',
            },
          },
        ],
      },
    })

    expect(knowledge.text()).toContain('正在整理资料')
    expect(knowledge.text()).not.toContain('知识库检索')
    expect(integration.text()).toContain('正在连接工具服务')
    expect(integration.text()).not.toContain('MCP 调用')
  })

  it('模型处理显示回答进展而不是能力名', () => {
    const wrapper = mount(ThinkingIndicator, {
      props: {
        streaming: true,
        reactSteps: [
          {
            type: 'TOOL_CALL',
            index: 0,
            toolId: 'llm.generate',
            toolName: '模型生成',
            inputSummary: '分析用户输入',
            latencyMs: 12,
          },
        ],
      },
    })

    expect(wrapper.text()).toContain('正在整理回答')
    expect(wrapper.text()).not.toContain('模型能力')
    expect(wrapper.text()).not.toContain('模型生成')
  })

  it('能力类准备阶段不暴露检查能力文案', () => {
    const wrapper = mount(ThinkingIndicator, {
      props: {
        streaming: true,
        reactSteps: [
          {
            type: 'TOOL_CALL',
            index: 0,
            toolId: 'registry.prepare',
            toolName: '能力注册',
            inputSummary: '准备工具注册',
            latencyMs: 12,
          },
        ],
      },
    })

    expect(wrapper.text()).toContain('正在准备执行')
    expect(wrapper.text()).not.toContain('正在检查能力')
    expect(wrapper.text()).not.toContain('能力注册')
  })

  it('流式阶段不展示内部意图和判断状态', () => {
    const wrapper = mount(ThinkingIndicator, {
      props: {
        streaming: true,
        reasoningEvents: [
          {
            id: 'event-tool-search',
            type: 'TOOL_CALL',
            title: '搜索工具',
            toolName: '搜索工具',
            createdAt: '2026-07-04T10:00:00Z',
            extra: {
              toolId: 'tool.search',
            },
          },
          {
            id: 'event-capability-check',
            type: 'TOOL_CALL',
            title: '调用核查',
            toolName: '调用核查',
            createdAt: '2026-07-04T10:00:00Z',
            extra: {
              toolId: 'capability.assess',
            },
          },
          {
            id: 'event-experience-match',
            type: 'TOOL_CALL',
            title: '经验匹配',
            toolName: '经验匹配',
            createdAt: '2026-07-04T10:00:00Z',
            extra: {
              toolId: 'experience.match',
            },
          },
          {
            id: 'event-1',
            type: 'PROGRESS',
            title: '进度',
            description: '决策信号生成中',
            createdAt: '2026-07-04T10:00:00Z',
          },
          {
            id: 'event-context',
            type: 'TOOL_CALL',
            title: '上下文装配',
            toolName: '上下文装配',
            createdAt: '2026-07-04T10:00:01Z',
          },
        ],
        reactSteps: [
          {
            type: 'TOOL_CALL',
            index: 0,
            toolId: 'intent.match',
            toolName: '意图识别',
            inputSummary: '判断用户意图',
            latencyMs: 0,
          },
          {
            type: 'PROGRESS',
            index: 1,
            content: '上下文装配中',
          },
        ],
      },
    })

    expect(wrapper.find('button').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('知微正在判断')
    expect(wrapper.text()).not.toContain('意图识别')
    expect(wrapper.text()).not.toContain('经验匹配')
    expect(wrapper.text()).not.toContain('决策信号')
    expect(wrapper.text()).not.toContain('上下文装配')
    expect(wrapper.text()).not.toContain('调用核查')
    expect(wrapper.text()).not.toContain('搜索工具')
  })

  it('完成后只保留轻量任务步骤入口', () => {
    const wrapper = mount(ThinkingIndicator, {
      props: {
        streaming: false,
        reactSteps: [
          {
            type: 'TOOL_CALL',
            index: 0,
            toolId: 'web.search',
            toolName: '网页搜索',
            inputSummary: '搜索「知微」',
            latencyMs: 1200,
          },
        ],
      },
    })

    expect(wrapper.text()).toContain('查看任务步骤')
    expect(wrapper.find('button').attributes('aria-label')).toBe('查看任务步骤')
    expect(wrapper.text()).toContain('1 秒')
    expect(wrapper.text()).not.toContain('已完成思考')
  })
})
