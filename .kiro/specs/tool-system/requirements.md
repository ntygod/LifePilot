# 工具系统需求文档

参考文档：
- 架构设计：#[[file:docs/architecture/tool-ecosystem.md]]
- 特性设计：#[[file:docs/features/tool-ecosystem.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

---

## 1. 概述

工具系统是 LifePilot Agent 引擎的核心扩展层，负责定义工具契约、管理工具注册、执行工具调用，并通过护栏引擎保障安全。本 spec 聚焦于工具系统的核心能力（Layer 3 Java 原生工具 + DynamicToolRegistry + GuardrailEngine + ToolExecutionPipeline），不包含 MCP 协议集成和 YAML 声明式工具（将在后续 spec 中实现）。

---

## 2. 用户故事

### US-1: 工具契约定义
作为核心开发者，我需要一个类型安全的工具契约体系（sealed interface），使所有工具（无论来源）都有统一的输入/输出 Schema、风险等级声明和执行预算，消除参数幻觉和类型不安全问题。

**验收标准：**
- AC-1.1: ToolContract 为 sealed interface，permits BuiltinTool, YamlTool, McpTool
- AC-1.2: 每个工具必须声明 inputSchema、outputSchema（JSON Schema 格式）
- AC-1.3: 每个工具必须声明 RiskLevel（LOW/MEDIUM/HIGH/CRITICAL）
- AC-1.4: 每个工具必须声明 ToolBudget（超时/重试/成本上限）
- AC-1.5: 每个工具必须声明 ToolLayer（JAVA_NATIVE/YAML_DECLARATIVE/MCP_EXTERNAL）
- AC-1.6: ToolInput 支持 JSON Schema 校验，校验失败返回结构化错误
- AC-1.7: ToolResult 为结构化信封（ok/data/error/meta），不使用异常控制流

### US-2: 动态工具注册中心
作为核心开发者，我需要一个线程安全的动态工具注册中心（DynamicToolRegistry），统一管理三层工具，支持运行时注册/注销，对 AgentLoop 完全透明。

**验收标准：**
- AC-2.1: 支持三层工具注册：registerBuiltinTool / registerYamlTools / registerMcpTools
- AC-2.2: 同名工具冲突时，高层覆盖低层（Layer 3 > Layer 2 > Layer 1）
- AC-2.3: 同层冲突时，先注册者优先，后注册者记录警告并跳过
- AC-2.4: 支持按 MCP Server 批量注销工具（unregisterMcpTools）
- AC-2.5: 支持 YAML 工具热加载（unregisterYamlTools + registerYamlTools）
- AC-2.6: 提供缓存快照（getToolSnapshot），避免 AgentLoop 每轮循环创建新列表
- AC-2.7: 注册/注销/冲突时发布 Spring ApplicationEvent
- AC-2.8: 使用 ConcurrentHashMap 保证线程安全

### US-3: 护栏引擎
作为核心开发者，我需要一个护栏引擎（GuardrailEngine），根据工具风险等级执行不同的审批流程，防止 LLM 幻觉或越狱导致的危险操作。

**验收标准：**
- AC-3.1: LOW 风险工具自动执行，无需审批
- AC-3.2: MEDIUM 风险工具自动执行 + 审计日志记录
- AC-3.3: HIGH 风险工具需要用户确认后执行
- AC-3.4: CRITICAL 风险工具需要用户确认 + 二次验证
- AC-3.5: GuardrailPolicy 维护工具白名单，未注册工具一律拒绝
- AC-3.6: 护栏检查结果为 sealed interface（Allowed/NeedsConfirmation/Denied）
- AC-3.7: 护栏检查在 ToolExecutionPipeline 中强制执行，不可绕过

### US-4: 工具执行管线
作为核心开发者，我需要一个工具执行管线（ToolExecutionPipeline），串联参数校验、护栏检查、幂等去重、超时控制、重试策略和轨迹记录，确保每次工具调用都安全、可观测。

**验收标准：**
- AC-4.1: 执行管线按顺序执行：参数校验 → 护栏检查 → 幂等去重 → 执行 → 重试 → 轨迹记录
- AC-4.2: 参数校验失败返回结构化错误（供 LLM 理解并修正）
- AC-4.3: 护栏拒绝时返回 Denied 结果，不执行工具
- AC-4.4: 幂等工具重复调用直接返回缓存结果
- AC-4.5: 超时控制使用 CompletableFuture + Virtual Thread
- AC-4.6: 重试使用指数退避策略（初始 500ms，倍数 2.0，上限 5s）
- AC-4.7: 每次调用生成 ToolResultMeta，关联 Agent TraceStep

### US-5: AgentToolProvider 桥接
作为核心开发者，我需要将工具系统与 Agent 引擎桥接，使 AgentLoop 通过 AgentToolProvider 接口获取 Spring AI ToolCallback 列表，实现工具调用的透明集成。

**验收标准：**
- AC-5.1: 实现 AgentToolProvider 接口，替换当前的 NoOpAgentToolProvider
- AC-5.2: 将 DynamicToolRegistry 中的 ToolContract 转换为 Spring AI ToolCallback
- AC-5.3: ToolCallback 的执行委托给 ToolExecutionPipeline
- AC-5.4: AgentAutoConfiguration 中的 agentToolProvider Bean 被工具系统的实现覆盖

---

## 3. 正确性属性

### CP-1: 工具注册幂等性
对于同一个 toolId，重复注册同层工具不会产生副作用（先注册者优先）。

### CP-2: 优先级覆盖一致性
如果 Layer 3 工具和 Layer 1 工具同名，resolve(id) 始终返回 Layer 3 工具。

### CP-3: 快照不可变性
getToolSnapshot() 返回的列表是不可变的，后续注册/注销不影响已获取的快照。

### CP-4: 护栏不可绕过
所有通过 ToolExecutionPipeline 执行的工具调用都必须经过护栏检查，无例外。

### CP-5: 预算强制执行
工具执行超时后必须被终止，不会无限等待。

---

## 4. 非功能需求

- 工具注册/注销操作的时间复杂度为 O(1)（ConcurrentHashMap）
- 工具快照获取为 O(1)（缓存）
- 工具系统通过 `lifepilot.tool.enabled` 配置项控制启用/禁用
- 所有日志、注释、异常消息使用中文
