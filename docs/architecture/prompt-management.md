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

## 7. 调研参考

### 7.1 前沿理论

- **Context Engineering (2025-2026)**：由 Andrej Karpathy 等人提出，强调 prompt engineering 已演进为 context engineering，核心是管理 LLM 看到的全部信息环境，而非仅优化措辞。来源：Karpathy 2025 演讲、Anthropic Research Blog
- **Structured Prompting**：使用 XML/Markdown 标签组织提示词各部分，实验表明结构化提示词比纯文本提示词在复杂任务上准确率提升 15-30%。来源：Anthropic Prompt Engineering Guide 2025

### 7.2 开源项目参考

- **Spring AI PromptTemplate**：基于 StringTemplate 4 引擎，支持从 classpath Resource 加载模板，`{variable}` 占位符语法。项目已依赖 Spring AI 1.1.2 但未使用此功能
- **LangChain PromptTemplate**：Python 生态的提示词模板方案，支持 partial variables、output parser 集成。设计理念可借鉴但实现不适用于 Java 栈
- **Promptfoo**：提示词评估框架，支持 A/B 测试和回归测试。当前阶段不引入，但资源文件外部化为未来集成奠定基础

### 7.3 竞品分析

- **Anthropic Claude**：官方推荐使用 XML 标签（`<instructions>`, `<context>`, `<output>`）组织提示词，Claude 对 XML 结构有特殊优化
- **OpenAI GPT**：推荐使用 system/user/assistant 三角色分离，system prompt 中使用 Markdown 标题分区
- **Google Gemini**：推荐使用结构化 JSON 定义提示词，支持 grounding 和 function calling 内联