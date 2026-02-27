package com.lifepilot.a2a.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A2A Task — 工作单元，具有生命周期状态管理。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record A2aTask(
        String id,
        String contextId,
        A2aTaskStatus status,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) List<A2aMessage> history,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) List<A2aArtifact> artifacts,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> metadata
) {}
