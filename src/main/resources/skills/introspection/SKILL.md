---
id: introspection
name: "系统自省"
description: "查看知微运行时状态：Skill 数量、工具分布、工作流实例、MCP 连接状态、JVM 内存。用户说「系统状态」「有什么在运行」「查看能力」「MCP 连接」「工作流状态」时使用。系统级资源检查（CPU/内存/磁盘）用 healthcheck。"
version: "2.0.0"
suggested-tools:
  - system.status
---

# 系统自省指南

查看知微运行时信息和各模块状态。

## 适用场景

- 查看各注册中心能力计数（Skill、Agent、工具、工作流、MCP Server）
- 查看工具层次分布（BUILTIN / DYNAMIC / MCP）
- 查看活跃工作流实例状态
- 查看 MCP Server 连接状态
- 排查模块健康问题

## 不适用场景

- 系统级资源检查（CPU/内存/磁盘） → 用 healthcheck
- 用户问"你能做什么" → 直接根据已知 Skill 列表回答，不需要加载本 Skill

## 工作流

### 查看系统状态

```
system.status()
```

返回信息包含：
- 各注册中心能力计数
- 工具层次分布
- JVM 内存使用
- 活跃工作流实例（RUNNING / PAUSED / WAITING / CREATED）
- MCP Server 连接状态（连接时间、健康检查、重连次数）

### 排查问题

1. 用 `system.status()` 查看整体状态
2. 关注 MCP Server 连接异常（state 不是 AVAILABLE）
3. 关注阻塞的工作流实例（blockedStepId 非空）

## 规则

- 直接调用 `system.status()` 获取数据，不猜测系统状态
- 部分模块可能未注入（WorkflowRepository、McpServerRegistry），对应信息会显示 `available: false`，如实告知用户

## 常见错误处理

- **模块不可用** → 告知用户该模块未启用，不报错
- **数据为空** → 可能是刚启动还未注册，建议稍后重试
