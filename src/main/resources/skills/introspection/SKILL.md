---
name: introspection
description: 当用户询问"系统状态""有什么在运行""查看能力""MCP 连接""工作流状态"等需要查看知微运行时信息时使用；覆盖 Skill 数量、工具分布、工作流实例、MCP 连接状态、JVM 内存。系统级资源检查（CPU/内存/磁盘）不在本 Skill 范围，应用 healthcheck。
version: 2.0.0
metadata:
  zhiwei:
    category: system
    priority: normal
    suggested_tools:
      - system.status
    tags:
      - introspection
      - runtime
      - status
---

# 系统自省指南

查看知微运行时信息和各模块状态。直接调用 `system.status()` 即可获取结构化数据，不猜测。

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

1. **调用 `system.status()`** 获取整体快照
2. **关注异常信号**：MCP Server state 非 AVAILABLE、工作流实例 blockedStepId 非空
3. **模块未注入时**（`available: false`）如实告知用户该模块未启用，不报错
4. **数据为空**可能是刚启动还未注册，建议稍后重试
</content>
</invoke>