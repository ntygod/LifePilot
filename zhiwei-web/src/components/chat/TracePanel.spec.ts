import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TracePanel from './TracePanel.vue'

function mountPanel(props: InstanceType<typeof TracePanel>['$props']) {
  return mount(TracePanel, {
    props,
    global: {
      stubs: {
        RouterLink: {
          props: ['to'],
          template: '<a><slot /></a>',
        },
      },
    },
  })
}

describe('TracePanel 任务步骤视图', () => {
  it('把工具轨迹转成任务步骤标题', () => {
    const wrapper = mountPanel({
      streaming: false,
      traceId: 'trace-1',
      reactSteps: [
        {
          type: 'TOOL_CALL',
          index: 0,
          toolId: 'web.search',
          toolName: '网页搜索',
          inputSummary: '搜索「知微」',
          latencyMs: 1200,
        },
        {
          type: 'OBSERVATION',
          index: 1,
          toolId: 'web.search',
          toolName: '网页搜索',
          success: true,
          outputSummary: '找到 3 条结果',
          tokensUsed: 0,
        },
      ],
    })

    expect(wrapper.text()).toContain('任务步骤')
    expect(wrapper.text()).toContain('已完成 1 个任务步骤。')
    expect(wrapper.text()).toContain('查找资料')
    expect(wrapper.text()).toContain('搜索「知微」')
    expect(wrapper.text()).toContain('找到 3 条结果')
    expect(wrapper.text()).toContain('查看完整任务记录')
    expect(wrapper.text()).not.toContain('网页搜索')
  })

  it('失败技能步骤展示主体和恢复参考摘要', () => {
    const wrapper = mountPanel({
      streaming: false,
      reactSteps: [
        {
          type: 'TOOL_CALL',
          index: 0,
          toolId: 'skill.load',
          toolName: '加载 Skill',
          inputSummary: '加载技能「research-assistant」',
          subjectLabel: '技能',
          subjectNames: ['research-assistant'],
          latencyMs: 12,
        },
        {
          type: 'OBSERVATION',
          index: 1,
          toolId: 'skill.load',
          toolName: '加载 Skill',
          success: false,
          outputSummary: '技能 research-assistant 不存在',
          outputDetail: '未找到 skill 文件',
          tokensUsed: 0,
        },
      ],
    })

    expect(wrapper.text()).toContain('1 个步骤需要处理，其余步骤可作为恢复参考。')
    expect(wrapper.text()).toContain('准备相关技能')
    expect(wrapper.text()).toContain('技能 research-assistant')
    expect(wrapper.text()).toContain('失败')
    expect(wrapper.text()).toContain('未找到 skill 文件')
    expect(wrapper.find('[aria-label="处理建议"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('确认技能 research-assistant 的名称和依赖是否可用')
    expect(wrapper.text()).toContain('重新加载技能后继续当前任务')
    expect(wrapper.text()).not.toContain('加载 Skill')
  })

  it('失败详情会截断并保留处理建议', () => {
    const longDetail = `${'AssertionError: expected true to be false\n'.repeat(20)}堆栈末尾`
    const wrapper = mountPanel({
      streaming: false,
      reactSteps: [
        {
          type: 'TOOL_CALL',
          index: 0,
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          inputSummary: '执行 `npm test`',
          latencyMs: 18,
        },
        {
          type: 'OBSERVATION',
          index: 1,
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          success: false,
          outputSummary: '测试失败',
          outputDetail: longDetail,
          tokensUsed: 0,
        },
      ],
    })

    expect(wrapper.text()).toContain('执行验证')
    expect(wrapper.text()).toContain('测试失败')
    expect(wrapper.text()).toContain('AssertionError: expected true to be false')
    expect(wrapper.text()).not.toContain('堆栈末尾')
    expect(wrapper.find('[aria-label="处理建议"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('查看命令输出并修正报错原因')
    expect(wrapper.text()).toContain('从失败命令后继续执行验证')
  })

  it('同一工具多次调用时按 callId 匹配各自观察结果', () => {
    const wrapper = mountPanel({
      streaming: false,
      reactSteps: [
        {
          type: 'TOOL_CALL',
          index: 0,
          toolId: 'web.search',
          callId: 'search-call-1',
          toolName: '联网搜索',
          inputSummary: '搜索「A」',
          latencyMs: 12,
        },
        {
          type: 'TOOL_CALL',
          index: 1,
          toolId: 'web.search',
          callId: 'search-call-2',
          toolName: '联网搜索',
          inputSummary: '搜索「B」',
          latencyMs: 13,
        },
        {
          type: 'OBSERVATION',
          index: 2,
          toolId: 'web.search',
          callId: 'search-call-2',
          toolName: '联网搜索',
          success: false,
          outputSummary: '外部访问超时',
          tokensUsed: 0,
        },
        {
          type: 'OBSERVATION',
          index: 3,
          toolId: 'web.search',
          callId: 'search-call-1',
          toolName: '联网搜索',
          success: true,
          outputSummary: '找到 A 的资料',
          tokensUsed: 0,
        },
      ],
    })

    const cards = wrapper.findAll('.trace-card')
    expect(wrapper.text()).toContain('1 个步骤需要处理，其余步骤可作为恢复参考。')
    expect(cards[0].text()).toContain('搜索「A」')
    expect(cards[0].text()).toContain('找到 A 的资料')
    expect(cards[0].text()).not.toContain('外部访问超时')
    expect(cards[1].text()).toContain('搜索「B」')
    expect(cards[1].text()).toContain('外部访问超时')
    expect(cards[1].text()).toContain('失败')
  })

  it('缺少 callId 时不会重复消费同一个观察结果', () => {
    const wrapper = mountPanel({
      streaming: false,
      reactSteps: [
        {
          type: 'TOOL_CALL',
          index: 0,
          toolId: 'web.search',
          toolName: '联网搜索',
          inputSummary: '搜索「A」',
          latencyMs: 12,
        },
        {
          type: 'TOOL_CALL',
          index: 1,
          toolId: 'web.search',
          toolName: '联网搜索',
          inputSummary: '搜索「B」',
          latencyMs: 13,
        },
        {
          type: 'OBSERVATION',
          index: 2,
          toolId: 'web.search',
          toolName: '联网搜索',
          success: true,
          outputSummary: '找到 A 的资料',
          tokensUsed: 0,
        },
        {
          type: 'OBSERVATION',
          index: 3,
          toolId: 'web.search',
          toolName: '联网搜索',
          success: true,
          outputSummary: '找到 B 的资料',
          tokensUsed: 0,
        },
      ],
    })

    const cards = wrapper.findAll('.trace-card')
    expect(wrapper.text()).toContain('已完成 2 个任务步骤。')
    expect(cards[0].text()).toContain('搜索「A」')
    expect(cards[0].text()).toContain('找到 A 的资料')
    expect(cards[0].text()).not.toContain('找到 B 的资料')
    expect(cards[1].text()).toContain('搜索「B」')
    expect(cards[1].text()).toContain('找到 B 的资料')
    expect(cards[1].text()).not.toContain('找到 A 的资料')
  })

  it('reasoningEvents 兜底路径缺少 callId 时也不会重复消费观察结果', () => {
    const wrapper = mountPanel({
      streaming: false,
      reasoningEvents: [
        {
          id: 'call-a',
          type: 'TOOL_CALL',
          title: '调用工具: 联网搜索',
          description: '搜索「A」',
          toolName: '联网搜索',
          createdAt: '2026-07-06T00:00:00.000Z',
          extra: { toolId: 'web.search' },
        },
        {
          id: 'call-b',
          type: 'TOOL_CALL',
          title: '调用工具: 联网搜索',
          description: '搜索「B」',
          toolName: '联网搜索',
          createdAt: '2026-07-06T00:00:01.000Z',
          extra: { toolId: 'web.search' },
        },
        {
          id: 'obs-a',
          type: 'OBSERVATION',
          title: '工具返回: 联网搜索',
          description: '找到 A 的资料',
          toolName: '联网搜索',
          createdAt: '2026-07-06T00:00:02.000Z',
        },
        {
          id: 'obs-b',
          type: 'OBSERVATION',
          title: '工具返回: 联网搜索',
          description: '找到 B 的资料',
          toolName: '联网搜索',
          createdAt: '2026-07-06T00:00:03.000Z',
        },
      ],
    })

    const cards = wrapper.findAll('.trace-card')
    expect(wrapper.text()).toContain('已完成 2 个任务步骤。')
    expect(cards[0].text()).toContain('搜索「A」')
    expect(cards[0].text()).toContain('找到 A 的资料')
    expect(cards[0].text()).not.toContain('找到 B 的资料')
    expect(cards[1].text()).toContain('搜索「B」')
    expect(cards[1].text()).toContain('找到 B 的资料')
    expect(cards[1].text()).not.toContain('找到 A 的资料')
  })

  it('流式运行中展示正在推进的任务步骤', () => {
    const wrapper = mountPanel({
      streaming: true,
      reactSteps: [
        {
          type: 'TOOL_CALL',
          index: 0,
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          inputSummary: '执行 `npm test`',
          latencyMs: 0,
        },
      ],
    })

    expect(wrapper.text()).toContain('正在推进 1 个任务步骤。')
    expect(wrapper.text()).toContain('执行验证')
    expect(wrapper.text()).toContain('执行中')
    expect(wrapper.text()).not.toContain('Shell 执行')
  })

  it('过滤工具搜索和能力核查这类内部编排步骤', () => {
    const wrapper = mountPanel({
      streaming: false,
      reactSteps: [
        {
          type: 'TOOL_CALL',
          index: 0,
          toolId: 'tool.search',
          toolName: '搜索工具',
          inputSummary: '查找可用工具',
          latencyMs: 12,
        },
        {
          type: 'OBSERVATION',
          index: 1,
          toolId: 'tool.search',
          toolName: '搜索工具',
          success: true,
          outputSummary: '找到 2 个工具',
          tokensUsed: 0,
        },
        {
          type: 'TOOL_CALL',
          index: 2,
          toolId: 'capability.assess',
          toolName: '调用核查',
          inputSummary: '核查能力路径',
          latencyMs: 8,
        },
        {
          type: 'TOOL_CALL',
          index: 3,
          toolId: 'experience.match',
          toolName: '经验匹配',
          inputSummary: '匹配历史经验',
          latencyMs: 8,
        },
        {
          type: 'TOOL_CALL',
          index: 4,
          toolId: 'context.assemble',
          toolName: '上下文装配',
          inputSummary: '准备对话上下文',
          latencyMs: 8,
        },
        {
          type: 'TOOL_CALL',
          index: 5,
          toolId: 'web.search',
          toolName: '联网搜索',
          inputSummary: '搜索知微资料',
          latencyMs: 18,
        },
        {
          type: 'OBSERVATION',
          index: 6,
          toolId: 'web.search',
          toolName: '联网搜索',
          success: true,
          outputSummary: '找到资料',
          tokensUsed: 0,
        },
      ],
    })

    expect(wrapper.text()).toContain('已完成 1 个任务步骤。')
    expect(wrapper.text()).toContain('查找资料')
    expect(wrapper.text()).toContain('搜索知微资料')
    expect(wrapper.text()).not.toContain('搜索工具')
    expect(wrapper.text()).not.toContain('调用核查')
    expect(wrapper.text()).not.toContain('查找可用工具')
    expect(wrapper.text()).not.toContain('经验匹配')
    expect(wrapper.text()).not.toContain('上下文装配')
    expect(wrapper.text()).not.toContain('匹配历史经验')
  })

  it('没有 reactSteps 时从 toolsSummary 兜底生成任务步骤', () => {
    const wrapper = mountPanel({
      streaming: false,
      toolSummaries: [
        {
          toolId: 'web.search',
          toolName: '联网搜索',
          executionKind: 'TOOL',
          status: 'SUCCEEDED',
          success: true,
          latencyMs: 120,
          inputSummary: '搜索知微产品定位',
          outputSummary: '找到 3 条资料',
        },
        {
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          status: 'FAILED',
          success: false,
          latencyMs: 40,
          inputSummary: '执行 `npm test`',
          outputSummary: '测试失败',
          outputDetail: 'AssertionError: expected true to be false',
          failureCategory: 'COMMAND',
        },
      ],
    })

    expect(wrapper.text()).toContain('1 个步骤需要处理，其余步骤可作为恢复参考。')
    expect(wrapper.text()).toContain('查找资料')
    expect(wrapper.text()).toContain('搜索知微产品定位')
    expect(wrapper.text()).toContain('找到 3 条资料')
    expect(wrapper.text()).toContain('执行验证')
    expect(wrapper.text()).toContain('测试失败')
    expect(wrapper.text()).toContain('查看命令输出并修正报错原因')
    expect(wrapper.text()).not.toContain('联网搜索')
    expect(wrapper.text()).not.toContain('Shell 执行')
  })

  it('把恢复上下文作为任务时间线里的第一步', () => {
    const wrapper = mountPanel({
      streaming: false,
      turnRecoveryContext: {
        action: 'RESUME',
        resumeInput: '继续刚才的测试修复',
        sourceTraceId: 'trace-before',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          recoveryActionMode: 'resume',
          recoveryActionDescription: '保留已完成步骤，修正命令或代码错误后继续验证。',
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          action: '执行命令',
          failureCategory: 'COMMAND',
          inputSummary: '执行 `npm test`',
          outputSummary: '测试失败',
          outputDetail: 'AssertionError: expected true to be false',
        },
        nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证'],
      },
      toolSummaries: [
        {
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          status: 'SUCCEEDED',
          success: true,
          latencyMs: 120,
          inputSummary: '重新执行 `npm test`',
          outputSummary: '测试通过',
        },
      ],
    })

    expect(wrapper.text()).toContain('已完成 2 个任务步骤。')
    expect(wrapper.text()).toContain('从断点继续')
    expect(wrapper.text()).toContain('执行命令 · Shell 执行')
    expect(wrapper.text()).toContain('继续刚才的测试修复')
    expect(wrapper.text()).toContain('测试失败')
    expect(wrapper.find('[aria-label="恢复方式"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('保留已完成步骤，修正命令或代码错误后继续验证。')
    expect(wrapper.text()).toContain('查看命令输出并修正报错原因')
    expect(wrapper.text()).toContain('执行验证')
    expect(wrapper.text()).toContain('测试通过')
  })

  it('恢复上下文缺少说明时按恢复动作和失败类型生成默认说明', () => {
    const wrapper = mountPanel({
      streaming: false,
      turnRecoveryContext: {
        action: 'RESTART',
        resumeInput: '重新开始刚才的资料处理',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          recoveryActionMode: 'restart',
          toolId: 'knowledge.search',
          toolName: '知识库检索',
          executionKind: 'TOOL',
          action: '处理资料',
          failureCategory: 'KNOWLEDGE',
          outputSummary: '索引不可用',
        },
        nextActions: ['重新检索资料', '重建资料处理步骤'],
      },
    })

    expect(wrapper.text()).toContain('重新开始并修正')
    expect(wrapper.text()).toContain('索引不可用')
    expect(wrapper.text()).toContain('恢复方式')
    expect(wrapper.text()).toContain('保留已处理资料线索，重新检索或重建处理步骤。')
  })

  it('恢复上下文带有继续策略时优先展示策略说明', () => {
    const wrapper = mountPanel({
      streaming: false,
      turnRecoveryContext: {
        action: 'RESUME',
        resumeInput: '继续验证失败命令',
        resumeStrategy: '从失败断点继续，保留已完成步骤和失败输出，不要重复成功部分。',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          action: '执行命令',
          failureCategory: 'COMMAND',
          outputSummary: '测试失败',
        },
        nextActions: ['查看命令输出并修正报错原因'],
      },
    })

    expect(wrapper.text()).toContain('从断点继续')
    expect(wrapper.text()).toContain('恢复方式')
    expect(wrapper.text()).toContain('从失败断点继续，保留已完成步骤和失败输出，不要重复成功部分。')
  })

  it('把资料、流程和连接器工具显示成自然任务步骤', () => {
    const wrapper = mountPanel({
      streaming: false,
      toolSummaries: [
        {
          toolId: 'knowledge.search',
          toolName: '知识库检索',
          executionKind: 'TOOL',
          status: 'SUCCEEDED',
          success: true,
          latencyMs: 18,
          inputSummary: '检索本地资料',
          outputSummary: '命中 2 条资料',
        },
        {
          toolId: 'workflow.run',
          toolName: '执行工作流',
          executionKind: 'TOOL',
          status: 'SUCCEEDED',
          success: true,
          latencyMs: 20,
          inputSummary: '运行资料整理流程',
          outputSummary: '流程已完成',
        },
        {
          toolId: 'mcp.call',
          toolName: 'MCP 调用',
          executionKind: 'TOOL',
          status: 'FAILED',
          success: false,
          latencyMs: 12,
          inputSummary: '调用本地连接器',
          outputSummary: '连接器不可用',
          failureCategory: 'INTEGRATION',
        },
      ],
    })

    expect(wrapper.text()).toContain('整理资料')
    expect(wrapper.text()).toContain('检索本地资料')
    expect(wrapper.text()).toContain('推进流程')
    expect(wrapper.text()).toContain('运行资料整理流程')
    expect(wrapper.text()).toContain('连接工具服务')
    expect(wrapper.text()).toContain('检查连接器服务、授权或参数')
    expect(wrapper.text()).not.toContain('知识库检索')
    expect(wrapper.text()).not.toContain('MCP 调用')
  })
})
