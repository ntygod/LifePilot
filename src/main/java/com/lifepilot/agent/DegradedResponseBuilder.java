package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.CompletionReason;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import org.springframework.lang.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        String toolFailure = extractRecentToolFailure(state.steps());
        String displayReason = toolFailure != null ? toolFailure : reason;
        String note = buildTerminalNote(displayReason);

        if (!visibleNarrative.isBlank()) {
            return mergeNarrativeWithNote(visibleNarrative, note);
        }

        String toolSummary = summarizeSuccessfulTools(state.steps());
        if (!toolSummary.isBlank()) {
            return """
                    本轮处理已中断。

                    已完成的步骤：
                    - %s

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

    private static final ObjectMapper PRODUCT_MAPPER = new ObjectMapper();

    private static String summarizeSuccessfulTools(List<ReactStep> steps) {
        Set<String> entries = new LinkedHashSet<>();
        for (ReactStep step : steps) {
            if (step instanceof ReactStep.Observation observation && observation.success()) {
                String displayName = observation.toolName() != null && !observation.toolName().isBlank()
                        ? observation.toolName()
                        : observation.toolId();
                if (displayName == null || displayName.isBlank()) {
                    continue;
                }
                String product = extractProduct(observation.toolId(), observation.output());
                entries.add(product.isBlank() ? displayName : displayName + " → " + product);
            }
            if (entries.size() >= 4) {
                break;
            }
        }
        if (entries.isEmpty()) {
            return "";
        }
        return String.join("\n- ", entries);
    }

    /**
     * 从工具输出中提取关键产物（路径 / 任务名 / URL），让降级响应能告诉用户产物去哪。
     * 失败/无法解析时返回空串，调用方退化为只显示工具名。
     */
    private static String extractProduct(@Nullable String toolId, @Nullable String output) {
        if (toolId == null || output == null || output.isBlank()) {
            return "";
        }
        try {
            Object parsed = PRODUCT_MAPPER.readValue(output, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) {
                return "";
            }
            Object data = map.get("data") instanceof Map<?, ?> ? map.get("data") : map;
            Map<?, ?> body = (Map<?, ?>) data;
            return switch (toolId) {
                case "file.write", "file_write" -> stringValue(body.get("path"));
                case "cron", "cron.create", "cron_create" -> joinValues(
                        stringValue(body.get("name")), stringValue(body.get("schedule")));
                case "web.fetch", "web_fetch" -> stringValue(body.get("url"));
                case "a2ui", "a2ui.render" -> stringValue(body.get("componentId"));
                case "memory", "memory.create" -> stringValue(body.get("documentId"));
                default -> "";
            };
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String stringValue(@Nullable Object value) {
        if (value == null) return "";
        String text = value.toString().strip();
        return text.isEmpty() ? "" : text;
    }

    private static String joinValues(String... parts) {
        StringBuilder buf = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (!buf.isEmpty()) buf.append(" · ");
            buf.append(p);
        }
        return buf.toString();
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

    /** 工具失败输出的最大截断长度。 */
    private static final int TOOL_FAILURE_OUTPUT_MAX_LENGTH = 200;

    @Nullable
    static String extractRecentToolFailure(List<ReactStep> steps) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i) instanceof ReactStep.Observation obs && !obs.success()
                    && !"llm".equals(obs.toolId())) {
                String toolDisplay = obs.toolName() != null ? obs.toolName() : obs.toolId();
                String output = obs.output() != null ? obs.output().strip() : "";
                if (output.length() > TOOL_FAILURE_OUTPUT_MAX_LENGTH) {
                    output = output.substring(0, TOOL_FAILURE_OUTPUT_MAX_LENGTH);
                }
                return toolDisplay + ": " + output;
            }
        }
        return null;
    }
}
