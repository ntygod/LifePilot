package com.lifepilot.memory.governance.lifecycle;

/**
 * 生命周期变化来源。
 *
 * @author zsg
 * @since 2026-04-23
 */
public enum ChangeSource {
    /** 工具显式调用（memory.cancel / complete / supersede / delete 等）。 */
    TOOL_EXPLICIT,
    /** LLM 语义识别（对话提取 / 反思 / 画像巩固等）。 */
    LLM_SEMANTIC,
    /** Cron 扫描到期。 */
    CRON_EXPIRE,
    /** upsert 语义冲突裁决后的状态转换。 */
    CONFLICT_RESOLVE,
    /** 负反馈累计达阈值触发。 */
    NEGATIVE_FEEDBACK,
    /** 主动任务取消级联。 */
    PROACTIVE_CANCEL,
    /** 前端 UI 编辑 / CRUD 触发。 */
    UI_EDIT,
    /** 派生实体源失效触发（REGENERATION_NEEDED 时避免递归）。 */
    DERIVATION_TRIGGER
}
