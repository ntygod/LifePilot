/**
 * 项目（Project）REST 封装 —— Plan 1 Task 17：列表 / 详情 / 创建 / 更新 / 删除。
 *
 * 项目现有的 API client 采用 fetch + ApiResponse 自动解包的模式（见
 * {@code src/api/documents.ts}）。本文件沿用同一模式：通过 `request<T>` 发起请求，
 * 自动解包 `{ code, message, data }` 响应结构；不新建 axios 实例。
 *
 * @author zsg
 * @since 2026-04-23
 */
import { getApiOrigin } from '@/api/config'

const getBase = () => getApiOrigin() + '/api'

/** 项目隔离模式 —— ISOLATED：项目内记忆独立；SHARED：与默认空间共享 */
export type ProjectIsolation = 'ISOLATED' | 'SHARED'

/**
 * 项目 DTO —— 对应后端 {@code ProjectResponse}。
 *
 * {@code createdAt} / {@code updatedAt} 为 ISO 8601 字符串（Java Instant 序列化结果）。
 */
export interface ProjectDto {
  id: string
  name: string
  instructions: string
  isolation: ProjectIsolation
  memorySpaceId: string
  createdAt: string
  updatedAt: string
}

/**
 * 创建项目请求 —— 对应后端 {@code CreateProjectRequest}。
 *
 * {@code instructions} 缺省由后端规范化为空串；{@code isolation} 缺省由后端使用
 * {@link ProjectIsolation.defaultValue}（当前为 ISOLATED）。
 */
export interface CreateProjectRequest {
  name: string
  instructions?: string
  isolation?: ProjectIsolation
}

/** 更新项目请求 —— 字段语义同创建；未提供字段由后端保留原值 */
export type UpdateProjectRequest = CreateProjectRequest

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
    let parsed: { code?: number; message?: string } | null = null
    if (text) {
      try {
        parsed = JSON.parse(text)
      } catch {
        parsed = null
      }
    }
    throw {
      code: parsed?.code ?? res.status,
      message: parsed?.message ?? text?.trim() ?? res.statusText ?? '请求失败',
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
      throw {
        code: res.status,
        message: '响应格式错误',
        timestamp: new Date().toISOString(),
      }
    }
  }
  const json = JSON.parse(body) as { code?: number; message?: string; data?: T }
  // 自动解包 ApiResponse 结构：后端 delete 返回 data=null，此处直接透传为 undefined
  if (json && typeof json === 'object' && 'code' in json && 'data' in json) {
    return (json.data ?? undefined) as T
  }
  return json as T
}

/** 列出所有项目（按创建时间倒序，由后端决定排序） */
export async function listProjects(): Promise<ProjectDto[]> {
  return request<ProjectDto[]>('/projects')
}

/** 获取单个项目详情；不存在时后端返回 404，调用方捕获错误 */
export async function getProject(id: string): Promise<ProjectDto> {
  return request<ProjectDto>(`/projects/${encodeURIComponent(id)}`)
}

/** 创建项目 */
export async function createProject(req: CreateProjectRequest): Promise<ProjectDto> {
  return request<ProjectDto>('/projects', {
    method: 'POST',
    body: JSON.stringify(req),
  })
}

/** 更新项目（name / instructions / isolation 可选） */
export async function updateProject(
  id: string,
  req: UpdateProjectRequest,
): Promise<ProjectDto> {
  return request<ProjectDto>(`/projects/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(req),
  })
}

/** 删除项目，级联清理由后端 Service 统一实现（会话 / 记忆实体关系 / memory_space） */
export async function deleteProject(id: string): Promise<void> {
  await request<void>(`/projects/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  })
}
