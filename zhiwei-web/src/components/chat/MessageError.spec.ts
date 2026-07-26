import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Message } from '@/types'
import MessageError from './MessageError.vue'

const mocks = vi.hoisted(() => ({
  copyToClipboard: vi.fn(),
  getDiagnosticReport: vi.fn(),
  createBackup: vi.fn(),
  validateBackup: vi.fn(),
}))

vi.mock('@/utils/clipboard', () => ({
  copyToClipboard: mocks.copyToClipboard,
}))

vi.mock('@/api/client', () => ({
  diagnosticsApi: {
    getReport: mocks.getDiagnosticReport,
    createBackup: mocks.createBackup,
    validateBackup: mocks.validateBackup,
  },
}))

function mountError(message: Message) {
  return mount(MessageError, {
    props: { message },
    global: {
      stubs: {
        RouterLink: defineComponent({
          name: 'RouterLink',
          props: {
            to: [String, Object],
          },
          setup(props, { attrs, slots }) {
            return () => h('a', {
              ...attrs,
              'data-route-name': typeof props.to === 'object' && props.to !== null
                ? String((props.to as { name?: unknown }).name ?? '')
                : String(props.to ?? ''),
            }, slots.default?.())
          },
        }),
      },
    },
  })
}

beforeEach(() => {
  mocks.copyToClipboard.mockReset().mockResolvedValue(true)
  mocks.createBackup.mockReset().mockResolvedValue({
    createdAt: '2026-07-04T10:01:00Z',
    fileName: 'zhiwei-backup-20260704-100100.zip',
    path: 'C:\\Users\\zsg\\.zhiwei\\backups\\zhiwei-backup-20260704-100100.zip',
    sizeBytes: 1024,
    includedFileCount: 3,
  })
  mocks.validateBackup.mockReset().mockResolvedValue({
    fileName: 'zhiwei-backup-20260704-100100.zip',
    path: 'C:\\Users\\zsg\\.zhiwei\\backups\\zhiwei-backup-20260704-100100.zip',
    status: 'OK',
    detail: '备份文件结构正常',
    sizeBytes: 1024,
    entryCount: 4,
    manifestPresent: true,
    manifest: null,
    problems: [],
    restorePlan: null,
  })
  mocks.getDiagnosticReport.mockReset().mockResolvedValue({
    generatedAt: '2026-07-04T10:00:00Z',
    status: 'WARN',
    summary: '本地服务可用，但有配置或运行时风险',
    app: {},
    runtime: {},
    counts: { 'modelServices.generationEnabled': 0 },
    checks: [
      { id: 'model-services', label: '模型服务', status: 'WARN', detail: '没有启用的生成模型服务', metadata: {} },
    ],
    hints: ['模型服务：没有启用的生成模型服务'],
  })
})

describe('MessageError 失败诊断', () => {
  it('可以复制当前失败消息的诊断信息', async () => {
    const message: Message = {
      id: 'msg-1',
      turnId: 'turn-1',
      role: 'user',
      content: '帮我调研一下今日最新资讯',
      timestamp: Date.now(),
      status: 'error',
      errorMessage: '发送失败',
      traceId: 'trace-1',
    } as any

    const wrapper = mountError(message)

    await wrapper.find('button[title="复制诊断信息"]').trigger('click')
    await flushPromises()

    expect(mocks.copyToClipboard).toHaveBeenCalledTimes(1)
    const diagnostic = mocks.copyToClipboard.mock.calls[0][0]
    expect(diagnostic).toContain('[知微诊断]')
    expect(diagnostic).toContain('范围: 消息失败')
    expect(diagnostic).toContain('消息ID: msg-1')
    expect(diagnostic).toContain('轮次ID: turn-1')
    expect(diagnostic).toContain('Trace: trace-1')
    expect(diagnostic).toContain('错误: 发送失败')
    expect(diagnostic).toContain('本地诊断状态: WARN')
    expect(diagnostic).toContain('下一步建议: 1. 到模型服务设置中启用一个生成模型，再重试当前消息。')
    expect(wrapper.text()).toContain('查看任务步骤')
    expect(wrapper.text()).not.toContain('查看执行详情')
    expect(wrapper.text()).toContain('已复制')
  })

  it('本地诊断报告获取失败时仍然复制消息上下文和失败原因', async () => {
    mocks.getDiagnosticReport.mockRejectedValue({ message: 'fetch failed' })
    const message: Message = {
      id: 'msg-network',
      turnId: 'turn-network',
      role: 'user',
      content: '继续刚才的任务',
      timestamp: Date.now(),
      status: 'error',
      errorMessage: '网络连接失败',
    } as any

    const wrapper = mountError(message)

    await wrapper.find('button[title="复制诊断信息"]').trigger('click')
    await flushPromises()

    const diagnostic = mocks.copyToClipboard.mock.calls[0][0]
    expect(diagnostic).toContain('消息ID: msg-network')
    expect(diagnostic).toContain('本地诊断状态: 获取失败')
    expect(diagnostic).toContain('本地诊断错误: fetch failed')
    expect(diagnostic).toContain('下一步建议: 1. 确认后端服务和网络连接正常后重试。')
  })

  it('可以在消息失败处创建并校验本地备份', async () => {
    mocks.getDiagnosticReport.mockResolvedValue({
      generatedAt: '2026-07-04T10:00:00Z',
      status: 'WARN',
      summary: '本地服务可用，但缺少备份',
      app: {},
      runtime: {},
      counts: { 'modelServices.generationEnabled': 1 },
      checks: [
        { id: 'data-backups', label: '数据备份', status: 'WARN', detail: '尚未发现本地备份文件', metadata: {} },
      ],
      hints: ['数据备份：尚未发现本地备份文件'],
    })
    const message: Message = {
      id: 'msg-backup',
      turnId: 'turn-backup',
      role: 'user',
      content: '继续刚才的任务',
      timestamp: Date.now(),
      status: 'error',
      errorMessage: '发送失败',
    } as any

    const wrapper = mountError(message)

    expect(wrapper.text()).not.toContain('创建备份')
    await wrapper.find('button[title="分析本机状态并给出下一步"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('升级、迁移或继续排查前，先备份知微 HOME 目录。')
    await wrapper.find('button[title="创建并校验本地备份"]').trigger('click')
    await flushPromises()

    expect(mocks.createBackup).toHaveBeenCalledTimes(1)
    expect(mocks.validateBackup).toHaveBeenCalledWith('zhiwei-backup-20260704-100100.zip')
    expect(wrapper.text()).toContain('已创建并校验本地备份：zhiwei-backup-20260704-100100.zip')
  })

  it('可以在消息失败处轻量分析本机状态并显示下一步', async () => {
    const message: Message = {
      id: 'msg-diagnostic',
      turnId: 'turn-diagnostic',
      role: 'user',
      content: '帮我写一段总结',
      timestamp: Date.now(),
      status: 'error',
      errorMessage: '模型服务不可用',
    } as any

    const wrapper = mountError(message)

    await wrapper.find('button[title="分析本机状态并给出下一步"]').trigger('click')
    await flushPromises()

    expect(mocks.getDiagnosticReport).toHaveBeenCalledTimes(1)
    expect(mocks.copyToClipboard).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('本机状态 WARN：本地服务可用，但有配置或运行时风险')
    expect(wrapper.text()).toContain('到模型服务设置中启用一个生成模型，再重试当前消息。')
    expect(wrapper.text()).toContain('打开模型设置')
    expect(wrapper.find('a[data-route-name="settingsModels"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('重新分析')
  })

  it('本机诊断发现能力异常时提供能力中心入口', async () => {
    mocks.getDiagnosticReport.mockResolvedValue({
      generatedAt: '2026-07-04T10:00:00Z',
      status: 'WARN',
      summary: '工具和技能需要校准',
      app: {},
      runtime: {},
      counts: {
        'modelServices.generationEnabled': 1,
        'skills.unknownToolReferences': 1,
        'skills.missingCanonicalToolReferences': 0,
      },
      checks: [
        {
          id: 'capabilities',
          label: '工具和技能',
          status: 'WARN',
          detail: '部分技能引用了未知工具，执行前需要修正 Skill 元数据',
          metadata: {
            unknownSkillToolReferences: 1,
            missingCanonicalSkillToolReferences: 0,
          },
        },
      ],
      hints: [],
    })
    const message: Message = {
      id: 'msg-capability',
      turnId: 'turn-capability',
      role: 'assistant',
      content: '工具未注册，可以修复能力后继续。',
      timestamp: Date.now(),
      status: 'error',
      errorMessage: '工具未注册',
    } as any

    const wrapper = mountError(message)

    await wrapper.find('button[title="分析本机状态并给出下一步"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('修正 Skill 元数据里的 suggestedTools，避免引用未知工具 ID。')
    expect(wrapper.text()).toContain('能力中心')
    expect(wrapper.find('a[data-route-name="capabilities"]').exists()).toBe(true)
  })

  it('能力失败消息不等本机诊断也会直接提供能力中心入口', () => {
    const message: Message = {
      id: 'msg-capability-direct',
      turnId: 'turn-capability-direct',
      role: 'assistant',
      content: '工具未注册，可以修复能力后继续。',
      timestamp: Date.now(),
      status: 'error',
      errorMessage: '工具未注册：web.search',
      taskRecovery: {
        status: 'DEGRADED',
        title: '能力缺口需要修复',
        detail: '依赖的工具或技能当前不可用，可以在能力中心修复连接后继续。',
        actionLabel: '修复能力后继续',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'web.search',
          toolName: '网页搜索',
          executionKind: 'TOOL',
          action: '搜索资料',
          failureCategory: 'CAPABILITY',
          outputSummary: '工具未注册',
        },
      },
    } as any

    const wrapper = mountError(message)

    expect(wrapper.text()).toContain('能力中心')
    expect(wrapper.find('a[data-route-name="capabilities"]').exists()).toBe(true)
    expect(mocks.getDiagnosticReport).not.toHaveBeenCalled()
  })

  it('诊断发现本地数据风险时提供维护入口', async () => {
    mocks.getDiagnosticReport.mockResolvedValue({
      generatedAt: '2026-07-04T10:00:00Z',
      status: 'WARN',
      summary: '本地服务可用，但缺少备份',
      app: {},
      runtime: {},
      counts: { 'modelServices.generationEnabled': 1 },
      checks: [
        { id: 'data-backups', label: '数据备份', status: 'WARN', detail: '尚未发现本地备份文件', metadata: {} },
        { id: 'schema-migrations', label: '数据迁移', status: 'OK', detail: '数据库迁移历史正常', metadata: {} },
      ],
      hints: ['数据备份：尚未发现本地备份文件'],
    })
    const message: Message = {
      id: 'msg-maintenance',
      turnId: 'turn-maintenance',
      role: 'user',
      content: '继续刚才的任务',
      timestamp: Date.now(),
      status: 'error',
      errorMessage: '发送失败',
    } as any

    const wrapper = mountError(message)

    await wrapper.find('button[title="分析本机状态并给出下一步"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('本地维护')
    expect(wrapper.find('a[data-route-name="settingsGeneral"]').exists()).toBe(true)
    expect(wrapper.find('button[title="创建并校验本地备份"]').exists()).toBe(true)
  })

  it('失败消息直接显示任务恢复线索且不主动读取本机诊断', () => {
    const message: Message = {
      id: 'msg-recovery',
      turnId: 'turn-recovery',
      role: 'assistant',
      content: '命令执行失败，可以修正后继续。',
      timestamp: Date.now(),
      status: 'error',
      errorMessage: '命令执行失败',
      traceId: 'trace-recovery',
      taskRecovery: {
        status: 'DEGRADED',
        title: 'Shell 执行没有完成',
        detail: '命令或代码没有完成，可以修正错误后继续执行。',
        actionLabel: '修正后继续',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          action: '执行命令',
          failureCategory: 'COMMAND',
          inputSummary: '执行 `npm test`',
          outputSummary: '测试失败',
          workingDirectory: 'D:\\WorkSpace\\Project\\News',
        },
        nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证'],
      },
    } as any

    const wrapper = mountError(message)

    expect(wrapper.text()).toContain('Shell 执行没有完成')
    expect(wrapper.text()).toContain('命令或代码没有完成，可以修正错误后继续执行。')
    expect(wrapper.text()).toContain('恢复方式：手动继续')
    expect(wrapper.text()).toContain('断点：任务步骤；Shell 执行；操作=执行命令；类型=命令执行')
    expect(wrapper.text()).toContain('查看命令输出并修正报错原因')
    expect(mocks.getDiagnosticReport).not.toHaveBeenCalled()
  })

  it('本机状态分析失败时仍显示可行动建议', async () => {
    mocks.getDiagnosticReport.mockRejectedValue({ message: 'fetch failed' })
    const message: Message = {
      id: 'msg-diagnostic-failed',
      turnId: 'turn-diagnostic-failed',
      role: 'user',
      content: '继续刚才的任务',
      timestamp: Date.now(),
      status: 'error',
      errorMessage: '网络连接失败',
    } as any

    const wrapper = mountError(message)

    await wrapper.find('button[title="分析本机状态并给出下一步"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('本机状态分析失败：fetch failed')
    expect(wrapper.text()).toContain('确认后端服务和网络连接正常后重试。')
  })
})
