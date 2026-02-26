# Implementation Plan: 代码执行沙箱 (Code Sandbox)

## Overview

按自底向上顺序实现代码执行沙箱模块：先建立数据模型和配置基础，再实现核心组件（CodeValidator → SandboxBooter → SandboxSessionManager → SandboxRepository），最后通过 CodeExecuteTool 将所有组件串联并注册到工具系统。每个任务对应一个可独立提交的代码变更。

## Tasks

- [ ] 1. 数据模型与配置基础
  - [x] 1.1 创建数据模型（model 包）
    - 创建 `com.lifepilot.sandbox.model` 包
    - 实现 Language 枚举（PYTHON / JAVASCRIPT / SHELL，含 runtimeCommand、fileExtension、fromString）
    - 实现 ExecutionState 枚举（COMPLETED / TIMEOUT / FAILED）
    - 实现 SandboxState 枚举（READY / RUNNING / SHUTDOWN）
    - 实现 ExecutionRequest record（language, code, timeoutSeconds, workingDirectory）
    - 实现 ExecutionResult record（stdout, stderr, exitCode, durationMs, state）
    - 实现 Violation record（pattern, description, severity, lineNumber）
    - 实现 ValidationResult record（passed, violations, 含 ok() 和 rejected() 工厂方法）
    - 实现 ExecutionRecord record（审计记录，含 @Nullable 字段）
    - _Requirements: 10.1, 10.2, 10.3, 10.4_

  - [ ]* 1.2 编写 Language 枚举属性测试
    - **Property 12: Language.fromString round-trip**
    - **Validates: Requirements 10.4**

  - [x] 1.3 创建 SandboxConfigProperties 配置类
    - 创建 `com.lifepilot.sandbox.config` 包
    - 实现 SandboxConfigProperties（@ConfigurationProperties prefix "lifepilot.sandbox"）
    - 实现嵌套类 Session（ttlSeconds, maxActiveSessions, cleanupIntervalSeconds）
    - 实现嵌套类 Validator（enabled, rejectCritical）
    - 实现嵌套类 Docker（memoryLimitMb, cpuLimit, diskLimitMb, networkEnabled, imagePrefix）
    - 所有字段设置默认值
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 8.9, 8.10, 8.11, 8.12, 8.13, 8.14, 8.15_

  - [x] 1.4 更新 application.yml 添加沙箱配置
    - 在 application.yml 中声明所有 lifepilot.sandbox.* 配置键及默认值
    - _Requirements: 8.16_

- [x] 2. Checkpoint — 确认数据模型和配置编译通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 3. CodeValidator 代码预检器
  - [x] 3.1 实现 CodeValidator
    - 创建 `com.lifepilot.sandbox.validator` 包
    - 实现 CodeValidator 类，注入 SandboxConfigProperties
    - 实现 Python 危险模式检测（CRITICAL: os.system, subprocess.call, os.exec*, eval, exec, compile, \_\_import\_\_; HIGH: os.remove, shutil.rmtree, open('/etc/'); MEDIUM: urllib.request, requests.get, socket.connect）
    - 实现 JavaScript 危险模式检测（CRITICAL: child_process.exec, child_process.spawn, require('child_process'); HIGH: fs.unlinkSync, fs.rmdirSync, fs.writeFileSync('/'); MEDIUM: http.request, fetch, net.connect）
    - 实现 Shell 危险模式检测（CRITICAL: rm -rf /, dd if=, mkfs, fork bomb, sudo, su, chmod 777, chown root; MEDIUM: curl, wget, nc, ssh）
    - 实现 validator.enabled=false 时直接返回 ok() 的逻辑
    - 实现 reject-critical 判定逻辑
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.10, 4.11_

  - [ ]* 3.2 编写 CodeValidator 属性测试
    - **Property 1: CodeValidator 检测已知危险模式**
    - **Validates: Requirements 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8**

  - [ ]* 3.3 编写 CodeValidator 配置行为属性测试
    - **Property 2: CodeValidator 配置行为一致性**
    - **Validates: Requirements 4.10, 4.11**

  - [ ]* 3.4 编写 CodeValidator 单元测试
    - 各语言各严重程度的具体危险模式示例
    - 安全代码不触发误报
    - 多行代码中的行号定位
    - 空代码输入
    - _Requirements: 4.1 ~ 4.11_

- [ ] 4. SandboxBooter 沙箱启动器
  - [x] 4.1 定义 SandboxBooter sealed interface
    - 创建 `com.lifepilot.sandbox.booter` 包
    - 定义 SandboxBooter sealed interface，permits ProcessBooter, DockerBooter
    - 声明方法：boot(Path), available(), execute(ExecutionRequest), shutdown(), type()
    - _Requirements: 1.1, 1.2, 1.3_

  - [x] 4.2 实现 ProcessBooter
    - 实现 ProcessBuilder 进程沙箱
    - 代码写入临时脚本文件（.py / .js / .sh）
    - 环境变量清洗（clear + 仅保留 PATH）
    - 工作目录设置为临时目录
    - Virtual Thread 异步读取 stdout / stderr
    - 超时处理（waitFor + destroyForcibly）
    - 输出截断到 maxOutputBytes
    - 执行后清理临时脚本文件
    - available() 始终返回 true
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9_

  - [x] 4.3 实现 DockerBooter
    - 实现 Docker CLI 容器沙箱
    - 构建 docker run 命令（--rm, --network none, --read-only, --user 1000:1000, --memory, --cpus, -v）
    - 通过 ProcessBuilder 执行 docker run
    - 超时处理（docker kill）
    - 执行后清理工作目录
    - available() 通过执行 "docker info" 检测
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_

  - [ ]* 4.4 编写 ProcessBooter 环境变量清洗属性测试
    - **Property 5: 环境变量清洗**
    - **Validates: Requirements 2.3**

  - [ ]* 4.5 编写 SandboxBooter 超时属性测试
    - **Property 3: 超时执行返回 TIMEOUT 状态**
    - **Validates: Requirements 2.5, 3.6**

  - [ ]* 4.6 编写 SandboxBooter 输出截断属性测试
    - **Property 4: 输出截断不超过最大字节数**
    - **Validates: Requirements 2.6**

  - [ ]* 4.7 编写 SandboxBooter 临时目录清理属性测试
    - **Property 13: 执行后临时目录清理**
    - **Validates: Requirements 2.7, 3.7**

  - [ ]* 4.8 编写 ProcessBooter 和 DockerBooter 单元测试
    - ProcessBooter: Python/JS/Shell 简单代码执行、退出码验证、空输出处理
    - DockerBooter: available() 返回 false 当 Docker 不可用、Docker 命令构建验证（Mock ProcessBuilder）
    - _Requirements: 2.1 ~ 2.9, 3.1 ~ 3.8_

- [x] 5. Checkpoint — 确认 CodeValidator 和 SandboxBooter 测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 6. SandboxSessionManager 会话管理
  - [x] 6.1 实现 SandboxSessionManager
    - 创建 `com.lifepilot.sandbox.session` 包
    - 实现 SandboxEntry record（booter, lastAccessTime, workingDirectory）
    - 实现 ConcurrentHashMap<String, SandboxEntry> 会话存储
    - 实现 getOrCreate(sessionId)：新建或复用沙箱实例，更新 lastAccessTime
    - 实现 maxActiveSessions 限制，超出时抛 IllegalStateException
    - 实现 ScheduledExecutorService 定时清理过期会话
    - 实现 destroy(sessionId)：shutdown booter + 删除工作目录 + 移除 entry
    - 实现 cleanupExpired()：扫描超过 TTL 的会话并销毁
    - 实现 activeCount() 和 shutdownAll()
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7_

  - [ ]* 6.2 编写 SandboxSessionManager activeCount 属性测试
    - **Property 6: 会话管理 activeCount 一致性**
    - **Validates: Requirements 5.2, 5.3, 5.7**

  - [ ]* 6.3 编写 SandboxSessionManager 最大会话限制属性测试
    - **Property 7: 会话最大数量限制**
    - **Validates: Requirements 5.4**

  - [ ]* 6.4 编写 SandboxSessionManager TTL 过期属性测试
    - **Property 8: 会话 TTL 过期清理**
    - **Validates: Requirements 5.5, 5.6**

  - [ ]* 6.5 编写 SandboxSessionManager 单元测试
    - 创建/销毁/过期的具体场景
    - 并发访问安全性
    - _Requirements: 5.1 ~ 5.7_

- [ ] 7. SandboxRepository 审计持久化
  - [x] 7.1 创建 Flyway V16 迁移脚本
    - 创建 `V16__create_sandbox_executions.sql`
    - 创建 sandbox_executions 表（id, session_id, language, code_hash, code_length, booter_type, validation_passed, violation_count, exit_code, stdout_length, stderr_length, duration_ms, state, error_message, created_at, updated_at）
    - 创建索引（session_id, state, created_at）
    - _Requirements: 7.1, 7.2_

  - [x] 7.2 实现 SandboxRepository
    - 创建 `com.lifepilot.sandbox.repository` 包
    - 实现 SandboxRepository 类，注入 JdbcTemplate
    - 实现 insert(ExecutionRecord)：SHA-256 哈希存储代码、仅存 stdout/stderr 字节长度
    - 实现 findBySessionId(String)、findByState(String)、findByTimeRange(Instant, Instant)
    - _Requirements: 7.3, 7.4, 7.5_

  - [ ]* 7.3 编写 SandboxRepository 审计记录完整性属性测试
    - **Property 9: 审计记录完整性**
    - **Validates: Requirements 7.3, 7.4, 7.5**

  - [ ]* 7.4 编写 SandboxRepository 单元测试
    - CRUD 操作具体示例（内存 SQLite）
    - SHA-256 哈希验证
    - _Requirements: 7.1 ~ 7.5_

- [x] 8. Checkpoint — 确认 SessionManager 和 Repository 测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 9. CodeExecuteTool 工具注册与集成
  - [x] 9.1 实现 CodeExecuteTool
    - 创建 `com.lifepilot.sandbox.tool` 包
    - 实现 CodeExecuteTool 类，注入 CodeValidator、SandboxSessionManager、SandboxRepository、SandboxConfigProperties
    - 实现 buildTool()：构建 BuiltinTool（id="code.execute", riskLevel=CRITICAL, idempotent=false, inputSchema 定义 language/code/sessionId）
    - 实现 execute(ToolInput)：语言校验 → CodeValidator 预检 → SessionManager 获取沙箱 → 执行 → 审计持久化 → 返回 ToolResult
    - 实现错误处理：不支持的语言、预检失败（REJECTED）、会话数上限、未预期异常（FAILED）
    - 审计写入失败不影响主流程（降级日志）
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7_

  - [ ]* 9.2 编写 CodeExecuteTool 输入验证属性测试
    - **Property 10: 工具输入验证拒绝无效请求**
    - **Validates: Requirements 6.3, 6.4**

  - [ ]* 9.3 编写 CodeExecuteTool 审计记录属性测试
    - **Property 11: 每次执行产生审计记录**
    - **Validates: Requirements 6.6, 6.7**

  - [ ]* 9.4 编写 CodeExecuteTool 单元测试
    - 完整执行流程示例（Mock SandboxBooter）
    - 各种错误场景（不支持语言、预检失败、会话上限、执行异常）
    - _Requirements: 6.1 ~ 6.7_

- [ ] 10. SandboxAutoConfiguration 与 Bean 注册
  - [x] 10.1 实现 SandboxAutoConfiguration
    - 实现 @AutoConfiguration + @ConditionalOnProperty(lifepilot.sandbox.enabled)
    - 注册 SandboxBooter Bean（根据 config.booter 选择 ProcessBooter 或 DockerBooter）
    - 注册 CodeValidator、SandboxSessionManager、SandboxRepository、CodeExecuteTool Bean
    - 在 CodeExecuteTool Bean 初始化时调用 DynamicToolRegistry.registerBuiltinTool()
    - Docker 模式下检查可用性，不可用则启动失败
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.6_

  - [x] 10.2 注册 AutoConfiguration imports
    - 在 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 中添加 SandboxAutoConfiguration
    - _Requirements: 9.5_

- [x] 11. Checkpoint — 确认所有组件编译通过
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 12. 集成测试
  - [ ]* 12.1 编写 CodeExecuteTool_DynamicToolRegistry 集成测试
    - 验证 CodeExecuteTool 作为 BuiltinTool 正确注册到 DynamicToolRegistry
    - 验证风险等级为 CRITICAL
    - _Requirements: 6.1, 9.3, 9.6_

  - [ ]* 12.2 编写 SandboxAutoConfiguration 集成测试
    - 验证 Spring Context 加载、所有 Bean 正确注入
    - 验证 enabled=false 时不注册 Bean
    - _Requirements: 9.1, 9.4_

  - [ ]* 12.3 编写 SandboxRepository_Flyway 集成测试
    - 验证 V16 迁移脚本正确创建表和索引
    - CRUD 操作端到端验证
    - _Requirements: 7.1, 7.2_

- [ ] 13. Final checkpoint — 确认所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties (jqwik, 100+ iterations)
- Unit tests validate specific examples and edge cases
- 集成测试验证跨模块协作和 Spring Context 加载
- GuardrailPolicy CRITICAL 审批由 ToolExecutionPipeline 自动执行，CodeExecuteTool 不需要显式调用
