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
  /**
   * 归属项目 ID。
   * - `null` / `undefined`：主账户对话（侧栏"今天/昨天"分组显示）
   * - 非空字符串：归属该项目，仅在项目展开项下嵌套显示
   */
  projectId?: string | null
}

export interface ChatSessionDetail extends ChatSession {
  knowledgeBaseIds: string[]
  preferredProviderId?: string
  temperature?: number
  maxSteps?: number
  maxDurationSeconds?: number
  messageCount: number
  totalTokens: number
  compactionStatus?: SessionCompactionStatus
}

export interface SessionCompactionStatus {
  enabled: boolean
  activeTranscriptTokens: number
  triggerThresholdTokens: number
  triggerThresholdPercent: number
  remainingTokens: number
  activeTurnCount: number
  minTurnCount: number
  keepRecentTurns: number
  thresholdReached: boolean
  minTurnsReached: boolean
  readyToCompact: boolean
  compactionCount: number
  lastCompactedAt?: string | null
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

export type ChatTurnStatus = 'PENDING' | 'SUCCESS' | 'FAILED' | 'DEGRADED' | 'SUSPENDED' | 'CANCELLED'
export type MemoryChangeStatus = 'checking' | 'settled' | 'checked-empty' | 'failed' | 'disabled'

export interface KnowledgeSettlement {
  knowledgeBaseId: string
  knowledgeBaseName: string
  sourceType?: 'MESSAGE_TEXT' | 'ARTIFACT' | string
  artifactId?: string
  fileName?: string
  savedAt?: string
}

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
  /**
   * 推理过程完整文本（流式 reasoning token 累计结果）。
   *
   * <p>来源：流式期间由 useChat 收集的 reasoningBuffer，DONE 事件触发时落入消息。
   * 历史消息可能因后端尚未在 message 接口暴露而为空，对应 UI 自动隐藏。
   */
  reasoningContent?: string
  /** 推理过程持续时间（毫秒），用于显示「已思考 X 秒」 */
  reasoningDurationMs?: number
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
  /** 文件产物引用列表 — 工具调用产生的 ToolArtifact 对应的 SessionArtifact 引用 */
  artifactRefs?: import('@/api/artifacts').ArtifactRefPayload[]
  /** Token 使用情况（从 DONE 事件中获取） */
  tokenUsage?: TokenUsage
  modelId?: string
  /** 首选 Provider ID */
  preferredProviderId?: string
  /** 本轮临时上下文/模型覆盖，用于解释用户发送时选择的上下文策略 */
  singleTurnOverride?: SessionConfigOverride | null
  /** 来源列表：知识库 / 工具调用结果 */
  sources?: SourceSummary[]
  /** 本轮对话沉淀的记忆 */
  memoryChanges?: SourceSummary[]
  /** 已沉淀到资料库的消息/产物记录 */
  knowledgeSettlements?: KnowledgeSettlement[]
  /** 本轮记忆沉淀后台状态，仅用于当前对话页的轻量反馈 */
  memoryChangeStatus?: MemoryChangeStatus
  /** 本轮记忆沉淀状态原因，用于解释失败或跳过 */
  memoryChangeReason?: string
  /** 工具调用摘要 */
  toolsSummary?: ToolCallSummary[]
  /** 任务恢复摘要 */
  taskRecovery?: TaskRecoverySummary
  /** 当前轮次恢复上下文 */
  turnRecoveryContext?: TurnRecoveryContext
  /** 本轮执行约束摘要，例如禁用联网、指定记忆模式等 */
  executionConstraints?: ExecutionConstraintSummary
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
  /** 历史权限审批记录 */
  permissionApprovalLogs?: PermissionApprovalLog[]
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
  defaultWorkspace?: string | null
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

/**
 * SSE reasoning token 增量事件（推理模型流式 reasoning_content）。
 *
 * <p>注意：与 ReactAgentLoop 推送的 ReAct 步骤 reasoning 事件共用同一 SSE event 名 "reasoning"，
 * 通过 payload 字段区分：含 `delta` → 推理 token 增量；含 `event` → ReAct 步骤元数据。
 */
export interface SseReasoningTokenEvent {
  sessionId: string
  turnId?: string
  /** 推理文本增量（pushReasoningToSse 推送，对应后端 ReasoningChunk.delta） */
  delta: string
}

/**
 * 单轮临时覆盖的会话配置。
 *
 * <p>对应后端 {@code com.lifepilot.interaction.web.model.SessionConfigOverride}。
 * 仅影响当前 turn 的 AgentRequest 构造，不写入持久化配置，
 * 取代旧版"发送前 PATCH 再恢复"双 PATCH race 实现。</p>
 */
export interface SessionConfigOverride {
  preferredProviderId?: string | null
  temperature?: number | null
  maxSteps?: number | null
  maxDurationSeconds?: number | null
  knowledgeBaseIds?: string[] | null
  memoryContextMode?: 'auto' | 'focused' | 'off' | null
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
  callId?: string
  inputSummary: string
  /** 工具输入详情，供断点恢复使用 */
  inputDetail?: string
  latencyMs: number
  /** 被执行主体标签，例如「技能」 */
  subjectLabel?: string
  /** 被执行主体名称列表，例如加载的 Skill 名称 */
  subjectNames?: string[]
}

/** 工具观察步骤 */
export interface ObservationStep extends ReactStepBase {
  type: 'OBSERVATION'
  toolId: string
  /** 工具名称（可选） */
  toolName?: string
  callId?: string
  success: boolean
  outputSummary: string
  tokensUsed: number
  /** 被执行主体标签，例如「技能」 */
  subjectLabel?: string
  /** 被执行主体名称列表，例如加载的 Skill 名称 */
  subjectNames?: string[]
  /** 文件工具成功时提取的生成文件绝对路径 */
  generatedFilePath?: string
  /** Shell / 代码执行的工作目录 */
  workingDirectory?: string
  /** 工具执行的具体结果（详情面板展示） */
  outputDetail?: string
  /** 结构化原始输出 —— 目前仅 browser 工具透传，供特化卡片直接渲染 url/截图/elements 等 */
  output?: unknown
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
  /** DONE 随带的已持久化附件 */
  attachments?: ChatAttachment[]
  /** sources（知识库 / 工具） */
  sources?: SourceSummary[]
  /** 本轮对话沉淀的记忆 */
  memoryChanges?: SourceSummary[]
  /** 本轮工具产出的文件/图片产物引用 */
  artifactRefs?: import('@/api/artifacts').ArtifactRefPayload[]
  /** toolsSummary */
  toolsSummary?: ToolCallSummary[]
  /** taskRecovery */
  taskRecovery?: TaskRecoverySummary
  /** 当前轮次恢复上下文 */
  turnRecoveryContext?: TurnRecoveryContext
  /** 本轮执行约束摘要 */
  executionConstraints?: ExecutionConstraintSummary
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
  taskRecovery?: TaskRecoverySummary
  executionConstraints?: ExecutionConstraintSummary
  /** 挂起超时秒数（BrowserTakeover 等场景由后端下发，前端为空时采用本地默认值） */
  timeoutSeconds?: number
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

/** 权限审批记录 */
export interface PermissionApprovalLog {
  requestId: string
  toolId: string
  toolName: string
  actionType: string
  resolution: 'approved' | 'rejected' | 'expired'
  subjectType?: string | null
  reason?: string | null
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
  toolsSummary?: ToolCallSummary[]
  taskRecovery?: TaskRecoverySummary
  executionConstraints?: ExecutionConstraintSummary
}

export interface ExecutionConstraintSummary {
  disabledTools?: ExecutionConstraintTool[]
  knowledgeBaseIds?: string[]
  memoryContextMode?: ExecutionConstraintMemoryMode
}

export interface ExecutionConstraintTool {
  id: string
  label: string
  reason?: string
}

export interface ExecutionConstraintMemoryMode {
  mode: 'focused' | 'off' | string
  label: string
  reason?: string
}

/** 来源摘要：知识库 / 文档 / 工具 / 工作流 */
export interface SourceSummary {
  type: 'knowledgeBase' | 'memory' | 'document' | 'tool' | 'workflow'
  id: string
  name: string
  extra?: Record<string, unknown>
}

export type MemoryTurnChangeBackendStatus = 'PENDING' | 'SETTLED' | 'CHECKED_EMPTY' | 'FAILED' | 'DISABLED'

export interface MemoryTurnChangesInfo {
  status: MemoryTurnChangeBackendStatus
  reason?: string | null
  changes: SourceSummary[]
}

/** 任务恢复摘要 */
export interface TaskRecoverySummary {
  status: 'SUSPENDED' | 'DEGRADED'
  title: string
  detail: string
  actionLabel?: string
  canResume: boolean
  canRestart: boolean
  reasonType?: string
  reasonSourceId?: string
  resumeMode?: 'manual' | 'user_reply' | 'external' | 'scheduled' | 'browser'
  /** 任务中断时的可恢复断点 */
  checkpoint?: TaskRecoveryCheckpoint
  /** 面向用户的下一步恢复计划 */
  nextActions?: string[]
}

export interface MissingCapability {
  kind?: 'TOOL' | 'SKILL' | string
  id: string
  source?: string
  reason?: string
  skillName?: string
}

export interface TaskRecoveryCheckpoint {
  kind?: 'TOOL_FAILURE' | 'SUSPEND'
  recoveryActionId?: string
  recoveryActionLabel?: string
  recoveryActionMode?: 'resume' | 'restart' | string
  recoveryActionDescription?: string
  callId?: string
  toolId?: string
  toolName?: string
  executionKind?: ToolExecutionKind
  action?: string
  failureCategory?: ToolFailureCategory
  interrupted?: boolean
  subjectLabel?: string
  subjectNames?: string[]
  inputSummary?: string
  inputDetail?: string
  outputSummary?: string
  outputDetail?: string
  workingDirectory?: string
  generatedFilePath?: string
  artifactRefs?: import('@/api/artifacts').ArtifactRefPayload[]
  missingCapabilities?: MissingCapability[]
  inputStepIndex?: number
  outputStepIndex?: number
}

export interface TurnRecoveryContext {
  action?: 'RESUME' | 'RESTART' | string
  resumeInput?: string
  sourceTraceId?: string
  assistantEntryId?: string
  title?: string
  detail?: string
  resumeStrategy?: string
  checkpoint?: TaskRecoveryCheckpoint
  nextActions?: string[]
  capturedAt?: string
}

export type ToolExecutionKind = 'TOOL' | 'SKILL'
export type ToolExecutionStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED'
export type ToolFailureCategory =
  | 'CAPABILITY'
  | 'COMMAND'
  | 'FILE'
  | 'BROWSER'
  | 'NETWORK'
  | 'MEMORY'
  | 'SKILL'
  | 'KNOWLEDGE'
  | 'WORKFLOW'
  | 'INTEGRATION'
  | 'AGENT'
  | 'MODEL'
  | 'REPOSITORY'
  | 'UNKNOWN'

export interface ToolRecoveryAction {
  id: string
  label: string
  description?: string
  mode?: 'resume' | 'restart' | 'retry' | 'manual'
  category?: ToolFailureCategory
  callId?: string
  toolId?: string
  toolName?: string
  executionKind?: ToolExecutionKind
  action?: string
  interrupted?: boolean
  subjectLabel?: string
  subjectNames?: string[]
  inputSummary?: string
  inputDetail?: string
  outputSummary?: string
  outputDetail?: string
  workingDirectory?: string
  generatedFilePath?: string
  artifactRefs?: import('@/api/artifacts').ArtifactRefPayload[]
  missingCapabilities?: MissingCapability[]
  recoveryHint?: string
  /** 点击恢复时带回后端的下一步计划 */
  nextActions?: string[]
}

/** 工具调用摘要 */
export interface ToolCallSummary {
  toolId: string
  /** 模型返回的工具调用 ID，用于恢复时精确定位同一轮内的具体调用 */
  callId?: string
  /** 工具显示名 */
  toolName?: string
  /** 执行主体类型：普通工具或 Skill */
  executionKind?: ToolExecutionKind
  /** 执行状态，优先于旧的 success / hasMoreSteps 推断 */
  status?: ToolExecutionStatus
  action?: string
  success: boolean
  latencyMs: number
  hasMoreSteps?: boolean
  /** 已开始但最终没有收到观察结果，通常表示本轮在工具/技能步骤中断 */
  interrupted?: boolean
  /** 失败类别，帮助前端给出一致的恢复语义 */
  failureCategory?: ToolFailureCategory
  recoveryActions?: ToolRecoveryAction[]
  subjectLabel?: string
  subjectNames?: string[]
  /** 输入摘要 */
  inputSummary?: string
  /** 输入详情，失败恢复时用于保留具体参数 */
  inputDetail?: string
  /** 输出摘要 */
  outputSummary?: string
  /** 失败或调试场景的详细输出 */
  outputDetail?: string
  /** Shell / 代码执行时的工作目录 */
  workingDirectory?: string
  /** 文件工具生成的文件路径 */
  generatedFilePath?: string
  artifactRefs?: import('@/api/artifacts').ArtifactRefPayload[]
  missingCapabilities?: MissingCapability[]
  /** 失败后的恢复提示 */
  recoveryHint?: string
  /** 工具原始输出（可能是 JSON 字符串或已反序列化对象），用于特化卡片渲染 */
  output?: unknown
}

/** 错误响应 */
export interface ErrorResponse {
  code: number
  message: string
  timestamp: string
}

/** 本地诊断检查项 */
export interface DiagnosticCheck {
  id: string
  label: string
  status: 'OK' | 'WARN' | 'ERROR' | string
  detail: string
  metadata: Record<string, unknown>
}

/** 本地诊断报告 */
export interface DiagnosticReport {
  generatedAt: string
  status: 'OK' | 'WARN' | 'ERROR' | string
  summary: string
  app: Record<string, unknown>
  runtime: Record<string, unknown>
  counts: Record<string, unknown>
  checks: DiagnosticCheck[]
  hints: string[]
}

/** 本地诊断包导出结果 */
export interface DiagnosticBundleInfo {
  createdAt: string
  fileName: string
  path: string
  sizeBytes: number
  includedFileCount: number
}

/** 本地数据备份结果 */
export interface LocalBackupInfo {
  createdAt: string
  fileName: string
  path: string
  sizeBytes: number
  includedFileCount: number
}

/** 本地备份文件 */
export interface LocalBackupFileInfo {
  modifiedAt: string
  fileName: string
  path: string
  sizeBytes: number
}

/** 本地备份包清单 */
export interface LocalBackupManifestInfo {
  formatVersion: string
  createdAt: string
  sourceHome: string
  includedFileCount: number
  excludedTopLevelDirs: string[]
}

/** 本地备份恢复前预检 */
export interface LocalBackupRestorePlanInfo {
  restoreMode: string
  manualRestoreOnly: boolean
  includedTopLevelItems: string[]
  excludedTopLevelDirs: string[]
  targetHome: string
  currentHomeHasData: boolean
  currentHomeFileCount: number
  backupSourceHome: string
  backupIncludedFileCount: number
  restoreStagingDirectory: string
  backupSizeBytes: number
  estimatedRestoreBytes: number
  targetUsableBytes: number
  restoreSpaceStatus: 'OK' | 'WARN' | 'UNKNOWN' | string
  warnings: string[]
  requiredSteps: string[]
}

/** 本地备份恢复准备结果 */
export interface LocalBackupRestorePreparationInfo {
  preparedAt: string
  fileName: string
  restoreDirectory: string
  extractedFileCount: number
  extractedBytes: number
  warnings: string[]
  nextSteps: string[]
}

/** 本地备份校验结果 */
export interface LocalBackupValidationInfo {
  fileName: string
  path: string
  status: 'OK' | 'WARN' | 'ERROR' | string
  detail: string
  sizeBytes: number
  entryCount: number
  manifestPresent: boolean
  manifest?: LocalBackupManifestInfo | null
  problems: string[]
  restorePlan?: LocalBackupRestorePlanInfo | null
}

/** 会话配置：温度/最大 tokens/知识库绑定 */
export interface SessionConfig {
  preferredProviderId?: string
  temperature?: number
  maxSteps?: number
  maxDurationSeconds?: number
  knowledgeBaseIds?: string[]
}

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
  rerankerModel?: string | null
  chunkingStrategy?: string
  chunkingConfig?: Record<string, unknown>
  tags?: string[]
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

/** Skill 安装来源类型（与后端 DB 层枚举 SkillSourceType 一一对应） */
export type SkillSourceType = 'BUILTIN' | 'USER_IMPORTED' | 'MARKETPLACE' | 'AUTO_GENERATED'

/** Skill 概要 */
export interface SkillSummary {
  id: string
  name: string
  description: string
  version: string
  /** DB 层四值来源枚举，驱动前端来源徽章 */
  sourceType: SkillSourceType
  /** 启用开关事实源（来自 skills 表 enabled 列） */
  enabled: boolean
  /** 内存 SkillSource 三值（UserDefined/AutoGenerated/Marketplace），兼容老字段 */
  source?: { type: 'UserDefined' | 'AutoGenerated' | 'Marketplace'; [key: string]: unknown }
  metadata?: Record<string, string>
}

/** Skill 详情 */
export interface SkillDetail extends SkillSummary {
  instructions: string
  suggestedTools: string[]
  metadata: Record<string, string>
}

/** Skill 安装元数据（后端 SkillInstallation 记录） */
export interface SkillInstallation {
  name: string
  sourceType: SkillSourceType
  sourceUri?: string | null
  filePath: string
  version: string
  enabled: boolean
  marketplaceId?: string | null
  checksum?: string | null
  installedAt: string
  updatedAt: string
  lastActivatedAt?: string | null
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
  top_k?: number
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


// ========== 第三部分 25: 渠道控制面相关 ==========

export type ChannelConnectorMode = 'LOCAL' | 'EXTERNAL'

export type ChannelInstanceStatus = 'CREATED' | 'STARTING' | 'RUNNING' | 'STOPPING' | 'STOPPED' | 'ERROR'

export interface ChannelConfigSchemaProperty {
  type?: string
  title?: string
  description?: string
  enum?: Array<string | number>
  default?: unknown
  secret?: boolean
}

export interface ChannelConfigSchema {
  type?: string
  properties?: Record<string, ChannelConfigSchemaProperty>
  required?: string[]
}

export interface ChannelSetupGuide {
  title?: string
  steps?: string[]
  [key: string]: unknown
}

export interface ChannelPluginResources {
  readmePath?: string | null
  iconPath?: string | null
  examplePaths: string[]
  assetPaths: string[]
}

export interface ChannelPluginDescriptor {
  pluginId: string
  name: string
  version: string
  vendor: string
  platform: string
  connectorMode: ChannelConnectorMode
  connectorSpec?: Record<string, unknown> | null
  capabilities: string[]
  configSchema: ChannelConfigSchema
  secretFields: string[]
  setupGuide?: ChannelSetupGuide | null
  resources?: ChannelPluginResources | null
}

export interface ChannelInstance {
  instanceId: string
  pluginId: string
  platform: string
  displayName: string
  enabled: boolean
  status: ChannelInstanceStatus
  config: Record<string, unknown>
  secretConfig?: Record<string, unknown> | null
  routingPolicy?: Record<string, unknown> | null
  lastHeartbeatAt?: string | null
  lastError?: string | null
  createdAt: string
  updatedAt: string
}

export interface ChannelInstanceEvent {
  id: string
  instanceId: string
  eventType: string
  message?: string | null
  payload?: Record<string, unknown> | null
  createdAt: string
}

export interface ChannelHealthStatus {
  instanceId: string
  pluginId: string
  platform: string
  enabled: boolean
  status: ChannelInstanceStatus
  connectorMode: ChannelConnectorMode
  healthy: boolean
  lastHeartbeatAt?: string | null
  lastError?: string | null
  details?: Record<string, unknown>
}

export interface CreateChannelInstanceRequest {
  pluginId: string
  instanceId?: string
  displayName?: string
  config?: Record<string, unknown>
  secretConfig?: Record<string, unknown> | null
  routingPolicy?: Record<string, unknown> | null
  enabled?: boolean
}

export interface UpdateChannelInstanceRequest {
  displayName?: string
  enabled?: boolean
  config?: Record<string, unknown>
  secretConfig?: Record<string, unknown> | null
  routingPolicy?: Record<string, unknown> | null
}

// ========== 第三部分 26: 扩展市场相关 ==========

/** 扩展类型 */
export type ExtensionType = 'SKILL' | 'AGENT' | 'WORKFLOW' | 'CHANNEL'

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

export interface InstalledExtensionAsset {
  kind: string
  relativePath: string
  localPath: string
}

export interface ExtensionInstallation {
  packageId: string
  type: ExtensionType
  name: string
  version: string
  entryPath: string
  installRootPath: string
  assets?: InstalledExtensionAsset[] | null
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

/** 通知阅读状态 */
export type NotificationReadStatus = 'UNREAD' | 'READ'

/** 通知项 */
export interface NotificationItem {
  id: string
  userId: string
  typeId?: string
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
  spaceId: string | null
  memoryScope: string | null
  realityType: string | null
  lifecycleState: string
  historical: boolean
  stale: boolean
  needsRevalidation: boolean
  evidenceKind: string
  trustLevel: string
  trustScore: number
  evidenceCount: number
  lastVerifiedAt: string | null
  scoreBreakdown: Record<string, number>
}

/** 记忆搜索治理响应 */
export interface MemorySearchResponse {
  results: MemorySearchResult[]
  count: number
  rawCount: number
  qualityFilteredCount: number
  truncatedCount: number
  filteredOutCount: number
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
  spaceId: string | null
  memoryScope: string | null
  realityType: string | null
  lifecycleState: string
  temporality: string
  expiresAt: string | null
  evidenceKind: string
  trustLevel: string
  trustScore: number
  evidenceCount: number
  lastVerifiedAt: string | null
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
  spaceId: string | null
  memoryScope: string | null
  realityType: string | null
  properties: Record<string, unknown>
  version: number
  isCurrent: boolean
  validFrom: string
  validTo: string | null
  sourceConversationId: string | null
  lifecycleState: string
  lifecycleReason: string | null
  expiresAt: string | null
  temporality: string
  succeededBy: string | null
  isDerived: boolean
  derivationSources: string[]
  evidenceKind: string
  trustLevel: string
  trustScore: number
  evidenceCount: number
  lastVerifiedAt: string | null
  extractionConfidence: number
  importanceScore: number
  accessCount: number
  lastAccessedAt: string | null
  createdAt: string
  updatedAt: string
}

/** 实体来源明细 */
export interface EntityProvenance {
  originType: string
  sourceReference: string | null
  sourceConversationId: string | null
  sourceSessionId: string | null
  sourceSessionTitle?: string | null
  sourceTurnId: string | null
  sourceEntryId: string | null
  sourceDocumentId: string | null
  sourceDocumentName: string | null
  sourceKnowledgeBaseId: string | null
  sourceKnowledgeBaseName: string | null
  evidenceKind: string | null
  trustLevel: string | null
  trustScore: number
  evidenceExcerpt: string | null
  confidence: number
  status: 'VALID' | 'STALE' | string
  invalidatedAt: string | null
  revalidationStatus: 'PENDING' | 'PROMPTED' | 'RESOLVED' | string | null
  createdAt: string
}

/** 记忆来源摘要 */
export interface MemoryProvenanceSummary {
  entityId: string
  entityName: string
  entityType: string
  entityTypeLabel: string
  entityMemoryScope: string | null
  entityRealityType: string | null
  originType: string
  sourceReference: string | null
  sourceConversationId: string | null
  sourceSessionId: string | null
  sourceSessionTitle?: string | null
  sourceTurnId: string | null
  sourceEntryId: string | null
  sourceDocumentId: string | null
  sourceDocumentName: string | null
  sourceKnowledgeBaseId: string | null
  sourceKnowledgeBaseName: string | null
  evidenceKind: string | null
  trustLevel: string | null
  trustScore: number
  evidenceExcerpt: string | null
  confidence: number
  status: 'VALID' | 'STALE' | string
  invalidatedAt: string | null
  revalidationStatus: 'PENDING' | 'PROMPTED' | 'RESOLVED' | string | null
  createdAt: string
}

/** 实体来源筛选参数 */
export interface EntityProvenanceParams {
  projectId?: string | null
  originType?: string
  sourceKnowledgeBaseId?: string
  sourceDocumentId?: string
}

/** 最近来源筛选参数 */
export interface MemoryProvenanceListParams extends EntityProvenanceParams {
  limit?: number
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
  name?: string
  description?: string
  properties?: Record<string, unknown>
  importanceScore?: number
}

/** 实体列表参数 */
export interface EntityListParams {
  page?: number
  size?: number
  projectId?: string | null
  type?: string
  q?: string
  spaceId?: string
  memoryScope?: string
  realityType?: string
  originType?: string
  sourceKnowledgeBaseId?: string
  sourceDocumentId?: string
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
  sourceEntitySpaceId: string | null
  sourceEntityMemoryScope: string | null
  sourceEntityRealityType: string | null
  targetEntityId: string
  targetEntityName: string
  targetEntityType: string
  targetEntitySpaceId: string | null
  targetEntityMemoryScope: string | null
  targetEntityRealityType: string | null
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
