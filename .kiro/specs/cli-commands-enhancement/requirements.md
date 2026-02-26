# Requirements Document

## Introduction

增强 CLI 交互层的快捷命令体系，补齐架构文档（`docs/architecture/cli-interaction.md` §8）规划的完整子命令集。当前各命令的子命令覆盖不足：todo/schedule/habit 缺少 done/delete/checkin/status/today/tomorrow 等操作命令；llm 缺少 add/remove 配置管理命令；mcp 缺少 add/remove/test 命令；skill 缺少 reload 命令。同时需要同步更新 CliCompleter 的 Tab 补全树，使所有新增子命令均可通过 Tab 补全发现。

参考文档：
- 架构设计：#[[file:docs/architecture/cli-interaction.md]]
- 特性设计：#[[file:docs/features/gateway-channels.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

## Glossary

- **QuickCommand**: 快捷命令分发器，直接调用各模块 Repository/Registry API 执行 CRUD 操作，不经过 AgentLoop 和 LLM 推理
- **CliCompleter**: 基于 JLine 3 AggregateCompleter 的 Tab 补全器，为所有快捷命令及其子命令提供补全候选
- **CommandRouter**: 命令路由器，根据用户输入的首个参数将命令分发到 QuickCommand 或 ChatCommand
- **ResponseRenderer**: 响应渲染器，提供 success/error/info/table 等格式化输出方法
- **ProviderRegistry**: LLM Provider 注册表，管理 Provider 配置和适配器实例，支持 register/deregister/healthCheckAll
- **McpServerRegistry**: MCP Server 注册中心，管理 MCP Server 连接生命周期，支持 connectServer/disconnectServer/listServers
- **SkillRegistry**: Skill 注册中心，管理 Skill 定义的注册/注销/查询
- **YamlSkillLoader**: YAML Skill 加载器，从文件系统加载 YAML 格式的 Skill 定义并注册到 SkillRegistry
- **DynamicToolRegistry**: 动态工具注册表，管理内置工具和 MCP 工具的注册与查询

## Requirements

### Requirement 1: Todo 完成命令

**User Story:** As a CLI 用户, I want to mark a todo item as done via quick command, so that I can update task status without entering chat mode.

#### Acceptance Criteria

1. WHEN the user inputs `todo done <id>`, THE QuickCommand SHALL invoke the builtin todo done tool with the specified ID and display a success confirmation
2. IF the specified todo ID does not exist, THEN THE QuickCommand SHALL display an error message containing the invalid ID
3. WHEN the user inputs `todo done` without an ID argument, THE QuickCommand SHALL display a usage hint indicating the required parameter

### Requirement 2: Todo 删除命令

**User Story:** As a CLI 用户, I want to delete a todo item via quick command, so that I can remove unwanted tasks efficiently.

#### Acceptance Criteria

1. WHEN the user inputs `todo delete <id>`, THE QuickCommand SHALL invoke the builtin todo delete tool with the specified ID and display a success confirmation
2. IF the specified todo ID does not exist, THEN THE QuickCommand SHALL display an error message containing the invalid ID
3. WHEN the user inputs `todo delete` without an ID argument, THE QuickCommand SHALL display a usage hint indicating the required parameter

### Requirement 3: Schedule Today 和 Tomorrow 命令

**User Story:** As a CLI 用户, I want to view today's or tomorrow's schedule via quick commands, so that I can quickly check upcoming events.

#### Acceptance Criteria

1. WHEN the user inputs `schedule today`, THE QuickCommand SHALL invoke the builtin schedule list tool with today's date filter and display the results in table format
2. WHEN the user inputs `schedule tomorrow`, THE QuickCommand SHALL invoke the builtin schedule list tool with tomorrow's date filter and display the results in table format
3. WHEN no schedule items exist for the queried date, THE QuickCommand SHALL display an informational message indicating no items found

### Requirement 4: Schedule Add 命令

**User Story:** As a CLI 用户, I want to add a schedule item via quick command, so that I can create events without entering chat mode.

#### Acceptance Criteria

1. WHEN the user inputs `schedule add <内容>`, THE QuickCommand SHALL invoke the builtin schedule create tool with the provided content and display a success confirmation
2. WHEN the user inputs `schedule add` without content, THE QuickCommand SHALL display a usage hint indicating the required parameter

### Requirement 5: Habit Checkin 命令

**User Story:** As a CLI 用户, I want to check in a habit via quick command, so that I can record habit completion quickly.

#### Acceptance Criteria

1. WHEN the user inputs `habit checkin <习惯名>`, THE QuickCommand SHALL invoke the builtin habit checkin tool with the specified habit name and display a success confirmation
2. IF the specified habit does not exist, THEN THE QuickCommand SHALL display an error message containing the invalid habit name
3. WHEN the user inputs `habit checkin` without a habit name, THE QuickCommand SHALL display a usage hint indicating the required parameter

### Requirement 6: Habit Status 命令

**User Story:** As a CLI 用户, I want to view habit tracking status via quick command, so that I can monitor my habit progress.

#### Acceptance Criteria

1. WHEN the user inputs `habit status`, THE QuickCommand SHALL invoke the builtin habit status tool and display the results in table format
2. WHEN no habits are configured, THE QuickCommand SHALL display an informational message indicating no habits found

### Requirement 7: LLM Add 命令

**User Story:** As a CLI 用户, I want to add a new LLM provider configuration via quick command, so that I can configure providers without editing YAML files.

#### Acceptance Criteria

1. WHEN the user inputs `llm add <providerId> <type> <modelName> <apiKey>`, THE QuickCommand SHALL create a ProviderConfig and register it via ProviderRegistry.register()
2. WHEN the registration succeeds, THE QuickCommand SHALL display a success confirmation containing the provider ID
3. IF a provider with the same ID already exists, THEN THE QuickCommand SHALL display an error message indicating the duplicate ID
4. WHEN the user inputs `llm add` with insufficient arguments, THE QuickCommand SHALL display a usage hint showing the required parameters

### Requirement 8: LLM Remove 命令

**User Story:** As a CLI 用户, I want to remove an LLM provider configuration via quick command, so that I can manage providers dynamically.

#### Acceptance Criteria

1. WHEN the user inputs `llm remove <providerId>`, THE QuickCommand SHALL invoke ProviderRegistry.deregister() with the specified ID and display a success confirmation
2. IF the specified provider ID does not exist, THEN THE QuickCommand SHALL display an error message containing the invalid ID
3. WHEN the user inputs `llm remove` without an ID argument, THE QuickCommand SHALL display a usage hint indicating the required parameter

### Requirement 9: LLM Enable/Disable 命令

**User Story:** As a CLI 用户, I want to enable or disable an LLM provider via quick command, so that I can temporarily toggle providers without removing their configuration.

#### Acceptance Criteria

1. WHEN the user inputs `llm enable <providerId>`, THE QuickCommand SHALL deregister the existing ProviderConfig and re-register it with `enabled=true`, then display a success confirmation
2. WHEN the user inputs `llm disable <providerId>`, THE QuickCommand SHALL deregister the existing ProviderConfig and re-register it with `enabled=false`, then display a success confirmation
3. IF the specified provider ID does not exist, THEN THE QuickCommand SHALL display an error message containing the invalid ID
4. IF the provider is already in the requested state (e.g., enabling an already-enabled provider), THEN THE QuickCommand SHALL display an informational message indicating no change was needed
5. WHEN the user inputs `llm enable` or `llm disable` without an ID argument, THE QuickCommand SHALL display a usage hint indicating the required parameter

### Requirement 10: MCP Add 命令

**User Story:** As a CLI 用户, I want to add a new MCP server configuration via quick command, so that I can connect to MCP servers dynamically.

#### Acceptance Criteria

1. WHEN the user inputs `mcp add <name> <transport> <url>`, THE QuickCommand SHALL create an McpServerConfig and invoke McpServerRegistry.connectServer()
2. WHEN the connection initiates successfully, THE QuickCommand SHALL display a success confirmation containing the server name
3. WHEN the user inputs `mcp add` with insufficient arguments, THE QuickCommand SHALL display a usage hint showing the required parameters

### Requirement 11: MCP Remove 命令

**User Story:** As a CLI 用户, I want to remove an MCP server via quick command, so that I can disconnect and clean up unused servers.

#### Acceptance Criteria

1. WHEN the user inputs `mcp remove <name>`, THE QuickCommand SHALL invoke McpServerRegistry.disconnectServer() with the specified name and display a success confirmation
2. IF the specified server name does not exist, THEN THE QuickCommand SHALL display an error message containing the invalid name
3. WHEN the user inputs `mcp remove` without a name argument, THE QuickCommand SHALL display a usage hint indicating the required parameter

### Requirement 12: MCP Test 命令

**User Story:** As a CLI 用户, I want to test an MCP server's connectivity via quick command, so that I can verify server health.

#### Acceptance Criteria

1. WHEN the user inputs `mcp test <name>`, THE QuickCommand SHALL retrieve the McpClient from McpServerRegistry and invoke listTools() as a connectivity check
2. WHEN the connectivity check succeeds, THE QuickCommand SHALL display a success message with the number of discovered tools and elapsed time
3. IF the connectivity check fails, THEN THE QuickCommand SHALL display an error message containing the failure reason
4. IF the specified server name does not exist or is not connected, THEN THE QuickCommand SHALL display an error message indicating the server is unavailable
5. WHEN the user inputs `mcp test` without a name argument, THE QuickCommand SHALL display a usage hint indicating the required parameter

### Requirement 13: Skill Reload 命令

**User Story:** As a CLI 用户, I want to manually reload all YAML skills via quick command, so that I can apply skill changes without restarting the application.

#### Acceptance Criteria

1. WHEN the user inputs `skill reload`, THE QuickCommand SHALL invoke YamlSkillLoader.loadAll() to reload all YAML skill definitions
2. WHEN the reload completes, THE QuickCommand SHALL display a success message containing the number of skills loaded
3. IF the reload encounters errors, THEN THE QuickCommand SHALL display an error message containing the failure reason

### Requirement 14: CliCompleter 补全树同步

**User Story:** As a CLI 用户, I want Tab completion to cover all available subcommands, so that I can discover and use commands efficiently.

#### Acceptance Criteria

1. THE CliCompleter SHALL provide Tab completion for `todo` with subcommands `list`, `add`, `done`, `delete`
2. THE CliCompleter SHALL provide Tab completion for `schedule` with subcommands `list`, `add`, `today`, `tomorrow`
3. THE CliCompleter SHALL provide Tab completion for `habit` with subcommands `list`, `checkin`, `status`
4. THE CliCompleter SHALL provide Tab completion for `llm` with subcommands `list`, `add`, `test`, `remove`, `enable`, `disable`
5. THE CliCompleter SHALL provide Tab completion for `mcp` with subcommands `list`, `add`, `remove`, `test`
6. THE CliCompleter SHALL provide Tab completion for `skill` with subcommands `list`, `info`, `reload`

### Requirement 15: 用法帮助信息更新

**User Story:** As a CLI 用户, I want usage help messages to list all available subcommands, so that I can learn the full command set when I make mistakes.

#### Acceptance Criteria

1. WHEN the user inputs a command prefix without a subcommand (e.g., `todo`, `llm`, `mcp`), THE QuickCommand SHALL display a usage message listing all available subcommands for that command
2. WHEN the user inputs an unknown subcommand, THE QuickCommand SHALL display an error message and the complete usage help for that command group
3. THE usage help for each command group SHALL list all subcommands defined in the architecture document §8
