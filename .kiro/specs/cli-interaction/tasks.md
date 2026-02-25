# Implementation Plan: cli-interaction

## Overview

基于已完成的 Agent 引擎、Skill 系统、LLM Router 和 MCP 模块，实现 CLI 交互层。实现顺序：配置与基础设施 → 响应渲染器 → 快速路径 → 命令路由 → 快捷命令 → 交互式对话 → 用户确认服务 → JLine 3 终端增强 → CliShell 主循环与自动配置 → 集成验证。

## Tasks

- [ ] 1. 配置属性与基础设施
  - [ ] 1.1 实现 CliConfigProperties 配置属性类
    - 使用 `@ConfigurationProperties(prefix = "lifepilot.cli")` 注解
    - 包含字段：historyFile、maxHistorySize、chatPrompt、defaultPrompt、streamFlushIntervalMs、confirmationTimeoutSeconds
    - 设置默认值与 design 文档一致
    - _Requirements: 7.1, 7.2, 7.3_

  - [ ] 1.2 在 application.yml 中声明所有 CLI 配置项及默认值
    - 配置前缀 `lifepilot.cli`，键名使用 kebab-case
    - _Requirements: 7.4_

- [ ] 2. 响应渲染器
  - [ ] 2.1 实现 ResponseRenderer（格式化输出到终端）
    - success() 使用 ✅ 前缀，error() 使用 ❌ 前缀
    - table() 输出对齐表格（含表头和数据行）
    - streamToken() 逐 token 输出，streamEnd() 换行
    - info() 输出普通信息
    - 依赖 JLine Terminal 实例
    - _Requirements: 6.2, 6.3, 6.4_

  - [ ]* 2.2 编写 ResponseRenderer 属性测试
    - **Property 11: 输出前缀格式化**
    - **Validates: Requirements 6.2, 6.3**

  - [ ]* 2.3 编写 ResponseRenderer 表格属性测试
    - **Property 12: 表格输出完整性**
    - **Validates: Requirements 6.4**

- [ ] 3. Checkpoint — 确认配置和渲染器编译通过
  - 确保 CliConfigProperties、ResponseRenderer 编译通过，getDiagnostics 无错误，ask the user if questions arise.

- [ ] 4. 快速路径
  - [ ] 4.1 实现 FastPathRunner（CLI 快速路径检测）
    - 静态方法 tryFastPath(String[] args)，检测 --help/-h/--version/-v
    - 匹配时直接输出帮助信息或版本号并返回 true
    - 不匹配返回 false，由调用方继续 Spring 初始化
    - 版本号从 MANIFEST.MF 或 fallback 常量读取
    - _Requirements: 5.1, 5.2, 5.4_

  - [ ]* 4.2 编写 FastPathRunner 属性测试
    - **Property 9: 快速路径检测**
    - **Validates: Requirements 5.1, 5.2, 5.3**

- [ ] 5. 命令路由
  - [ ] 5.1 实现 CommandRouter（命令路由器）
    - 根据首个参数分发到 ChatCommand 或 QuickCommand
    - 空参数默认进入 ChatCommand（对话模式）
    - 未知命令输出可用命令列表和帮助提示
    - _Requirements: 6.1, 6.5, 6.6_

  - [ ]* 5.2 编写 CommandRouter 属性测试
    - **Property 10: 命令路由正确性**
    - **Validates: Requirements 6.1, 6.5**

  - [ ]* 5.3 编写 CommandRouter 未知命令属性测试
    - **Property 13: 未知命令输出帮助**
    - **Validates: Requirements 6.6**

- [ ] 6. Checkpoint — 确认快速路径和命令路由编译通过
  - 确保 FastPathRunner、CommandRouter 编译通过，单元测试通过，ask the user if questions arise.

- [ ] 7. 快捷命令
  - [ ] 7.1 实现 QuickCommand（快捷命令分发器）
    - 支持 todo/schedule/habit 命令，通过 DynamicToolRegistry.resolve() 调用内置工具
    - 支持 llm 命令（list/test），调用 ProviderRegistry
    - 支持 mcp 命令（list），调用 McpServerRegistry
    - 支持 skill 命令（list/info），调用 SkillRegistry
    - 非法子命令或缺少参数时输出 usage 帮助
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10_

  - [ ]* 7.2 编写 QuickCommand 属性测试
    - **Property 4: 非法快捷命令输出 usage**
    - **Validates: Requirements 2.10**

  - [ ]* 7.3 编写 QuickCommand 单元测试
    - 覆盖 todo list/add、schedule list、habit list、llm list/test、mcp list、skill list/info 各子命令
    - Mock DynamicToolRegistry、ProviderRegistry、McpServerRegistry、SkillRegistry
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9_

- [ ] 8. 交互式对话
  - [ ] 8.1 实现 ChatCommand（交互式对话命令）
    - startSession() 启动对话会话，生成 sessionId
    - 读取用户输入，构造 AgentRequest（message、sessionId、channel="cli"）提交给 AgentLoop
    - 支持 /exit、/quit、Ctrl+D 退出会话
    - 支持 /new 创建新会话（新 sessionId）
    - AgentLoop 异常时捕获并通过 ResponseRenderer.error() 输出，保持会话可继续
    - 支持流式响应输出（通过 LlmRouter.stream() 的 Flux<String>）
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_

  - [ ]* 8.2 编写 ChatCommand 属性测试 — AgentRequest 构造
    - **Property 1: AgentRequest 构造正确性**
    - **Validates: Requirements 1.2**

  - [ ]* 8.3 编写 ChatCommand 属性测试 — 会话 ID 不变量
    - **Property 2: 会话 ID 不变量**
    - **Validates: Requirements 1.3**

  - [ ]* 8.4 编写 ChatCommand 属性测试 — 异常不传播
    - **Property 3: 异常不传播**
    - **Validates: Requirements 1.7**

- [ ] 9. Checkpoint — 确认快捷命令和对话模块编译通过
  - 确保 QuickCommand、ChatCommand 编译通过，单元测试通过，ask the user if questions arise.

- [ ] 10. CLI 用户确认服务
  - [ ] 10.1 实现 CliUserConfirmationService（终端用户确认服务）
    - 实现 UserConfirmationService 接口
    - 显示工具名称、风险级别、确认消息，等待用户输入 y/n
    - y/yes 返回 true，n/no/空/其他返回 false
    - Ctrl+C 返回 false
    - 超时（从 CliConfigProperties 读取）返回 false
    - 使用 @Primary 替换 NoOpUserConfirmationService
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

  - [ ]* 10.2 编写 CliUserConfirmationService 属性测试
    - **Property 8: 确认输入解析**
    - **Validates: Requirements 4.2, 4.3**

- [ ] 11. JLine 3 终端交互增强
  - [ ] 11.1 实现 JLine 3 Tab 补全器（CliCompleter）
    - 补全范围：顶层命令（chat/todo/schedule/habit/llm/mcp/skill）和子命令
    - 使用 JLine AggregateCompleter + StringsCompleter 组合
    - _Requirements: 3.1_

  - [ ]* 11.2 编写 Tab 补全属性测试
    - **Property 5: Tab 补全覆盖所有命令**
    - **Validates: Requirements 3.1**

  - [ ] 11.3 实现命令历史持久化
    - 使用 JLine FileHistory，路径从 CliConfigProperties 读取
    - 最大条数从配置读取（默认 1000）
    - 历史文件路径不可写时降级为内存历史，记录 WARN 日志
    - _Requirements: 3.2_

  - [ ]* 11.4 编写命令历史 round-trip 属性测试
    - **Property 6: 命令历史 round-trip**
    - **Validates: Requirements 3.2**

  - [ ] 11.5 实现语法高亮器（CliHighlighter）
    - 对命令关键字和参数进行语法高亮
    - 区分命令名、子命令和参数
    - _Requirements: 3.3_

  - [ ] 11.6 实现多行输入支持
    - 以 `\` 结尾或 `"""` 包裹时进入多行输入模式
    - 解析后拼接为完整消息
    - _Requirements: 3.4_

  - [ ]* 11.7 编写多行输入属性测试
    - **Property 7: 多行输入拼接**
    - **Validates: Requirements 3.4**

  - [ ] 11.8 实现自定义提示符
    - 对话模式显示 chatPrompt（默认 `lifepilot> `），普通模式显示 defaultPrompt（默认 `$ `）
    - 提示符文本从 CliConfigProperties 读取
    - _Requirements: 3.5_

- [ ] 12. Checkpoint — 确认 JLine 增强和确认服务编译通过
  - 确保 CliCompleter、CliHighlighter、CliUserConfirmationService 编译通过，单元测试通过，ask the user if questions arise.

- [ ] 13. CliShell 主循环与自动配置
  - [ ] 13.1 实现 CliShell（JLine 3 交互式 Shell 主循环）
    - 实现 CommandLineRunner，Spring 容器启动后自动进入交互循环
    - 初始化 JLine Terminal + LineReader，配置 Completer、Highlighter、History
    - 根据 args 决定进入对话模式或执行单次命令后退出
    - 主循环：读取输入 → CommandRouter 分发 → 输出结果
    - Ctrl+C 提示 "输入 /exit 退出"，Ctrl+D 优雅退出
    - _Requirements: 1.1, 1.5, 3.1, 3.2, 3.3, 3.5_

  - [ ] 13.2 修改 LifePilotApplication.main()，在 SpringApplication.run() 前插入 FastPathRunner.tryFastPath() 调用
    - 匹配快速路径时 System.exit(0)，否则继续正常启动
    - _Requirements: 5.3, 5.4_

  - [ ] 13.3 实现 CliAutoConfiguration（Spring Boot 自动配置）
    - 注册 Bean：CliConfigProperties、ResponseRenderer、CommandRouter、ChatCommand、QuickCommand、CliUserConfirmationService、CliShell
    - CliUserConfirmationService 使用 @Primary 替换 NoOpUserConfirmationService
    - 条件装配：@ConditionalOnProperty("lifepilot.cli.enabled", matchIfMissing = true)
    - _Requirements: 4.5, 7.1_

  - [ ]* 13.4 编写 CliAutoConfiguration 集成测试
    - 验证所有 CLI Bean 正确注册
    - 验证 CliUserConfirmationService 替换 NoOpUserConfirmationService
    - _Requirements: 4.5, 7.1_

  - [ ]* 13.5 编写 CliShell_AgentLoop 集成测试
    - 验证对话模式用户消息正确传递到 AgentLoop
    - 验证快捷命令 todo list 正确调用工具
    - Mock AgentLoop 避免真实 LLM 调用
    - _Requirements: 1.2, 2.1_

- [ ] 14. Final checkpoint — 确保所有测试通过
  - 确保所有编译通过，所有单元测试和集成测试通过，ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- 每个子任务完成后独立 git commit，遵循 `<type>(<scope>): <中文描述>` 格式
- 所有代码遵循编码规范：中文注释/Javadoc/日志/异常消息/测试方法名，英文类名/方法名/变量名
- 所有类级别 Javadoc 包含 @author zsg 和 @since 日期
- 所有服务通过 @Bean 注册在 CliAutoConfiguration 中，不使用 @Service/@Component
- 属性测试使用 jqwik，每个属性测试标注对应的设计属性编号
- 每个 Correctness Property 对应一个独立的属性测试子任务
- 快捷命令通过 DynamicToolRegistry.resolve() 调用内置工具，保持工具调用统一入口
