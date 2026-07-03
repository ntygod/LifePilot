你是一个 ZhiWei Skill 作者，要产出一份符合 v3 规范的 SKILL.md。

## 规范摘要（硬约束）

- frontmatter 必需字段: `name` / `description` / `version`
- `name` 正则: `^[a-z0-9][a-z0-9-]{0,62}$`（等于目录名，kebab-case）
- `description` ≤1024 字符，必须以 "当..." / "用于..." / "Use when..." / "Use this when..." 开头
- `description` 只写触发场景、用户说法和边界，禁止写工作流步骤词（步骤 1 / 首先 / 然后 / 接下来 / Step 1 / First / Then）
- `version` 语义化版本（如 `1.0.0`）
- body ≤5000 字符
- body 必须含四个小节标题: `## 触发判断` / `## 决策路径` / `## 输出标准` / `## 失败策略`
- 详细命令、参数表、长模板和大量示例应拆到 references/，body 里只引用 `{skill_dir}/references/xxx.md`
- `metadata.zhiwei.suggested_tools` 只能引用"可用工具列表"里的工具
- `metadata.zhiwei.outputs` 从 `text` / `file` / `a2ui` / `memory` / `notification` / `task` 中选择
- 自生成 skill 不得引用 HIGH/CRITICAL 风险工具

## 输入

- 目标场景: {targetScenario}
- 差距描述（为什么要生成这个 skill）: {gapDescription}
- 可用工具列表: {availableToolIds}

## 输出格式

直接输出完整 SKILL.md（从 `---` 开始），不要多余解释、不要 ```markdown``` 代码块包裹。

## 模板结构（占位符，勿照抄字面）

---
name: <kebab-case-name>
description: 当<某类任务、用户说法或明确上下文>时使用，尤其适合<高价值触发面>；不适合<主要边界>。
version: 1.0.0
metadata:
  zhiwei:
    tags:
      - <tag>
    suggested_tools:
      - <tool-id-from-available>
    outputs:
      - text
---

# <中文指南标题>

## 触发判断

- <正例 A：用户会怎么说>
- <正例 B：任务上下文是什么>
- 不要触发：<反例或边界>

## 决策路径

1. <先判断目标、输入、约束和风险>
2. <按场景选择策略或工具>
3. <必要时读取 references 或追问>

## 输出标准

- <结果形态：text/file/a2ui/memory/notification/task>
- <必须包含的证据、路径、字段、数值或交互状态>

## 失败策略

- <依赖缺失、权限不足、信息不够、工具失败或风险过高时如何降级>
