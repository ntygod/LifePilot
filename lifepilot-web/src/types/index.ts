// LifePilot 前端类型定义

/** 会话摘要 */
export interface ChatSession {
  id: string
  title: string
  createdAt: string   // ISO 8601
  updatedAt: string
  /** 是否置顶 */
  pinned?: boolean
  /** 是否归档 */
  archived?: boolean
  /** 最近一条消息摘要（截断显示） */
  lastMessagePreview?: string
  /** 会话类型标记（可选） */
  type?: string
}

/** 聊天消息附件（前端展示用） */
export interface ChatAttachment {
  /** 后端返回的文件 ID（用于后续多模态路由与检索） */
  fileId: string
  /** 可访问的文件 URL（通常为后端提供的相对路径，经网关 / CDN 代理） */
  url: string
  /** 原始文件名 */
  filename: string
  /** 文件大小（字节） */
  size: number
  /** MIME 类型 */
  type: string
  /** 是否为图片类型，便于前端按图片样式渲染缩略图 */
  isImage: boolean
}

/** 消息 */
export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  a2uiComponents?: A2uiComponent[]
  timestamp: number
  /**
   * 本条消息对应的一轮推理概要摘要。
   * 由后端在 SSE DONE 事件中通过 reasoningSummary 字段返回。
   */
  reasoningSummary?: string
  /** 前端侧的发送 / 处理状态标记，用于展示"发送中 / 失败 / 可重试" */
  status?: 'pending' | 'success' | 'error'
  /** 与本条消息相关的错误说明（仅在 status === 'error' 时展示） */
  errorMessage?: string
  /** 后端执行轨迹 ID（如存在），用于跳转到 Trace 详情 */
  traceId?: string
  /** 高亮后的 HTML 内容（用于搜索高亮） */
  highlightedContent?: string
  /** 附件列表（图片/文件等），用于前端展示缩略图与下载入口 */
  attachments?: ChatAttachment[]
}

/** A2UI 组件节点（邻接表） */
export interface A2uiComponent {
  id: string
  type: string
  properties: Record<string, unknown>
  children: string[]
  signal?: A2uiSignal
}

/** A2UI 信号 */
export interface A2uiSignal {
  name: string
  payload: Record<string, unknown>
}

/** Token 消耗统计 */
export interface TokenUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  modelId: string
}

/** 用户设置 */
export interface UserSettings {
  theme: 'light' | 'dark' | 'system'
  language: string
  llmProvider: string
  enableStreaming?: boolean
  enableFunctionCall?: boolean
  enableKnowledgeBase?: boolean
  enableToolCall?: boolean
  sessionTimeout?: number
  maxRecentTurns?: number
  workingMemoryBudget?: number
  compressionThreshold?: number
  maxRetentionDays?: number
}

/** SSE token 事件 */
export interface SseTokenEvent {
  content: string
  index: number
}

/** 推理过程事件类型 */
export type ReasoningEventType =
  | 'AGENT_START'
  | 'CONTEXT_LOADING'
  | 'MEMORY_RETRIEVAL'
  | 'TOOL_CALL_START'
  | 'TOOL_CALL_END'
  | 'THINKING_STEP'
  | 'PLAN_UPDATED'
  | 'ANSWER_DRAFTING'
  | 'ANSWER_FINALIZED'
  | 'ERROR'

/** 推理过程事件（Reasoning Timeline） */
export interface ReasoningEvent {
  id: string
  type: ReasoningEventType
  title: string
  description?: string
  toolName?: string
  createdAt: string
  extra?: Record<string, any>
}

/** SSE 完成事件 */
export interface SseDoneEvent {
  messageId: string
  /** 可选：完整消息内容（非流式响应时由后端直接返回） */
  content?: string
  /** 可选：Token 使用统计 */
  tokenUsage?: TokenUsage
  /** 可选：本轮执行对应的 Trace Id（如后端有返回） */
  traceId?: string
  /** 可选：会话 ID（后端返回，用于前端同步） */
  sessionId?: string
  /** 可选：消息完成时间戳（毫秒，后端返回） */
  timestamp?: number
  /** 可选：本轮推理概要（已检索记忆/工具调用等） */
  reasoningSummary?: string
  /** 可选：按多模态结构返回的完整内容列表（兼容未来拓展） */
  contents?: Array<{
    type: 'TEXT' | 'IMAGE' | 'AUDIO' | 'VIDEO' | 'FILE'
    text?: string
    url?: string
    mimeType?: string
    metadata?: Record<string, any>
  }>
}

/** SSE 错误事件 */
export interface SseErrorEvent {
  code: number
  message: string
  /** 可选：错误对应的 Trace Id，便于前端跳转调试 */
  traceId?: string
}

/** 非流式聊天响应 */
export interface ChatResponse {
  messageId: string
  content: string
  a2ui?: { components: A2uiComponent[] }
  tokenUsage?: TokenUsage
  /** 本轮对话中使用到的知识库 / 文档来源等（由后端返回，前端只做轻量展示） */
  sources?: Array<{
    type: 'knowledgeBase' | 'document' | 'tool' | 'workflow'
    id: string
    name: string
    extra?: Record<string, unknown>
  }>
}

/** 统一错误响应 */
export interface ErrorResponse {
  code: number
  message: string
  timestamp: string
}

// ========== 模块 19: Web UI 功能页面类型 ==========

/** 知识库 */
export interface KnowledgeBase {
  id: string
  name: string
  description: string
  embeddingModel: string
  documentCount: number
  totalChunks: number
  createdAt: string
  updatedAt: string
}

/** 创建知识库请求 */
export interface CreateKbRequest {
  name: string
  description: string
  embeddingModel?: string
}

/** 知识库文档 */
export interface KbDocument {
  id: string
  knowledgeBaseId: string
  fileName: string
  fileSize: number
  mimeType: string
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'ERROR'
  chunkCount: number
  errorMessage?: string
  createdAt: string
  updatedAt: string
}

/** 文档分块 */
export interface DocumentChunk {
  id: string
  documentId: string
  knowledgeBaseId: string
  content: string
  contextPrefix?: string
  chunkIndex: number
  startOffset: number
  endOffset: number
  tokenCount: number
  contentHash: string
  headingHierarchy?: string[]
  pageNumber?: number
  metadata?: Record<string, unknown>
  createdAt: string
}

/** 知识库统计信息 */
export interface KbStats {
  documentCount: number
  totalChunks: number
  totalSize: number
  indexStatus: 'HEALTHY' | 'PROCESSING' | 'PARTIAL_FAILURE'
  processingDocuments: number
  errorDocuments: number
}

/** 处理日志 */
export interface ProcessingLog {
  id: string
  documentId: string
  stage: 'UPLOAD' | 'PARSE' | 'CHUNK' | 'INDEX' | 'COMPLETE' | 'ERROR'
  message: string
  timestamp: string
  error?: string
}

/** 测试检索结果 */
export interface TestRetrievalResult {
  query: string
  chunks: Array<{
    chunkId: string
    documentId: string
    documentName: string
    content: string
    similarity: number
    metadata?: Record<string, unknown>
  }>
  answer?: string
}

/** Skill 摘要 */
export interface SkillSummary {
  id: string
  name: string
  description: string
  version: string
  sourceType: 'Builtin' | 'UserDefined' | 'AutoGenerated'
}

/** Skill 详情 */
export interface SkillDetail extends SkillSummary {
  systemPrompt: string
  allowedTools: string[]
  metadata: Record<string, string>
  preferredProviderId?: string
}

/** MCP Server */
export interface McpServer {
  name: string
  state: 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING'
  toolCount: number
  connectedSince?: string
  lastError?: string
  config?: McpServerConfig
}

/** MCP Server 配置 */
export interface McpServerConfig {
  transport: 'STDIO' | 'STREAMABLE_HTTP' | 'SSE_LEGACY' | 'stdio' | 'sse'
  command?: string
  args?: string[]
  env?: Record<string, string>
  baseUrl?: string
  url?: string
  timeoutSeconds?: number
  timeout?: number
  maxRetries?: number
  autoConnect?: boolean
  reconnect?: boolean
  reconnectDelay?: number
  maxReconnectAttempts?: number
  healthCheckInterval?: number
}

/** MCP 工具 */
export interface McpTool {
  id: string
  name: string
  description: string
}

/** 轨迹列表项 */
export interface TraceItem {
  id: string
  sessionId: string
  userMessage: string
  success: boolean
  totalSteps: number
  totalTokens: number
  durationMs: number
  createdAt: string
}

/** 轨迹详情 */
export interface TraceDetail extends TraceItem {
  finalOutput?: string
  errorMessage?: string
  terminationReason?: string
  modelId?: string
}

/** 轨迹步骤 */
export interface TraceStep {
  id: string
  stepIndex: number
  phaseBefore: string
  phaseAfter: string
  actionType: string
  actionJson?: string
  toolId?: string
  toolInputJson?: string
  toolOutput?: string
  success: boolean
  blocked: boolean
  blockReason?: string
  tokensUsed: number
  latencyMs: number
  createdAt: string
}

/** 分页结果 */
export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
}

/** 工作流列表项 */
export interface WorkflowItem {
  id: string
  name: string
  description: string
  enabled: boolean
  triggerTypes: string[]
  version: string
}

/** 工作流详情 */
export interface WorkflowDetail extends WorkflowItem {
  triggers: unknown[]
  inputs: Record<string, unknown>
  steps: unknown[]
  metadata: Record<string, string>
  yaml?: string
}

/** 工作流执行记录 */
export interface WorkflowExecution {
  id: string
  workflowId: string
  state: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  currentStepIndex: number
  startedAt?: string
  completedAt?: string
  failureReason?: string
  createdAt: string
}

/** Agent 列表项 */
export interface AgentSummary {
  id: string
  name: string
  description?: string
  type: 'default' | 'custom' | 'workflow'
  modelId?: string
  knowledgeBaseCount: number
  enabled: boolean
  updatedAt: string
  createdAt: string
  tags?: string[]
  avatar?: string
}

/** Agent 详情 */
export interface AgentDetail extends AgentSummary {
  systemPrompt: string
  modelConfig: {
    modelId: string
    temperature?: number
    maxTokens?: number
    topP?: number
  }
  knowledgeBases: Array<{
    id: string
    name: string
    topK?: number
    maxContextTokens?: number
  }>
  enabledTools: string[]
  metadata?: Record<string, unknown>
}

/** Tool 摘要 */
export interface ToolSummary {
  id: string
  name: string
  displayName?: string
  description?: string
  type: 'PLUGIN' | 'SKILL' | 'MCP'
  source: string
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH'
  enabled: boolean
  idempotent?: boolean
}

/** Tool 详情 */
export interface ToolDetail extends ToolSummary {
  inputSchema?: Record<string, any>
  outputSchema?: Record<string, any>
  budget?: {
    timeoutSeconds?: number
    maxRetries?: number
    maxCostCents?: number
  }
  tags?: string[]
  sideEffects?: string[]
  metadata?: Record<string, unknown>
}

/** Tool 测试请求 */
export interface ToolTestRequest {
  toolId: string
  input?: Record<string, any>
  arguments?: Record<string, any> // 兼容旧字段名
}

/** Tool 测试响应 */
export interface ToolTestResponse {
  success: boolean
  output?: any
  error?: string
  executionTimeMs?: number
  meta?: {
    durationMs?: number
    toolId?: string
    action?: string
  }
}

/** Analytics 用量统计 */
export interface UsageStats {
  totalRequests: number
  totalTokens: number
  promptTokens: number
  completionTokens: number
  estimatedCost?: number
  timeRange: {
    from: string
    to: string
  }
  dailyStats?: Array<{
    date: string
    requests: number
    tokens: number
    promptTokens: number
    completionTokens: number
    cost?: number
  }>
}

/** Analytics Agent 统计 */
export interface AgentStats {
  agentId: string
  agentName: string
  callCount: number
  avgResponseTime: number
  failureRate: number
  totalTokens: number
}

/** Analytics 知识库统计 */
export interface KnowledgeBaseStats {
  kbId: string
  kbName: string
  retrievalCount: number
  hitRate?: number
  avgRetrievalTime?: number
}

/** Analytics 工具统计 */
export interface ToolStats {
  toolId: string
  toolName: string
  callCount: number
  successCount: number
  failureCount: number
  avgLatency: number
}

/** LLM Provider 详情 */
export interface LlmProviderDetail {
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

/** Provider 能力类型 */
export type ProviderCapability = 'STREAMING' | 'FUNCTION_CALLING' | 'EMBEDDING' | 'VISION' | 'AUDIO'
