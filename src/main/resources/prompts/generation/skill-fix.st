上一次生成的 SKILL.md 校验失败，需要修正后重新输出。

## 失败原因

{errorMessage}

## 上一次输出

{previousSkillMd}

## 修正任务

1. 读失败原因，精确定位需要改哪里
2. 保留正确部分，只修改出错的地方
3. 重新输出完整 SKILL.md（从 `---` 开始），不要多余解释、不要 ```markdown``` 代码块包裹

## v3 规范回顾（硬约束）

- frontmatter 必需字段: `name` / `description` / `version`
- `name` 正则: `^[a-z0-9][a-z0-9-]{0,62}$`（kebab-case，等于目录名）
- `description` ≤1024 字符，必须以 "当..." / "用于..." / "Use when..." / "Use this when..." 开头
- `description` 禁止写工作流词（步骤 1 / 首先 / 然后 / 接下来 / Step 1 / First / Then）
- `version` 语义化版本（如 `1.0.0`）
- body ≤5000 字符，必须含 `## 触发判断` / `## 决策路径` / `## 输出标准` / `## 失败策略` 四个小节
- `metadata.zhiwei.suggested_tools` 声明的工具必须已注册且非 HIGH/CRITICAL 风险
- `metadata.zhiwei.outputs` 只能使用 `text` / `file` / `a2ui` / `memory` / `notification` / `task`

## 上下文（原始需求）

- 差距描述: {gapDescription}
