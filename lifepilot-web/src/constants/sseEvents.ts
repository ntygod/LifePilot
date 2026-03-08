/**
 * SSE 事件类型常量。
 * 
 * 统一管理所有 SSE 事件类型，避免硬编码字符串，提高类型安全性。
 * 
 * 事件类型说明：
 * - Chat 模块：TOKEN, REASONING, UI, DONE, ERROR, HEARTBEAT
 * - A2A 模块：TASK_STATUS_UPDATE, TASK_ARTIFACT_UPDATE, TASK_COMPLETE
 * 
 * @see 后端对应常量类：com.lifepilot.interaction.web.sse.SseEventType
 */
export const SSE_EVENT_TYPES = {
  // Chat 模块事件类型
  /** 增量文本片段事件 */
  TOKEN: 'token',
  /** 推理过程事件（Reasoning Timeline） */
  REASONING: 'reasoning',
  /** UI 组件更新事件 */
  UI: 'ui',
  /** 消息完成事件 */
  DONE: 'done',
  /** 错误事件 */
  ERROR: 'error',
  /** 心跳事件 */
  HEARTBEAT: 'heartbeat',
  /** 媒体数据事件（截图等二进制数据） */
  MEDIA: 'media',
  // Trace 模块事件类型
  /** Trace 开始事件 */
  TRACE_START: 'trace-start',
  /** Trace 步骤事件 */
  TRACE_STEP: 'trace-step',
  /** Trace 结束事件 */
  TRACE_END: 'trace-end',
  // A2A 模块事件类型
  /** 任务状态更新事件 */
  TASK_STATUS_UPDATE: 'task-status-update',
  /** 任务产物更新事件 */
  TASK_ARTIFACT_UPDATE: 'task-artifact-update',
  /** 任务完成事件 */
  TASK_COMPLETE: 'task-complete',
} as const

/**
 * SSE 事件类型值类型。
 */
export type SseEventType = typeof SSE_EVENT_TYPES[keyof typeof SSE_EVENT_TYPES]
