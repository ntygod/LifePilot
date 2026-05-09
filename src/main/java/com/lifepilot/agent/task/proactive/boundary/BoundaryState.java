package com.lifepilot.agent.task.proactive.boundary;

/**
 * 任务边界状态 — 标识用户当前是否处于"刚完成一段结构化任务的窗口内"。
 *
 * <p>参考：JetBrains 2026 田野研究（arXiv:2601.10253）发现 post-commit 时段 engagement
 * 52%，mid-task 时段 dismissed 62%。CHI 2025 Goldilocks Time Window（arXiv:2504.09332）
 * 进一步证明时机错位比内容错位更伤害用户体验。</p>
 *
 * <p>由 {@link BoundarySignalCollector} 根据已订阅的对话完成 / 工作流完成 / A2A 委派
 * 结束事件推导；由 {@code ContextPacket.boundaryState} 携带给 {@code DecisionGate}。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public enum BoundaryState {

    /** 处于边界窗口内（距最近边界事件 ≤ boundaryWindowMinutes）。 */
    IN_BOUNDARY,

    /** 不处于边界窗口内（无边界事件或已超过窗口）。 */
    OUT_OF_BOUNDARY,

    /** 未启用或无法判断（BoundarySignalCollector 未装配时使用）。 */
    UNKNOWN
}
