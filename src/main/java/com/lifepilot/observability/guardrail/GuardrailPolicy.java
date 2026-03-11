package com.lifepilot.observability.guardrail;

import java.util.List;
import java.util.Map;

/**
 * 护栏策略 sealed interface — 定义五种策略类型。
 *
 * <p>每种策略通过 {@link #priority()} 确定执行顺序（数值越小优先级越高），
 * 通过 {@link #enabled()} 控制是否启用。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public sealed interface GuardrailPolicy
        permits ToolRiskPolicy, BudgetLimitPolicy, ContentSafetyPolicy,
                RateLimitPolicy, DataRedactionPolicy {

    /**
     * 策略 ID。
     */
    String policyId();

    /**
     * 是否启用。
     */
    boolean enabled();

    /**
     * 优先级（数值越小优先级越高）。
     */
    int priority();

    /**
     * 创建工具风险策略实例。
     *
     * @param policyId         策略 ID
     * @param enabled          是否启用
     * @param priority         优先级
     * @param toolRiskMapping  工具 ID → 风险等级映射
     * @param defaultRiskLevel 默认风险等级
     * @return 工具风险策略
     */
    static GuardrailPolicy toolRiskPolicy(String policyId, boolean enabled, int priority,
                                           Map<String, RiskLevel> toolRiskMapping,
                                           RiskLevel defaultRiskLevel) {
        return new ToolRiskPolicy(policyId, enabled, priority, toolRiskMapping, defaultRiskLevel);
    }
}

/**
 * 工具风险策略 — 根据工具风险等级决定审批模式。
 *
 * @param policyId         策略 ID
 * @param enabled          是否启用
 * @param priority         优先级
 * @param toolRiskMapping  工具 ID → 风险等级映射
 * @param defaultRiskLevel 默认风险等级
 * @author zsg
 * @since 2026-02-27
 */
record ToolRiskPolicy(
        String policyId,
        boolean enabled,
        int priority,
        Map<String, RiskLevel> toolRiskMapping,
        RiskLevel defaultRiskLevel
) implements GuardrailPolicy {

    /**
     * 紧凑构造函数 — 使用 Map.copyOf() 保证不可变性。
     */
    ToolRiskPolicy {
        toolRiskMapping = Map.copyOf(toolRiskMapping);
    }
}

/**
 * 预算限制策略 — 限制每日 Token 消耗上限。
 *
 * @param policyId        策略 ID
 * @param enabled         是否启用
 * @param priority        优先级
 * @param dailyTokenLimit 每日 Token 上限
 * @author zsg
 * @since 2026-02-27
 */
record BudgetLimitPolicy(
        String policyId,
        boolean enabled,
        int priority,
        int dailyTokenLimit
) implements GuardrailPolicy {
}

/**
 * 内容安全策略 — 检查输入/输出内容是否包含阻断模式或敏感话题。
 *
 * @param policyId        策略 ID
 * @param enabled         是否启用
 * @param priority        优先级
 * @param blockedPatterns 阻断正则模式列表
 * @param sensitiveTopics 敏感话题列表
 * @author zsg
 * @since 2026-02-27
 */
record ContentSafetyPolicy(
        String policyId,
        boolean enabled,
        int priority,
        List<String> blockedPatterns,
        List<String> sensitiveTopics
) implements GuardrailPolicy {

    /**
     * 紧凑构造函数 — 使用 List.copyOf() 保证不可变性。
     */
    ContentSafetyPolicy {
        blockedPatterns = List.copyOf(blockedPatterns);
        sensitiveTopics = List.copyOf(sensitiveTopics);
    }
}

/**
 * 速率限制策略 — 限制工具调用频率。
 *
 * @param policyId          策略 ID
 * @param enabled           是否启用
 * @param priority          优先级
 * @param maxCallsPerMinute 每分钟最大调用次数
 * @param maxCallsPerHour   每小时最大调用次数
 * @author zsg
 * @since 2026-02-27
 */
record RateLimitPolicy(
        String policyId,
        boolean enabled,
        int priority,
        int maxCallsPerMinute,
        int maxCallsPerHour
) implements GuardrailPolicy {
}

/**
 * 数据脱敏策略 — 标记是否需要对工具输入/输出进行脱敏。
 *
 * @param policyId 策略 ID
 * @param enabled  是否启用
 * @param priority 优先级
 * @author zsg
 * @since 2026-02-27
 */
record DataRedactionPolicy(
        String policyId,
        boolean enabled,
        int priority
) implements GuardrailPolicy {
}
