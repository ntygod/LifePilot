package com.lifepilot.memory.governance.server;

import com.lifepilot.mcp.protocol.JsonRpcMessage;
import com.lifepilot.memory.governance.config.MemoryGovernanceProperties;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Memory MCP Server 消息处理器 — 把 JSON-RPC 消息路由到对应的记忆操作。
 *
 * <p>实现 MCP 协议最小子集：
 * <ul>
 *   <li>{@code initialize}：返回 serverInfo + capabilities</li>
 *   <li>{@code tools/list}：返回 {@link MemoryMcpToolRegistry} 的工具清单</li>
 *   <li>{@code tools/call}：按 tool name 分发到 memory_search / memory_recall / memory_create</li>
 * </ul>
 * </p>
 *
 * <p>错误码遵循 JSON-RPC 2.0：-32600 invalid request / -32601 method not found /
 * -32602 invalid params / -32603 internal error。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class MemoryMcpHandler {

    private static final Logger log = LoggerFactory.getLogger(MemoryMcpHandler.class);

    private static final String MCP_PROTOCOL_VERSION = "2025-03-26";

    private final MemoryMcpToolRegistry toolRegistry;
    private final MemoryGovernanceProperties properties;
    private final HybridRetriever hybridRetriever;
    private final EpisodicMemory episodicMemory;
    private final SemanticMemory semanticMemory;

    public MemoryMcpHandler(MemoryMcpToolRegistry toolRegistry,
                             MemoryGovernanceProperties properties,
                             HybridRetriever hybridRetriever,
                             EpisodicMemory episodicMemory,
                             SemanticMemory semanticMemory) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.hybridRetriever = Objects.requireNonNull(hybridRetriever, "hybridRetriever");
        this.episodicMemory = Objects.requireNonNull(episodicMemory, "episodicMemory");
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory");
    }

    public JsonRpcMessage handle(@Nullable JsonRpcMessage request) {
        if (request == null) return error(null, -32600, "Invalid Request: null");
        if (request.method() == null || request.method().isBlank()) {
            return error(request.id(), -32600, "Invalid Request: missing method");
        }
        try {
            return switch (request.method()) {
                case "initialize" -> handleInitialize(request);
                case "tools/list" -> handleToolsList(request);
                case "tools/call" -> handleToolsCall(request);
                default -> error(request.id(), -32601, "Method not found: " + request.method());
            };
        } catch (Exception e) {
            log.warn("Memory MCP handler 异常: method={}, error={}", request.method(), e.getMessage(), e);
            return error(request.id(), -32603, "Internal error: " + e.getMessage());
        }
    }

    private JsonRpcMessage handleInitialize(JsonRpcMessage req) {
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", properties.getMcpServer().getServerName());
        serverInfo.put("version", properties.getMcpServer().getServerVersion());

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", MCP_PROTOCOL_VERSION);
        result.put("serverInfo", serverInfo);
        result.put("capabilities", capabilities);
        return JsonRpcMessage.response(nvl(req.id()), result);
    }

    private JsonRpcMessage handleToolsList(JsonRpcMessage req) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", toolRegistry.listTools());
        return JsonRpcMessage.response(nvl(req.id()), result);
    }

    @SuppressWarnings("unchecked")
    private JsonRpcMessage handleToolsCall(JsonRpcMessage req) {
        Object params = req.params();
        if (!(params instanceof Map<?, ?> pm)) {
            return error(req.id(), -32602, "Invalid params: expected object");
        }
        String name;
        try {
            name = requiredString(pm, "name");
        } catch (IllegalArgumentException e) {
            return error(req.id(), -32602, e.getMessage());
        }
        Map<String, Object> args;
        try {
            args = toolArguments(pm);
        } catch (IllegalArgumentException e) {
            return error(req.id(), -32602, e.getMessage());
        }
        return switch (name) {
            case MemoryMcpToolRegistry.TOOL_SEARCH -> executeSearch(req.id(), args);
            case MemoryMcpToolRegistry.TOOL_RECALL -> executeRecall(req.id(), args);
            case MemoryMcpToolRegistry.TOOL_CREATE -> executeCreate(req.id(), args);
            default -> error(req.id(), -32602, "Unknown tool: " + name);
        };
    }

    // ── 工具执行 ──

    private JsonRpcMessage executeSearch(Long id, Map<String, Object> args) {
        String query;
        try {
            query = requiredString(args, "query");
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        }
        int topK;
        try {
            topK = positiveIntArg(args, "topK", 10);
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        }

        List<RetrievalResult> results = hybridRetriever.retrieve(query, topK, RetrievalWeights.DEFAULT);
        List<Map<String, Object>> items = new ArrayList<>(results.size());
        for (var r : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("entityId", r.entityId());
            item.put("entityType", r.entityType());
            item.put("name", r.name());
            item.put("description", r.description() != null ? r.description() : "");
            item.put("score", r.fusedScore());
            items.add(item);
        }
        return JsonRpcMessage.response(nvl(id), toolContent(items.size() + " entities matched",
                Map.of("items", items, "count", items.size())));
    }

    private JsonRpcMessage executeRecall(Long id, Map<String, Object> args) {
        String query;
        try {
            query = requiredString(args, "query");
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        }
        int limit;
        try {
            limit = positiveIntArg(args, "limit", 5);
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        }

        List<ConversationRecord> conversations = episodicMemory.search(query);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, conversations.size()); i++) {
            var c = conversations.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.id());
            item.put("sessionId", c.sessionId());
            item.put("goal", c.goal());
            item.put("summary", c.summary() != null ? c.summary() : "");
            item.put("messageCount", c.messageCount());
            item.put("updatedAt", c.updatedAt().toString());
            items.add(item);
        }
        return JsonRpcMessage.response(nvl(id), toolContent(items.size() + " conversations matched",
                Map.of("items", items, "count", items.size())));
    }

    private JsonRpcMessage executeCreate(Long id, Map<String, Object> args) {
        String name;
        String typeStr;
        String description;
        try {
            name = requiredString(args, "name");
            typeStr = requiredString(args, "entityType");
            description = optionalString(args, "description");
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        }

        EntityType type;
        try {
            type = EntityType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            return error(id, -32602, "Invalid entityType: " + typeStr);
        }

        Instant now = Instant.now();
        MemoryEvidenceKind evidenceKind = MemoryEvidenceKind.USER_CONFIRMED;
        float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, 0.8f);
        MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);
        var incoming = new TemporalEntity(
                null, type, name, description,
                Map.of(), 1, true,
                now, null, null,
                0.8f, 0.5f, 0, null, now, now,
                LifecycleState.ACTIVE, null, null, Temporality.PERSISTENT,
                null, false, List.of(),
                evidenceKind, trustLevel, trustScore, 1, now
        );
        var created = semanticMemory.upsertWithConflictDetection(
                incoming,
                "mcp-memory-create",
                MemoryWriteContext.tool("mcp-memory-create"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", created.id());
        result.put("name", created.name());
        result.put("type", created.type().name());
        result.put("version", created.version());
        return JsonRpcMessage.response(nvl(id), toolContent("Created entity: " + created.name(), result));
    }

    // ── helpers ──

    private Map<String, Object> toolContent(String text, Object structured) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of(content));
        out.put("structuredContent", structured);
        return out;
    }

    private JsonRpcMessage error(@Nullable Long id, int code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        return new JsonRpcMessage("2.0", id, null, null, null, err);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toolArguments(Map<?, ?> params) {
        if (!params.containsKey("arguments")) {
            return Map.of();
        }
        Object value = params.get("arguments");
        if (!(value instanceof Map<?, ?> args)) {
            throw new IllegalArgumentException("arguments must be an object");
        }
        return (Map<String, Object>) args;
    }

    private static String requiredString(Map<?, ?> args, String key) {
        Object v = args.get(key);
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException("Missing param: " + key);
        }
        if (s.isBlank()) {
            throw new IllegalArgumentException("Missing param: " + key);
        }
        if (!s.equals(s.trim())) {
            throw new IllegalArgumentException(key + " must not contain leading or trailing whitespace");
        }
        return s;
    }

    @Nullable
    private static String optionalString(Map<?, ?> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        if (!s.equals(s.trim())) {
            throw new IllegalArgumentException(key + " must not contain leading or trailing whitespace");
        }
        return s;
    }

    private static int positiveIntArg(Map<String, Object> args, String key, int fallback) {
        Object v = args.get(key);
        if (v == null) return fallback;
        int value;
        if (v instanceof Number n) {
            double doubleValue = n.doubleValue();
            value = n.intValue();
            if (!Double.isFinite(doubleValue) || doubleValue != value) {
                throw new IllegalArgumentException(key + " must be a positive integer");
            }
        } else {
            throw new IllegalArgumentException(key + " must be a positive integer");
        }
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be a positive integer");
        }
        return value;
    }

    private static long nvl(@Nullable Long id) {
        return id == null ? 0L : id;
    }
}
