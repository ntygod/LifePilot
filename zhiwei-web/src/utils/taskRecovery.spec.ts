import { describe, expect, it } from 'vitest'
import {
  canUseManualRestart,
  canUseManualResume,
  hasRecoverableTaskState,
  isRecoverableAssistantTaskMessage,
  resolveManualResumeStatusLabel,
  resolveRecoverableTaskStatus,
} from './taskRecovery'
import type { TaskRecoverySummary } from '@/types'

function recovery(overrides: Partial<TaskRecoverySummary>): TaskRecoverySummary {
  return {
    status: 'SUSPENDED',
    title: '任务挂起',
    detail: '等待下一步',
    canResume: true,
    canRestart: true,
    ...overrides,
  }
}

describe('taskRecovery 恢复入口策略', () => {
  it('只把手动模式暴露成普通继续入口', () => {
    expect(canUseManualResume(recovery({ resumeMode: 'manual' }))).toBe(true)
    expect(canUseManualResume(recovery({ resumeMode: undefined }))).toBe(true)
    expect(canUseManualResume(recovery({ resumeMode: 'browser' }))).toBe(false)
    expect(canUseManualResume(recovery({ resumeMode: 'external' }))).toBe(false)
    expect(canUseManualResume(recovery({ resumeMode: 'scheduled' }))).toBe(false)
    expect(canUseManualResume(recovery({ resumeMode: 'user_reply' }))).toBe(false)
    expect(canUseManualResume(recovery({ reasonSourceId: '__await_user_input__' }))).toBe(false)
    expect(canUseManualResume(recovery({ canResume: false, resumeMode: 'manual' }))).toBe(false)
  })

  it('把任务恢复摘要状态视为可恢复任务状态', () => {
    expect(hasRecoverableTaskState({
      taskRecovery: recovery({ status: 'SUSPENDED', resumeMode: 'manual' }),
    })).toBe(true)
    expect(hasRecoverableTaskState({
      taskRecovery: recovery({ status: 'DEGRADED', resumeMode: 'manual' }),
    })).toBe(true)
    expect(hasRecoverableTaskState({ turnStatus: 'SUSPENDED' } as any)).toBe(true)
    expect(hasRecoverableTaskState({ completionMode: 'DEGRADED' } as any)).toBe(true)
    expect(hasRecoverableTaskState({})).toBe(false)
    expect(resolveRecoverableTaskStatus({
      taskRecovery: recovery({ status: 'DEGRADED', resumeMode: 'manual' }),
    })).toBe('DEGRADED')
    expect(resolveRecoverableTaskStatus({
      turnStatus: 'DEGRADED',
      taskRecovery: recovery({ status: 'SUSPENDED', resumeMode: 'manual' }),
    } as any)).toBe('SUSPENDED')
    expect(resolveRecoverableTaskStatus({ turnStatus: 'SUCCESS' } as any)).toBeNull()
    expect(isRecoverableAssistantTaskMessage({
      role: 'assistant',
      taskRecovery: recovery({ status: 'DEGRADED', resumeMode: 'manual' }),
    } as any)).toBe(true)
    expect(isRecoverableAssistantTaskMessage({
      role: 'user',
      taskRecovery: recovery({ status: 'DEGRADED', resumeMode: 'manual' }),
    } as any)).toBe(false)
  })

  it('为非普通继续模式提供轻量状态标签', () => {
    expect(resolveManualResumeStatusLabel(recovery({ resumeMode: 'manual' }))).toBeNull()
    expect(resolveManualResumeStatusLabel(recovery({ resumeMode: 'browser' }))).toBe('浏览器接管')
    expect(resolveManualResumeStatusLabel(recovery({ resumeMode: 'external' }))).toBe('等待外部任务')
    expect(resolveManualResumeStatusLabel(recovery({ resumeMode: 'scheduled' }))).toBe('已安排唤醒')
    expect(resolveManualResumeStatusLabel(recovery({ resumeMode: 'user_reply' }))).toBe('等你补充')
    expect(resolveManualResumeStatusLabel(recovery({ reasonSourceId: '__await_user_input__' }))).toBe('等你补充')
    expect(resolveManualResumeStatusLabel(recovery({ canResume: false }))).toBe('等待中')
  })

  it('只在手动续接场景暴露普通重新开始入口', () => {
    expect(canUseManualRestart(recovery({ resumeMode: 'manual' }))).toBe(true)
    expect(canUseManualRestart(recovery({ resumeMode: undefined }))).toBe(true)
    expect(canUseManualRestart(recovery({ canRestart: false, resumeMode: 'manual' }))).toBe(false)
    expect(canUseManualRestart(recovery({ resumeMode: 'browser' }))).toBe(false)
    expect(canUseManualRestart(recovery({ resumeMode: 'external' }))).toBe(false)
    expect(canUseManualRestart(recovery({ resumeMode: 'scheduled' }))).toBe(false)
    expect(canUseManualRestart(recovery({ resumeMode: 'user_reply' }))).toBe(false)
    expect(canUseManualRestart(recovery({ reasonSourceId: '__await_user_input__' }))).toBe(false)
  })
})
