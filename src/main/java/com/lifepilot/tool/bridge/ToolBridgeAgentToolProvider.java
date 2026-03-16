package com.lifepilot.tool.bridge;

import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.pipeline.ToolExecutionPipeline;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceRecorder;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工具桥接层 — 将 ToolContract 转换为 Spring AI ToolCallback。
 *
 * <p>实现 AgentToolProvider 接口，覆盖 agent 模块的空实现兜底 Bean。
 * 通过 ToolExecutionPipeline 执行工具调用，确保护栏、幂等、重试等机制生效。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public class ToolBridgeAgentToolProvider implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(ToolBridgeAgentToolProvider.class);

    private final DynamicToolRegistry toolRegistry;
    private final ToolExecutionPipeline pipeline;
    private final ObjectMapper objectMapper;
    private final int maxToolOutputChars;
    @Nullable
    private final TraceRecorder traceRecorder;

    public ToolBridgeAgentToolProvider(
            DynamicToolRegistry toolRegistry,
            ToolExecutionPipeline pipeline,
            ObjectMapper objectMapper,
            MetaProperties metaProperties,
            @Nullable TraceRecorder traceRecorder) {
        this.toolRegistry = toolRegistry;
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
        this.maxToolOutputChars = metaProperties.getInfra().getMaxToolOutputChars();
        this.traceRecorder = traceRecorder;
    }

    @Override
    public List<ToolCallback> getToolCallbacks(ReactAgentState state, @Nullable String streamId) {
        List<ToolContract> tools = toolRegistry.getToolSnapshot();
        var allowedToolIds = state.allowedToolIds();
        if (allowedToolIds != null && !allowedToolIds.isEmpty()) {
            int totalCount = tools.size();
            tools = tools.stream()
                    .filter(t -> allowedToolIds.contains(t.id())
                                 || t.tags().contains("infrastructure"))
                    .toList();
            log.debug("生成 ToolCallback: total={}, filtered={}", totalCount, tools.size());
        } else {
            log.debug("生成 ToolCallback: count={}", tools.size());
        }
        return tools.stream()
                .map(t -> toToolCallback(t, streamId))
                .toList();
    }

    /**
     * 将 ToolContract 转换为 Spring AI ToolCallback。
     *
     * @param tool 工具契约
     * @param streamId SSE 流标识（用于精确推送确认请求，可选）
     * @return Spring AI ToolCallback
     */
    private ToolCallback toToolCallback(ToolContract tool, @Nullable String streamId) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(tool.id())
                .description(tool.description())
                .inputSchema(formatInputSchema(tool))
                .build();

        return new ToolCallback() {
            @Override
            @NonNull
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            @NonNull
            public String call(@NonNull String toolInput) {
                Map<String, Object> params = parseInput(toolInput);
                String traceId = UUID.randomUUID().toString();
                Instant start = Instant.now();
                ToolResult result = pipeline.execute(tool.id(), params, traceId, null, streamId);
                String output = formatOutput(result);

                // Token 消耗估算（字符数 / 3）并填充到 meta
                int estimatedTokens = output.length() / 3;
                ToolResult finalResult = result.toBuilder()
                        .meta(result.meta().toBuilder().tokensUsed(estimatedTokens).build())
                        .build();

                // 记录 ToolCallStep 到当前 TraceContext（Spring AI function calling 路径）
                if (traceRecorder != null) {
                    traceRecorder.currentContext().ifPresent(ctx -> {
                        try {
                            Duration duration = Duration.between(start, Instant.now());
                            String outputJson = output;
                            if (outputJson != null && outputJson.length() > 2000) {
                                outputJson = outputJson.substring(0, 2000) + "...[truncated]";
                            }
                            var step = new ToolCallStep(
                                    ctx.steps().size(),
                                    start,
                                    duration,
                                    tool.id(),
                                    tool.id(),
                                    toolInput,
                                    outputJson,
                                    finalResult.ok(),
                                    finalResult.ok() ? null : finalResult.error(),
                                    RiskLevel.LOW
                            );
                            traceRecorder.recordStep(ctx, step);
                            log.debug("Function calling 工具调用已记录到 Trace: toolId={}, success={}, duration={}ms",
                                      tool.id(), finalResult.ok(), duration.toMillis());
                        } catch (Exception e) {
                            log.warn("Function calling 工具调用 Trace 记录失败: toolId={}, error={}", tool.id(), e.getMessage());
                        }
                    });
                }
                return output;
            }
        };
    }

    /** 格式化输入 Schema 为 JSON 字符串。 */
    private String formatInputSchema(ToolContract tool) {
        Map<String, Object> schema = tool.inputSchema().toMap();
        if (schema.isEmpty()) {
            return "{}";
        }
        // 简单序列化，避免引入额外依赖
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : schema.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            sb.append(toJsonValue(entry.getValue()));
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /** 解析 Spring AI 传入的 JSON 字符串为参数 Map。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseInput(String toolInput) {
        if (toolInput == null || toolInput.isBlank() || "{}".equals(toolInput.trim())) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(toolInput, Map.class);
        } catch (Exception e) {
            log.warn("工具输入解析失败: input={}", toolInput, e);
            return Map.of();
        }
    }

    /** 格式化输出结果为 JSON 字符串，超过全局上限时截断。 */
    private String formatOutput(ToolResult result) {
        String output;
        if (result.ok()) {
            output = toJsonValue(result.data());
        } else {
            output = "{\"error\":\"" + escapeJson(result.error()) + "\"}";
        }
        // 全局字符数上限截断
        if (output.length() > maxToolOutputChars) {
            int originalLength = output.length();
            output = output.substring(0, maxToolOutputChars)
                    + "\n...[输出已截断，原始长度: " + originalLength + " 字符，截断到: " + maxToolOutputChars + " 字符]";
        }
        return output;
    }

    /** 将对象转换为 JSON 值字符串。 */
    private String toJsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var sb = new StringBuilder("{");
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(entry.getKey()).append("\":");
                sb.append(toJsonValue(entry.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        if (value instanceof List<?> list) {
            var sb = new StringBuilder("[");
            boolean first = true;
            for (var item : list) {
                if (!first) sb.append(",");
                sb.append(toJsonValue(item));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    /** 转义 JSON 特殊字符。 */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
