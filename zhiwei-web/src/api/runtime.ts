/**
 * 捆绑 Python 运行时（Code Sandbox）REST 封装。
 *
 * <p>沿用项目现有的 API client 模式（见 {@code src/api/project.ts}）：
 * 通过 {@code getApiOrigin() + '/api'} 作为基地址，本地 {@code request<T>} 自动
 * 解包后端 {@code ApiResponse<T>} 响应结构（{code, message, data}），
 * 失败时抛出结构化错误对象。</p>
 *
 * <p>SSE 进度流端点 {@link runtimeApi.installProgressUrl} 返回包含 origin 的绝对
 * URL，便于 Tauri 桌面端直接喂给 {@code EventSource}（浏览器环境下 origin 为空，
 * 走 Vite proxy 转发）。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
import { getApiOrigin } from '@/api/config'

const getBase = () => getApiOrigin() + '/api'

/** 运行时状态枚举 — 与后端 {@code RuntimeStatus} sealed interface 5 个 permits 一一对应 */
export type RuntimeStatusKind =
  | 'NOT_INSTALLED'
  | 'DISABLED'
  | 'INSTALLING'
  | 'READY'
  | 'INSTALL_FAILED'

/**
 * 运行时状态 DTO — 后端将 sealed interface 序列化为带 {@code status} 判别符的扁平结构。
 *
 * <p>各状态对应字段：
 * <ul>
 *   <li>{@code READY}：{@code version} + {@code diskBytes}</li>
 *   <li>{@code INSTALLING}：{@code phase} + {@code bytesDownloaded} + {@code totalBytes} + {@code percent}</li>
 *   <li>{@code INSTALL_FAILED}：{@code reason}</li>
 *   <li>{@code NOT_INSTALLED} / {@code DISABLED}：仅 {@code status}</li>
 * </ul>
 * 字段全部声明为可选以兼容多状态共用同一结构。</p>
 */
export interface RuntimeStatus {
  status: RuntimeStatusKind
  version?: string
  diskBytes?: number
  phase?: string
  bytesDownloaded?: number
  totalBytes?: number
  percent?: number
  reason?: string
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
  // 自动解包 ApiResponse 结构：后端 disable / enable / uninstall 可能返回 data=null
  if (json && typeof json === 'object' && 'code' in json && 'data' in json) {
    return (json.data ?? undefined) as T
  }
  return json as T
}

/**
 * 捆绑 Python 运行时管理 API。
 *
 * <p>状态机：NOT_INSTALLED → INSTALLING → READY ↔ DISABLED；
 * 任意阶段失败进入 INSTALL_FAILED，调用 {@link install} 可再次重试。</p>
 */
export const runtimeApi = {
  /** 查询当前运行时状态。 */
  status: () => request<RuntimeStatus>('/runtime/python/status'),

  /**
   * 触发安装。后端异步执行，立即返回；进度通过 SSE 流（见
   * {@link installProgressUrl}）推送，前端调用方应在 install 之后立即
   * 订阅进度流以避免错过事件。
   */
  install: () => request<void>('/runtime/python/install', { method: 'POST' }),

  /** 卸载运行时（删除文件，状态回到 NOT_INSTALLED）。 */
  uninstall: () => request<void>('/runtime/python/uninstall', { method: 'POST' }),

  /** 禁用运行时（保留文件，状态切到 DISABLED；不影响磁盘占用）。 */
  disable: () => request<void>('/runtime/python/disable', { method: 'POST' }),

  /** 启用运行时（从 DISABLED 切回 READY）。 */
  enable: () => request<void>('/runtime/python/enable', { method: 'POST' }),

  /**
   * 安装进度 SSE 流的完整 URL（含 origin 前缀），供 {@code EventSource} 直接消费。
   *
   * <p>事件类型：
   * <ul>
   *   <li>{@code progress} — 数据为 {@link RuntimeStatus} JSON</li>
   *   <li>{@code failed} — 数据为字符串 reason，emitter 关闭</li>
   * </ul>
   */
  installProgressUrl: () => `${getBase()}/runtime/install/progress`,
}
