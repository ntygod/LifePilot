package com.lifepilot.memory.mcp.server;

import com.lifepilot.mcp.protocol.JsonRpcMessage;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final MemoryProperties properties;
    @Nullable private final HybridRetriever hybridRetriever;
    @Nullable private final EpisodicMemory episodicMemory;
    @Nullable private final SemanticMemory semanticMemory;

    public MemoryMcpHandler(MemoryMcpToolRegistry toolRegistry,
                             MemoryProperties properties,
                             @Nullable HybridRetriever hybridRetriever,
                             @Nullable EpisodicMemory episodicMemory,
                             @Nullable SemanticMemory semanticMemory) {
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.hybridRetriever = hybridRetriever;
        this.episodicMemory = episodicMemory;
        this.semanticMemory = semanticMemory;
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
        String name = pm.get("name") != null ? pm.get("name").toString() : null;
        Object argsObj = pm.get("arguments");
        Map<String, Object> args = argsObj instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        if (name == null) {
            return error(req.id(), -32602, "Invalid params: missing tool name");
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
        if (hybridRetriever == null) {
            return error(id, -32603, "HybridRetriever 不可用");
        }
        String query = strArg(args, "query");
        if (query == null || query.isBlank()) {
            return error(id, -32602, "Missing param: query");
        }
        int topK = intArg(args, "topK", 10);

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
        if (episodicMemory == null) {
            return error(id, -32603, "EpisodicMemory 不可用");
        }
        String query = strArg(args, "query");
        if (query == null || query.isBlank()) {
            return error(id, -32602, "Missing param: query");
        }
        int limit = intArg(args, "limit", 5);

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
        if (semanticMemory == null) {
            return error(id, -32603, "SemanticMemory 不可用");
        }
        String name = strArg(args, "name");
        String typeStr = strArg(args, "entityType");
        String description = strArg(args, "description");

        if (name == null || name.isBlank()) return error(id, -32602, "Missing param: name");
        if (typeStr == null || typeStr.isBlank()) return error(id, -32602, "Missing param: entityType");

        EntityType type;
        try {
            type = EntityType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return error(id, -32602, "Invalid entityType: " + typeStr);
        }

        Instant now = Instant.now();
        var incoming = new TemporalEntity(
                null, type, name, description,
                Map.of(), 1, true,
                now, null, null,
                0.8f, 0.5f, 0, null, now, now
        );
        var created = semanticMemory.upsertWithConflictDetection(incoming, null);

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

    @Nullable
    private static String strArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object v = args.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long nvl(@Nullable Long id) {
        return id == null ? 0L : id;
    }
}
