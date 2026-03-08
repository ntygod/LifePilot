import type { InstallResult, PageResult, SkillPackage, UpdateInfo } from '@/types'

// API 基础路径（开发环境通过 Vite proxy 转发）
const BASE = '/api/marketplace'

/** 统一 HTTP 请求封装 */
async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${url}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  })
  if (!res.ok) {
    let error: { code: number; message: string; timestamp: string }
    try {
      error = await res.json()
    } catch {
      error = { code: res.status, message: res.statusText, timestamp: new Date().toISOString() }
    }
    throw error
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  if (!text || text.trim() === '') return undefined as T
  return JSON.parse(text) as T
}

/** 扩展市场 API */
export const marketplaceApi = {
  /** 获取扩展列表（分页 + 搜索 + 标签筛选 + 类型筛选） */
  getSkills(params: { type?: string; search?: string; tag?: string; page?: number; size?: number } = {}): Promise<PageResult<SkillPackage>> {
    const query = new URLSearchParams()
    if (params.type) query.append('type', params.type)
    if (params.search) query.append('search', params.search)
    if (params.tag) query.append('tag', params.tag)
    query.append('page', String(params.page ?? 0))
    query.append('size', String(params.size ?? 20))
    return request(`/extensions?${query.toString()}`)
  },

  /** 获取单个扩展详情 */
  getSkill(id: string): Promise<SkillPackage> {
    return request(`/extensions/${id}`)
  },

  /** 安装扩展 */
  install(id: string, confirmHighRisk = false): Promise<InstallResult> {
    return request(`/extensions/${id}/install`, {
      method: 'POST',
      body: JSON.stringify({ confirmHighRisk })
    })
  },

  /** 卸载扩展 */
  uninstall(id: string): Promise<void> {
    return request(`/extensions/${id}`, { method: 'DELETE' })
  },

  /** 刷新索引 */
  refreshIndex(): Promise<void> {
    return request('/index/refresh', { method: 'POST' })
  },

  /** 获取可用更新列表 */
  getUpdates(): Promise<UpdateInfo[]> {
    return request('/updates')
  },

  /** 升级扩展 */
  upgrade(id: string): Promise<InstallResult> {
    return request(`/extensions/${id}/upgrade`, { method: 'POST' })
  }
}
