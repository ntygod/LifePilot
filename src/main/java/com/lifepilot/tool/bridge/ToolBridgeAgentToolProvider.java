package com.lifepilot.tool.bridge;

import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.pipeline.ToolExecutionPipeline;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
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

    public ToolBridgeAgentToolProvider(
            DynamicToolRegistry toolRegistry,
            ToolExecutionPipeline pipeline,
            ObjectMapper objectMapper,
            MetaProperties metaProperties) {
        this.toolRegistry = toolRegistry;
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
        this.maxToolOutputChars = metaProperties.getInfra().getMaxToolOutputChars();
    }

    @Override
    @Nullable
    public String resolveToolDisplayName(String toolId) {
        return toolRegistry.resolve(toolId)
                .map(ToolContract::name)
                .orElse(null);
    }

    @Override
    public List<ToolCallback> getToolCallbacks(ReactAgentState state, @Nullable String streamId) {
        List<ToolContract> tools = toolRegistry.getToolSnapshot();
        if (isWebConversation(state)) {
            tools = tools.stream()
                    .filter(tool -> !isUserPromptInteractionTool(tool.id()))
                    .toList();
        }
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
                .map(t -> toToolCallback(t, streamId, state))
                .toList();
    }

    /** Web 对话里改用自然语言挂起追问，不再暴露弹窗式输入工具。 */
    private boolean isWebConversation(ReactAgentState state) {
        return state.channel() != null && "web".equalsIgnoreCase(state.channel());
    }

    /** 这两类工具会触发前端交互控件，Web 普通对话模式下直接屏蔽。 */
    private boolean isUserPromptInteractionTool(String toolId) {
        return "builtin.interact.input".equals(toolId)
                || "builtin.interact.choose".equals(toolId);
    }

    /**
     * 将 ToolContract 转换为 Spring AI ToolCallback。
     *
     * @param tool 工具契约
     * @param streamId SSE 流标识（用于精确推送授权审批请求，可选）
     * @return Spring AI ToolCallback
     */
    private ToolCallback toToolCallback(ToolContract tool, @Nullable String streamId, ReactAgentState state) {
        // 构建请求级上下文，传递会话、预算和委托链元数据给工具执行器
        Map<String, Object> context = new LinkedHashMap<>();
        if (state.sessionId() != null) {
            context.put(ToolContextKeys.SESSION_ID, state.sessionId());
        }
        if (state.turnId() != null && !state.turnId().isBlank()) {
            context.put(ToolContextKeys.TURN_ID, state.turnId());
        }
        if (state.userId() != null && !state.userId().isBlank()) {
            context.put(ToolContextKeys.USER_ID, state.userId());
        }
        if (state.channel() != null && !state.channel().isBlank()) {
            context.put(ToolContextKeys.CHANNEL_TYPE, state.channel());
        }
        if (streamId != null && !streamId.isBlank()) {
            context.put(ToolContextKeys.STREAM_ID, streamId);
        }
        context.put(ToolContextKeys.CALLER_TRACE_ID, state.traceId());
        context.put(ToolContextKeys.CALLER_DEPTH, state.depth());
        context.put(ToolContextKeys.CALLER_BUDGET, state.budget());

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
                ToolResult result = pipeline.execute(tool.id(), params, traceId, null, streamId, Map.copyOf(context));
                String output = formatOutput(result);

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

    /**
     * 格式化输出结果为 JSON 字符串，超过全局上限时截断。
     *
     * <p>PARTIAL_SUCCESS 时同时输出 data 和 error，让 LLM 了解部分成功的上下文。</p>
     */
    private String formatOutput(ToolResult result) {
        String output;
        if (result.status() == com.lifepilot.tool.model.ToolResultStatus.PARTIAL_SUCCESS) {
            // 部分成功：同时输出 data 和 error
            output = "{\"data\":" + toJsonValue(result.data())
                    + ",\"error\":\"" + escapeJson(result.error())
                    + "\",\"status\":\"PARTIAL_SUCCESS\"}";
        } else if (result.ok()) {
            output = toJsonValue(result.data());
        } else {
            output = "{\"error\":\"" + escapeJson(result.error()) + "\",\"status\":\"ERROR\"}";
        }
        // 全局字符数上限截断
        // 注意：包含已知媒体字段（如 screenshot）的输出跳过截断，
        // 由 MediaDataExtractor 在 ReactAgentLoop 中提取媒体后再处理
        if (output.length() > maxToolOutputChars && !containsMediaField(result)) {
            int originalLength = output.length();
            output = output.substring(0, maxToolOutputChars)
                    + "...[输出已截断，原始长度: " + originalLength + " 字符，截断到: " + maxToolOutputChars + " 字符]";
        }
        return output;
    }

    /** 检查 ToolResult 是否包含已知媒体字段。 */
    private boolean containsMediaField(ToolResult result) {
        if (!result.ok() || result.data() == null) return false;
        // 与 MediaDataExtractor.KNOWN_MEDIA_FIELDS 保持一致
        return result.data().containsKey("screenshot");
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
