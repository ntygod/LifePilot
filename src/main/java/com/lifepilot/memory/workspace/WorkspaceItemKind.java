package com.lifepilot.memory.workspace;

/**
 * 工作区条目类型。
 *
 * @author zsg
 * @since 2026-03-20
 */
public enum WorkspaceItemKind {
    PENDING_DECISION("待确认"),
    TASK_STATE("任务状态"),
    WORKING_SET("临时集合");

    private final String label;

    WorkspaceItemKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
