---
name: a2ui
description: 当需要让前端渲染可交互组件（按钮、表单、待办列表、筛选 Chip 等，点击后回调后端 signal）时使用。
version: 3.1.2
metadata:
  zhiwei:
    tags:
      - ui
      - a2ui
      - interactive
      - component
      - signal
    suggested_tools:
      - ui.render
    outputs:
      - a2ui
      - text
---
# A2UI 组件输出指南

通过 `ui_render` 向前端提交组件树（工具 ID 为 `ui.render`，模型调用名为 `ui_render`）。用户点击组件触发 signal 回到后端继续 Agent 流程。**判断依据：组件不需要 signal 就用 Markdown，不强行套 A2UI。**

## 触发判断
- 待办列表（点击完成）→ ListItem + signal
- 操作按钮（触发动作）→ Button + signal
- 表单输入（提交数据）→ TextField + signal
- 选项切换（筛选 Chip）→ Chip + signal
- 可点选列表（用户从中挑一个继续流程）

不要触发：

- 纯展示表格 → Markdown 表格
- 步骤路线图 / 有序列表 → Markdown 列表
- 代码 → Markdown 代码块
- 标题层级 → Markdown 标题
- 数学公式 → KaTeX
- 图片仅展示 → Markdown 图片

## 决策路径

1. **先判**：要回调后端的内容用 A2UI；纯展示用 Markdown
2. **选组件**：从注册组件目录选（Button / TextField / Chip / ListItem / Card 等，详见参考）
3. **先文字铺垫**：调 `ui_render` 前先用一句话说"这里给你 X 个选项"等上下文
4. **提交组件树**：每次最多 50 个组件；signal 放组件**顶层**，不进 properties
5. **id 唯一**：同一调用内组件 id 不能重复
6. **多步流程**：点击 signal → 后端处理 → 必要时再 `ui_render` 给下一步


## 输出标准

- 优先输出可被前端渲染的 A2UI 组件树；没有 signal 或状态变更需求时输出 Markdown。
- 组件必须包含明确 label、value、signal payload 和空/加载/错误状态。
- 交互结果要说明用户点击后会触发什么后端动作。


## 失败策略

- 缺少组件类型或 signal 参数时先追问，不猜 payload。
- 前端不支持目标组件时降级为 Markdown 列表或表单草案。
- 交互动作涉及高风险变更时只渲染确认组件，不直接执行。

## 详细参考
- 注册组件完整目录 + 调用样例 + Markdown vs A2UI 选择矩阵：`{skill_dir}/references/component-catalog.md`
