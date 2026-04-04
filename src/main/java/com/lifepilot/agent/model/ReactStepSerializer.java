package com.lifepilot.agent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReactStep 序列化工具 — 将 ReAct 步骤序列转为 JSON 友好的 Map 列表。
 *
 * <p>用于 SSE DONE 事件的 reactSteps 字段和数据库持久化。
 * 对长文本字段执行截断，控制单条消息的 JSON 体积不超过 10KB。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public final class ReactStepSerializer {

    // 截断阈值 — 纯技术常量（协议层固定值）
    static final int THOUGHT_MAX_LENGTH = 500;
    static final int INPUT_MAX_LENGTH = 200;
    static final int OUTPUT_MAX_LENGTH = 300;

    /** 产出文件的工具 ID 集合 — 成功时 output 中含 "path" 字段。 */
    private static final Set<String> FILE_PRODUCING_TOOL_IDS = Set.of(
            "file.write", "file.edit", "file.manage"
    );

    /** 从 JSON 输出中快速提取 "path" 值的正则（避免引入完整 JSON 解析依赖）。 */
    private static final Pattern PATH_PATTERN = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");

    private ReactStepSerializer() {}

    /**
     * 将 ReactStep 列表序列化为 JSON 友好的 Map 列表。
     *
     * @param steps ReAct 步骤序列
     * @return 序列化后的 Map 列表，每个 Map 包含 type、index 和对应字段
     */
    public static List<Map<String, Object>> serialize(List<ReactStep> steps) {
        if (steps == null || steps.isEmpty()) return List.of();
        var result = new ArrayList<Map<String, Object>>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            result.add(serializeStep(steps.get(i), i));
        }
        return List.copyOf(result);
    }

    /**
     * 将单个 ReactStep 序列化为 Map。
     *
     * @param step  ReAct 步骤
     * @param index 步骤索引
     * @return 序列化后的 Map
     */
    static Map<String, Object> serializeStep(ReactStep step, int index) {
        return switch (step) {
            case ReactStep.Progress(var content) -> Map.of(
                    "type", "PROGRESS",
                    "index", index,
                    "content", truncate(content, THOUGHT_MAX_LENGTH)
            );
            case ReactStep.Thought(var content) -> Map.of(
                    "type", "THOUGHT",
                    "index", index,
                    "content", truncate(content, THOUGHT_MAX_LENGTH)
            );
            case ReactStep.ToolCall toolCall -> {
                var map = new LinkedHashMap<String, Object>();
                map.put("type", "TOOL_CALL");
                map.put("index", index);
                map.put("toolId", toolCall.toolId());
                if (toolCall.toolName() != null) map.put("toolName", toolCall.toolName());
                if (toolCall.callId() != null) map.put("callId", toolCall.callId());
                map.put("inputSummary", truncate(toolCall.inputJson(), INPUT_MAX_LENGTH));
                map.put("latencyMs", toolCall.latencyMs());
                yield Map.copyOf(map);
            }
            case ReactStep.Observation observation -> {
                var map = new LinkedHashMap<String, Object>();
                map.put("type", "OBSERVATION");
                map.put("index", index);
                map.put("toolId", observation.toolId());
                if (observation.toolName() != null) map.put("toolName", observation.toolName());
                if (observation.callId() != null) map.put("callId", observation.callId());
                map.put("success", observation.success());
                map.put("outputSummary", truncate(observation.output(), OUTPUT_MAX_LENGTH));
                map.put("tokensUsed", observation.tokensUsed());
                // 文件工具成功时，提取生成文件路径（不受截断影响）
                String filePath = extractGeneratedFilePath(observation);
                if (filePath != null) {
                    map.put("generatedFilePath", filePath);
                }
                yield Map.copyOf(map);
            }
            case ReactStep.Answer(var content) -> Map.of(
                    "type", "ANSWER",
                    "index", index,
                    "content", truncate(content, THOUGHT_MAX_LENGTH)
            );
            case ReactStep.Suspend(var reason, var suspendedAt, var stepIdx) -> Map.of(
                    "type", "SUSPEND",
                    "index", index,
                    "reason", reason.toString(),
                    "suspendedAt", suspendedAt.toString()
            );
            case ReactStep.Resume(var payload, var resumedAt, var duration) -> Map.of(
                    "type", "RESUME",
                    "index", index,
                    "resumedAt", resumedAt.toString(),
                    "suspendDurationMs", duration.toMillis()
            );
            case ReactStep.Reflect(var content, var trigger) -> Map.of(
                    "type", "REFLECT",
                    "index", index,
                    "content", truncate(content, THOUGHT_MAX_LENGTH),
                    "trigger", trigger.name()
            );
        };
    }

    /**
     * 从文件工具的成功输出中提取生成文件的绝对路径。
     *
     * @param observation 工具观察步骤
     * @return 文件路径，非文件工具或提取失败时返回 null
     */
    @org.springframework.lang.Nullable
    static String extractGeneratedFilePath(ReactStep.Observation observation) {
        if (!observation.success()) return null;
        if (!FILE_PRODUCING_TOOL_IDS.contains(observation.toolId())) return null;
        String output = observation.output();
        if (output == null || output.isBlank()) return null;
        Matcher matcher = PATH_PATTERN.matcher(output);
        if (matcher.find()) {
            // JSON 中反斜杠被转义为 \\，还原为实际路径
            return matcher.group(1).replace("\\\\", "\\");
        }
        return null;
    }

    /**
     * 截断字符串到指定最大长度，超出部分用 "..." 替代。
     *
     * @param text      原始文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 将 ReactStep 列表序列化为 JSON 字符串（用于数据库持久化）。
     *
     * @param steps        ReAct 步骤序列
     * @param objectMapper Jackson ObjectMapper
     * @return JSON 字符串，步骤为空时返回 null
     */
    @org.springframework.lang.Nullable
    public static String serializeToJson(List<ReactStep> steps, ObjectMapper objectMapper) {
        var maps = serialize(steps);
        if (maps.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(maps);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
