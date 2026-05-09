package com.lifepilot.memory.mcp.server;

import com.lifepilot.mcp.protocol.JsonRpcMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
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
public class MemoryMcpServerController {

    private static final Logger log = LoggerFactory.getLogger(MemoryMcpServerController.class);

    private final MemoryMcpHandler handler;

    public MemoryMcpServerController(MemoryMcpHandler handler) {
        this.handler = handler;
        log.info("Memory MCP Server 已启用, endpoint=POST /api/mcp/memory");
    }

    @PostMapping(value = "/memory",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonRpcMessage handle(@RequestBody JsonRpcMessage request) {
        if (log.isDebugEnabled()) {
            log.debug("Memory MCP 请求: method={}, id={}", request.method(), request.id());
        }
        return handler.handle(request);
    }
}
