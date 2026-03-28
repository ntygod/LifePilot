import type { PermissionApprovalLog } from '@/types'

const actionTypeLabels: Record<string, string> = {
  READ_FILE: '读取文件',
  WRITE_FILE: '修改文件',
  DELETE_FILE: '删除文件',
  EXECUTE_SHELL: '命令 / 代码执行',
  BROWSER_AUTOMATION: '执行浏览器自动化',
  HTTP_REQUEST: '访问外部网络',
  WRITE_MEMORY: '写入长期记忆',
  MODIFY_DATASTORE: '修改数据存储',
  CREATE_SCHEDULE: '创建或修改定时任务',
  GENERIC_TOOL_OPERATION: '任务级高风险操作',
}

const toolLabels: Record<string, string> = {
  'code.execute': '运行本地代码',
  'shell.exec': '执行本地命令',
}

const subjectTypeLabels: Record<string, string> = {
  SESSION: '本会话',
  WORKSPACE: '当前工作目录',
  TASK: '当前任务',
  USER: '长期',
}

export function formatPermissionActionLabel(actionType?: string | null, toolName?: string | null) {
  if (actionType && actionTypeLabels[actionType]) {
    return actionTypeLabels[actionType]
  }
  if (toolName && toolName.trim()) {
    return toolName.trim()
  }
  return '任务级高风险操作'
}

export function formatPermissionToolLabel(toolId?: string | null, toolName?: string | null) {
  if (toolId && toolLabels[toolId]) {
    return toolLabels[toolId]
  }
  if (toolName && toolName.trim()) {
    return toolName.trim()
  }
  return '执行工具操作'
}

export function buildPermissionCoverageHint(actionType?: string | null, toolName?: string | null) {
  if (actionType === 'CREATE_SCHEDULE') {
    return '授权后，这个任务后续自动运行时可直接执行高风险操作；不影响其他任务。'
  }
  const actionLabel = formatPermissionActionLabel(actionType, toolName)
  return `仅覆盖“${actionLabel}”这一类操作，不会自动放开其他高风险能力。`
}

export function formatPermissionSubjectLabel(subjectType?: string | null) {
  if (!subjectType) {
    return null
  }
  return subjectTypeLabels[subjectType] ?? subjectType
}

export function buildPermissionApprovalLog(log: PermissionApprovalLog) {
  const actionLabel = formatPermissionActionLabel(log.actionType, log.toolName)
  const subjectLabel = formatPermissionSubjectLabel(log.subjectType)

  if (log.resolution === 'approved') {
    return subjectLabel
      ? `已授权 · ${subjectLabel} · ${actionLabel}`
      : `已授权 · ${actionLabel}`
  }

  if (log.resolution === 'expired') {
    return `已超时 · ${actionLabel}`
  }

  return `已拒绝 · ${actionLabel}`
}
