// ZhiWei 前端类型定义

/** 聊天会话 */
export interface ChatSession {
  id: string
  title: string
  createdAt: string   // ISO 8601
  updatedAt: string
  /** 是否置顶 */
  pinned?: boolean
  /** 是否归档 */
  archived?: boolean
  /** 最后一条消息预览 */
  lastMessagePreview?: string
  /** 会话类型标识 */
  type?: string
}

export interface ChatSessionDetail extends ChatSession {
  knowledgeBaseIds: string[]
  preferredProviderId?: string
  temperature?: number
  maxTokens?: number
  maxSteps?: number
  maxDurationSeconds?: number
  messageCount: number
  totalTokens: number
}

/** 聊天附件/文件上传 */
export interface ChatAttachment {
  /** 文件 ID */
  fileId: string
  /** 文件 URL / CDN 链接 */
  url: string
  /** 文件名 */
  filename: string
  /** 文件大小（字节） */
  size: number
  /** MIME 类型 */
  type: string
  /** 是否为图片 */
  isImage: boolean
}

export type CompletionMode = 'NORMAL' | 'DEGRADED' | 'SUSPENDED'
export type OutputContentRole = 'FINAL' | 'PROGRESS' | 'SUSPEND_PROMPT' | 'BLOCKED'

export type ChatTurnAction = 'SEND' | 'RETRY' | 'RESUME' | 'RESTART'

export type ChatTurnStatus = 'PENDING' | 'SUCCESS' | 'FAILED' | 'DEGRADED' | 'SUSPENDED'

/** 消息 */
export interface Message {
  id: string
  turnId?: string
  role: 'user' | 'assistant' | 'permission-approval'
  content: string
  a2uiComponents?: A2uiComponent[]
  timestamp: number
  /**
   * 推理摘要
   * 从 SSE DONE 事件中获取的 reasoningSummary
   */
  reasoningSummary?: string
  /** 推理过程事件列表（从 useChat 中收集） */
  reasoningEvents?: ReasoningEvent[]
  /** 消息状态：pending / success / error（对应 blocked） */
  status?: 'pending' | 'success' | 'error'
  turnStatus?: ChatTurnStatus
  /** 错误信息（status === 'error' 时存在） */
  errorMessage?: string
  suspendReasonType?: string
  suspendReasonSourceId?: string
  /** 关联 Trace ID */
  traceId?: string
  /** 完成模式 */
  completionMode?: CompletionMode
  /** 恢复自 traceId */
  resumedFromTraceId?: string
  /** 高亮 HTML 片段 */
  highlightedContent?: string
  /** 附件列表 */
  attachments?: ChatAttachment[]
  /** Token 使用情况（从 DONE 事件中获取） */
  tokenUsage?: TokenUsage
  modelId?: string
  /** 首选 Provider ID */
  preferredProviderId?: string
  /** 来源列表：知识库 / 工具调用结果 */
  sources?: SourceSummary[]
  /** 工具调用摘要 */
  toolsSummary?: ToolCallSummary[]
  /** ReAct 步骤列表（toolsSummary 的详细版本） */
  reactSteps?: ReactStepDto[]
  /** 是否折叠 */
  collapsed?: boolean
  /** 反馈状态 */
  feedbackStatus?: 'liked' | 'disliked' | null
  /** 权限审批请求 */
  permissionApprovals?: Record<string, PermissionApprovalRequest>
  /** 权限审批决议 */
  permissionApprovalResolutions?: Record<string, 'approved' | 'rejected' | 'expired'>
}

/** A2UI 组件 */
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

/** A2UI 信号上下文 */
export interface A2uiSignalContext {
  componentId?: string
  entryId?: string
  traceId?: string
  signalName?: string
}

/** A2UI 信号运行时状态 */
export interface A2uiSignalRuntime {
  status: 'idle' | 'sending' | 'success' | 'error'
  error?: string | null
  updatedAt: number
}

/** Token 使用统计 */
export interface TokenUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  modelId: string
  /** Provider ID */
  providerId?: string
}

/** 用户设置 */
export interface UserSettings {
  theme: 'light' | 'dark' | 'system'
  language: string
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

export interface SseInteractionEvent {
  interactionId: string
  type: 'INPUT' | 'CHOOSE' | 'CONFIRM' | 'NOTIFY'
  sessionId: string
  streamId?: string | null
  message: string
  options?: string[] | null
}

/** 推理过程事件类型：对应后端 pushReactStepEvent */
export type ReasoningEventType =
  | 'AGENT_START'
  | 'PROGRESS'
  | 'THOUGHT'
  | 'TOOL_CALL'
  | 'OBSERVATION'
  | 'ANSWER'
  | 'SUSPEND'
  | 'RESUME'
  | 'ANSWER_FINALIZED'

/** 推理过程事件 (Reasoning Timeline) */
export interface ReasoningEvent {
  id: string
  type: ReasoningEventType
  title: string
  description?: string
  toolName?: string
  createdAt: string
  extra?: Record<string, any>
}

// ===== ReactStep 类型定义（对应后端 ReactStepSerializer） =====

/** ReactStep 类型 */
export type ReactStepType = 'PROGRESS' | 'THOUGHT' | 'TOOL_CALL' | 'OBSERVATION' | 'ANSWER' | 'SUSPEND' | 'RESUME'

/** ReactStep 基础接口 */
interface ReactStepBase {
  type: ReactStepType
  index: number
}

export interface ProgressStep extends ReactStepBase {
  type: 'PROGRESS'
  content: string
}

/** 思考步骤 */
export interface ThoughtStep extends ReactStepBase {
  type: 'THOUGHT'
  content: string
}

/** 工具调用步骤 */
export interface ToolCallStep extends ReactStepBase {
  type: 'TOOL_CALL'
  toolId: string
  /** 工具名称（可选） */
  toolName?: string
  inputSummary: string
  latencyMs: number
}

/** 工具观察步骤 */
export interface ObservationStep extends ReactStepBase {
  type: 'OBSERVATION'
  toolId: string
  /** 工具名称（可选） */
  toolName?: string
  success: boolean
  outputSummary: string
  tokensUsed: number
}

/** 回答步骤 */
export interface AnswerStep extends ReactStepBase {
  type: 'ANSWER'
  content: string
}

/** 挂起步骤 */
export interface SuspendStep extends ReactStepBase {
  type: 'SUSPEND'
  reason: string
  suspendedAt: string
}

/** 恢复步骤 */
export interface ResumeStep extends ReactStepBase {
  type: 'RESUME'
  resumedAt: string
  suspendDurationMs: number
}

/** ReactStep DTO 联合类型 */
export type ReactStepDto = ProgressStep | ThoughtStep | ToolCallStep | ObservationStep | AnswerStep | SuspendStep | ResumeStep

/** SSE done 事件 */
export interface SseDoneEvent {
  entryId: string
  turnId?: string
  turnStatus?: ChatTurnStatus
  terminationReason?: string
  contentRole?: OutputContentRole
  /** 内容 */
  content?: string
  /** A2UI 组件列表 */
  a2uiComponents?: A2uiComponent[]
  /** Token 使用统计 */
  tokenUsage?: TokenUsage
  /** usage（兼容旧版） */
  usage?: {
    inputTokens: number
    outputTokens: number
    totalTokens: number
  }
  /** Trace Id */
  traceId?: string
  /** 完成模式 */
  completionMode?: CompletionMode
  resumedFromTraceId?: string
  sessionId?: string
  /** 时间戳 */
  timestamp?: number
  /** 推理摘要 */
  reasoningSummary?: string
  /** contents（多模态） */
  contents?: Array<{
    type: 'TEXT' | 'IMAGE' | 'AUDIO' | 'VIDEO' | 'FILE'
    text?: string
    url?: string
    mimeType?: string
    metadata?: Record<string, any>
  }>
  /** sources（知识库 / 工具） */
  sources?: SourceSummary[]
  /** toolsSummary */
  toolsSummary?: ToolCallSummary[]
  /** reactSteps */
  reactSteps?: ReactStepDto[]
}

/** SSE error 事件 */
export interface SseErrorEvent {
  code: number
  message: string
  turnId?: string
  turnStatus?: ChatTurnStatus
  /** Trace Id */
  traceId?: string
}

export interface SseAgentSuspendedEvent {
  traceId?: string
  sessionId?: string
  turnId?: string
  completionMode?: CompletionMode
  turnStatus?: ChatTurnStatus
  contentRole?: OutputContentRole
  reasonType?: string
  reasonSourceId?: string
  reasonDetail?: string
  terminationReason?: string
  content?: string
  suspendedAt?: string
}

/** 权限审批请求 SSE 事件 payload */
export interface PermissionApprovalRequest {
  requestId: string
  toolId: string
  toolName: string
  actionType: string
  riskLevel: 'HIGH' | 'CRITICAL'
  message: string
  availableSubjectTypes: string[]
  recommendedSubjectType: string
  resourceScope?: Record<string, unknown>
  timestamp: string
}

/** 授权记录 */
export interface PermissionGrant {
  id: string
  subjectType: 'SESSION' | 'WORKSPACE' | 'TASK' | 'USER'
  subjectId: string
  actionType: string
  riskCeiling: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  scope: Record<string, unknown>
  channels: string[]
  autonomousAllowed: boolean
  expiresAt?: string | null
  revokedAt?: string | null
  revokedBy?: string | null
  revokedReason?: string | null
  createdBy?: string | null
  sourceEntryId?: string | null
  reason?: string | null
  metadata: Record<string, unknown>
  createdAt: string
  updatedAt: string
}

/** 手动创建授权请求 */
export interface PermissionGrantCreateRequest {
  subjectType: 'SESSION' | 'WORKSPACE' | 'TASK' | 'USER'
  subjectId: string
  actionType: string
  riskCeiling: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  scope?: Record<string, unknown>
  channels?: string[]
  autonomousAllowed: boolean
  expiresAt?: string | null
  createdBy?: string | null
  sourceEntryId?: string | null
  reason?: string | null
  metadata?: Record<string, unknown>
}

/** SSE 媒体事件 payload */
export interface SseMediaEvent {
  /** field */
  field: string
  /** mimeType */
  mimeType: string
  /** Base64 数据 */
  data: string
}

/** 音频转写事件 */
export interface SseTranscriptionEvent {
  sessionId: string
  text: string
  entryId?: string
}

export interface ChatResponse {
  entryId: string
  turnId?: string
  content: string
  a2uiComponents?: A2uiComponent[]
  tokenUsage?: TokenUsage
  traceId?: string
  /** 完成模式 */
  completionMode?: CompletionMode
  resumedFromTraceId?: string
  turnStatus?: ChatTurnStatus
  sources?: SourceSummary[]
}

/** 来源摘要：知识库 / 文档 / 工具 / 工作流 */
export interface SourceSummary {
  type: 'knowledgeBase' | 'document' | 'tool' | 'workflow'
  id: string
  name: string
  extra?: Record<string, unknown>
}

/** 工具调用摘要 */
export interface ToolCallSummary {
  toolId: string
  action?: string
  success: boolean
  latencyMs: number
  hasMoreSteps?: boolean
  /** 输入摘要 */
  inputSummary?: string
  /** 输出摘要 */
  outputSummary?: string
}

/** 错误响应 */
export interface ErrorResponse {
  code: number
  message: string
  timestamp: string
}

/** 会话配置：温度/最大 tokens/知识库绑定 */
export interface SessionConfig {
  preferredProviderId?: string
  temperature?: number
  maxTokens?: number
  maxSteps?: number
  maxDurationSeconds?: number
  knowledgeBaseIds?: string[]
}

// ========== 第一部分 19: Web UI 相关类型定义 ==========

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

/** 知识库统计 */
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

/** Skill 概要 */
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
  /** JSON Schema */
  inputSchema?: Record<string, any>
}

/** Trace 列表项 */
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

/** Trace 详情 */
export interface TraceDetail extends TraceItem {
  finalOutput?: string
  errorMessage?: string
  terminationReason?: string
  modelId?: string
}

/** Trace 步骤 */
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

/** 工作流输入参数 */
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

/** 工作流执行实例 */
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

/** 工作流事件类型 */
export type WorkflowEventType =
  | 'INSTANCE_CREATED'
  | 'INSTANCE_STATE_CHANGED'
  | 'STEP_STARTED'
  | 'STEP_COMPLETED'
  | 'STEP_FAILED'
  | 'STEP_SKIPPED'
  | 'APPROVAL_REQUESTED'
  | 'APPROVAL_DECIDED'

/** 工作流事件 */
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

/** 工作流步骤日志 */
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

// ========== 工作流统计相关类型定义 ==========

/** 工作流统计 */
export interface WorkflowStats {
  workflowId: string
  totalExecutions: number
  successCount: number
  failedCount: number
  avgDurationMs: number
  recentTrend: DailyTrend[]
}

/** 每日趋势 */
export interface DailyTrend {
  date: string
  count: number
  successCount: number
}

/** 步骤统计 */
export interface StepStats {
  stepId: string
  stepType: string
  executionCount: number
  successRate: number
  avgDurationMs: number
  maxDurationMs: number
  totalRetries: number
}

/** 步骤输出 */
export interface StepOutput {
  stepId: string
  output: unknown
  durationMs: number
  retryCount: number
  errorMessage?: string
  state: string
}

/** DAG 图数据 */
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

/** 预运行结果 */
export interface DryRunResult {
  steps: DryRunStepTrace[]
  dagOrder: string[]
  warnings: string[]
}

/** 预运行步骤跟踪 */
export interface DryRunStepTrace {
  stepId: string
  stepName: string
  stepType: string
  resolvedParams: Record<string, unknown>
  conditionResult?: boolean
  branch?: string
  loopIterations?: number
}

/** YAML 验证响应 */
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

/** Agent 类型 */
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
  /** 来源 */
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

/** Tool 概要 */
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

/** Analytics 统计 */
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
 * Token 消耗统计（Trace 级别）
 *
 * 用于 Trace 详情页展示 Token 使用情况
 */
export interface TokenConsumptionStats {
  /** Trace 数量 */
  traceCount: number
  /** 总 Token 数 */
  totalTokens: number
  /** 总输入 Token 数 */
  totalInputTokens: number
  /** 总输出 Token 数 */
  totalOutputTokens: number
  /** 平均每个 Trace 的 Token 数 */
  avgTokensPerTrace: number
  /** 最大 Token 数 */
  maxTokens: number
  /** 成功 Trace 数量 */
  successCount: number
  /** 平均耗时 */
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


/** Provider 能力类型 */
export type ProviderCapability = 'STREAMING' | 'FUNCTION_CALLING' | 'EMBEDDING' | 'VISION' | 'AUDIO' | 'RERANK'

// ========== 第二部分 20: Observability 可观测性相关 ==========

/**
 * 概览统计
 *
 * 用于 Trace 列表页顶部统计卡片展示
 */
export interface OverviewStats {
  /** 总 Trace 数 */
  totalTraces: number
  /** 成功 Trace 数 */
  successCount: number
  /** 失败 Trace 数 */
  failureCount: number
  /** 成功率 (0-1) */
  successRate: number
  /** 平均步骤数 */
  avgSteps: number
  /** 平均耗时 */
  avgDurationMs: number
  /** 总 Token 数 */
  totalTokens: number
  /** 平均每个 Trace 的 Token 数 */
  avgTokens: number
}

/**
 * 工具调用统计
 *
 * 用于工具使用频率排行等场景
 */
export interface ToolUsageStats {
  /** 工具 ID */
  toolId: string
  /** 调用次数 */
  callCount: number
  /** 成功次数 */
  successCount: number
  /** 失败次数 */
  failureCount: number
  /** 成功率 (0-1) */
  successRate: number
  /** 平均耗时 */
  avgDurationMs: number
}

/**
 * Trace 评估结果
 *
 * 用于 Agentic Evals 质量评估
 */
export interface EvaluationResult {
  /** Trace ID */
  traceId: string
  /** 评估时间 */
  evaluatedAt: string
  /** 工具选择得分 (0-1) */
  toolSelectionScore: number
  /** 参数有效性得分 (0-1) */
  parameterValidityScore: number
  /** 步骤效率得分 (0-1) */
  stepEfficiencyScore: number
  /** 策略合规得分 (0-1) */
  policyComplianceScore: number
  /** Token 效率得分 (-1 到 1) */
  tokenEfficiencyScore: number
  /** 综合得分 (-1 到 1) */
  overallScore: number
  /** 实际步骤数 */
  actualSteps: number
  /** 实际 Token 数 */
  actualTokens: number
  /** 违规项列表 */
  violations: string[]
  /** 改进建议 */
  suggestions: string[]
}

// ========== 知识库文档上传相关类型定义 ==========

/**
 * 上传文件项
 * 用于 uploadProgress 事件
 */
export interface UploadFileItem {
  /** 文件 ID */
  id: string
  /** File 对象 */
  file: File
  /** 文件名 */
  fileName: string
  /** 状态 */
  status: 'waiting' | 'uploading' | 'success' | 'error'
  /** 错误信息 */
  errorMessage?: string
}


// ========== 第三部分 25: 扩展市场相关 ==========

/** 扩展类型 */
export type ExtensionType = 'SKILL' | 'AGENT' | 'WORKFLOW'

/** 扩展包 */
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
  /** 已安装版本 */
  installedVersion?: string
  /** 是否已安装 */
  installed: boolean
}

/** Skill 包 */
export type SkillPackage = ExtensionPackage

/** 安全报告 */
export interface SecurityFinding {
  level: 'LOW' | 'MEDIUM' | 'HIGH'
  category: string
  description: string
}

/** 安全审计报告 */
export interface SecurityReport {
  findings: SecurityFinding[]
  overallRisk: 'LOW' | 'MEDIUM' | 'HIGH'
}

/** 安装结果 */
export interface InstallResult {
  success: boolean
  extensionId?: string
  extensionType?: ExtensionType
  securityReport?: SecurityReport
  requirements?: string[]
  errorMessage?: string
  requiresConfirmation: boolean
}

/** 分页结果 */
export interface PagedResult<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}


// ========== Web UI 辅助类型定义 ==========

/** 上下文预览响应 */
export interface ContextPreviewResponse {
  segments: {
    systemPrompt: { content: string; tokens: number }
    contextMessages: { content: string; tokens: number }
    historyMessages: { content: string; tokens: number }
    currentUserPrompt: { content: string; tokens: number }
  }
  tokenBudget: TokenBudgetData
  totalTokens: number
  totalBudget: number
  degraded: boolean
}

/** Token Budget 数据 */
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

/** 依赖节点 */
export interface DependencyNode {
  id: string
  name: string
  type: 'AGENT' | 'SKILL' | 'TOOL'
  enabled: boolean
}

/** 依赖边 */
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

/** Tool 调用统计 */
export interface ToolCallStats {
  toolId: string
  toolName: string
  callCount: number
  successCount: number
  failureCount: number
  avgLatencyMs: number
}

/** Tool 每日趋势 */
export interface ToolDailyTrend {
  date: string
  callCount: number
  successCount: number
  failureCount: number
}

/** Tool 分析响应 */
export interface ToolAnalyticsResponse {
  toolStats: ToolCallStats[]
  dailyTrend: ToolDailyTrend[]
}

/** 错误趋势每日统计 */
export interface ErrorTrendDaily {
  date: string
  agentErrors: number
  toolErrors: number
  totalErrors: number
}

/** MCP 连接日志 */
export interface McpConnectionLog {
  timestamp: string
  eventType: 'CONNECT' | 'DISCONNECT' | 'ERROR' | 'RECONNECT'
  description: string
}

/** Tool 测试历史项 */
export interface ToolTestHistoryItem {
  id: string
  timestamp: number
  input: Record<string, any>
  result: ToolTestResponse
}


// ========== MCP Server 连接状态 SSE 事件 ==========

/** MCP Server 状态快照 SSE mcp-status-snapshot 事件 */
export interface McpStatusSnapshot {
  servers: Array<{
    serverName: string
    state: McpServer['state']
    connectedSince?: string
    lastError?: string
  }>
}

/** MCP Server 状态变更 SSE mcp-status-change 事件 */
export interface McpStatusChange {
  serverName: string
  oldState: McpServer['state']
  newState: McpServer['state']
  timestamp: string
  error?: string
}

// ========== 通知系统相关类型定义 ==========

/** 通知紧急程度 */
export type NotificationUrgency = 'HIGH' | 'MEDIUM' | 'LOW'

/** 通知阅读状态 */
export type NotificationReadStatus = 'UNREAD' | 'READ'

/** 通知项 */
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

/** 解析后的详情 */
export interface ParsedDetail {
  type: 'TEXT' | 'MARKDOWN' | 'CARD' | 'UNKNOWN'
  /** TEXT: 纯文本; MARKDOWN: markdown; CARD: 卡片 */
  text?: string
  /** MARKDOWN: 渲染后的 HTML */
  html?: string
  /** CARD: 标题 */
  title?: string
  /** CARD: 正文 */
  body?: string
  /** CARD: 操作按钮 */
  actions?: Array<{ label: string; url?: string }>
}



// ========== 记忆系统相关类型定义 ==========

/** 记忆统计 */
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

/** 记忆搜索结果 */
export interface MemorySearchResult {
  entityId: string
  entityType: string
  name: string
  description: string | null
  relevanceScore: number
}

/** 实体摘要 */
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

/** 实体详情 */
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

/** 实体列表参数 */
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

/** 关系项 */
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

/** 关系列表参数 */
export interface RelationListParams {
  page?: number
  size?: number
  entityId?: string
  relationType?: string
}

/** 对话摘要 */
export interface ConversationSummary {
  id: string
  sessionId: string
  goal: string
  summary: string | null
  messageCount: number
  createdAt: string
}

/** 对话详情 */
export interface ConversationDetail {
  id: string
  sessionId: string
  goal: string
  summary: string | null
  messages: MessageRecord[]
  createdAt: string
  updatedAt: string
}

/** 消息记录 */
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

/** 对话列表参数 */
export interface ConversationListParams {
  page?: number
  size?: number
  q?: string
  timeFrom?: string
  timeTo?: string
}

/** 程序模板 */
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

/** 偏好规则 */
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

/** 模板列表参数 */
export interface TemplateListParams {
  page?: number
  size?: number
  q?: string
  sortBy?: string
  order?: string
}

/** 遗忘日志 */
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

/** 遗忘日志列表参数 */
export interface ForgettingLogListParams {
  page?: number
  size?: number
  timeFrom?: string
  timeTo?: string
  strategy?: string
}

/** 实体类型常量 */
export const ENTITY_TYPES = [
  { value: 'PERSON', label: '人物' },
  { value: 'ORGANIZATION', label: '组织' },
  { value: 'PLACE', label: '地点' },
  { value: 'EVENT', label: '事件' },
  { value: 'PROJECT', label: '项目' },
  { value: 'TOPIC', label: '主题' },
  { value: 'PREFERENCE', label: '偏好' },
  { value: 'HABIT', label: '习惯' },
  { value: 'GOAL', label: '目标' },
  { value: 'SKILL', label: '技能' },
  { value: 'CUSTOM', label: '自定义' },
] as const

// ========== Eval 评估相关 ==========

/** Benchmark 场景 */
export interface BenchmarkScenario {
  id: string
  name: string
  userInput: string
  expectedToolCalls: string[]
  expectedOutputPattern?: string | null
  dimensionWeights: Record<string, number>
  timeoutSeconds: number
  mockToolResponses?: Record<string, string> | null
  mockTools?: MockToolSpec[] | null
  initialContext?: Record<string, string> | null
  tags: string[]
  llmJudgeCriteria?: string | null
  expectedTokenBudget: number
  expectedStepCount: number
  category?: string | null
  difficulty?: string | null
  description?: string | null
}

/** Mock 工具规格 */
export interface MockToolSpec {
  toolId: string
  behaviors: MockBehavior[]
  defaultResponse: string
}

/** Mock 行为 */
export interface MockBehavior {
  parameterPattern?: string | null
  response: string
  simulateError: boolean
  delayMs: number
}

/** 评估结果项 */
export interface EvalResultItem {
  evalId: string
  traceId: string
  scenarioId: string
  dimensionScores: Record<string, number>
  overallScore: number
  violations: string[]
  suggestions: string[]
  llmJudgeScore?: number | null
  llmJudgeJustification?: string | null
  llmJudgeTokensUsed: number
  evaluatedAt: string
  gitCommitHash?: string | null
  gitBranch?: string | null
  evalRunId: string
  diagnosticJson?: string | null
  runMetadataJson?: string | null
}

/** 诊断报告 */
export interface DiagnosticReport {
  dimensionDiagnostics: DimensionDiagnostic[]
  actionableSuggestions: string[]
  overallAssessment: string
}

/** 维度诊断 */
export interface DimensionDiagnostic {
  dimension: string
  label: string
  score: number
  diagnosis: string
  fixes: string[]
}

/** 运行元数据 */
export interface RunMetadata {
  modelId?: string | null
  promptVersion?: string | null
  configSnapshot?: string | null
  baselineRunId?: string | null
  labels: Record<string, string>
}

/** 评估报告汇总 */
export interface EvalReportSummary {
  evalRunId: string
  totalScenarios: number
  passCount: number
  failCount: number
  averageOverallScore: number
  dimensionAverages: Record<string, number>
  degraded: boolean
  regressedScenarios: string[]
  newRegressions: string[]
  evaluatedAt: string
  metadata?: RunMetadata | null
}

/** A/B 对比报告 */
export interface ComparisonReport {
  currentRunId: string
  baselineRunId: string
  currentAvg: number
  baselineAvg: number
  delta: number
  scenarios: ScenarioComparison[]
  currentMetadata?: RunMetadata | null
  baselineMetadata?: RunMetadata | null
}

/** 场景对比 */
export interface ScenarioComparison {
  scenarioId: string
  currentScore: number
  baselineScore: number
  delta: number
  status: 'improved' | 'degraded' | 'unchanged' | 'new'
}

/** 评估反馈 */
export interface EvalFeedback {
  feedbackId: string
  evalId: string
  scenarioId: string
  feedbackType: 'AGREE' | 'DISAGREE' | 'GOLDEN_ANSWER'
  comment?: string | null
  goldenAnswer?: string | null
  createdBy?: string | null
  createdAt: string
}

/** 评估运行请求 */
export interface EvalRunRequest {
  scenarioIds?: string[] | null
  tag?: string | null
  baselineRunId?: string | null
  labels?: Record<string, string> | null
  smokeTestOnly?: boolean | null
  offlineReeval?: boolean | null
}
