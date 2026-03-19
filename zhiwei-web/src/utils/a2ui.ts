import type { A2uiComponent, ChatAttachment, Message, ReactStepDto } from '@/types'

/** 后端附件数据结构（对应 AttachmentInfo record） */
interface BackendAttachment {
  id: string
  fileName: string
  fileSize: number
  mimeType: string
  url?: string | null
}

type BackendMessageLike = {
  id: string
  role: 'user' | 'assistant'
  content: string
  a2uiComponents?: unknown
  timestamp: string | number
  reasoningSummary?: string | null
  traceId?: string | null
  attachments?: BackendAttachment[] | null
  reactSteps?: ReactStepDto[] | null
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
  return {
    id: message.id,
    role: message.role,
    content: message.content ?? '',
    a2uiComponents: components ?? undefined,
    timestamp: parseMessageTimestamp(message.timestamp),
    reasoningSummary: message.reasoningSummary ?? undefined,
    traceId: message.traceId ?? undefined,
    attachments,
    reactSteps: message.reactSteps?.length ? message.reactSteps : undefined,
  }
}
