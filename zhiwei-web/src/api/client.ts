import type {
  ChatAttachment,
  ChatResponse,
  ResumePolicy,
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
  DependencyGraphResponse,
  ToolCallStats,
  ToolDailyTrend,
  ToolAnalyticsResponse,
  ErrorTrendDaily,
  McpConnectionLog,
  // 宸ヤ綔娴佹垚鐔熷寲鏂板绫诲瀷
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
  // 閫氱煡涓績绫诲瀷
  NotificationItem,
  // 璁板繂绠＄悊绫诲瀷
  MemoryStats,
  MemorySearchResult,
  EntitySummary,
  EntityDetail,
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
  ForgettingLogListParams
} from '@/types'
import { mapBackendMessage } from '@/utils/a2ui'

// API 鍩虹璺緞锛堝紑鍙戠幆澧冮€氳繃 Vite proxy 杞彂锛?
const BASE = '/api'

/** 缃戠粶閿欒绫?*/
export class NetworkError extends Error {
  constructor(message = '网络连接失败') {
    super(message)
    this.name = 'NetworkError'
  }
}

/** 瓒呮椂閿欒绫?*/
export class TimeoutError extends Error {
  constructor(message = '请求超时') {
    super(message)
    this.name = 'TimeoutError'
  }
}

/** 缁熶竴 HTTP 璇锋眰灏佽锛岄潪 2xx 鎶涘嚭鍖呭惈 ErrorResponse 鐨勫紓甯?*/
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
    // 204 No Content 鏃犲搷搴斾綋
    if (res.status === 204) return undefined as T
    // 妫€鏌ュ搷搴斾綋鏄惁涓虹┖
    const contentType = res.headers.get('content-type')
    if (!contentType || !contentType.includes('application/json')) {
      // 濡傛灉涓嶆槸 JSON锛屽皾璇曡鍙栨枃鏈?
      const text = await res.text()
      if (!text || text.trim() === '') {
        return undefined as T
      }
      // 灏濊瘯瑙ｆ瀽涓?JSON
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

/** 瀵硅瘽鐩稿叧 API */
export const chatApi = {
  /** 闈炴祦寮忓彂閫佹秷鎭?*/
  sendMessage(
    content: string,
    sessionId?: string,
    attachmentIds?: string[],
    resumePolicy?: ResumePolicy
  ): Promise<ChatResponse> {
    return request('/chat/messages', {
      method: 'POST',
      body: JSON.stringify({ content, sessionId, attachmentIds, resumePolicy })
    })
  },

  /**
   * 娴佸紡鍙戦€佹秷鎭紝杩斿洖 ReadableStream 鐢ㄤ簬 SSE 瑙ｆ瀽銆?
   * 璋冪敤鏂归€氳繃 ReadableStream 閫愯璇诲彇 SSE 浜嬩欢銆?
   */
  async sendMessageStream(
    content: string,
    sessionId?: string,
    attachmentIds?: string[],
    resumePolicy?: ResumePolicy,
    signal?: AbortSignal
  ): Promise<ReadableStream<Uint8Array>> {
    const res = await fetch(`${BASE}/chat/messages/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content, sessionId, attachmentIds, resumePolicy }),
      signal
    })
    if (!res.ok || !res.body) {
      throw { code: res.status, message: '流式请求失败', timestamp: new Date().toISOString() }
    }
    return res.body
  },

  /** 鍒涘缓浼氳瘽 */
  createSession(title?: string): Promise<ChatSession> {
    return request('/chat/sessions', {
      method: 'POST',
      body: JSON.stringify({ title })
    })
  },

  /** 鑾峰彇浼氳瘽鍒楄〃 */
  listSessions(): Promise<ChatSession[]> {
    return request('/chat/sessions')
  },

  /** 鑾峰彇浼氳瘽鍘嗗彶娑堟伅 */
  async getSessionMessages(sessionId: string): Promise<Message[]> {
    const messages = await request<Array<{
      id: string
      role: 'user' | 'assistant' | 'tool-confirmation'
      content: string
      a2uiComponents?: unknown
      timestamp: string | number
      reasoningSummary?: string | null
      traceId?: string | null
      attachments?: Array<{ id: string; fileName: string; fileSize: number; mimeType: string; url?: string | null }> | null
      reactSteps?: ReactStepDto[] | null
      completionMode?: 'NORMAL' | 'DEGRADED' | 'SUSPENDED' | null
      resumedFromTraceId?: string | null
    }>>(`/chat/sessions/${sessionId}/messages`)
    return messages.map(mapBackendMessage)
  },

  /** 鑾峰彇浼氳瘽璇︽儏 */
  getSession(sessionId: string): Promise<ChatSessionDetail> {
    return request(`/chat/sessions/${sessionId}`)
  },

  /** 鏇存柊浼氳瘽锛堟爣棰樸€佺疆椤躲€佸綊妗ｇ瓑锛?*/
  updateSession(sessionId: string, updates: { title?: string; pinned?: boolean; archived?: boolean }): Promise<ChatSession> {
    return request(`/chat/sessions/${sessionId}`, {
      method: 'PATCH',
      body: JSON.stringify(updates)
    })
  },

  /**
   * 鏇存柊浼氳瘽閰嶇疆锛堟ā鍨?娓╁害/涓夌淮棰勭畻/鍏宠仈鐭ヨ瘑搴擄級銆?
   *
   * 娉ㄦ剰锛氱煡璇嗗簱鍏宠仈浼氬奖鍝嶅悗绔湪鐢熸垚鍥炵瓟鍓嶇殑妫€绱笂涓嬫枃娉ㄥ叆锛堣嫢宸插惎鐢級銆?
   */
  updateSessionConfig(
    sessionId: string,
    config: {
      preferredProviderId?: string
      temperature?: number
      maxTokens?: number
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

  /** 鍒犻櫎浼氳瘽 */
  deleteSession(sessionId: string): Promise<void> {
    return request(`/chat/sessions/${sessionId}`, { method: 'DELETE' })
  },

  /** 娓呯┖浼氳瘽娑堟伅 */
  clearSessionMessages(sessionId: string): Promise<void> {
    return request(`/chat/sessions/${sessionId}/clear`, { method: 'POST' })
  },

  /** 鎻愪氦鏉＄洰鍙嶉锛堢偣璧?鐐硅俯锛?*/
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

  /** 鍒嗗弶浼氳瘽锛堜粠鎸囧畾鏉＄洰澶勫垱寤烘柊浼氳瘽锛?*/
  forkSession(
    sessionId: string,
    fromEntryId?: string
  ): Promise<ChatSession> {
    return request(`/chat/sessions/${sessionId}/fork`, {
      method: 'POST',
      body: JSON.stringify({ fromEntryId })
    })
  },

  /** A2UI 淇″彿鍥炰紶 */
  sendSignal(name: string, payload: Record<string, unknown>, sessionId: string): Promise<ChatResponse> {
    return request('/chat/signals', {
      method: 'POST',
      body: JSON.stringify({ name, payload, sessionId })
    })
  },

  /** 宸ュ叿纭鍝嶅簲 */
  respondToolConfirmation(requestId: string, confirmed: boolean, reason?: string): Promise<void> {
    return request(`/chat/tool-confirmations/${requestId}`, {
      method: 'POST',
      body: JSON.stringify({ requestId, confirmed, reason })
    })
  },

  /**
   * 涓婁紶鍗曚釜娑堟伅闄勪欢锛堝浘鐗?鏂囦欢锛夈€?
   *
   * 浣跨敤 multipart/form-data锛屽皢鏂囦欢浜岃繘鍒朵氦缁欏悗绔瓨鍌紝杩斿洖鏂囦欢 ID 鍜岃闂?URL 绛変俊鎭€?
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

// 瀵煎嚭 LlmProviderDetail 绫诲瀷锛堝悜鍚庡吋瀹癸級
export type { LlmProviderDetail }

/** LLM Provider 淇℃伅锛堝吋瀹规棫鎺ュ彛锛?*/
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

/** 璁剧疆鐩稿叧 API */
export const settingsApi = {
  /** 鑾峰彇鐢ㄦ埛璁剧疆 */
  getSettings(): Promise<UserSettings> {
    return request('/settings')
  },

  /** 鏇存柊鐢ㄦ埛璁剧疆 */
  updateSettings(settings: UserSettings): Promise<UserSettings> {
    return request('/settings', {
      method: 'PUT',
      body: JSON.stringify(settings)
    })
  },

  /** 鑾峰彇鍙敤鐨?LLM Provider 鍒楄〃锛堝寘鍚缁嗕俊鎭級 */
  getProviders(): Promise<LlmProvider[]> {
    return request('/settings/providers')
  },

  /** 鑾峰彇鎸囧畾 Provider 鐨勮缁嗕俊鎭?*/
  getProviderDetail(providerId: string): Promise<LlmProviderDetail> {
    return request(`/settings/providers/${providerId}`)
  },

  /** 鑾峰彇鎵€鏈?Provider 鐨勫仴搴风姸鎬?*/
  getProviderHealth(): Promise<Record<string, boolean>> {
    return request('/settings/providers/health')
  },

  /** 鑾峰彇鍏ㄥ眬 Reranker 閰嶇疆 */
  getRerankerSettings(): Promise<RerankerSettings> {
    return request('/settings/reranker')
  },

  /** 鏇存柊鍏ㄥ眬 Reranker 閰嶇疆 */
  updateRerankerSettings(settings: RerankerSettingsRequest): Promise<RerankerSettings> {
    return request('/settings/reranker', {
      method: 'PUT',
      body: JSON.stringify(settings)
    })
  },

  /** 鑾峰彇鐭ヨ瘑搴撳叏灞€閰嶇疆 */
  getKnowledgeSettings(): Promise<KnowledgeSettings> {
    return request('/settings/knowledge')
  },

  /** 鏇存柊鐭ヨ瘑搴撳叏灞€閰嶇疆 */
  updateKnowledgeSettings(settings: Record<string, unknown>): Promise<KnowledgeSettings> {
    return request('/settings/knowledge', {
      method: 'PUT',
      body: JSON.stringify(settings)
    })
  },

  /** 鑾峰彇鑱旂綉鎼滅储閰嶇疆 */
  getSearchSettings(): Promise<SearchSettings> {
    return request('/settings/search')
  },

  /** 鏇存柊鑱旂綉鎼滅储閰嶇疆 */
  updateSearchSettings(settings: SearchSettingsRequest): Promise<SearchSettings> {
    return request('/settings/search', {
      method: 'PUT',
      body: JSON.stringify(settings)
    })
  },

  /** 鑾峰彇娓犻亾閰嶇疆锛堟晱鎰熷瓧娈靛凡 mask锛?*/
  getChannelConfig(): Promise<ChannelConfig> {
    return request('/settings/channels')
  },

  /** 鏇存柊娓犻亾閰嶇疆 */
  updateChannelConfig(config: ChannelConfig): Promise<ChannelConfig> {
    return request('/settings/channels', {
      method: 'PUT',
      body: JSON.stringify(config)
    })
  }
}

/** Reranker 閰嶇疆鍝嶅簲 */
export interface RerankerSettings {
  enabled: boolean
  type: string
  model: string
  topK: number
  llmMode: string
  apiProvider: string
  apiKey: string
  apiEndpoint: string
  apiTimeoutMs: number
  memoryRerankEnabled: boolean
  memoryRerankTopK: number
}

/** Reranker 閰嶇疆璇锋眰 */
export interface RerankerSettingsRequest {
  enabled?: boolean
  type?: string
  model?: string
  topK?: number
  llmMode?: string
  apiProvider?: string
  apiKey?: string
  apiEndpoint?: string
  apiTimeoutMs?: number
  memoryRerankEnabled?: boolean
  memoryRerankTopK?: number
}

/** 鐭ヨ瘑搴撳叏灞€閰嶇疆鍝嶅簲 */
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

/** 鑱旂綉鎼滅储閰嶇疆鍝嶅簲 */
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

/** 鑱旂綉鎼滅储閰嶇疆璇锋眰 */
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

/** 鍗曚釜娓犻亾閰嶇疆 */
export interface SingleChannelConfig {
  enabled?: boolean
  [key: string]: unknown
}

/** 娓犻亾閰嶇疆锛堟寜娓犻亾鍚嶅垎缁勶級 */
export interface ChannelConfig {
  feishu?: SingleChannelConfig
  wecom?: SingleChannelConfig
  dingtalk?: SingleChannelConfig
  [key: string]: SingleChannelConfig | undefined
}

/** LLM Provider 绠＄悊 API */
export const llmProviderApi = {
  /** 鑾峰彇鎵€鏈?Provider锛堝寘鎷凡绂佺敤锛?*/
  listProviders(): Promise<LlmProvider[]> {
    return request('/llm-providers')
  },

  /** 鑾峰彇鎵€鏈夊凡鍚敤鐨?Provider */
  listEnabledProviders(): Promise<LlmProvider[]> {
    return request('/llm-providers/enabled')
  },

  /** 鑾峰彇鎵€鏈夐璁剧疆鐨?Provider */
  listPresets(): Promise<LlmProvider[]> {
    return request('/llm-providers/presets')
  },

  /** 鏍规嵁 ID 鑾峰彇 Provider */
  getProvider(id: string): Promise<LlmProvider> {
    return request(`/llm-providers/${id}`)
  },

  /** 鍒涘缓鎴栨洿鏂?Provider */
  saveProvider(provider: CreateProviderRequest): Promise<LlmProvider> {
    return request('/llm-providers', {
      method: 'POST',
      body: JSON.stringify(provider)
    })
  },

  /** 鏇存柊 Provider锛堥儴鍒嗘洿鏂帮級 */
  updateProvider(id: string, provider: UpdateProviderRequest): Promise<LlmProvider> {
    return request(`/llm-providers/${id}`, {
      method: 'PUT',
      body: JSON.stringify(provider)
    })
  },

  /** 鍒犻櫎 Provider锛堜粎鍒犻櫎闈為璁剧疆鐨勶級 */
  deleteProvider(id: string): Promise<void> {
    return request(`/llm-providers/${id}`, {
      method: 'DELETE'
    })
  },

  /** 鑾峰彇 Provider 鍋ュ悍鐘舵€?*/
  getProviderHealth(id: string): Promise<{ healthy: boolean }> {
    return request(`/llm-providers/${id}/health`)
  }
}

/** 鍒涘缓 Provider 璇锋眰 */
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

/** 鏇存柊 Provider 璇锋眰锛堟墍鏈夊瓧娈靛彲閫夛級 */
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

// ========== 妯″潡 19: 鍔熻兘椤甸潰 API ==========

/** 鐭ヨ瘑搴撶鐞?API */
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
  // 鏂囦欢涓婁紶浣跨敤 FormData锛屼笉璁剧疆 Content-Type
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
    return fetch(`${BASE}/knowledge-bases/${kbId}/documents/${docId}/download`).then(res => {
      if (!res.ok) throw new Error('涓嬭浇澶辫触')
      return res.blob()
    })
  },
  getStats(kbId: string): Promise<KbStats> {
    return request(`/knowledge-bases/${kbId}/stats`)
  },
  // 浠ヤ笅鎺ュ彛寰呭悗绔疄鐜?
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

/** Skill 绠＄悊 API */
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
  /** 鑾峰彇 Skill Markdown 瀹氫箟 */
  getSkillMarkdown(skillId: string): Promise<string> {
    return fetch(`${BASE}/skills/${skillId}/markdown`).then(res => {
      if (!res.ok) throw { code: res.status, message: '鑾峰彇澶辫触', timestamp: new Date().toISOString() }
      return res.text()
    })
  },
  /** 鏇存柊 Skill Markdown 瀹氫箟 */
  updateSkillMarkdown(skillId: string, content: string): Promise<void> {
    return request(`/skills/${skillId}/markdown`, {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain' },
      body: content
    } as any)
  }
}

/** MCP Server 绠＄悊 API */
export const mcpApi = {
  /** 鑾峰彇 MCP 鐜鐘舵€侊紙npx 鍙敤鎬х瓑锛?*/
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
  /** 鑾峰彇 MCP Server 杩炴帴鏃ュ織 */
  getConnectionLogs(serverName: string): Promise<McpConnectionLog[]> {
    return request(`/mcp/servers/${serverName}/connection-logs`)
  }
}

/** 杞ㄨ抗鏌ヨ API */
export const traceApi = {
  /**
   * 鍒嗛〉鑾峰彇杞ㄨ抗鍒楄〃
   */
  list(page = 0, size = 20): Promise<PageResult<TraceItem>> {
    return request(`/traces?page=${page}&size=${size}`)
  },
  /**
   * 鑾峰彇鍗曟潯杞ㄨ抗璇︽儏
   */
  get(id: string): Promise<TraceDetail> {
    return request(`/traces/${id}`)
  },
  /**
   * 鑾峰彇杞ㄨ抗姝ラ鍒楄〃
   */
  getSteps(id: string): Promise<TraceStep[]> {
    return request(`/traces/${id}/steps`)
  },
  /**
   * 鑾峰彇杞ㄨ抗姒傝缁熻淇℃伅
   *
   * 鐢ㄤ簬缁熻鍗＄墖鍖哄煙锛堟€绘暟銆佹垚鍔熺巼銆佸钩鍧囨楠ゆ暟绛夛級銆?
   */
  getOverviewStats(window: '24h' | '7d' | '30d' = '7d'): Promise<OverviewStats> {
    return request(`/traces/stats/overview?window=${window}`)
  },
  /**
   * 鑾峰彇宸ュ叿浣跨敤缁熻淇℃伅
   *
   * 杩斿洖鍚勫伐鍏风殑璋冪敤娆℃暟銆佹垚鍔熺巼鍜屽钩鍧囪€楁椂绛夈€?
   */
  getToolStats(): Promise<ToolUsageStats[]> {
    return request('/traces/stats/tools')
  },
  /**
   * 鎼滅储杞ㄨ抗
   *
   * 浣跨敤鍏抽敭瀛楁悳绱㈡渶杩戠殑杞ㄨ抗璁板綍銆?
   */
  search(keyword: string, limit = 20): Promise<TraceItem[]> {
    const params = new URLSearchParams()
    params.append('keyword', keyword)
    params.append('limit', String(limit))
    const query = params.toString()
    return request(`/traces/search?${query}`)
  },
  /**
   * 瀵煎嚭杞ㄨ抗涓?JSON 瀛楃涓?
   *
   * 杩斿洖鍗曟潯杞ㄨ抗鐨勫畬鏁?JSON 鏂囨湰锛屼緵鍓嶇涓嬭浇淇濆瓨銆?
   */
  export(id: string): Promise<string> {
    return request(`/traces/${id}/export`)
  },
  /**
   * 鑾峰彇 Token 娑堣€楃粺璁?
   *
   * 鍙€夌殑璧锋鏃堕棿鐢ㄤ簬闄愬畾缁熻绐楀彛銆?
   */
  getTokenStats(start?: string, end?: string): Promise<TokenConsumptionStats> {
    const params = new URLSearchParams()
    if (start) params.append('start', start)
    if (end) params.append('end', end)
    const query = params.toString()
    return request(`/traces/stats/tokens${query ? `?${query}` : ''}`)
  },
  /**
   * 鑾峰彇杞ㄨ抗璇勪及缁撴灉
   *
   * 杩斿洖鍗曟潯杞ㄨ抗鐨勭绾胯瘎浼板垎鏁颁笌杩濊/寤鸿淇℃伅銆?
   */
  getEvaluation(id: string): Promise<EvaluationResult> {
    return request(`/traces/${id}/evaluation`)
  }
}

/** 宸ヤ綔娴佺鐞?API */
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
  /** 鑾峰彇宸ヤ綔娴?YAML 鍐呭锛堜究鎹锋柟娉曪級 */
  getWorkflowYaml(workflowId: string): Promise<string> {
    return this.getYaml(workflowId).then(res => res.yamlContent)
  },
  /** 鏇存柊宸ヤ綔娴?YAML 鍐呭锛堜究鎹锋柟娉曪級 */
  updateWorkflowYaml(workflowId: string, content: string): Promise<WorkflowDetail> {
    return this.update(workflowId, { yamlContent: content })
  },
  /** 鑾峰彇鍗曚釜鎵ц瀹炰緥璇︽儏 */
  getInstance(instanceId: string): Promise<WorkflowExecution> {
    return request(`/workflows/executions/${instanceId}`)
  },
  /** 鎻愪氦瀹℃壒鍐崇瓥 */
  approve(instanceId: string, stepId: string, req: ApprovalRequest): Promise<WorkflowExecution> {
    return request(`/workflows/executions/${instanceId}/steps/${stepId}/approve`, {
      method: 'POST',
      body: JSON.stringify(req)
    })
  },
  /** 鑾峰彇瀹炰緥浜嬩欢鏃堕棿绾?*/
  getEventTimeline(instanceId: string): Promise<WorkflowEvent[]> {
    return request(`/workflows/executions/${instanceId}/events`)
  },
  /** 鑾峰彇瀹炰緥姝ラ鎵ц鏃ュ織 */
  getStepLogs(instanceId: string): Promise<StepLog[]> {
    return request(`/workflows/executions/${instanceId}/step-logs`)
  },

  // ========== 鏂板 API锛氬伐浣滄祦鎴愮啛鍖栭渶姹?==========

  /** 鑾峰彇宸ヤ綔娴佹墽琛岀粺璁?*/
  getStats(id: string): Promise<WorkflowStats> {
    return request(`/workflows/${id}/stats`)
  },

  /** 鑾峰彇宸ヤ綔娴佹楠ょ粺璁?*/
  getStepStats(id: string): Promise<StepStats[]> {
    return request(`/workflows/${id}/step-stats`)
  },

  /** 鑾峰彇鎵ц瀹炰緥鐨勫畬鏁翠笂涓嬫枃 */
  getExecutionContext(instanceId: string): Promise<Record<string, unknown>> {
    return request(`/workflows/executions/${instanceId}/context`)
  },

  /** 鑾峰彇鎸囧畾姝ラ鐨勮緭鍑?*/
  getStepOutput(instanceId: string, stepId: string): Promise<StepOutput> {
    return request(`/workflows/executions/${instanceId}/steps/${stepId}/output`)
  },

  /** 鑾峰彇宸ヤ綔娴?DAG 渚濊禆鍥炬暟鎹?*/
  getDag(id: string): Promise<DagData> {
    return request(`/workflows/${id}/dag`)
  },

  /** 璇曡繍琛屽伐浣滄祦 */
  dryRun(id: string, inputs?: Record<string, unknown>): Promise<DryRunResult> {
    return request(`/workflows/${id}/dry-run`, {
      method: 'POST',
      body: JSON.stringify({ inputs })
    })
  },

  /** 鏍￠獙 YAML 璇硶鍜岃涔?*/
  validateYaml(yaml: string): Promise<ValidationResponse> {
    return request('/workflows/validate', {
      method: 'POST',
      body: JSON.stringify({ yamlContent: yaml })
    })
  },

  /** 瑙﹀彂 Webhook */
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

  /** 瀵煎嚭鍗曚釜宸ヤ綔娴?*/
  exportWorkflow(id: string): Promise<{ id: string; yamlContent: string }> {
    return request(`/workflows/${id}/export`)
  },

  /** 鎵归噺瀵煎嚭宸ヤ綔娴?*/
  exportWorkflows(ids: string[]): Promise<Array<{ id: string; yamlContent: string }>> {
    return request(`/workflows/export?ids=${ids.join(',')}`)
  },

  /** 瀵煎叆宸ヤ綔娴?*/
  importWorkflow(yamlContent: string): Promise<WorkflowDetail> {
    return request('/workflows/import', {
      method: 'POST',
      body: JSON.stringify({ yamlContent })
    })
  },

  /** 鑾峰彇姝ラ绫诲瀷鍙傛暟 Schema */
  getStepTypes(): Promise<StepTypeSchema[]> {
    return request('/workflows/step-types')
  },

  /** 鎸夋爣绛剧瓫閫夊伐浣滄祦鍒楄〃 */
  listByTag(tag: string): Promise<WorkflowItem[]> {
    return request(`/workflows?tag=${encodeURIComponent(tag)}`)
  }
}

/** Tool 绠＄悊 API */
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
    // 鍚庣浣跨敤 /tools/{id}/test锛屽弬鏁板瓧娈靛悕涓?arguments
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
  /** 鑾峰彇 Tool YAML 瀹氫箟 */
  getToolYaml(toolId: string): Promise<string> {
    return fetch(`${BASE}/tools/${toolId}/yaml`).then(res => {
      if (!res.ok) throw { code: res.status, message: '鑾峰彇澶辫触', timestamp: new Date().toISOString() }
      return res.text()
    })
  },
  /** 鏇存柊 Tool YAML 瀹氫箟 */
  updateToolYaml(toolId: string, content: string): Promise<void> {
    return request(`/tools/${toolId}/yaml`, {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain' },
      body: content
    } as any)
  }
}

/** Agent 绠＄悊 API */
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
  /** 娴佸紡娴嬭瘯鑱婂ぉ */
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
  /** 涓婁笅鏂囩粍瑁呴瑙?*/
  contextPreview(agentId: string, message: string, sessionId?: string): Promise<ContextPreviewResponse> {
    return request(`/agents/${agentId}/context-preview`, {
      method: 'POST',
      body: JSON.stringify({ message, sessionId })
    })
  },
  /** 鑾峰彇 Agent Markdown 瀹氫箟 */
  getAgentMarkdown(agentId: string): Promise<string> {
    return fetch(`${BASE}/agents/${agentId}/markdown`).then(res => {
      if (!res.ok) throw { code: res.status, message: '鑾峰彇澶辫触', timestamp: new Date().toISOString() }
      return res.text()
    })
  },
  /** 鏇存柊 Agent Markdown 瀹氫箟 */
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
  /** 鑾峰彇 Tool 璋冪敤缁熻 */
  getToolAnalytics(timeRange: { from: string; to: string }): Promise<ToolAnalyticsResponse> {
    const query = new URLSearchParams()
    query.append('from', timeRange.from)
    query.append('to', timeRange.to)
    return request(`/analytics/tools?${query.toString()}`)
  },
  /** 鑾峰彇閿欒瓒嬪娍 */
  getErrorTrend(timeRange: { from: string; to: string }): Promise<ErrorTrendDaily[]> {
    const query = new URLSearchParams()
    query.append('from', timeRange.from)
    query.append('to', timeRange.to)
    return request(`/analytics/error-trend?${query.toString()}`)
  }
}

/** 渚濊禆鍏崇郴鍥?API */
export const dependencyApi = {
  /** 鑾峰彇渚濊禆鍏崇郴鍥?*/
  getGraph(): Promise<DependencyGraphResponse> {
    return request('/dependencies/graph')
  }
}

/** 閫氱煡绠＄悊 API */
export const notificationApi = {
  /** 鑾峰彇閫氱煡鍒楄〃锛堝垎椤碉級 */
  listNotifications(userId: string, page?: number, size?: number, urgency?: string): Promise<PageResult<NotificationItem>> {
    const params = new URLSearchParams()
    params.append('userId', userId)
    if (page !== undefined) params.append('page', String(page))
    if (size !== undefined) params.append('size', String(size))
    if (urgency) params.append('urgency', urgency)
    return request(`/notifications?${params.toString()}`)
  },

  /** 鏍囪鍗曟潯閫氱煡宸茶 */
  markAsRead(id: string): Promise<NotificationItem> {
    return request(`/notifications/${id}/read`, { method: 'PUT' })
  },

  /** 鏍囪鎵€鏈夐€氱煡宸茶 */
  markAllAsRead(userId: string): Promise<{ updatedCount: number }> {
    return request(`/notifications/read-all?userId=${encodeURIComponent(userId)}`, { method: 'PUT' })
  }
}

// ========== 璁板繂绠＄悊 API ==========


/** 灏嗗弬鏁板璞¤浆涓?URL 鏌ヨ瀛楃涓诧紝璺宠繃 undefined 鍜岀┖瀛楃涓?*/
function toQueryString(params: Record<string, unknown>): string {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    query.append(key, String(value))
  }
  return query.toString()
}

/** 璁板繂绠＄悊 API */
export const memoryApi = {
  /** 缁熻姒傝 */
  getStats: () => request<MemoryStats>('/memories/stats'),

  /** 缁熶竴鎼滅储 */
  search: (q: string, topK = 10) =>
    request<MemorySearchResult[]>(`/memories/search?q=${encodeURIComponent(q)}&topK=${topK}`),

  /** 鎵嬪姩宸╁浐 */
  triggerConsolidation: () =>
    request<{ status: string; message: string }>('/memories/consolidate', { method: 'POST' }),

  // L3 瀹炰綋
  listEntities: (params: EntityListParams) =>
    request<PageResult<EntitySummary>>(`/memories/entities?${toQueryString(params as unknown as Record<string, unknown>)}`),
  getEntity: (id: string) => request<EntityDetail>(`/memories/entities/${id}`),
  getEntityHistory: (id: string) => request<EntityDetail[]>(`/memories/entities/${id}/history`),
  getRelatedEntities: (id: string, maxDepth = 2) =>
    request<EntitySummary[]>(`/memories/entities/${id}/related?maxDepth=${maxDepth}`),
  createEntity: (req: EntityCreateRequest) =>
    request<EntityDetail>('/memories/entities', { method: 'POST', body: JSON.stringify(req) }),
  updateEntity: (id: string, req: EntityUpdateRequest) =>
    request<EntityDetail>(`/memories/entities/${id}`, { method: 'PUT', body: JSON.stringify(req) }),
  deleteEntity: (id: string) =>
    request<void>(`/memories/entities/${id}`, { method: 'DELETE' }),

  // L3 鍏崇郴
  listRelations: (params: RelationListParams) =>
    request<PageResult<RelationItem>>(`/memories/relations?${toQueryString(params as unknown as Record<string, unknown>)}`),

  // L2 瀵硅瘽
  listConversations: (params: ConversationListParams) =>
    request<PageResult<ConversationSummary>>(`/memories/conversations?${toQueryString(params as unknown as Record<string, unknown>)}`),
  getConversation: (id: string) => request<ConversationDetail>(`/memories/conversations/${id}`),
  deleteConversation: (id: string) =>
    request<void>(`/memories/conversations/${id}`, { method: 'DELETE' }),

  // L4 妯℃澘
  listTemplates: (params: TemplateListParams) =>
    request<PageResult<ProcedureTemplate>>(`/memories/templates?${toQueryString(params as unknown as Record<string, unknown>)}`),
  getTemplate: (id: string) => request<ProcedureTemplate>(`/memories/templates/${id}`),
  deleteTemplate: (id: string) =>
    request<void>(`/memories/templates/${id}`, { method: 'DELETE' }),

  // L4 鍋忓ソ
  listPreferences: (category?: string) =>
    request<PreferenceRule[]>(
      `/memories/preferences${category ? `?category=${encodeURIComponent(category)}` : ''}`
    ),
  deletePreference: (id: string) =>
    request<void>(`/memories/preferences/${id}`, { method: 'DELETE' }),

  // 閬楀繕鏃ュ織
  listForgettingLogs: (params: ForgettingLogListParams) =>
    request<PageResult<ForgettingLog>>(`/memories/forgetting-logs?${toQueryString(params as unknown as Record<string, unknown>)}`),
}
