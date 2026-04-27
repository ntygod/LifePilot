package com.lifepilot.tool.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.pipeline.ToolExecutionPipeline;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.semantics.ToolScopeResolution;
import com.lifepilot.tool.tier1.Tier1Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

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
    private static final Set<String> IDEMPOTENCY_KEY_WHITELIST =
            Set.of("web.search", "web.fetch");

    /** Meta 工具 ID 集合 — 始终可见于 prompt，供 LLM 发现更多能力。 */
    private static final Set<String> META_TOOL_IDS =
            Set.of("tools.search", "tools.describe");

    private final DynamicToolRegistry toolRegistry;
    private final ToolExecutionPipeline pipeline;
    private final ObjectMapper objectMapper;
    private final int maxToolOutputChars;
    /** Tier 1 工具服务 — 聚合 pinned + Advisory APPROVED，提供当前 Tier 1 工具 ID 集合。 */
    private final Tier1Service tier1Service;
    private volatile Map<String, String> toolIdToModelName = Map.of();
    private volatile Map<String, String> modelNameToToolId = Map.of();

    public ToolBridgeAgentToolProvider(
            DynamicToolRegistry toolRegistry,
            ToolExecutionPipeline pipeline,
            ObjectMapper objectMapper,
            int maxToolOutputChars,
            Tier1Service tier1Service) {
        this.toolRegistry = toolRegistry;
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
        this.maxToolOutputChars = maxToolOutputChars;
        this.tier1Service = tier1Service;
    }

    @Override
    @Nullable
    public String resolveToolDisplayName(String toolId) {
        return resolveTool(toolId)
                .map(ToolContract::name)
                .orElse(null);
    }

    @Override
    public RiskLevel resolveToolRiskLevel(String toolId) {
        return resolveTool(toolId)
                .map(ToolContract::riskLevel)
                .orElse(RiskLevel.LOW);
    }

    @Override
    public ToolSchedulingHint resolveSchedulingHint(String toolId, String inputJson) {
        ToolContract tool = resolveTool(toolId).orElse(null);
        if (tool == null) {
            return ToolSchedulingHint.sequential();
        }

        ToolSchedulingMode mode = tool.schedulingMode();
        if (mode == ToolSchedulingMode.SEQUENTIAL) {
            return ToolSchedulingHint.sequential();
        }
        if (mode == ToolSchedulingMode.PARALLEL_SAFE) {
            return ToolSchedulingHint.parallelSafe();
        }

        var parsedInput = parseInputSafely(inputJson);
        if (!parsedInput.success()) {
            log.debug("RESOURCE_SERIALIZED 工具输入解析失败，回退串行: toolId={}", toolId);
            return ToolSchedulingHint.sequential();
        }

        List<String> resourceKeys = resolveResourceKeys(tool, parsedInput.envelope());
        if (resourceKeys.isEmpty()) {
            log.debug("RESOURCE_SERIALIZED 工具资源解析失败，回退串行: toolId={}", toolId);
            return ToolSchedulingHint.sequential();
        }
        return ToolSchedulingHint.resourceSerialized(resourceKeys);
    }

    @Override
    public String resolveCanonicalToolId(String toolId) {
        return modelNameToToolId.getOrDefault(toolId, toolId);
    }

    @Override
    public List<ToolCallback> getToolCallbacks(ReactAgentState state, @Nullable String streamId) {
        List<ToolContract> all = toolRegistry.getToolSnapshot();
        var allowed = state.allowedToolIds();

        List<ToolContract> tools;
        if (allowed != null && !allowed.isEmpty()) {
            // 多 Agent / 受限代理场景 — 严格按白名单过滤
            int totalCount = all.size();
            tools = all.stream()
                    .filter(t -> allowed.contains(t.id()))
                    .toList();
            log.debug("ToolCallback 过滤 (allowedToolIds): total={}, filtered={}", totalCount, tools.size());
        } else {
            // 统一分层 — Tier 1 ∪ activatedToolIds ∪ Meta 工具
            Set<String> tier1 = tier1Service.getCurrentTier1Ids();
            Set<String> activated = state.activatedToolIds() != null
                    ? state.activatedToolIds() : Set.of();
            Set<String> visible = new HashSet<>();
            visible.addAll(tier1);
            visible.addAll(activated);
            visible.addAll(META_TOOL_IDS);
            int totalCount = all.size();
            tools = all.stream().filter(t -> visible.contains(t.id())).toList();
            log.debug("ToolCallback 过滤 (统一分层): total={}, tier1={}, activated={}, meta={}, final={}",
                    totalCount, tier1.size(), activated.size(), META_TOOL_IDS.size(), tools.size());
        }

        refreshToolNameMappings(tools);
        return tools.stream()
                .map(t -> toToolCallback(t, streamId, state))
                .toList();
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
            context.put(ToolContextKeys.SOURCE_ID, state.channel());
        }
        context.put(ToolContextKeys.SOURCE_KIND, state.sourceKind().name());
        if (state.channelPlatform() != null && !state.channelPlatform().isBlank()) {
            context.put(ToolContextKeys.CHANNEL_TYPE, state.channelPlatform());
            context.put(ToolContextKeys.CHANNEL_PLATFORM, state.channelPlatform());
        } else if (state.channel() != null && !state.channel().isBlank()) {
            context.put(ToolContextKeys.CHANNEL_TYPE, state.channel());
        }
        if (state.channelInstanceId() != null && !state.channelInstanceId().isBlank()) {
            context.put(ToolContextKeys.CHANNEL_INSTANCE_ID, state.channelInstanceId());
        }
        if (streamId != null && !streamId.isBlank()) {
            context.put(ToolContextKeys.STREAM_ID, streamId);
        }
        context.put(ToolContextKeys.CALLER_TRACE_ID, state.traceId());
        context.put(ToolContextKeys.CALLER_DEPTH, state.depth());
        context.put(ToolContextKeys.CALLER_BUDGET, state.budget());
        // 把 state 直接放进 context，供 meta 工具（tools.search/describe/list）的 executor 读取
        context.put(ToolContextKeys.CALLER_STATE, state);

        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(resolveModelToolName(tool.id()))
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
                String idempotencyKey = buildIdempotencyKey(tool, params, context);
                ToolResult result = pipeline.execute(
                        tool.id(),
                        params,
                        traceId,
                        idempotencyKey,
                        streamId,
                        Map.copyOf(context)
                );
                String output = formatOutput(result);

                return output;
            }
        };
    }

    private void refreshToolNameMappings(List<ToolContract> tools) {
        Map<String, String> nextToolIdToModelName = new LinkedHashMap<>();
        Map<String, String> nextModelNameToToolId = new LinkedHashMap<>();
        for (ToolContract tool : tools) {
            String modelName = buildUniqueModelToolName(tool.id(), nextModelNameToToolId);
            nextToolIdToModelName.put(tool.id(), modelName);
            nextModelNameToToolId.put(modelName, tool.id());
        }
        this.toolIdToModelName = Map.copyOf(nextToolIdToModelName);
        this.modelNameToToolId = Map.copyOf(nextModelNameToToolId);
    }

    private String buildUniqueModelToolName(String toolId, Map<String, String> occupiedNames) {
        String baseName = sanitizeToolName(toolId);
        String candidate = baseName;
        if (occupiedNames.containsKey(candidate) && !Objects.equals(occupiedNames.get(candidate), toolId)) {
            String suffix = "_" + Integer.toHexString(toolId.hashCode()).replace('-', '0');
            candidate = baseName + suffix;
        }
        int attempt = 1;
        while (occupiedNames.containsKey(candidate) && !Objects.equals(occupiedNames.get(candidate), toolId)) {
            candidate = baseName + "_" + attempt;
            attempt++;
        }
        return candidate;
    }

    static String sanitizeToolName(String toolId) {
        String sanitized = toolId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return sanitized.isBlank() ? "tool" : sanitized;
    }

    private String resolveModelToolName(String toolId) {
        return toolIdToModelName.getOrDefault(toolId, sanitizeToolName(toolId));
    }

    @Nullable
    private String buildIdempotencyKey(ToolContract tool,
                                       Map<String, Object> params,
                                       Map<String, Object> context) {
        if (!tool.idempotent() || !IDEMPOTENCY_KEY_WHITELIST.contains(tool.id())) {
            return null;
        }
        Object traceIdValue = context.get(ToolContextKeys.CALLER_TRACE_ID);
        if (!(traceIdValue instanceof String callerTraceId) || callerTraceId.isBlank()) {
            return null;
        }
        String canonicalParams = toJsonValue(canonicalizeValue(params));
        return "trace:" + callerTraceId + ":" + tool.id() + ":" + sha256Hex(canonicalParams);
    }

    private Optional<ToolContract> resolveTool(String toolIdOrAlias) {
        String canonicalToolId = resolveCanonicalToolId(toolIdOrAlias);
        return toolRegistry.resolve(canonicalToolId);
    }

    /** 格式化输入 Schema 为 JSON 字符串。 */
    private String formatInputSchema(ToolContract tool) {
        Map<String, Object> schema = normalizeInputSchema(tool.inputSchema().toMap(), tool.id());
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

    private Map<String, Object> normalizeInputSchema(Map<String, Object> rawSchema, String toolId) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (rawSchema != null) {
            schema.putAll(rawSchema);
        }

        Object type = schema.get("type");
        if (!"object".equals(type)) {
            if (type != null) {
                log.warn("工具输入 Schema 根类型不是 object，已强制修正: toolId={}, originalType={}", toolId, type);
            }
            schema.put("type", "object");
        }

        Object properties = schema.get("properties");
        if (!(properties instanceof Map<?, ?>)) {
            schema.put("properties", Map.of());
        }

        return Map.copyOf(schema);
    }

    /** 解析 Spring AI 传入的 JSON 字符串为参数 Map。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseInput(String toolInput) {
        return parseInputSafely(toolInput).envelope().parameters();
    }

    @SuppressWarnings("unchecked")
    private ParsedToolInput parseInputSafely(String toolInput) {
        if (toolInput == null || toolInput.isBlank() || "{}".equals(toolInput.trim())) {
            return new ParsedToolInput(true, new ToolInputEnvelope(Map.of()));
        }
        try {
            return new ParsedToolInput(true, new ToolInputEnvelope(objectMapper.readValue(toolInput, Map.class)));
        } catch (Exception e) {
            log.warn("工具输入解析失败: input={}", toolInput, e);
            return new ParsedToolInput(false, new ToolInputEnvelope(Map.of()));
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
        } else if (result.data() != null && !result.data().isEmpty()) {
            // 失败但 data 非空（如 code.execute / shell.exec 在 exitCode!=0 时带 stdout/stderr），
            // 必须把 data 也透给 LLM，否则 AI 只看到"代码执行失败 exitCode=1"无法 debug
            output = "{\"data\":" + toJsonValue(result.data())
                    + ",\"error\":\"" + escapeJson(result.error())
                    + "\",\"status\":\"ERROR\"}";
        } else {
            output = "{\"error\":\"" + escapeJson(result.error()) + "\",\"status\":\"ERROR\"}";
        }
        // 全局字符数上限截断
        // 注意：包含已知媒体字段（如 screenshot）的输出跳过截断，
        // 由 MediaDataExtractor 在 ReactAgentLoop 中提取媒体后再处理
        if (output.length() > maxToolOutputChars && !containsMediaField(result)) {
            int originalLength = output.length();
            output = truncateJsonSafe(output, maxToolOutputChars, originalLength);
        }
        return output;
    }

    /**
     * JSON 安全截断 — 截断后保证 JSON 结构可被解析器容忍。
     *
     * <p>在截断位置后追加闭合标记和截断提示，避免 MediaDataExtractor
     * 等下游解析器遇到不完整 JSON 抛出 {@code Unexpected end-of-input}。</p>
     */
    private static String truncateJsonSafe(String json, int maxChars, int originalLength) {
        String truncated = json.substring(0, maxChars);
        // 如果截断点在字符串内部（奇数个未转义引号），先闭合字符串
        if (isInsideJsonString(truncated)) {
            truncated += "\"";
        }
        String suffix = "...[已截断: %d→%d 字符]".formatted(originalLength, maxChars);
        // 追加最小闭合：让顶层 JSON 对象/数组能被解析
        if (json.charAt(0) == '{') {
            return truncated + ",\"_truncated\":\"" + suffix + "\"}";
        } else if (json.charAt(0) == '[') {
            return truncated + "]";
        }
        return truncated + suffix;
    }

    /**
     * 粗略判断截断点是否落在 JSON 字符串值内部。
     * 从末尾向前找最近的未转义双引号，计算从该引号到末尾的引号数量奇偶性。
     */
    private static boolean isInsideJsonString(String s) {
        int quotes = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            if (s.charAt(i) == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                quotes++;
            }
        }
        return quotes % 2 != 0;
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

    private Object canonicalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var ordered = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) {
                ordered.put(String.valueOf(entry.getKey()), canonicalizeValue(entry.getValue()));
            }
            return ordered;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::canonicalizeValue)
                    .toList();
        }
        return value;
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

    @Nullable
    private List<String> resolveResourceKeys(ToolContract tool,
                                             ToolInputEnvelope input) {
        ToolInput toolInput = new ToolInput(
                tool.id(),
                input.parameters(),
                tool.inputSchema(),
                null,
                null
        );
        ToolScopeResolution resolution = tool.executionSemantics().scopeResolver().resolve(toolInput);
        if (resolution.normalizedResources().isEmpty()) {
            return List.of();
        }
        return resolution.normalizedResources();
    }

    private String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception e) {
            throw new IllegalStateException("生成幂等键哈希失败", e);
        }
    }

    private record ParsedToolInput(boolean success, ToolInputEnvelope envelope) {}

    private record ToolInputEnvelope(Map<String, Object> parameters) {
        private ToolInputEnvelope {
            parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        }
    }
}
