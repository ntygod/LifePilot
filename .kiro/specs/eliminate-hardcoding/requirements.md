# Requirements Document

## Introduction

LifePilot 项目在快速迭代过程中，部分模块遗留了硬编码的魔法数字和字符串常量。这些硬编码值分散在 TokenBudgetAllocator、SkillDefinitionValidator、DataSourceConfig、BuiltinSkill 注解和 SkillRegistry 中，导致运维人员无法在不修改代码的情况下调整系统行为。

本次重构的目标是：
1. 将已识别的硬编码常量提取到 `*Properties.java` 配置类 + `application.yml`，遵循项目已有的外部化配置模式
2. 更新 `coding-standards.md` 编码规范，增加"禁止硬编码"条款，防止后续开发引入新的硬编码

本次重构不改变任何现有行为，所有提取的配置值使用原硬编码值作为默认值。

参考文档：
- 编码规范：`.kiro/steering/coding-standards.md`
- Git 工作流：`.kiro/steering/git-workflow.md`
- Spec 工作流：`.kiro/steering/spec-workflow.md`

## Glossary

- **TokenBudgetAllocator**: Token 预算分配器，根据上下文窗口大小和会话状态动态分配四区域预算
- **SkillDefinitionValidator**: Skill 定义校验器，在注册前校验 SkillDefinition 的合法性
- **DataSourceConfig**: SQLite 数据源配置类，设置 PRAGMA 参数
- **SkillRegistry**: Skill 注册中心，管理 Skill 的注册、注销和搜索
- **BuiltinSkill**: 内置 Skill 注解，标注在 BuiltinSkillProvider 实现类上声明加载顺序
- **MemoryProperties**: 记忆系统配置属性类，绑定 `lifepilot.memory` 前缀
- **SkillProperties**: 待创建的 Skill 系统配置属性类，绑定 `lifepilot.skill` 前缀
- **DataSourceProperties**: 待创建的数据源配置属性类，绑定 `lifepilot.datasource` 前缀
- **application.yml**: Spring Boot 外部化配置文件
- **coding-standards.md**: LifePilot 编码规范 steering 文档

## Requirements

### Requirement 1: TokenBudgetAllocator 预算比例外部化

**User Story:** As a 运维人员, I want to 通过配置文件调整 Token 预算分配比例, so that 无需修改代码即可优化不同场景下的预算分配策略。

#### Acceptance Criteria

1. THE MemoryProperties SHALL 包含 Token 预算分配的固定比例配置项：系统提示词比例（默认 0.10）和用户消息比例（默认 0.15）
2. THE MemoryProperties SHALL 包含动态分配策略的配置项：高相关度场景的工作记忆比例（默认 40）和检索比例（默认 35），长对话场景的工作记忆比例（默认 60）和检索比例（默认 15），默认场景的工作记忆比例（默认 50）和检索比例（默认 25）
3. THE MemoryProperties SHALL 包含阈值配置项：高相关度判断阈值（默认 0.9）和长对话轮次判断阈值（默认 10）
4. WHEN TokenBudgetAllocator 执行预算分配时, THE TokenBudgetAllocator SHALL 从 MemoryProperties 读取所有比例和阈值，替代原有的硬编码常量
5. THE application.yml SHALL 在 `lifepilot.memory` 前缀下声明所有新增的 Token 预算分配配置项及其默认值

### Requirement 2: SkillDefinitionValidator 校验限制外部化

**User Story:** As a 运维人员, I want to 通过配置文件调整 Skill 定义的校验限制, so that 无需修改代码即可适配不同规模的 Skill 定义。

#### Acceptance Criteria

1. THE SkillProperties SHALL 包含校验限制配置项：名称最大长度（默认 128）、System Prompt 最大长度（默认 10000）、AUTO_GENERATED 来源的 maxTokens 上限（默认 10000）、maxSteps 上限（默认 15）和 timeoutSeconds 上限（默认 180）
2. WHEN SkillDefinitionValidator 执行校验时, THE SkillDefinitionValidator SHALL 从 SkillProperties 读取所有校验限制，替代原有的硬编码常量
3. THE application.yml SHALL 在 `lifepilot.skill` 前缀下声明所有校验限制配置项及其默认值

### Requirement 3: DataSourceConfig SQLite PRAGMA 外部化

**User Story:** As a 运维人员, I want to 通过配置文件调整 SQLite 的 busy_timeout 参数, so that 无需修改代码即可针对不同负载场景优化数据库连接行为。

#### Acceptance Criteria

1. THE DataSourceProperties SHALL 包含 SQLite PRAGMA 配置项：busy-timeout（默认 5000）
2. WHEN DataSourceConfig 创建数据源时, THE DataSourceConfig SHALL 从 DataSourceProperties 读取 busy-timeout 值，替代原有的硬编码常量
3. THE application.yml SHALL 在 `lifepilot.datasource` 前缀下声明 busy-timeout 配置项及其默认值

### Requirement 4: SkillRegistry 搜索限制外部化

**User Story:** As a 运维人员, I want to 通过配置文件调整 Skill 语义搜索的默认返回数量, so that 无需修改代码即可优化搜索结果的精度和数量。

#### Acceptance Criteria

1. THE SkillProperties SHALL 包含搜索限制配置项：默认搜索返回数量（默认 10）
2. WHEN SkillRegistry 执行语义搜索时, THE SkillRegistry SHALL 从 SkillProperties 读取默认搜索返回数量，替代原有的硬编码常量
3. THE application.yml SHALL 在 `lifepilot.skill` 前缀下声明搜索限制配置项及其默认值

### Requirement 5: 编码规范更新 — 禁止硬编码条款

**User Story:** As a 开发者, I want to 在编码规范中明确禁止硬编码的规则, so that 后续开发不会引入新的魔法数字和字符串。

#### Acceptance Criteria

1. THE coding-standards.md SHALL 新增"配置外部化"章节，明确规定以下规则：
   - 业务可调参数（阈值、比例、限制值、超时时间）禁止在代码中硬编码，必须通过 `*Properties.java` + `application.yml` 外部化
   - 每个模块的配置类使用 `@ConfigurationProperties(prefix = "lifepilot.{module}")` 绑定
   - 配置项在 `application.yml` 中声明默认值，配置键使用 kebab-case
   - 纯技术常量（正则表达式、协议版本号、数学常数）允许保留为代码常量
2. THE coding-standards.md SHALL 提供合规和违规的代码示例，帮助开发者理解规则边界

### Requirement 6: 行为不变性保证

**User Story:** As a 开发者, I want to 确保重构后系统行为与重构前完全一致, so that 配置外部化不会引入任何功能回归。

#### Acceptance Criteria

1. THE 重构后的代码 SHALL 使用原硬编码值作为所有新增配置项的默认值
2. WHEN 未在 application.yml 中覆盖任何新增配置项时, THE 系统 SHALL 产生与重构前完全相同的行为
3. THE 现有单元测试 SHALL 在不修改测试断言的前提下全部通过
