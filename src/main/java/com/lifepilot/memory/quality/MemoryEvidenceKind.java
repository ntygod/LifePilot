package com.lifepilot.memory.quality;

/**
 * 记忆证据类型。
 *
 * @author zsg
 * @since 2026-05-05
 */
public enum MemoryEvidenceKind {
    USER_EXPLICIT,
    USER_CONFIRMED,
    TOOL_VERIFIED,
    DOCUMENT_GROUNDED,
    CHAT_INFERRED,
    BEHAVIOR_INFERRED,
    LLM_SUMMARIZED_EXPERIENCE,
    DERIVED,
    UNKNOWN
}
