package com.lifepilot.agent.intelligence.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 决策信号 — AdaptiveDecisionEngine 产出的结构化信号，注入 ContextAssembler。
 *
 * <p>不替代 LLM 决策，而是为 LLM 提供额外的上下文信息，让它做出更好的决策。</p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public record DecisionSignal(
    /** 匹配到的历史经验模板（如有）。 */
    @Nullable ExperienceHint experienceHint,
    /** 工具能力评估摘要。 */
    List<ToolCapabilityHint> toolHints,
    /** 风险预警。 */
    List<RiskWarning> risks,
    /** 环境提示。 */
    @Nullable String environmentHint
) {
    public DecisionSignal {
        toolHints = List.copyOf(toolHints);
        risks = List.copyOf(risks);
    }

    public boolean isEmpty() {
        return experienceHint == null && toolHints.isEmpty() && risks.isEmpty()
                && (environmentHint == null || environmentHint.isBlank());
    }

    /**
     * 历史经验提示 — 告诉 LLM "类似任务以前怎么做的"。
     */
    public record ExperienceHint(
        String templateName,
        float confidence,
        String pattern,
        @Nullable String caveat
    ) {}

    /**
     * 工具能力提示 — 告诉 LLM "这个工具最近表现如何"。
     */
    public record ToolCapabilityHint(
        String toolId,
        float proficiency,
        @Nullable String issue
    ) {}

    /**
     * 风险预警 — 告诉 LLM "注意这些潜在问题"。
     */
    public record RiskWarning(
        String type,
        String description,
        float severity
    ) {}

    public static DecisionSignal empty() {
        return new DecisionSignal(null, List.of(), List.of(), null);
    }
}
