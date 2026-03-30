package com.lifepilot.agent;

import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.CompletionReason;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import org.springframework.lang.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 降级响应构建器。
 *
 * @author zsg
 * @since 2026-03-14
 */
public final class DegradedResponseBuilder {

    private DegradedResponseBuilder() {}

    /**
     * 以指定原因终止循环，生成用户可读的降级响应。
     */
    public static ReactAgentState terminateWithReason(ReactAgentState state, String reason) {
        return terminateWithReason(state, reason, null);
    }

    public static ReactAgentState terminateWithReason(ReactAgentState state,
                                                      String reason,
                                                      @Nullable CompletionReason completionReason) {
        String degradedResponse = buildDegradedResponse(state, reason);
        return state.toBuilder()
                .done(true)
                .finalOutput(degradedResponse)
                .terminationReason(reason)
                .completionReason(completionReason)
                .completionMode(CompletionMode.DEGRADED)
                .build();
    }

    /**
     * 基于当前可见正文和执行进度构建降级说明。
     */
    static String buildDegradedResponse(ReactAgentState state, String reason) {
        String visibleNarrative = extractVisibleNarrative(state);
        String note = buildTerminalNote(reason);

        if (!visibleNarrative.isBlank()) {
            return mergeNarrativeWithNote(visibleNarrative, note);
        }

        String toolSummary = summarizeSuccessfulTools(state.steps());
        if (!toolSummary.isBlank()) {
            return """
                    本轮处理已中断。

                    已完成的步骤：%s

                    %s
                    """.formatted(toolSummary, note).trim();
        }

        return """
                本轮处理已中断。

                %s
                """.formatted(note).trim();
    }

    private static String extractVisibleNarrative(ReactAgentState state) {
        if (state.finalOutput() != null && !state.finalOutput().isBlank()) {
            return state.finalOutput().strip();
        }

        for (int index = state.steps().size() - 1; index >= 0; index--) {
            ReactStep step = state.steps().get(index);
            if (step instanceof ReactStep.Answer(var content) && content != null && !content.isBlank()) {
                return content.strip();
            }
        }

        return "";
    }

    private static String summarizeSuccessfulTools(List<ReactStep> steps) {
        Set<String> toolNames = new LinkedHashSet<>();
        for (ReactStep step : steps) {
            if (step instanceof ReactStep.Observation observation && observation.success()) {
                String displayName = observation.toolName() != null && !observation.toolName().isBlank()
                        ? observation.toolName()
                        : observation.toolId();
                if (displayName != null && !displayName.isBlank()) {
                    toolNames.add(displayName);
                }
            }
            if (toolNames.size() >= 4) {
                break;
            }
        }
        if (toolNames.isEmpty()) {
            return "";
        }
        return String.join("、", toolNames);
    }

    private static String buildTerminalNote(String reason) {
        return """
                我已保留当前进度。
                原因：%s
                你可以点击“继续执行”从当前进度接着处理，或点击“重新开始”重新跑一遍本轮任务。
                """.formatted(reason).trim();
    }

    private static String mergeNarrativeWithNote(String visibleNarrative, String note) {
        String normalizedNarrative = normalizeForCompare(visibleNarrative);
        String normalizedNote = normalizeForCompare(note);

        if (normalizedNarrative.contains(normalizedNote)) {
            return visibleNarrative.strip();
        }

        return visibleNarrative.stripTrailing() + "\n\n---\n" + note;
    }

    private static String normalizeForCompare(String text) {
        return text.replace("\r", "")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();
    }
}
