import type {
  ChatAttachment,
  ChatResponse,
  ChatTurnAction,
  ChatTurnStatus,
  ChatSession,
  ChatSessionDetail,
  CreateKbRequest,
  UpdateKbRequest,
  DocumentChunk,
  ErrorResponse,
  KbDocument,
  KbStats,
  KnowledgeBase,
  McpServer,
  McpServerConfig,
  McpTool,
  Message,
  ReactStepDto,
  PageResult,
  ProcessingLog,
  SkillDetail,
  SkillSummary,
  TestRetrievalResult,
  AgentDetail,
  CreateAgentRequest,
  AgentSummary,
  ToolDetail,
  ToolSummary,
  ToolTestRequest,
  ToolTestResponse,
  TraceDetail,
  TraceItem,
  TraceStep,
  UpdateAgentRequest,
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
  ToolCallStats,
  ToolDailyTrend,
  ToolAnalyticsResponse,
  ErrorTrendDaily,
  McpConnectionLog,
  // 工作流成熟化新增类型
  WorkflowStats,
  StepStats,
  DailyTrend,
  StepOutput,
  DagData,
  DagNode,
  DagEdge,
  DryRunResult,
  DryRunStepTrace,
  ValidationResponse,
  StepTypeSchema,
  ParamSchema,
  OptionItem,
  // 通知中心类型
  NotificationItem,
  ProactiveConfig,
  ProactiveConfigUpdate,
  QueuedAction,
  TrustStatus,
  SseInteractionEvent,
  // 记忆管理类型
  MemoryStats,
  MemorySearchResult,
  MemoryProvenanceListParams,
  MemoryProvenanceSummary,
  EntitySummary,
  EntityDetail,
  EntityProvenance,
  EntityProvenanceParams,
  EntityCreateRequest,
  EntityUpdateRequest,
  EntityListParams,
  RelationItem,
  RelationListParams,
  ConversationSummary,
  ConversationDetail,
  ConversationListParams,
  ProcedureTemplate,
  TemplateListParams,
  PreferenceRule,
  ForgettingLog,
  ForgettingLogListParams,
  PermissionGrant,
  PermissionGrantCreateRequest,
  ChannelPluginDescriptor,
  ChannelInstance,
  ChannelInstanceEvent,
  ChannelHealthStatus,
  CreateChannelInstanceRequest,
  UpdateChannelInstanceRequest
} from '@/types'
import { mapBackendMessage } from '@/utils/a2ui'
import { logger } from '@/utils/logger'
import { getApiOrigin } from '@/api/config'

// API 基础路径（运行时求值，Tauri 桌面端使用绝对路径，浏览器环境通过 Vite proxy 转发）
const getBase = () => getApiOrigin() + '/api'

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
    const res = await fetch(`${getBase()}${url}`, {
      headers: { 'Content-Type': 'application/json' },
      ...options
    })
    if (!res.ok) {
      const text = await res.text()
      let error: ErrorResponse
      try {
        error = text
          ? JSON.parse(text) as ErrorResponse
          : { code: res.status, message: res.statusText, timestamp: new Date().toISOString() }
      } catch {
        error = {
          code: res.status,
          message: text?.trim() || res.statusText || '请求失败',
          timestamp: new Date().toISOString()
        }
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
      const json = JSON.parse(text)
      // 自动解包 ApiResponse 结构 { code: number, message: string, data: T }
      if (json && typeof json === 'object' && 'code' in json && typeof json.code === 'number' && 'message' in json && 'data' in json) {
        return json.data as T
      }
      return json as T
    } catch (e) {
      logger.error('JSON 解析失败:', e, '响应内容:', text)
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
    attachmentIds?: string[],
    turnId?: string,
    action: ChatTurnAction = 'SEND'
  ): Promise<ChatResponse> {
    return request('/chat/messages', {
      method: 'POST',
      body: JSON.stringify({ content, sessionId, attachmentIds, turnId, action })
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
    turnId?: string,
    action: ChatTurnAction = 'SEND',
    signal?: AbortSignal
  ): Promise<ReadableStream<Uint8Array>> {
    const res = await fetch(`${getBase()}/chat/messages/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content, sessionId, attachmentIds, turnId, action }),
      signal
    })
    if (!res.ok || !res.body) {
      throw { code: res.status, message: '流式请求失败', timestamp: new Date().toISOString() }
    }
    return res.body
  },

  /** 创建会话，可选传入 projectId 将会话归入指定项目 */
  createSession(title?: string, projectId?: string | null): Promise<ChatSession> {
    return request('/chat/sessions', {
      method: 'POST',
      body: JSON.stringify({ title, projectId: projectId ?? null })
    })
  },

  /**
   * 获取会话列表。
   *
   * @param projectId 项目 ID；传入时仅返回该项目下的会话，
   *                  不传或为 null 时返回主账户会话（project_id IS NULL）
   */
  listSessions(projectId?: string | null): Promise<ChatSession[]> {
    const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''
    return request(`/chat/sessions${qs}`)
  },

  /** 获取会话历史消息 */
  async getSessionMessages(sessionId: string): Promise<Message[]> {
    const messages = await request<Array<{
      id: string
      turnId?: string | null
      role: 'user' | 'assistant' | 'permission-approval'
      content: string
      a2uiComponents?: unknown
      timestamp: string | number
      reasoningSummary?: string | null
      /** 推理过程文本（payload_json 暴露后自动生效，缺失时 UI 自动隐藏） */
      reasoningContent?: string | null
      reasoningDurationMs?: number | null
      traceId?: string | null
      attachments?: Array<{ id: string; fileName: string; fileSize: number; mimeType: string; url?: string | null }> | null
      reactSteps?: ReactStepDto[] | null
      completionMode?: 'NORMAL' | 'DEGRADED' | 'SUSPENDED' | null
      resumedFromTraceId?: string | null
      turnStatus?: ChatTurnStatus | null
      errorMessage?: string | null
      suspendReasonType?: string | null
      suspendReasonSourceId?: string | null
    }>>(`/chat/sessions/${sessionId}/messages`)
    return messages.map(mapBackendMessage)
  },

  /** 获取会话详情 */
  getSession(sessionId: string): Promise<ChatSessionDetail> {
    return request(`/chat/sessions/${sessionId}`)
  },

  /** 更新会话（标题、置顶、归档等） */
  updateSession(sessionId: string, updates: { title?: string; pinned?: boolean; archived?: boolean }): Promise<ChatSession> {
    return request(`/chat/sessions/${sessionId}`, {
      method: 'PATCH',
      body: JSON.stringify(updates)
    })
  },

  /**
   * 更新会话配置（模型/温度/三维预算/关联知识库）。
   *
   * 注意：知识库关联会影响后端在生成回答前的检索上下文注入（若已启用）。
   */
  updateSessionConfig(
    sessionId: string,
    config: {
      preferredProviderId?: string
      temperature?: number
      maxSteps?: number
      maxDurationSeconds?: number
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

  /** 提交条目反馈（点赞/点踩） */
  submitFeedback(
    entryId: string,
    type: 'like' | 'dislike',
    feedback?: string
  ): Promise<void> {
    return request(`/chat/entries/${entryId}/feedback`, {
      method: 'POST',
      body: JSON.stringify({ type, feedback })
    })
  },

  /** 分叉会话（从指定条目处创建新会话） */
  forkSession(
    sessionId: string,
    fromEntryId?: string
  ): Promise<ChatSession> {
    return request(`/chat/sessions/${sessionId}/fork`, {
      method: 'POST',
      body: JSON.stringify({ fromEntryId })
    })
  },

  /** A2UI 信号回传 */
  sendSignal(name: string, payload: Record<string, unknown>, sessionId: string): Promise<ChatResponse> {
    return request('/chat/signals', {
      method: 'POST',
      body: JSON.stringify({ name, payload, sessionId })
    })
  },

  /** 权限审批响应 */
  respondPermissionApproval(
    requestId: string,
    approved: boolean,
    subjectType: string,
    reason?: string,
  ): Promise<void> {
    return request(`/permissions/approvals/${requestId}`, {
      method: 'POST',
      body: JSON.stringify({ requestId, approved, subjectType, reason })
    })
  },

  /** 用户交互回传 */
  respondInteraction(
    interactionId: string,
    payload: Pick<SseInteractionEvent, 'type'> & { value?: string | null; confirmed: boolean; timedOut?: boolean }
  ): Promise<void> {
    return request(`/chat/interactions/${interactionId}`, {
      method: 'POST',
      body: JSON.stringify({
        value: payload.value ?? null,
        confirmed: payload.confirmed,
        timedOut: payload.timedOut ?? false,
      })
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

    const res = await fetch(`${getBase()}/chat/messages/upload`, {
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
  },

  /** 查询后端语音输入能力（原生音频 / STT 转录） */
  async getVoiceCapability(): Promise<{ nativeAudio: boolean; stt: boolean; supported: boolean }> {
    return request<{ nativeAudio: boolean; stt: boolean; supported: boolean }>('/chat/voice-capability')
  }
}

/** 权限授权相关 API */
export const permissionApi = {
  listGrants(params?: {
    activeOnly?: boolean
    subjectType?: 'SESSION' | 'WORKSPACE' | 'TASK' | 'USER'
    subjectId?: string
  }): Promise<PermissionGrant[]> {
    const search = new URLSearchParams()
    if (params?.activeOnly != null) {
      search.set('activeOnly', String(params.activeOnly))
    }
    if (params?.subjectType) {
      search.set('subjectType', params.subjectType)
    }
    if (params?.subjectId) {
      search.set('subjectId', params.subjectId)
    }
    const query = search.toString()
    return request(`/permissions/grants${query ? `?${query}` : ''}`)
  },

  createGrant(payload: PermissionGrantCreateRequest): Promise<PermissionGrant> {
    return request('/permissions/grants', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  },

  revokeGrant(grantId: string, options?: { revokedBy?: string; reason?: string }): Promise<void> {
    const search = new URLSearchParams()
    if (options?.revokedBy) {
      search.set('revokedBy', options.revokedBy)
    }
    if (options?.reason) {
      search.set('reason', options.reason)
    }
    const query = search.toString()
    return request(`/permissions/grants/${grantId}${query ? `?${query}` : ''}`, {
      method: 'DELETE'
    })
  }
}

/** 思考模式 — 与后端 {@code ThinkingMode} 枚举一一对应（小写形式，序列化时直接传字符串）。 */
export type ThinkingMode = 'auto' | 'enabled' | 'disabled'

/** 模型服务定义。 */
export interface ModelService {
  id: string
  kind: string
  type: string
  /** 内置 ProviderProfile ID（Phase 6 新增）—— 决定 thinking 协议、模型探测端点等。 */
  profileId?: string
  vendorKey?: string
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
  /** 是否为推理模型（Phase 6 新增）—— 决定是否走 thinking 协议下发。 */
  isReasoning?: boolean
  /** 思考模式（Phase 6 新增）—— auto/enabled/disabled 三态控制是否下发 thinking 字段。 */
  thinkingMode?: ThinkingMode
  healthy?: boolean
  apiUrl?: string
  timeoutSeconds?: number
  description?: string
  embeddingDimension?: number
}

export type ModelServiceDetail = ModelService

export interface ModelServiceTemplateModel {
  kind: string
  value: string
  label: string
  recommended: boolean
  capabilities: string[]
  scenes: string[]
  supportsStreaming: boolean
  maxContextWindow?: number
  embeddingDimension?: number
}

export interface ModelServiceTemplate {
  vendorKey: string
  displayName: string
  providerType: string
  description: string
  defaultApiUrl: string
  supportedKinds: string[]
  defaultTimeoutSeconds: number
  defaultCapabilities: string[]
  defaultScenes: string[]
  defaultSupportsStreaming: boolean
  defaultMaxContextWindow?: number
  modelOptions: ModelServiceTemplateModel[]
}

export interface GenerationRoutingSettings {
  defaultServiceId?: string
  sceneServiceBindings: Record<string, string>
}

export interface GenerationRoutingSettingsRequest {
  defaultServiceId?: string
  sceneServiceBindings?: Record<string, string>
}

export interface EmbeddingRoutingSettings {
  defaultServiceId?: string
  knowledgeBaseServiceId?: string
  memoryServiceId?: string
}

export interface EmbeddingRoutingSettingsRequest {
  defaultServiceId?: string
  knowledgeBaseServiceId?: string
  memoryServiceId?: string
}

export interface RerankRoutingSettings {
  enabled: boolean
  mode: string
  nativeServiceId?: string
  llmServiceId?: string
  knowledgeTopK: number
  memoryEnabled: boolean
  memoryTopK: number
}

export interface RerankRoutingSettingsRequest {
  enabled?: boolean
  mode?: string
  nativeServiceId?: string
  llmServiceId?: string
  knowledgeTopK?: number
  memoryEnabled?: boolean
  memoryTopK?: number
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
  /** 获取知识库全局配置 */
  getKnowledgeSettings(): Promise<KnowledgeSettings> {
    return request('/settings/knowledge')
  },

  /** 更新知识库全局配置 */
  updateKnowledgeSettings(settings: Record<string, unknown>): Promise<KnowledgeSettings> {
    return request('/settings/knowledge', {
      method: 'PUT',
      body: JSON.stringify(settings)
    })
  },

  /** 获取联网搜索配置 */
  getSearchSettings(): Promise<SearchSettings> {
    return request('/settings/search')
  },

  /** 更新联网搜索配置 */
  updateSearchSettings(settings: SearchSettingsRequest): Promise<SearchSettings> {
    return request('/settings/search', {
      method: 'PUT',
      body: JSON.stringify(settings)
    })
  },

  /** 获取数据目录 */
  getDataDir(): Promise<{ dataDir: string, configuredDir: string | null }> {
    return request('/settings/data-dir')
  },

  /** 更新数据目录（需重启生效） */
  updateDataDir(dataDir: string | null): Promise<{ dataDir: string, configuredDir: string | null }> {
    return request('/settings/data-dir', {
      method: 'PUT',
      body: JSON.stringify({ dataDir: dataDir ?? '' })
    })
  },

  /** 获取工作目录配置 */
  getWorkspaceSettings(): Promise<WorkspaceSettings> {
    return request('/settings/workspace')
  },

  /** 更新工作目录配置 */
  updateWorkspaceSettings(workspace: { defaultWorkspace: string | null }): Promise<WorkspaceSettings> {
    return request('/settings/workspace', {
      method: 'PUT',
      body: JSON.stringify(workspace)
    })
  },

  /** 获取外部 CLI Bash 依赖路径 */
  getExternalCliBashSettings(): Promise<ExternalCliBashSettings> {
    return request('/settings/external-cli-bash')
  },

  /** 更新外部 CLI Bash 依赖路径 */
  updateExternalCliBashSettings(payload: { externalCliBashPath: string | null }): Promise<ExternalCliBashSettings> {
    return request('/settings/external-cli-bash', {
      method: 'PUT',
      body: JSON.stringify(payload)
    })
  }
}

/** 工作目录配置响应 */
export interface WorkspaceSettings {
  defaultWorkspace: string | null
  resolvedPath: string
  systemDefault: string
}

/** 外部 CLI Bash 依赖配置响应 */
export interface ExternalCliBashSettings {
  externalCliBashPath: string | null
}

/** Reranker 配置响应 */
/** Reranker 配置请求 */
/** 知识库全局配置响应 */
export interface KnowledgeSettings {
  enabled: boolean
  maxFileSize: number
  chunking: {
    defaultStrategy: string
    chunkSize: number
    overlapSize: number
    maxChunkTokens: number
  }
  retrieval: {
    defaultTopK: number
    vectorWeight: number
    ftsWeight: number
    minRelevanceScore: number
  }
  vectorIndexer: {
    embeddingDimension: number
    batchSize: number
  }
}

/** 联网搜索配置响应 */
export interface SearchSettings {
  provider: string
  apiKey: string
  maxResults: number
  searchDepth: string
  topic: string
  includeAnswer: boolean
  connectTimeoutSeconds: number
  readTimeoutSeconds: number
}

/** 联网搜索配置请求 */
export interface SearchSettingsRequest {
  provider?: string
  apiKey?: string
  maxResults?: number
  searchDepth?: string
  topic?: string
  includeAnswer?: boolean
  connectTimeoutSeconds?: number
  readTimeoutSeconds?: number
}

/** 渠道控制面 API */
export const channelApi = {
  listPlugins(): Promise<ChannelPluginDescriptor[]> {
    return request('/channels/plugins')
  },

  listInstances(): Promise<ChannelInstance[]> {
    return request('/channels/instances')
  },

  getInstance(instanceId: string): Promise<ChannelInstance> {
    return request(`/channels/instances/${encodeURIComponent(instanceId)}`)
  },

  createInstance(payload: CreateChannelInstanceRequest): Promise<ChannelInstance> {
    return request('/channels/instances', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  },

  updateInstance(instanceId: string, payload: UpdateChannelInstanceRequest): Promise<ChannelInstance> {
    return request(`/channels/instances/${encodeURIComponent(instanceId)}`, {
      method: 'PUT',
      body: JSON.stringify(payload)
    })
  },

  updateEnabled(instanceId: string, enabled: boolean): Promise<ChannelInstance> {
    return request(`/channels/instances/${encodeURIComponent(instanceId)}/enabled?enabled=${enabled}`, {
      method: 'PATCH'
    })
  },

  startInstance(instanceId: string): Promise<ChannelInstance> {
    return request(`/channels/instances/${encodeURIComponent(instanceId)}/start`, {
      method: 'POST'
    })
  },

  stopInstance(instanceId: string): Promise<ChannelInstance> {
    return request(`/channels/instances/${encodeURIComponent(instanceId)}/stop`, {
      method: 'POST'
    })
  },

  reloadInstance(instanceId: string): Promise<ChannelInstance> {
    return request(`/channels/instances/${encodeURIComponent(instanceId)}/reload`, {
      method: 'POST'
    })
  },

  getHealth(instanceId: string): Promise<ChannelHealthStatus> {
    return request(`/channels/instances/${encodeURIComponent(instanceId)}/health`)
  },

  listInstanceEvents(instanceId: string, limit = 20): Promise<ChannelInstanceEvent[]> {
    return request(`/channels/instances/${encodeURIComponent(instanceId)}/events?limit=${limit}`)
  },

  deleteInstance(instanceId: string): Promise<void> {
    return request(`/channels/instances/${encodeURIComponent(instanceId)}`, {
      method: 'DELETE'
    })
  }
}

export const modelRoutingApi = {
  getGenerationSettings(): Promise<GenerationRoutingSettings> {
    return request('/model-routing/generation')
  },

  updateGenerationSettings(settings: GenerationRoutingSettingsRequest): Promise<GenerationRoutingSettings> {
    return request('/model-routing/generation', {
      method: 'PUT',
      body: JSON.stringify(settings)
    })
  },

  getEmbeddingSettings(): Promise<EmbeddingRoutingSettings> {
    return request('/model-routing/embedding')
  },

  updateEmbeddingSettings(settings: EmbeddingRoutingSettingsRequest): Promise<EmbeddingRoutingSettings> {
    return request('/model-routing/embedding', {
      method: 'PUT',
      body: JSON.stringify(settings)
    })
  },

  getRerankSettings(): Promise<RerankRoutingSettings> {
    return request('/model-routing/rerank')
  },

  updateRerankSettings(settings: RerankRoutingSettingsRequest): Promise<RerankRoutingSettings> {
    return request('/model-routing/rerank', {
      method: 'PUT',
      body: JSON.stringify(settings)
    })
  }
}

/** 模型服务管理 API */
export const modelServiceApi = {
  /** 获取所有模型服务（包含已禁用项）。 */
  listServices(kind?: string): Promise<ModelService[]> {
    const query = kind ? `?kind=${encodeURIComponent(kind)}` : ''
    return request(`/model-services${query}`)
  },

  /** 获取模型服务模板目录。 */
  listTemplates(): Promise<ModelServiceTemplate[]> {
    return request('/model-services/templates')
  },

  /** 获取所有已启用的模型服务。 */
  listEnabledServices(kind?: string): Promise<ModelService[]> {
    const query = kind ? `?kind=${encodeURIComponent(kind)}` : ''
    return request(`/model-services/enabled${query}`)
  },

  /** 根据 ID 获取模型服务详情。 */
  getService(id: string): Promise<ModelServiceDetail> {
    return request(`/model-services/${id}`)
  },

  /** 创建模型服务。 */
  createService(provider: CreateModelServiceRequest): Promise<ModelService> {
    return request('/model-services', {
      method: 'POST',
      body: JSON.stringify(provider)
    })
  },

  /** 更新模型服务。 */
  updateService(id: string, provider: UpdateModelServiceRequest): Promise<ModelService> {
    return request(`/model-services/${id}`, {
      method: 'PUT',
      body: JSON.stringify(provider)
    })
  },

  /** 删除模型服务。 */
  deleteService(id: string): Promise<void> {
    return request(`/model-services/${id}`, {
      method: 'DELETE'
    })
  },

  /** 切换模型服务启用状态。 */
  toggleEnabled(id: string): Promise<ModelService> {
    return request(`/model-services/${id}/toggle-enabled`, { method: 'POST' })
  },

  /** 测试模型服务连接。 */
  testConnection(id: string): Promise<{ healthy: boolean; serviceId: string; modelName?: string; error?: string }> {
    return request(`/model-services/${id}/test`, { method: 'POST' })
  },
}

/** 创建模型服务请求。 */
export interface CreateModelServiceRequest {
  id: string
  kind: string
  type: string
  /** 内置 ProviderProfile ID（Phase 6 新增）—— 必填字段。 */
  profileId?: string
  vendorKey?: string
  apiUrl: string
  apiKey?: string
  modelName: string
  timeoutSeconds?: number
  priority?: number
  scenes?: string[]
  capabilities?: string[]
  enabled?: boolean
  /** 是否为推理模型（Phase 6 新增）。 */
  isReasoning?: boolean
  /** 思考模式（Phase 6 新增）。 */
  thinkingMode?: ThinkingMode
  costPerInputToken?: number
  costPerOutputToken?: number
  maxContextWindow?: number
  embeddingDimension?: number
  supportsStreaming?: boolean
  displayName?: string
  description?: string
}

/** 更新模型服务请求。 */
export interface UpdateModelServiceRequest {
  kind?: string
  type?: string
  /** 内置 ProviderProfile ID（Phase 6 新增）。 */
  profileId?: string
  vendorKey?: string
  apiUrl?: string
  apiKey?: string
  modelName?: string
  timeoutSeconds?: number
  priority?: number
  scenes?: string[]
  capabilities?: string[]
  enabled?: boolean
  /** 是否为推理模型（Phase 6 新增）。 */
  isReasoning?: boolean
  /** 思考模式（Phase 6 新增）。 */
  thinkingMode?: ThinkingMode
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
  update(id: string, req: UpdateKbRequest): Promise<KnowledgeBase> {
    return request(`/knowledge-bases/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(req)
    })
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
    const res = await fetch(`${getBase()}/knowledge-bases/${kbId}/documents`, {
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
  async getDocumentChunks(kbId: string, docId: string, offset = 0, limit = 100): Promise<DocumentChunk[]> {
    const res = await request<{ chunks: DocumentChunk[]; total: number }>(
      `/knowledge-bases/${kbId}/documents/${docId}/chunks?offset=${offset}&limit=${limit}`,
    )
    return res.chunks
  },
  retryDocument(kbId: string, docId: string): Promise<void> {
    return request(`/knowledge-bases/${kbId}/documents/${docId}/retry`, { method: 'POST' })
  },
  rechunkDocument(kbId: string, docId: string): Promise<void> {
    return request(`/knowledge-bases/${kbId}/documents/${docId}/rechunk`, { method: 'POST' })
  },
  downloadDocument(kbId: string, docId: string): Promise<Blob> {
    return fetch(`${getBase()}/knowledge-bases/${kbId}/documents/${docId}/download`).then(res => {
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
  get(name: string): Promise<SkillDetail> {
    return request(`/skills/${encodeURIComponent(name)}`)
  },
  create(data: { skillMdContent: string }): Promise<import('@/types').SkillInstallation> {
    return request('/skills', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },
  update(name: string, data: Partial<SkillDetail>): Promise<SkillDetail> {
    return request(`/skills/${encodeURIComponent(name)}`, {
      method: 'PUT',
      body: JSON.stringify(data)
    })
  },
  unregister(name: string): Promise<void> {
    return request(`/skills/${encodeURIComponent(name)}`, { method: 'DELETE' })
  },
  /** 显式设置启用状态（Phase B.6 新端点） */
  setEnabled(name: string, enabled: boolean): Promise<void> {
    return request(`/skills/${encodeURIComponent(name)}/enabled`, {
      method: 'PUT',
      body: JSON.stringify({ enabled })
    })
  },
  /** 兼容旧端点：启用 Skill */
  enable(name: string): Promise<void> {
    return request(`/skills/${encodeURIComponent(name)}/enable`, { method: 'POST' })
  },
  /** 兼容旧端点：禁用 Skill */
  disable(name: string): Promise<void> {
    return request(`/skills/${encodeURIComponent(name)}/disable`, { method: 'POST' })
  },
  test(name: string, data: { userMessage: string; context?: Record<string, unknown> }): Promise<any> {
    return request(`/skills/${encodeURIComponent(name)}/test`, {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },
  /** 获取 Skill Markdown 定义 */
  getSkillMarkdown(name: string): Promise<string> {
    return fetch(`${getBase()}/skills/${encodeURIComponent(name)}/markdown`).then(res => {
      if (!res.ok) throw { code: res.status, message: '获取失败', timestamp: new Date().toISOString() }
      return res.text()
    })
  },
  /** 更新 Skill Markdown 定义 */
  updateSkillMarkdown(name: string, content: string): Promise<void> {
    return request(`/skills/${encodeURIComponent(name)}/markdown`, {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain' },
      body: content
    } as any)
  },
  /** 上传 .skill 压缩包导入 Skill（multipart/form-data，字段名 file） */
  async importPackage(file: File): Promise<import('@/types').SkillInstallation> {
    const formData = new FormData()
    formData.append('file', file)
    const res = await fetch(`${getBase()}/skills/import`, {
      method: 'POST',
      body: formData
    })
    if (!res.ok) {
      const text = await res.text()
      let error: ErrorResponse
      try {
        error = text
          ? JSON.parse(text) as ErrorResponse
          : { code: res.status, message: res.statusText, timestamp: new Date().toISOString() }
      } catch {
        error = { code: res.status, message: text?.trim() || res.statusText || '请求失败', timestamp: new Date().toISOString() }
      }
      throw error
    }
    const text = await res.text()
    if (!text) return undefined as unknown as import('@/types').SkillInstallation
    const json = JSON.parse(text)
    return (json && typeof json === 'object' && 'code' in json && 'data' in json)
      ? json.data as import('@/types').SkillInstallation
      : json as import('@/types').SkillInstallation
  },
  /** 从市场索引按 marketplaceId 安装 Skill */
  installFromMarketplace(marketplaceId: string): Promise<import('@/types').SkillInstallation> {
    return request('/skills/install-from-marketplace', {
      method: 'POST',
      body: JSON.stringify({ marketplaceId })
    })
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
  },

  // ========== 新增 API：工作流成熟化需求 ==========

  /** 获取工作流执行统计 */
  getStats(id: string): Promise<WorkflowStats> {
    return request(`/workflows/${id}/stats`)
  },

  /** 获取工作流步骤统计 */
  getStepStats(id: string): Promise<StepStats[]> {
    return request(`/workflows/${id}/step-stats`)
  },

  /** 获取执行实例的完整上下文 */
  getExecutionContext(instanceId: string): Promise<Record<string, unknown>> {
    return request(`/workflows/executions/${instanceId}/context`)
  },

  /** 获取指定步骤的输出 */
  getStepOutput(instanceId: string, stepId: string): Promise<StepOutput> {
    return request(`/workflows/executions/${instanceId}/steps/${stepId}/output`)
  },

  /** 获取工作流 DAG 依赖图数据 */
  getDag(id: string): Promise<DagData> {
    return request(`/workflows/${id}/dag`)
  },

  /** 试运行工作流 */
  dryRun(id: string, inputs?: Record<string, unknown>): Promise<DryRunResult> {
    return request(`/workflows/${id}/dry-run`, {
      method: 'POST',
      body: JSON.stringify({ inputs })
    })
  },

  /** 校验 YAML 语法和语义 */
  validateYaml(yaml: string): Promise<ValidationResponse> {
    return request('/workflows/validate', {
      method: 'POST',
      body: JSON.stringify({ yamlContent: yaml })
    })
  },

  /** 触发 Webhook */
  triggerWebhook(id: string, body: Record<string, unknown>, signature?: string): Promise<WorkflowExecution> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (signature) {
      headers['X-Webhook-Signature'] = signature
    }
    return request(`/workflows/${id}/webhook`, {
      method: 'POST',
      headers,
      body: JSON.stringify(body)
    })
  },

  /** 导出单个工作流 */
  exportWorkflow(id: string): Promise<{ id: string; yamlContent: string }> {
    return request(`/workflows/${id}/export`)
  },

  /** 批量导出工作流 */
  exportWorkflows(ids: string[]): Promise<Array<{ id: string; yamlContent: string }>> {
    return request(`/workflows/export?ids=${ids.join(',')}`)
  },

  /** 导入工作流 */
  importWorkflow(yamlContent: string): Promise<WorkflowDetail> {
    return request('/workflows/import', {
      method: 'POST',
      body: JSON.stringify({ yamlContent })
    })
  },

  /** 获取步骤类型参数 Schema */
  getStepTypes(): Promise<StepTypeSchema[]> {
    return request('/workflows/step-types')
  },

  /** 按标签筛选工作流列表 */
  listByTag(tag: string): Promise<WorkflowItem[]> {
    return request(`/workflows?tag=${encodeURIComponent(tag)}`)
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
  test(req: ToolTestRequest): Promise<ToolTestResponse> {
    // 后端使用 /tools/{id}/test，参数字段名为 arguments
    const args = req.input ?? {}
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
    return fetch(`${getBase()}/tools/${toolId}/yaml`).then(res => {
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
  create(data: CreateAgentRequest): Promise<AgentDetail> {
    return request('/agents', {
      method: 'POST',
      body: JSON.stringify(data)
    })
  },
  update(id: string, data: UpdateAgentRequest): Promise<AgentDetail> {
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
    const res = await fetch(`${getBase()}/agents/${id}/test-chat/stream`, {
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
    return fetch(`${getBase()}/agents/${agentId}/markdown`).then(res => {
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

/**
 * 浏览器人工接管 API — Agent 因登录 / 验证码 / 人机验证等场景挂起时，
 * 前端完成确认后调用本接口恢复 Agent。
 */
export const browserTakeoverApi = {
  /**
   * 通知后端用户已完成（或取消）浏览器人工接管，触发 Agent 恢复。
   *
   * @param turnId     所在对话 turn 的 ID
   * @param sessionId  浏览器会话 ID（对应 SuspendReason.BrowserTakeover.sessionId）
   * @param cancelled  是否取消任务
   * @param note       用户备注（可空）
   */
  resume(turnId: string, sessionId: string, cancelled = false, note?: string): Promise<void> {
    const search = new URLSearchParams()
    search.set('sessionId', sessionId)
    search.set('cancelled', String(cancelled))
    if (note) search.set('note', note)
    return request(`/agent/browser-takeover/${encodeURIComponent(turnId)}/resume?${search.toString()}`, {
      method: 'POST',
    })
  },
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

/** 通知管理 API */
export const notificationApi = {
  /** 获取通知列表（分页） */
  listNotifications(userId: string, page?: number, size?: number): Promise<PageResult<NotificationItem>> {
    const params = new URLSearchParams()
    params.append('userId', userId)
    if (page !== undefined) params.append('page', String(page))
    if (size !== undefined) params.append('size', String(size))
    return request(`/notifications?${params.toString()}`)
  },

  /** 标记单条通知已读 */
  markAsRead(id: string): Promise<NotificationItem> {
    return request(`/notifications/${id}/read`, { method: 'PUT' })
  },

  /** 标记所有通知已读 */
  markAllAsRead(userId: string): Promise<{ updatedCount: number }> {
    return request(`/notifications/read-all?userId=${encodeURIComponent(userId)}`, { method: 'PUT' })
  },

  /** 提交主动提醒反馈 */
  submitFeedback(id: string, feedbackType: string): Promise<NotificationItem> {
    return request(`/notifications/${id}/feedback`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ feedbackType }),
    })
  }
}

// ========== 主动引擎 API ==========

export const proactiveApi = {
  /** 获取待阅队列 */
  getQueue(userId: string = 'default', limit: number = 20): Promise<QueuedAction[]> {
    return request(`/proactive/queue?userId=${encodeURIComponent(userId)}&limit=${limit}`)
  },

  /** 标记队列条目已展示 */
  markShown(id: string): Promise<void> {
    return request(`/proactive/queue/${id}/shown`, { method: 'PUT' })
  },

  /** 删除队列条目 */
  deleteQueueItem(id: string): Promise<void> {
    return request(`/proactive/queue/${id}`, { method: 'DELETE' })
  },

  /** 获取信任状态列表 */
  getTrustStatus(userId: string = 'default'): Promise<TrustStatus[]> {
    return request(`/proactive/trust?userId=${encodeURIComponent(userId)}`)
  },

  /** 确认信任升级 */
  confirmUpgrade(behavior: string, userId: string = 'default'): Promise<TrustStatus> {
    return request(`/proactive/trust/${behavior}/confirm?userId=${encodeURIComponent(userId)}`, { method: 'POST' })
  },

  /** 获取主动引擎配置 */
  getConfig(userId: string = 'default'): Promise<ProactiveConfig> {
    return request(`/proactive/config?userId=${encodeURIComponent(userId)}`)
  },

  /** 更新主动引擎配置 */
  updateConfig(data: ProactiveConfigUpdate): Promise<ProactiveConfig> {
    return request('/proactive/config', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    })
  },
}

// ========== 记忆管理 API ==========


/** 将参数对象转为 URL 查询字符串，跳过 undefined 和空字符 */
function toQueryString(params: Record<string, unknown>): string {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    query.append(key, String(value))
  }
  return query.toString()
}

/** 记忆管理 API */
export const memoryApi = {
  /** 统计概览 */
  getStats: () => request<MemoryStats>('/memories/stats'),

  /** 统一搜索 */
  search: (q: string, top_k = 10) =>
    request<MemorySearchResult[]>(`/memories/search?q=${encodeURIComponent(q)}&top_k=${top_k}`),

  /** 手动巩固 */
  triggerConsolidation: () =>
    request<{ status: string; message: string }>('/memories/consolidate', { method: 'POST' }),

  // L3 实体
  listEntities: (params: EntityListParams) =>
    request<PageResult<EntitySummary>>(`/memories/entities?${toQueryString(params as unknown as Record<string, unknown>)}`),
  getEntity: (id: string) => request<EntityDetail>(`/memories/entities/${id}`),
  getEntityHistory: (id: string) => request<EntityDetail[]>(`/memories/entities/${id}/history`),
  getEntityProvenances: (id: string, params?: EntityProvenanceParams) => {
    const query = params ? toQueryString(params as unknown as Record<string, unknown>) : ''
    return request<EntityProvenance[]>(`/memories/entities/${id}/provenances${query ? `?${query}` : ''}`)
  },
  listRecentProvenances: (params?: MemoryProvenanceListParams) => {
    const query = params ? toQueryString(params as unknown as Record<string, unknown>) : ''
    return request<MemoryProvenanceSummary[]>(`/memories/provenances/recent${query ? `?${query}` : ''}`)
  },
  getRelatedEntities: (id: string, maxDepth = 2) =>
    request<EntitySummary[]>(`/memories/entities/${id}/related?maxDepth=${maxDepth}`),
  createEntity: (req: EntityCreateRequest) =>
    request<EntityDetail>('/memories/entities', { method: 'POST', body: JSON.stringify(req) }),
  updateEntity: (id: string, req: EntityUpdateRequest) =>
    request<EntityDetail>(`/memories/entities/${id}`, { method: 'PUT', body: JSON.stringify(req) }),
  deleteEntity: (id: string) =>
    request<void>(`/memories/entities/${id}`, { method: 'DELETE' }),

  // L3 关系
  listRelations: (params: RelationListParams) =>
    request<PageResult<RelationItem>>(`/memories/relations?${toQueryString(params as unknown as Record<string, unknown>)}`),

  // L2 对话
  listConversations: (params: ConversationListParams) =>
    request<PageResult<ConversationSummary>>(`/memories/conversations?${toQueryString(params as unknown as Record<string, unknown>)}`),
  getConversation: (id: string) => request<ConversationDetail>(`/memories/conversations/${id}`),
  deleteConversation: (id: string) =>
    request<void>(`/memories/conversations/${id}`, { method: 'DELETE' }),

  // L4 模板
  listTemplates: (params: TemplateListParams) =>
    request<PageResult<ProcedureTemplate>>(`/memories/templates?${toQueryString(params as unknown as Record<string, unknown>)}`),
  getTemplate: (id: string) => request<ProcedureTemplate>(`/memories/templates/${id}`),
  deleteTemplate: (id: string) =>
    request<void>(`/memories/templates/${id}`, { method: 'DELETE' }),

  // L4 偏好
  listPreferences: (category?: string) =>
    request<PreferenceRule[]>(
      `/memories/preferences${category ? `?category=${encodeURIComponent(category)}` : ''}`
    ),
  deletePreference: (id: string) =>
    request<void>(`/memories/preferences/${id}`, { method: 'DELETE' }),

  // 遗忘日志
  listForgettingLogs: (params: ForgettingLogListParams) =>
    request<PageResult<ForgettingLog>>(`/memories/forgetting-logs?${toQueryString(params as unknown as Record<string, unknown>)}`),
}
