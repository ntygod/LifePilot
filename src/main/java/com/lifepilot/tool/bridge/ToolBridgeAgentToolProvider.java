package com.lifepilot.tool.bridge;

import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.pipeline.ToolExecutionPipeline;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import org.springframework.lang.NonNull;

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

    public ToolBridgeAgentToolProvider(
            DynamicToolRegistry toolRegistry,
            ToolExecutionPipeline pipeline) {
        this.toolRegistry = toolRegistry;
        this.pipeline = pipeline;
    }

    @Override
    public List<ToolCallback> getToolCallbacks(AgentState state) {
        List<ToolContract> tools = toolRegistry.getToolSnapshot();
        var allowedToolIds = state.allowedToolIds();
        if (allowedToolIds != null && !allowedToolIds.isEmpty()) {
            int totalCount = tools.size();
            tools = tools.stream()
                    .filter(t -> allowedToolIds.contains(t.id()))
                    .toList();
            log.debug("生成 ToolCallback: total={}, filtered={}", totalCount, tools.size());
        } else {
            log.debug("生成 ToolCallback: count={}", tools.size());
        }
        return tools.stream()
                .map(this::toToolCallback)
                .toList();
    }

    /**
     * 将 ToolContract 转换为 Spring AI ToolCallback。
     *
     * @param tool 工具契约
     * @return Spring AI ToolCallback
     */
    private ToolCallback toToolCallback(ToolContract tool) {
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
                ToolResult result = pipeline.execute(tool.id(), params, traceId, null);
                return formatOutput(result);
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
        // 简单 JSON 解析：依赖 Spring 上下文中的 ObjectMapper 会更好，
        // 但为了减少依赖，这里使用简单的 key-value 提取
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(toolInput, Map.class);
        } catch (Exception e) {
            log.warn("工具输入解析失败: input={}", toolInput, e);
            return Map.of();
        }
    }

    /** 格式化输出结果为 JSON 字符串。 */
    private String formatOutput(ToolResult result) {
        if (result.ok()) {
            return toJsonValue(result.data());
        }
        return "{\"error\":\"" + escapeJson(result.error()) + "\"}";
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
