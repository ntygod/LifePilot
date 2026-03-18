# Implementation Plan: 记忆系统增强

## Overview

基于记忆系统审计的 6 项增强需求，按模块独立性和依赖关系拆分为 8 个顶层任务。
每个任务对应一个可独立提交的 git commit，包含实现代码和对应测试。
执行顺序：配置层 → 记忆层新组件 → Agent 层变更 → Skill 层变更 → 集成验证。

> **重要说明**：本 spec 基于 agentic-memory-tool 合并后的代码重新审查。
> agentic-memory-tool 对 MemorySkillProvider（7 工具结构）、ContextAssembler（8 参数完整版构造器）
> 和 AgentAutoConfiguration 做了大幅重构。以下任务已针对新代码基线调整。

## Tasks

- [ ] 1. MemoryProperties 新增 EpisodicCleanup 配置 + application.yml 声明
  - 在 MemoryProperties 中新增 EpisodicCleanup 嵌套静态内部类（cron / retentionDays / maxCleanupPerRun）
  - 在 application.yml 的 lifepilot.memory 节点下追加 episodic-cleanup 配置项及默认值
  - _Requirements: 5.4_

- [ ] 2. PreferenceConsolidator 实现 + ConsolidationPipeline 集成
  - [ ] 2.1 新增 PreferenceSyncStats record 和 PreferenceConsolidator 类
    - 在 com.lifepilot.memory.consolidation 包下创建 PreferenceSyncStats record（created, reinforced, deleted）
    - 创建 PreferenceConsolidator 类，实现 L3 PREFERENCE 实体与 L4 PreferenceRule 的对比同步逻辑
    - 同步逻辑：L3有+L4无→savePreference，L3有+L4有→reinforcePreference，L3归档+L4有→deletePreference
    - 单条同步失败时捕获异常、记录 WARN 日志、继续处理剩余条目
    - _Requirements: 4.2, 4.3, 4.4, 4.5_

  - [ ] 2.2 修改 ConsolidationPipeline 构造器和 consolidate() 方法
    - 构造器末尾追加 @Nullable PreferenceConsolidator 参数（当前 3 参数→4 参数）
    - consolidate() 方法在语义巩固和程序巩固之后追加第三步偏好同步
    - preferenceConsolidator 为 null 时跳过，异常时记录 WARN 日志不影响前两步结果
    - _Requirements: 4.1, 4.5_

  - [ ] 2.3 修改 MemoryAutoConfiguration Bean 注册
    - 注册 PreferenceConsolidator Bean（@ConditionalOnBean SemanticMemory + ProceduralMemory）
    - 修改 ConsolidationPipeline Bean 注册，注入 @Nullable PreferenceConsolidator
    - _Requirements: 4.1_

  - [ ]* 2.4 编写 PreferenceConsolidator 单元测试
    - 测试新建/强化/删除三种同步场景
    - 测试空集合边界条件
    - 测试单条失败不影响整体
    - _Requirements: 4.2, 4.3, 4.4_

  - [ ]* 2.5 编写 PreferenceConsolidator 属性测试（jqwik）
    - **Property 6: 偏好同步后 L4 规则与 L3 当前有效 PREFERENCE 实体一致**
    - **Validates: Requirements 4.2, 4.3, 4.4, 4.5**


- [ ] 3. EpisodicCleanupJob 实现
  - [ ] 3.1 新增 EpisodicCleanupJob 类
    - 在 com.lifepilot.memory.episodic 包下创建 EpisodicCleanupJob
    - 依赖 EpisodicMemory + JdbcTemplate + MemoryProperties
    - @Scheduled(cron) 定时执行：JdbcTemplate 查询过期且非 pinned 对话 ID，逐个调用 episodicMemory.delete()
    - 记录 INFO 日志（清理数量+耗时），异常时记录 WARN 日志并终止本次清理
    - _Requirements: 5.1, 5.2, 5.3, 5.5, 5.6_

  - [ ] 3.2 在 MemoryAutoConfiguration 注册 EpisodicCleanupJob Bean
    - @ConditionalOnBean(EpisodicMemory.class)，注入 EpisodicMemory + JdbcTemplate + MemoryProperties
    - _Requirements: 5.1_

  - [ ]* 3.3 编写 EpisodicCleanupJob 单元测试
    - 测试正常清理、pinned 对话保留、maxCleanupPerRun 限制、异常处理
    - _Requirements: 5.2, 5.3, 5.4, 5.6_

  - [ ]* 3.4 编写 EpisodicCleanupJob 属性测试（jqwik）
    - **Property 7: 清理任务保留 pinned 对话且删除过期非 pinned 对话**
    - **Validates: Requirements 5.2, 5.3**

- [ ] 4. Checkpoint — 记忆层变更验证
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 5. ContextAssembler 死代码移除 + DataRedactor 激活 + L4 偏好整合
  - [ ] 5.1 移除 ContextAssembler 死代码
    - 删除 buildMinimalContext(ReactAgentState) 私有方法（当前 Line 178）
    - 删除 safeAllocate 四参数重载（当前 Line 387）— 仅保留五参数版本
    - 全量编译确认无编译错误
    - _Requirements: 2.3, 2.4, 2.5_

  - [ ] 5.2 激活 DataRedactor 脱敏
    - 在 buildEnhancedUserPrompt() 调用链中，对 formatUserProfileSection() 和 formatConversationHistorySection() 的输出调用 dataRedactor.redact()
    - dataRedactor == null 时跳过脱敏，redact() 抛异常时降级使用原始文本并记录 WARN 日志
    - _Requirements: 2.1, 2.2_

  - [ ] 5.3 ContextAssembler 构造器新增 ProceduralMemory 参数 + L4 偏好查询逻辑
    - 完整版构造器新增 @Nullable ProceduralMemory 参数（memoryProperties 之后、promptRegistry 之前，8→9 参数）
    - 基础版构造器中 proceduralMemory 设为 null
    - 修改 safeGetUserProfile()：查询 L4 高置信度偏好规则（confidence >= 0.7），L4 优先去重同名 L3 PREFERENCE 实体
    - 格式化 L4 规则为 "[偏好规则] {key}: {value}（置信度: {confidence}）"
    - proceduralMemory == null 或查询异常时降级为仅使用 L3 实体
    - _Requirements: 6.1, 6.2, 6.3, 6.4_

  - [ ] 5.4 同步修改 AgentAutoConfiguration 中 ContextAssembler 构造调用点
    - 注入 @Nullable ProceduralMemory Bean（当前 contextAssembler bean 方法已有 8 个参数，新增第 9 个）
    - _Requirements: 6.4_

  - [ ]* 5.5 编写 ContextAssembler 死代码移除验证测试
    - 反射验证 buildMinimalContext 和 safeAllocate 四参数重载方法不存在
    - _Requirements: 2.3, 2.4_

  - [ ]* 5.6 编写 ContextAssembler 脱敏属性测试（jqwik）
    - **Property 3: DataRedactor 脱敏一致性**
    - **Validates: Requirements 2.1, 2.2**

  - [ ]* 5.7 编写 ContextAssembler L4 偏好去重属性测试（jqwik）
    - **Property 8: 用户画像 L4 优先去重**
    - **Validates: Requirements 6.1, 6.2, 6.3**

  - [ ]* 5.8 编写 ContextAssembler L4 偏好规则格式化属性测试（jqwik）
    - **Property 9: L4 偏好规则格式化一致性**
    - **Validates: Requirements 6.3**

- [ ] 6. ReactAgentLoop L4 反馈闭环
  - [ ] 6.1 ReactAgentLoop 构造器新增 ProceduralMemory + IntentMatcher 参数
    - 构造器末尾追加 @Nullable ProceduralMemory 和 @Nullable IntentMatcher 两个参数（23→25 个）
    - 新增对应字段赋值
    - _Requirements: 3.1_

  - [ ] 6.2 在 executeToolCall() 中实现 L4 执行结果记录
    - 工具执行成功后，调用 intentMatcher.match() 匹配操作模板
    - 匹配成功时调用 proceduralMemory.recordExecution(templateId, success)
    - proceduralMemory/intentMatcher 为 null 时跳过，异常时记录 WARN 日志不阻塞主循环
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

  - [ ] 6.3 同步修改 AgentAutoConfiguration 中 ReactAgentLoop 构造调用点
    - 注入 @Nullable ProceduralMemory 和 @Nullable IntentMatcher Bean
    - _Requirements: 3.1_

  - [ ]* 6.4 编写 ReactAgentLoop L4 反馈单元测试
    - 测试匹配成功记录、无匹配跳过、异常不阻塞三种场景
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

  - [ ]* 6.5 编写 ReactAgentLoop L4 反馈属性测试（jqwik）
    - **Property 5: L4 反馈闭环异常不阻塞 Agent 主循环**
    - **Validates: Requirements 3.3, 3.4**

- [ ] 7. MemorySkillProvider 新增 query-at-time 工具
  - [ ] 7.1 在 MemorySkillProvider 中注册 builtin.memory.query-at-time 工具
    - registerTools() 中新增工具定义（当前已有 7 个工具，query-at-time 为第 8 个）
    - 输入 timestamp（ISO 8601 必填）+ entityType（可选过滤）
    - 实现执行逻辑：解析 timestamp → 调用 semanticMemory.queryAtTime(instant) → 可选按 entityType 过滤 → 格式化返回
    - provide() 方法的 tools 列表中添加 "builtin.memory.query-at-time"
    - 错误处理：timestamp 格式无效返回错误提示，查询异常返回 "查询失败" 提示
    - _Requirements: 1.1, 1.2, 1.3_

  - [ ]* 7.2 编写 MemorySkillProvider query-at-time 单元测试
    - 测试正常查询、空结果、无效时间格式、entityType 过滤
    - _Requirements: 1.1, 1.2, 1.3_

  - [ ]* 7.3 编写 MemorySkillProvider query-at-time 属性测试（jqwik）
    - **Property 1: queryAtTime 返回的实体在指定时间点有效**
    - **Validates: Requirements 1.1**

  - [ ]* 7.4 编写 queryAtTime 结果格式化属性测试（jqwik）
    - **Property 2: queryAtTime 结果格式化包含所有实体信息**
    - **Validates: Requirements 1.2**

- [ ] 8. 集成测试 + 最终验证
  - [ ]* 8.1 编写 ConsolidationPipeline 偏好同步集成测试
    - 验证巩固管线三步骤顺序执行
    - 验证 PreferenceConsolidator 为 null 时跳过偏好同步步骤
    - _Requirements: 4.1_

  - [ ]* 8.2 编写 EpisodicCleanupJob 集成测试
    - 使用内存 SQLite 验证端到端清理流程（写入→过期→清理→验证）
    - _Requirements: 5.1, 5.2, 5.3_

  - [ ]* 8.3 编写 MemoryAutoConfiguration 新 Bean 集成测试
    - 验证 PreferenceConsolidator 和 EpisodicCleanupJob Bean 正确注册
    - _Requirements: 4.1, 5.1_

  - [ ]* 8.4 编写 recordExecution 加权平均属性测试（jqwik）
    - **Property 4: recordExecution 更新 successRate 的加权平均不变量**
    - **Validates: Requirements 3.2**

  - [ ] 8.5 Final checkpoint — 全量编译 + 测试通过
    - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- 技术栈：Java 22 + Spring Boot 3.5.x + Spring AI 1.1.2 + SQLite + JUnit 5 + jqwik
- 编码规范：中文注释/Javadoc/日志/测试方法名，@author zsg，配置外部化
- 每个任务对应一个 git commit，提交消息遵循 `<type>(<scope>): <中文描述>` 格式
- Property tests validate universal correctness properties using jqwik (@Property tries=100)
- 执行顺序确保依赖关系：配置层(1) → 记忆层(2,3) → Agent层(5,6) → Skill层(7) → 集成验证(8)
- **代码基线**：基于 agentic-memory-tool 合并后的 develop 分支
  - MemorySkillProvider 已有 7 个工具（search/recall/search-docs/create/update/delete/tag）
  - ContextAssembler 完整版构造器 8 参数（config, workingMemory, tokenBudgetAllocator, dataRedactor, semanticMemory, passiveNotificationQueue, memoryProperties, promptRegistry）
  - AgentAutoConfiguration 的 contextAssembler bean 已适配 8 参数版本
  - buildMinimalContext 和 safeAllocate 四参数重载仍存在（需清理）
