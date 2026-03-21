package com.lifepilot.agent;

import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;

import java.util.List;

/**
 * 降级响应构建器。
 *
 * @author zsg
 * @since 2026-03-14
 */
public final class DegradedResponseBuilder {

    private DegradedResponseBuilder() {}

    /**
     * 以指定原因终止循环，生成降级响应。
     */
    public static ReactAgentState terminateWithReason(ReactAgentState state, String reason) {
        String degradedResponse = buildDegradedResponse(state.steps(), reason);
        return state.toBuilder()
                .done(true)
                .finalOutput(degradedResponse)
                .terminationReason(reason)
                .completionMode(CompletionMode.DEGRADED)
                .build();
    }

    /**
     * 基于已执行步骤生成降级响应文本。
     */
    static String buildDegradedResponse(List<ReactStep> steps, String reason) {
        var successfulObs = steps.stream()
                .filter(step -> step instanceof ReactStep.Observation obs && obs.success())
                .map(step -> (ReactStep.Observation) step)
                .toList();

        if (successfulObs.isEmpty()) {
            return """
                    本轮任务已中断，原因：%s。
                    我已保留当前进度；请再次提交同一个任务，我会从断点继续执行。
                    """.formatted(reason).trim();
        }

        var sb = new StringBuilder("本轮任务已中断，以下是中断前已获取的信息：\n");
        for (var obs : successfulObs) {
            sb.append("- ").append(obs.toolId()).append("：").append(obs.output()).append("\n");
        }
        sb.append("\n原因：").append(reason)
                .append("\n我已保留当前进度；请再次提交同一个任务，我会从断点继续执行。");
        return sb.toString();
    }
}
