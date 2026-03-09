package com.lifepilot.workflow.engine;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.lifepilot.workflow.model.WorkflowStep;

/**
 * DAG 执行计划，包含拓扑排序结果。
 *
 * @param topologicalOrder 拓扑排序后的步骤 ID 列表
 * @param stepMap          步骤 ID → WorkflowStep 映射
 * @param dependencyMap    步骤 ID → 前置依赖 ID 集合
 * @author zsg
 * @since 2026-03-09
 */
public record ExecutionPlan(
        List<String> topologicalOrder,
        Map<String, WorkflowStep> stepMap,
        Map<String, Set<String>> dependencyMap
) {
}
