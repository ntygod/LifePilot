---
name: a2ui
description: 当需要让前端渲染可交互组件（按钮、表单、待办列表、筛选 Chip 等，点击后回调后端 signal）时使用。关键词：待办点击完成、操作按钮、可点击组件、表单提交、A2UI、渲染卡片、交互式 UI、点选、能选的列表。纯展示内容（表格、列表、标题、代码）应使用 Markdown，不需要 A2UI。
version: 3.1.0
metadata:
  zhiwei:
    priority: normal
    tags:
      - ui
      - a2ui
      - interactive
      - component
      - signal
    suggested_tools:
      - ui.render
---

# A2UI 组件输出指南

通过 `ui.render` 向前端提交组件树。用户点击组件触发 signal 回到后端继续 Agent 流程。**判断依据：组件不需要 signal 就用 Markdown，不强行套 A2UI。**

## 适用场景

- 待办列表（点击完成）→ ListItem + signal
- 操作按钮（触发动作）→ Button + signal
- 表单输入（提交数据）→ TextField + signal
- 选项切换（筛选 Chip）→ Chip + signal
- 可点选列表（用户从中挑一个继续流程）

## 不适用场景

- 纯展示表格 → Markdown 表格
- 步骤路线图 / 有序列表 → Markdown 列表
- 代码 → Markdown 代码块
- 标题层级 → Markdown 标题
- 数学公式 → KaTeX
- 图片仅展示 → Markdown 图片

## 工作流

1. **先判**：要回调后端的内容用 A2UI；纯展示用 Markdown
2. **选组件**：从注册组件目录选（Button / TextField / Chip / ListItem / Card 等，详见参考）
3. **先文字铺垫**：调 `ui.render` 前先用一句话说"这里给你 X 个选项"等上下文
4. **提交组件树**：每次最多 50 个组件；signal 放组件**顶层**，不进 properties
5. **id 唯一**：同一调用内组件 id 不能重复
6. **多步流程**：点击 signal → 后端处理 → 必要时再 `ui.render` 给下一步

## 详细参考

- 注册组件完整目录 + 调用样例 + Markdown vs A2UI 选择矩阵：`{skill_dir}/references/component-catalog.md`
