package com.lifepilot.workflow.registry;

import com.lifepilot.workflow.model.WorkflowDefinition;

import java.util.Optional;

/**
 * 工作流注册中心 — 管理 WorkflowDefinition 的注册、查询、启用/禁用。
 *
 * <p>当前为最小桩实现，仅提供 {@link #find(String)} 方法供 {@code WorkflowEngine} 编译。
 * 完整实现将在 task 12.1 中完成。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WorkflowRegistry {

    /**
     * 根据 ID 查找工作流定义。
     *
     * @param workflowId 工作流定义 ID
     * @return 工作流定义 Optional，未找到时返回 empty
     */
    public Optional<WorkflowDefinition> find(String workflowId) {
        return Optional.empty();
    }
}
