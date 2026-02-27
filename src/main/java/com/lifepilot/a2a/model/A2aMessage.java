package com.lifepilot.a2a.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A2A Message — Agent 间交换信息的基本单元。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record A2aMessage(
        String messageId,
        A2aRole role,
        List<A2aPart> parts,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String taskId,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String contextId,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> metadata
) {
    public A2aMessage {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
