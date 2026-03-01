package com.lifepilot.memory.procedural;

import java.util.Map;

/**
 * 模板步骤 — 操作模板中的单个执行步骤。
 *
 * <p>完整实现将在后续任务中补充（Task 2.4）。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public record TemplateStep(
        int stepOrder,
        String toolId,
        String action,
        Map<String, String> parameterTemplate,
        String description,
        boolean isOptional
) {
    /** compact constructor：确保 parameterTemplate 不可变。 */
    public TemplateStep {
        parameterTemplate = parameterTemplate != null ? Map.copyOf(parameterTemplate) : Map.of();
    }
}
