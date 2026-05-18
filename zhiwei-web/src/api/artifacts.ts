/**
 * 会话产物（Artifact）API 客户端 —— 配合 Web/Tauri 端 ArtifactCard 组件
 * 拉取产物元数据 / 构造下载 URL。
 *
 * <p>对应后端 {@code /api/artifacts/{id}} 元数据查询与
 * {@code /api/artifacts/{id}/download} 下载端点。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
import { getApiOrigin } from '@/api/config'

const getBase = () => getApiOrigin() + '/api'

/** 产物类型枚举 —— 与后端 {@code com.lifepilot.tool.model.ArtifactKind} 对齐。 */
export type ArtifactKind = 'FILE' | 'IMAGE'

/** 产物元数据 —— 对应后端 ArtifactController.metadata 返回体。 */
export interface ArtifactMetadata {
  id: string
  fileName: string
  mimeType: string
  size: number
  kind: ArtifactKind
  summary: string | null
  createdAt: string | null
}

/** 拉取产物元数据；404 / 网络异常时抛错。 */
export async function getArtifactMetadata(id: string): Promise<ArtifactMetadata> {
  const res = await fetch(`${getBase()}/artifacts/${encodeURIComponent(id)}`)
  if (!res.ok) {
    throw new Error(`artifact metadata fetch failed: ${res.status} ${res.statusText}`)
  }
  const json = (await res.json()) as { code?: number; data?: ArtifactMetadata }
  if (typeof json?.code !== 'number' || !json.data) {
    throw new Error('artifact metadata 响应格式错误')
  }
  return json.data
}

/**
 * 构造产物下载 URL —— 直接放到 {@code <a href download>} 即可触发浏览器下载。
 * Tauri 环境下使用 {@code http://localhost:{port}} 绝对路径连接后端。
 */
export function buildArtifactDownloadUrl(id: string): string {
  return `${getBase()}/artifacts/${encodeURIComponent(id)}/download`
}

/** SSE artifact-ref 事件 payload —— 与后端 SseEventType.ARTIFACT_REF 对齐。 */
export interface ArtifactRefPayload {
  artifactId: string
  fileName: string
  mimeType: string
  kind: ArtifactKind
  size: number
  /** 后端推送时即提供，前端可直接用，无需再调 buildArtifactDownloadUrl。 */
  downloadUrl: string
}
