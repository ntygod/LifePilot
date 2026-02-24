package com.lifepilot.mcp.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.mcp.model.McpToolResult;
import com.lifepilot.mcp.protocol.JsonRpcMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * MCP Server HTTP 端点。
 *
 * <p>当 lifepilot.mcp.server.enabled=true 时启用。
 * 提供 Streamable HTTP 传输端点，供外部 MCP Client 连接。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
@RestController
@RequestMapping("/mcp")
@ConditionalOnProperty(name = "lifepilot.mcp.server.enabled", havingValue = "true")
public class McpServerEndpoint {

    private static final Logger log = LoggerFactory.getLogger(McpServerEndpoint.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillToMcpBridge bridge;

    public McpServerEndpoint(SkillToMcpBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * 处理 MCP JSON-RPC 请求。
     *
     * <p>支持的方法：initialize、tools/list、tools/call。</p>
     */
    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonRpcMessage handleRequest(@RequestBody JsonNode request) {
        String method = request.get("method").asText();
        long id = request.has("id") ? request.get("id").asLong() : 0;

        log.debug("MCP Server 收到请求: method={}, id={}", method, id);

        return switch (method) {
            case "initialize" -> {
                var result = bridge.handleInitialize();
                yield JsonRpcMessage.response(id, result);
            }
            case "tools/list" -> {
                var tools = bridge.listExportableTools();
                yield JsonRpcMessage.response(id, Map.of("tools", tools));
            }
            case "tools/call" -> {
                JsonNode params = request.get("params");
                String toolName = params.get("name").asText();
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = MAPPER.convertValue(
                        params.get("arguments"), Map.class);
                McpToolResult result = bridge.handleToolCall(toolName, arguments);
                yield JsonRpcMessage.response(id, result);
            }
            default -> {
                log.warn("MCP Server 不支持的方法: method={}", method);
                yield new JsonRpcMessage("2.0", id, null, null, null,
                        Map.of("code", -32601, "message", "方法不支持: " + method));
            }
        };
    }
}
