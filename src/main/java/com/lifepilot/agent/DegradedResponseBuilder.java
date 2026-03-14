package com.lifepilot.agent;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;

import java.util.List;

/**
 * 降级响应构建器 — 当 ReAct 循环异常终止时生成友好的降级响应。
 *
 * @author zsg
 * @since 2026-03-14
 */
public final class DegradedResponseBuilder {

    private DegradedResponseBuilder() {}

    /**
     * 以指定原因终止循环，生成降级响应。
     *
     * @param state  当前状态（done == false）
     * @param reason 终止原因
     * @return 标记为 done 的新状态，含降级响应文本
     */
    public static ReactAgentState terminateWithReason(ReactAgentState state, String reason) {
        String degradedResponse = buildDegradedResponse(state.steps(), reason);
        return state.toBuilder()
                .done(true)
                .finalOutput(degradedResponse)
                .terminationReason(reason)
                .build();
    }

    /**
     * 基于已执行步骤生成降级响应文本。
     *
     * <p>从历史步骤中提取成功的工具调用结果，拼接为摘要。
     * 无成功观察时返回友好提示。</p>
     *
     * @param steps  已执行的步骤列表
     * @param reason 终止原因
     * @return 降级响应文本
     */
    static String buildDegradedResponse(List<ReactStep> steps, String reason) {
        var successfulObs = steps.stream()
                .filter(s -> s instanceof ReactStep.Observation obs && obs.success())
                .map(s -> (ReactStep.Observation) s)
                .toList();

        if (successfulObs.isEmpty()) {
            return "抱歉，处理您的请求时资源耗尽（" + reason + "），请尝试简化请求或稍后重试。";
        }

        var sb = new StringBuilder("处理过程中资源耗尽，以下是已获取的信息：\n");
        for (var obs : successfulObs) {
            sb.append("- ").append(obs.toolId()).append("：").append(obs.output()).append("\n");
        }
        sb.append("\n（处理未完成：").append(reason).append("）");
        return sb.toString();
    }
}
