import type { A2uiComponent, ChatAttachment, Message, ReactStepDto } from '@/types'
import type { PermissionApprovalLog } from '@/types'
import { formatPermissionActionLabel } from '@/utils/permissionApproval'

/** 后端附件数据结构，对应 AttachmentInfo record。 */
interface BackendAttachment {
  id: string
  fileName: string
  fileSize: number
  mimeType: string
  url?: string | null
}

type BackendMessageLike = {
  id: string
  turnId?: string | null
  role: 'user' | 'assistant' | 'permission-approval'
  content: string
  a2uiComponents?: unknown
  timestamp: string | number
  reasoningSummary?: string | null
  /** 推理过程文本（DeepSeek/Qwen 等推理模型 reasoning_content）— 后端 payload_json 暴露后自动启用 */
  reasoningContent?: string | null
  /** 推理过程持续时间（毫秒） */
  reasoningDurationMs?: number | null
  traceId?: string | null
  attachments?: BackendAttachment[] | null
  reactSteps?: ReactStepDto[] | null
  completionMode?: Message['completionMode'] | null
  resumedFromTraceId?: string | null
  turnStatus?: Message['turnStatus'] | null
  errorMessage?: string | null
  suspendReasonType?: string | null
  suspendReasonSourceId?: string | null
}

function isObjectRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isA2uiComponentArray(value: unknown): value is A2uiComponent[] {
  return Array.isArray(value)
    && value.every(item =>
      item
      && typeof item === 'object'
      && typeof (item as A2uiComponent).id === 'string'
      && typeof (item as A2uiComponent).type === 'string'
      && Array.isArray((item as A2uiComponent).children),
    )
}

export function normalizeA2uiComponents(components: A2uiComponent[] | null | undefined): A2uiComponent[] {
  if (!components?.length) {
    return []
  }

  return components.map(component => ({
    ...component,
    properties: { ...(component.properties ?? {}) },
    children: [...(component.children ?? [])],
  }))
}

export function extractA2uiComponents(payload: unknown): A2uiComponent[] | null {
  if (!isObjectRecord(payload)) return null

  const components = (payload as { a2uiComponents?: unknown }).a2uiComponents
  return isA2uiComponentArray(components) ? normalizeA2uiComponents(components) : null
}

export function parseMessageTimestamp(timestamp: string | number): number {
  if (typeof timestamp === 'number') {
    return timestamp
  }
  const parsed = Date.parse(timestamp)
  return Number.isNaN(parsed) ? Date.now() : parsed
}

export function mapBackendMessage(message: BackendMessageLike): Message {
  const components = extractA2uiComponents(message)
  const attachments: ChatAttachment[] | undefined = message.attachments?.length
    ? message.attachments.map(att => ({
        fileId: att.id,
        url: att.url ?? '',
        filename: att.fileName,
        size: att.fileSize,
        type: att.mimeType,
        isImage: att.mimeType.startsWith('image/'),
      }))
    : undefined

  // 权限审批消息的 content 是 JSON，需要解析成前端可消费的审批结构。
  if (message.role === 'permission-approval') {
    try {
      const parsed = JSON.parse(message.content) as {
        requestId: string
        toolId: string
        toolName: string
        actionType?: string
        riskLevel: string
        approved?: boolean
        subjectType?: string | null
        reason?: string | null
        resourceScope?: Record<string, unknown> | null
      }
      const resolution = parsed.approved === true
        ? 'approved'
        : (parsed.reason === '审批超时' ? 'expired' : 'rejected')
      const approvalLog: PermissionApprovalLog = {
        requestId: parsed.requestId,
        toolId: parsed.toolId,
        toolName: parsed.toolName,
        actionType: parsed.actionType ?? 'GENERIC_TOOL_OPERATION',
        resolution,
        subjectType: parsed.subjectType ?? null,
        reason: parsed.reason ?? null,
        timestamp: typeof message.timestamp === 'string'
          ? message.timestamp
          : new Date(message.timestamp).toISOString(),
      }
      return {
        id: message.id,
        turnId: message.turnId ?? undefined,
        role: 'permission-approval',
        content: formatPermissionActionLabel(parsed.actionType, parsed.toolName),
        timestamp: parseMessageTimestamp(message.timestamp),
        permissionApprovalLogs: [approvalLog],
      }
    } catch {
      // JSON 解析失败时，回退为普通消息展示。
    }
  }

  return {
    id: message.id,
    turnId: message.turnId ?? undefined,
    role: message.role,
    content: message.content ?? '',
    a2uiComponents: components ?? undefined,
    timestamp: parseMessageTimestamp(message.timestamp),
    reasoningSummary: message.reasoningSummary ?? undefined,
    reasoningContent: message.reasoningContent ?? undefined,
    reasoningDurationMs: message.reasoningDurationMs ?? undefined,
    traceId: message.traceId ?? undefined,
    attachments,
    reactSteps: message.reactSteps?.length ? message.reactSteps : undefined,
    completionMode: message.completionMode ?? undefined,
    resumedFromTraceId: message.resumedFromTraceId ?? undefined,
    turnStatus: message.turnStatus ?? undefined,
    errorMessage: message.errorMessage ?? undefined,
    suspendReasonType: message.suspendReasonType ?? undefined,
    suspendReasonSourceId: message.suspendReasonSourceId ?? undefined,
  }
}
