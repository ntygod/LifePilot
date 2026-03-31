# 内置 Skill 插件 — 架构设计

> **文档性质**：架构设计文档
> **模块归属**：预置 Skill / 元能力工具
> **最后更新**：2026-03-20

> **说明**：本文只覆盖与记忆模块直接相关的预置能力，不展开其他 Skill 子系统实现。

> **注意**：`src/main/resources/skills/` 目录下共有 **33** 个内置 Skill 定义。完整 Skill ID 列表：`api-debugger`、`browser-automation`、`code-assistant`、`content-creator`、`cron-scheduler`、`daily-manager`、`data-analyst`、`database-query`、`datastore`、`desktop-automation`、`dingtalk`、`doc-processor`、`email-manager`、`feishu`、`file-organizer`、`find-skills`、`gitee`、`github-workflow`、`healthcheck`、`heartbeat-checklist`、`introspection`、`log-analyzer`、`memory`、`project-scaffolder`、`research-assistant`、`session-logs`、`summarizer`、`teaching-assistant`、`translator`、`web-novel-writer`、`workflow-creator`、`wps-office`、`yuque`。本文仅覆盖 Memory Skill。

## 1. 模块概述

当前记忆相关的预置能力分成两层：

- **预置 Skill 定义**：`src/main/resources/skills/memory/SKILL.md`
- **底层记忆工具注册**：`com.lifepilot.meta.infra.memory.MemoryToolProvider`

也就是说，Memory Skill 的“指令和建议工具列表”由资源文件提供，而真正可执行的记忆/资料检索工具由 `MemoryToolProvider` 注册到 `DynamicToolRegistry`。

## 2. 架构图

```mermaid
graph TB
    subgraph "预置 Skill 定义"
        MSD["memory/SKILL.md<br/>instructions + suggestedTools"]
        SR["SkillRegistry"]
        MSD --> SR
    end

    subgraph "元能力工具注册"
        MAC["MetaAutoConfiguration"]
        MTP["MemoryToolProvider"]
        DTR["DynamicToolRegistry"]
        MAC --> MTP
        MTP --> DTR
    end

    subgraph "记忆依赖"
        HR["HybridRetriever"]
        SM["SemanticMemory"]
        EM["EpisodicMemory"]
        DR["DocumentRetriever"]
        SKB["SessionKnowledgeBaseRepository"]
    end

    MTP --> HR
    MTP --> SM
    MTP -.可选.-> EM
    MTP -.可选.-> DR
    MTP -.可选.-> SKB
```

## 3. 核心组件

### 3.1 `memory/SKILL.md`

- 提供记忆相关的提示词说明和建议工具列表
- 当前列出的关键工具包括：
  - `memory.search`
  - `memory.recall`
  - `knowledge.search`
  - `memory.create`
  - `memory.update`
  - `memory.delete`
  - `memory.tag`
  - `memory.query-at-time`
  - `memory.search-experience`

### 3.2 MemoryToolProvider

- 位于 `com.lifepilot.meta.infra.memory`
- 当前负责注册 9 个记忆工具
- 工具职责分层如下：
  - `search`：搜索 L3/L4 实体
  - `recall`：跨 session 回忆对话片段
  - `knowledge.search`：搜索资料文档
  - `create / update / delete / tag`：管理语义记忆实体和关系
  - `query-at-time`：查询指定时间点有效的实体
  - `search-experience`：搜索历史执行经验

### 3.3 MetaAutoConfiguration

- 在 `HybridRetriever` 和 `SemanticMemory` 可用时注册 `MemoryToolProvider`
- `EpisodicMemory`、`DocumentRetriever`、`SessionKnowledgeBaseRepository` 为可选依赖
- 应用启动完成后，把记忆工具注册到 `DynamicToolRegistry`

## 4. 设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| Skill 与工具分层 | 指令在 `SKILL.md`，执行能力在 `MemoryToolProvider` | 让提示词和实现解耦 |
| recall 返回形式 | snippet 而不是单条 message | 更适合模型直接使用 |
| 资料检索入口 | `knowledge.search` 单独拆出 | 区分会话回忆、知识实体检索和资料文档检索 |
| 工具注册位置 | Meta 模块统一注册 | 便于与其他内置元能力工具共用启动流程 |

## 5. 集成点

| 集成模块 | 方向 | 说明 |
|---------|------|------|
| Skill 系统 | Skill → Tool | `memory/SKILL.md` 暴露记忆相关建议工具 |
| 元能力模块 | Meta → Tool | `MemoryToolProvider` 注册记忆与资料检索工具 |
| 记忆系统 | Tool → Memory | 工具调用 `HybridRetriever`、`SemanticMemory`、`EpisodicMemory` |
| 知识库 | Tool → Knowledge | `knowledge.search` 走知识库检索链路 |
