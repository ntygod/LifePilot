package com.lifepilot.conversation.transcript;

/**
 * Transcript 条目类型。
 *
 * @author zsg
 * @since 2026-03-23
 */
public enum TranscriptEntryType {

    USER_MESSAGE("user_message"),
    ASSISTANT_MESSAGE("assistant_message"),
    TOOL_CALL("tool_call"),
    TOOL_RESULT("tool_result"),
    COMPACTION_SUMMARY("compaction_summary"),
    BRANCH_SUMMARY("branch_summary"),
    CUSTOM_CONTEXT("custom_context"),
    SYSTEM_EVENT("system_event"),
    MEMORY_FLUSH_EVENT("memory_flush_event"),
    ARTIFACT_REF("artifact_ref");

    private final String value;

    TranscriptEntryType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static TranscriptEntryType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return CUSTOM_CONTEXT;
        }
        for (TranscriptEntryType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return CUSTOM_CONTEXT;
    }

    public static TranscriptEntryType fromRole(String role) {
        if (role == null || role.isBlank()) {
            return CUSTOM_CONTEXT;
        }
        return switch (role.strip()) {
            case "user" -> USER_MESSAGE;
            case "assistant" -> ASSISTANT_MESSAGE;
            case "system" -> SYSTEM_EVENT;
            default -> CUSTOM_CONTEXT;
        };
    }
}
