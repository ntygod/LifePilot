# Requirements Document

## Introduction

多 Agent 协作模块（模块 21）为 LifePilot 引入四层能力模型（Tool → Skill → Agent → Orchestrator），解决现有 Skill 系统中 L1 确定性工作流与 L2 自主推理实体职责重叠的根本问题。本模块创建独立的 Agent 层（L2），使主 Agent（L3 Orchestrator）能够通过 HandoffTool 将特定领域任务委托给拥有独立身份、独立上下文、独立预算和差异化模型的专家 Agent 执行。同时清理 Skill 系统中的 SubAgent 激活路径，使 Skill 回归纯 L1 确定性工作流定位。

本模块采用 agents-as-tools 模式（委托执行后返回，主 Agent 保持控制权），复用现有 AgentLoop、Action.SubAgentResult、AgentState.forSubAgent 等基础设施。Agent 使用 Markdown + YAML Frontmatter 格式声明式定义（`.md` 文件），Markdown 正文作为 System Prompt，支持热加载。

参考文档：
- 架构设计：#[[file:docs/architecture/multi-agent-v2.md]]
- 特性设计：#[[file:docs/features/multi-agent-v2.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

## Glossary

- **AgentDefinition**: Agent 的完整蓝图 record，包含身份标识、System Prompt、工具白名单、预算约束、模型偏好等字段
- **AgentRegistry**: Agent 定义的运行时注册中心，支持注册、注销、查找、列举操作，使用 ConcurrentHashMap 实现线程安全
- **HandoffTool**: 委托工具，每个注册的 Agent 对应一个 HandoffTool 实例（工具 ID 格式 `handoff_to_{agentId}`），LLM 通过 Function Call 调用以委托任务
- **AgentExecutor**: 执行器，负责创建隔离上下文（AgentState.forSubAgent）、分配独立预算、选择 LLM Provider 并驱动 AgentLoop 执行委托任务
- **AgentBudget**: Agent 级预算 record，包含 maxTokens、maxSteps、timeoutSeconds 三个维度，可转换为 AgentLoop 使用的 Budget record
- **AgentSource**: Agent 来源密封接口，permits Builtin（内置预设）和 MarkdownDefined（用户 Markdown 定义，含 filePath 和 lastModified）
- **AgentMarkdownLoader**: Markdown Agent 定义加载器，解析 YAML Frontmatter 提取结构化字段，Markdown 正文作为 System Prompt
- **AgentToToolBridge**: Agent 工具桥接器，监听 AgentRegistry 事件，自动为 Agent 注册/注销对应的 HandoffTool
- **Primary_Agent**: 主 Agent（L3 Orchestrator），直接处理用户请求，拥有 HandoffTool 可委托任务给专家 Agent
- **Expert_Agent**: 专家 Agent（L2），被委托执行特定领域任务的 Agent，拥有独立身份、上下文和预算
- **Delegation_Depth**: 委托深度，HandoffTool 嵌套调用的层数，复用 AgentState.depth 机制
- **MultiAgentProperties**: 多 Agent 协作模块的 @ConfigurationProperties 配置类，管理所有业务可调参数
- **ToolDiscovery**: 工具发现能力，允许查询系统中所有可用工具（BuiltinTool + Skill 工具 + MCP 工具）的列表和描述

## Requirements

### Requirement 1: AgentDefinition 数据模型

**User Story:** As a 系统开发者, I want to 使用 record 定义 Agent 的完整蓝图, so that 每个专家 Agent 拥有明确的身份、能力范围和资源约束。

#### Acceptance Criteria

1. THE AgentDefinition SHALL 使用 Java record 定义，包含以下字段：id（唯一标识，kebab-case）、name（显示名称）、description（能力描述，用于 HandoffTool 描述和 LLM 发现）、systemPrompt（专属 System Prompt，来自 Markdown 正文）、allowedTools（工具白名单列表，引用系统中已注册的工具 ID）、canDelegate（是否允许嵌套委托）、budget（AgentBudget）、preferredProvider（偏好 LLM Provider ID，可空）、source（AgentSource）、metadata（扩展元数据 Map）
2. THE AgentDefinition SHALL 使用 `@Builder(toBuilder = true)` 注解支持不可变实例的部分字段更新
3. THE AgentDefinition SHALL 对 allowedTools 和 metadata 字段执行防御性拷贝（List.copyOf / Map.copyOf）
4. THE AgentBudget SHALL 使用 Java record 定义，包含 maxTokens、maxSteps、timeoutSeconds 三个维度，并提供 DEFAULT（16000/15/180）、LIGHTWEIGHT（4000/8/60）、HEAVYWEIGHT（32000/25/300）三个预设常量
5. THE AgentBudget SHALL 提供 toAgentBudget() 方法将自身转换为 AgentLoop 使用的 Budget record
6. THE AgentSource SHALL 为密封接口，permits Builtin 和 MarkdownDefined 两个子类型，其中 MarkdownDefined 包含 filePath（String）和可空的 lastModified（Instant）字段

### Requirement 2: AgentRegistry 注册中心

**User Story:** As a 系统开发者, I want to 通过 AgentRegistry 管理所有 Agent 定义的生命周期, so that Agent 可以在运行时动态注册、注销和查找。

#### Acceptance Criteria

1. THE AgentRegistry SHALL 使用 ConcurrentHashMap 存储 AgentDefinition，以 Agent ID 为键
2. WHEN 注册一个 AgentDefinition 时, THE AgentRegistry SHALL 校验 ID 非空，校验失败时返回 false 并记录警告日志
3. WHEN 注册一个 Builtin 来源的 AgentDefinition 且已存在同 ID 的 Builtin Agent 时, THE AgentRegistry SHALL 拒绝覆盖并返回 false
4. WHEN 注册一个 MarkdownDefined 来源的 AgentDefinition 且已存在同 ID 的 Builtin Agent 时, THE AgentRegistry SHALL 允许覆盖（用户自定义优先于内置预设）
5. THE AgentRegistry SHALL 通过 ApplicationEventPublisher 发布 AgentRegistered 和 AgentUnregistered 事件
6. WHEN 注销一个 AgentDefinition 时, THE AgentRegistry SHALL 从注册表中移除该 Agent 并发布 AgentUnregistered 事件
7. THE AgentRegistry SHALL 提供 find(agentId) 方法返回 Optional<AgentDefinition>
8. THE AgentRegistry SHALL 提供 listAll() 方法返回所有已注册 Agent 的不可变列表
9. THE AgentRegistry SHALL 提供 unregisterBySource(sourceType) 方法批量注销指定来源类型的所有 Agent，返回注销数量

### Requirement 3: AgentToToolBridge 工具桥接

**User Story:** As a 系统开发者, I want to Agent 注册/注销时自动同步 HandoffTool 到 DynamicToolRegistry, so that LLM 能够通过 Function Call 发现和调用 Agent。

#### Acceptance Criteria

1. WHEN AgentToToolBridge 收到 AgentRegistered 事件且 MultiAgentProperties 的 registerHandoffTools 为 true 时, THE AgentToToolBridge SHALL 创建对应的 HandoffTool 并注册到 DynamicToolRegistry
2. WHEN AgentToToolBridge 收到 AgentUnregistered 事件时, THE AgentToToolBridge SHALL 从 DynamicToolRegistry 注销对应的 HandoffTool
3. THE AgentToToolBridge SHALL 使用 Spring @EventListener 监听 AgentRegistry 发布的事件

### Requirement 4: HandoffTool 委托工具

**User Story:** As a Primary_Agent 的 LLM, I want to 通过 Function Call 调用 HandoffTool 将任务委托给 Expert_Agent, so that 特定领域任务由专门优化的 Agent 处理。

#### Acceptance Criteria

1. THE HandoffTool SHALL 实现 ToolContract 接口，工具 ID 格式为 `handoff_to_{agentId}`
2. THE HandoffTool SHALL 接受两个参数：task（必填，委托任务描述）和 context（选填，额外上下文信息）
3. THE HandoffTool 的 description SHALL 包含对应 Agent 的 name 和 description，使 LLM 能够理解该工具的用途并自主决策何时委托
4. WHEN HandoffTool 被调用时, THE HandoffTool SHALL 委托 AgentExecutor 执行任务并返回执行结果
5. IF 当前 AgentState.depth 已达到 MultiAgentProperties 配置的 maxDelegationDepth, THEN THE HandoffTool SHALL 拒绝执行并返回错误信息
6. IF 目标 Agent 的 canDelegate 为 false, THEN THE HandoffTool SHALL 在构建子 Agent 工具列表时排除所有 HandoffTool

### Requirement 5: AgentExecutor 执行器

**User Story:** As a HandoffTool, I want to 通过 AgentExecutor 在隔离环境中执行 Expert_Agent, so that 子 Agent 拥有独立的上下文、预算和工具集。

#### Acceptance Criteria

1. WHEN AgentExecutor 执行委托任务时, THE AgentExecutor SHALL 调用 AgentState.forSubAgent() 创建隔离的子 AgentState，depth 为父状态 depth + 1
2. WHEN AgentExecutor 执行委托任务时, THE AgentExecutor SHALL 使用 AgentDefinition 的 AgentBudget.toAgentBudget() 创建独立的 Budget 实例
3. WHEN AgentDefinition 指定了 preferredProvider 时, THE AgentExecutor SHALL 将该 Provider ID 传递给 LlmRouter 用于模型选择
4. THE AgentExecutor SHALL 仅向子 AgentLoop 提供 AgentDefinition.allowedTools 中声明的工具，从 DynamicToolRegistry 中按 ID 解析实际工具实例
5. THE AgentExecutor SHALL 调用 AgentLoop.run() 执行子 Agent 并返回 Action.SubAgentResult
6. IF AgentLoop.run() 执行过程中抛出异常, THEN THE AgentExecutor SHALL 捕获异常并返回 success=false 的 SubAgentResult，包含错误信息

### Requirement 6: Markdown 声明式 Agent 定义与热加载

**User Story:** As a 用户, I want to 通过 Markdown 文件定义自定义 Expert_Agent 并支持热加载, so that 无需编码和重启即可扩展和修改 Agent 能力。

#### Acceptance Criteria

1. THE AgentMarkdownLoader SHALL 从 MultiAgentProperties 配置的 agentDefinitionsPath 目录加载所有 `.md` 文件
2. WHEN 一个合法的 Markdown Agent 定义文件被加载时, THE AgentMarkdownLoader SHALL 解析 YAML Frontmatter 提取结构化字段（id、name、description、allowed-tools、can-delegate、budget、preferred-provider、metadata），Markdown 正文作为 systemPrompt，以 AgentSource.MarkdownDefined 来源注册到 AgentRegistry
3. THE Markdown Agent 定义文件 SHALL 使用 YAML Frontmatter（`---` 分隔符）+ Markdown 正文格式，其中 Frontmatter 包含结构化配置，正文作为 Agent 的 System Prompt
4. IF Markdown 文件格式不合法或缺少必填字段（id、name、description）, THEN THE AgentMarkdownLoader SHALL 跳过该文件并记录警告日志，包含文件路径和具体错误原因
5. IF Markdown 正文为空, THEN THE AgentMarkdownLoader SHALL 跳过该文件并记录警告日志（System Prompt 是 Agent 的核心，不允许为空）
6. WHEN agentDefinitionsPath 目录不存在时, THE AgentMarkdownLoader SHALL 自动创建该目录并记录 INFO 日志
7. WHILE MultiAgentProperties 的 hotReload.enabled 为 true 时, THE AgentMarkdownLoader SHALL 以 hotReload.scanIntervalSeconds 为间隔定期扫描 agentDefinitionsPath 目录，检测文件新增、修改和删除
8. WHEN 检测到 Markdown 文件新增或修改时, THE AgentMarkdownLoader SHALL 重新解析该文件并更新 AgentRegistry 中的对应 Agent 定义
9. WHEN 检测到 Markdown 文件删除时, THE AgentMarkdownLoader SHALL 从 AgentRegistry 中注销对应的 MarkdownDefined Agent

### Requirement 7: 预设专家 Agent

**User Story:** As a 用户, I want to 开箱即用地获得写作专家、生活教练和规划专家三个预设 Expert_Agent, so that 常见的专业任务无需额外配置即可委托执行。

#### Acceptance Criteria

1. THE MultiAgentAutoConfiguration SHALL 在启动时以 AgentSource.Builtin 来源注册三个预设 Expert_Agent：writer（写作专家）、life-coach（生活教练）、planner（规划专家）
2. THE writer Agent SHALL 拥有专注于文字创作的 System Prompt（结构化写作原则、风格匹配、素材组织），工具白名单包含 memory-search 和 knowledge-search，预算级别为 DEFAULT，canDelegate 为 false
3. THE life-coach Agent SHALL 拥有教练式引导提问的 System Prompt（Socratic 提问法、行为模式分析、正向反馈），工具白名单包含 memory-search、knowledge-search、skill.todo-query、skill.schedule-query、skill.habit-query，预算级别为 HEAVYWEIGHT，canDelegate 为 false
4. THE planner Agent SHALL 拥有时间管理方法论的 System Prompt（Eisenhower 矩阵、时间块法、能量管理），工具白名单包含 memory-search、skill.todo-query、skill.schedule-query、skill.habit-query，预算级别为 DEFAULT，canDelegate 为 false
5. THE 三个预设 Expert_Agent 的 Markdown 定义文件 SHALL 作为 classpath 资源打包在 JAR 中，启动时由 AgentMarkdownLoader 解析后以 Builtin 来源注册

### Requirement 8: 配置外部化

**User Story:** As a 用户, I want to 通过配置文件调整多 Agent 协作的行为参数, so that 可以根据实际需求优化委托策略和资源分配。

#### Acceptance Criteria

1. THE MultiAgentProperties SHALL 通过 `@ConfigurationProperties(prefix = "lifepilot.agent.multi-agent")` 绑定配置
2. THE MultiAgentProperties SHALL 包含以下可配置项及默认值：enabled（true）、maxDelegationDepth（2）、agentDefinitionsPath（~/.lifepilot/agents/）、registerHandoffTools（true）
3. THE MultiAgentProperties SHALL 包含嵌套的 HotReload 配置类，含 enabled（true）和 scanIntervalSeconds（5）
4. THE MultiAgentProperties SHALL 包含嵌套的 BudgetDefaults 配置类，含 defaultMaxTokens（16000）、defaultMaxSteps（15）、defaultTimeoutSeconds（180）
5. WHILE MultiAgentProperties 的 enabled 为 false 时, THE MultiAgentAutoConfiguration SHALL 跳过所有 Bean 注册，不加载预设 Agent 和 Markdown Agent

### Requirement 9: Spring AutoConfiguration 集成

**User Story:** As a 系统开发者, I want to 多 Agent 协作模块通过 Spring AutoConfiguration 自动装配, so that 模块可以无侵入地集成到现有 Spring Boot 应用中。

#### Acceptance Criteria

1. THE MultiAgentAutoConfiguration SHALL 使用 `@AutoConfiguration(after = {AgentAutoConfiguration.class, SkillAutoConfiguration.class})` 确保在 Agent 引擎和 Skill 系统之后加载
2. THE MultiAgentAutoConfiguration SHALL 使用 `@ConditionalOnProperty(prefix = "lifepilot.agent.multi-agent", name = "enabled", havingValue = "true", matchIfMissing = true)` 控制启用
3. THE MultiAgentAutoConfiguration SHALL 注册 AgentRegistry、AgentExecutor、AgentMarkdownLoader、AgentToToolBridge Bean
4. WHEN ApplicationContext 刷新完成后, THE MultiAgentAutoConfiguration SHALL 先注册预设 Expert_Agent（Builtin），再加载用户自定义 Markdown Agent 定义（MarkdownDefined）

### Requirement 10: Skill 系统清理（L1 回归）

**User Story:** As a 系统开发者, I want to 清理 Skill 系统中的 SubAgent 激活路径, so that Skill 回归纯 L1 确定性工作流定位，与 Agent（L2）职责边界清晰。

#### Acceptance Criteria

1. THE SkillDefinition SHALL 移除 preferredProviderId 字段（Skill 不使用 LLM，无需模型偏好）
2. THE SkillToToolBridge SHALL 移除 SubAgent 激活路径，仅桥接 L1 确定性 Skill 执行（HttpAction / ShellAction / ChainAction / TemplateAction）
3. THE SkillLifecycleManager SHALL 移除 SubAgent 激活委托逻辑
4. THE SubAgentFactory SHALL 标记为 @Deprecated，其 SubAgent 激活职责由 AgentExecutor 替代
5. WHEN 现有 Skill YAML 定义文件不包含 SubAgent 相关配置时, THE SkillYamlLoader SHALL 正常加载，保持完全向后兼容

### Requirement 11: 工具发现能力

**User Story:** As a 用户, I want to 查询系统中所有可用工具的列表和描述, so that 创建自定义 Agent 时能够知道 allowed-tools 可以引用哪些工具 ID。

#### Acceptance Criteria

1. THE ToolDiscoveryService SHALL 提供 listAvailableTools() 方法，返回系统中所有已注册工具的摘要列表（包含 toolId、name、description、来源类型）
2. THE ToolDiscoveryService SHALL 聚合三类工具来源：BuiltinTool（内置工具）、Skill 工具（通过 SkillToToolBridge 注册）、MCP 工具（通过 MCP 协议注册）
3. THE ToolDiscoveryService SHALL 从 DynamicToolRegistry 获取所有已注册工具，排除 HandoffTool 类型（避免循环引用）
4. WHEN 用户通过 CLI 或 API 请求工具列表时, THE ToolDiscoveryService SHALL 返回按来源类型分组的工具清单
