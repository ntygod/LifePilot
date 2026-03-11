import type {
  ChatAttachment,
  ChatResponse,
  ChatSession,
  CreateKbRequest,
  DocumentChunk,
  ErrorResponse,
  KbDocument,
  KbStats,
  KnowledgeBase,
  McpServer,
  McpServerConfig,
  McpTool,
  Message,
  PageResult,
  ProcessingLog,
  SkillDetail,
  SkillSummary,
  TestRetrievalResult,
  AgentDetail,
  AgentSummary,
  ToolDetail,
  ToolSummary,
  ToolTestRequest,
  ToolTestResponse,
  TraceDetail,
  TraceItem,
  TraceStep,
  UserSettings,
  WorkflowDetail,
  WorkflowEvent,
  WorkflowExecution,
  WorkflowItem,
  StepLog,
  ApprovalRequest,
  UsageStats,
  AgentStats,
  KnowledgeBaseStats,
  ToolStats,
  OverviewStats,
  ToolUsageStats,
  EvaluationResult,
  TokenConsumptionStats,
  ContextPreviewResponse,
  TokenBudgetData,
  DependencyGraphResponse,
  ToolCallStats,
  ToolDailyTrend,
  ToolAnalyticsResponse,
  ErrorTrendDaily,
  McpConnectionLog
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
  sendMessage(
    content: string,
    sessionId?: string,
    attachmentIds?: string[]
  ): Promise<ChatResponse> {
    return request('/chat/messages', {
      method: 'POST',
      body: JSON.stringify({ content, sessionId, attachmentIds })
    })
  },

  /**
   * 流式发送消息，返回 ReadableStream 用于 SSE 解析。
   * 调用方通过 ReadableStream 逐行读取 SSE 事件。
   */
  async sendMessageStream(
    content: string,
    sessionId?: string,
    attachmentIds?: string[],
    signal?: AbortSignal
  ): Promise<ReadableStream<Uint8Array>> {
    const res = await fetch(`${BASE}/chat/messages/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content, sessionId, attachmentIds }),
      signal
    })
    if (!res.ok || !res.body) {
      throw { code: res.status, message: '流式请求失败', timestamp: new Date().toISOString() }
    }
    return res.body
  },

  /** 创建会话 */
  createSession(title?: string): Promise<ChatSession> {
    return request('/chat/sessions', {
      method: 'POST',
      body: JSON.stringify({ title })
    })
  },

  /** 获取会话列表 */
  listSessions(): Promise<ChatSession[]> {
    return request('/chat/sessions')
  },

  /** 获取会话历史消息 */
  getSessionMessages(sessionId: string): Promise<Message[]> {
    return request(`/chat/sessions/${sessionId}/messages`)
  },

  /** 更新会话（标题、置顶、归档等） */
  updateSession(sessionId: string, updates: { title?: string; pinned?: boolean; archived?: boolean }): Promise<ChatSession> {
    return request(`/chat/sessions/${sessionId}`, {
      method: 'PATCH',
      body: JSON.stringify(updates)
    })
  },

  /**
   * 更新会话配置（模型/温度/最大Tokens/关联知识库）。
   *
   * 注意：知识库关联会影响后端在生成回答前的检索上下文注入（若已启用）。
   */
  updateSessionConfig(
    sessionId: string,
    config: {
      modelId?: string
      temperature?: number
      maxTokens?: number
      knowledgeBaseIds?: string[]
    }
  ): Promise<void> {
    return request(`/chat/sessions/${sessionId}/config`, {
      method: 'PATCH',
      body: JSON.stringify(config)
    })
  },

  /** 删除会话 */
  deleteSession(sessionId: string): Promise<void> {
    return request(`/chat/sessions/${sessionId}`, { method: 'DELETE' })
  },

  /** 清空会话消息 */
  clearSessionMessages(sessionId: string): Promise<void> {
    return request(`/chat/sessions/${sessionId}/clear`, { method: 'POST' })
  },

  /** 提交消息反馈（点赞/点踩） */
  submitFeedback(
    messageId: string,
    type: 'like' | 'dislike',
    feedback?: string
  ): Promise<void> {
    return request(`/chat/messages/${messageId}/feedback`, {
      method: 'POST',
      body: JSON.stringify({ type, feedback })
    })
  },

  /** 分叉会话（从指定消息处创建新会话） */
  forkSession(
    sessionId: string,
    fromMessageId?: string
  ): Promise<ChatSession> {
    return request(`/chat/sessions/${sessionId}/fork`, {
      method: 'POST',
      body: JSON.stringify({ fromMessageId })
    })
  },

  /** A2UI 信号回传 */
  sendSignal(name: string, payload: Record<string, unknown>, sessionId: string): Promise<unknown> {
    return request('/chat/signals', {
      method: 'POST',
      body: JSON.stringify({ name, payload, sessionId })
    })
  },

  /**
   * 上传单个消息附件（图片/文件）。
   *
   * 使用 multipart/form-data，将文件二进制交给后端存储，返回文件 ID 和访问 URL 等信息。
   */
  async uploadAttachment(file: File, sessionId?: string): Promise<ChatAttachment> {
    const form = new FormData()
    form.append('file', file)
    if (sessionId) {
      form.append('sessionId', sessionId)
    }

    const res = await fetch(`${BASE}/chat/messages/upload`, {
      method: 'POST',
      body: form
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

    const data = (await res.json()) as {
      fileId: string
      url: string
      filename: string
      size: number
      type: string
    }

    const isImage = data.type.startsWith('image/')

    return {
      fileId: data.fileId,
      url: data.url,
      filename: data.filename,
      size: data.size,
      type: data.type,
      isImage
    }
  }
}

import type {
  LlmProviderDetail
} from '@/types'

// 导出 LlmProviderDetail 类型（向后兼容）
export type { LlmProviderDetail }

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
  },
  getDocumentChunks(kbId: string, docId: string, offset = 0, limit = 100): Promise<DocumentChunk[]> {
    return request(`/knowledge-bases/${kbId}/documents/${docId}/chunks?offset=${offset}&limit=${limit}`)
  },
  retryDocument(kbId: string, docId: string): Promise<void> {
    return request(`/knowledge-bases/${kbId}/documents/${docId}/retry`, { method: 'POST' })
  },
  rechunkDocument(kbId: string, docId: string): Promise<void> {
    return request(`/knowledge-bases/${kbId}/documents/${docId}/rechunk`, { method: 'POST' })
  },
  downloadDocument(kbId: string, docId: string): Promise<Blob> {
    return fetch(`${BASE}/knowledge-bases/${kbId}/documents/${docId}/download`).then(res => {
      if (!res.ok) throw new Error('下载失败')
      return res.blob()
    })
  },
  getStats(kbId: string): Promise<KbStats> {
    return request(`/knowledge-bases/${kbId}/stats`)
  },
  // 以下接口待后端实现
  getDocumentLogs(kbId: string, docId: string): Promise<ProcessingLog[]> {
    return request<{ logs: Array<{ timestamp: string; level: string; message: string; details?: Record<string, unknown> }> }>(
      `/knowledge-bases/${kbId}/documents/${docId}/logs`
    ).then(resp => {
      if (!resp || !resp.logs) return []
      return resp.logs.map((log, index) => ({
        id: `${docId}-log-${index}`,
        documentId: docId,
        stage: (log.level === 'ERROR' ? 'ERROR' : 'COMPLETE') as ProcessingLog['stage'],
        message: log.message,
        timestamp: log.timestamp,
        error: log.level === 'ERROR' ? (log.details?.error as string) : undefined
      }))
    })
  },
  testRetrieval(kbId: string, query: string): Promise<TestRetrievalResult> {
    return request(`/knowledge-bases/${kbId}/test-retrieval`, {
      method: 'POST',
      body: JSON.stringify({ query })
    })
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
  },
  enable(id: string): Promise<void> {
    return request(`/skills/${id}/enable`, { method: 'POST' })
  },
  disable(id: string): Promise<void> {
    return request(`/skills/${id}/disable`, { method: 'POST' })
  },
  test(id: string, data: { userMessage: string; context?: Record<string, unknown> }): Promise<any> {
    return request(`/skills/${id}/test`, {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },
  /** 获取 Skill Markdown 定义 */
  getSkillMarkdown(skillId: string): Promise<string> {
    return fetch(`${BASE}/skills/${skillId}/markdown`).then(res => {
      if (!res.ok) throw { code: res.status, message: '获取失败', timestamp: new Date().toISOString() }
      return res.text()
    })
  },
  /** 更新 Skill Markdown 定义 */
  updateSkillMarkdown(skillId: string, content: string): Promise<void> {
    return request(`/skills/${skillId}/markdown`, {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain' },
      body: content
    } as any)
  }
}

/** MCP Server 管理 API */
export const mcpApi = {
  /** 获取 MCP 环境状态（npx 可用性等） */
  getStatus(): Promise<{ npxAvailable: boolean }> {
    return request('/mcp/status')
  },
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
  },
  /** 获取 MCP Server 连接日志 */
  getConnectionLogs(serverName: string): Promise<McpConnectionLog[]> {
    return request(`/mcp/servers/${serverName}/connection-logs`)
  }
}

/** 轨迹查询 API */
export const traceApi = {
  /**
   * 分页获取轨迹列表
   */
  list(page = 0, size = 20): Promise<PageResult<TraceItem>> {
    return request(`/traces?page=${page}&size=${size}`)
  },
  /**
   * 获取单条轨迹详情
   */
  get(id: string): Promise<TraceDetail> {
    return request(`/traces/${id}`)
  },
  /**
   * 获取轨迹步骤列表
   */
  getSteps(id: string): Promise<TraceStep[]> {
    return request(`/traces/${id}/steps`)
  },
  /**
   * 获取轨迹概览统计信息
   *
   * 用于统计卡片区域（总数、成功率、平均步骤数等）。
   */
  getOverviewStats(window: '24h' | '7d' | '30d' = '7d'): Promise<OverviewStats> {
    return request(`/traces/stats/overview?window=${window}`)
  },
  /**
   * 获取工具使用统计信息
   *
   * 返回各工具的调用次数、成功率和平均耗时等。
   */
  getToolStats(): Promise<ToolUsageStats[]> {
    return request('/traces/stats/tools')
  },
  /**
   * 搜索轨迹
   *
   * 使用关键字搜索最近的轨迹记录。
   */
  search(keyword: string, limit = 20): Promise<TraceItem[]> {
    const params = new URLSearchParams()
    params.append('keyword', keyword)
    params.append('limit', String(limit))
    const query = params.toString()
    return request(`/traces/search?${query}`)
  },
  /**
   * 导出轨迹为 JSON 字符串
   *
   * 返回单条轨迹的完整 JSON 文本，供前端下载保存。
   */
  export(id: string): Promise<string> {
    return request(`/traces/${id}/export`)
  },
  /**
   * 获取 Token 消耗统计
   *
   * 可选的起止时间用于限定统计窗口。
   */
  getTokenStats(start?: string, end?: string): Promise<TokenConsumptionStats> {
    const params = new URLSearchParams()
    if (start) params.append('start', start)
    if (end) params.append('end', end)
    const query = params.toString()
    return request(`/traces/stats/tokens${query ? `?${query}` : ''}`)
  },
  /**
   * 获取轨迹评估结果
   *
   * 返回单条轨迹的离线评估分数与违规/建议信息。
   */
  getEvaluation(id: string): Promise<EvaluationResult> {
    return request(`/traces/${id}/evaluation`)
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
  getYaml(id: string): Promise<{ yamlContent: string }> {
    return request(`/workflows/${id}/yaml`)
  },
  create(data: { yaml: string } | { yamlContent: string }): Promise<WorkflowDetail> {
    const yamlContent = 'yamlContent' in data ? data.yamlContent : data.yaml
    return request('/workflows', {
      method: 'POST',
      body: JSON.stringify({ yamlContent })
    })
  },
  update(id: string, data: { yaml: string } | { yamlContent: string }): Promise<WorkflowDetail> {
    const yamlContent = 'yamlContent' in data ? data.yamlContent : data.yaml
    return request(`/workflows/${id}`, {
      method: 'PUT',
      body: JSON.stringify({ yamlContent })
    })
  },
  delete(id: string): Promise<void> {
    return request(`/workflows/${id}`, { method: 'DELETE' })
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
  },
  /** 获取工作流 YAML 内容（便捷方法） */
  getWorkflowYaml(workflowId: string): Promise<string> {
    return this.getYaml(workflowId).then(res => res.yamlContent)
  },
  /** 更新工作流 YAML 内容（便捷方法） */
  updateWorkflowYaml(workflowId: string, content: string): Promise<WorkflowDetail> {
    return this.update(workflowId, { yamlContent: content })
  },
  /** 获取单个执行实例详情 */
  getInstance(instanceId: string): Promise<WorkflowExecution> {
    return request(`/workflows/executions/${instanceId}`)
  },
  /** 提交审批决策 */
  approve(instanceId: string, stepId: string, req: ApprovalRequest): Promise<WorkflowExecution> {
    return request(`/workflows/executions/${instanceId}/steps/${stepId}/approve`, {
      method: 'POST',
      body: JSON.stringify(req)
    })
  },
  /** 获取实例事件时间线 */
  getEventTimeline(instanceId: string): Promise<WorkflowEvent[]> {
    return request(`/workflows/executions/${instanceId}/events`)
  },
  /** 获取实例步骤执行日志 */
  getStepLogs(instanceId: string): Promise<StepLog[]> {
    return request(`/workflows/executions/${instanceId}/step-logs`)
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
    // 后端使用 /tools/{id}/test，参数字段名为 arguments
    const args = req.input ?? req.arguments ?? {}
    return request(`/tools/${req.toolId}/test`, {
      method: 'POST',
      body: JSON.stringify({
        arguments: args
      })
    })
  },
  getUsage(id: string): Promise<any> {
    return request(`/tools/${id}/usage`)
  },
  /** 获取 Tool YAML 定义 */
  getToolYaml(toolId: string): Promise<string> {
    return fetch(`${BASE}/tools/${toolId}/yaml`).then(res => {
      if (!res.ok) throw { code: res.status, message: '获取失败', timestamp: new Date().toISOString() }
      return res.text()
    })
  },
  /** 更新 Tool YAML 定义 */
  updateToolYaml(toolId: string, content: string): Promise<void> {
    return request(`/tools/${toolId}/yaml`, {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain' },
      body: content
    } as any)
  }
}

/** Agent 管理 API */
export const agentApi = {
  list(params?: { q?: string; type?: string; status?: string; tags?: string[] }): Promise<AgentSummary[]> {
    const query = new URLSearchParams()
    if (params?.q) query.append('q', params.q)
    if (params?.type) query.append('type', params.type)
    if (params?.status) query.append('status', params.status)
    if (params?.tags && params.tags.length > 0) {
      params.tags.forEach(tag => query.append('tags', tag))
    }
    const queryString = query.toString()
    return request(`/agents${queryString ? `?${queryString}` : ''}`)
  },
  get(id: string): Promise<AgentDetail> {
    return request(`/agents/${id}`)
  },
  create(data: Partial<AgentDetail>): Promise<AgentDetail> {
    return request('/agents', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },
  update(id: string, data: Partial<AgentDetail>): Promise<AgentDetail> {
    return request(`/agents/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  },
  delete(id: string): Promise<void> {
    return request(`/agents/${id}`, { method: 'DELETE' })
  },
  enable(id: string): Promise<void> {
    return request(`/agents/${id}/enable`, { method: 'POST' })
  },
  disable(id: string): Promise<void> {
    return request(`/agents/${id}/disable`, { method: 'POST' })
  },
  testChat(id: string, message: string): Promise<ChatResponse> {
    return request(`/agents/${id}/test-chat`, {
      method: 'POST',
      body: JSON.stringify({ message })
    })
  },
  /** 流式测试聊天 */
  async testChatStream(id: string, message: string, signal?: AbortSignal): Promise<ReadableStream<Uint8Array>> {
    const res = await fetch(`${BASE}/agents/${id}/test-chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message }),
      signal
    })
    if (!res.ok || !res.body) {
      throw { code: res.status, message: '流式请求失败', timestamp: new Date().toISOString() }
    }
    return res.body
  },
  /** 上下文组装预览 */
  contextPreview(agentId: string, message: string, sessionId?: string): Promise<ContextPreviewResponse> {
    return request(`/agents/${agentId}/context-preview`, {
      method: 'POST',
      body: JSON.stringify({ message, sessionId })
    })
  },
  /** 获取 Agent Markdown 定义 */
  getAgentMarkdown(agentId: string): Promise<string> {
    return fetch(`${BASE}/agents/${agentId}/markdown`).then(res => {
      if (!res.ok) throw { code: res.status, message: '获取失败', timestamp: new Date().toISOString() }
      return res.text()
    })
  },
  /** 更新 Agent Markdown 定义 */
  updateAgentMarkdown(agentId: string, content: string): Promise<void> {
    return request(`/agents/${agentId}/markdown`, {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain' },
      body: content
    } as any)
  }
}

/** Analytics API */
export const analyticsApi = {
  getUsageStats(timeRange: { from: string; to: string }): Promise<UsageStats> {
    const query = new URLSearchParams()
    query.append('from', timeRange.from)
    query.append('to', timeRange.to)
    return request(`/analytics/usage?${query.toString()}`)
  },
  getAgentStats(timeRange?: { from: string; to: string }): Promise<AgentStats[]> {
    const query = new URLSearchParams()
    if (timeRange) {
      query.append('from', timeRange.from)
      query.append('to', timeRange.to)
    }
    return request(`/analytics/agents?${query.toString()}`)
  },
  getKnowledgeBaseStats(timeRange?: { from: string; to: string }): Promise<KnowledgeBaseStats[]> {
    const query = new URLSearchParams()
    if (timeRange) {
      query.append('from', timeRange.from)
      query.append('to', timeRange.to)
    }
    return request(`/analytics/knowledge-bases?${query.toString()}`)
  },
  /** 获取 Tool 调用统计 */
  getToolAnalytics(timeRange: { from: string; to: string }): Promise<ToolAnalyticsResponse> {
    const query = new URLSearchParams()
    query.append('from', timeRange.from)
    query.append('to', timeRange.to)
    return request(`/analytics/tools?${query.toString()}`)
  },
  /** 获取错误趋势 */
  getErrorTrend(timeRange: { from: string; to: string }): Promise<ErrorTrendDaily[]> {
    const query = new URLSearchParams()
    query.append('from', timeRange.from)
    query.append('to', timeRange.to)
    return request(`/analytics/error-trend?${query.toString()}`)
  }
}

/** 依赖关系图 API */
export const dependencyApi = {
  /** 获取依赖关系图 */
  getGraph(): Promise<DependencyGraphResponse> {
    return request('/dependencies/graph')
  }
}
