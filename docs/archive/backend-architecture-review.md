# ZhiWei 后端架构对齐评审（草案）

## 1. 文档目的与范围

本文件基于 `docs/ARCHITECTURE.md` 以及各模块架构文档，对当前后端实现进行一次高层次的**“架构设计 vs 实际实现”**对齐评审，聚焦：

- 哪些核心设计目标已经很好落地；
- 哪些模块“已经实现但入口/集成不够完善”；
- 哪些能力仍处于架构文档中的规划阶段，代码实现明显缺失；
- 后续可以据此拆分为哪些具体改进方向和任务。

本评审**不**尝试穷举所有类与方法，而是按模块/能力层级给出结构化总结。

---

## 2. 总体结论概览

- **整体实现程度较高**：  
  Agent 引擎、四层记忆系统、混合工具生态（含 MCP）、知识库、工作流、多 Agent、外部数据同步、可观测性与护栏等**核心模块已经有完整实现**，并通过 AutoConfiguration、Scheduler、Web Controller 等真实集成进主链路，而非“概念代码”。

- **与理想架构的主要差距点集中在 LLM 路由与多模态集成**：  
  LLM 路由层在文档 `llm-router.md` 中有较详细的预算/用量/缓存设计，但目前代码只实现了多 Provider 场景路由与熔断；多模态/媒体模块和轨迹评估模块实现度较高，但对外入口和主链路集成不足。

- **文档与实现之间存在少量命名与包结构偏差**：  
  例如记忆系统中 `KnowledgeExtractionPipeline` 的包位置、LLM 路由层“规划中的组件”类名等，需要后续做一轮文档与代码的对齐整理。

---

## 3. 分层/模块对齐情况

### 3.1 Agent 引擎（`com.lifepilot.agent`）

**设计目标（见 `ARCHITECTURE.md` + `agent-engine.md`）**

- 强调**概率域（LLM 决策）与确定性域（状态机）分离**。
- 使用 `AgentLoop` 作为状态化控制循环，`StateReducer` 作为纯函数状态机。
- `ContextAssembler` 负责上下文工程，整合记忆检索、预算与 Token 控制。
- 支持 ProactiveReasoner 主动推理能力。

**实际实现情况（高层概括）**

- `AgentLoop` 明确把“调用 LLM → 解析为 Action → 交给 StateReducer”分离出来；  
  `StateReducer` 使用 sealed interface + switch/pattern matching 实现**纯函数状态转换**，利于测试与回放。
- `ContextAssembler` 集成了：
  - 四层记忆检索（通过 `HybridRetriever`、`WorkingMemory` 等）；
  - Token 预算与对话压缩逻辑；
  - 对不同阶段（system / user / history / tools）进行上下文组装。
- `ProactiveReasoner` 作为独立组件，基于规则 + LLM 两阶段推理，在主动推理场景下驱动 AgentLoop。

**集成链路**

- Web：Gateway 中的 `ExecutionMiddleware` 将 Web 请求转换为 `AgentRequest`，调用 `AgentLoop.run()`，返回 `GatewayResponse`。
- A2A：`A2aAgentExecutor` 使用 `AgentLoop` 处理 Agent 间请求。
- 多 Agent：`multiagent.execution.AgentExecutor` 基于 `AgentLoop` 实现子 Agent 执行。
- 评测：`eval.engine.EvalEngine` 通过 `AgentLoop` + Trace 记录实现轨迹评估。

**结论**

- Agent 层与文档中的**关键设计原则高度一致**：概率/确定性分离、上下文工程、主动推理等都有清晰实现和真实调用路径。

---

### 3.2 记忆系统与知识库（`com.lifepilot.memory` + `com.lifepilot.knowledge`）

**设计目标（见 `memory-system.md` + `memory-advanced.md` + `knowledge-base.md`）**

- 四层认知记忆架构：Working / Episodic / Semantic / Procedural。
- 提供多通道 Hybrid 检索：向量 + FTS5 文本 + 图遍历。
- 具备记忆巩固（Consolidation）与遗忘（MaRS 风格）机制。
- 知识库通过文档管线与时序知识图谱结合。

**实际实现情况**

- **L1 WorkingMemory**：  
  管理 session 级工作记忆槽（`ConversationSlot`、`ToolResultSlot`、`ReasoningSlot` 等），支持 Token 预算与清理，并通过 `flush()` 写入 L2。
- **L2 EpisodicMemory**：  
  由 `conversations` / `messages` 表（Flyway `V5__create_memory_tables.sql` 等）和 FTS5（`V6__create_messages_fts.sql`）支撑，记录对话历史和消息。
- **L3 SemanticMemory**：  
  基于实体/关系模型（`TemporalEntity`/`TemporalRelation`），使用 sqlite-vec 存储向量，并提供：
  - `ConflictDetector` 做冲突检测；
  - `VersionMerger` 做版本合并；
  - 支持时间旅行查询与版本化。
- **L4 ProceduralMemory**：  
  存储意图/程序性知识，通过 `IntentMatcher` 为检索与建议提供支持。

- **HybridRetriever**：  
  并行执行：
  - 向量检索；
  - FTS5 文本检索；
  - 图遍历（时序知识图谱），  
  然后按加权融合（含时间衰减、重要度）合并结果，形成统一检索结果，并可返回 L4 层的程序性建议。

- **记忆巩固与遗忘**：
  - `ConsolidationPipeline` 使用 `@Scheduled` 任务定期将 Episodic 转化为 Semantic/Procedural。
  - `ForgettingEngine` 同样通过调度任务驱动，综合 FIFO / LRU / PriorityDecay / Reflection-Summary / Hybrid 等策略，并带有日志记录。

- **知识库集成**：
  - `DocumentIngester` 负责文档解析/切分。
  - `KnowledgeExtractionPipeline` 从文档 chunk 中提取实体/关系，写入 L3 语义记忆/知识图谱。
  - `DocumentRetriever` 提供基于文档的混合检索，对应架构文档的 GraphRAG 设计。

**结论**

- 记忆系统与知识库管线的实现**完全符合乃至超出文档描述**，四层架构、混合检索、巩固与遗忘，以及知识图谱的结合都已有落地实现，并通过 `ContextAssembler`、同步模块、知识库 API 等被真实使用。

---

### 3.3 Skill / Tool / MCP 生态（`com.lifepilot.skill` + `com.lifepilot.tool` + `com.lifepilot.mcp`）

**设计目标（见 `tool-ecosystem.md` + `skill-system.md`）**

- 三层工具来源：
  - Layer 3：Java 原生 Skill（内置插件）；
  - Layer 2：YAML 声明式 Skill（用户/系统可配置）；
  - Layer 1：MCP 外部工具服务器。
- `DynamicToolRegistry` 统一管理所有工具，Agent 对工具来源透明。
- 通过 `AgentToolProvider` 将 Tool 映射为 LLM 可用的 ToolCallbacks。

**实际实现情况**

- **统一契约与工具注册**：
  - `ToolContract`、`ToolInput`、`ToolResult`、`JsonSchema` 等类型定义齐备，支持参数校验与错误封装。
  - `DynamicToolRegistry` 管理所有工具（内置、YAML、MCP），支持运行时增删。

- **三层工具来源**：
  - Layer 3（Java 原生）：
    - 各内置 Skill（Todo/Schedule/Habit/Memory 等）在 `SkillAutoConfiguration` 中注册为 Bean；
    - 通过 `BuiltinSkillRegistrar` 统一注入 `SkillRegistry` 与 `DynamicToolRegistry`。
  - Layer 2（YAML 声明式 Skill）：
    - `YamlSkillLoader` + `SkillFileWatcher` 从资源目录和用户目录加载 `*.yml`；
    - `SkillToToolBridge` 将 Skill 转换为标准 Tool 并注册进 `DynamicToolRegistry`。
  - Layer 1（MCP 外部工具）：
    - `McpClient` + `McpTransport`（Stdio/SSE/Streamable HTTP）管理 MCP 连接；
    - `McpServerRegistry` 负责服务端生命周期和健康检查；
    - `McpToolAdapter` 将 MCP 工具映射为 `ToolContract` 并注册进 `DynamicToolRegistry`。

- **Agent 集成**：
  - `ToolBridgeAgentToolProvider` 实现 `AgentToolProvider`，在每轮 LLM 调用前基于当前 Agent 状态从 `DynamicToolRegistry` 取快照，构建 Spring AI 的 ToolCallbacks 列表；
  - `AgentLoop` 对工具来源透明，只依赖 `AgentToolProvider`。

**结论**

- Skill / Tool / MCP 生态的**设计与实现高度对齐**，并且已经与 AgentLoop/LLM 调用链完全打通，是系统目前最成熟的模块之一。

---

### 3.4 LLM 路由层（`com.lifepilot.llm`）

**设计目标（见 `llm-router.md`）**

- 多 Provider、多能力（Chat/Embedding/Multimodal）统一路由。
- 支持场景级路由（按 LlmScene 选择 Provider 策略）。
- 内建熔断与退避机制。
- 预算感知与成本控制：`TokenBudgetManager`、`LlmUsageTracker`。
- 语义缓存：`SemanticCache` 等，用于减少重复调用。

**现有实现**

- **已实现部分**：
  - `LlmRouter` 提供统一接口：`call` / `callEntity` / `embed` / `stream` 等；
  - `ProviderRegistry` 管理各 Provider 的配置与 `ProviderAdapter`；
  - `SpringAiProviderAdapter` 将 Spring AI 的 `ChatModel` / `EmbeddingModel` 适配进路由层；
  - `CircuitBreakerManager` 基于 `providerId + capabilityType` 实现能力级熔断，符合文档中“能力类型隔离”的设计；
  - 退避/重试等逻辑存在基本实现。

- **缺失或未接线部分**：
  - 文档中详细描述的以下组件目前**在代码中缺失或未接入**：
    - `TokenBudgetManager`：按用户/场景/时间窗口控制 Token 预算；
    - `LlmUsageTracker`：记录每次调用的消耗，形成统计与账单；
    - `SemanticCache`：基于语义相似度的响应缓存；
  - 因此，`LlmRouter` 当前主要负责：
    - 在多 Provider 间进行路由与熔断控制；
    - 并**未真正实现预算感知与成本控制、语义缓存等高级特性**。

**结论**

- LLM 路由层的**基础设施已搭好（Provider 管理 + 场景路由 + 熔断/退避）**，但与架构文档中关于“预算/统计/缓存”的设计相比，仍有明显缺口，是当前最值得优先补齐的区域之一。

---

### 3.5 可观测性与护栏（`com.lifepilot.observability`）

**设计目标（见 `observability.md` + `error-handling.md`）**

- 提供 Trace 级别的行为可观测能力。
- 使用 GuardrailEngine 实现 Policy-as-Code 风格的护栏。
- 支持轨迹评估（Trajectory Evaluation）。

**实际实现情况**

- **Trace 记录与查询**：
  - `TraceRecorderImpl` + `TraceAdvisor` 通过 Spring AI Advisor 模式接入；
  - AgentLoop 与 StateReducer 在关键步骤记录 trace（如状态转换、工具调用等）；
  - `TraceQuery` 提供按 traceId / 时间过滤等查询能力；
  - 数据通过 Flyway 迁移脚本对应的表结构落地。

- **护栏与脱敏**：
  - `GuardrailEngine` + `GuardrailAdvisor` 在 ChatClient 调用链上横切注入输入/输出/工具护栏；
  - 使用 `DataRedactor` 对敏感信息进行脱敏处理；
  - 对于被阻止的请求，通过异常与 Action 映射在 Agent 层体现（例如 `Action.Blocked`）。

- **轨迹评估**：
  - `TrajectoryEvaluator` 配合 eval 模块实现工具选择正确性、步骤效率等指标的评估逻辑；
  - 当前主要在 Java 服务内部调用，尚未有友好的 Web API 或 CLI 外壳暴露。

**结论**

- 可观测性与护栏模块**实现度高，与文档高度一致**；  
  不足主要在“如何对外暴露这些评估与轨迹数据”，而非核心能力本身。

---

### 3.6 多 Agent / 工作流 / 同步 / Sandbox / 多模态媒体

**多 Agent（`com.lifepilot.multiagent`，参见 `multi-agent.md` + `multi-agent-v2.md`）**

- 已实现：
  - `AgentRegistry`、`AgentExecutor`、`AgentMarkdownLoader`/Parser；
  - 支持从 Markdown 文件加载 Agent 定义，并通过 `AgentToToolBridge` 将 Agent 暴露为 Tool；
  - `MultiAgentAutoConfiguration` 在应用启动后自动加载 `preset-agents` 与用户目录中的 Agent 定义。
- 集成：
  - 注册的多 Agent 可以作为工具在 AgentLoop 中被调用，支持 Handoff 场景。

**工作流（`com.lifepilot.workflow`，参见 `workflow.md`）**

- 已实现：
  - YAML 定义解析与序列化；
  - Workflow 引擎、状态管理与 Trigger 管理；
  - `WorkflowController` 提供 CRUD、启用/禁用与手动触发 API。
- 集成：
  - Workflow 可以调用 Skill/Tool，形成 Agent + Workflow 的组合能力。

**外部数据同步（`com.lifepilot.sync`，参见 `external-data-sync.md`）**

- 已实现：
  - 多种 Connector（CalDav、Todoist、滴答清单、Obsidian 等）；
  - `SyncEngine` 负责统一的同步逻辑与冲突策略；
  - `SyncScheduler` 通过定时任务管理同步频率；
  - `SyncSkillProvider` 暴露同步能力为 Skill/Tool。
- 集成：
  - 同步结果可进入记忆系统或任务系统，与 Agent 能力协同。

**Sandbox（`com.lifepilot.sandbox`，参见 `sandbox.md`）**

- 已实现：
  - 代码执行记录、会话管理；
  - `CodeExecuteTool` 等工具，对外以 Tool 形式提供安全的代码执行能力。
- 集成：
  - 当前入口主要通过 Tool 使用，UI 与更友好的交互仍有提升空间。

**多模态媒体（`com.lifepilot.media` + `com.lifepilot.llm.multimodal`，参见 `multimodal.md`）**

- 已实现：
  - 音频处理（转写、TTS）、视频处理（关键帧截取、音轨抽取）、文档解析等；
  - `MultimodalRouter` 统一管理多模态模型与处理流程；
  - 对应 AutoConfiguration 负责 Bean 注册。
- 集成现状：
  - 后端已经具备相对完善的处理与路由能力；
  - 但在 Chat 流程与知识库 ingest 流程中的**入口与端到端集成较少**，目前看更偏“可用基础设施”，还未完全融入日常使用路径。

**结论**

- 多 Agent、工作流、同步模块的**代码与配置都较为完善，并有 AutoConfiguration / Controller / Scheduler 等集成点**，说明它们不是“孤立模块”；  
- Sandbox 与多模态媒体的能力实现较好，但需要进一步在 Web/Agent 主路径中打造清晰的**端到端使用场景**。

---

## 4. 关键优势总结（已打磨较好的部分）

- **Agent 架构清晰、可测试性强**：  
  AgentLoop + StateReducer 分离概率/确定性领域，配合 sealed interface 与模式匹配，利于单测与属性测试。

- **四层记忆系统 + 混合检索落地完备**：  
  Working/Episodic/Semantic/Procedural 四层记忆、HybridRetriever 三路检索、记忆巩固与 MaRS 风格遗忘均已实现，并与 Agent/知识库/同步模块联动。

- **混合工具生态与 MCP 集成成熟**：  
  Java 原生 + YAML Skill + MCP 外部工具统一由 DynamicToolRegistry 管理，Agent 通过 AgentToolProvider 透明使用，是系统目前最成熟的模块之一。

- **Trace 级可观测性与护栏体系完整**：  
  TraceRecorder、GuardrailEngine、DataRedactor 与 TrajectoryEvaluator 构成了从行为追踪、策略护栏到轨迹评估的闭环，基本满足架构文档中对“可观测性 + 安全”的高标准要求。

- **多 Agent / 工作流 / 同步等模块实现深度较高**：  
  不仅有 API/配置，还与 Skill/Tool/AgentLoop 有实际集成，具备真实可用性。

---

## 5. 与架构文档的主要差距与不足

### 5.1 LLM 预算管理与使用统计尚未落地

- 文档中详细设计了：
  - `TokenBudgetManager`：按用户/场景/时间窗口控制 Token 预算与状态（OK/WARN/BLOCKED）；
  - `LlmUsageTracker`：对每次调用进行用量与成本统计；
  - `SemanticCache`：语义级缓存以减少重复调用。
- 现状：
  - 这些组件在代码中暂未发现对应实现或未接入 `LlmRouter` 调用链；
  - `LlmRouter` 当前主要负责 Provider 路由与熔断，对预算/成本/缓存基本无感知。
- 影响：
  - 无法基于预算智能选择模型；
  - 难以从系统层面管控 Token 成本；
  - 重复调用较多场景缺少缓存层减少消耗。

### 5.2 多模态能力基础完备但与主链路集成不足

- 媒体处理与 `MultimodalRouter` 实现较为完善，但：
  - Chat 与知识库侧缺少统一的“上传媒体 → 自动处理 → 产出文本/摘要/知识入库”的端到端流程；
  - 多模态相关的 LLM 场景（如多模态总结/问答）在路由与 Agent 层的整合有限。
- 影响：
  - 多模态能力目前更像独立组件，用户需要通过较底层的 API/Tool 才能使用，不符合文档中“日常可用”的愿景。

### 5.3 可观测性与评估能力对外暴露不够友好

- 内部实现的 Trace 与 TrajectoryEvaluator 已较完备，但：
  - 缺少面向用户/运维的 Web API 或 CLI，让人可以方便地：
    - 按 traceId/时间段查询轨迹；
    - 查看评估结果与指标（工具正确率、步骤数、Token 成本等）；
    - 为后续 UI 图表/分析提供数据源。
- 影响：
  - 难以在实践中发挥“轨迹级调试/对比评估”的价值；
  - 与文档中“Trace 级行为可观测 + 轨迹评估”的产品化体验仍有差距。

### 5.4 文档与实现的差异与误导点

- 部分架构文档中的包结构/类名与实际实现不完全一致，例如：
  - 记忆系统中的 `KnowledgeExtractionPipeline` 位置；
  - LLM Router 章节中的部分组件在代码中尚未出现（属于规划名称）。
- 影响：
  - 新加入的开发者或外部读者可能误以为某些组件已实现；
  - 维护时容易产生“按文档找不到代码”的困惑。

### 5.5 外围模块的“健康度”需要一次系统性端到端验证

- 多 Agent、同步、工作流、Sandbox 等模块：
  - 从代码/配置角度看实现较为完整；
  - 但尚未有统一记录“哪些典型场景已经端到端跑通”的健康报告。
- 影响：
  - 对这些模块的稳定性和“可立即用于生产/日常使用”程度缺乏清晰认识；
  - 架构文档中的部分“理想用例”可能尚需配置/入口/UI 补充才能实现。

---

## 6. 建议的改进方向（后续任务拆分基础）

> 下面是若干建议的中短期改进方向，后续可以基于此文档将每个方向拆解为具体任务。

### 6.1 LLM 预算与使用统计接入 LlmRouter

- 设计并实现：
  - `TokenBudgetManager`：支持日/月级配额、场景级策略和状态机（OK/WARN/BLOCKED）。
  - `LlmUsageTracker`：记录每次 LLM 调用的 Token 与成本，用于统计与告警。
  - `SemanticCache`：基于场景 + 归一化 Prompt 的语义缓存。
- 将上述组件接入：
  - 在 `LlmRouter` 路由流程中**先**询问 TokenBudgetManager 决定是否允许调用及选择 Provider 策略；
  - 在调用完成后将实际消耗写入 LlmUsageTracker；
  - 在入口阶段检查 SemanticCache 命中以短路调用。

### 6.2 多模态能力的端到端集成

- 在 Web 与 Agent 层面补齐：
  - 上传音频/视频/文档的统一 API；
  - 自动调用媒体处理 + 文档解析 + 知识提取/摘要；
  - 将结果写入知识库与记忆系统（如 Episodic/Semantic）。
- 为多模态问答/总结定义明确的 LLM 场景并在 LlmRouter 中配置相应 Provider 策略。

### 6.3 可观测性与评估对外接口

- 为 Trace 与 TrajectoryEvaluator：
  - 增加 REST API（或 CLI 子命令），支持基本的查看与过滤；
  - 统一结构化返回评估结果与关键指标，便于前端可视化。
- 在文档中补充：
  - 如何通过这些 API 进行调试与回归对比；
  - 与现有 observability 架构文档互相引用。

### 6.4 架构文档与实现的同步修订

- 针对 `llm-router.md`、`memory-system.md`、`knowledge-base.md` 等关键文档：
  - 标注哪些组件**已实现**、哪些处于**规划中**；
  - 更新包结构与类名，使其与当前代码一致；
  - 对尚未实现的组件，将本评审中的改进方向链接为后续 Roadmap。

### 6.5 多 Agent / 同步 / 工作流 / Sandbox 的健康检查

- 为每个模块选取 1–2 条“黄金路径”用例，例如：
  - 新建一个 Markdown Agent，通过 Tool 调用完成一次完整任务；
  - 配置一个 Todoist 或 CalDav 同步 Profile，并观察本地数据变化；
  - 定义一个简单工作流（例如“每日总结”），并触发执行；
  - 使用 Sandbox 运行一段代码并记录结果。
- 实际运行并记录：
  - 是否端到端成功；
  - 所需配置/前提条件；
  - 已知限制/缺陷。  
- 在对应架构/功能文档中新增“小结：当前实现状态与已知限制”。

---

## 7. 后续工作建议

- 将本评审文档作为后续 Roadmap 讨论的基础：
  - **短期优先项**：建议优先考虑 LLM 预算/统计（6.1）与多模态端到端集成（6.2），二者直接影响成本与用户体验。
  - **中期优化项**：可观测性 API（6.3）与文档同步修订（6.4）。
  - **持续性工作**：模块健康检查（6.5）可作为回归测试与发布前检查的一部分。
- 后续可以针对每个方向单独开文档/Issue，对应拆分为：
  - 设计澄清任务；
  - 具体开发任务（后端/前端）；
  - 回归测试与文档更新任务。

---