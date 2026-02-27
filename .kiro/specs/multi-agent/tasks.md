# Implementation Plan: 多 Agent 协作（模块 21）

## Overview

按自底向上顺序实现多 Agent 协作模块：先建立数据模型层（AgentBudget / AgentSource / AgentDefinition / AgentRegistryEvent），再实现注册中心（AgentRegistry）和跨模块变更（DynamicToolRegistry.unregisterBuiltinTool），然后构建 HandoffToolFactory 和 AgentExecutor 执行器，接着实现 Markdown Agent 定义解析器和加载器（含热加载），再通过 AgentToToolBridge 桥接事件，然后完成 AutoConfiguration 和预设 Agent（writer / life-coach / planner），清理 Skill 系统 SubAgent 路径，最后实现 ToolDiscoveryService 工具发现能力。AgentRequest 扩展在 AgentExecutor 任务中一并完成。

## Tasks

- [ ] 1. 实现数据模型层（model 包）
  - [ ] 1.1 实现 AgentSource 密封接口和 AgentBudget record
    - 创建 `com.lifepilot.multiagent.model.AgentSource` sealed interface
    - permits `Builtin` 和 `MarkdownDefined` 两个 record 子类型
    - `MarkdownDefined` 包含 `filePath`（String）和 `@Nullable lastModified`（Instant）字段
    - 创建 `com.lifepilot.multiagent.model.AgentBudget` record
    - 包含 `maxTokens`、`maxSteps`、`timeoutSeconds` 三个 int 字段
    - 定义 `DEFAULT(16000,15,180)`、`LIGHTWEIGHT(4000,8,60)`、`HEAVYWEIGHT(32000,25,300)` 预设常量
    - 实现 `toAgentBudget()` 方法，映射到 `com.lifepilot.agent.model.Budget`（tokensUsed=0, tokensReserved=0, elapsed=ZERO）
    - _Requirements: 1.4, 1.5, 1.6_

  - [ ] 1.2 实现 AgentDefinition record
    - 创建 `com.lifepilot.multiagent.model.AgentDefinition` record
    - 包含 10 个字段：id, name, description, systemPrompt, allowedTools, canDelegate, budget, preferredProvider(@Nullable), source, metadata
    - 使用 `@Builder(toBuilder = true)` 注解
    - 紧凑构造器中对 allowedTools 执行 `List.copyOf()`，对 metadata 执行 `Map.copyOf()`
    - 紧凑构造器中校验 id/name/systemPrompt 非空非 blank，抛出 IllegalArgumentException
    - _Requirements: 1.1, 1.2, 1.3_

  - [ ] 1.3 实现 AgentRegistryEvent 密封接口
    - 创建 `com.lifepilot.multiagent.model.AgentRegistryEvent` sealed interface
    - permits `AgentRegistered(AgentDefinition definition)` 和 `AgentUnregistered(String agentId)` 两个 record
    - _Requirements: 2.5_

  - [ ]* 1.4 编写 AgentDefinition 属性测试
    - **Property 1: AgentDefinition toBuilder 字段保持不变**
    - **Property 2: AgentDefinition 防御性拷贝**
    - 使用 jqwik `@Property(tries = 100)`
    - **Validates: Requirements 1.1, 1.2, 1.3**

  - [ ]* 1.5 编写 AgentBudget 属性测试和单元测试
    - **Property 3: AgentBudget 到 Budget 转换**
    - 单元测试：DEFAULT/LIGHTWEIGHT/HEAVYWEIGHT 预设常量值验证
    - **Validates: Requirements 1.4, 1.5**

- [ ] 2. 实现注册中心和跨模块变更
  - [ ] 2.1 在 DynamicToolRegistry 新增 unregisterBuiltinTool 方法
    - 在 `com.lifepilot.tool.registry.DynamicToolRegistry` 中新增 `unregisterBuiltinTool(String toolId)` 方法
    - 从 builtinTools ConcurrentHashMap 中移除指定 toolId 的工具
    - 跨模块变更，向后兼容（新增方法）
    - _Requirements: 3.2（跨模块接口变更表：DynamicToolRegistry）_

  - [ ] 2.2 实现 AgentRegistry 注册中心
    - 创建 `com.lifepilot.multiagent.registry.AgentRegistry`
    - 使用 `ConcurrentHashMap<String, AgentDefinition>` 存储
    - 实现 `register(AgentDefinition)` 方法：校验 ID 非空、Builtin 不允许被 Builtin 覆盖、MarkdownDefined 可覆盖 Builtin、发布 AgentRegistered 事件
    - 实现 `unregister(String agentId)` 方法：移除 Agent、发布 AgentUnregistered 事件
    - 实现 `find(String agentId)` 返回 `Optional<AgentDefinition>`
    - 实现 `listAll()` 返回 `List.copyOf()` 不可变列表
    - 实现 `unregisterBySource(Class<? extends AgentSource> sourceType)` 批量注销，返回注销数量
    - 依赖注入：`ApplicationEventPublisher`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9_

  - [ ]* 2.3 编写 AgentRegistry 属性测试
    - **Property 4: AgentRegistry 注册优先级**（Builtin vs MarkdownDefined 覆盖规则）
    - **Property 5: AgentRegistry CRUD 一致性**（register/find/listAll/unregister 往返）
    - **Property 6: AgentRegistry 按来源批量注销**
    - 使用 jqwik `@Property(tries = 100)`
    - **Validates: Requirements 2.3, 2.4, 2.6, 2.7, 2.8, 2.9**

  - [ ]* 2.4 编写 AgentRegistry 单元测试
    - 测试空 ID 注册返回 false + WARN 日志
    - 测试事件发布（AgentRegistered / AgentUnregistered）
    - 测试并发注册安全性
    - **Validates: Requirements 2.2, 2.5**

- [ ] 3. Checkpoint - 确认数据模型层和注册中心
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 4. 实现 HandoffTool 工厂和 AgentExecutor 执行器
  - [ ] 4.1 扩展 AgentRequest 新增 SubAgent 支持字段
    - 修改 `com.lifepilot.agent.model.AgentRequest` record，新增可选字段：
      - `@Nullable String systemPrompt`（Agent 专属 System Prompt）
      - `@Nullable Budget budget`（独立预算，覆盖默认）
      - `@Nullable String parentTraceId`（父 traceId，轨迹关联）
      - `int depth`（委托深度，默认 0）
      - `@Nullable String preferredProvider`（偏好 LLM Provider）
      - `@Nullable List<String> allowedToolIds`（工具白名单）
    - 保留兼容现有构造器（message, sessionId, channel）
    - 跨模块变更，向后兼容（新增字段 + 兼容构造器）
    - _Requirements: 5.1, 5.2, 5.3, 5.4_

  - [ ] 4.2 实现 HandoffToolFactory 委托工具工厂
    - 创建 `com.lifepilot.multiagent.execution.HandoffToolFactory`
    - 实现 `createHandoffTool(AgentDefinition)` 方法，返回 `BuiltinTool` 实例
    - 工具 ID 格式 `handoff_to_{agentId}`
    - description 包含 Agent 的 name 和 description
    - 输入 Schema 定义 task（必填）和 context（选填）两个参数
    - executor lambda 内部委托 AgentExecutor.execute()
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

  - [ ] 4.3 实现 AgentExecutor 执行器
    - 创建 `com.lifepilot.multiagent.execution.AgentExecutor`
    - 实现 `execute(AgentDefinition, task, context, parentState)` 方法
    - 执行流程：
      1. 检查 parentState.depth + 1 ≤ maxDelegationDepth
      2. 创建独立 Budget（AgentBudget.toAgentBudget()）
      3. 构建 allowedToolIds（canDelegate=false 时排除 handoff_to_* 工具）
      4. 构建 AgentRequest（含 systemPrompt, budget, parentTraceId, depth+1, preferredProvider, allowedToolIds）
      5. 调用 agentLoop.run(subRequest)
      6. 将 AgentResponse 转换为 Action.SubAgentResult
    - 异常捕获：返回 success=false 的 SubAgentResult，包含错误信息
    - 依赖注入：AgentLoop, DynamicToolRegistry, MultiAgentProperties
    - _Requirements: 4.4, 4.5, 4.6, 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_

  - [ ]* 4.4 编写 HandoffToolFactory 属性测试
    - **Property 7: HandoffTool 创建正确性**（工具 ID 格式、description 包含 name 和 description）
    - 使用 jqwik `@Property(tries = 100)`
    - **Validates: Requirements 4.1, 4.3**

  - [ ]* 4.5 编写 AgentExecutor 属性测试和单元测试
    - **Property 8: 委托深度限制**（depth+1 > maxDelegationDepth 时返回 success=false）
    - **Property 9: 子 Agent 工具过滤**（canDelegate=false 排除 handoff_to_* 工具）
    - **Property 10: AgentExecutor 异常捕获**（AgentLoop 异常不传播）
    - 单元测试：preferredProvider 传递验证、allowedTools 中不存在的工具 ID 跳过 + WARN 日志
    - **Validates: Requirements 4.5, 4.6, 5.4, 5.6**

- [ ] 5. Checkpoint - 确认 HandoffTool 和 AgentExecutor
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 6. 实现 Markdown Agent 定义解析与加载
  - [ ] 6.1 实现 AgentMarkdownParser 解析器
    - 创建 `com.lifepilot.multiagent.loader.AgentMarkdownParser`
    - 实现 `parse(String content, Path filePath)` 方法，返回 `Optional<AgentDefinition>`
    - 分离 YAML Frontmatter（`---` 分隔符）和 Markdown 正文
    - Frontmatter → 结构化字段（id, name, description, allowed-tools, can-delegate, budget, preferred-provider, metadata）
    - Markdown 正文 → systemPrompt
    - 校验必填字段（id, name, description），缺失返回 Optional.empty() + WARN 日志
    - 正文为空返回 Optional.empty() + WARN 日志
    - budget 字段缺失时使用 BudgetDefaults 配置的默认值
    - source 设为 `AgentSource.MarkdownDefined(filePath, lastModified)`
    - _Requirements: 6.2, 6.3, 6.4, 6.5_

  - [ ] 6.2 实现 AgentMarkdownLoader 加载器（含热加载）
    - 创建 `com.lifepilot.multiagent.loader.AgentMarkdownLoader`
    - 实现 `loadFromDirectory(Path directory)` 方法：遍历 `.md` 文件 → 调用 parser.parse() → 注册到 AgentRegistry
    - 实现 `loadFromFile(Path file)` 方法：读取文件内容 → 调用 parser.parse()
    - 目录不存在时自动创建 + INFO 日志
    - 实现 `startHotReload()` 方法：使用 ScheduledExecutorService 定期扫描
    - 热加载检测逻辑：比较文件 lastModified 时间戳，检测新增/修改/删除
    - 新增/修改 → 重新解析并更新 AgentRegistry
    - 删除 → 调用 AgentRegistry.unregister() 注销 MarkdownDefined Agent
    - 实现 `stopHotReload()` 方法：关闭 ScheduledExecutorService
    - 依赖注入：AgentRegistry, AgentMarkdownParser, MultiAgentProperties
    - _Requirements: 6.1, 6.6, 6.7, 6.8, 6.9_

  - [ ]* 6.3 编写 AgentMarkdownParser 属性测试和单元测试
    - **Property 11: Markdown Agent 定义解析往返**（序列化 → 解析 → 字段一致）
    - 单元测试：缺少必填字段跳过、空正文跳过、特殊字符处理、budget 缺失使用默认值
    - **Validates: Requirements 6.2, 6.4, 6.5**

  - [ ]* 6.4 编写 AgentMarkdownLoader 单元测试
    - 测试目录不存在时自动创建
    - 测试空目录返回空列表
    - 测试热加载文件变更检测（新增/修改/删除）
    - **Validates: Requirements 6.1, 6.6, 6.7, 6.8, 6.9**

- [ ] 7. 实现 AgentToToolBridge 事件驱动桥接
  - [ ] 7.1 实现 AgentToToolBridge
    - 创建 `com.lifepilot.multiagent.bridge.AgentToToolBridge`
    - 使用 `@EventListener` 监听 `AgentRegistryEvent.AgentRegistered` 和 `AgentRegistryEvent.AgentUnregistered` 事件
    - AgentRegistered 事件：如果 `MultiAgentProperties.registerHandoffTools` 为 true，调用 HandoffToolFactory 创建 BuiltinTool 并注册到 DynamicToolRegistry
    - AgentUnregistered 事件：调用 `DynamicToolRegistry.unregisterBuiltinTool("handoff_to_" + agentId)` 注销对应工具
    - 注册/注销失败时记录 WARN 日志，不影响 AgentRegistry 状态
    - 依赖注入：DynamicToolRegistry, HandoffToolFactory, MultiAgentProperties
    - _Requirements: 3.1, 3.2, 3.3_

  - [ ]* 7.2 编写 AgentToToolBridge 单元测试
    - 测试 AgentRegistered 事件触发 HandoffTool 注册
    - 测试 AgentUnregistered 事件触发 HandoffTool 注销
    - 测试 registerHandoffTools=false 时不注册
    - **Validates: Requirements 3.1, 3.2, 3.3**

- [ ] 8. 实现配置、自动装配和预设 Agent
  - [ ] 8.1 实现 MultiAgentProperties 配置属性类
    - 创建 `com.lifepilot.multiagent.config.MultiAgentProperties`
    - `@ConfigurationProperties(prefix = "lifepilot.agent.multi-agent")`
    - 字段：enabled(true)、maxDelegationDepth(2)、agentDefinitionsPath("~/.lifepilot/agents/")、registerHandoffTools(true)
    - 嵌套 HotReload 类：enabled(true)、scanIntervalSeconds(5)
    - 嵌套 BudgetDefaults 类：defaultMaxTokens(16000)、defaultMaxSteps(15)、defaultTimeoutSeconds(180)
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

  - [ ] 8.2 创建预设 Agent Markdown 定义文件
    - 创建 `src/main/resources/preset-agents/writer.md`（写作专家）
      - System Prompt：结构化写作原则、风格匹配、素材组织
      - allowed-tools: [memory-search, knowledge-search]，budget: DEFAULT，canDelegate: false
    - 创建 `src/main/resources/preset-agents/life-coach.md`（生活教练）
      - System Prompt：Socratic 提问法、行为模式分析、正向反馈
      - allowed-tools: [memory-search, knowledge-search, skill.todo-query, skill.schedule-query, skill.habit-query]，budget: HEAVYWEIGHT，canDelegate: false
    - 创建 `src/main/resources/preset-agents/planner.md`（规划专家）
      - System Prompt：Eisenhower 矩阵、时间块法、能量管理
      - allowed-tools: [memory-search, skill.todo-query, skill.schedule-query, skill.habit-query]，budget: DEFAULT，canDelegate: false
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

  - [ ] 8.3 实现 MultiAgentAutoConfiguration 自动配置
    - 创建 `com.lifepilot.multiagent.config.MultiAgentAutoConfiguration`
    - `@AutoConfiguration(after = {AgentAutoConfiguration.class, SkillAutoConfiguration.class})`
    - `@ConditionalOnProperty(prefix = "lifepilot.agent.multi-agent", name = "enabled", havingValue = "true", matchIfMissing = true)`
    - 注册 Bean：AgentRegistry、AgentExecutor、HandoffToolFactory、AgentMarkdownParser、AgentMarkdownLoader、AgentToToolBridge、ToolDiscoveryService
    - `@EventListener(ApplicationReadyEvent.class)` 中：
      1. 先从 classpath `preset-agents/` 加载预设 Agent（以 Builtin 来源注册）
      2. 再从 agentDefinitionsPath 加载用户自定义 Markdown Agent（以 MarkdownDefined 来源注册）
      3. 如果 hotReload.enabled=true，启动热加载
    - _Requirements: 9.1, 9.2, 9.3, 9.4_

  - [ ] 8.4 添加 application.yml 配置项
    - 在 `src/main/resources/application.yml` 中添加 `lifepilot.agent.multi-agent` 配置段
    - 包含所有配置项及默认值
    - _Requirements: 8.2_

  - [ ]* 8.5 编写 MultiAgentProperties 单元测试
    - 验证所有默认值正确
    - **Validates: Requirements 8.2, 8.3, 8.4**

- [ ] 9. Checkpoint - 确认配置、自动装配和预设 Agent
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 10. Skill 系统清理（L1 回归）
  - [ ] 10.1 清理 SkillDefinition 和 SkillToToolBridge
    - 从 `SkillDefinition` record 移除 `preferredProviderId` 字段
    - 修改 `SkillToToolBridge`：移除 SubAgent 激活路径，仅桥接 L1 确定性 Skill 执行（HttpAction / ShellAction / ChainAction / TemplateAction）
    - 修复所有因字段移除导致的编译错误（SkillYamlLoader、SkillLifecycleManager 等）
    - _Requirements: 10.1, 10.2_

  - [ ] 10.2 清理 SkillLifecycleManager 和 SubAgentFactory
    - 从 `SkillLifecycleManager` 移除 SubAgent 委托逻辑
    - 在 `SubAgentFactory` 类上标记 `@Deprecated`，添加 Javadoc 说明职责已迁移到 AgentExecutor
    - 确保现有 Skill YAML 定义文件不包含 SubAgent 配置时正常加载
    - _Requirements: 10.3, 10.4, 10.5_

  - [ ]* 10.3 编写 Skill 系统清理验证测试
    - 验证 SkillToToolBridge 不再委托 SubAgent
    - 验证 SkillDefinition 无 preferredProviderId 字段
    - 验证现有 Skill YAML 正常加载（向后兼容）
    - **Validates: Requirements 10.1, 10.2, 10.3, 10.5**

- [ ] 11. 实现 ToolDiscoveryService 工具发现能力
  - [ ] 11.1 实现 ToolDiscoveryService
    - 创建 `com.lifepilot.multiagent.discovery.ToolDiscoveryService`
    - 定义 `ToolSummary` record：toolId, name, description, sourceType
    - 实现 `listAvailableTools()` 方法：从 DynamicToolRegistry.getAllTools() 获取所有工具
    - 排除 ID 以 `handoff_to_` 开头的 HandoffTool（避免循环引用）
    - 按来源类型分类：BuiltinTool → "builtin"、YamlTool → "skill"、McpTool → "mcp"
    - 依赖注入：DynamicToolRegistry
    - _Requirements: 11.1, 11.2, 11.3, 11.4_

  - [ ]* 11.2 编写 ToolDiscoveryService 属性测试
    - **Property 12: 工具发现完整性**（非 HandoffTool 工具全部返回）
    - **Property 13: 工具发现排除 HandoffTool**（handoff_to_* 工具不出现在结果中）
    - 单元测试：空注册表返回空列表、按来源分组验证
    - **Validates: Requirements 11.1, 11.2, 11.3**

- [ ] 12. 集成测试
  - [ ]* 12.1 编写 MultiAgent_AgentLoop_集成测试
    - `@SpringBootTest` 验证 Spring Context 加载、Bean 注入链完整
    - 验证预设 Agent 注册（先 Builtin 后 MarkdownDefined）
    - 验证 Agent 注册 → HandoffTool 创建 → AgentExecutor 执行完整链路
    - **Validates: Requirements 9.1, 9.2, 9.3, 9.4**

  - [ ]* 12.2 编写 MultiAgent_SkillCleanup_集成测试
    - 验证 SkillDefinition 移除 preferredProviderId 后编译通过
    - 验证 SkillToToolBridge 不再委托 SubAgent
    - **Validates: Requirements 10.1, 10.2, 10.3**

  - [ ]* 12.3 编写 MultiAgent_禁用_集成测试
    - `@SpringBootTest` + `lifepilot.agent.multi-agent.enabled=false`
    - 验证无 AgentRegistry、AgentExecutor 等 Bean 注册
    - **Validates: Requirements 8.5**

- [ ] 13. Final checkpoint - 确认所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties (13 properties from design)
- 跨模块变更：DynamicToolRegistry.unregisterBuiltinTool（task 2.1）、AgentRequest 扩展（task 4.1）、Skill 系统清理（task 10）
- 预设 Agent 的 Markdown 定义文件作为 classpath 资源打包在 JAR 中（src/main/resources/preset-agents/）
- 热加载使用 ScheduledExecutorService，扫描间隔由 MultiAgentProperties.hotReload.scanIntervalSeconds 控制
- 所有代码遵循编码规范：中文注释/Javadoc/测试方法名/日志/异常消息，@author zsg，@since 2026-02-27
- 使用 jqwik 进行属性测试，每个属性 `@Property(tries = 100)`
