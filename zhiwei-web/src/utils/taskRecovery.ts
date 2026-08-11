import type { Message, TaskRecoverySummary } from '@/types'

const USER_REPLY_REASON_SOURCE_ID = '__await_user_input__'

export type RecoverableTaskStatus = 'DEGRADED' | 'SUSPENDED'

type RecoverableTaskStateLike = {
  turnStatus?: Message['turnStatus'] | null
  completionMode?: Message['completionMode'] | null
  taskRecovery?: TaskRecoverySummary | null
}

export function isUserReplyRecovery(taskRecovery?: TaskRecoverySummary | null): boolean {
  return taskRecovery?.resumeMode === 'user_reply'
    || taskRecovery?.reasonSourceId === USER_REPLY_REASON_SOURCE_ID
}

export function resolveRecoverableTaskStatus(
  state?: RecoverableTaskStateLike | null,
): RecoverableTaskStatus | null {
  const statuses = [
    state?.turnStatus,
    state?.completionMode,
    state?.taskRecovery?.status,
  ]

  if (statuses.some(status => status === 'SUSPENDED')) {
    return 'SUSPENDED'
  }
  if (statuses.some(status => status === 'DEGRADED')) {
    return 'DEGRADED'
  }
  return null
}

export function hasRecoverableTaskState(
  message?: RecoverableTaskStateLike | null,
): boolean {
  return resolveRecoverableTaskStatus(message) !== null
}

export function isRecoverableAssistantTaskMessage(
  message?: Pick<Message, 'role' | 'turnStatus' | 'completionMode' | 'taskRecovery'> | null,
): boolean {
  return message?.role === 'assistant' && hasRecoverableTaskState(message)
}

/**
 * 判断挂起任务是否适合用前端普通 RESUME 按钮恢复。
 *
 * 浏览器接管、外部等待、定时唤醒和等待用户补充都有专用入口或触发方式，
 * 不应该暴露成同一个“继续执行”按钮。
 */
export function canUseManualResume(taskRecovery?: TaskRecoverySummary | null): boolean {
  if (!taskRecovery) {
    return true
  }
  if (taskRecovery.canResume === false) {
    return false
  }
  if (isUserReplyRecovery(taskRecovery)) {
    return false
  }
  return !taskRecovery.resumeMode || taskRecovery.resumeMode === 'manual'
}

export function canUseManualResumeForMessage(message?: Pick<Message, 'taskRecovery'> | null): boolean {
  return canUseManualResume(message?.taskRecovery)
}

/**
 * 判断挂起/降级任务是否适合暴露成普通“重新开始”入口。
 *
 * 非手动续接场景已经有更自然的恢复路径：用户补充、浏览器接管、
 * 外部任务返回或定时唤醒。此时继续展示“重新开始”会把用户从当前闭环里拉走。
 */
export function canUseManualRestart(taskRecovery?: TaskRecoverySummary | null): boolean {
  if (!taskRecovery) {
    return true
  }
  if (taskRecovery.canRestart === false) {
    return false
  }
  return canUseManualResume(taskRecovery)
}

export function canUseManualRestartForMessage(message?: Pick<Message, 'taskRecovery'> | null): boolean {
  return canUseManualRestart(message?.taskRecovery)
}

export function resolveManualResumeStatusLabel(taskRecovery?: TaskRecoverySummary | null): string | null {
  if (!taskRecovery || canUseManualResume(taskRecovery)) {
    return null
  }
  if (isUserReplyRecovery(taskRecovery)) {
    return '等你补充'
  }
  switch (taskRecovery.resumeMode) {
    case 'user_reply':
      return '等你补充'
    case 'browser':
      return '浏览器接管'
    case 'external':
      return '等待外部任务'
    case 'scheduled':
      return '已安排唤醒'
    default:
      return '等待中'
  }
}

export function resolveManualResumeStatusLabelForMessage(
  message?: Pick<Message, 'taskRecovery'> | null,
): string | null {
  return resolveManualResumeStatusLabel(message?.taskRecovery)
}
