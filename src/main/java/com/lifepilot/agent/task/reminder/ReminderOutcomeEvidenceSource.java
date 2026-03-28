package com.lifepilot.agent.task.reminder;

/**
 * 提醒隐式结果证据来源。
 *
 * @author zsg
 * @since 2026-03-28
 */
public enum ReminderOutcomeEvidenceSource {
    WORKSPACE_STATE,
    WORKFLOW_INSTANCE,
    WORKFLOW_STEP_LOG,
    TRACE_TOOL_OUTPUT,
    SEMANTIC_STATE,
    CONVERSATION_MESSAGE
}
