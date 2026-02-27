package com.lifepilot.a2a.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * A2A Agent Skill — Agent Card 中的技能单元。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record A2aAgentSkill(
        String id,
        String name,
        String description,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) List<String> inputModes,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) List<String> outputModes
) {}
