package com.lifepilot.memory.working;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

/**
 * 工作记忆槽位 — L1 工作记忆中的最小存储单元。
 *
 * <p>sealed interface 限定三种槽位类型，确保类型安全和穷举匹配。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ConversationSlot.class, name = "CONVERSATION"),
        @JsonSubTypes.Type(value = ToolResultSlot.class, name = "TOOL_RESULT"),
        @JsonSubTypes.Type(value = ReasoningSlot.class, name = "REASONING")
})
public sealed interface WorkingMemorySlot
        permits ConversationSlot, ToolResultSlot, ReasoningSlot {

    /** 槽位占用的 Token 数量。 */
    int tokenCount();

    /** 槽位创建时间。 */
    Instant createdAt();

    /** 槽位重要度评分 [0.0, 1.0]，用于淘汰决策。 */
    float importance();
}
