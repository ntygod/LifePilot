package com.lifepilot.tool.dispatch;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;

/**
 * action 级别执行元数据。
 *
 * <p>用于描述某个 action 的风险等级与执行语义，供权限与调度系统动态解析。</p>
 *
 * @param riskLevel action 风险等级
 * @param executionSemantics action 执行语义
 * @author zsg
 * @since 2026-03-31
 */
public record ActionMetadata(
        RiskLevel riskLevel,
        ToolExecutionSemantics executionSemantics
) {

    public ActionMetadata {
        if (riskLevel == null) {
            throw new IllegalArgumentException("action 风险等级不能为空");
        }
        if (executionSemantics == null) {
            throw new IllegalArgumentException("action 执行语义不能为空");
        }
    }
}
