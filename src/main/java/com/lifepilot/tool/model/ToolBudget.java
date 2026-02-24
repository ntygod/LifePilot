package com.lifepilot.tool.model;

import java.time.Duration;

/**
 * 工具执行预算 — 三维预算模型。
 *
 * <p>每个工具调用都有三维预算：超时时间、最大重试次数、最大成本。
 * 预算在工具注册时声明，在 ToolExecutionPipeline 中强制执行。</p>
 *
 * @param timeout 单次调用的最大等待时间
 * @param maxRetries 失败后的最大重试次数
 * @param maxCostCents 单次调用的最大成本（分）
 * @author zsg
 * @since 2026-02-24
 */
public record ToolBudget(
        Duration timeout,
        int maxRetries,
        int maxCostCents
) {
    /** 默认预算：30 秒超时，2 次重试，无成本限制。 */
    public static final ToolBudget DEFAULT = new ToolBudget(
            Duration.ofSeconds(30), 2, Integer.MAX_VALUE
    );

    /** 严格预算：5 秒超时，0 次重试，无成本限制。 */
    public static final ToolBudget STRICT = new ToolBudget(
            Duration.ofSeconds(5), 0, Integer.MAX_VALUE
    );

    /** MCP 工具默认预算：60 秒超时，2 次重试。 */
    public static final ToolBudget MCP_DEFAULT = new ToolBudget(
            Duration.ofSeconds(60), 2, Integer.MAX_VALUE
    );

    /** 校验预算参数合法性。 */
    public ToolBudget {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("超时时间必须为正数");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("最大重试次数不能为负数");
        }
        if (maxCostCents < 0) {
            throw new IllegalArgumentException("最大成本不能为负数");
        }
    }

    /**
     * 创建自定义预算。
     *
     * @param timeout 超时时间
     * @param maxRetries 最大重试次数
     * @param maxCostCents 最大成本（分）
     * @return 工具预算
     */
    public static ToolBudget of(Duration timeout, int maxRetries, int maxCostCents) {
        return new ToolBudget(timeout, maxRetries, maxCostCents);
    }
}
