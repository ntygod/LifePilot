# 提示词管理架构设计

## 1. 模块定位与职责边界

提示词管理模块负责将散落在 Java 源码中的硬编码 LLM 提示词统一外部化到 classpath 资源文件，并通过 Spring AI 的 PromptTemplate 机制实现模板化管理。

核心目标：
- 提示词与业务逻辑解耦，支持独立迭代
- 利用 Spring AI PromptTemplate + StringTemplate 引擎实现变量插值
- 提示词内容遵循 2025-2026 前沿最佳实践优化

## 2. 核心概念与术语

| 术语 | 定义 |
|------|------|
| Prompt Template | 包含 `{variable}` 占位符的提示词模板文件（`.st` 后缀） |
| PromptTemplate | Spring AI 提供的模板类，基于 StringTemplate 引擎渲染 |
| Context Engineering | 2025 年兴起的范式，强调管理 LLM 看到的全部信息而非仅措辞 |
| System Prompt | 设定 LLM 行为和角色的持久指令 |
| Structured Prompt | 使用明确分区（角色/上下文/约束/输出格式）组织的提示词 |
| PromptRegistry | 项目自定义的提示词注册中心，统一管理所有模板的加载和渲染 |

## 3. 现状分析

### 3.1 硬编码提示词清单

经全面扫描，项目中存在以下硬编码提示词（共 14 处）：

**A. Agent 核心系统提示词（1 处，最复杂）**

| 位置 | 类 | 方法/字段 | 行数 | 说明 |
|------|---|----------|------|------|
| agent/context | ContextAssembler | buildSystemPrompt() | ~110 行 | switch 表达式按 AgentPhase 生成 5 个阶段提示词 + 角色定义 + 约束 |

这是项目中最大的硬编码提示词，包含 UNDERSTANDING / PLANNING / EXECUTING / REFLECTING / RESPONDING 五个阶段的完整指令，每个阶段含任务描述、输出格式、示例。

**B. Skill 系统提示词（5 处，`private static final String SYSTEM_PROMPT`）**

| 位置 | 类 | 行数 | 说明 |
|------|---|------|------|
| skill/builtin/todo | TodoSkillProvider | ~30 行 | 待办管理助手角色定义 + 能力范围 + 交互原则 |
| skill/builtin/schedule | ScheduleSkillProvider | ~30 行 | 日程管理助手角色定义 |
| skill/builtin/habit | HabitSkillProvider | ~30 行 | 习惯追踪助手角色定义 |
| skill/builtin/memory | MemorySkillProvider | ~30 行 | 记忆管理助手角色定义 |
| sync/skill | SyncSkillProvider | ~50 行 | 数据同步助手角色定义 + 数据源说明 + 冲突策略 |

这 5 个 Skill 提示词结构高度相似：角色 → 核心职责 → 能力范围 → 交互原则 → 回复风格。

**C. 记忆系统提示词（3 处）**

| 位置 | 类 | 方法/字段 | 行数 | 说明 |
|------|---|----------|------|------|
| memory/compression | CompressionService | SUMMARY_PROMPT | ~12 行 | 对话摘要压缩，使用 `%s` 格式化 |
| memory/compression | CompressionService | KEYPOINTS_PROMPT | ~5 行 | 要点提取，使用 `%s` 格式化 |
| memory/forgetting | ForgettingEngine | buildCompressionPrompt() | ~18 行 | 记忆实体压缩为一句话摘要，使用 `%s.formatted()` |

**D. 知识库系统提示词（3 处）**

| 位置 | 类 | 方法/字段 | 行数 | 说明 |
|------|---|----------|------|------|
| knowledge/enricher | ChunkContextEnricher | buildBatchPrompt() | ~10 行 | 文档分块上下文前缀生成，StringBuilder 拼接 |
| knowledge/rerank | LlmReranker | scoreCandidate() | ~6 行 | Pointwise 相关性评分，text block |
| knowledge/rerank | LlmReranker | listwiseSinglePass() | ~8 行 | Listwise 文档排序，StringBuilder 拼接 |

**E. 其他模块提示词（2 处）**

| 位置 | 类 | 方法/字段 | 行数 | 说明 |
|------|---|----------|------|------|
| skill/generation | SkillGenerator | buildGenerationPrompt() | ~25 行 | Skill YAML 自动生成，StringBuilder 拼接 |
| memory/semantic | ConflictDetector | llmDisambiguate() | ~3 行 | 实体消歧义，字符串拼接 |
| agent/proactive | ProactiveReasoner | buildEvaluationPrompt() | ~8 行 | 主动推理评估，text block |

### 3.2 当前问题分析

**P1. 提示词与业务逻辑深度耦合**
- 提示词嵌入在 Java 方法体内，修改提示词必须重新编译部署
- 无法由非开发人员（如 Prompt Engineer）独立调优
- 代码审查时提示词变更与逻辑变更混杂，难以区分

**P2. 模板化能力缺失**
- 使用 `String.formatted()` / `StringBuilder` / 字符串拼接，无统一模板引擎
- 变量插值方式不一致（`%s` vs StringBuilder.append vs 字符串拼接）
- 无法复用公共片段（如角色定义、输出格式约束）

**P3. 提示词质量参差不齐**
- 部分提示词缺少结构化分区（如 ConflictDetector 仅 3 行）
- 输出格式约束不统一（有的要求 JSON，有的无格式要求）
- 缺少 few-shot 示例和边界条件处理指令

**P4. 无版本管理和 A/B 测试能力**
- 提示词变更混在代码提交中，无法独立追踪版本
- 无法对比不同版本提示词的效果

## 4. 架构设计方案

### 4.1 资源文件组织结构

```
src/main/resources/prompts/
├── agent/
│   ├── understanding.st          # UNDERSTANDING 阶段提示词
│   ├── planning.st               # PLANNING 阶段提示词
│   ├── executing.st              # EXECUTING 阶段提示词
│   ├── reflecting.st             # REFLECTING 阶段提示词
│   ├── responding.st             # RESPONDING 阶段提示词
│   ├── role-definition.st        # 角色定义（公共片段）
│   └── output-constraint.st      # 输出约束（公共片段）
├── skill/
│   ├── todo.st                   # 待办管理 Skill 提示词
│   ├── schedule.st               # 日程管理 Skill 提示词
│   ├── habit.st                  # 习惯追踪 Skill 提示词
│   ├── memory.st                 # 记忆管理 Skill 提示词
│   └── sync.st                   # 数据同步 Skill 提示词
├── memory/
│   ├── compression-summary.st    # 对话摘要压缩
│   ├── compression-keypoints.st  # 要点提取
│   └── entity-compression.st     # 实体压缩
├── knowledge/
│   ├── chunk-context.st          # 分块上下文生成
│   ├── rerank-pointwise.st       # Pointwise 精排
│   └── rerank-listwise.st        # Listwise 精排
├── proactive/
│   └── evaluation.st             # 主动推理评估
├── generation/
│   └── skill-generation.st       # Skill YAML 生成
└── semantic/
    └── entity-disambiguation.st  # 实体消歧义
```


### 4.2 Spring AI PromptTemplate 集成

Spring AI 1.1.2 提供 `PromptTemplate` 类，基于 StringTemplate 4 引擎：

```java
// 从 classpath 加载模板
var resource = new ClassPathResource("prompts/agent/understanding.st");
var template = new PromptTemplate(resource);

// 变量插值
Map<String, Object> variables = Map.of(
    "role", "LifePilot 智能助手",
    "maxContentLength", 200
);
String rendered = template.render(variables);
```

StringTemplate 语法要点：
- 变量占位符：`{variable}`
- 条件渲染：`{if(condition)}...{endif}`
- 列表迭代：`{items:{ item | ...}}`
- 转义大括号：`\{` 和 `\}`（JSON 输出格式中需要）

### 4.3 PromptRegistry 设计

```java
/**
 * 提示词注册中心 — 统一管理所有提示词模板的加载、缓存和渲染。
 */
public class PromptRegistry {

    private final Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();

    /**
     * 加载指定路径的提示词模板。
     *
     * @param key  模板键（如 "agent/understanding"）
     * @param path classpath 路径（如 "prompts/agent/understanding.st"）
     */
    public void register(String key, String path) { ... }

    /**
     * 渲染模板，返回最终提示词文本。
     *
     * @param key       模板键
     * @param variables 变量映射
     * @return 渲染后的提示词
     */
    public String render(String key, Map<String, Object> variables) { ... }

    /**
     * 获取原始模板（用于调试/日志）。
     */
    public Optional<PromptTemplate> getTemplate(String key) { ... }
}
```

PromptRegistry 在 Spring AutoConfiguration 中初始化，扫描 `classpath:prompts/` 目录自动注册所有 `.st` 文件。

### 4.4 与已有模块的集成点

| 消费方 | 当前方式 | 迁移后方式 |
|--------|---------|-----------|
| ContextAssembler | switch 表达式内联 | `promptRegistry.render("agent/" + phase.name().toLowerCase(), vars)` |
| BuiltinSkillProvider | `static final String` | `promptRegistry.render("skill/" + skillId, vars)` |
| CompressionService | `String.formatted()` | `promptRegistry.render("memory/compression-summary", vars)` |
| LlmReranker | text block / StringBuilder | `promptRegistry.render("knowledge/rerank-pointwise", vars)` |
| SkillGenerator | StringBuilder | `promptRegistry.render("generation/skill-generation", vars)` |
| ConflictDetector | 字符串拼接 | `promptRegistry.render("semantic/entity-disambiguation", vars)` |
| ProactiveReasoner | text block | `promptRegistry.render("proactive/evaluation", vars)` |
| ForgettingEngine | text block | `promptRegistry.render("memory/entity-compression", vars)` |

## 5. 提示词内容优化指南

### 5.1 结构化提示词模板（基于 2025-2026 最佳实践）

每个提示词模板应遵循以下分区结构：

```
<role>
角色定义：明确 LLM 扮演的角色和专业领域
</role>

<context>
上下文信息：当前任务的背景、约束条件、可用资源
</context>

<instructions>
具体指令：分步骤的任务描述，使用编号列表
</instructions>

<constraints>
约束条件：必须遵守的规则、禁止的行为
</constraints>

<output_format>
输出格式：期望的响应结构、JSON schema、示例
</output_format>

<examples>
示例（可选）：1-2 个 few-shot 示例，展示期望的输入输出
</examples>
```

### 5.2 关键优化原则

**来源：Anthropic Prompt Engineering Guide (2025)、OpenAI Best Practices、Context Engineering 范式**

1. **XML 标签分区**：使用 XML 标签（如 `<role>`, `<instructions>`）明确划分提示词各部分，LLM 对 XML 结构的理解优于纯文本分隔符
2. **指令前置**：将最重要的指令放在提示词开头，利用 LLM 的注意力分布特性
3. **正面表述优先**：使用"请做 X"而非"不要做 Y"，减少歧义
4. **输出格式锚定**：对需要结构化输出的场景，提供完整的 JSON schema 和示例
5. **边界条件处理**：明确说明异常情况的处理方式（如输入为空、格式错误）
6. **Context Engineering**：不仅优化措辞，更要优化 LLM 看到的全部信息（上下文窗口利用率、信息密度、噪声过滤）

### 5.3 StringTemplate 中的 XML 标签处理

由于 StringTemplate 使用 `<>` 作为默认分隔符可能与 XML 标签冲突，LifePilot 采用以下策略：
- 提示词模板使用 `{variable}` 占位符（Spring AI 默认配置）
- XML 标签直接写入模板文本，不会被 StringTemplate 解析
- 如需在 XML 标签内使用变量：`<role>{roleName}</role>`

## 6. 迁移策略

### 6.1 分批迁移顺序

按影响范围和复杂度分三批迁移：

**第一批（基础设施 + 简单提示词）**
1. 创建 PromptRegistry + AutoConfiguration
2. 迁移 Skill 系统提示词（5 处，结构统一，风险最低）
3. 迁移 ConflictDetector（最简单，仅 3 行）

**第二批（记忆 + 知识库提示词）**
4. 迁移 CompressionService（2 处）
5. 迁移 ForgettingEngine（1 处）
6. 迁移 ChunkContextEnricher（1 处）
7. 迁移 LlmReranker（2 处）

**第三批（复杂提示词 + 内容优化）**
8. 迁移 ContextAssembler（最复杂，5 个阶段提示词）
9. 迁移 SkillGenerator（动态参数多）
10. 迁移 ProactiveReasoner（1 处）
11. 对所有模板进行内容优化（结构化分区、few-shot 示例）

### 6.2 迁移验证

每个提示词迁移后，通过以下方式验证：
- 单元测试：模板加载 + 变量渲染 + 输出内容断言
- 集成测试：确认 PromptRegistry Bean 注入成功
- 回归测试：确认原有功能不受影响

## 7. 调研参考（2026-03 更新）

### 7.1 前沿理论

- **Context Engineering 范式 (2025-2026)**：Andrej Karpathy（2025 年 6 月）提出"LLM 是 CPU，上下文窗口是 RAM，你的工作是操作系统"。Phil Schmid（Hugging Face）指出"大多数 Agent 失败不是模型失败，而是上下文失败"。LangChain 总结四大策略：write（持久化）、select（RAG 检索）、compress（摘要压缩）、isolate（上下文隔离）。来源：[thomas-wiegold.com](https://thomas-wiegold.com/blog/prompt-engineering-best-practices-2026/)
- **Lost-in-the-Middle 问题**：Liu et al.（2024）发现 LLM 注意力呈 U 型曲线，中间位置信息准确率下降 30% 以上。设计启示：关键指令放在提示词开头和末尾，避免放在中间。来源：[arxiv.org/abs/2406.16008](https://arxiv.org/abs/2406.16008)
- **提示词长度甜区**：Levy/Jacoby/Goldberg（2024）研究表明 LLM 推理能力在约 3,000 token 后开始退化，每个提示词分区的实用甜区为 150-300 词。来源：[thomas-wiegold.com](https://thomas-wiegold.com/blog/prompt-engineering-best-practices-2026/)
- **ACE 框架（Stanford）**：Agentic Context Engineering 框架将上下文视为可演进的 playbook，通过 Generator/Reflector/Curator 三角色防止简洁偏差和上下文坍缩。来源：[arxiv.org/abs/2510.04618](https://arxiv.org/abs/2510.04618)
- **Structured Prompting**：使用 XML/Markdown 标签组织提示词各部分，实验表明结构化提示词比纯文本提示词在复杂任务上准确率提升 15-30%。来源：Anthropic Prompt Engineering Guide 2025
- **生产级 Agent 提示词五要素**：Identity & Role、Tool Definitions、Constraints、Output Format、Few-shot Examples。不可协商的约束放最前，工具语义次之，示例放最后。来源：[arunbaby.com](https://arunbaby.com/ai-agents/0003-prompt-engineering-for-agents/)

### 7.2 结构化输出与 Spring AI

- **Spring AI `.entity()` 结构化输出**：Spring AI 的 `.entity()` 方法通过 structured output converter 自动向 LLM 注入 JSON Schema，无需在提示词中手动指定输出格式。OpenAI Structured Outputs（2024 年 8 月）、Anthropic constrained decoding（2025 年 11 月）均已原生支持。设计启示：UNDERSTANDING/PLANNING/REFLECTING 三个使用 `.entity()` 的阶段应移除冗余的 JSON 格式指令，避免与自动注入的 Schema 冲突。来源：[spring.io](https://spring.io/blog/2024/05/09/spring-ai-structured-output/)

### 7.3 开源项目参考

- **Spring AI PromptTemplate**：基于 StringTemplate 4 引擎，支持从 classpath Resource 加载模板，`{variable}` 占位符语法。项目已依赖 Spring AI 1.1.2，prompt-optimization spec 已完成集成
- **LangChain PromptTemplate**：Python 生态的提示词模板方案，支持 partial variables、output parser 集成。设计理念可借鉴但实现不适用于 Java 栈
- **Promptfoo**：提示词评估框架，支持 A/B 测试和回归测试。当前阶段不引入，但资源文件外部化为未来集成奠定基础

### 7.4 竞品分析

- **Anthropic Claude**：官方推荐使用 XML 标签（`<instructions>`, `<context>`, `<output>`）组织提示词，Claude 对 XML 结构有特殊优化。注意：攻击性语言（"CRITICAL!"、"YOU MUST"）在新版 Claude 模型上反而降低效果。来源：[docs.anthropic.com](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/use-xml-tags)
- **OpenAI GPT**：推荐使用 system/user/assistant 三角色分离，system prompt 中使用 Markdown 标题分区
- **Google Gemini**：推荐使用结构化 JSON 定义提示词，支持 grounding 和 function calling 内联

### 7.5 记忆上下文安全

- **上下文来源标注**：标记上下文来源（用户输入 vs 工具输出 vs 检索文档），将不可信的 RAG 片段与系统指令分离。记忆内容应视为假设而非事实："如不确定，请确认"。来源：[arunbaby.com](https://arunbaby.com/ai-agents/0003-prompt-engineering-for-agents/)、[casaba.com](https://www.casaba.com/docs/agentic-ai-security/data-rag-memory/)

## 8. Agent 提示词内容增强设计

> 本节基于 §7 调研结论，定义 Agent 阶段提示词的内容增强方向。实现细节见 `.kiro/specs/agent-prompt-enhancement/design.md`。

### 8.1 核心设计决策

| 决策 | 选择 | 理由（引用调研） |
|------|------|----------------|
| 移除结构化输出阶段的 JSON 格式指令 | 是 | Spring AI `.entity()` 自动注入 JSON Schema（§7.2），手动指令冗余且可能冲突 |
| 角色定义控制在 300 字以内 | 是 | 提示词长度甜区 150-300 词（§7.1 Levy et al.），角色定义应简洁以预留上下文预算 |
| 添加记忆上下文使用引导 | 是 | 记忆内容需标注来源和可信度（§7.5），引导 LLM 正确区分事实依据和参考信息 |
| 关键指令放在提示词首尾 | 是 | Lost-in-the-Middle U 型注意力曲线（§7.1 Liu et al.），中间位置信息易被忽略 |
| 使用 XML 标签分区 | 是 | Anthropic 推荐（§7.4），LLM 对 XML 结构理解优于纯文本分隔符 |
| 避免攻击性语言 | 是 | Anthropic 研究表明"CRITICAL!"等语言在新模型上降低效果（§7.4） |

### 8.2 各阶段增强要点

| 阶段 | 增强内容 | 对应调研 |
|------|---------|---------|
| role-definition | 四维角色定义（身份/能力/准则/风格），≤300 字 | §7.1 提示词长度甜区、§7.1 五要素 Identity |
| understanding | 移除 JSON 格式指令 + `<context_guide>` 记忆引导 + 复杂度典型场景 + 时间规范化 | §7.2 Spring AI、§7.5 上下文安全、§7.1 Context Engineering |
| planning | 移除 JSON 格式指令 + `<context_guide>` + `<tool_strategy>` 工具选择策略 | §7.2 Spring AI、§7.1 五要素 Tool Definitions |
| reflecting | 移除 JSON 格式指令 + `<context_guide>` + `<evaluation_dimensions>` 三维评估 | §7.2 Spring AI、§7.1 ACE 框架 Reflector 角色 |
| responding | `<tone_style>` 人格风格 + `<response_structure>` 回复结构 | §7.1 五要素 Constraints、§7.4 Anthropic 风格指南 |
| executing | 删除（死代码，EXECUTING 阶段直接执行工具不经过 LLM） | 代码审计结论 |
| streaming-constraint | 新增，从 AgentLoop 硬编码常量迁移 | §5.2 提示词与业务逻辑解耦原则 |