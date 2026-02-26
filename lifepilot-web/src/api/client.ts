import type {
  ChatResponse,
  ChatSession,
  CreateKbRequest,
  ErrorResponse,
  KbDocument,
  KnowledgeBase,
  McpServer,
  McpTool,
  Message,
  PageResult,
  SkillDetail,
  SkillSummary,
  TraceDetail,
  TraceItem,
  TraceStep,
  UserSettings,
  WorkflowDetail,
  WorkflowExecution,
  WorkflowItem
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

// ========== 模块 19: 功能页面 API ==========

/** 知识库管理 API */
export const knowledgeBaseApi = {
  list(): Promise<KnowledgeBase[]> {
    return request('/knowledge-bases')
  },
  create(req: CreateKbRequest): Promise<KnowledgeBase> {
    return request('/knowledge-bases', {
      method: 'POST',
      body: JSON.stringify(req)
    })
  },
  get(id: string): Promise<KnowledgeBase> {
    return request(`/knowledge-bases/${id}`)
  },
  delete(id: string): Promise<void> {
    return request(`/knowledge-bases/${id}`, { method: 'DELETE' })
  },
  listDocuments(kbId: string): Promise<KbDocument[]> {
    return request(`/knowledge-bases/${kbId}/documents`)
  },
  // 文件上传使用 FormData，不设置 Content-Type
  async uploadDocument(kbId: string, file: File): Promise<KbDocument> {
    const formData = new FormData()
    formData.append('file', file)
    const res = await fetch(`${BASE}/knowledge-bases/${kbId}/documents`, {
      method: 'POST',
      body: formData
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
    return res.json()
  },
  deleteDocument(kbId: string, docId: string): Promise<void> {
    return request(`/knowledge-bases/${kbId}/documents/${docId}`, { method: 'DELETE' })
  }
}

/** Skill 管理 API */
export const skillApi = {
  list(): Promise<SkillSummary[]> {
    return request('/skills')
  },
  get(id: string): Promise<SkillDetail> {
    return request(`/skills/${id}`)
  },
  unregister(id: string): Promise<void> {
    return request(`/skills/${id}`, { method: 'DELETE' })
  }
}

/** MCP Server 管理 API */
export const mcpApi = {
  listServers(): Promise<McpServer[]> {
    return request('/mcp/servers')
  },
  getServer(name: string): Promise<McpServer> {
    return request(`/mcp/servers/${name}`)
  },
  connect(name: string): Promise<void> {
    return request(`/mcp/servers/${name}/connect`, { method: 'POST' })
  },
  disconnect(name: string): Promise<void> {
    return request(`/mcp/servers/${name}/disconnect`, { method: 'POST' })
  },
  listTools(name: string): Promise<McpTool[]> {
    return request(`/mcp/servers/${name}/tools`)
  }
}

/** 轨迹查询 API */
export const traceApi = {
  list(page = 0, size = 20): Promise<PageResult<TraceItem>> {
    return request(`/traces?page=${page}&size=${size}`)
  },
  get(id: string): Promise<TraceDetail> {
    return request(`/traces/${id}`)
  },
  getSteps(id: string): Promise<TraceStep[]> {
    return request(`/traces/${id}/steps`)
  }
}

/** 工作流管理 API */
export const workflowApi = {
  list(): Promise<WorkflowItem[]> {
    return request('/workflows')
  },
  get(id: string): Promise<WorkflowDetail> {
    return request(`/workflows/${id}`)
  },
  enable(id: string): Promise<void> {
    return request(`/workflows/${id}/enable`, { method: 'POST' })
  },
  disable(id: string): Promise<void> {
    return request(`/workflows/${id}/disable`, { method: 'POST' })
  },
  trigger(id: string, inputs?: Record<string, unknown>): Promise<WorkflowExecution> {
    return request(`/workflows/${id}/trigger`, {
      method: 'POST',
      body: JSON.stringify({ inputs })
    })
  },
  listExecutions(id: string): Promise<WorkflowExecution[]> {
    return request(`/workflows/${id}/executions`)
  }
}
