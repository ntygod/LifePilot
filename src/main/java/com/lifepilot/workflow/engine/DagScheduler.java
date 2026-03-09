package com.lifepilot.workflow.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.lifepilot.workflow.model.WorkflowStep;

/**
 * DAG 拓扑排序与并行调度器。
 *
 * <p>无状态组件，所有状态通过参数传入。使用 Kahn 算法进行拓扑排序和环检测。
 * 当步骤均无 dependsOn 声明时，退化为按列表原始顺序串行执行（每个步骤隐式依赖前一个）。
 *
 * @author zsg
 * @since 2026-03-09
 */
public class DagScheduler {

    /** 当前执行计划（由 buildExecutionPlan 构建）。 */
    private ExecutionPlan currentPlan;

    /**
     * 构建执行计划：Kahn 拓扑排序 + 环检测。
     *
     * <p>当所有步骤的 dependsOn 均为空时，退化为按列表原始顺序串行：
     * 每个步骤隐式依赖前一个步骤。
     *
     * @param steps 步骤列表
     * @return 执行计划
     * @throws IllegalArgumentException 存在环依赖时抛出，包含参与环的步骤 ID
     */
    public ExecutionPlan buildExecutionPlan(List<WorkflowStep> steps) {
        // 构建 stepMap
        Map<String, WorkflowStep> stepMap = new LinkedHashMap<>();
        for (WorkflowStep step : steps) {
            stepMap.put(step.id(), step);
        }

        // 检查是否所有步骤都无 dependsOn → 退化为串行
        boolean allEmpty = steps.stream()
                .allMatch(s -> s.dependsOn() == null || s.dependsOn().isEmpty());

        Map<String, Set<String>> dependencyMap = new HashMap<>();
        if (allEmpty) {
            // 退化模式：每个步骤隐式依赖前一个
            for (int i = 0; i < steps.size(); i++) {
                String id = steps.get(i).id();
                if (i == 0) {
                    dependencyMap.put(id, Set.of());
                } else {
                    dependencyMap.put(id, Set.of(steps.get(i - 1).id()));
                }
            }
        } else {
            // DAG 模式：使用显式 dependsOn
            for (WorkflowStep step : steps) {
                Set<String> deps = (step.dependsOn() != null && !step.dependsOn().isEmpty())
                        ? new HashSet<>(step.dependsOn())
                        : Set.of();
                dependencyMap.put(step.id(), deps);
            }
        }

        // Kahn 拓扑排序
        List<String> topologicalOrder = kahnSort(stepMap.keySet(), dependencyMap);

        this.currentPlan = new ExecutionPlan(
                List.copyOf(topologicalOrder),
                Map.copyOf(stepMap),
                Map.copyOf(dependencyMap)
        );
        return this.currentPlan;
    }

    /**
     * 获取当前可执行的步骤（所有前置依赖均已在 completedStepIds 中）。
     *
     * @param completedStepIds 已完成步骤 ID 集合
     * @return 就绪步骤列表
     */
    public List<WorkflowStep> getReadySteps(Set<String> completedStepIds) {
        if (currentPlan == null) {
            return List.of();
        }
        List<WorkflowStep> ready = new ArrayList<>();
        for (String stepId : currentPlan.topologicalOrder()) {
            if (completedStepIds.contains(stepId)) {
                continue; // 已完成，跳过
            }
            Set<String> deps = currentPlan.dependencyMap().getOrDefault(stepId, Set.of());
            if (completedStepIds.containsAll(deps)) {
                ready.add(currentPlan.stepMap().get(stepId));
            }
        }
        return List.copyOf(ready);
    }

    /**
     * 是否还有未完成的步骤。
     *
     * @param completedStepIds 已完成步骤 ID 集合
     * @return true 表示还有未完成步骤
     */
    public boolean hasNext(Set<String> completedStepIds) {
        if (currentPlan == null) {
            return false;
        }
        return completedStepIds.size() < currentPlan.stepMap().size();
    }

    // ==================== 内部方法 ====================

    /**
     * Kahn 拓扑排序算法。
     *
     * @param nodeIds       所有节点 ID
     * @param dependencyMap 节点 ID → 前置依赖 ID 集合
     * @return 拓扑排序后的节点 ID 列表
     * @throws IllegalArgumentException 存在环依赖时抛出
     */
    private List<String> kahnSort(Set<String> nodeIds,
                                  Map<String, Set<String>> dependencyMap) {
        // 构建入度表
        Map<String, Integer> inDegree = new HashMap<>();
        // 构建邻接表（前驱 → 后继列表）
        Map<String, List<String>> adjacency = new HashMap<>();

        for (String id : nodeIds) {
            inDegree.put(id, 0);
            adjacency.put(id, new ArrayList<>());
        }

        for (String id : nodeIds) {
            Set<String> deps = dependencyMap.getOrDefault(id, Set.of());
            inDegree.put(id, deps.size());
            for (String dep : deps) {
                adjacency.computeIfAbsent(dep, k -> new ArrayList<>()).add(id);
            }
        }

        // BFS：入度为 0 的节点入队
        Queue<String> queue = new ArrayDeque<>();
        for (String id : nodeIds) {
            if (inDegree.get(id) == 0) {
                queue.add(id);
            }
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(current);
            for (String successor : adjacency.getOrDefault(current, List.of())) {
                int newDegree = inDegree.get(successor) - 1;
                inDegree.put(successor, newDegree);
                if (newDegree == 0) {
                    queue.add(successor);
                }
            }
        }

        // 环检测：处理节点数 < 总节点数
        if (sorted.size() < nodeIds.size()) {
            Set<String> cycleNodes = new HashSet<>(nodeIds);
            cycleNodes.removeAll(new HashSet<>(sorted));
            throw new IllegalArgumentException(
                    "工作流步骤存在环依赖: " + cycleNodes);
        }

        return sorted;
    }
}
