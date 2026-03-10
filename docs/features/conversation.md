# 对话系统 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.conversation`
> **最后更新**：2026-03

## 1. 功能概述

对话系统模块为知微（ZhiWei）提供统一的对话历史视图能力。它聚合来自 Agent 会话快照（L0）和情景记忆（L2）的数据，为 Web UI 对话页面、评估组件等提供只读的对话历史查询接口，同时通过 `ConversationHistoryStore` 抽象对话消息的持久化写入。

## 2. 核心特性

### 2.1 统一对话视图

通过 `ConversationViewService` 提供与底层存储解耦的对话历史读取入口。无论数据存储在 L0 会话快照还是 L2 情景记忆中，消费方都通过同一接口获取标准化的 `ConversationTurnView`。

### 2.2 双数据源智能聚合

查询完整对话时间线时，优先从 L2 情景记忆读取归档数据（包含完整历史）；若该会话尚未归档到 L2，则自动退化为 L0 会话快照中的最近 N 轮消息，保证始终有数据可返回。

### 2.3 对话历史写入抽象

`ConversationHistoryStore` 接口将对话消息的写入与记忆系统物理解耦。Web UI 通过此接口保存用户消息和助手回复，支持关联 traceId 用于轨迹追踪，支持推理摘要（reasoningSummary）记录。

### 2.4 轮次视图模型

每条对话消息抽象为 `ConversationTurnView` record，包含角色（USER/ASSISTANT）、内容、时间戳和可选的推理摘要。视图模型与底层存储结构完全解耦，既可从会话快照映射，也可从情景记忆记录映射。

## 3. 使用场景

### 3.1 Web UI 对话历史展示

用户打开对话页面时，前端通过 REST API 调用 `ConversationViewService.getFullTimeline()` 获取完整对话历史，按时间正序渲染消息气泡。新消息通过 `ConversationHistoryStore` 实时写入。

### 3.2 评估组件轨迹回放

Agentic Evals 评估框架通过 `ConversationViewService` 读取指定会话的对话历史，结合 TraceRecorder 的执行轨迹进行多维度评估（工具选择正确性、步骤效率等）。

### 3.3 记忆系统上下文组装

ContextAssembler 在组装 Agent 上下文时，通过 `ConversationViewService.getRecentTurns()` 获取最近对话消息，填充对话历史槽位。

## 4. 配置项

本模块无独立配置项。相关配置由上游模块管理：

| 相关配置 | 所属模块 | 说明 |
|---------|---------|------|
| `lifepilot.agent.session.*` | Agent 会话管理 | 会话快照容量、过期策略 |
| `lifepilot.memory.episodic.*` | 记忆系统 | 情景记忆归档策略 |

## 5. 限制与未来方向

### 当前限制

- 视图层为只读聚合，不支持对话消息的编辑或删除
- `getFullTimeline()` 在 L2 无数据时退化为 L0 最近 N 轮，可能不完整
- `ConversationHistoryStore.appendSystemMessage()` 默认 no-op，需实现类覆盖

### 未来方向

- 支持对话消息的搜索和过滤
- 支持对话导出（Markdown / JSON 格式）
- 推理过程可视化（展示 Agent 的工具调用、记忆检索等中间步骤）
