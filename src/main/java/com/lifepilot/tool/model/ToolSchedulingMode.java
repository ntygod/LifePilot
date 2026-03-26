package com.lifepilot.tool.model;

/**
 * 工具调度模式。
 *
 * <p>用于指导 ReAct 循环在同一轮内如何编排多个工具调用：</p>
 * <ul>
 *   <li>{@link #PARALLEL_SAFE}：可直接与其他并行安全工具同波次执行</li>
 *   <li>{@link #RESOURCE_SERIALIZED}：允许并行，但同资源键冲突时必须拆波次串行</li>
 *   <li>{@link #SEQUENTIAL}：必须单独成波次顺序执行</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-26
 */
public enum ToolSchedulingMode {

    PARALLEL_SAFE,
    RESOURCE_SERIALIZED,
    SEQUENTIAL
}
