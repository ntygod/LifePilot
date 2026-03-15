// ZhiWei 前端类型定义

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

export interface ChatSessionDetail extends ChatSession {
  knowledgeBaseIds: string[]
  preferredProviderId?: string
  temperature?: number
  maxTokens?: number
  messageCount: number
  totalTokens: number
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
  /** 本条消息对应的推理事件时间线（由前端在 SSE 流结束时从 useChat 快照保存） */
  reasoningEvents?: ReasoningEvent[]
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
  /** 可选：本条消息对应的 Token 使用统计（如后端在 DONE 事件中返回） */
  tokenUsage?: TokenUsage
  modelId?: string
  /** 可选：本条消息对应的模型 ID（如可用），用于消息级调试展示 */
  preferredProviderId?: string
  /** 可选：本轮执行涉及到的知识库 / 文档等来源摘要 */
  sources?: SourceSummary[]
  /** 可选：本轮执行涉及到的工具调用摘要列表 */
  toolsSummary?: ToolCallSummary[]
  /** 消息是否已折叠（长消息场景，前端本地状态） */
  collapsed?: boolean
  /** 用户反馈状态（前端本地状态，不持久化到后端） */
  feedbackStatus?: 'liked' | 'disliked' | null
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

/** A2UI 信号上下文（前端本地与回传时附带） */
export interface A2uiSignalContext {
  componentId?: string
  messageId?: string
  traceId?: string
  signalName?: string
}

/** A2UI 交互运行时状态 */
export interface A2uiSignalRuntime {
  status: 'idle' | 'sending' | 'success' | 'error'
  error?: string | null
  updatedAt: number
}

/** Token 消耗统计 */
export interface TokenUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  modelId: string
  /** 可选：底层 Provider 标识（用于调试与计费对账） */
  providerId?: string
}

/** 用户设置 */
export interface UserSettings {
  theme: 'light' | 'dark' | 'system'
  language: string
  llmProvider: string
  sceneProviders?: Record<string, string>
  layoutDensity?: 'compact' | 'standard'
  fontSize?: 'small' | 'medium' | 'large'
  timeFormat?: '12h' | '24h'
  showTokenUsage?: boolean
  autoExpandCodeBlocks?: boolean
  collapseLongReplies?: boolean
  collapseThreshold?: number
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
  /** 可选：本轮回答附带的 A2UI 组件树快照 */
  a2uiComponents?: A2uiComponent[]
  /** 可选：Token 使用统计 */
  tokenUsage?: TokenUsage
  /** 可选：聚合后的 Token 使用概要（input/output/total），与后端 doneData.usage 对齐 */
  usage?: {
    inputTokens: number
    outputTokens: number
    totalTokens: number
  }
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
  /** 可选：知识库 / 文档 / 工具等来源摘要（用于轻量 UI 展示） */
  sources?: SourceSummary[]
  /** 可选：本轮工具调用摘要列表（来自 Trace ToolCallStep 聚合） */
  toolsSummary?: ToolCallSummary[]
}

/** SSE 错误事件 */
export interface SseErrorEvent {
  code: number
  message: string
  /** 可选：错误对应的 Trace Id，便于前端跳转调试 */
  traceId?: string
}

/** 工具确认请求（SSE 事件 payload） */
export interface ToolConfirmationRequest {
  requestId: string
  toolId: string
  toolName: string
  riskLevel: 'HIGH' | 'CRITICAL'
  approvalMode: string
  message: string
  timestamp: string
}

/** SSE 媒体数据事件（截图等二进制数据通过独立事件传输，避免被截断） */
export interface SseMediaEvent {
  /** 媒体字段名（如 screenshot） */
  field: string
  /** MIME 类型（如 image/png） */
  mimeType: string
  /** Base64 编码的媒体数据 */
  data: string
}

/** 非流式聊天响应 */
export interface ChatResponse {
  messageId: string
  content: string
  a2uiComponents?: A2uiComponent[]
  tokenUsage?: TokenUsage
  traceId?: string
  /** 本轮对话中使用到的知识库 / 文档来源等（由后端返回，前端只做轻量展示） */
  sources?: SourceSummary[]
}

/** 执行来源摘要（知识库 / 文档 / 工具 / 工作流） */
export interface SourceSummary {
  type: 'knowledgeBase' | 'document' | 'tool' | 'workflow'
  id: string
  name: string
  extra?: Record<string, unknown>
}

/** 工具调用摘要（用于单轮执行概要与调试视图） */
export interface ToolCallSummary {
  toolId: string
  action?: string
  success: boolean
  latencyMs: number
  hasMoreSteps?: boolean
  /** 工具调用的输入参数摘要（截断展示，由后端 done 事件返回） */
  inputSummary?: string
  /** 工具调用的输出结果摘要（截断展示，由后端 done 事件返回） */
  outputSummary?: string
}

/** 统一错误响应 */
export interface ErrorResponse {
  code: number
  message: string
  timestamp: string
}

/** 会话配置（模型/温度/最大 Token/知识库选择） */
export interface SessionConfig {
  preferredProviderId?: string
  temperature?: number
  maxTokens?: number
  knowledgeBaseIds?: string[]
}

// ========== 模块 19: Web UI 功能页面类型 ==========

/** 知识库 */
export interface KnowledgeBase {
  id: string
  name: string
  description: string
  embeddingModel: string
  rerankerModel?: string
  chunkingStrategy?: string
  tags?: string[]
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

/** 更新知识库请求 */
export interface UpdateKbRequest {
  name?: string
  description?: string
  embeddingModel?: string
  rerankerModel?: string | null
  chunkingStrategy?: string
  chunkingConfig?: Record<string, unknown>
  tags?: string[]
}

/** 知识库文档 */
export interface KbDocument {
  id: string
  knowledgeBaseId: string
  fileName: string
  fileSize: number
  mimeType: string
  status: 'UPLOADING' | 'PARSING' | 'CHUNKING' | 'INDEXING' | 'EXTRACTING' | 'READY' | 'UPDATING' | 'DELETING' | 'ERROR'
  chunkCount: number
  errorMessage?: string | null
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
  source: { type: 'Builtin' | 'UserDefined' | 'AutoGenerated' | 'Marketplace'; [key: string]: unknown }
  metadata?: Record<string, string>
}

/** Skill 详情 */
export interface SkillDetail extends SkillSummary {
  instructions: string
  suggestedTools: string[]
  metadata: Record<string, string>
}

/** MCP Server */
export interface McpServer {
  name: string
  state:
    | 'DISCONNECTED'
    | 'CONNECTING'
    | 'CONNECTED'
    | 'RECONNECTING'
    | 'INITIALIZING'
    | 'HEALTH_CHECK'
    | 'DISCONNECTING'
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
  /** 输入参数 JSON Schema（可选，后端 ToolContract 序列化返回） */
  inputSchema?: Record<string, any>
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
  tags?: string[]
}

/** 工作流输入参数定义（对齐后端 WorkflowInputParam record） */
export interface WorkflowInputParam {
  name: string
  type: 'string' | 'number' | 'boolean' | 'list' | 'map'
  required: boolean
  defaultValue?: unknown
  description?: string
}

/** 工作流详情 */
export interface WorkflowDetail extends WorkflowItem {
  triggers: unknown[]
  inputs: Record<string, WorkflowInputParam>
  steps: unknown[]
  metadata: Record<string, string>
  yaml?: string
}

/** 工作流执行记录 */
export interface WorkflowExecution {
  id: string
  workflowId: string
  state: 'CREATED' | 'RUNNING' | 'PAUSED' | 'WAITING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  completedStepIds: string[]
  pendingApprovalStepId?: string
  startedAt?: string
  completedAt?: string
  failureReason?: string
  createdAt: string
  updatedAt: string
}

export interface WorkflowExecutionsSnapshot {
  executions: WorkflowExecution[]
}

/** 工作流审计事件类型 */
export type WorkflowEventType =
  | 'INSTANCE_CREATED'
  | 'INSTANCE_STATE_CHANGED'
  | 'STEP_STARTED'
  | 'STEP_COMPLETED'
  | 'STEP_FAILED'
  | 'STEP_SKIPPED'
  | 'APPROVAL_REQUESTED'
  | 'APPROVAL_DECIDED'

/** 工作流审计事件 */
export interface WorkflowEvent {
  id: string
  instanceId: string
  workflowId: string
  type: WorkflowEventType
  stepId?: string
  dataJson?: string
  createdAt: string
}

export interface WorkflowTimelineSnapshot {
  events: WorkflowEvent[]
}

/** 审批请求 */
export interface ApprovalRequest {
  decision: 'APPROVED' | 'REJECTED'
  decidedBy: string
  reason?: string
}

/** 工作流步骤执行日志 */
export interface StepLog {
  id: string
  instanceId: string
  stepId: string
  stepType: string
  state: 'COMPLETED' | 'FAILED' | 'SKIPPED'
  attempt: number
  retryCount: number
  inputJson?: string
  outputJson?: string
  errorMessage?: string
  startedAt: string
  completedAt: string
  durationMs: number
  createdAt: string
}

export interface WorkflowStepLogsSnapshot {
  stepLogs: StepLog[]
}

// ========== 工作流成熟化需求类型定义 ==========

/** 工作流执行统计 */
export interface WorkflowStats {
  workflowId: string
  totalExecutions: number
  successCount: number
  failedCount: number
  avgDurationMs: number
  recentTrend: DailyTrend[]
}

/** 每日执行趋势 */
export interface DailyTrend {
  date: string
  count: number
  successCount: number
}

/** 步骤执行统计 */
export interface StepStats {
  stepId: string
  stepType: string
  executionCount: number
  successRate: number
  avgDurationMs: number
  maxDurationMs: number
  totalRetries: number
}

/** 步骤输出详情 */
export interface StepOutput {
  stepId: string
  output: unknown
  durationMs: number
  retryCount: number
  errorMessage?: string
  state: string
}

/** DAG 数据 */
export interface DagData {
  nodes: DagNode[]
  edges: DagEdge[]
  levels: string[][]
}

/** DAG 节点 */
export interface DagNode {
  id: string
  name: string
  type: string
  dependsOn: string[]
}

/** DAG 边 */
export interface DagEdge {
  from: string
  to: string
}

/** 试运行结果 */
export interface DryRunResult {
  steps: DryRunStepTrace[]
  dagOrder: string[]
  warnings: string[]
}

/** 试运行步骤轨迹 */
export interface DryRunStepTrace {
  stepId: string
  stepName: string
  stepType: string
  resolvedParams: Record<string, unknown>
  conditionResult?: boolean
  branch?: string
  loopIterations?: number
}

/** YAML 校验响应 */
export interface ValidationResponse {
  valid: boolean
  errors: string[]
  warnings: string[]
  dagValid: boolean
  dagError?: string
}

/** 步骤类型 Schema */
export interface StepTypeSchema {
  stepType: string
  label: string
  description: string
  params: ParamSchema[]
}

/** 参数 Schema */
export interface ParamSchema {
  name: string
  type: string
  required: boolean
  defaultValue?: unknown
  description?: string
  inputType?: string
  options?: OptionItem[]
  placeholder?: string
  example?: string
  validationPattern?: string
  validationMessage?: string
}

/** 选项项 */
export interface OptionItem {
  value: string
  label: string
}

/** Agent 列表项 */
export type AgentType = 'default' | 'custom' | 'workflow' | 'marketplace'

export interface AgentLlmConfig {
  preferredProviderId?: string
  temperature?: number
  maxTokens?: number
  topP?: number
}

export interface AgentKnowledgeBaseBinding {
  id: string
  name: string
  topK?: number
  maxContextTokens?: number
}

export interface AgentSummary {
  id: string
  name: string
  description?: string
  type: AgentType
  preferredProviderId?: string
  knowledgeBaseCount: number
  enabled: boolean
  status: string
  updatedAt: string
  createdAt: string
  tags?: string[]
  avatar?: string
  /** Agent 来源类型（MarkdownDefined / Builtin 等） */
  source?: string
}

/** Agent 详情 */
export interface AgentDetail extends AgentSummary {
  systemPrompt: string
  llmConfig: AgentLlmConfig
  knowledgeBases: AgentKnowledgeBaseBinding[]
  enabledTools: string[]
  metadata?: Record<string, unknown>
}

export interface CreateAgentRequest {
  name: string
  description?: string
  systemPrompt?: string
  preferredProviderId?: string
  temperature?: number
  maxTokens?: number
  topP?: number
  knowledgeBaseIds?: string[]
  toolIds?: string[]
  tags?: string[]
  metadata?: Record<string, unknown>
}

export interface UpdateAgentRequest {
  name?: string
  description?: string
  systemPrompt?: string
  preferredProviderId?: string
  temperature?: number
  maxTokens?: number
  topP?: number
  knowledgeBaseIds?: string[]
  toolIds?: string[]
  tags?: string[]
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
  exportable?: boolean
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
  inputTokens: number
  outputTokens: number
  estimatedCost?: number
  timeRange: {
    from: string
    to: string
  }
  dailyStats?: Array<{
    date: string
    requests: number
    tokens: number
    inputTokens: number
    outputTokens: number
    cost?: number
  }>
}

/**
 * Token 消耗统计（Trace 维度）
 *
 * 对指定时间范围内的 Trace 进行聚合统计，用于 Trace 页面 Token 分析。
 */
export interface TokenConsumptionStats {
  /** 统计时间范围内的 Trace 数量 */
  traceCount: number
  /** 所有 Trace 的总 Token 数 */
  totalTokens: number
  /** 所有 Trace 的总输入 Token 数 */
  totalInputTokens: number
  /** 所有 Trace 的总输出 Token 数 */
  totalOutputTokens: number
  /** 每条 Trace 平均 Token 数 */
  avgTokensPerTrace: number
  /** 单条 Trace 的最大 Token 数 */
  maxTokens: number
  /** 成功的 Trace 数量 */
  successCount: number
  /** 平均耗时（毫秒） */
  avgDurationMs: number
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
export type ProviderCapability = 'STREAMING' | 'FUNCTION_CALLING' | 'EMBEDDING' | 'VISION' | 'AUDIO' | 'RERANK'

// ========== 模块 20: Observability 统计与评估 ==========

/**
 * 轨迹概览统计数据
 *
 * 用于 Trace 列表页顶部的统计卡片区域，展示整体运行情况。
 */
export interface OverviewStats {
  /** 轨迹总数 */
  totalTraces: number
  /** 成功轨迹数量 */
  successCount: number
  /** 失败轨迹数量 */
  failureCount: number
  /** 成功率（0-1 小数） */
  successRate: number
  /** 平均步骤数 */
  avgSteps: number
  /** 平均耗时（毫秒） */
  avgDurationMs: number
  /** 总 Token 消耗 */
  totalTokens: number
  /** 平均每条轨迹 Token 消耗 */
  avgTokens: number
}

/**
 * 工具调用使用统计
 *
 * 用于工具统计列表，展示各工具的调用与成功情况。
 */
export interface ToolUsageStats {
  /** 工具唯一标识 */
  toolId: string
  /** 总调用次数 */
  callCount: number
  /** 成功调用次数 */
  successCount: number
  /** 失败调用次数 */
  failureCount: number
  /** 成功率（0-1 小数） */
  successRate: number
  /** 平均调用耗时（毫秒） */
  avgDurationMs: number
}

/**
 * 轨迹离线评估结果
 *
 * 对单条 Trace 的多维度质量评估，用于详情页展示。
 */
export interface EvaluationResult {
  /** 被评估的轨迹 ID */
  traceId: string
  /** 评估时间（ISO 8601） */
  evaluatedAt: string
  /** 工具选择合理性评分（0-1） */
  toolSelectionScore: number
  /** 参数合法性与幂等性评分（0-1） */
  parameterValidityScore: number
  /** 步骤数量与结构效率评分（0-1） */
  stepEfficiencyScore: number
  /** 策略与护栏合规性评分（0-1） */
  policyComplianceScore: number
  /** Token 使用效率评分（0-1） */
  tokenEfficiencyScore: number
  /** 综合评分（0-1） */
  overallScore: number
  /** 实际执行步骤数 */
  actualSteps: number
  /** 实际消耗 Token 数 */
  actualTokens: number
  /** 违规说明列表（如存在问题） */
  violations: string[]
  /** 优化建议列表 */
  suggestions: string[]
}

// ========== 知识库拖拽上传：前端本地类型 ==========

/**
 * 上传文件条目（UploadProgress 组件使用，纯前端状态）
 */
export interface UploadFileItem {
  /** 前端生成的唯一 ID（用于列表 key） */
  id: string
  /** 原始 File 对象引用（用于重试） */
  file: File
  /** 文件名 */
  fileName: string
  /** 上传状态 */
  status: 'waiting' | 'uploading' | 'success' | 'error'
  /** 错误信息（仅 status === 'error' 时有值） */
  errorMessage?: string
}


// ========== 模块 25: 扩展市场类型 ==========

/** 扩展类型枚举（对齐后端 ExtensionType） */
export type ExtensionType = 'SKILL' | 'AGENT' | 'WORKFLOW'

/** 扩展包元数据（对齐后端 ExtensionPackage record） */
export interface ExtensionPackage {
  id: string
  name: string
  type: ExtensionType
  description: string
  version: string
  author: string
  repoUrl: string
  filePath: string
  tags: string[]
  requirements: string[]
  minZhiweiVersion: string
  createdAt: string
  updatedAt: string
  downloads: number
  verified: boolean
  /** 已安装的版本（未安装时为 undefined） */
  installedVersion?: string
  /** 是否已安装 */
  installed: boolean
}

/** 向后兼容别名 */
export type SkillPackage = ExtensionPackage

/** 安全发现条目 */
export interface SecurityFinding {
  level: 'LOW' | 'MEDIUM' | 'HIGH'
  category: string
  description: string
}

/** 安全扫描报告 */
export interface SecurityReport {
  findings: SecurityFinding[]
  overallRisk: 'LOW' | 'MEDIUM' | 'HIGH'
}

/** 安装结果（对齐后端 InstallResult record） */
export interface InstallResult {
  success: boolean
  extensionId?: string
  extensionType?: ExtensionType
  securityReport?: SecurityReport
  requirements?: string[]
  errorMessage?: string
  requiresConfirmation: boolean
}

/** 后端分页结果（对齐 MarketplaceService.PagedResult） */
export interface PagedResult<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}


// ========== Web UI 深度调试与配置：新增类型 ==========

/** 上下文组装预览响应 */
export interface ContextPreviewResponse {
  segments: {
    systemPrompt: { content: string; tokens: number }
    conversationHistory: { content: string; tokens: number }
    memoryRetrieval: { content: string; tokens: number }
    toolResults: { content: string; tokens: number }
  }
  tokenBudget: TokenBudgetData
  totalTokens: number
  totalBudget: number
  degraded: boolean
}

/** Token 预算数据（对齐后端 TokenBudget record） */
export interface TokenBudgetData {
  systemPromptBudget: number
  historyBudget: number
  memoryBudget: number
  toolSchemaBudget: number
  toolResultBudget: number
  reservedBuffer: number
  systemPromptUsed: number
  historyUsed: number
  memoryUsed: number
  toolSchemaUsed: number
  toolResultUsed: number
}

/** 依赖图节点 */
export interface DependencyNode {
  id: string
  name: string
  type: 'AGENT' | 'SKILL' | 'TOOL'
  enabled: boolean
}

/** 依赖图边 */
export interface DependencyEdge {
  source: string
  target: string
  relation: string
}

/** 依赖图响应 */
export interface DependencyGraphResponse {
  nodes: DependencyNode[]
  edges: DependencyEdge[]
}

/** Tool 调用统计项 */
export interface ToolCallStats {
  toolId: string
  toolName: string
  callCount: number
  successCount: number
  failureCount: number
  avgLatencyMs: number
}

/** Tool 调用每日趋势 */
export interface ToolDailyTrend {
  date: string
  callCount: number
  successCount: number
  failureCount: number
}

/** Tool 统计 API 响应 */
export interface ToolAnalyticsResponse {
  toolStats: ToolCallStats[]
  dailyTrend: ToolDailyTrend[]
}

/** 错误趋势每日数据 */
export interface ErrorTrendDaily {
  date: string
  agentErrors: number
  toolErrors: number
  totalErrors: number
}

/** MCP 连接日志条目 */
export interface McpConnectionLog {
  timestamp: string
  eventType: 'CONNECT' | 'DISCONNECT' | 'ERROR' | 'RECONNECT'
  description: string
}

/** Tool 测试历史记录 */
export interface ToolTestHistoryItem {
  id: string
  timestamp: number
  input: Record<string, any>
  result: ToolTestResponse
}


// ========== MCP Server 状态 SSE 推送类型 ==========

/** MCP Server 状态快照（SSE mcp-status-snapshot 事件数据） */
export interface McpStatusSnapshot {
  servers: Array<{
    serverName: string
    state: McpServer['state']
    connectedSince?: string
    lastError?: string
  }>
}

/** MCP Server 状态变化（SSE mcp-status-change 事件数据） */
export interface McpStatusChange {
  serverName: string
  oldState: McpServer['state']
  newState: McpServer['state']
  timestamp: string
  error?: string
}

// ========== 通知中心类型定义 ==========

/** 通知紧急程度 */
export type NotificationUrgency = 'HIGH' | 'MEDIUM' | 'LOW'

/** 通知已读状态 */
export type NotificationReadStatus = 'UNREAD' | 'READ'

/** 通知条目（对齐后端 NotificationDto） */
export interface NotificationItem {
  id: string
  userId: string
  typeId?: string
  urgency: NotificationUrgency
  contentJson: string
  channel: string
  readStatus: NotificationReadStatus
  status: string
  metadataJson?: string
  sentAt: string  // ISO 8601
}


// ========== 记忆管理类型定义 ==========

/** 记忆统计概览（对应 MemoryStatsDto） */
export interface MemoryStats {
  conversationCount: number
  entityCount: number
  entityCountByType: Record<string, number>
  relationCount: number
  templateCount: number
  preferenceCount: number
  forgettingLogCount: number
  lastForgettingTime: string | null
}

/** 统一搜索结果（对应 MemorySearchResultDto） */
export interface MemorySearchResult {
  entityId: string
  entityType: string
  name: string
  description: string | null
  relevanceScore: number
}

/** 实体列表项（对应 EntitySummaryDto） */
export interface EntitySummary {
  id: string
  type: string
  typeLabel: string
  name: string
  description: string | null
  importanceScore: number
  accessCount: number
  version: number
  createdAt: string
  updatedAt: string
}

/** 实体详情（对应 EntityDetailDto） */
export interface EntityDetail {
  id: string
  type: string
  typeLabel: string
  name: string
  description: string | null
  properties: Record<string, unknown>
  version: number
  isCurrent: boolean
  validFrom: string
  validTo: string | null
  sourceConversationId: string | null
  extractionConfidence: number
  importanceScore: number
  accessCount: number
  lastAccessedAt: string | null
  createdAt: string
  updatedAt: string
}

/** 实体创建请求 */
export interface EntityCreateRequest {
  name: string
  type: string
  description?: string
  properties?: Record<string, unknown>
  importanceScore?: number
}

/** 实体更新请求 */
export interface EntityUpdateRequest {
  description?: string
  properties?: Record<string, unknown>
  importanceScore?: number
}

/** 实体列表查询参数 */
export interface EntityListParams {
  page?: number
  size?: number
  type?: string
  q?: string
  timeFrom?: string
  timeTo?: string
  sortBy?: string
  order?: string
}

/** 关系列表项（对应 RelationDto） */
export interface RelationItem {
  id: string
  sourceEntityId: string
  sourceEntityName: string
  sourceEntityType: string
  targetEntityId: string
  targetEntityName: string
  targetEntityType: string
  relationType: string
  strength: number
  validFrom: string
  validTo: string | null
  createdAt: string
}

/** 关系列表查询参数 */
export interface RelationListParams {
  page?: number
  size?: number
  entityId?: string
  relationType?: string
}

/** 对话列表项（对应 ConversationSummaryDto） */
export interface ConversationSummary {
  id: string
  sessionId: string
  goal: string
  summary: string | null
  messageCount: number
  createdAt: string
}

/** 对话详情（对应 ConversationRecord） */
export interface ConversationDetail {
  id: string
  sessionId: string
  goal: string
  summary: string | null
  messages: MessageRecord[]
  createdAt: string
  updatedAt: string
}

/** 消息记录（对应 MessageRecord） */
export interface MessageRecord {
  id: string
  conversationId: string
  role: string
  content: string
  compressedContent: string | null
  compressionLevel: 'ORIGINAL' | 'SUMMARY' | 'KEYPOINTS' | 'ARCHIVED'
  isPinned: boolean
  toolCallJson: string | null
  tokenCount: number
  createdAt: string
}

/** 对话列表查询参数 */
export interface ConversationListParams {
  page?: number
  size?: number
  q?: string
  timeFrom?: string
  timeTo?: string
}

/** 操作模板（对应 ProcedureTemplate） */
export interface ProcedureTemplate {
  templateId: string
  name: string
  description: string
  triggerIntent: string
  steps: TemplateStep[]
  variables: Record<string, string>
  successRate: number
  useCount: number
  lastUsedAt: string | null
  sourceTraceIds: string[]
  createdAt: string
  updatedAt: string
}

/** 模板步骤 */
export interface TemplateStep {
  stepIndex: number
  action: string
  toolName: string | null
  parameters: Record<string, unknown>
  expectedOutcome: string | null
}

/** 偏好规则（对应 PreferenceRule） */
export interface PreferenceRule {
  ruleId: string
  category: string
  key: string
  value: string
  confidence: number
  learnedFrom: string
  observationCount: number
  createdAt: string
  updatedAt: string
}

/** 模板列表查询参数 */
export interface TemplateListParams {
  page?: number
  size?: number
  q?: string
  sortBy?: string
  order?: string
}

/** 遗忘日志（对应 ForgettingLogDto） */
export interface ForgettingLog {
  id: string
  entityId: string
  entityName: string
  strategy: string
  actionTaken: string
  forgettingPriority: number
  reason: string
  createdAt: string
}

/** 遗忘日志查询参数 */
export interface ForgettingLogListParams {
  page?: number
  size?: number
  timeFrom?: string
  timeTo?: string
  strategy?: string
}

/** 实体类型枚举映射 */
export const ENTITY_TYPES = [
  { value: 'PERSON', label: '人物' },
  { value: 'ORGANIZATION', label: '组织' },
  { value: 'PLACE', label: '地点' },
  { value: 'EVENT', label: '事件' },
  { value: 'PROJECT', label: '项目' },
  { value: 'TOPIC', label: '话题' },
  { value: 'PREFERENCE', label: '偏好' },
  { value: 'HABIT', label: '习惯' },
  { value: 'GOAL', label: '目标' },
  { value: 'SKILL', label: '技能' },
  { value: 'CUSTOM', label: '自定义' },
] as const
