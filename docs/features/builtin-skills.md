# 预置 Skill 与元能力工具 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：预置 Skill / 元能力工具
> **最后更新**：2026-04-15

> **说明**：本文只覆盖与记忆模块直接相关的预置能力。

> **注意**：`src/main/resources/skills/` 目录下共有 **25** 个预置 Skill 定义。完整 Skill ID 列表：`a2ui`、`api-debugger`、`browser-automation`、`code-assistant`、`content-creator`、`cron-scheduler`、`daily-manager`、`data-analyst`、`database-query`、`datastore`、`desktop-automation`、`doc-processor`、`feishu`、`file-organizer`、`find-skills`、`gitee`、`github-workflow`、`healthcheck`、`introspection`、`log-analyzer`、`research-assistant`、`summarizer`、`teaching-assistant`、`web-novel-writer`、`workflow-creator`。本文仅覆盖 Memory Skill 相关能力。

## 1. 功能概述

当前记忆能力由 `MemoryToolProvider`（元能力模块）直接注册工具实现，不依赖独立的 `memory/SKILL.md` 资源文件。`MemoryToolProvider` 在应用启动后将记忆与资料检索工具注册到 `DynamicToolRegistry`，Agent 可直接调用这些工具进行检索、创建和更新。

## 2. 核心特性

### 2.1 记忆实体搜索

`memory.search` 用于搜索长期记忆实体，如人物、地点、事件、偏好、习惯、目标和经验。

### 2.2 跨会话对话回忆

`memory.recall` 用于搜索别的 session 里的历史对话片段。

- 返回的是 snippet，而不是零散单条消息
- 会自动排除当前 session
- 适用于“我之前说过什么”“上次聊到哪儿了”这类问题

### 2.3 知识库文档搜索

`knowledge.search` 用于搜索当前会话绑定的资料文档，适合“文档里怎么说”的场景。

### 2.4 语义记忆管理

以下工具用于管理长期记忆实体和关系：

- `memory.create`
- `memory.update`
- `memory.delete`
- `memory.tag`
- `memory.query-at-time`

### 2.5 历史经验复用

`memory.search-experience` 用于主动搜索历史执行经验，适用于：

- 遇到类似任务时想复用过去策略
- 工具连续失败时参考成功经验
- 想了解某类工具的最佳使用方式

## 3. 工具清单

| 工具 | 说明 |
|------|------|
| `memory.search` | 搜索长期记忆实体 |
| `memory.recall` | 回忆跨会话历史对话片段 |
| `knowledge.search` | 搜索资料文档 |
| `memory.create` | 创建记忆实体 |
| `memory.update` | 更新记忆实体 |
| `memory.delete` | 归档记忆实体 |
| `memory.tag` | 添加实体关系 |
| `memory.query-at-time` | 查询时间点有效实体 |
| `memory.search-experience` | 搜索历史执行经验 |

## 4. 使用场景

### 4.1 回忆别的会话

用户问“我上次提到过旅行计划吗”，Agent 应调用 `memory.recall`，而不是在主 Prompt 里自动混入跨会话历史。

### 4.2 查用户长期偏好

用户问“你还记得我喜欢什么样的工作节奏吗”，Agent 可以通过 `memory.search` 查长期偏好实体。

### 4.3 查文档资料

用户问“部署文档里关于回滚怎么写的”，Agent 应调用 `knowledge.search`。

### 4.4 复用执行经验

当某个任务与过去做过的任务相似时，Agent 可以主动调用 `memory.search-experience` 查历史策略。

## 5. 当前限制

- Memory Skill 只负责给出使用说明，真正能力边界由底层工具实现决定
- `knowledge.search` 依赖会话绑定知识库或 datastore，没有绑定时会返回空结果
- `recall` 只检索跨 session 片段，不负责当前 session 连续性
