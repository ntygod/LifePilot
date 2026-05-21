package com.lifepilot.memory.governance.server;

import com.lifepilot.mcp.protocol.JsonRpcMessage;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Memory MCP Server REST 端点 — 接收 JSON-RPC 请求并委派给 {@link MemoryMcpHandler}。
 *
 * <p>通过 {@code lifepilot.memory.mcp-server.enabled=true} 开启。</p>
 *
 * <p>客户端接入示例（Claude Desktop / Cursor 等）：
 * <pre>
 * {
 *   "mcpServers": {
 *     "zhiwei-memory": {
 *       "url": "http://localhost:8080/api/mcp/memory"
 *     }
 *   }
 * }
 * </pre>
 * </p>
 *
 * @author zsg
 * @since 2026-05-09
 */
@RestController
@RequestMapping("/api/mcp")
@ConditionalOnProperty(name = "lifepilot.memory.mcp-server.enabled", havingValue = "true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(MemoryMcpHandler.class)
public class MemoryMcpServerController {

    private static final Logger log = LoggerFactory.getLogger(MemoryMcpServerController.class);

    private final MemoryMcpHandler handler;
    private final String apiKey;

    public MemoryMcpServerController(MemoryMcpHandler handler,
                                     @org.springframework.beans.factory.annotation.Value("${lifepilot.memory.mcp-server.api-key:}") String apiKey) {
        this.handler = handler;
        this.apiKey = apiKey;
        log.info("Memory MCP Server 已启用, endpoint=POST /api/mcp/memory, auth={}",
                apiKey.isBlank() ? "none (localhost-only)" : "bearer-token");
    }

    @PostMapping(value = "/memory",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonRpcMessage> handle(@RequestBody JsonRpcMessage request,
                                                  HttpServletRequest httpRequest) {
        // 认证：配置了 api-key 时验证 Bearer token；未配置时仅限 localhost
        if (!apiKey.isBlank()) {
            String authHeader = httpRequest.getHeader("Authorization");
            if (authHeader == null || !authHeader.equals("Bearer " + apiKey)) {
                log.warn("Memory MCP 认证失败: remoteAddr={}", httpRequest.getRemoteAddr());
                return ResponseEntity.status(401).build();
            }
        } else if (!isLocalhost(httpRequest)) {
            log.warn("Memory MCP 拒绝非本地请求: remoteAddr={}", httpRequest.getRemoteAddr());
            return ResponseEntity.status(403).build();
        }
        if (log.isDebugEnabled()) {
            log.debug("Memory MCP 请求: method={}, id={}", request.method(), request.id());
        }
        return ResponseEntity.ok(handler.handle(request));
    }

    private static boolean isLocalhost(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return "127.0.0.1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr) || "::1".equals(addr);
    }
}
