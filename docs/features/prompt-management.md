# 提示词管理特性说明

## 1. 功能概述

提示词管理特性将项目中 14 处硬编码 LLM 提示词统一外部化到 classpath 资源文件（`.st` 模板），通过 Spring AI PromptTemplate 实现模板化管理，同时对提示词内容进行结构化优化。

用户价值：
- 提示词独立于代码迭代，修改后无需重新编译
- 统一的模板引擎消除变量插值方式不一致问题
- 结构化提示词提升 LLM 响应质量和一致性

## 2. 核心特性

### 2.1 PromptRegistry 提示词注册中心

- 启动时自动扫描 `classpath:prompts/` 目录，注册所有 `.st` 模板文件
- 提供 `render(key, variables)` 统一渲染接口
- ConcurrentHashMap 缓存已加载模板，避免重复 I/O
- 模板键与文件路径映射：`agent/understanding` → `prompts/agent/understanding.st`

### 2.2 提示词模板外部化

- 所有 LLM 提示词从 Java 源码迁移到 `src/main/resources/prompts/` 目录
- 使用 Spring AI PromptTemplate（StringTemplate 4 引擎）
- 变量占位符：`{variable}` 语法
- 支持条件渲染和列表迭代

### 2.3 提示词内容结构化优化

- 采用 XML 标签分区：`<role>` / `<context>` / `<instructions>` / `<constraints>` / `<output_format>`
- 遵循 Context Engineering 范式优化信息密度
- 为关键提示词补充 few-shot 示例
- 统一输出格式约束

## 3. 使用场景

### 场景 1：修改 Agent 阶段提示词
编辑 `src/main/resources/prompts/agent/understanding.st`，重启应用即可生效，无需修改 Java 代码。

### 场景 2：新增 Skill 提示词
在 `src/main/resources/prompts/skill/` 下创建新的 `.st` 文件，PromptRegistry 自动发现并注册。

### 场景 3：动态变量注入
```java
var prompt = promptRegistry.render("proactive/evaluation", Map.of(
    "maxContentLength", config.getMaxContentLength(),
    "type", candidate.type(),
    "urgency", candidate.urgency(),
    "reason", candidate.reason()
));
```

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| lifepilot.prompt.base-path | classpath:prompts/ | 提示词模板根目录 |

## 5. Agent 提示词内容增强（Phase 2）

在 Phase 1 完成外部化基础设施后，Phase 2 聚焦于提升 Agent 阶段提示词的内容质量，基于 2025-2026 前沿调研成果（Context Engineering、Lost-in-the-Middle、Spring AI 结构化输出等）进行系统性优化。

### 5.1 角色定义增强

- 从单行描述扩展为四维角色定义：身份声明、核心能力范围、行为准则、交互风格
- 核心能力覆盖：待办管理、日程安排、习惯追踪、记忆管理、知识库检索、数据同步
- 行为准则：隐私保护、诚实承认能力边界、工具优先、主动澄清
- 交互风格：简洁友好、中文为主、适度 emoji
- 字数控制在 300 字以内，为动态上下文预留窗口预算

### 5.2 冗余格式指令清理

- 移除 UNDERSTANDING/PLANNING/REFLECTING 三个阶段的 `<constraints>`、`<output_format>`、`<examples>` 中的 JSON 格式指令
- 原因：Spring AI `.entity()` 自动注入 JSON Schema，手动格式指令冗余且可能冲突
- RESPONDING 阶段保留自然语言输出指令（不使用 `.entity()`）

### 5.3 记忆上下文使用引导

- 为 UNDERSTANDING/PLANNING/REFLECTING 三个阶段添加 `<context_guide>` 分区
- 解释四种上下文区域的含义和使用方式：相关记忆、知识库片段、跨会话参考、推理上下文
- 引导 LLM 将记忆内容视为参考而非绝对事实，不确定时主动确认

### 5.4 工具选择策略

- PLANNING 阶段新增 `<tool_strategy>` 分区
- 四项策略：直接匹配优先、数据依赖排序、参数提取与澄清、选择理由说明

### 5.5 反思评估维度

- REFLECTING 阶段新增 `<evaluation_dimensions>` 分区
- 三个评估维度：完整性、准确性、意图匹配
- 要求 needsReplanning=true 时提供具体调整方向

### 5.6 响应人格与风格

- RESPONDING 阶段新增 `<tone_style>` 和 `<response_structure>` 分区
- 基础语气：友好、简洁、专业
- 按任务类型适配：信息查询简洁直接、错误场景诚恳并提供替代方案
- 回复结构：核心回答 → 补充说明 → 后续建议

### 5.7 死代码清理与常量迁移

- 删除 `executing.st`（EXECUTING 阶段直接执行工具，不经过 LLM）
- 将 AgentLoop 中硬编码的 `STREAMING_OUTPUT_CONSTRAINT` 迁移到 `streaming-constraint.st` 模板

## 6. 限制与未来扩展

### 当前限制
- 模板仅支持 classpath 加载，不支持运行时热更新
- 无提示词版本管理和 A/B 测试能力
- 无提示词效果评估指标

### 未来扩展方向
- 集成 Promptfoo 进行提示词回归测试
- 支持文件系统监听实现热更新
- 提示词版本化 + 效果对比评估