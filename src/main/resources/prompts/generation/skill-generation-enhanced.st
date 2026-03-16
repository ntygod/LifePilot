请根据以下需求生成一个 SKILL.md 格式的 Skill 定义。

SKILL.md 格式说明：文件由 YAML Frontmatter（以 --- 分隔）和 Markdown 正文两部分组成。
Frontmatter 包含元数据字段，Markdown 正文作为 Skill 的 instructions（指令）。

<constraints>
## 安全约束（必须遵守）
- suggested-tools 只能引用已注册的非 HIGH/CRITICAL 风险工具
- maxSteps 不超过 {maxSteps}
- timeoutSeconds 不超过 {maxTimeout}
- maxTokens 不超过 {maxTokens}
- maxCostCents 不超过 {maxCostCents}
</constraints>

<brand_style>
## ZhiWei 品牌风格约束
生成的 Skill instructions 必须遵循以下风格规范：
- 角色定位：以"ZhiWei 的 XX 能力"开头描述角色（如"ZhiWei 的待办管理能力"）
- 语气风格：简洁务实、温和友好，中文为主
- 结构规范：使用 `<role>`、`<instructions>`、`<constraints>` 三段式结构
- constraints 段必须包含"回复风格：简洁务实、温和友好"
</brand_style>

<tool_manifest>
## 可用工具能力清单
以下是当前 Agent 已注册的所有工具，按能力分组。生成的 Skill 的 suggested-tools 必须从此清单中选择：
{toolManifest}
</tool_manifest>

<requirements>
## 需求描述
{gapDescription}
</requirements>

<examples>
## 已有 Skill 参考
参考以下已有 Skill 的风格和结构模式（`<role>` + `<instructions>` + `<constraints>` 三段式），确保生成的 Skill 与已有 Skill 风格一致：
{existingSkills}
</examples>

<template>
## 模板参考
以下是一个高质量 Skill 模板示例，请参考其结构和风格：
{templateExample}
</template>

<output>
## 输出要求
请直接输出 SKILL.md 内容，格式如下：

---
id: skill-id
name: Skill 名称
description: 一句话描述
version: 1.0.0
suggested-tools:
  - tool-a
  - tool-b
---

# Skill 名称

这里是 instructions 内容，使用 Markdown 格式编写。

注意：
- Frontmatter 必须以 --- 开头和结尾
- 必填字段：id、name、description
- suggested-tools、metadata 为可选字段，省略时使用默认值
- Frontmatter 之后的 Markdown 正文作为 instructions（指令），不能为空
</output>
