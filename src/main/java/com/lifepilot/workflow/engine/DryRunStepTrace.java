package com.lifepilot.workflow.engine;

import java.util.Map;

import org.springframework.lang.Nullable;

/**
 * 试运行步骤追踪记录。
 *
 * @param stepId              步骤 ID
 * @param stepType            步骤类型
 * @param resolvedParams      解析后的参数（表达式已求值，缺失变量用占位符）
 * @param branchResult        条件步骤的分支求值结果（非条件步骤为 null）
 * @param estimatedIterations 循环步骤的预估迭代次数（非循环步骤为 null）
 * @param level               DAG 拓扑层级
 * @author zsg
 * @since 2026-03-13
 */
public record DryRunStepTrace(
        String stepId,
        String stepType,
        Map<String, Object> resolvedParams,
        @Nullable String branchResult,
        @Nullable Integer estimatedIterations,
        int level
) {

    public DryRunStepTrace {
        resolvedParams = resolvedParams == null ? Map.of() : Map.copyOf(resolvedParams);
    }
}
