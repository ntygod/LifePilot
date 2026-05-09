# Memory MCP Server

> Spec: `.kiro/specs/memory-mcp-server/`
> 关联 Gap: #[[file:docs/planned/memory-and-proactive-evolution-gaps.md]] §1 M-P2-8
> MCP 协议参考: https://modelcontextprotocol.io/specification/2025-03-26

## 1. 定位

把知微的记忆能力暴露为 MCP Server，供外部 Agent（Claude Desktop / Cursor / ChatGPT 桌面版等）通过 MCP 协议读写。

**产品前提**：本地个人助手可选能力，默认关闭。开启后仅在本地回环地址提供 HTTP 服务。

## 2. 最小子集

本 spec 只实现 MCP 协议的最小可用子集：

- **单一 HTTP POST 端点** `/api/mcp/memory`
- **3 个 JSON-RPC 方法**：`initialize` / `tools/list` / `tools/call`
- **3 个工具**：`memory_search` / `memory_recall` / `memory_create`
- **不实现**：SSE / Streamable HTTP / Stdio 传输、prompts / resources / logging 等扩展

## 3. 组件

```
外部 Agent (MCP Client)
       │
       │ HTTP POST /api/mcp/memory
       │ JSON-RPC 2.0 Body
       ▼
┌──────────────────────────────────────┐
│ MemoryMcpServerController (@RestController)
│   @ConditionalOnProperty(mcp-server.enabled)
└─────────────────┬────────────────────┘
                  │
                  ▼
┌──────────────────────────────────────┐
│ MemoryMcpHandler                      │
│   switch method:                      │
│     initialize → serverInfo + caps    │
│     tools/list → MemoryMcpToolRegistry│
│     tools/call → 按 name 分发         │
└─────────────────┬────────────────────┘
                  │
        ┌─────────┼─────────┐
        │         │         │
        ▼         ▼         ▼
   memory_search memory_recall memory_create
        │         │         │
HybridRetriever EpisodicMemory SemanticMemory
```

## 4. 工具定义

### memory_search
- 描述：搜索用户长期语义记忆（向量 + FTS + 图融合）
- 输入：`{query: string, topK?: number (default 10)}`
- 输出：`{items: [{entityId, entityType, name, description, score}], count}`

### memory_recall
- 描述：回忆历史对话
- 输入：`{query: string, limit?: number (default 5)}`
- 输出：`{items: [{id, sessionId, goal, summary, messageCount, updatedAt}], count}`

### memory_create
- 描述：创建新的语义记忆实体
- 输入：`{name: string, entityType: string, description?: string}`
- 输出：`{id, name, type, version}`

## 5. 错误码

| 情况 | code |
|---|---|
| 请求为 null 或缺 method | -32600 Invalid Request |
| 未知 method | -32601 Method not found |
| 参数错误 / 未知工具 / 缺必填字段 | -32602 Invalid params |
| 内部依赖不可用或异常 | -32603 Internal error |

## 6. 配置

```yaml
lifepilot:
  memory:
    mcp-server:
      enabled: false  # 默认关闭
      server-name: "zhiwei-memory"
      server-version: "1.0.0"
```

## 7. 客户端接入示例

Claude Desktop `claude_desktop_config.json`：

```json
{
  "mcpServers": {
    "zhiwei-memory": {
      "url": "http://localhost:8080/api/mcp/memory"
    }
  }
}
```

## 8. 测试

- `MemoryMcpHandler_单元测试`：11 场景（initialize / tools/list / 3 个 tools/call 路径 / 错误码 / 未知 method / 缺参数）
- `MemoryMcpToolRegistry_单元测试`：4 场景（数量 / 字段 / JSON Schema / name 命名）

## 9. 风险

- **认证缺失**：本 spec 不实现 token / mTLS；仅供本地回环使用。生产环境开启前需加认证中间件
- **协议版本**：固定 `2025-03-26`，未来协议演进需人工升级

## 10. 未来扩展

- SSE / Streamable HTTP 传输
- 认证机制
- 暴露更多工具（update / delete / relations）
- 暴露 resources（实体树浏览）
