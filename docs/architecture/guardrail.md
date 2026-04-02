# 安全护栏 — 架构设计

> **文档性质**：架构设计文档
> **模块归属**：`com.lifepilot.observability.guardrail`
> **最后更新**：2026-04

## 1. 模块概述

安全护栏系统负责在 LLM 之外强制执行工具调用的安全策略，所有护栏类均位于 `observability.guardrail` 包中。当前它主要负责内容安全（ContentSafetyPolicy）、速率限制（RateLimitPolicy）、数据脱敏（DataRedactionPolicy）和审计日志；工具权限、风险判定、用户授权和白名单控制均由 `permission` 模块负责，预算限制已移除（个人助手场景下费用由用户自行承担）。

## 2. 架构图

```mermaid
graph TB
    subgraph "调用链"
        AGENT["AgentLoop"]
        PIPE["ToolExecutionPipeline"]
    end

    subgraph "护栏引擎"
        ENGINE["GuardrailEngine<br/>策略注册 + 执行"]
        ADVISOR["GuardrailAdvisor<br/>Spring AI Advisor 横切注入"]
    end

    subgraph "策略模型"
        POLICY["GuardrailPolicy（sealed interface）<br/>3 permits"]
        CSP["ContentSafetyPolicy<br/>阻断模式 + 敏感话题"]
        RLP["RateLimitPolicy<br/>每分钟最大调用次数"]
        DRP["DataRedactionPolicy<br/>脱敏标记"]
        RISK["RiskLevel（enum）<br/>LOW/MEDIUM/HIGH/CRITICAL"]
        APPROVAL["ApprovalMode（enum）<br/>风险语义映射"]
        RESULT["GuardrailResult（sealed interface）<br/>Passed/Blocked/NeedsConfirmation"]
    end

    subgraph "异常"
        BLOCKED["GuardrailBlockedException"]
        CONFIRM["GuardrailConfirmationRequiredException"]
    end

    POLICY --> CSP
    POLICY --> RLP
    POLICY --> DRP

    AGENT --> PIPE --> ENGINE
    ADVISOR -->|"Advisor 注入"| AGENT
    ENGINE --> POLICY
    POLICY --> RISK
    RISK --> APPROVAL
    ENGINE --> RESULT
    ENGINE --> BLOCKED
```

## 3. 核心组件

### 3.1 GuardrailEngine

- 职责：策略注册表 + 执行引擎，按优先级顺序执行所有已注册策略
- 关键接口：`registerPolicy(GuardrailPolicy)`、`unregisterPolicy(policyId)`、`checkToolCall(tool, input)`、`checkInput(content)`、`checkOutput(content)`

### 3.2 GuardrailPolicy（sealed interface）

- 职责：可插拔的安全策略定义
- 三个 permits：`ContentSafetyPolicy`（内容安全，阻断模式 + 敏感话题）、`RateLimitPolicy`（速率限制，每分钟最大调用次数）、`DataRedactionPolicy`（数据脱敏标记）
- 关键属性：`policyId()`、`enabled()`、`priority()`

### 3.3 RiskLevel（enum）

- 四级风险：`LOW`（自动执行）→ `MEDIUM`（自动 + 审计）→ `HIGH`（高风险授权）→ `CRITICAL`（关键风险授权）
- 关键方法：`requiresConfirmation()`、`requiresAudit()`、`requiresSecondaryVerification()`

### 3.4 GuardrailAdvisor

- 职责：作为 Spring AI Advisor 横切注入 ChatClient 调用链，在 LLM 调用前后执行护栏检查

## 4. 核心流程

```mermaid
sequenceDiagram
    participant P as ToolExecutionPipeline
    participant PM as PermissionService
    participant E as GuardrailEngine
    participant PO as GuardrailPolicy

    P->>PM: 先完成权限判定 / 授权
    P->>E: 检查工具调用安全性
    loop 按优先级遍历策略
        E->>PO: evaluate(toolId, params)
        PO-->>E: GuardrailResult
    end
    alt 护栏通过
        E-->>P: 允许执行 / 记录审计
    else 策略阻断
        E-->>P: 抛出 GuardrailBlockedException
    end
```

## 5. 设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 策略模式 | GuardrailPolicy sealed interface（3 permits） | 编译时穷举策略类型，支持运行时动态注册/注销 |
| 风险分级 | 四级枚举 | 为权限系统和护栏阻断提供统一风险语义 |
| 注入方式 | Spring AI Advisor | 复用 Spring AI 生态，无侵入式横切注入 |
| 包归属 | 全部位于 `observability.guardrail` 包 | 护栏是可观测性的子能力，不再独立成包 |

## 6. 集成点

- **工具系统**（`tool`）：ToolExecutionPipeline 在权限判定之后调用 GuardrailEngine
- **权限系统**（`permission`）：高风险工具先经授权链路，再进入护栏检查
- **Agent 引擎**（`agent`）：GuardrailAdvisor 注入 ChatClient 调用链
- **可观测性**（`observability`）：审计记录写入轨迹系统

## 7. 配置参考

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.observability.guardrail.enabled` | `true` | 是否启用护栏引擎（GuardrailEngine + GuardrailAdvisor） |
