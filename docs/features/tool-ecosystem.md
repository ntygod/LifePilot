# 工具系统 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.tool`
> **最后更新**：2026-04

## 1. 功能概述

工具系统为 Agent 提供与外部世界交互的能力，支持两种工具来源：Java 内置工具（BuiltinTool）和 MCP 外部工具（McpTool）。统一的工具契约确保所有工具具有一致的输入输出规范、风险等级声明和执行保障。

> **重要变更**：原三层架构中的 `SkillTool`（SKILL_DECLARATIVE 层）已移除。`load_skill` 独立工具已删除，Skill 加载迁移至 `file.read(skill=...)` 参数，ReactAgentLoop 自动检测并激活工具。`generate_skill` 工具保留用于 LLM 驱动的 Skill 自扩展。

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

- Skill 激活时自动注册关联工具
- MCP 服务器连接时自动注册远程工具

### 2.6 层次优先级

当不同来源的工具 ID 冲突时，按优先级覆盖：

`BuiltinTool > McpTool`

确保内置工具行为不被外部工具意外替换。

## 3. 核心类说明

| 类 | 职责 |
|---|------|
| `ToolContract` | 工具契约 sealed interface |
| `BuiltinTool` | 内置工具（Java 原生） |
| `McpTool` | MCP 外部工具 |
| `ToolExecutor` | 工具执行器 |
| `ToolExecutionPipeline` | 执行管道 |
| `DynamicToolRegistry` | 动态注册表 |

## 3. 使用场景

Agent 在规划阶段决定需要调用哪些工具（如查询待办列表、搜索知识库、执行代码），执行阶段通过工具系统逐步调用。工具系统自动处理参数校验、授权匹配、风险检查、超时重试等细节，Agent 只需关注工具调用结果。

## 4. 配置项

```yaml
lifepilot:
  tool:
    enabled: true
    pipeline:
      default-timeout: 30s
      max-retries: 2
```

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.tool.enabled` | `true` | 是否启用工具系统 |
| `lifepilot.tool.pipeline.default-timeout` | `30s` | 默认执行超时 |
| `lifepilot.tool.pipeline.max-retries` | `2` | 默认最大重试次数 |

## 5. 限制与未来方向

- 工具执行结果的结构化程度依赖各工具实现质量
- Skill 通过 `file.read(skill=...)` 和 `generate_skill` 实现渐进式发现与按需激活，`load_skill` 独立工具已删除
- 未来计划：工具执行结果的自动摘要、工具推荐排序优化
