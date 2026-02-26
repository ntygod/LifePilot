# Requirements Document

## Introduction

代码执行沙箱（Code Sandbox）是 LifePilot Phase 4 模块 16，为 AI Agent 提供安全的代码执行能力。用户通过 Agent 对话请求代码执行（数据处理、计算、脚本自动化），Agent 调用 `code.execute` 工具，经过三层安全防护（CodeValidator 静态预检 → GuardrailPolicy CRITICAL 审批 → SandboxBooter 隔离执行）后在沙箱中运行代码并返回结果。

核心设计目标：
1. 纵深防御，三层安全屏障确保代码执行安全
2. SandboxBooter sealed interface 抽象，支持 ProcessBooter（默认，零依赖）和 DockerBooter（可选，强隔离）
3. 会话级沙箱实例复用，避免冷启动开销
4. 与工具系统深度集成，作为 BuiltinTool 注册到 DynamicToolRegistry
5. 全链路审计日志，执行可追溯

支持语言：Python、JavaScript、Shell。

参考文档：
- 架构设计：#[[file:docs/architecture/sandbox.md]]
- 特性设计：#[[file:docs/features/sandbox.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

## Glossary

- **SandboxBooter**: 沙箱启动器抽象，sealed interface，穷举两种隔离实现（ProcessBooter / DockerBooter）
- **ProcessBooter**: 基于 Java ProcessBuilder 的轻量级进程沙箱，零外部依赖，默认方案
- **DockerBooter**: 基于 Docker Engine API 的容器级强隔离沙箱，可选方案
- **CodeValidator**: 代码预检器，通过正则模式匹配检测已知危险操作，拦截明显恶意代码
- **ValidationResult**: 代码预检结果 record，包含通过状态和违规列表
- **Violation**: 预检违规项 record，包含匹配模式、描述、严重程度和行号
- **SandboxSessionManager**: 会话级沙箱实例管理器，维护会话到沙箱实例的映射，支持 TTL 自动续期和过期清理
- **CodeExecuteTool**: 代码执行工具，作为 BuiltinTool 注册到 DynamicToolRegistry，风险等级 CRITICAL
- **ExecutionRequest**: 执行请求 record，包含语言、代码、超时等参数
- **ExecutionResult**: 执行结果 record，包含 stdout、stderr、退出码、耗时
- **ExecutionRecord**: 审计记录 record，包含执行元数据（代码哈希、沙箱类型、预检结果、执行状态等）
- **SandboxRepository**: 审计持久化仓储，管理 sandbox_executions 表
- **SandboxState**: 沙箱实例状态枚举，包括 READY / RUNNING / SHUTDOWN
- **Language**: 支持的语言枚举，包括 PYTHON / JAVASCRIPT / SHELL
- **SandboxConfigProperties**: 沙箱配置属性类，外部化所有业务可调参数
- **SandboxAutoConfiguration**: Spring AutoConfiguration，注册所有沙箱模块 Bean
- **DynamicToolRegistry**: 已完成模块，动态工具注册中心，提供 registerBuiltinTool() 方法注册内置工具
- **ToolContract**: 已完成模块，工具契约 sealed interface，BuiltinTool 为其 permits 之一
- **BuiltinTool**: 已完成模块，Java 原生工具 record，通过 Builder 模式构建
- **RiskLevel**: 已完成模块，工具风险等级枚举，CRITICAL 级别需要用户确认 + 二次验证
- **GuardrailPolicy**: 已完成模块，护栏策略，CRITICAL 级别触发用户确认和二次验证流程
- **ToolExecutionPipeline**: 已完成模块，工具执行管线，负责护栏检查、执行、审计的完整流程

## Requirements

### Requirement 1: SandboxBooter 沙箱启动器抽象

**User Story:** As a LifePilot developer, I want a pluggable sandbox abstraction with sealed interface, so that different isolation strategies can be selected based on deployment environment and security needs.

#### Acceptance Criteria

1. THE SandboxBooter sealed interface SHALL define two permitted implementations: ProcessBooter and DockerBooter
2. THE SandboxBooter SHALL declare four methods: boot(SandboxConfig) returning CompletableFuture<Void>, available() returning boolean, execute(ExecutionRequest) returning ExecutionResult, and shutdown() returning void
3. THE SandboxBooter SHALL declare a type() method returning a String identifier ("process" or "docker")
4. WHEN SandboxAutoConfiguration initializes, THE SandboxAutoConfiguration SHALL instantiate the SandboxBooter implementation matching the configured booter type (lifepilot.sandbox.booter)

### Requirement 2: ProcessBooter 轻量级进程沙箱

**User Story:** As a LifePilot user, I want a zero-dependency default sandbox that works out of the box, so that I can execute code safely without installing additional software.

#### Acceptance Criteria

1. THE ProcessBooter SHALL execute code using Java ProcessBuilder in an isolated temporary directory created under the system temp path
2. WHEN executing code, THE ProcessBooter SHALL write the code to a temporary script file with the appropriate extension (.py / .js / .sh) and invoke the configured runtime command (python3 / node / bash)
3. WHEN executing code, THE ProcessBooter SHALL clear all environment variables except PATH and language-specific runtime variables, removing sensitive variables such as HOME, USER, and API key variables
4. WHEN executing code, THE ProcessBooter SHALL set the ProcessBuilder working directory to the temporary directory, restricting the code's default file access scope
5. WHEN the execution exceeds the configured timeout (lifepilot.sandbox.execution-timeout-seconds, default 30 seconds), THE ProcessBooter SHALL terminate the process using Process.destroyForcibly() and return an ExecutionResult with a TIMEOUT state
6. THE ProcessBooter SHALL capture stdout and stderr separately, truncating each to the configured maximum output bytes (lifepilot.sandbox.max-output-bytes, default 65536 bytes)
7. WHEN execution completes, THE ProcessBooter SHALL delete the temporary directory and all its contents
8. THE ProcessBooter SHALL read stdout and stderr using Virtual Threads to avoid blocking the calling thread
9. THE ProcessBooter.available() method SHALL return true (ProcessBuilder is always available in the JVM)

### Requirement 3: DockerBooter 容器级强隔离沙箱

**User Story:** As a LifePilot user with strict security requirements, I want a Docker-based sandbox with full resource isolation, so that untrusted code cannot access host resources or network.

#### Acceptance Criteria

1. THE DockerBooter SHALL create a Docker container for each code execution using the configured image prefix (lifepilot.sandbox.docker.image-prefix) combined with the language name
2. THE DockerBooter SHALL configure the container with memory limit (lifepilot.sandbox.docker.memory-limit-mb, default 256MB), CPU limit (lifepilot.sandbox.docker.cpu-limit, default 1.0 core), and disk limit (lifepilot.sandbox.docker.disk-limit-mb, default 512MB)
3. THE DockerBooter SHALL set the container network mode to "none" by default, disabling all network access unless lifepilot.sandbox.docker.network-enabled is set to true
4. THE DockerBooter SHALL mount the temporary working directory as the only writable volume and set the container root filesystem to read-only
5. THE DockerBooter SHALL run the container as a non-root user (UID 1000:1000)
6. WHEN the container execution exceeds the configured timeout, THE DockerBooter SHALL kill the container and return an ExecutionResult with a TIMEOUT state
7. WHEN execution completes, THE DockerBooter SHALL remove the container (--rm equivalent) and clean up the temporary working directory
8. WHEN DockerBooter.available() is called, THE DockerBooter SHALL check Docker Engine availability by executing "docker info" and return true only if the command succeeds
9. IF the configured booter type is "docker" and Docker Engine is not available, THEN THE SandboxAutoConfiguration SHALL fail to start and log an ERROR indicating Docker is required

### Requirement 4: CodeValidator 代码预检

**User Story:** As a LifePilot user, I want dangerous code patterns to be detected before execution, so that obviously malicious code is rejected without consuming sandbox resources.

#### Acceptance Criteria

1. WHEN Python code is provided, THE CodeValidator SHALL detect CRITICAL patterns including os.system(), subprocess.call(), os.exec*(), eval(), exec(), compile(), and __import__()
2. WHEN Python code is provided, THE CodeValidator SHALL detect HIGH patterns including os.remove(), shutil.rmtree(), and open() with system paths like /etc/
3. WHEN Python code is provided, THE CodeValidator SHALL detect MEDIUM patterns including urllib.request, requests.get(), and socket.connect()
4. WHEN JavaScript code is provided, THE CodeValidator SHALL detect CRITICAL patterns including child_process.exec(), child_process.spawn(), and require('child_process')
5. WHEN JavaScript code is provided, THE CodeValidator SHALL detect HIGH patterns including fs.unlinkSync(), fs.rmdirSync(), and fs.writeFileSync() targeting system paths
6. WHEN JavaScript code is provided, THE CodeValidator SHALL detect MEDIUM patterns including http.request(), fetch(), and net.connect()
7. WHEN Shell code is provided, THE CodeValidator SHALL detect CRITICAL patterns including rm -rf /, dd if=, mkfs, fork bombs, sudo, su, chmod 777, and chown root
8. WHEN Shell code is provided, THE CodeValidator SHALL detect MEDIUM patterns including curl, wget, nc, and ssh
9. THE CodeValidator SHALL return a ValidationResult record containing a passed boolean and a list of Violation records, each with pattern, description, severity (CRITICAL / HIGH / MEDIUM), and lineNumber
10. WHEN the configuration lifepilot.sandbox.validator.reject-critical is true (default) and a CRITICAL violation is detected, THE CodeValidator SHALL set ValidationResult.passed to false
11. WHEN the configuration lifepilot.sandbox.validator.enabled is false, THE CodeValidator SHALL return a ValidationResult with passed=true and an empty violations list

### Requirement 5: SandboxSessionManager 会话级实例管理

**User Story:** As a LifePilot user, I want sandbox instances to be reused within the same Agent session, so that installed dependencies and generated files persist across multiple code executions.

#### Acceptance Criteria

1. THE SandboxSessionManager SHALL maintain a ConcurrentHashMap mapping session IDs to SandboxEntry records containing the SandboxBooter instance, last access time, and working directory path
2. WHEN getOrCreate(sessionId) is called and no session exists, THE SandboxSessionManager SHALL create a new SandboxBooter instance, boot it, and store the entry in the map
3. WHEN getOrCreate(sessionId) is called and a session exists, THE SandboxSessionManager SHALL update the last access time (TTL renewal) and return the existing SandboxBooter instance
4. WHEN the number of active sessions reaches the configured maximum (lifepilot.sandbox.session.max-active-sessions, default 5), THE SandboxSessionManager SHALL reject new session creation and return an error indicating the session limit is reached
5. THE SandboxSessionManager SHALL run a periodic cleanup task at the configured interval (lifepilot.sandbox.session.cleanup-interval-seconds, default 60 seconds) using ScheduledExecutorService to destroy sessions that have exceeded the TTL (lifepilot.sandbox.session.ttl-seconds, default 600 seconds)
6. WHEN a session is destroyed, THE SandboxSessionManager SHALL call shutdown() on the SandboxBooter instance, delete the working directory, and remove the entry from the map
7. THE SandboxSessionManager SHALL provide an activeCount() method returning the current number of active sessions

### Requirement 6: CodeExecuteTool 工具注册与集成

**User Story:** As a LifePilot Agent, I want code execution to be available as a standard tool, so that I can invoke it through the ToolExecutionPipeline with automatic guardrail enforcement.

#### Acceptance Criteria

1. THE CodeExecuteTool SHALL register a BuiltinTool with id "code.execute", risk level CRITICAL, and idempotent=false in DynamicToolRegistry
2. THE CodeExecuteTool input schema SHALL define three required parameters: language (enum: python, javascript, shell), code (string), and sessionId (string)
3. WHEN the code.execute tool is invoked, THE CodeExecuteTool SHALL first validate the language against the configured supported languages list (lifepilot.sandbox.supported-languages)
4. WHEN the code.execute tool is invoked, THE CodeExecuteTool SHALL call CodeValidator.validate() and reject execution if ValidationResult.passed is false, returning a ToolResult with the violation details
5. WHEN validation passes, THE CodeExecuteTool SHALL obtain a SandboxBooter instance from SandboxSessionManager.getOrCreate(sessionId) and call execute() with the ExecutionRequest
6. WHEN execution completes, THE CodeExecuteTool SHALL persist an ExecutionRecord to SandboxRepository and return a ToolResult containing stdout, stderr, and exitCode
7. IF an unexpected exception occurs during execution, THEN THE CodeExecuteTool SHALL return a ToolResult with error status and the exception message, and persist an ExecutionRecord with FAILED state

### Requirement 7: 审计持久化

**User Story:** As a LifePilot administrator, I want every code execution to be recorded in an audit log, so that I can review execution history and investigate security incidents.

#### Acceptance Criteria

1. THE Flyway migration script V16 SHALL create a sandbox_executions table with columns: id (TEXT, PRIMARY KEY), session_id (TEXT, NOT NULL), language (TEXT, NOT NULL), code_hash (TEXT, NOT NULL), code_length (INTEGER, NOT NULL), booter_type (TEXT, NOT NULL), validation_passed (INTEGER, NOT NULL), violation_count (INTEGER, NOT NULL, DEFAULT 0), exit_code (INTEGER), stdout_length (INTEGER), stderr_length (INTEGER), duration_ms (INTEGER), state (TEXT, NOT NULL), error_message (TEXT), created_at (TEXT, NOT NULL), updated_at (TEXT, NOT NULL)
2. THE Flyway migration script V16 SHALL create indexes on session_id, state, and created_at columns of the sandbox_executions table
3. THE SandboxRepository SHALL store the code content hash as SHA-256 in the code_hash column, not the original code content
4. THE SandboxRepository SHALL store only the byte length of stdout and stderr in stdout_length and stderr_length columns, not the actual output content
5. WHEN an execution is completed, timed out, failed, or rejected, THE SandboxRepository SHALL insert an ExecutionRecord with the corresponding state value (COMPLETED / TIMEOUT / FAILED / REJECTED)

### Requirement 8: 配置外部化

**User Story:** As a LifePilot administrator, I want all business-tunable parameters of the code sandbox to be externalized to configuration, so that behavior can be adjusted without code changes.

#### Acceptance Criteria

1. THE SandboxConfigProperties SHALL externalize the sandbox enabled toggle with configuration key lifepilot.sandbox.enabled and default value true
2. THE SandboxConfigProperties SHALL externalize the booter type with configuration key lifepilot.sandbox.booter and default value "process"
3. THE SandboxConfigProperties SHALL externalize the supported languages list with configuration key lifepilot.sandbox.supported-languages and default value [python, javascript, shell]
4. THE SandboxConfigProperties SHALL externalize the execution timeout with configuration key lifepilot.sandbox.execution-timeout-seconds and default value 30
5. THE SandboxConfigProperties SHALL externalize the maximum output bytes with configuration key lifepilot.sandbox.max-output-bytes and default value 65536
6. THE SandboxConfigProperties SHALL externalize session TTL with configuration key lifepilot.sandbox.session.ttl-seconds and default value 600
7. THE SandboxConfigProperties SHALL externalize maximum active sessions with configuration key lifepilot.sandbox.session.max-active-sessions and default value 5
8. THE SandboxConfigProperties SHALL externalize cleanup interval with configuration key lifepilot.sandbox.session.cleanup-interval-seconds and default value 60
9. THE SandboxConfigProperties SHALL externalize validator enabled toggle with configuration key lifepilot.sandbox.validator.enabled and default value true
10. THE SandboxConfigProperties SHALL externalize validator reject-critical toggle with configuration key lifepilot.sandbox.validator.reject-critical and default value true
11. THE SandboxConfigProperties SHALL externalize Docker memory limit with configuration key lifepilot.sandbox.docker.memory-limit-mb and default value 256
12. THE SandboxConfigProperties SHALL externalize Docker CPU limit with configuration key lifepilot.sandbox.docker.cpu-limit and default value 1.0
13. THE SandboxConfigProperties SHALL externalize Docker network enabled toggle with configuration key lifepilot.sandbox.docker.network-enabled and default value false
14. THE SandboxConfigProperties SHALL externalize runtime paths with configuration key lifepilot.sandbox.runtime-paths (python, javascript, shell) and default values "python3", "node", "bash"
15. THE SandboxConfigProperties SHALL be registered as a Spring Bean via @ConfigurationProperties prefix "lifepilot.sandbox"
16. THE application.yml SHALL declare all sandbox configuration keys with their default values

### Requirement 9: Spring AutoConfiguration 与 Bean 注册

**User Story:** As a developer, I want all sandbox components to be registered as Spring Beans via AutoConfiguration, so that the module integrates cleanly with the LifePilot Spring Boot application.

#### Acceptance Criteria

1. THE SandboxAutoConfiguration SHALL register SandboxBooter, CodeValidator, SandboxSessionManager, CodeExecuteTool, and SandboxRepository as Spring Beans via @Bean methods
2. THE SandboxAutoConfiguration SHALL inject JdbcTemplate from the existing data source for SandboxRepository
3. THE SandboxAutoConfiguration SHALL inject DynamicToolRegistry from the tool system module for CodeExecuteTool registration
4. THE SandboxAutoConfiguration SHALL be conditional on lifepilot.sandbox.enabled being true (default)
5. THE SandboxAutoConfiguration SHALL be registered in META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
6. THE SandboxAutoConfiguration SHALL register the CodeExecuteTool as a BuiltinTool in DynamicToolRegistry during bean initialization

### Requirement 10: 执行请求与结果模型

**User Story:** As a developer, I want well-defined immutable data models for execution requests and results, so that the sandbox API is type-safe and easy to use.

#### Acceptance Criteria

1. THE ExecutionRequest record SHALL contain fields: language (Language enum), code (String), timeoutSeconds (int), and workingDirectory (Path)
2. THE ExecutionResult record SHALL contain fields: stdout (String), stderr (String), exitCode (int), durationMs (long), and state (ExecutionState enum with values COMPLETED, TIMEOUT, FAILED)
3. THE ExecutionRecord record SHALL contain fields: id (String), sessionId (String), language (Language), codeHash (String), codeLength (int), booterType (String), validationPassed (boolean), violationCount (int), exitCode (Integer nullable), stdoutLength (Integer nullable), stderrLength (Integer nullable), durationMs (Long nullable), state (String), errorMessage (String nullable), createdAt (Instant), updatedAt (Instant)
4. THE Language enum SHALL define three values: PYTHON, JAVASCRIPT, SHELL, each with a runtimeCommand (String) and fileExtension (String) field
