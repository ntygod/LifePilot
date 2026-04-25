---
name: a2ui
description: 当需要让前端渲染可交互组件（按钮、表单、待办列表、筛选 Chip 等，点击后回调后端 signal）时使用。关键词：待办点击完成、操作按钮、可点击组件、表单提交、A2UI、渲染卡片、交互式 UI。纯展示内容（表格、列表、标题、代码）应使用 Markdown，不需要 A2UI。
version: 3.0.0
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

通过 `ui.render` 向前端提交组件树，用户点击后通过 signal 回调后端。判断依据：**组件不需要 signal，就不需要 A2UI**，改用 Markdown。

## 适用场景

- 待办列表（点击完成）→ ListItem + signal
- 操作按钮（触发动作）→ Button + signal
- 表单输入（提交数据）→ TextField + signal
- 选项切换（筛选标签）→ Chip + signal

## 不适用场景

- 纯展示表格 → Markdown 表格
- 步骤路线图 / 有序列表 → Markdown 列表 + 加粗
- 代码展示 → Markdown 代码块
- 标题层级 → Markdown 标题
- 数学公式 → KaTeX

## 工作流

1. **判断是否需要 signal**：不需要回调的内容用 Markdown，不强行套 A2UI
2. **选组件**：从已注册组件列表中选（详见参考）
3. **先文字说明上下文**，再调用 `ui.render` 渲染
4. **提交组件树**：每次最多 50 个组件；signal 放组件顶层，不进 properties
5. **id 唯一**：同一调用内组件 id 不能重复

## 详细参考

- 已注册组件完整目录 + ui.render 调用样例 + Markdown vs A2UI 选择矩阵：`{skill_dir}/references/component-catalog.md`
</content>
</invoke>