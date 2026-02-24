package com.lifepilot.agent.model;

/**
 * Agent 执行阶段枚举。
 *
 * <p>定义 Agent 生命周期的六个阶段及合法转换路径。
 *
 * @author zsg
 * @since 2026-07-20
 */
public enum AgentPhase {

    /** 意图理解 */
    UNDERSTANDING("意图理解"),

    /** 任务规划 */
    PLANNING("任务规划"),

    /** 工具执行 */
    EXECUTING("工具执行"),

    /** 反思评估 */
    REFLECTING("反思评估"),

    /** 生成响应 */
    RESPONDING("生成响应"),

    /** 终止 */
    TERMINATED("终止");

    private final String description;

    AgentPhase(String description) {
        this.description = description;
    }

    /**
     * 获取阶段描述。
     *
     * @return 中文描述
     */
    public String description() {
        return description;
    }

    /**
     * 判断是否允许转换到目标阶段。
     *
     * @param target 目标阶段
     * @return 是否允许转换
     */
    public boolean canTransitionTo(AgentPhase target) {
        return switch (this) {
            case UNDERSTANDING -> target == PLANNING
                               || target == RESPONDING
                               || target == TERMINATED;
            case PLANNING      -> target == EXECUTING
                               || target == TERMINATED;
            case EXECUTING     -> target == EXECUTING
                               || target == REFLECTING
                               || target == RESPONDING
                               || target == UNDERSTANDING
                               || target == TERMINATED;
            case REFLECTING    -> target == EXECUTING
                               || target == PLANNING
                               || target == RESPONDING
                               || target == TERMINATED;
            case RESPONDING    -> target == TERMINATED;
            case TERMINATED    -> false;
        };
    }

    /**
     * 是否为终态。
     *
     * @return 仅 TERMINATED 返回 true
     */
    public boolean isTerminal() {
        return this == TERMINATED;
    }

    /**
     * 是否为活跃阶段。
     *
     * @return 非 TERMINATED 返回 true
     */
    public boolean isActive() {
        return this != TERMINATED;
    }
}
