import type {
  ChatResponse,
  ChatSession,
  CreateKbRequest,
  ErrorResponse,
  KbDocument,
  KnowledgeBase,
  McpServer,
  McpServerConfig,
  McpTool,
  Message,
  PageResult,
  SkillDetail,
  SkillSummary,
  ToolDetail,
  ToolSummary,
  ToolTestRequest,
  ToolTestResponse,
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

/** 网络错误类 */
export class NetworkError extends Error {
  constructor(message = '网络连接失败') {
    super(message)
    this.name = 'NetworkError'
  }
}

/** 超时错误类 */
export class TimeoutError extends Error {
  constructor(message = '请求超时') {
    super(message)
    this.name = 'TimeoutError'
  }
}

/** 统一 HTTP 请求封装，非 2xx 抛出包含 ErrorResponse 的异常 */
async function request<T>(url: string, options?: RequestInit): Promise<T> {
  try {
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
    // 检查响应体是否为空
    const contentType = res.headers.get('content-type')
    if (!contentType || !contentType.includes('application/json')) {
      // 如果不是 JSON，尝试读取文本
      const text = await res.text()
      if (!text || text.trim() === '') {
        return undefined as T
      }
      // 尝试解析为 JSON
      try {
        return JSON.parse(text) as T
      } catch {
        throw { code: res.status, message: '响应格式错误', timestamp: new Date().toISOString() }
      }
    }
    const text = await res.text()
    if (!text || text.trim() === '') {
      return undefined as T
    }
    try {
      return JSON.parse(text) as T
    } catch (e) {
      console.error('JSON 解析失败:', e, '响应内容:', text)
      throw { code: res.status, message: '响应解析失败', timestamp: new Date().toISOString() }
    }
  } catch (error) {
    if (error instanceof TypeError && error.message.includes('fetch')) {
      throw new NetworkError()
    }
    throw error
  }
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

import type {
  LlmProviderDetail
} from '@/types'

/** LLM Provider 信息（兼容旧接口） */
export interface LlmProvider {
  id: string
  type: string
  modelName: string
  displayName?: string
  capabilities?: string[]
  priority?: number
  costPerInputToken?: number
  costPerOutputToken?: number
  scenes?: string[]
  maxContextWindow?: number
  supportsStreaming?: boolean
  enabled?: boolean
  healthy?: boolean
  apiUrl?: string
  timeoutSeconds?: number
  isPreset?: boolean
  description?: string
  embeddingDimension?: number
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
  },

  /** 获取可用的 LLM Provider 列表（包含详细信息） */
  getProviders(): Promise<LlmProvider[]> {
    return request('/settings/providers')
  },

  /** 获取指定 Provider 的详细信息 */
  getProviderDetail(providerId: string): Promise<LlmProviderDetail> {
    return request(`/settings/providers/${providerId}`)
  },

  /** 获取所有 Provider 的健康状态 */
  getProviderHealth(): Promise<Record<string, boolean>> {
    return request('/settings/providers/health')
  }
}

/** LLM Provider 管理 API */
export const llmProviderApi = {
  /** 获取所有 Provider（包括已禁用） */
  listProviders(): Promise<LlmProvider[]> {
    return request('/llm-providers')
  },

  /** 获取所有已启用的 Provider */
  listEnabledProviders(): Promise<LlmProvider[]> {
    return request('/llm-providers/enabled')
  },

  /** 获取所有预设置的 Provider */
  listPresets(): Promise<LlmProvider[]> {
    return request('/llm-providers/presets')
  },

  /** 根据 ID 获取 Provider */
  getProvider(id: string): Promise<LlmProvider> {
    return request(`/llm-providers/${id}`)
  },

  /** 创建或更新 Provider */
  saveProvider(provider: CreateProviderRequest): Promise<LlmProvider> {
    return request('/llm-providers', {
      method: 'POST',
      body: JSON.stringify(provider)
    })
  },

  /** 更新 Provider（部分更新） */
  updateProvider(id: string, provider: UpdateProviderRequest): Promise<LlmProvider> {
    return request(`/llm-providers/${id}`, {
      method: 'PUT',
      body: JSON.stringify(provider)
    })
  },

  /** 删除 Provider（仅删除非预设置的） */
  deleteProvider(id: string): Promise<void> {
    return request(`/llm-providers/${id}`, {
      method: 'DELETE'
    })
  },

  /** 获取 Provider 健康状态 */
  getProviderHealth(id: string): Promise<{ healthy: boolean }> {
    return request(`/llm-providers/${id}/health`)
  }
}

/** 创建 Provider 请求 */
export interface CreateProviderRequest {
  id: string
  type: string
  apiUrl: string
  apiKey?: string
  modelName: string
  timeoutSeconds?: number
  priority?: number
  scenes?: string[]
  capabilities?: string[]
  enabled?: boolean
  costPerInputToken?: number
  costPerOutputToken?: number
  maxContextWindow?: number
  embeddingDimension?: number
  supportsStreaming?: boolean
  displayName?: string
  description?: string
}

/** 更新 Provider 请求（所有字段可选） */
export interface UpdateProviderRequest {
  type?: string
  apiUrl?: string
  apiKey?: string
  modelName?: string
  timeoutSeconds?: number
  priority?: number
  scenes?: string[]
  capabilities?: string[]
  enabled?: boolean
  costPerInputToken?: number
  costPerOutputToken?: number
  maxContextWindow?: number
  embeddingDimension?: number
  supportsStreaming?: boolean
  displayName?: string
  description?: string
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
  create(data: Partial<SkillDetail>): Promise<SkillDetail> {
    return request('/skills', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },
  update(id: string, data: Partial<SkillDetail>): Promise<SkillDetail> {
    return request(`/skills/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
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
  createServer(data: { name: string; config: McpServerConfig }): Promise<McpServer> {
    return request('/mcp/servers', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },
  updateServer(name: string, data: { config: McpServerConfig }): Promise<McpServer> {
    return request(`/mcp/servers/${name}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
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
  create(data: Partial<WorkflowDetail>): Promise<WorkflowDetail> {
    return request('/workflows', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },
  update(id: string, data: Partial<WorkflowDetail>): Promise<WorkflowDetail> {
    return request(`/workflows/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
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

/** Tool 管理 API */
export const toolApi = {
  list(params?: { source?: string; status?: string; name?: string }): Promise<ToolSummary[]> {
    const query = new URLSearchParams()
    if (params?.source) query.append('source', params.source)
    if (params?.status) query.append('status', params.status)
    if (params?.name) query.append('name', params.name)
    const queryString = query.toString()
    return request(`/tools${queryString ? `?${queryString}` : ''}`)
  },
  get(id: string): Promise<ToolDetail> {
    return request(`/tools/${id}`)
  },
  create(data: {
    id: string
    name: string
    description?: string
    inputSchema?: Record<string, any>
    outputSchema?: Record<string, any>
    budget?: {
      timeoutSeconds?: number
      maxRetries?: number
      maxCostCents?: number
    }
    riskLevel?: string
    idempotent?: boolean
    tags?: string[]
  }): Promise<ToolDetail> {
    return request('/tools', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },
  update(id: string, data: {
    name?: string
    description?: string
    inputSchema?: Record<string, any>
    outputSchema?: Record<string, any>
    budget?: {
      timeoutSeconds?: number
      maxRetries?: number
      maxCostCents?: number
    }
    riskLevel?: string
    idempotent?: boolean
    tags?: string[]
  }): Promise<ToolDetail> {
    return request(`/tools/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  },
  delete(id: string): Promise<void> {
    return request(`/tools/${id}`, { method: 'DELETE' })
  },
  enable(id: string): Promise<void> {
    return request(`/tools/${id}/enable`, { method: 'POST' })
  },
  disable(id: string): Promise<void> {
    return request(`/tools/${id}/disable`, { method: 'POST' })
  },
  test(req: ToolTestRequest): Promise<ToolTestResponse> {
    // 统一使用 input 字段，如果提供了 arguments 则转换为 input
    const requestBody = {
      toolId: req.toolId,
      input: req.input ?? req.arguments ?? {}
    }
    return request('/tools/test', {
      method: 'POST',
      body: JSON.stringify(requestBody)
    })
  },
  getUsage(id: string): Promise<any> {
    return request(`/tools/${id}/usage`)
  }
}
