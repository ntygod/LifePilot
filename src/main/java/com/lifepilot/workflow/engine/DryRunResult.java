package com.lifepilot.workflow.engine;

import java.util.List;

import org.springframework.lang.Nullable;

/**
 * 试运行结果。
 *
 * @param workflowId 工作流 ID
 * @param stepTraces 步骤追踪列表（按 DAG 拓扑顺序）
 * @param dagValid   DAG 是否有效（无环）
 * @param dagError   DAG 错误信息（有环时）
 * @param warnings   警告列表（缺失变量等）
 * @author zsg
 * @since 2026-03-13
 */
public record DryRunResult(
        String workflowId,
        List<DryRunStepTrace> stepTraces,
        boolean dagValid,
        @Nullable String dagError,
        List<String> warnings
) {

    public DryRunResult {
        stepTraces = stepTraces == null ? List.of() : List.copyOf(stepTraces);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
