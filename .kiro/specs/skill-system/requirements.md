# Requirements Document

## 参考文档

- 架构设计：#[[file:docs/architecture/skill-system.md]]
- 特性设计：#[[file:docs/features/skill-system.md]]
- Skill 开发指南：#[[file:docs/features/skill-development.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]
- 前置 spec：#[[file:.kiro/specs/builtin-skills/requirements.md]]

## Introduction

本模块实现 LifePilot 技能系统的 YAML 声明式 Skill 热加载、Skill 自扩展能力和安全验证管线（Phase 3，模块 10）。

builtin-skills spec 已实现 Skill 框架核心（SkillDefinition、SkillRegistry、SubAgentFactory、SkillToToolBridge、SkillLifecycleManager、SkillDefinitionValidator、MemoryAccessEnforcer、SkillMetricsTracker 等）和三个内置 Skill（Todo、Schedule、Habit）。本 spec 在此基础上扩展，完成 USER_DEFINED 和 AUTO_GENERATED 两种 Skill 来源的完整支持。

本 spec 的范围包括：
1. YAML 声明式 Skill 解析与热加载（YamlSkillLoader + SkillFileWatcher）
2. YAML Skill 动作执行器（HTTP 调用、Shell 命令、技能串联）
3. 安全验证管线（格式校验 → 安全检查 → 沙箱试运行）三重验证
4. Skill 自扩展能力（SkillGapDetector + SkillGenerator）
5. 自生成 Skill 的用户确认与持久化
6. Skill 创建和使用记录的完整审计追溯

本 spec 不包括：内置 Skill 实现（已在 builtin-skills spec 完成）、SkillRegistry 核心逻辑（已完成）、SubAgentFactory 核心逻辑（已完成）。

## Glossary

- **Yaml_Skill_Loader**: YAML Skill 加载器，从 ~/.lifepilot/skills/ 目录扫描并解析 .yml 文件为 Skill_Definition，使用 SnakeYAML 解析，单个文件错误不影响其他文件加载
- **Skill_File_Watcher**: Skill 文件监听器，使用 Java NIO WatchService 监听 ~/.lifepilot/skills/ 目录的文件创建、修改和删除事件，触发热加载
- **Yaml_Schema_Validator**: YAML Schema 校验器，校验 YAML Skill 定义的结构合规性，包括必填字段、字段类型、值范围和 ID 格式
- **Skill_Action**: YAML Skill 动作 sealed interface，穷举四种动作类型：HttpAction（HTTP 调用）、ShellAction（Shell 命令）、ChainAction（技能串联）、TemplateAction（模板渲染）
- **Http_Action_Executor**: HTTP 动作执行器，使用 Spring WebClient 执行 HTTP 请求，支持 GET/POST/PUT/DELETE 方法、请求头、查询参数和请求体模板
- **Shell_Action_Executor**: Shell 命令执行器，在受限环境中执行 Shell 命令，强制超时限制，禁止危险命令
- **Chain_Action_Executor**: 技能串联执行器，按步骤顺序激活多个 Skill，前一步的输出作为后一步的输入参数
- **Skill_Action_Dispatcher**: 动作分发器，根据 Skill_Action 类型路由到对应的执行器
- **Skill_Gap_Detector**: Skill 缺口检测器，分析用户请求是否超出现有 Skill 的能力范围，使用语义匹配和 LLM 分析
- **Skill_Generator**: Skill 生成器，使用 LLM 根据 Skill 缺口描述自动生成 YAML Skill 定义
- **Skill_Validation_Pipeline**: Skill 验证管线，对自生成的 YAML Skill 执行格式验证 → 安全验证 → 沙箱验证三重验证
- **Format_Validator**: 格式验证器，校验 YAML 语法、必填字段、字段类型和值范围
- **Security_Validator**: 安全验证器，校验工具白名单、危险工具检测、记忆访问范围、预算上限和 Prompt 注入检测
- **Sandbox_Validator**: 沙箱验证器，在隔离环境中尝试构建 Skill_Definition，验证内部一致性和运行时兼容性
- **Skill_Audit_Repository**: Skill 审计仓库，持久化 Skill 的创建、激活、修改和删除事件到 SQLite，支持按 Skill ID 和时间范围查询
- **Skill_Gap**: Skill 缺口描述 record，包含置信度、建议的 Skill ID 和名称、触发请求、建议工具列表和分析原因
- **Skill_Validation_Result**: 验证管线结果 record，包含是否通过、失败阶段和错误信息列表
- **Dangerous_Command_Detector**: 危险命令检测器，维护 Shell 命令黑名单模式，拦截 rm -rf、sudo、chmod 等危险操作

## Requirements

### Requirement 1: YAML Skill 解析与加载

**User Story:** As a 用户, I want 将 YAML 文件放到 ~/.lifepilot/skills/ 目录即可注册新 Skill, so that 无需编写 Java 代码就能扩展 Agent 能力

#### Acceptance Criteria

1. WHEN 应用启动时, THE Yaml_Skill_Loader SHALL 扫描 ~/.lifepilot/skills/ 目录下所有 .yml 和 .yaml 后缀文件，解析为 Skill_Definition 并注册到 Skill_Registry
2. WHEN 目录不存在时, THE Yaml_Skill_Loader SHALL 自动创建 ~/.lifepilot/skills/ 目录并记录 INFO 日志
3. WHEN YAML 文件语法错误时, THE Yaml_Skill_Loader SHALL 记录 WARN 日志（包含文件名和错误详情）并跳过该文件，继续加载其他文件
4. WHEN YAML 文件 Schema 校验失败时, THE Yaml_Skill_Loader SHALL 记录 WARN 日志（包含文件名和校验错误列表）并跳过该文件
5. THE Yaml_Skill_Loader SHALL 将解析后的 Skill_Definition 的 source 设置为 SkillSource.UserDefined，包含文件路径和最后修改时间
6. THE Yaml_Skill_Loader SHALL 支持解析 YAML Skill 定义的所有字段：id、name、description、version、system-prompt、allowed-tools、execution（含 retry 子节点）、memory-access（含 read 和 write 子节点）、budget、metadata 和 provider-id
7. WHEN execution 或 budget 字段缺省时, THE Yaml_Skill_Loader SHALL 使用 ExecutionStrategy.DEFAULT 和 SkillBudget.DEFAULT 作为默认值
8. THE Yaml_Skill_Loader SHALL 支持解析 memory-access.read 中的 time-range 字段，支持 "7d"、"24h"、"30m" 格式转换为 Duration
9. FOR ALL 合法的 YAML Skill 文件, 加载后通过 Skill_Registry.find(skillId) 查询 SHALL 返回与 YAML 定义一致的 Skill_Definition（round-trip 属性）

### Requirement 2: YAML Schema 校验

**User Story:** As a 开发者, I want YAML Skill 定义在加载前经过 Schema 校验, so that 格式不合法的 YAML 文件不会进入注册中心

#### Acceptance Criteria

1. THE Yaml_Schema_Validator SHALL 校验 YAML 根节点包含 "skill" 键
2. THE Yaml_Schema_Validator SHALL 校验必填字段存在且非空：id、name、description、system-prompt、allowed-tools
3. THE Yaml_Schema_Validator SHALL 校验 id 格式为小写字母、数字和连字符组成，长度 1-64 字符
4. THE Yaml_Schema_Validator SHALL 校验 allowed-tools 为非空列表类型
5. THE Yaml_Schema_Validator SHALL 校验 execution.max-steps 不超过配置的上限（默认 50）、execution.timeout-seconds 不超过配置的上限（默认 600）
6. THE Yaml_Schema_Validator SHALL 校验 version 字段符合语义化版本格式（MAJOR.MINOR.PATCH）
7. THE Yaml_Schema_Validator SHALL 返回 ValidationResult record，包含 valid 布尔值和 errors 错误信息列表

### Requirement 3: YAML Skill 文件热加载

**User Story:** As a 用户, I want 修改或新增 YAML Skill 文件后无需重启应用即可生效, so that 我可以快速迭代 Skill 定义

#### Acceptance Criteria

1. THE Skill_File_Watcher SHALL 使用 Java NIO WatchService 监听 ~/.lifepilot/skills/ 目录的 ENTRY_CREATE、ENTRY_MODIFY 和 ENTRY_DELETE 事件
2. WHEN 检测到 .yml 或 .yaml 文件创建事件时, THE Skill_File_Watcher SHALL 加载该文件并注册到 Skill_Registry
3. WHEN 检测到 .yml 或 .yaml 文件修改事件时, THE Skill_File_Watcher SHALL 重新加载该文件并更新 Skill_Registry 中的注册
4. WHEN 检测到 .yml 或 .yaml 文件删除事件时, THE Skill_File_Watcher SHALL 从 Skill_Registry 注销对应的 Skill
5. THE Skill_File_Watcher SHALL 对文件变更事件进行防抖处理，防抖间隔通过 lifepilot.skills.hot-reload-debounce-ms 配置（默认 500ms）
6. WHEN 热加载过程中发生异常时, THE Skill_File_Watcher SHALL 记录 ERROR 日志并继续监听，不中断文件监听服务
7. THE Skill_File_Watcher SHALL 使用 AtomicBoolean 防止并发重载，当重载正在进行时跳过新的重载请求

### Requirement 4: YAML Skill 动作类型定义

**User Story:** As a 开发者, I want YAML Skill 支持多种动作类型, so that 用户可以通过声明式配置实现 HTTP 调用、Shell 命令和技能串联

#### Acceptance Criteria

1. THE Skill_Action SHALL 使用 sealed interface 实现，permits HttpAction、ShellAction、ChainAction、TemplateAction 四个 record 子类型
2. THE HttpAction SHALL 包含 method（GET/POST/PUT/DELETE）、url（支持 ${params.xxx} 和 ${env.XXX} 变量替换）、headers（Map）、query（Map）和 body（Object）字段
3. THE ShellAction SHALL 包含 command（支持 ${params.xxx} 变量替换）和 timeoutSeconds（默认 10，上限通过配置控制）字段
4. THE ChainAction SHALL 包含 steps 列表，每个 step 包含 skill（目标 Skill ID）、params（输入参数 Map，支持 ${前一步输出变量} 引用）和 output（输出变量名）字段
5. THE TemplateAction SHALL 包含 template 字符串字段，支持 ${result.xxx} 变量替换用于格式化输出
6. THE Yaml_Skill_Loader SHALL 根据 action.type 字段值（http/shell/chain/template）解析为对应的 Skill_Action 子类型

### Requirement 5: HTTP 动作执行器

**User Story:** As a 用户, I want YAML Skill 能够调用外部 HTTP API, so that 我可以通过声明式配置集成第三方服务

#### Acceptance Criteria

1. THE Http_Action_Executor SHALL 使用 Spring WebClient 执行 HTTP 请求，支持 GET、POST、PUT 和 DELETE 方法
2. WHEN URL 或请求体包含 ${params.xxx} 占位符时, THE Http_Action_Executor SHALL 使用 Skill 执行时的输入参数进行变量替换
3. WHEN 请求头包含 ${env.XXX} 占位符时, THE Http_Action_Executor SHALL 使用系统环境变量进行替换，环境变量不存在时记录 WARN 日志并使用空字符串
4. THE Http_Action_Executor SHALL 对 HTTP 请求设置超时限制，超时时间从 Skill_Definition 的 execution.timeoutSeconds 读取
5. IF HTTP 响应状态码为 4xx 或 5xx, THEN THE Http_Action_Executor SHALL 返回包含状态码和响应体的错误结果
6. THE Http_Action_Executor SHALL 对请求 URL 进行安全校验，拒绝访问 localhost、127.0.0.1、10.x.x.x、172.16-31.x.x、192.168.x.x 等内网地址（SSRF 防护）
7. IF HTTP 请求超时, THEN THE Http_Action_Executor SHALL 返回超时错误结果并记录 WARN 日志

### Requirement 6: Shell 命令执行器

**User Story:** As a 用户, I want YAML Skill 能够执行 Shell 命令, so that 我可以通过声明式配置调用本地命令行工具

#### Acceptance Criteria

1. THE Shell_Action_Executor SHALL 使用 ProcessBuilder 执行 Shell 命令，在独立进程中运行
2. THE Shell_Action_Executor SHALL 强制超时限制，超时时间从 ShellAction.timeoutSeconds 读取，超时后强制终止进程
3. WHEN 命令包含 ${params.xxx} 占位符时, THE Shell_Action_Executor SHALL 使用 Skill 执行时的输入参数进行变量替换
4. THE Dangerous_Command_Detector SHALL 维护危险命令模式黑名单，包含 rm -rf、sudo、chmod、chown、mkfs、dd、shutdown、reboot 等模式
5. WHEN 命令匹配危险命令模式时, THE Shell_Action_Executor SHALL 拒绝执行并返回安全错误结果
6. THE Shell_Action_Executor SHALL 捕获命令的标准输出和标准错误，返回包含 exitCode、stdout 和 stderr 的执行结果
7. IF 命令执行返回非零退出码, THEN THE Shell_Action_Executor SHALL 返回包含退出码和 stderr 内容的错误结果

### Requirement 7: 技能串联执行器

**User Story:** As a 用户, I want YAML Skill 能够串联多个 Skill 按顺序执行, so that 我可以通过声明式配置组合复杂工作流

#### Acceptance Criteria

1. THE Chain_Action_Executor SHALL 按 steps 列表顺序依次激活每个 Skill，通过 SubAgent_Factory 执行
2. WHEN step 的 params 包含 ${前一步output变量名.xxx} 引用时, THE Chain_Action_Executor SHALL 将前一步的输出结果注入为当前步的输入参数
3. IF 某个 step 执行失败, THEN THE Chain_Action_Executor SHALL 终止后续步骤并返回包含失败步骤索引和错误信息的结果
4. THE Chain_Action_Executor SHALL 限制串联步骤数不超过配置的上限（默认 5），超出时拒绝执行
5. THE Chain_Action_Executor SHALL 累计所有步骤的 Token 消耗，总消耗不超过 Skill_Definition 的 budget.maxTokens
6. FOR ALL 串联执行, 每个 step 引用的 Skill ID SHALL 在 Skill_Registry 中存在，不存在时返回错误结果

### Requirement 8: 动作分发与变量替换

**User Story:** As a 开发者, I want 统一的动作分发和变量替换机制, so that 所有动作类型共享一致的参数处理逻辑

#### Acceptance Criteria

1. THE Skill_Action_Dispatcher SHALL 根据 Skill_Action 的具体类型使用 switch 表达式穷举匹配，路由到对应的执行器
2. THE Skill_Action_Dispatcher SHALL 在分发前执行统一的变量替换，支持 ${params.xxx}（输入参数）、${env.XXX}（环境变量）和 ${result.xxx}（前序结果）三种占位符
3. IF Skill_Action 类型未知, THEN THE Skill_Action_Dispatcher SHALL 返回包含错误信息的结果
4. THE Skill_Action_Dispatcher SHALL 对变量替换后的最终值进行安全检查，拒绝包含 Shell 注入特殊字符（;、|、&&、`、$() 等）的参数值（仅 ShellAction 场景）

### Requirement 9: 安全验证管线 — 格式验证

**User Story:** As a 开发者, I want 自生成 Skill 的 YAML 经过格式验证, so that 语法错误和结构不合规的定义在第一道关卡被拦截

#### Acceptance Criteria

1. THE Format_Validator SHALL 校验 YAML 语法正确性，语法错误时返回包含解析错误详情的 ValidationResult
2. THE Format_Validator SHALL 校验必填字段存在且非空：skill.id、skill.name、skill.description、skill.system-prompt、skill.allowed-tools
3. THE Format_Validator SHALL 校验 skill.id 格式为小写字母、数字和连字符组成，长度 1-64 字符
4. THE Format_Validator SHALL 校验 execution.max-steps 不超过 50、execution.timeout-seconds 不超过 600
5. THE Format_Validator SHALL 校验 allowed-tools 为非空列表类型
6. THE Format_Validator SHALL 返回 ValidationResult record，格式验证失败时管线终止不进入安全验证阶段

### Requirement 10: 安全验证管线 — 安全验证

**User Story:** As a 开发者, I want 自生成 Skill 经过安全验证, so that 恶意工具引用、越权记忆访问和 Prompt 注入被拦截

#### Acceptance Criteria

1. THE Security_Validator SHALL 校验 allowed-tools 中的每个工具 ID 在已注册工具集合中存在
2. THE Security_Validator SHALL 校验 allowed-tools 不包含 HIGH 或 CRITICAL 风险等级的工具
3. THE Security_Validator SHALL 校验自生成 Skill 的记忆写权限必须设置 require-approval 为 true
4. THE Security_Validator SHALL 校验自生成 Skill 的预算不超过配置的上限：max-tokens 不超过 10000、max-steps 不超过 15、timeout-seconds 不超过 180、max-cost-cents 不超过 100
5. THE Security_Validator SHALL 使用正则模式检测 system-prompt 中的 Prompt 注入指令，包含 "ignore previous instructions"、"you are now a"、"disregard your rules"、"override system"、"jailbreak"、"DAN mode" 等模式
6. THE Security_Validator SHALL 返回 ValidationResult record，安全验证失败时管线终止不进入沙箱验证阶段

### Requirement 11: 安全验证管线 — 沙箱验证

**User Story:** As a 开发者, I want 自生成 Skill 在沙箱中试运行验证, so that 运行时异常和内部不一致在注册前被发现

#### Acceptance Criteria

1. THE Sandbox_Validator SHALL 尝试将 YAML 内容解析为 Skill_Definition record，构建失败时返回包含异常信息的 ValidationResult
2. THE Sandbox_Validator SHALL 校验 Skill_Definition 的内部一致性：system-prompt 长度不超过 5000 字符（沙箱限制）
3. THE Sandbox_Validator SHALL 校验工具列表数量不超过 10 个（沙箱限制）
4. THE Sandbox_Validator SHALL 使用临时文件进行解析，解析完成后清理临时文件
5. THE Sandbox_Validator SHALL 返回 ValidationResult record，沙箱验证失败时管线返回最终失败结果

### Requirement 12: 三重验证管线编排

**User Story:** As a 开发者, I want 格式验证、安全验证和沙箱验证按顺序串联执行, so that 验证管线高效且任一阶段失败即终止

#### Acceptance Criteria

1. THE Skill_Validation_Pipeline SHALL 按顺序执行格式验证 → 安全验证 → 沙箱验证，任一阶段失败时终止管线
2. THE Skill_Validation_Pipeline SHALL 返回 Skill_Validation_Result record，包含 passed 布尔值、failedStage（FORMAT/SECURITY/SANDBOX 枚举）和 errors 错误信息列表
3. WHEN 所有三个阶段通过时, THE Skill_Validation_Pipeline SHALL 返回 passed 为 true 的结果
4. THE Skill_Validation_Pipeline SHALL 在每个阶段开始和结束时记录 DEBUG 日志，管线最终结果记录 INFO 日志
5. FOR ALL 通过三重验证的 YAML 内容, 解析为 Skill_Definition 后注册到 Skill_Registry SHALL 成功（验证通过即可注册属性）

### Requirement 13: Skill 缺口检测

**User Story:** As a 用户, I want Agent 自动检测现有 Skill 无法处理的请求, so that Agent 可以主动提议创建新 Skill 来满足需求

#### Acceptance Criteria

1. WHEN 用户请求与所有已注册 Skill 的语义匹配度低于阈值（默认 0.6，通过 lifepilot.skills.auto-generation.gap-threshold 配置）时, THE Skill_Gap_Detector SHALL 判定存在 Skill 缺口
2. WHEN 判定存在缺口时, THE Skill_Gap_Detector SHALL 使用 LLM 分析缺口内容，生成 Skill_Gap record 包含置信度、建议的 Skill ID、名称、触发请求、建议工具列表和分析原因
3. WHEN Skill_Registry 中无任何已注册 Skill 时, THE Skill_Gap_Detector SHALL 直接判定存在缺口，置信度为 0.9
4. THE Skill_Gap_Detector SHALL 使用 LlmRouter 获取 ChatClient，路由键为 "skill-generation"
5. IF LLM 调用失败, THEN THE Skill_Gap_Detector SHALL 记录 WARN 日志并返回空结果，不阻塞主流程

### Requirement 14: Skill 自动生成

**User Story:** As a 用户, I want Agent 根据缺口分析自动生成 YAML Skill 定义, so that 新能力可以快速创建而无需手动编写 YAML

#### Acceptance Criteria

1. WHEN 接收到 Skill_Gap 时, THE Skill_Generator SHALL 使用 LLM 生成完整的 YAML Skill 定义
2. THE Skill_Generator SHALL 在生成 Prompt 中注入安全约束：只能引用已注册的非 HIGH/CRITICAL 风险工具、maxSteps 不超过 15、timeoutSeconds 不超过 180、maxTokens 不超过 10000、maxCostCents 不超过 100、记忆写权限必须设置 require-approval 为 true
3. THE Skill_Generator SHALL 在生成 Prompt 中包含已有 Skill 的摘要作为格式参考（最多 3 个示例）
4. THE Skill_Generator SHALL 使用 LlmRouter 获取 ChatClient，路由键为 "skill-generation"
5. THE Skill_Generator SHALL 对生成的 YAML 内容调用 Skill_Validation_Pipeline 执行三重验证
6. IF 三重验证失败, THEN THE Skill_Generator SHALL 记录 WARN 日志并返回包含验证错误的失败结果
7. IF LLM 调用失败, THEN THE Skill_Generator SHALL 记录 ERROR 日志并返回生成失败结果

### Requirement 15: 自生成 Skill 用户确认与持久化

**User Story:** As a 用户, I want 自生成 Skill 首次激活前需要我确认, so that 我可以审查和修改 Agent 自动创建的 Skill 定义

#### Acceptance Criteria

1. WHEN 自生成 Skill 通过三重验证后, THE Skill_Generator SHALL 返回待确认的 Skill_Definition，其 source 为 SkillSource.AutoGenerated 且 userConfirmed 为 false
2. WHEN 用户确认启用自生成 Skill 时, THE Skill_Generator SHALL 将 YAML 文件持久化到 ~/.lifepilot/skills/auto/ 目录，文件名为 {skill-id}.yml
3. WHEN 用户确认启用时, THE Skill_Generator SHALL 将 Skill_Definition 的 userConfirmed 更新为 true 并注册到 Skill_Registry
4. WHEN 用户拒绝自生成 Skill 时, THE Skill_Generator SHALL 记录 INFO 日志并丢弃该 Skill 定义
5. THE Skill_Generator SHALL 确保 ~/.lifepilot/skills/auto/ 目录存在，不存在时自动创建
6. WHEN 尝试激活 userConfirmed 为 false 的自生成 Skill 时, THE SubAgent_Factory SHALL 拒绝激活并返回需要用户确认的错误结果

### Requirement 16: Skill 审计追溯

**User Story:** As a 用户, I want 所有 Skill 的创建和使用记录完整可追溯, so that 我可以审查 Agent 的能力扩展历史和使用情况

#### Acceptance Criteria

1. THE Skill_Audit_Repository SHALL 使用 SQLite 持久化审计事件，skill_audit_logs 表包含 id(TEXT PK)、skill_id(TEXT NOT NULL)、event_type(TEXT NOT NULL)、event_detail_json(TEXT)、source_type(TEXT NOT NULL)、operator(TEXT NOT NULL)、created_at(TEXT NOT NULL) 字段
2. WHEN Skill 注册到 Skill_Registry 时, THE Skill_Audit_Repository SHALL 记录 REGISTERED 事件，包含 Skill 来源类型和定义摘要
3. WHEN Skill 从 Skill_Registry 注销时, THE Skill_Audit_Repository SHALL 记录 UNREGISTERED 事件
4. WHEN 自生成 Skill 通过三重验证时, THE Skill_Audit_Repository SHALL 记录 GENERATED 事件，包含触发请求和验证结果
5. WHEN 用户确认或拒绝自生成 Skill 时, THE Skill_Audit_Repository SHALL 记录 CONFIRMED 或 REJECTED 事件
6. WHEN Skill 被激活执行时, THE Skill_Audit_Repository SHALL 记录 ACTIVATED 事件，包含激活者 traceId 和输入参数摘要
7. THE Skill_Audit_Repository SHALL 提供 findBySkillId(skillId) 方法，返回指定 Skill 的所有审计事件列表，按 created_at 降序排列
8. THE Skill_Audit_Repository SHALL 提供 findByTimeRange(start, end) 方法，返回指定时间范围内的审计事件列表

### Requirement 17: Flyway 迁移与配置扩展

**User Story:** As a 开发者, I want Skill 审计表通过 Flyway 迁移创建, so that 数据库 Schema 变更可追踪且可重复

#### Acceptance Criteria

1. THE Flyway 迁移脚本 SHALL 创建 skill_audit_logs 表，包含 id(TEXT PK)、skill_id(TEXT NOT NULL)、event_type(TEXT NOT NULL)、event_detail_json(TEXT)、source_type(TEXT NOT NULL)、operator(TEXT NOT NULL)、created_at(TEXT NOT NULL) 字段
2. THE Flyway 迁移脚本 SHALL 在 skill_audit_logs 表的 skill_id 列上创建索引
3. THE Flyway 迁移脚本 SHALL 在 skill_audit_logs 表的 created_at 列上创建索引
4. THE SkillConfigProperties SHALL 扩展支持以下配置项：lifepilot.skills.directory（默认 ~/.lifepilot/skills）、lifepilot.skills.hot-reload-debounce-ms（默认 500）、lifepilot.skills.auto-generation.enabled（默认 true）、lifepilot.skills.auto-generation.gap-threshold（默认 0.6）、lifepilot.skills.shell-action.max-timeout-seconds（默认 30）、lifepilot.skills.chain-action.max-steps（默认 5）、lifepilot.skills.http-action.ssrf-protection-enabled（默认 true）

### Requirement 18: Spring 自动配置扩展

**User Story:** As a 开发者, I want 新增的 Skill 系统组件通过 Spring AutoConfiguration 自动装配, so that 所有 Bean 自动注册且可通过配置开关控制

#### Acceptance Criteria

1. THE SkillAutoConfiguration SHALL 通过 @Bean 方法注册 Yaml_Skill_Loader、Yaml_Schema_Validator、Skill_File_Watcher、Skill_Action_Dispatcher、Http_Action_Executor、Shell_Action_Executor、Chain_Action_Executor
2. THE SkillAutoConfiguration SHALL 通过 @Bean 方法注册 Skill_Gap_Detector、Skill_Generator、Skill_Validation_Pipeline、Format_Validator、Security_Validator、Sandbox_Validator
3. THE SkillAutoConfiguration SHALL 通过 @Bean 方法注册 Skill_Audit_Repository
4. WHEN lifepilot.skills.auto-generation.enabled 配置为 false 时, THE SkillAutoConfiguration SHALL 不注册 Skill_Gap_Detector 和 Skill_Generator Bean
5. THE SkillAutoConfiguration SHALL 在应用启动完成后（ApplicationReadyEvent）触发 Yaml_Skill_Loader 的初始加载和 Skill_File_Watcher 的监听启动

### Requirement 19: YAML Skill 解析的 Pretty Print 与 Round-Trip

**User Story:** As a 开发者, I want YAML Skill 定义支持从 Skill_Definition 序列化回 YAML 格式, so that 自生成 Skill 可以持久化为合法的 YAML 文件

#### Acceptance Criteria

1. THE Yaml_Skill_Serializer SHALL 将 Skill_Definition 序列化为符合 YAML Skill Schema 的 YAML 字符串
2. THE Yaml_Skill_Serializer SHALL 输出的 YAML 包含所有非默认值字段，省略与默认值相同的可选字段
3. FOR ALL 合法的 Skill_Definition 对象, 序列化为 YAML 后再解析回 Skill_Definition SHALL 产生语义等价的对象（round-trip 属性）
4. THE Yaml_Skill_Serializer SHALL 在自生成 Skill 持久化时被调用，将 Skill_Definition 写入 ~/.lifepilot/skills/auto/{skill-id}.yml
