# MCP 协议支持

> 本文档从 [FEATURES.md](../FEATURES.md) 拆分而来，对应原文 §2.5 章节。

> ⚠️ 本文档描述的是目标功能设计，尚未实现。

LifePilot 将实现 MCP（Model Context Protocol）客户端，可以连接任意 MCP Server，接入日益丰富的 MCP 工具生态。

## 1. 为什么需要 MCP？

MCP 已成为 AI Agent 工具调用的事实标准（Anthropic / OpenAI / Google / Microsoft 均支持）。通过 MCP，LifePilot 可以连接：

- 文件系统操作工具
- 数据库查询工具
- 浏览器控制工具
- 各类 SaaS API（GitHub、Notion、Slack 等）
- 社区贡献的数千种 MCP Server

## 2. 配置方式

```yaml
lifepilot:
  mcp:
    servers:
      # 本地文件系统 MCP Server（stdio 传输）
      - name: filesystem
        command: npx
        args: ["-y", "@modelcontextprotocol/server-filesystem", "/home/user/documents"]
        transport: stdio

      # 远程 MCP Server（SSE 传输）
      - name: github
        url: http://localhost:3001/sse
        transport: sse

      # 数据库查询 MCP Server
      - name: sqlite-query
        command: npx
        args: ["-y", "@modelcontextprotocol/server-sqlite", "~/data/mydb.sqlite"]
        transport: stdio
```

## 3. 双向桥接

不仅将能连接外部 MCP Server，LifePilot 的内置 Skill 也将通过桥接层暴露为 MCP Tool，供其他 MCP 客户端调用：

```
外部 MCP Server → McpToolAdapter → 统一工具注册中心 ← SkillToMcpBridge ← 内置 Skill
```
