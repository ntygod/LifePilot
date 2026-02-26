// LifePilot 前端类型定义

/** 会话摘要 */
export interface ChatSession {
  id: string
  title: string
  createdAt: string   // ISO 8601
  updatedAt: string
}

/** 消息 */
export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  a2uiComponents?: A2uiComponent[]
  timestamp: number
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
}

/** SSE token 事件 */
export interface SseTokenEvent {
  content: string
  index: number
}

/** SSE 完成事件 */
export interface SseDoneEvent {
  messageId: string
  tokenUsage: TokenUsage
}

/** SSE 错误事件 */
export interface SseErrorEvent {
  code: number
  message: string
}

/** 非流式聊天响应 */
export interface ChatResponse {
  messageId: string
  content: string
  a2ui?: { components: A2uiComponent[] }
  tokenUsage?: TokenUsage
}

/** 统一错误响应 */
export interface ErrorResponse {
  code: number
  message: string
  timestamp: string
}
