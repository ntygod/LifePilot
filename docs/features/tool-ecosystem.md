# 工具系统 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.tool`
> **最后更新**：2026-05-04

## 1. 功能概述

工具系统为 Agent 提供与外部世界交互的能力，支持两种工具来源：Java 内置工具（BuiltinTool）和 MCP 外部工具（McpTool）。统一的工具契约确保所有工具具有一致的输入输出规范、风险等级声明和执行保障。

工具对 LLM 的暴露采用核心常驻 + 按需发现模式：`memory` / `file.read` / `status` / `skill.load` / `tool.search` 直接注入；其他 Java 内置工具和 MCP 外部工具通过 `tool.search` 发现后再注入。

> **重要变更**：
> - 原三层架构中的 `SkillTool`（SKILL_DECLARATIVE 层）已移除。Skill 系统 v2（2026-04-24）把激活入口归一到 `skill.load(names=[...])` BuiltinTool，废弃了 `file.read(skill=...)` 捷径、`SkillDisclosureTool` 空壳以及 `generate_skill` 独立工具；Skill 自生成由 `SkillSynthesizer` 后台服务在判定能力缺口时触发，不再通过 Agent 侧工具暴露。
> - 旧的 `lifepilot.agent.core-tool-ids` 配置已删除，由 `lifepilot.tool.tier1.pinned` 静态配置替代。
> - **2026-04-25 后**：原 `Tier1AdvisoryJob` / `ToolUsageStatsRecorder` 自动晋升 / 降级机制已下架（V29 删表）。单机本地部署没有"管理员审批"角色，PENDING advisory 永远没有人 APPROVE，整套机制是死代码。Tier 1 现在完全由 `application.yml` 的 `pinned` 列表手工维护。
> - **2026-04-25 后**：FTS5 索引 tokenizer 从 `unicode61` 切到 `trigram`（V30 迁移），`ToolValidator` 放开了 description / tags 的英文限制，工具描述改写中文（含高频用户短语）让 trigram substring 召回更准确。

## 2. 核心架构

### 2.1 两种工具来源

| 工具类型 | 包路径 | 特点 |
|----------|--------|------|
| `BuiltinTool` | `com.lifepilot.tool.BuiltinTool` | Java 代码实现，性能最优，可靠性最高 |
| `McpTool` | `com.lifepilot.tool.McpTool` | MCP 协议桥接，接入第三方工具生态 |

### 2.2 统一工具契约

`ToolContract` sealed interface 定义标准化属性：

- ID、描述、输入输出 Schema
- 风险等级（RiskLevel）
- 幂等性声明
- 执行预算（ToolBudget）

LLM 通过工具描述和 Schema 理解工具用途。

### 2.3 四级风险分级

| 风险等级 | 描述 | 默认执行策略 |
|----------|------|--------------|
| `LOW` | 只读操作，无副作用 | 直接执行 |
| `MEDIUM` | 有副作用但可撤销 | 直接执行 + 审计日志 |
| `HIGH` | 不可逆操作 | 交互渠道走授权；自主渠道需预授权 |
| `CRITICAL` | 涉及敏感数据或高敏感动作 | 交互渠道走更严格授权；自主渠道需预授权 |

`RiskLevel` 枚举位于 `com.lifepilot.observability.guardrail` 包。

### 2.4 执行管道

`ToolExecutionPipeline` 完整执行流程：

1. 参数预处理与校验
2. 权限判定与授权匹配
3. 护栏预检（GuardrailEngine）
4. 幂等查重（IdempotencyManager）
5. 超时控制
6. 重试（指数退避）
7. 实际执行

幂等工具的重复调用直接返回缓存结果。

### 2.5 动态注册

`DynamicToolRegistry` 支持运行时动态注册和注销工具：

- Java 内置工具在启动期注册
- MCP 服务器连接时自动注册远程工具

### 2.6 层次优先级

当不同来源的工具 ID 冲突时，按优先级覆盖：

`BuiltinTool > McpTool`

确保内置工具行为不被外部工具意外替换。

### 2.7 核心常驻 + 按需发现

- **核心 pinned 工具**：`lifepilot.tool.tier1.pinned` 配置列表中的少量核心工具，完整 schema 常驻 prompt，LLM 可直接调用
- **非核心 Java / MCP 工具**：通过 `tool.search` 发现，由 `ToolSearchIndexMaintainer` 维护到 FTS5 索引；发现结果写入 `ReactAgentState.discoveredToolIds`，下一轮注入 schema
- **Skill**：`skill.load` 只注入 Skill 指南内容，`suggested_tools` 不再自动改变工具可见集

### 2.8 工具搜索（FTS5 trigram + BM25）

- `ToolSearchService` 走"sanitize → 缓存 → FTS5 MATCH + BM25 排序 + 语义 RRF → 排除核心 pinned / 已发现 / meta / 权限外"链路；`bm25-confidence-threshold` 决定返回的 confidence 标签
- FTS5 表用 `tokenize = 'trigram'`（V30 迁移），3 字符滑窗双向 substring 匹配，对中文短语命中友好；query 走 `ToolSearchQuerySanitizer` 切 3-gram phrase 用 `OR` 连接
- 工具 description / tags 中英混排，并主动写入"删除文件""复制目录"等高频用户短语，让 trigram 直接命中
- Tier 1 名单完全由 `pinned` 配置静态维护，无后台晋升 / 降级 Job

### 2.9 启动期命名强校验（ToolValidator）

- `id`：`^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$`，namespace 必须自描述或含动词词根
- `name`：必须含中文字符
- `description`：长度 ≥ 20 字符；允许中英混排；未检测到中文且无英文动词词根时 warn
- `tags`：数量 ≥ 3，非空白且不重复；允许中英混排
- 硬规则违反抛 `IllegalStateException` 阻止启动；`tool.search` 与 `a2a_remote_*` 工具豁免

## 3. 核心类说明

| 类 | 职责 |
|---|------|
| `ToolContract` | 工具契约 sealed interface |
| `BuiltinTool` | 内置工具（Java 原生） |
| `McpTool` | MCP 外部工具 |
| `ToolExecutor` | 工具执行器 |
| `ToolExecutionPipeline` | 执行管道 |
| `DynamicToolRegistry` | 动态注册表 |
| `ToolBridgeAgentToolProvider` | core pinned ∪ discovered 过滤，生成 Spring AI ToolCallback |
| `Tier1Service` | 只读 `pinned` 配置，提供 Tier 1 工具 ID 集合 |
| `ToolSearchService` | 搜索链路服务 |
| `BuiltinToolSearchProvider` | 注册 `tool.search` meta BuiltinTool |
| `ToolSearchIndexBuilder` / `ToolSearchIndexMaintainer` | FTS5 索引全量 / 增量维护 |
| `ToolValidator` | 启动期工具命名规范强校验 |

## 3. 使用场景

Agent 在规划阶段决定需要调用哪些工具（如查询待办列表、搜索知识库、执行代码），执行阶段通过工具系统逐步调用。工具系统自动处理参数校验、授权匹配、风险检查、超时重试等细节，Agent 只需关注工具调用结果。

## 4. 配置项

```yaml
lifepilot:
  tool:
    enabled: true
    pipeline:
      default-timeout-seconds: 30
      default-max-retries: 2
    tier1:
      pinned:
        - memory
        - file.read
        - status
        - skill.load
        - tool.search
    search:
      default-limit: 5
      max-limit: 20
      bm25-confidence-threshold: 1.0
```

关键配置键：

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.tool.enabled` | `true` | 工具系统总开关 |
| `lifepilot.tool.pipeline.default-timeout-seconds` | `30` | 默认执行超时 |
| `lifepilot.tool.pipeline.default-max-retries` | `2` | 默认最大重试次数 |
| `lifepilot.tool.tier1.pinned` | 5 项核心工具 | 人工固定的核心工具 ID |
| `lifepilot.tool.search.default-limit` | `5` | `tool.search` 默认 limit |
| `lifepilot.tool.search.max-limit` | `20` | `tool.search` 单次上限 |
| `lifepilot.tool.search.bm25-confidence-threshold` | `1.0` | BM25 高置信度阈值 |

完整字段见 [工具系统架构文档](../architecture/tool-ecosystem.md#7-配置参考)。

## 5. 限制与未来方向

- 工具执行结果的结构化程度依赖各工具实现质量
- Skill 通过 `skill.load(names=[...])` 实现渐进式按需激活，`file.read(skill=...)` 和 `generate_skill` 已删除（详见 `docs/architecture/skill-system.md`）
- 未来计划：工具执行结果的自动摘要、工具推荐排序优化
