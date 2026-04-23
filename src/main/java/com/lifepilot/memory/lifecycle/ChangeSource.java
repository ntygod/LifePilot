package com.lifepilot.memory.lifecycle;

/**
 * 生命周期变化来源。
 *
 * @author zsg
 * @since 2026-04-23
 */
public enum ChangeSource {
    TOOL_EXPLICIT, LLM_SEMANTIC, CRON_EXPIRE, CONFLICT_RESOLVE,
    NEGATIVE_FEEDBACK, PROACTIVE_CANCEL, UI_EDIT, DERIVATION_TRIGGER
}
