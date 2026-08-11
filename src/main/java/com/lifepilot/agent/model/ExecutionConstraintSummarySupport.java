package com.lifepilot.agent.model;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本轮执行约束摘要。
 *
 * <p>把用户在对话入口表达的轻量约束整理为结构化 payload，供同步响应、SSE DONE
 * 和前端上下文条展示。该摘要只解释执行边界，不参与模型提示词生成。</p>
 *
 * @author zsg
 * @since 2026-07-07
 */
public final class ExecutionConstraintSummarySupport {

    private static final Map<String, String> TOOL_LABELS = Map.ofEntries(
            Map.entry("web", "联网搜索"),
            Map.entry("web.search", "联网搜索"),
            Map.entry("web.fetch", "网页读取"),
            Map.entry("browser", "浏览器操作"),
            Map.entry("browser.click", "浏览器点击"),
            Map.entry("browser.input", "浏览器输入"),
            Map.entry("browser.navigate", "浏览器导航")
    );

    private ExecutionConstraintSummarySupport() {
    }

    /**
     * 从请求构建执行约束摘要。
     *
     * @param request Agent 请求
     * @return 结构化摘要；无约束时返回空 Map
     */
    public static Map<String, Object> from(AgentRequest request) {
        if (request == null) {
            return Map.of();
        }
        return build(request.disabledToolIds(), request.overrideKnowledgeBaseIds(), request.memoryContextMode());
    }

    /**
     * 从最终运行状态构建执行约束摘要。
     *
     * @param state Agent 状态
     * @return 结构化摘要；无约束时返回空 Map
     */
    public static Map<String, Object> from(ReactAgentState state) {
        if (state == null) {
            return Map.of();
        }
        return build(state.disabledToolIds(), state.overrideKnowledgeBaseIds(), state.memoryContextMode());
    }

    private static Map<String, Object> build(@Nullable List<String> disabledToolIds,
                                             @Nullable List<String> overrideKnowledgeBaseIds,
                                             @Nullable String memoryContextMode) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> disabledTools = disabledTools(disabledToolIds);
        if (!disabledTools.isEmpty()) {
            summary.put("disabledTools", disabledTools);
        }
        List<String> knowledgeBaseIds = normalizeStringList(overrideKnowledgeBaseIds);
        if (!knowledgeBaseIds.isEmpty()) {
            summary.put("knowledgeBaseIds", knowledgeBaseIds);
        }
        Map<String, Object> memoryMode = memoryContextMode(memoryContextMode);
        if (!memoryMode.isEmpty()) {
            summary.put("memoryContextMode", memoryMode);
        }
        return summary.isEmpty() ? Map.of() : Map.copyOf(summary);
    }

    private static List<Map<String, Object>> disabledTools(@Nullable List<String> disabledToolIds) {
        List<String> ids = normalizeStringList(disabledToolIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("label", TOOL_LABELS.getOrDefault(id, id));
            item.put("reason", "按本轮用户要求禁用");
            result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> memoryContextMode(@Nullable String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return Map.of();
        }
        String mode = rawMode.trim().toLowerCase();
        if (!"focused".equals(mode) && !"off".equals(mode)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        if ("focused".equals(mode)) {
            result.put("label", "本轮重点使用记忆");
            result.put("reason", "按本轮上下文选择参考偏好和事实");
        } else {
            result.put("label", "本轮不使用记忆");
            result.put("reason", "按本轮上下文选择跳过长期记忆");
        }
        return Map.copyOf(result);
    }

    private static List<String> normalizeStringList(@Nullable List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = value.trim();
            if (!result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }
}
