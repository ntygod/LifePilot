你是一个 ZhiWei Skill 作者，要产出一份符合 v2 规范的 SKILL.md。

## 规范摘要（硬约束）

- frontmatter 必需字段: `name` / `description` / `version`
- `name` 正则: `^[a-z0-9][a-z0-9-]{0,62}$`（等于目录名，kebab-case）
- `description` ≤1024 字符，必须以 "当...时使用" 或 "Use when..." 开头
- `description` **禁止写工作流步骤词**（步骤 1 / 首先 / 然后 / 接下来 / Step 1 / First / Then）
- `version` 语义化版本（如 `1.0.0`）
- body（frontmatter 之后的 Markdown 正文）≤5000 字符
- body 必须含三个小节标题: `## 适用场景` / `## 不适用场景` / `## 工作流`
- 详细内容超长时应拆到 references/，body 里只写 "参见 {{skill_dir}}/references/xxx.md"
- 如果声明了 `metadata.zhiwei.suggested_tools`，工具必须存在于"可用工具列表"中
- 自生成 skill 不得引用 HIGH/CRITICAL 风险工具（如 shell.exec）

## 输入

- 目标场景: {targetScenario}
- 差距描述（为什么要生成这个 skill）: {gapDescription}
- 可用工具列表: {availableToolIds}

## 输出格式

直接输出完整 SKILL.md（从 `---` 开始），不要多余解释、不要 ```markdown``` 代码块包裹。

## 模板结构（占位符，勿照抄字面）

---
name: <kebab-case-name>
description: 当 <某场景> 时使用。关键词 <k1>, <k2>, <k3>
version: 1.0.0
metadata:
  zhiwei:
    suggested_tools: [<tool-id-from-available>]
    tags: [<tag>]
    category: <external-integration | content-creation | automation | infrastructure | utility>
    priority: <high | normal | low>
---

# <中文指南标题>

## 适用场景
- <场景 A 描述>
- <场景 B 描述>

## 不适用场景
- <反例 A>
- <反例 B>

## 工作流
1. <步骤 A>
2. <步骤 B>
3. <步骤 C>
