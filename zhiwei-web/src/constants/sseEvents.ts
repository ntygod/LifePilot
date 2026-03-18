/**
 * Shared SSE event names used by the frontend.
 *
 * Keep these values in sync with
 * `com.lifepilot.interaction.web.sse.SseEventType`.
 */
export const SSE_EVENT_TYPES = {
  TOKEN: 'token',
  REASONING: 'reasoning',
  UI: 'ui',
  DONE: 'done',
  ERROR: 'error',
  HEARTBEAT: 'heartbeat',
  MEDIA: 'media',

  TRACE_START: 'trace-start',
  TRACE_STEP: 'trace-step',
  TRACE_END: 'trace-end',

  TASK_STATUS_UPDATE: 'task-status-update',
  TASK_ARTIFACT_UPDATE: 'task-artifact-update',
  TASK_COMPLETE: 'task-complete',

  NOTIFICATION: 'notification',

  MCP_STATUS_SNAPSHOT: 'mcp-status-snapshot',
  MCP_STATUS_CHANGE: 'mcp-status-change',

  WORKFLOW_EXECUTIONS_SNAPSHOT: 'workflow-executions-snapshot',
  WORKFLOW_EXECUTION_SNAPSHOT: 'workflow-execution-snapshot',
  WORKFLOW_EXECUTION_UPDATED: 'workflow-execution-updated',
  WORKFLOW_TIMELINE_SNAPSHOT: 'workflow-timeline-snapshot',
  WORKFLOW_EVENT_CREATED: 'workflow-event-created',
  WORKFLOW_STEP_LOGS_SNAPSHOT: 'workflow-step-logs-snapshot',
  WORKFLOW_STEP_LOG_CREATED: 'workflow-step-log-created',

  TOOL_CONFIRMATION_REQUEST: 'tool-confirmation-request',

  TRANSCRIPTION: 'transcription',
} as const

export type SseEventType = typeof SSE_EVENT_TYPES[keyof typeof SSE_EVENT_TYPES]
