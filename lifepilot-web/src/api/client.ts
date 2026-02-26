import type {
  ChatResponse,
  ChatSession,
  ErrorResponse,
  Message,
  UserSettings
} from '@/types'

// API 基础路径（开发环境通过 Vite proxy 转发）
const BASE = '/api'

/** 统一 HTTP 请求封装，非 2xx 抛出包含 ErrorResponse 的异常 */
async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${url}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  })
  if (!res.ok) {
    let error: ErrorResponse
    try {
      error = await res.json()
    } catch {
      error = { code: res.status, message: res.statusText, timestamp: new Date().toISOString() }
    }
    throw error
  }
  // 204 No Content 无响应体
  if (res.status === 204) return undefined as T
  return res.json()
}

/** 对话相关 API */
export const chatApi = {
  /** 非流式发送消息 */
  sendMessage(content: string, sessionId?: string): Promise<ChatResponse> {
    return request('/chat/messages', {
      method: 'POST',
      body: JSON.stringify({ content, sessionId })
    })
  },

  /**
   * 流式发送消息，返回 ReadableStream 用于 SSE 解析。
   * 调用方通过 ReadableStream 逐行读取 SSE 事件。
   */
  async sendMessageStream(
    content: string,
    sessionId?: string,
    signal?: AbortSignal
  ): Promise<ReadableStream<Uint8Array>> {
    const res = await fetch(`${BASE}/chat/messages/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content, sessionId }),
      signal
    })
    if (!res.ok || !res.body) {
      throw { code: res.status, message: '流式请求失败', timestamp: new Date().toISOString() }
    }
    return res.body
  },

  /** 获取会话列表 */
  listSessions(): Promise<ChatSession[]> {
    return request('/chat/sessions')
  },

  /** 获取会话历史消息 */
  getSessionMessages(sessionId: string): Promise<Message[]> {
    return request(`/chat/sessions/${sessionId}/messages`)
  },

  /** 删除会话 */
  deleteSession(sessionId: string): Promise<void> {
    return request(`/chat/sessions/${sessionId}`, { method: 'DELETE' })
  },

  /** A2UI 信号回传 */
  sendSignal(name: string, payload: Record<string, unknown>, sessionId: string): Promise<unknown> {
    return request('/chat/signals', {
      method: 'POST',
      body: JSON.stringify({ name, payload, sessionId })
    })
  }
}

/** 设置相关 API */
export const settingsApi = {
  /** 获取用户设置 */
  getSettings(): Promise<UserSettings> {
    return request('/settings')
  },

  /** 更新用户设置 */
  updateSettings(settings: UserSettings): Promise<UserSettings> {
    return request('/settings', {
      method: 'PUT',
      body: JSON.stringify(settings)
    })
  }
}
