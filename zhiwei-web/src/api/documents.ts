/**
 * 文档产物 REST 封装 —— Phase 3A：元数据 / 版本 / diff / commit / rollback / 丢弃。
 *
 * 项目现有的 API client（src/api/client.ts）采用 fetch 封装的 `request` 函数，
 * 本文件沿用同一模式：通过 `request<T>` 发起请求，`getBase()` 负责解析 `/api` 前缀。
 *
 * @author zsg
 * @since 2026-04-21
 */
import { getApiOrigin } from '@/api/config'

const getBase = () => getApiOrigin() + '/api'

/** 文档元数据 —— 对应后端 DocumentController.getMetadata 返回体 */
export interface DocumentMetadata {
  id: string
  fileName: string
  mimeType: string
  fileSize: number
  origin: string
  sourcePath: string | null
  latestVersion: number
  createdAt: string
}

/** 版本列表条目 —— 对应 DocumentController.listVersions 中的单行 */
export interface DocumentVersionInfo {
  versionNo: number
  source: 'initial' | 'patch' | 'rollback'
  patchSummary: string | null
  createdAt: string
}

/** diff 片段：keep/delete/insert 三态，供前端渲染 inline diff */
export interface DiffSegment {
  type: 'keep' | 'delete' | 'insert'
  text: string
}

/** 单个改动：docx 用 paragraph_index/paragraph_preview；xlsx 用 sheet/cell/range/row 等字段 */
export interface DiffChange {
  patch_id: string
  op: string
  /** docx 场景：所在段落索引（xlsx 场景缺省） */
  paragraph_index?: number
  /** docx 场景：段落预览（xlsx 场景缺省） */
  paragraph_preview?: string
  /** xlsx 场景：工作表名 */
  sheet?: string
  /** xlsx update_cell：A1 地址 */
  cell?: string
  /** xlsx set_range：A1 区域 */
  range?: string
  /** xlsx insert_row / delete_row：1-based 行号 */
  row?: number
  /** xlsx set_range：区域行数 */
  rows?: number
  /** xlsx set_range：区域列数 */
  cols?: number
  segments: DiffSegment[]
  reason: string
}

/** diffJson 解析后的完整负载（docx / xlsx 共用，按 mime 字段区分） */
export interface DiffPayload {
  documentId: string
  fromVersion: number
  toVersion: number
  summary: string
  /** P3B 起：docx / xlsx；docx 后端可不写该字段，缺省视为 docx */
  mime?: 'docx' | 'xlsx'
  changes: DiffChange[]
}

/** commit 成功返回体 */
export interface CommitResult {
  committedPath: string
  backupPath?: string
}

/**
 * 统一 fetch 封装：强制要求后端走 ApiResponse<T> 包装（{code, message, data}），
 * 自动解包 .data；204 / 空 body / 非 JSON 三种边界单独处理。
 */
async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${getBase()}${url}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw {
      code: res.status,
      message: text || res.statusText || '请求失败',
      timestamp: new Date().toISOString(),
    }
  }
  if (res.status === 204) return undefined as T
  const contentType = res.headers.get('content-type') ?? ''
  const body = await res.text()
  if (!body || !body.trim()) return undefined as T
  if (!contentType.includes('application/json')) {
    try {
      return JSON.parse(body) as T
    } catch {
      throw { code: res.status, message: '响应格式错误', timestamp: new Date().toISOString() }
    }
  }
  const json = JSON.parse(body) as { code?: number; message?: string; data?: T }
  if (typeof json?.code !== 'number' || !('data' in json)) {
    throw {
      code: res.status,
      message: '响应格式错误：缺少 ApiResponse 封装',
      timestamp: new Date().toISOString(),
    }
  }
  return json.data as T
}

/** 获取文档元数据 */
export async function getDocument(id: string): Promise<DocumentMetadata> {
  return request<DocumentMetadata>(`/documents/${id}`)
}

/** 分页响应结构 —— 对应后端 {@code /versions} 端点返回体 */
export interface VersionPage {
  items: DocumentVersionInfo[]
  total: number
  page: number
  pageSize: number
}

/** 列出文档版本（默认 page=1, pageSize=20；pageSize 上限 100） */
export async function listVersions(id: string, page = 1, pageSize = 20): Promise<VersionPage> {
  const q = new URLSearchParams({ page: String(page), pageSize: String(pageSize) })
  return request<VersionPage>(`/documents/${id}/versions?${q.toString()}`)
}

/** 获取 from → to 的 diff（diffJson 为序列化后的字符串，调用方需用 parseDiffJson 解析） */
export async function getDiff(
  id: string,
  from: number,
  to: number,
): Promise<{ diffJson: string }> {
  return request<{ diffJson: string }>(`/documents/${id}/diff?from=${from}&to=${to}`)
}

/** 提交工作副本 —— overwrite 覆盖原路径，saveAs 另存到新路径 */
export async function commit(
  id: string,
  target: 'overwrite' | 'saveAs',
  saveAsPath?: string,
): Promise<CommitResult> {
  return request<CommitResult>(`/documents/${id}/commit`, {
    method: 'POST',
    body: JSON.stringify({ target, saveAsPath }),
  })
}

/** 回滚到指定版本（生成新版本，不抹掉历史链） */
export async function rollback(
  id: string,
  version: number,
): Promise<{ newVersion: number; summary: string }> {
  return request<{ newVersion: number; summary: string }>(`/documents/${id}/rollback`, {
    method: 'POST',
    body: JSON.stringify({ version }),
  })
}

/** 丢弃工作副本（删除 working 目录 + 版本链 + session_documents 行） */
export async function discardWorkingCopy(id: string): Promise<void> {
  await request<void>(`/documents/${id}/working-copy`, { method: 'DELETE' })
}

/** 将后端返回的 diffJson 字符串解析为 DiffPayload；空串/非法 JSON 返回 null */
export function parseDiffJson(json: string): DiffPayload | null {
  if (!json || !json.trim()) return null
  try {
    return JSON.parse(json) as DiffPayload
  } catch {
    return null
  }
}
