# 非记忆模块设计 ↔ 实现差距报告

> **范围**：本报告审计**排除记忆系统本身**的模块（即我们不审查 `com.lifepilot.memory` 内部实现，也不提出记忆系统修复建议）。  
> **重要**：我们*会*记录非记忆模块**硬依赖**记忆系统 Bean 的情况，因为这是影响运行时行为和发布顺序的非记忆集成决策。
>
> **来源**：`docs/architecture/*.md` 中的设计文档 + `src/main/java/com/lifepilot/**` 下的实现。
>
> **最后更新**：2026-02-28

---

## 严重程度分级

- **P0**：正确性/安全性缺陷；导致常见流程中的错误行为或不安全行为
- **P1**：重大设计偏差；导致功能不可用/脆弱或破坏承诺的用户体验
- **P2**：不完整/占位实现；早期阶段可接受但应跟踪
- **P3**：完善/技术债务；风险低但会累积

---

## 执行摘要（主要差距）

- **P0 — Skill 工具注销为空操作**：`SkillToToolBridge.onSkillUnregistered` 仅记录警告，尽管注册表支持注销，但仍留下孤立工具。  
  - **已应用修复**：`onSkillUnregistered` 现在调用 `DynamicToolRegistry.unregisterBuiltinTool(toolId)`，`onSkillUpdated` 现在真正注销然后重新注册。
  - **代码**：`src/main/java/com/lifepilot/skill/bridge/SkillToToolBridge.java` + `src/main/java/com/lifepilot/tool/registry/DynamicToolRegistry.java`

- **P1 — AgentLoop 结构化输出设计与实现偏差**：设计文档强调“LLM 通过结构化输出输出 `Action`”，但当前 `AgentLoop` 调用 `LlmRouter.call(..., null)` 并通过 `ActionParser` 从原始文本解析。这可以工作，但与规范偏差并削弱了可靠性保证。
  - **文档**：`docs/architecture/agent-engine.md`（结构化输出 / 基于 Advisor 的链）
  - **代码**：`src/main/java/com/lifepilot/agent/AgentLoop.java`

- **P1 — ProactiveReasoner 配置 + 行为偏差**：主动推理设计文档显示的配置键/默认值和 LLM 评估规则与代码不匹配（前缀、默认值、静默时段类型、LLM 评估门控、持久化策略）。
  - **文档**：`docs/architecture/proactive-reasoning.md`
  - **代码**：`src/main/java/com/lifepilot/agent/proactive/*`

- **P1 — 非记忆模块被记忆系统 Bean 门控**（集成耦合）：某些非记忆能力仅在记忆系统 Bean 存在时实例化，这影响发布和“部分启用”场景。
  - **示例**：
    - 主动推理 `SignalCollector` 需要 `EpisodicMemory`
    - 同步 `SyncEngine` 需要 `EpisodicMemory`
    - 知识提取需要 `SemanticMemory`
  - **代码**：`ProactiveAutoConfiguration`、`SyncAutoConfiguration`、`KnowledgeAutoConfiguration`

---

## 代码中已应用的修复（自上次审查以来）

### Skill → Tool 桥接：真正的注销

- **问题所在（P0）**：`SkillToToolBridge.onSkillUnregistered` 记录 `DynamicToolRegistry`“不支持注销”，留下孤立工具条目。
- **实际情况**：`DynamicToolRegistry` 已经有 `unregisterBuiltinTool(String toolId)`。
- **修复**：调用 `toolRegistry.unregisterBuiltinTool(toolId)`；更新注释/日志；使 `onSkillUpdated` 真正注销旧工具然后注册新工具。
- **代码**：
  - `src/main/java/com/lifepilot/skill/bridge/SkillToToolBridge.java`
  - `src/main/java/com/lifepilot/tool/registry/DynamicToolRegistry.java`

---

## 按模块差距

## 1) Agent 引擎 (`com.lifepilot.agent`) — 排除记忆系统关注点

### 设计承诺（高信号）

- **概率性 vs 确定性分离**：LLM 决策；确定性 reducer 应用状态转换。
- **Action 作为契约**：LLM 输出是 `Action`（推荐结构化输出）。
- **Advisors**：通过 Spring AI Advisor 链注入防护栏和追踪。

**文档**：`docs/architecture/agent-engine.md`

### 实现现状

- `AgentLoop` 使用 `llmRouter.call(scene, assembledContext.userPrompt(), null)`，然后从响应内容通过 `actionParser.parse(...)` 解析。
- SubAgent 模式将 `systemPrompt` 合并到组装的系统提示中，但仍依赖相同的 `call()` 路径。
- 会话持久化在 `asyncPostProcess` 中完成，每次运行创建**新的** `newVirtualThreadPerTaskExecutor()` 并关闭它。

**代码**：`src/main/java/com/lifepilot/agent/AgentLoop.java`

### 差距 / 风险

- **P1 — 结构化输出偏差**：设计显示结构化输出（`.entity(Action.class)`）；实现使用文本+解析器。  
  - **影响**：格式错误的 action 概率更高，提示注入影响 JSON，在 SDK 层验证契约更困难。
  - **建议修复**：至少将“Action 生成”调用通过 `LlmRouter.callEntity(...)` 路由，为每个阶段使用专用 schema/类型（或带判别器的密封 `Action` DTO）并收紧解析回退。

- **P2 — 每个请求分配 Executor**：每次运行分配并关闭新的虚拟线程 executor 是安全的但不是最优的。  
  - **影响**：可避免的开销；可能使性能分析和泄漏检测复杂化。
  - **建议修复**：注入共享的 `ExecutorService` / `TaskExecutor` Bean 用于异步后处理。

---

## 2) Tool 生态系统 (`com.lifepilot.tool`) + 桥接

### 设计承诺

- 3 层注册表（Java 原生 > YAML > MCP），确定性冲突规则，运行时注册/注销。

**文档**：`docs/architecture/tool-ecosystem.md`

### 实现现状

- `DynamicToolRegistry` 实现分层注册和注销方法（builtin/yaml/mcp）并维护缓存快照。
- 防护栏白名单在注册/注销时更新。

**代码**：`src/main/java/com/lifepilot/tool/registry/DynamicToolRegistry.java`

### 差距 / 风险

- **P2 — 注册表注释 / 文档偏差表现为缺陷**：P0 注销缺陷发生是因为桥接代码认为注册表无法注销。  
  - **建议修复**：添加小型单元测试验证 builtin 工具和 Skill 桥接路径的注册→注销。

---

## 3) Skill 系统 (`com.lifepilot.skill`) — 桥接层重点

### 设计承诺

- Skill 是一等能力单元；热重载；生命周期事件驱动桥接；最小化“孤立状态”。

**文档**：`docs/architecture/skill-system.md`

### 实现现状

- `SkillToToolBridge` 将工具注册为 `skill.{id}` builtin，现在在技能移除时注销它们。

**代码**：`src/main/java/com/lifepilot/skill/bridge/SkillToToolBridge.java`

### 差距 / 风险

- **P2 — 风险映射**：Skill 工具无条件注册为 `RiskLevel.MEDIUM`。  
  - **影响**：防护栏批准语义可能不反映实际技能行为。
  - **建议修复**：从技能元数据映射（如果可用）或允许每个技能的风险配置。

---

## 4) 主动推理 (`com.lifepilot.agent.proactive`)

### 设计承诺

- 两阶段管道（RuleEngine <10ms + 可选 LLM 评估），智能频率状态机，主动命名空间下的配置键，以及“异步持久化”说明。

**文档**：`docs/architecture/proactive-reasoning.md`

### 实现现状

- 配置前缀是 **`lifepilot.agent.proactive`**，默认间隔是 **30 分钟**（`intervalMs=1_800_000`），调度器使用 `${lifepilot.agent.proactive.interval-ms:1800000}`。
- 静默时段建模为**小时整数**（开始/结束），而不是 `"23:00"` 字符串。
- LLM 评估在频率检查后对每个候选执行（没有“仅 MEDIUM”门控）。
- `FrequencyStateManager.persistState(...)` 在每次更新中使用同步 `jdbcTemplate.update(...)`。
- `SignalCollector` Bean 仅在 `EpisodicMemory` 和内置存储库存在时创建。

**代码**：
- `src/main/java/com/lifepilot/agent/proactive/config/ProactiveConfigProperties.java`
- `src/main/java/com/lifepilot/agent/proactive/ProactiveReasoner.java`
- `src/main/java/com/lifepilot/agent/proactive/FrequencyStateManager.java`
- `src/main/java/com/lifepilot/agent/proactive/config/ProactiveAutoConfiguration.java`

### 差距 / 风险

- **P1 — 配置键偏差**：文档示例使用 `lifepilot.proactive.*`，代码使用 `lifepilot.agent.proactive.*`。  
  - **影响**：遵循文档的用户实际上无法配置运行系统。
  - **建议修复**：要么 (a) 更新文档，要么 (b) 通过兼容性绑定层支持别名前缀。

- **P1 — 默认间隔不匹配**：文档描述 5 分钟默认值；代码默认为 30 分钟。  
  - **影响**：功能在评估中看起来“不工作”；测试/基准测试偏差。
  - **建议修复**：对齐默认值或在文档中明确说明理由。

- **P1 — LLM 阶段门控偏差**：文档说 LLM 评估“可选 / 仅 MEDIUM”；代码评估所有候选（除了被 `FrequencyStateManager` 过滤的）。  
  - **影响**：更高的 token 成本和更高的延迟；违反“成本递增”原则。
  - **建议修复**：强制执行紧急性门控（例如 HIGH 绕过 LLM，MEDIUM 使用 LLM，LOW 使用被动队列）。

- **P2 — 持久化策略偏差**：文档描述异步持久化；代码同步持久化。  
  - **影响**：如果 SQLite 处于写入争用下，周期性停顿；对于单用户仍然可接受但与规范偏差。
  - **建议修复**：队列写入或批量持久化。

- **P1 — 记忆系统 Bean 门控影响发布**（非记忆集成问题）：如果记忆系统暂时禁用，主动推理引擎部分禁用（无 `SignalCollector`），即使其他部分已启用。  
  - **建议修复**：提供不需要记忆的“降级信号收集器”，或明确记录依赖关系。

---

## 5) Gateway + Interaction (`com.lifepilot.interaction`)

### 设计承诺

- 统一的 `GatewayMessage`，有序中间件链，多通道适配器（CLI/Web/IM），基于 token 的速率限制和安全检查。

**文档**：`docs/architecture/gateway-middleware.md`

### 实现现状

- `GatewayMiddlewareAutoConfiguration` 在属性/Bean 条件后注册中间件 Bean 和 CLI 通道适配器。

**代码**：`src/main/java/com/lifepilot/interaction/config/GatewayMiddlewareAutoConfiguration.java`

### 差距 / 风险

- **P2 — 通道覆盖 vs 文档**：设计突出企业 IM 适配器；当前代码库似乎专注于 CLI/Web（IM 适配器可能根据构建配置文件计划/缺失）。  
  - **影响**：文档承诺超过当前可交付成果。
  - **建议修复**：记录当前通道支持矩阵和功能标志。

---

## 6) Workflow 引擎 (`com.lifepilot.workflow`)

### 设计承诺

- YAML 定义，轮询热重载，崩溃恢复，触发器，虚拟线程，错误策略。

**文档**：`docs/architecture/workflow.md`

### 实现现状

- 热重载使用 `TaskScheduler.scheduleAtFixedRate` 实现为计划扫描。
- 启动连接触发器管理器，运行崩溃恢复，注册触发器，启动计划扫描。

**代码**：
- `src/main/java/com/lifepilot/workflow/config/WorkflowAutoConfiguration.java`
- `src/main/java/com/lifepilot/workflow/registry/WorkflowRegistry.java`

### 差距 / 风险

- **P2 — 验证深度**：注册表验证目前专注于基本必需字段（根据代码注释）。文档提到更丰富的预检查（表达式语法、步骤 ID 唯一性等）。  
  - **影响**：错误的工作流可能仅在运行时失败。
  - **建议修复**：添加 `WorkflowDefinitionValidator` 镜像文档。

---

## 7) Multi-Agent v2 (`com.lifepilot.multiagent`)

### 设计承诺

- 四层模型，Markdown 定义的 Agent，热重载，移交工具注册。

**文档**：`docs/architecture/multi-agent-v2.md`

### 实现现状

- 自动配置从 `classpath:preset-agents/*.md` 加载预设 Agent，然后从配置路径加载用户定义的 Agent 并可选热重载。

**代码**：`src/main/java/com/lifepilot/multiagent/config/MultiAgentAutoConfiguration.java`

### 差距 / 风险

- **P2 — v1/v2 文档之间的规范偏差风险**：存在多个多 Agent 文档；确保仓库有单一的“真相来源”，旧文档明确标记。  
  - **建议修复**：在 `docs/ARCHITECTURE.md` 中添加文档索引说明并弃用旧变体。

---

## 8) Sandbox (`com.lifepilot.sandbox`)

### 设计承诺

- Booter 抽象（process/docker），CRITICAL 工具带批准，会话重用 + TTL，审计。

**文档**：`docs/architecture/sandbox.md`

### 实现现状

- `SandboxAutoConfiguration` 通过 `lifepilot.sandbox.booter` 选择 booter，注册 `CodeExecuteTool` 并将其工具注册到 `DynamicToolRegistry`。

**代码**：`src/main/java/com/lifepilot/sandbox/config/SandboxAutoConfiguration.java`

### 差距 / 风险

- **P2 — 审计存储库 Bean 是条件性的**：`SandboxRepository` 仅在 `JdbcTemplate` 存在时创建；`CodeExecuteTool` 在其 Bean 签名中需要存储库。  
  - **影响**：沙箱工具可能在最小模式下无法实例化；可能是预期的但应该明确。
  - **建议修复**：提供内存/无操作存储库回退。

---

## 9) 外部数据同步 (`com.lifepilot.sync`)

### 设计承诺

- 增量同步，冲突解决，凭证存储，调度器 + 事件触发器。

**文档**：`docs/architecture/external-data-sync.md`

### 实现现状

- `SyncEngine` 仅在 `ChangeDetector` **和 `EpisodicMemory`** Bean 存在时注册。

**代码**：`src/main/java/com/lifepilot/sync/config/SyncAutoConfiguration.java`

### 差距 / 风险

- **P1 — 非记忆功能被记忆系统 Bean 门控**：同步“核心”引擎依赖 `EpisodicMemory`。  
  - **影响**：即使存在仅同步用例，同步也无法在“记忆禁用”部署中运行。
  - **建议修复**：使记忆集成可选（在存在时向记忆系统发出同步事件）。

---

## 10) Knowledge Base (`com.lifepilot.knowledge`)

### 设计承诺

- 摄取管道，多分块器，向量+FTS，提取管道，重排序。

**文档**：`docs/architecture/knowledge-base.md`

### 实现现状

- `KnowledgeExtractionPipeline` 需要 `SemanticMemory` Bean。

**代码**：`src/main/java/com/lifepilot/knowledge/config/KnowledgeAutoConfiguration.java`

### 差距 / 风险

- **P1 — 非记忆模块被记忆系统门控**（集成耦合）：提取依赖 `SemanticMemory`。  
  - **影响**：知识模块可能部分功能（索引/检索），但提取在没有记忆系统时被禁用。
  - **建议修复**：将“提取输出”与“存储目标”分离；当记忆系统不存在时允许存储到 KB 本地存储。

---

## 11) 多模态 (`com.lifepilot.media` + `com.lifepilot.llm.multimodal`)

### 设计承诺

- `MultimodalRouter`，基于能力的路由（VISION/STT/TTS），视频关键帧 + 转录策略。

**文档**：`docs/architecture/multimodal.md`

### 实现现状

- `ProviderCapability` 包括 `VISION`、`TTS`、`STT`。
- `MultimodalRouter` 存在并实现 VISION 路由 + 故障转移；当 `VideoProcessor` 存在时支持视频预处理。
- `MediaAutoConfiguration` 连接路由器和处理器。

**代码**：
- `src/main/java/com/lifepilot/llm/multimodal/MultimodalRouter.java`
- `src/main/java/com/lifepilot/media/config/MediaAutoConfiguration.java`
- `src/main/java/com/lifepilot/llm/config/ProviderCapability.java`

### 差距 / 风险

- **P2 — 端到端通道集成不明确**：架构要求 Gateway/Channel 适配器将音频/媒体消息路由到多模态管道（STT 前置，TTS 后置）。核心构建块存在；集成路径应记录和测试。

---

## 后续建议（仅非记忆系统）

1. **对齐文档和配置前缀**（主动推理是最明显的偏差；确保其他模块匹配）。
2. **将 AgentLoop 移向结构化 Action 输出**以匹配核心可靠性故事（或者如果“解析器方法”现在是预期设计，则明确更新文档）。
3. **减少非记忆 → 记忆硬门控**：尽可能将记忆系统视为可选接收器/源；保持核心引擎功能在没有它的情况下可用。
4. **添加桥接回归测试**（Skill→Tool 注册/更新/注销；MCP 工具注册/注销；YAML 重载路径）。

---

## 超出范围

- 记忆系统架构和实现（`com.lifepilot.memory`，`docs/architecture/memory-*.md`）
- 记忆系统 PR 和记忆表*内部*的架构更改
