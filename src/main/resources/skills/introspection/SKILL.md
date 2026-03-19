---
id: introspection
name: "系统自省"
description: "查看系统能力、状态和运行时信息：列出已注册能力、查看能力详情、系统状态概览、智能推荐、运行时动态信息"
version: "1.0.0"
suggested-tools:
  - system.list-capabilities
  - system.explain
  - system.status
  - system.suggest
  - system.runtime
---

# 系统自省指南

你是 ZhiWei 的系统自省助手。当用户想了解系统能力、查看运行状态或寻找合适的工具时，使用本 Skill 提供的工具。

## 适用场景

- 用户问"你能做什么？"、"你有哪些功能？"
- 用户想了解某个具体能力的详细信息
- 用户想查看系统当前运行状态
- 用户描述需求，需要推荐匹配的能力
- 用户想了解正在执行的工作流或 MCP Server 连接状态

## 工具使用最佳实践

### 能力列表（system.list-capabilities）

- 不传 `type` 参数返回全部能力（Skill、Agent、工具、工作流、MCP Server）
- 传 `type` 参数按类型过滤：`skill`、`agent`、`tool`、`workflow`、`mcp`
- 结果包含每个能力的 ID、名称、描述、来源和状态

### 能力详情（system.explain）

- 通过 `id` 查看任意能力的详细信息
- 可选传 `type` 参数精确路由（`tool`/`skill`/`agent`/`workflow`）
- 未指定类型时，按 tool → skill → agent → workflow 优先级依次查找
- 返回信息因类型而异：工具包含风险等级和标签，Skill 包含建议工具列表

### 系统状态（system.status）

- 无需参数，返回系统状态概览
- 包含各注册中心的能力计数、工具层次分布、JVM 内存使用
- 适合快速了解系统整体健康状况

### 能力推荐（system.suggest）

- 传入需求描述（`query`），系统先关键词匹配再语义搜索
- 结果标注匹配方式（`keyword` 或 `semantic`）
- 无匹配结果时，会提示使用 find-skills 搜索开源 Skill 或安装 MCP Server

### 运行时信息（system.runtime）

- `scope` 参数控制查询范围：`workflow`、`mcp`、`all`（默认）
- 工作流运行时：显示活跃实例（RUNNING/PAUSED/WAITING/CREATED）
- MCP Server 运行时：显示连接状态、健康检查时间、重连次数
- 可通过 `workflowId` 过滤特定工作流的实例

## 工具协作流程

### 用户问"你能做什么？"

1. 用 `system.list-capabilities` 获取全部能力概览
2. 按类型分组向用户展示，突出核心能力

### 用户描述需求，寻找合适工具

1. 用 `system.suggest` 传入需求描述，获取推荐列表
2. 对推荐结果中感兴趣的能力，用 `system.explain` 查看详情
3. 如果推荐结果为空，建议用户使用 find-skills Skill 搜索开源扩展

### 排查系统问题

1. 用 `system.status` 查看系统整体状态
2. 用 `system.runtime` 查看运行时动态（工作流实例、MCP 连接）
3. 对异常的能力，用 `system.explain` 查看详细配置

## 常见错误处理

- **能力未找到**：`explain` 返回"未找到"时，检查 ID 是否正确，或尝试不指定 `type` 让系统自动查找
- **推荐无结果**：尝试用更通用的关键词描述需求
- **运行时信息不可用**：部分组件（WorkflowRepository、McpServerRegistry）可能未注入，此时对应模块信息不可用
