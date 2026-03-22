package com.lifepilot.mcp.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.lifepilot.mcp.config.McpConfigProperties;
import com.lifepilot.mcp.model.McpToolResult;
import com.lifepilot.mcp.protocol.JsonRpcMessage;
import com.lifepilot.mcp.protocol.McpJsonSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MCP Server HTTP 端点。
 *
 * <p>当 lifepilot.mcp.server.enabled=true 时启用。
 * 提供 Streamable HTTP 传输端点，供外部 MCP Client 连接。</p>
 *
 * <p>安全防护：
 * <ul>
 *   <li>当配置了 api-key 时，要求请求携带 Bearer Token 或 X-API-Key 头</li>
 *   <li>对所有输入进行 null 检查，防止 NPE</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-02-24
 */
@RestController
@RequestMapping("/mcp")
@ConditionalOnProperty(name = "lifepilot.mcp.server.enabled", havingValue = "true")
public class McpServerEndpoint {

    private static final Logger log = LoggerFactory.getLogger(McpServerEndpoint.class);

    private final SkillToMcpBridge bridge;
    private final String apiKey;

    public McpServerEndpoint(SkillToMcpBridge bridge, McpConfigProperties properties) {
        this.bridge = bridge;
        this.apiKey = properties.getServer().getApiKey();
    }

    /**
     * 处理 MCP JSON-RPC 请求。
     *
     * <p>支持的方法：initialize、tools/list、tools/call。</p>
     */
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonRpcMessage> handleRequest(
            @RequestBody JsonNode request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-API-Key", required = false) String xApiKey) {

        // API Key 认证
        if (apiKey != null && !apiKey.isBlank()) {
            if (!authenticateRequest(authorization, xApiKey)) {
                log.warn("MCP Server 认证失败: 无效的 API Key");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        }

        // 输入校验：method 字段必须存在
        if (!request.has("method") || request.get("method").isNull()) {
            long id = request.has("id") ? request.get("id").asLong() : 0;
            return ResponseEntity.ok(new JsonRpcMessage("2.0", id, null, null, null,
                    Map.of("code", -32600, "message", "缺少 method 字段")));
        }

        String method = request.get("method").asText();
        long id = request.has("id") ? request.get("id").asLong() : 0;

        log.debug("MCP Server 收到请求: method={}, id={}", method, id);

        var response = switch (method) {
            case "initialize" -> {
                var result = bridge.handleInitialize();
                yield JsonRpcMessage.response(id, result);
            }
            case "tools/list" -> {
                var tools = bridge.listExportableTools();
                yield JsonRpcMessage.response(id, Map.of("tools", tools));
            }
            case "tools/call" -> handleToolCall(request, id);
            default -> {
                log.warn("MCP Server 不支持的方法: method={}", method);
                yield new JsonRpcMessage("2.0", id, null, null, null,
                        Map.of("code", -32601, "message", "方法不支持: " + method));
            }
        };

        return ResponseEntity.ok(response);
    }

    /** 处理 tools/call 请求，包含参数校验。 */
    private JsonRpcMessage handleToolCall(JsonNode request, long id) {
        JsonNode params = request.get("params");
        if (params == null || !params.has("name")) {
            return new JsonRpcMessage("2.0", id, null, null, null,
                    Map.of("code", -32602, "message", "tools/call 缺少 params.name 参数"));
        }

        String toolName = params.get("name").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = params.has("arguments")
                ? McpJsonSupport.MAPPER.convertValue(params.get("arguments"), Map.class)
                : Map.of();

        McpToolResult result = bridge.handleToolCall(toolName, arguments);
        return JsonRpcMessage.response(id, result);
    }

    /**
     * 验证请求的 API Key。
     *
     * <p>支持两种方式：
     * <ul>
     *   <li>Authorization: Bearer {api-key}</li>
     *   <li>X-API-Key: {api-key}</li>
     * </ul></p>
     */
    private boolean authenticateRequest(String authorization, String xApiKey) {
        if (xApiKey != null && apiKey.equals(xApiKey)) {
            return true;
        }
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7).trim();
            return apiKey.equals(token);
        }
        return false;
    }
}
