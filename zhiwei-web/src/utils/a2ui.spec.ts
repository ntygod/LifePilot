import { describe, expect, it } from 'vitest'
import { mapBackendMessage } from './a2ui'

describe('mapBackendMessage', () => {
  it('保留历史消息中的工具摘要和任务恢复断点', () => {
    const message = mapBackendMessage({
      id: 'assistant-history-recovery',
      role: 'assistant',
      content: '测试失败，我可以继续。',
      timestamp: '2026-07-04T06:00:00Z',
      sources: [
        {
          type: 'knowledgeBase',
          id: 'kb-product',
          name: '产品资料库',
        },
        {
          type: 'memory',
          id: 'entity-preference-1',
          name: '偏好轻量主界面',
          extra: {
            entityTypeLabel: '偏好',
            trustLevel: 'VERIFIED',
          },
        },
      ],
      memoryChanges: [
        {
          type: 'memory',
          id: 'entity-preference-1',
          name: '偏好轻量主界面',
          extra: {
            operationLabel: '新增',
            evidenceExcerpt: '主界面做轻，做好交互',
          },
        },
      ],
      knowledgeSettlements: [
        {
          knowledgeBaseId: 'kb-product',
          knowledgeBaseName: '产品资料库',
          sourceType: 'MESSAGE_TEXT',
          savedAt: '2026-07-07T04:00:00Z',
        },
      ],
      toolsSummary: [
        {
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          executionKind: 'TOOL',
          status: 'FAILED',
          success: false,
          latencyMs: 35,
          inputSummary: '执行 `npm test`',
          outputSummary: '测试失败',
        },
      ],
      taskRecovery: {
        status: 'DEGRADED',
        title: 'Shell 执行 没有完成',
        detail: '命令或代码没有完成，可以修正错误后继续执行。',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'shell.exec',
          toolName: 'Shell 执行',
          inputSummary: '执行 `npm test`',
          outputSummary: '测试失败',
        },
        nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证'],
      },
      turnRecoveryContext: {
        action: 'RESUME',
        resumeInput: '继续，先修 npm test',
        sourceTraceId: 'trace-failed-1',
        title: 'Shell 执行 没有完成',
        checkpoint: {
          kind: 'TOOL_FAILURE',
          toolId: 'shell.exec',
          inputSummary: '执行 `npm test`',
        },
        nextActions: ['从失败命令后继续执行验证'],
      },
    })

    expect(message.toolsSummary?.[0]?.toolId).toBe('shell.exec')
    expect(message.sources?.[0]).toMatchObject({ type: 'knowledgeBase', name: '产品资料库' })
    expect(message.sources?.[1]?.extra?.entityTypeLabel).toBe('偏好')
    expect(message.memoryChanges?.[0]?.name).toBe('偏好轻量主界面')
    expect(message.memoryChanges?.[0]?.extra?.operationLabel).toBe('新增')
    expect(message.knowledgeSettlements?.[0]).toMatchObject({
      knowledgeBaseId: 'kb-product',
      knowledgeBaseName: '产品资料库',
    })
    expect(message.taskRecovery?.checkpoint?.inputSummary).toBe('执行 `npm test`')
    expect(message.taskRecovery?.nextActions).toContain('从失败命令后继续执行验证')
    expect(message.turnRecoveryContext?.resumeInput).toBe('继续，先修 npm test')
    expect(message.turnRecoveryContext?.checkpoint?.toolId).toBe('shell.exec')
    expect(message.turnRecoveryContext?.nextActions).toContain('从失败命令后继续执行验证')
  })
})
