# Memory MCP Server（面向开发者）

> 架构：#[[file:docs/architecture/memory-mcp-server.md]]

## 启用

```yaml
lifepilot:
  memory:
    mcp-server:
      enabled: true
      server-name: "zhiwei-memory"
      server-version: "1.0.0"
```

启动后 POST `/api/mcp/memory` 即可接收 JSON-RPC 请求。

## 接入 Claude Desktop

在 `claude_desktop_config.json` 中添加：

```json
{
  "mcpServers": {
    "zhiwei-memory": {
      "url": "http://localhost:8080/api/mcp/memory"
    }
  }
}
```

## 手动测试

```bash
# initialize
curl -X POST http://localhost:8080/api/mcp/memory \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'

# tools/list
curl -X POST http://localhost:8080/api/mcp/memory \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

# memory_search
curl -X POST http://localhost:8080/api/mcp/memory \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"memory_search","arguments":{"query":"Rust","topK":5}}}'
```

## 安全提示

- 本 spec **未实现认证**，仅供本地回环使用
- 生产或公网暴露前需接入 token / mTLS
- 建议用反向代理（Nginx）限制访问来源

## 回退

```yaml
lifepilot.memory.mcp-server.enabled: false
```

关闭后 Controller / Handler / ToolRegistry 全部不装配。
