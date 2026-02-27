package com.lifepilot.a2a.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.lang.Nullable;

/**
 * A2A Task 状态。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record A2aTaskStatus(
        A2aTaskState state,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) A2aMessage message,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String timestamp
) {}
