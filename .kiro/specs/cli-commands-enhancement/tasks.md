# Implementation Plan: CLI Commands Enhancement

## Overview

扩展 QuickCommand 和 CliCompleter，补齐架构文档 §8 规划的完整子命令集。变更集中在两个文件，沿用现有 switch 分支 + ResponseRenderer 输出模式。按 domain 分组递增实现，每组实现后立即补充测试，最后统一更新补全树和用法帮助。

## Tasks

- [x] 1. QuickCommand 新增 YamlSkillLoader 依赖注入
  - 在 QuickCommand 构造函数中新增 `YamlSkillLoader` 参数并存为字段
  - 更新 Spring 自动配置中 QuickCommand Bean 的构造参数
  - _Requirements: 13.1_

- [x] 2. 实现 todo/schedule/habit 子命令扩展
  - [x] 2.1 实现 todo done 和 todo delete 子命令
    - 在 `handleBuiltinTool` 的 switch 中新增 `done` 和 `delete` 分支
    - `done` 分支：校验 domain 为 todo，校验参数存在，调用 `builtin.todo.done` 工具
    - `delete` 分支：校验 domain 为 todo，校验参数存在，调用 `builtin.todo.delete` 工具
    - 缺少参数时输出用法提示
    - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3_

  - [x] 2.2 实现 schedule today、tomorrow、add 子命令
    - 在 `handleBuiltinTool` 的 switch 中新增 `today`、`tomorrow`、`add`（schedule domain）分支
    - `today` 分支：校验 domain 为 schedule，用 `LocalDate.now()` 计算日期，调用 `builtin.schedule.list`
    - `tomorrow` 分支：校验 domain 为 schedule，用 `LocalDate.now().plusDays(1)` 计算日期，调用 `builtin.schedule.list`
    - `add` 分支（schedule domain）：校验参数存在，调用 `builtin.schedule.create`
    - _Requirements: 3.1, 3.2, 3.3, 4.1, 4.2_

  - [x] 2.3 实现 habit checkin 和 habit status 子命令
    - 在 `handleBuiltinTool` 的 switch 中新增 `checkin` 和 `status` 分支
    - `checkin` 分支：校验 domain 为 habit，校验参数存在，拼接多词习惯名，调用 `builtin.habit.checkin`
    - `status` 分支：校验 domain 为 habit，调用 `builtin.habit.status`
    - _Requirements: 5.1, 5.2, 5.3, 6.1, 6.2_

  - [ ]* 2.4 编写 builtin tool 命令分派属性测试
    - **Property 1: Builtin tool command dispatch**
    - **Validates: Requirements 1.1, 2.1, 4.1, 5.1**

  - [ ]* 2.5 编写 todo/schedule/habit 子命令单元测试
    - 测试 todo done/delete 缺少参数场景
    - 测试 schedule today/tomorrow 传入正确日期
    - 测试 habit status 无习惯时的提示信息
    - _Requirements: 1.3, 2.3, 3.1, 3.2, 6.2_

- [x] 3. 实现 llm 子命令扩展
  - [x] 3.1 实现 llm add 子命令
    - 在 `handleLlm` 的 switch 中新增 `add` 分支
    - 校验参数数量（需要 providerId、type、modelName、apiKey 四个参数）
    - 构造 ProviderConfig 并调用 `ProviderRegistry.register()`
    - 重复 ID 由外层 catch 捕获
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

  - [x] 3.2 实现 llm remove 子命令
    - 在 `handleLlm` 的 switch 中新增 `remove` 分支
    - 先通过 `getConfig()` 检查存在性，不存在则输出错误
    - 存在则调用 `deregister()` 并输出成功
    - _Requirements: 8.1, 8.2, 8.3_

  - [x] 3.3 实现 llm enable 和 llm disable 子命令
    - 在 `handleLlm` 的 switch 中新增 `enable` 和 `disable` 分支
    - 获取当前 config，检查是否已处于目标状态（幂等处理）
    - deregister 旧 config，用新 enabled 值构造新 ProviderConfig 并 register
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

  - [ ]* 3.4 编写 llm 子命令属性测试
    - **Property 2: LLM add creates and registers provider**
    - **Property 3: LLM remove deregisters provider**
    - **Property 4: LLM enable/disable toggles enabled flag**
    - **Property 5: LLM toggle idempotence**
    - **Validates: Requirements 7.1, 7.2, 8.1, 9.1, 9.2, 9.4**

  - [ ]* 3.5 编写 llm 子命令单元测试
    - 测试 llm add 参数不足、重复 ID 场景
    - 测试 llm remove 不存在场景
    - 测试 llm enable 已启用时的幂等提示
    - _Requirements: 7.3, 7.4, 8.2, 9.4_

- [x] 4. Checkpoint — 确认 builtin tool 和 llm 子命令实现正确
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. 实现 mcp 子命令扩展
  - [x] 5.1 实现 mcp add 子命令
    - 在 `handleMcp` 的 switch 中新增 `add` 分支
    - 校验参数数量（需要 name、transport、url 三个参数）
    - 构造 McpServerConfig 并调用 `McpServerRegistry.connectServer()`
    - _Requirements: 10.1, 10.2, 10.3_

  - [x] 5.2 实现 mcp remove 子命令
    - 在 `handleMcp` 的 switch 中新增 `remove` 分支
    - 先通过 `getServer()` 检查存在性，不存在则输出错误
    - 存在则调用 `disconnectServer()` 并输出成功
    - _Requirements: 11.1, 11.2, 11.3_

  - [x] 5.3 实现 mcp test 子命令
    - 在 `handleMcp` 的 switch 中新增 `test` 分支
    - 通过 `getClient()` 获取 McpClient，不存在则输出错误
    - 调用 `listTools().join()` 执行连通性检查，记录耗时
    - 成功输出工具数量和延迟，失败输出错误原因
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5_

  - [ ]* 5.4 编写 mcp 子命令属性测试
    - **Property 6: MCP add creates config and initiates connection**
    - **Property 7: MCP remove disconnects server**
    - **Property 8: MCP test performs connectivity check**
    - **Validates: Requirements 10.1, 10.2, 11.1, 12.1, 12.2**

  - [ ]* 5.5 编写 mcp 子命令单元测试
    - 测试 mcp add 参数不足场景
    - 测试 mcp remove 不存在场景
    - 测试 mcp test 不可用和连接失败场景
    - _Requirements: 10.3, 11.2, 12.3, 12.4_

- [x] 6. 实现 skill reload 子命令
  - [x] 6.1 实现 skill reload 子命令
    - 在 `handleSkill` 的 switch 中新增 `reload` 分支
    - 调用 `YamlSkillLoader.loadAll()` 并输出加载数量
    - 异常由外层 catch 捕获
    - _Requirements: 13.1, 13.2, 13.3_

  - [ ]* 6.2 编写 skill reload 单元测试
    - 测试 reload 成功时显示加载数量
    - 测试 reload 异常时显示错误
    - _Requirements: 13.2, 13.3_

- [x] 7. 更新 CliCompleter 补全树和用法帮助
  - [x] 7.1 更新 CliCompleter 补全树
    - 更新 `buildCompleters()` 中 todo 子命令：添加 `done`, `delete`
    - 更新 schedule 子命令：添加 `add`, `today`, `tomorrow`
    - 更新 habit 子命令：添加 `checkin`, `status`
    - 更新 llm 子命令：添加 `add`, `remove`, `enable`, `disable`
    - 更新 mcp 子命令：添加 `add`, `remove`, `test`
    - 更新 skill 子命令：添加 `reload`
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6_

  - [x] 7.2 更新所有 printXxxUsage 方法
    - 更新 `printBuiltinUsage` 按 domain 输出完整子命令列表（todo/schedule/habit 各自不同）
    - 更新 `printLlmUsage` 列出 list/add/remove/enable/disable/test
    - 更新 `printMcpUsage` 列出 list/add/remove/test
    - 更新 `printSkillUsage` 列出 list/info/reload
    - _Requirements: 15.1, 15.2, 15.3_

  - [ ]* 7.3 编写 CliCompleter 补全测试
    - 测试 todo/llm 等命令返回完整子命令集
    - _Requirements: 14.1, 14.4_

  - [ ]* 7.4 编写用法帮助完整性属性测试
    - **Property 9: Usage help completeness**
    - **Validates: Requirements 15.1, 15.2, 15.3**

- [x] 8. Final checkpoint — 全量测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- 仅修改 QuickCommand.java 和 CliCompleter.java 两个文件，不引入新类
- 所有新增子命令复用现有 switch + ResponseRenderer 模式
- 属性测试使用 jqwik 框架，单元测试使用 JUnit 5 + Mockito
- 每个 property test 子任务标注了对应的 design property 编号
