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

/** Skill 市场 API */
export const marketplaceApi = {
  /** 获取 Skill 列表（分页 + 搜索 + 标签筛选） */
  getSkills(params: { search?: string; tag?: string; page?: number; size?: number } = {}): Promise<PageResult<SkillPackage>> {
    const query = new URLSearchParams()
    if (params.search) query.append('search', params.search)
    if (params.tag) query.append('tag', params.tag)
    query.append('page', String(params.page ?? 0))
    query.append('size', String(params.size ?? 20))
    return request(`/skills?${query.toString()}`)
  },

  /** 获取单个 Skill 详情 */
  getSkill(id: string): Promise<SkillPackage> {
    return request(`/skills/${id}`)
  },

  /** 安装 Skill */
  install(id: string, confirmHighRisk = false): Promise<InstallResult> {
    return request(`/skills/${id}/install`, {
      method: 'POST',
      body: JSON.stringify({ confirmHighRisk })
    })
  },

  /** 卸载 Skill */
  uninstall(id: string): Promise<void> {
    return request(`/skills/${id}`, { method: 'DELETE' })
  },

  /** 刷新索引 */
  refreshIndex(): Promise<void> {
    return request('/index/refresh', { method: 'POST' })
  },

  /** 获取可用更新列表 */
  getUpdates(): Promise<UpdateInfo[]> {
    return request('/updates')
  },

  /** 升级 Skill */
  upgrade(id: string): Promise<InstallResult> {
    return request(`/skills/${id}/upgrade`, { method: 'POST' })
  }
}
