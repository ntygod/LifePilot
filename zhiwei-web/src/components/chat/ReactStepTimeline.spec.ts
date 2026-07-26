import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ReactStepTimeline from './ReactStepTimeline.vue'
import type { ReactStepDto } from '@/types'

function mountTimeline(props: InstanceType<typeof ReactStepTimeline>['$props']) {
  return mount(ReactStepTimeline, {
    props,
    global: {
      stubs: {
        RouterLink: true,
      },
    },
  })
}

describe('ReactStepTimeline 执行轨迹', () => {
  it('流式阶段不展示内部意图和能力判断步骤', () => {
    const steps: ReactStepDto[] = [
      {
        type: 'TOOL_CALL',
        index: 0,
        toolId: 'intent.match',
        toolName: '意图识别',
        inputSummary: '判断用户意图',
        latencyMs: 0,
      },
      {
        type: 'TOOL_CALL',
        index: 1,
        toolId: 'capability.assess',
        toolName: '能力核查',
        inputSummary: '检查可用工具',
        latencyMs: 0,
      },
      {
        type: 'TOOL_CALL',
        index: 2,
        toolId: '',
        toolName: '能力注册',
        inputSummary: '注册工具能力',
        latencyMs: 0,
      },
      {
        type: 'TOOL_CALL',
        index: 3,
        toolId: '',
        toolName: 'Capability Check',
        inputSummary: 'check capabilities',
        latencyMs: 0,
      },
      {
        type: 'PROGRESS',
        index: 4,
        content: '决策信号生成中',
      },
    ]

    const wrapper = mountTimeline({ steps, streaming: true })

    expect(wrapper.text()).toBe('')
  })

  it('流式阶段不展示缺少工具标识的匿名步骤', () => {
    const steps: ReactStepDto[] = [
      {
        type: 'TOOL_CALL',
        index: 0,
        toolId: '',
        toolName: '',
        inputSummary: '后台准备上下文',
        latencyMs: 0,
      },
    ]

    const wrapper = mountTimeline({ steps, streaming: true })

    expect(wrapper.text()).toBe('')
  })

  it('完成后只把可见工具计入折叠摘要', () => {
    const steps: ReactStepDto[] = [
      {
        type: 'TOOL_CALL',
        index: 0,
        toolId: 'tool.search',
        toolName: '工具搜索',
        inputSummary: '搜索工具',
        latencyMs: 0,
      },
      {
        type: 'TOOL_CALL',
        index: 1,
        toolId: 'shell.exec',
        toolName: 'Shell 执行',
        inputSummary: '运行测试',
        latencyMs: 1200,
      },
    ]

    const wrapper = mountTimeline({ steps, streaming: false })

    expect(wrapper.text()).toContain('Shell 执行')
    expect(wrapper.text()).not.toContain('工具搜索')
  })
})
