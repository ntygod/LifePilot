import { describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import ToolCallCard from './ToolCallCard.vue'
import type { ToolCallSummary } from '@/types'

function buildTool(overrides: Partial<ToolCallSummary>): ToolCallSummary {
  return {
    toolId: 'shell.exec',
    toolName: 'Shell 执行',
    success: true,
    latencyMs: 120,
    ...overrides,
  }
}

async function openDetails(wrapper: VueWrapper) {
  const trigger = wrapper.find('[data-slot="accordion-trigger"]')
  expect(trigger.exists()).toBe(true)
  expect(trigger.attributes('data-state')).toBe('closed')

  await trigger.trigger('click')

  expect(trigger.attributes('data-state')).toBe('open')
}

describe('ToolCallCard 任务步骤卡片', () => {
  it('展示技能步骤的执行主体和状态', () => {
    const wrapper = mount(ToolCallCard, {
      props: {
        tool: buildTool({
          toolId: 'skill.load',
          toolName: '加载 Skill',
          executionKind: 'SKILL',
          status: 'SUCCEEDED',
          action: '加载技能',
          subjectLabel: '技能',
          subjectNames: ['research-assistant'],
          outputSummary: '已加载 1 个技能',
        }),
      },
    })

    expect(wrapper.text()).toContain('技能')
    expect(wrapper.text()).toContain('加载技能')
    expect(wrapper.text()).toContain('research-assistant')
    expect(wrapper.text()).toContain('已完成')
  })

  it('失败步骤默认只露出摘要和恢复入口，详情按需展开', async () => {
    const wrapper = mount(ToolCallCard, {
      props: {
        tool: buildTool({
          success: false,
          status: 'FAILED',
          failureCategory: 'COMMAND',
          action: '执行命令',
          inputSummary: '执行 `npm test`',
          outputSummary: '测试失败',
          recoveryHint: '命令或代码没有完成，可以修正错误后继续执行。',
        }),
      },
    })

    const trigger = wrapper.find('[data-slot="accordion-trigger"]')
    expect(trigger.exists()).toBe(true)
    expect(trigger.attributes('data-state')).toBe('closed')
    expect(wrapper.text()).toContain('测试失败')
    expect(wrapper.text()).toContain('修正后继续')

    await trigger.trigger('click')

    expect(trigger.attributes('data-state')).toBe('open')
  })

  it('失败步骤展示恢复动作并向上抛出选择', async () => {
    const action = {
      id: 'resume',
      label: '修正后继续',
      mode: 'resume' as const,
      category: 'COMMAND' as const,
    }
    const wrapper = mount(ToolCallCard, {
      props: {
        tool: buildTool({
          success: false,
          status: 'FAILED',
          callId: 'call-shell-1',
          interrupted: true,
          failureCategory: 'COMMAND',
          action: '执行命令',
          outputSummary: '测试失败',
          outputDetail: 'AssertionError: expected true to be false',
          workingDirectory: 'D:\\WorkSpace\\Project\\News',
          artifactRefs: [
            {
              artifactId: 'artifact-report',
              kind: 'FILE',
              fileName: '调研结果.md',
              mimeType: 'text/markdown',
              size: 2048,
              downloadUrl: '/api/artifacts/artifact-report/download',
            },
          ],
          recoveryHint: '命令或代码没有完成，可以修正错误后继续执行。',
          recoveryActions: [
            action,
            {
              id: 'restart',
              label: '重新开始',
              mode: 'restart',
              category: 'COMMAND',
            },
          ],
        }),
      },
    })

    await openDetails(wrapper)

    expect(wrapper.text()).toContain('卡点：命令执行')
    expect(wrapper.text()).toContain('处理建议')
    expect(wrapper.find('[aria-label="续接计划"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="恢复上下文"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('查看命令输出并修正报错原因')
    expect(wrapper.text()).toContain('从失败命令后继续执行验证')
    expect(wrapper.text()).toContain('继续时保留')
    expect(wrapper.text()).toContain('带上失败输出')
    expect(wrapper.text()).toContain('工作目录 News')
    expect(wrapper.text()).toContain('保留产物 调研结果.md')
    expect(wrapper.text()).toContain('从中断位置接上')
    expect(wrapper.text()).toContain('修正后继续')
    expect(wrapper.find('button[aria-label="修正后继续：Shell 执行，测试失败"]').exists()).toBe(true)

    await wrapper.find('button[title^="修正后继续"]').trigger('click')

    expect(wrapper.emitted('recover')?.[0]).toEqual([
      expect.objectContaining({
        ...action,
        toolId: 'shell.exec',
        callId: 'call-shell-1',
        toolName: 'Shell 执行',
        action: '执行命令',
        interrupted: true,
        outputSummary: '测试失败',
        outputDetail: 'AssertionError: expected true to be false',
        workingDirectory: 'D:\\WorkSpace\\Project\\News',
        artifactRefs: [
          expect.objectContaining({
            artifactId: 'artifact-report',
            fileName: '调研结果.md',
          }),
        ],
        recoveryHint: '命令或代码没有完成，可以修正错误后继续执行。',
      }),
    ])
  })

  it('旧工具摘要没有恢复动作时自动生成可续接动作', async () => {
    const wrapper = mount(ToolCallCard, {
      props: {
        tool: buildTool({
          success: false,
          status: 'FAILED',
          failureCategory: 'COMMAND',
          action: '执行命令',
          inputSummary: '执行 `npm test`',
          outputSummary: '测试失败',
          outputDetail: 'AssertionError: expected true to be false',
          workingDirectory: 'D:\\WorkSpace\\Project\\News',
        }),
      },
    })

    await openDetails(wrapper)

    expect(wrapper.text()).toContain('命令或代码没有完成，可以修正错误后继续执行。')
    expect(wrapper.text()).toContain('修正后继续')
    expect(wrapper.text()).toContain('重新开始')

    await wrapper.find('button[title^="修正后继续"]').trigger('click')

    expect(wrapper.emitted('recover')?.[0]).toEqual([
      expect.objectContaining({
        id: 'resume',
        label: '修正后继续',
        mode: 'resume',
        toolId: 'shell.exec',
        toolName: 'Shell 执行',
        action: '执行命令',
        category: 'COMMAND',
        inputSummary: '执行 `npm test`',
        outputSummary: '测试失败',
        outputDetail: 'AssertionError: expected true to be false',
        workingDirectory: 'D:\\WorkSpace\\Project\\News',
        recoveryHint: '命令或代码没有完成，可以修正错误后继续执行。',
        nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证'],
      }),
    ])
  })

  it('旧工具摘要遇到未注册工具时生成能力缺口恢复动作', async () => {
    const wrapper = mount(ToolCallCard, {
      props: {
        tool: buildTool({
          toolId: 'web.search',
          toolName: '联网搜索',
          success: false,
          status: 'FAILED',
          inputSummary: '搜索资料',
          outputSummary: '工具未注册: web.search',
          outputDetail: '工具未注册: web.search',
        }),
      },
    })

    expect(wrapper.text()).toContain('能力')

    await openDetails(wrapper)

    expect(wrapper.text()).toContain('卡点：能力缺口')
    expect(wrapper.text()).toContain('依赖的工具或技能当前不可用，可以在能力中心修复连接或调整 Skill 元数据后继续。')
    expect(wrapper.text()).toContain('补齐缺失能力：web.search')
    expect(wrapper.text()).toContain('到能力中心检查 MCP 工具提供方或本地工具连接')
    expect(wrapper.text()).toContain('修复能力后继续')

    await wrapper.find('button[title^="修复能力后继续"]').trigger('click')

    expect(wrapper.emitted('recover')?.[0]).toEqual([
      expect.objectContaining({
        id: 'resume',
        label: '修复能力后继续',
        mode: 'resume',
        toolId: 'web.search',
        toolName: '联网搜索',
        category: 'CAPABILITY',
        inputSummary: '搜索资料',
        outputSummary: '工具未注册: web.search',
        recoveryHint: '依赖的工具或技能当前不可用，可以在能力中心修复连接或调整 Skill 元数据后继续。',
        nextActions: ['补齐缺失能力：web.search', '到能力中心检查 MCP 工具提供方或本地工具连接', '修复后从失败步骤继续'],
      }),
    ])
  })

  it('恢复动作和工具步骤的产物引用会合并后续接', async () => {
    const wrapper = mount(ToolCallCard, {
      props: {
        tool: buildTool({
          success: false,
          status: 'FAILED',
          failureCategory: 'COMMAND',
          outputSummary: '生成报告后测试失败',
          artifactRefs: [
            {
              artifactId: 'artifact-report',
              kind: 'FILE',
              fileName: '调研结果.md',
              mimeType: 'text/markdown',
              size: 2048,
              downloadUrl: '/api/artifacts/artifact-report/download',
            },
          ],
          recoveryActions: [
            {
              id: 'resume',
              label: '修正后继续',
              mode: 'resume',
              category: 'COMMAND',
              artifactRefs: [
                {
                  artifactId: 'artifact-screenshot',
                  kind: 'IMAGE',
                  fileName: '失败截图.png',
                  mimeType: 'image/png',
                  size: 1024,
                  downloadUrl: '/api/artifacts/artifact-screenshot/download',
                },
              ],
            },
          ],
        }),
      },
    })

    await wrapper.find('button[title^="修正后继续"]').trigger('click')

    expect(wrapper.emitted('recover')?.[0]?.[0]).toMatchObject({
      artifactRefs: [
        {
          artifactId: 'artifact-screenshot',
          fileName: '失败截图.png',
        },
        {
          artifactId: 'artifact-report',
          fileName: '调研结果.md',
        },
      ],
    })
  })

  it('技能失败步骤展示技能化恢复动作', async () => {
    const wrapper = mount(ToolCallCard, {
      props: {
        tool: buildTool({
          toolId: 'skill.load',
          toolName: '加载 Skill',
          executionKind: 'SKILL',
          status: 'FAILED',
          success: false,
          action: '加载技能',
          subjectLabel: '技能',
          subjectNames: ['research-assistant'],
          failureCategory: 'SKILL',
          outputSummary: '技能 research-assistant 不存在',
          recoveryHint: '技能加载没有完成，可以检查技能名称或依赖后继续。',
          recoveryActions: [
            {
              id: 'resume',
              label: '检查技能后继续',
              mode: 'resume',
              category: 'SKILL',
            },
          ],
        }),
      },
    })

    await openDetails(wrapper)

    expect(wrapper.text()).toContain('卡点：技能步骤')
    expect(wrapper.text()).toContain('确认技能 research-assistant 的名称和依赖是否可用')
    expect(wrapper.text()).toContain('重新加载技能后继续当前任务')
    expect(wrapper.text()).toContain('保留技能 research-assistant')
    expect(wrapper.text()).toContain('带上失败输出')
    expect(wrapper.text()).toContain('检查技能后继续')

    await wrapper.find('button[title^="检查技能后继续"]').trigger('click')

    expect(wrapper.emitted('recover')?.[0]).toEqual([
      expect.objectContaining({
        label: '检查技能后继续',
        toolId: 'skill.load',
        executionKind: 'SKILL',
        action: '加载技能',
        category: 'SKILL',
        subjectLabel: '技能',
        subjectNames: ['research-assistant'],
        outputSummary: '技能 research-assistant 不存在',
      }),
    ])
  })

  it('技能执行失败不会误显示成技能加载卡点', async () => {
    const wrapper = mount(ToolCallCard, {
      props: {
        tool: buildTool({
          toolId: 'skill.run',
          toolName: '执行 Skill',
          executionKind: 'SKILL',
          status: 'FAILED',
          success: false,
          action: '执行技能',
          subjectLabel: '技能',
          subjectNames: ['research-assistant'],
          failureCategory: 'SKILL',
          outputSummary: '资料源不可用',
          recoveryHint: '技能执行没有完成，可以检查输入、依赖或技能步骤后继续。',
        }),
      },
    })

    await openDetails(wrapper)

    expect(wrapper.text()).toContain('卡点：技能步骤')
    expect(wrapper.text()).not.toContain('卡点：技能加载')
    expect(wrapper.text()).toContain('检查技能 research-assistant 的输入、依赖和执行步骤')
    expect(wrapper.text()).toContain('保留当前进度并从失败技能步骤继续')
  })

  it('浏览器失败步骤也会透出恢复动作', async () => {
    const wrapper = mount(ToolCallCard, {
      props: {
        tool: buildTool({
          toolId: 'browser.click',
          toolName: '浏览器点击',
          status: 'FAILED',
          success: false,
          failureCategory: 'BROWSER',
          inputSummary: '点击 #submit',
          outputSummary: '选择器未匹配到任何元素',
          recoveryActions: [
            {
              id: 'resume',
              label: '检查页面后继续',
              mode: 'resume',
              category: 'BROWSER',
            },
          ],
        }),
      },
    })

    await wrapper.find('button[title^="检查页面后继续"]').trigger('click')

    expect(wrapper.emitted('recover')?.[0]).toEqual([
      expect.objectContaining({
        toolId: 'browser.click',
        toolName: '浏览器点击',
        category: 'BROWSER',
        inputSummary: '点击 #submit',
        outputSummary: '选择器未匹配到任何元素',
      }),
    ])
  })

  it('浏览器失败旧摘要没有恢复动作时也能生成续接动作', async () => {
    const wrapper = mount(ToolCallCard, {
      props: {
        tool: buildTool({
          toolId: 'browser.click',
          toolName: '浏览器点击',
          status: 'FAILED',
          success: false,
          inputSummary: '点击 #submit',
          outputSummary: '选择器未匹配到任何元素',
        }),
      },
    })

    expect(wrapper.text()).toContain('检查页面后继续')

    await wrapper.find('button[title^="检查页面后继续"]').trigger('click')

    expect(wrapper.emitted('recover')?.[0]).toEqual([
      expect.objectContaining({
        id: 'resume',
        label: '检查页面后继续',
        mode: 'resume',
        toolId: 'browser.click',
        toolName: '浏览器点击',
        category: 'BROWSER',
        inputSummary: '点击 #submit',
        outputSummary: '选择器未匹配到任何元素',
        recoveryHint: '浏览器操作没有完成，可以检查页面状态、登录或元素选择后继续。',
        nextActions: ['检查浏览器页面状态、登录或元素选择', '保留当前上下文并从失败页面操作继续'],
      }),
    ])
  })

  it('资料处理失败会显示资料化类型和恢复动作', async () => {
    const wrapper = mount(ToolCallCard, {
      props: {
        tool: buildTool({
          toolId: 'knowledge.search',
          toolName: '知识库检索',
          status: 'FAILED',
          success: false,
          inputSummary: '检索本地资料',
          outputSummary: '索引暂不可用',
        }),
      },
    })

    expect(wrapper.text()).toContain('资料')

    await openDetails(wrapper)

    expect(wrapper.text()).toContain('卡点：资料处理')
    expect(wrapper.text()).toContain('资料处理没有完成，可以检查资料来源、索引或解析结果后继续。')
    expect(wrapper.text()).toContain('检查资料来源、索引或解析结果')
    expect(wrapper.text()).toContain('调整资料后继续')

    await wrapper.find('button[title^="调整资料后继续"]').trigger('click')

    expect(wrapper.emitted('recover')?.[0]).toEqual([
      expect.objectContaining({
        id: 'resume',
        label: '调整资料后继续',
        mode: 'resume',
        toolId: 'knowledge.search',
        toolName: '知识库检索',
        category: 'KNOWLEDGE',
        inputSummary: '检索本地资料',
        outputSummary: '索引暂不可用',
        nextActions: ['检查资料来源、索引或解析结果', '保留已完成资料处理并从失败处继续'],
      }),
    ])
  })

  it('工作流和连接器工具会显示更具体的能力类型', () => {
    const workflow = mount(ToolCallCard, {
      props: {
        tool: buildTool({
          toolId: 'workflow.run',
          toolName: '执行工作流',
          status: 'SUCCEEDED',
          success: true,
          outputSummary: '流程已完成',
        }),
      },
    })
    const integration = mount(ToolCallCard, {
      props: {
        tool: buildTool({
          toolId: 'mcp.call',
          toolName: 'MCP 调用',
          status: 'SUCCEEDED',
          success: true,
          outputSummary: '连接器已返回结果',
        }),
      },
    })

    expect(workflow.text()).toContain('流程')
    expect(integration.text()).toContain('连接器')
  })

  it('可隐藏恢复按钮以避免和消息级恢复入口重复', async () => {
    const wrapper = mount(ToolCallCard, {
      props: {
        showRecoveryActions: false,
        tool: buildTool({
          success: false,
          status: 'FAILED',
          failureCategory: 'COMMAND',
          outputSummary: '测试失败',
          recoveryActions: [
            {
              id: 'resume',
              label: '修正后继续',
              mode: 'resume',
              category: 'COMMAND',
            },
          ],
        }),
      },
    })

    await openDetails(wrapper)

    expect(wrapper.text()).toContain('处理建议')
    expect(wrapper.text()).toContain('测试失败')
    expect(wrapper.find('button[title^="修正后继续"]').exists()).toBe(false)
  })
})
