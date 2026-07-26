import { describe, expect, it } from 'vitest'
import {
  buildToolRecoveryActions,
  buildToolRecoveryContextSummary,
  buildToolRecoveryPlan,
  formatToolFailureCategory,
  mergeToolRecoveryArtifactRefs,
  resolveToolAction,
  resolveToolExecutionKind,
  resolveToolFailureCategory,
  resolveToolRecoveryHint,
} from './toolExecution'

describe('toolExecution 工具/技能执行语义', () => {
  it('合并恢复动作和工具步骤里的产物引用', () => {
    expect(mergeToolRecoveryArtifactRefs([
      {
        artifactId: 'artifact-action',
        fileName: '动作截图.png',
        mimeType: 'image/png',
        kind: 'IMAGE',
        size: 512,
        downloadUrl: '/api/artifacts/artifact-action/download',
      },
    ], [
      {
        artifactId: 'artifact-action',
        fileName: '重复截图.png',
      },
      {
        artifactId: 'artifact-tool',
        fileName: '调研结果.md',
        mimeType: 'text/markdown',
        kind: 'FILE',
        size: 2048,
      },
    ])).toEqual([
      {
        artifactId: 'artifact-action',
        fileName: '动作截图.png',
        mimeType: 'image/png',
        kind: 'IMAGE',
        size: 512,
        downloadUrl: '/api/artifacts/artifact-action/download',
      },
      {
        artifactId: 'artifact-tool',
        fileName: '调研结果.md',
        mimeType: 'text/markdown',
        kind: 'FILE',
        size: 2048,
        downloadUrl: '/api/artifacts/artifact-tool/download',
      },
    ])
  })

  it('识别技能加载失败并生成技能化恢复动作', () => {
    expect(resolveToolExecutionKind('skill.load')).toBe('SKILL')
    expect(resolveToolAction('skill.load')).toBe('加载技能')
    expect(resolveToolAction('skill.run')).toBe('执行技能')
    expect(resolveToolFailureCategory('skill.load')).toBe('SKILL')
    expect(formatToolFailureCategory('SKILL')).toBe('技能步骤')
    expect(resolveToolRecoveryHint('skill.load')).toBe('技能加载没有完成，可以检查技能名称或依赖后继续。')
    expect(buildToolRecoveryActions('skill.load')).toEqual([
      {
        id: 'resume',
        label: '检查技能后继续',
        description: '保留当前进度，检查技能后从失败步骤接上。',
        mode: 'resume',
        category: 'SKILL',
        nextActions: ['确认技能名称和依赖是否可用', '重新加载技能后继续当前任务'],
      },
      {
        id: 'restart',
        label: '重新开始',
        description: '保留技能失败线索，重新加载或执行失败技能步骤。',
        mode: 'restart',
        category: 'SKILL',
        nextActions: ['保留技能加载失败原因', '重新加载技能后重跑当前任务'],
      },
    ])
    expect(buildToolRecoveryPlan({
      toolId: 'skill.load',
      executionKind: 'SKILL',
      failureCategory: 'SKILL',
      subjectNames: ['research-assistant'],
    })).toEqual([
      '确认技能 research-assistant 的名称和依赖是否可用',
      '重新加载技能后继续当前任务',
    ])
    expect(buildToolRecoveryActions('skill.load', {
      toolId: 'skill.load',
      executionKind: 'SKILL',
      failureCategory: 'SKILL',
      subjectNames: ['research-assistant'],
    })[0].nextActions).toEqual([
      '确认技能 research-assistant 的名称和依赖是否可用',
      '重新加载技能后继续当前任务',
    ])
    expect(buildToolRecoveryActions('skill.load', {
      toolId: 'skill.load',
      executionKind: 'SKILL',
      failureCategory: 'SKILL',
      subjectNames: ['research-assistant'],
    })[1].nextActions).toEqual([
      '保留技能 research-assistant 的加载失败原因',
      '重新加载技能后重跑当前任务',
    ])
  })

  it('区分技能执行失败和技能加载失败', () => {
    expect(resolveToolRecoveryHint('skill.run')).toBe('技能执行没有完成，可以检查输入、依赖或技能步骤后继续。')
    expect(buildToolRecoveryPlan({
      toolId: 'skill.run',
      executionKind: 'SKILL',
      failureCategory: 'SKILL',
      subjectNames: ['research-assistant'],
    })).toEqual([
      '检查技能 research-assistant 的输入、依赖和执行步骤',
      '保留当前进度并从失败技能步骤继续',
    ])
  })

  it('为失败步骤生成可解释的恢复上下文', () => {
    expect(buildToolRecoveryContextSummary({
      toolId: 'skill.run',
      executionKind: 'SKILL',
      failureCategory: 'SKILL',
      subjectNames: ['research-assistant'],
      inputSummary: '按资料整理方案',
      outputSummary: '技能执行超时',
      interrupted: true,
    })).toEqual([
      '保留技能 research-assistant',
      '带上原始输入',
      '带上失败输出',
      '从中断位置接上',
    ])

    expect(buildToolRecoveryContextSummary({
      toolId: 'shell.exec',
      failureCategory: 'COMMAND',
      outputDetail: 'AssertionError',
      workingDirectory: 'D:\\WorkSpace\\Project\\News',
      artifactRefs: [{ fileName: '调研结果.md' }],
    })).toEqual([
      '带上失败输出',
      '工作目录 News',
      '保留产物 调研结果.md',
    ])

    expect(buildToolRecoveryContextSummary({
      toolId: 'web.search',
      failureCategory: 'CAPABILITY',
      outputSummary: '工具未注册: web.search',
      missingCapabilities: [
        { kind: 'TOOL', id: 'web.search', source: 'runtime_tool_call' },
      ],
    })).toEqual([
      '带上失败输出',
      '缺失能力 web.search',
    ])
  })

  it('为常见工具生成可恢复的动作语义', () => {
    expect(resolveToolAction('shell.exec')).toBe('执行命令')
    expect(resolveToolAction('code')).toBe('执行命令')
    expect(resolveToolAction('file.read')).toBe('读取文件')
    expect(resolveToolAction('file.write')).toBe('写入文件')
    expect(resolveToolAction('file.delete')).toBe('操作文件')
    expect(resolveToolAction('web.search')).toBe('搜索资料')
    expect(resolveToolAction('web.fetch')).toBe('读取网页')
    expect(resolveToolAction('web.open')).toBe('访问网络资料')
    expect(resolveToolAction('browser')).toBe('操作浏览器')
    expect(resolveToolAction('browser.click')).toBe('操作浏览器')
    expect(resolveToolFailureCategory('browser.click')).toBe('BROWSER')
    expect(formatToolFailureCategory('BROWSER')).toBe('浏览器操作')
    expect(resolveToolRecoveryHint('browser.click')).toBe('浏览器操作没有完成，可以检查页面状态、登录或元素选择后继续。')
    expect(buildToolRecoveryActions('browser.click')[0]).toMatchObject({
      label: '检查页面后继续',
      description: '保留当前浏览器上下文，检查页面状态、登录或元素选择后继续。',
      category: 'BROWSER',
      nextActions: ['检查浏览器页面状态、登录或元素选择', '保留当前上下文并从失败页面操作继续'],
    })
    expect(resolveToolAction('memory.search')).toBe('处理记忆')
    expect(resolveToolAction('git.diff')).toBe('操作仓库')
    expect(resolveToolAction('unknown.tool')).toBeUndefined()
  })

  it('为产品级能力生成明确分类和恢复建议', () => {
    expect(resolveToolFailureCategory('web.search', '工具未注册: web.search')).toBe('CAPABILITY')
    expect(resolveToolFailureCategory('web.search', '工具 web.search 未注册')).toBe('CAPABILITY')
    expect(formatToolFailureCategory('CAPABILITY')).toBe('能力缺口')
    expect(resolveToolRecoveryHint('web.search', '工具未注册: web.search'))
      .toBe('依赖的工具或技能当前不可用，可以在能力中心修复连接或调整 Skill 元数据后继续。')
    expect(buildToolRecoveryActions('web.search', {
      outputSummary: '工具未注册: web.search',
    })[0]).toMatchObject({
      label: '修复能力后继续',
      description: '保留当前进度，修复缺失工具或技能连接后从失败步骤接上。',
      category: 'CAPABILITY',
      nextActions: ['补齐缺失能力：web.search', '到能力中心检查 MCP 工具提供方或本地工具连接', '修复后从失败步骤继续'],
    })
    expect(buildToolRecoveryPlan({
      toolId: 'skill.run',
      executionKind: 'SKILL',
      outputSummary: '工具未注册: web.search',
    })).toEqual([
      '补齐缺失能力：web.search',
      '检查当前 Skill 的 suggestedTools 引用',
      '修复后从失败步骤继续',
    ])
    expect(buildToolRecoveryPlan({
      toolId: 'skill.run',
      executionKind: 'SKILL',
      failureCategory: 'CAPABILITY',
      subjectNames: ['research-assistant'],
      missingCapabilities: [
        { kind: 'TOOL', id: 'web.search', source: 'suggested_tools', skillName: 'research-assistant' },
        { kind: 'TOOL', id: 'browser.click', source: 'suggested_tools', skillName: 'research-assistant' },
      ],
    })).toEqual([
      '补齐缺失能力：web.search、browser.click',
      '检查 Skill research-assistant 的 suggestedTools 引用',
      '修复后从失败步骤继续',
    ])
    expect(buildToolRecoveryActions('skill.run', {
      executionKind: 'SKILL',
      failureCategory: 'CAPABILITY',
      subjectNames: ['research-assistant'],
      missingCapabilities: [
        { kind: 'TOOL', id: 'web.search', source: 'suggested_tools', skillName: 'research-assistant' },
      ],
    })[1].nextActions).toEqual([
      '保留缺失能力线索：web.search',
      '检查 Skill research-assistant 的 suggestedTools 引用',
      '修复后重新执行相关步骤',
    ])

    expect(resolveToolAction('knowledge.search')).toBe('处理资料')
    expect(resolveToolFailureCategory('knowledge.search')).toBe('KNOWLEDGE')
    expect(formatToolFailureCategory('KNOWLEDGE')).toBe('资料处理')
    expect(resolveToolRecoveryHint('knowledge.search')).toBe('资料处理没有完成，可以检查资料来源、索引或解析结果后继续。')
    expect(buildToolRecoveryActions('knowledge.search')[0]).toMatchObject({
      label: '调整资料后继续',
      description: '保留已处理资料，调整来源、索引或解析后继续。',
      category: 'KNOWLEDGE',
    })
    expect(buildToolRecoveryPlan({ toolId: 'knowledge.search' })).toEqual([
      '检查资料来源、索引或解析结果',
      '保留已完成资料处理并从失败处继续',
    ])

    expect(resolveToolAction('workflow.run')).toBe('执行工作流')
    expect(resolveToolFailureCategory('workflow.run')).toBe('WORKFLOW')
    expect(resolveToolAction('mcp.call')).toBe('调用连接器')
    expect(resolveToolFailureCategory('mcp.call')).toBe('INTEGRATION')
    expect(resolveToolAction('agent.delegate')).toBe('协作智能体')
    expect(resolveToolFailureCategory('agent.delegate')).toBe('AGENT')
    expect(resolveToolAction('llm.generate')).toBe('整理回答')
    expect(resolveToolFailureCategory('llm.generate')).toBe('MODEL')
    expect(resolveToolFailureCategory('git.clone')).toBe('REPOSITORY')
    expect(buildToolRecoveryActions('git.clone')[0]).toMatchObject({
      label: '检查仓库后继续',
      description: '保留仓库状态，处理分支、权限或冲突后继续。',
      category: 'REPOSITORY',
    })
  })

  it('保留命令失败的原有恢复语义', () => {
    expect(resolveToolExecutionKind('shell.exec')).toBe('TOOL')
    expect(resolveToolFailureCategory('shell.exec')).toBe('COMMAND')
    expect(buildToolRecoveryActions('shell.exec')[0]).toMatchObject({
      label: '修正后继续',
      description: '保留已完成步骤，修正命令或代码错误后继续验证。',
      category: 'COMMAND',
    })
    expect(buildToolRecoveryPlan({ toolId: 'shell.exec' })).toEqual([
      '查看命令输出并修正报错原因',
      '从失败命令后继续执行验证',
    ])
  })
})
