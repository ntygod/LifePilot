import type { ExtensionInstallation, ExtensionPackage, InstallResult, PagedResult } from '@/types'
import { getApiOrigin } from '@/api/config'

// API 基础路径（运行时求值，Tauri 桌面端使用绝对路径，浏览器环境通过 Vite proxy 转发）
const getBase = () => getApiOrigin() + '/api/marketplace'

/** 统一 HTTP 请求封装 */
async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${getBase()}${url}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  })
  if (!res.ok) {
    const text = await res.text()
    let error: { code: number; message: string; timestamp: string }
    try {
      error = text
        ? JSON.parse(text) as { code: number; message: string; timestamp: string }
        : { code: res.status, message: res.statusText, timestamp: new Date().toISOString() }
    } catch {
      error = {
        code: res.status,
        message: text?.trim() || res.statusText || '请求失败',
        timestamp: new Date().toISOString(),
      }
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
  getSkills(params: { type?: string; search?: string; tag?: string; page?: number; size?: number } = {}): Promise<PagedResult<ExtensionPackage>> {
    const query = new URLSearchParams()
    if (params.type) query.append('type', params.type)
    if (params.search) query.append('search', params.search)
    if (params.tag) query.append('tag', params.tag)
    query.append('page', String(params.page ?? 0))
    query.append('size', String(params.size ?? 20))
    return request(`/extensions?${query.toString()}`)
  },

  /** 获取单个扩展详情 */
  getSkill(id: string): Promise<ExtensionPackage> {
    return request(`/extensions/${id}`)
  },

  /** 获取已安装扩展的本地安装快照 */
  getInstallation(id: string): Promise<ExtensionInstallation> {
    return request(`/extensions/${id}/installation`)
  },

  /** 构造已安装扩展资产的文件 URL */
  getInstallationAssetUrl(id: string, relativePath: string): string {
    return `${getBase()}/extensions/${encodeURIComponent(id)}/assets/file?path=${encodeURIComponent(relativePath)}`
  },

  /** 读取已安装扩展的文本资产 */
  async getInstallationAssetText(id: string, relativePath: string): Promise<string> {
    const res = await fetch(this.getInstallationAssetUrl(id, relativePath))
    if (!res.ok) {
      const text = await res.text()
      throw {
        code: res.status,
        message: text?.trim() || '读取插件资产失败',
        timestamp: new Date().toISOString(),
      }
    }
    return res.text()
  },

  /** 安装扩展 — confirmHighRisk 作为查询参数传递 */
  install(id: string, confirmHighRisk = false): Promise<InstallResult> {
    const query = confirmHighRisk ? '?confirmHighRisk=true' : ''
    return request(`/extensions/${id}/install${query}`, { method: 'POST' })
  },

  /** 卸载扩展 */
  uninstall(id: string): Promise<void> {
    return request(`/extensions/${id}`, { method: 'DELETE' })
  },

  /** 刷新索引 */
  refreshIndex(): Promise<void> {
    return request('/index/refresh', { method: 'POST' })
  },

  /** 获取可用更新列表（返回 ExtensionPackage 数组） */
  getUpdates(): Promise<ExtensionPackage[]> {
    return request('/updates')
  },

  /** 升级扩展 — confirmHighRisk 作为查询参数传递 */
  upgrade(id: string, confirmHighRisk = false): Promise<InstallResult> {
    const query = confirmHighRisk ? '?confirmHighRisk=true' : ''
    return request(`/extensions/${id}/upgrade${query}`, { method: 'POST' })
  }
}
