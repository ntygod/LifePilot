# 需求文档：CLI 交互层

## 简介

CLI 交互层是 LifePilot 的主要用户界面，通过终端提供与 AI Agent 的交互式对话能力。基于 JLine 3 实现，支持流式响应输出、Tab 补全、命令历史、语法高亮等终端交互特性。同时提供快捷命令（todo/schedule/habit/llm/mcp/skill），绕过完整 Agent 循环直接调用对应模块，实现常用操作的快速执行。CLI 快速路径机制允许简单命令跳过完整 Spring 初始化，提升启动速度。

参考文档：
- 特性设计：#[[file:docs/FEATURES.md]]（§四 快速开始）
- 架构设计：#[[file:docs/ARCHITECTURE.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

## 术语表

- **CLI_Shell**：基于 JLine 3 的交互式终端 Shell，负责读取用户输入、分发命令、渲染输出
- **ChatCommand**：交互式对话命令（`lifepilot chat`），启动与 Agent 的多轮对话会话
- **QuickCommand**：快捷命令（todo/schedule/habit/llm/mcp/skill），绕过 Agent 循环直接调用对应模块
- **CommandRouter**：命令路由器，根据用户输入分发到 ChatCommand 或 QuickCommand
- **ResponseRenderer**：响应渲染器，将 Agent 响应或命令结果格式化输出到终端
- **CliUserConfirmationService**：CLI 用户确认服务，实现 UserConfirmationService 接口，在终端中请求用户确认高风险工具执行
- **FastPathRunner**：CLI 快速路径执行器，简单命令跳过完整 Spring 初始化直接执行
- **AgentLoop**：Agent 核心控制循环，处理用户消息并返回 AgentResponse
- **SessionManager**：会话管理器，管理多轮对话的会话持久化和恢复
- **SkillRegistry**：Skill 注册中心，管理所有已注册 Skill 的查询和列表
- **ProviderRegistry**：LLM Provider 注册表，管理 LLM 服务商配置和适配器
- **DynamicToolRegistry**：动态工具注册中心，统一管理三层工具

## 需求

### 需求 1：交互式对话

**用户故事：** 作为用户，我希望通过终端与 AI Agent 进行多轮对话，以便用自然语言完成各种任务。

#### 验收标准

1. WHEN 用户执行 `lifepilot chat` 命令，THE CLI_Shell SHALL 启动交互式对话会话，显示欢迎信息并进入输入等待状态
2. WHEN 用户输入一条消息并按回车，THE CLI_Shell SHALL 将消息封装为 AgentRequest 提交给 AgentLoop，并将 AgentResponse 的内容通过 ResponseRenderer 输出到终端
3. WHILE 对话会话处于活跃状态，THE CLI_Shell SHALL 维持同一个 sessionId，使 SessionManager 能够关联多轮对话上下文
4. WHEN AgentLoop 返回流式响应，THE ResponseRenderer SHALL 逐 token 实时输出到终端，而非等待完整响应后一次性输出
5. WHEN 用户输入 `/exit` 或 `/quit` 或按 Ctrl+D，THE CLI_Shell SHALL 优雅终止当前对话会话并返回命令行
6. WHEN 用户输入 `/new` 命令，THE CLI_Shell SHALL 创建新的对话会话（新 sessionId），清空当前对话上下文
7. IF AgentLoop 执行过程中发生异常，THEN THE CLI_Shell SHALL 向用户显示友好的错误提示信息，并保持会话可继续使用

### 需求 2：快捷命令

**用户故事：** 作为用户，我希望通过快捷命令直接管理待办、日程、习惯等，以便快速完成常用操作而无需经过完整 Agent 对话。

#### 验收标准

1. WHEN 用户执行 `lifepilot todo list` 命令，THE QuickCommand SHALL 直接调用 TodoSkillProvider 的列表工具，将结果格式化输出到终端
2. WHEN 用户执行 `lifepilot todo add <内容>` 命令，THE QuickCommand SHALL 直接调用 TodoSkillProvider 的创建工具，创建待办并输出确认信息
3. WHEN 用户执行 `lifepilot schedule list` 命令，THE QuickCommand SHALL 直接调用 ScheduleSkillProvider 的列表工具，将日程格式化输出到终端
4. WHEN 用户执行 `lifepilot habit list` 命令，THE QuickCommand SHALL 直接调用 HabitSkillProvider 的列表工具，将习惯列表格式化输出到终端
5. WHEN 用户执行 `lifepilot llm list` 命令，THE QuickCommand SHALL 调用 ProviderRegistry 列出所有已注册的 LLM Provider 及其状态
6. WHEN 用户执行 `lifepilot llm test <providerId>` 命令，THE QuickCommand SHALL 调用对应 Provider 执行健康检查，输出连通性测试结果和延迟
7. WHEN 用户执行 `lifepilot mcp list` 命令，THE QuickCommand SHALL 列出所有已配置的 MCP Server 及其连接状态
8. WHEN 用户执行 `lifepilot skill list` 命令，THE QuickCommand SHALL 调用 SkillRegistry.listSummaries() 列出所有已注册 Skill 的摘要
9. WHEN 用户执行 `lifepilot skill info <skillId>` 命令，THE QuickCommand SHALL 调用 SkillRegistry.find() 输出指定 Skill 的详细信息
10. IF 用户输入的快捷命令格式不正确或缺少必要参数，THEN THE QuickCommand SHALL 输出该命令的用法说明（usage）


### 需求 3：JLine 3 终端交互增强

**用户故事：** 作为用户，我希望终端交互具备补全、历史记录和高亮等特性，以便提升输入效率和使用体验。

#### 验收标准

1. THE CLI_Shell SHALL 提供 Tab 键命令补全，补全范围包括所有顶层命令（chat/todo/schedule/habit/llm/mcp/skill）和子命令
2. THE CLI_Shell SHALL 持久化命令历史到本地文件（`~/.lifepilot/cli-history`），使用户重启后可通过上下箭头键浏览历史命令
3. WHEN 用户输入命令时，THE CLI_Shell SHALL 对命令关键字和参数进行语法高亮，区分命令名、子命令和参数
4. WHEN 用户输入多行文本（以 `\` 结尾或使用三引号 `"""` 包裹），THE CLI_Shell SHALL 支持多行输入模式，在用户完成输入后再提交
5. THE CLI_Shell SHALL 显示自定义提示符（prompt），在对话模式下显示 `lifepilot> `，在普通模式下显示 `$ `

### 需求 4：CLI 用户确认服务

**用户故事：** 作为用户，我希望在 Agent 执行高风险工具时收到终端确认提示，以便我能审查并决定是否允许执行。

#### 验收标准

1. WHEN Agent 执行 HIGH 或 CRITICAL 风险级别的工具时，THE CliUserConfirmationService SHALL 在终端显示工具名称、风险级别和确认消息，等待用户输入 y/n
2. WHEN 用户输入 `y` 或 `yes`，THE CliUserConfirmationService SHALL 返回 true 允许工具执行
3. WHEN 用户输入 `n` 或 `no` 或直接按回车，THE CliUserConfirmationService SHALL 返回 false 拒绝工具执行
4. IF 用户在确认等待期间按 Ctrl+C，THEN THE CliUserConfirmationService SHALL 返回 false 拒绝工具执行
5. THE CliUserConfirmationService SHALL 替换现有的 NoOpUserConfirmationService 作为 CLI 模式下的默认 UserConfirmationService 实现

### 需求 5：CLI 快速路径

**用户故事：** 作为用户，我希望简单的快捷命令能快速执行，以便不需要等待完整 Spring 容器初始化。

#### 验收标准

1. WHEN 用户执行 `lifepilot --help` 或 `lifepilot -h` 命令，THE FastPathRunner SHALL 跳过 Spring 初始化，直接输出帮助信息
2. WHEN 用户执行 `lifepilot --version` 或 `lifepilot -v` 命令，THE FastPathRunner SHALL 跳过 Spring 初始化，直接输出版本号
3. WHEN 用户执行需要数据访问的命令（如 todo/schedule/habit/chat），THE CLI_Shell SHALL 执行完整 Spring 初始化以获取所需的 Bean 依赖
4. THE FastPathRunner SHALL 在 LifePilotApplication.main() 方法中，于 SpringApplication.run() 之前检测命令行参数并决定是否走快速路径

### 需求 6：命令路由与输出格式化

**用户故事：** 作为用户，我希望命令输出格式清晰美观，以便快速理解执行结果。

#### 验收标准

1. THE CommandRouter SHALL 根据第一个命令行参数将请求分发到对应的 ChatCommand 或 QuickCommand 处理器
2. WHEN 快捷命令执行成功，THE ResponseRenderer SHALL 使用 ✅ 前缀输出成功信息
3. WHEN 快捷命令执行失败，THE ResponseRenderer SHALL 使用 ❌ 前缀输出错误信息
4. WHEN 输出列表数据（如 todo list、skill list），THE ResponseRenderer SHALL 使用表格或对齐格式输出，包含序号和关键字段
5. WHEN 用户未输入任何命令参数，THE CommandRouter SHALL 默认进入交互式对话模式（等同于 `lifepilot chat`）
6. IF 用户输入未识别的命令，THEN THE CommandRouter SHALL 输出可用命令列表和帮助提示

### 需求 7：配置外部化

**用户故事：** 作为开发者，我希望 CLI 交互层的可调参数通过配置文件管理，以便用户可以自定义 CLI 行为。

#### 验收标准

1. THE CLI_Shell SHALL 从 `@ConfigurationProperties` 读取配置，配置前缀为 `lifepilot.cli`
2. THE CLI_Shell SHALL 支持配置项：命令历史文件路径（默认 `~/.lifepilot/cli-history`）、历史记录最大条数（默认 1000）、提示符文本（默认 `lifepilot> `）
3. THE CLI_Shell SHALL 支持配置项：流式输出刷新间隔（默认 50ms）、确认超时时间（默认 30s）
4. THE CLI_Shell SHALL 在 `application.yml` 中声明所有配置项及默认值
