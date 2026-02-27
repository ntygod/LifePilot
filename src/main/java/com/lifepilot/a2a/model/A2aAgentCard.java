package com.lifepilot.a2a.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A2A Agent Card — Agent 能力声明文档。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record A2aAgentCard(
        String name,
        String description,
        String url,
        String version,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String protocolVersion,
        List<A2aAgentSkill> skills,
        A2aAgentCapabilities capabilities,
        List<String> defaultInputModes,
        List<String> defaultOutputModes,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> securitySchemes
) {
    public A2aAgentCard {
        skills = skills == null ? List.of() : List.copyOf(skills);
        defaultInputModes = defaultInputModes == null ? List.of("text") : List.copyOf(defaultInputModes);
        defaultOutputModes = defaultOutputModes == null ? List.of("text") : List.copyOf(defaultOutputModes);
    }
}
