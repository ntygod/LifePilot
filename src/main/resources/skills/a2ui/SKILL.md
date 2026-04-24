---
name: a2ui
description: 当需要让前端渲染可交互组件（按钮、表单、待办列表、筛选 Chip 等，点击后回调后端 signal）时使用。关键词：待办点击完成、操作按钮、可点击组件、表单提交、A2UI、ui.emit、交互式 UI。纯展示内容（表格、列表、标题、代码）应使用 Markdown，不需要 A2UI。
version: 3.0.0
metadata:
  zhiwei:
    category: infrastructure
    priority: normal
    tags:
      - ui
      - a2ui
      - interactive
      - component
      - signal
    suggested_tools:
      - ui.emit
---

# A2UI 组件输出指南

## 适用场景

- 待办列表（点击完成）→ ListItem + signal
- 操作按钮（触发动作）→ Button + signal
- 表单输入（提交数据）→ TextField + signal
- 选项切换（筛选标签）→ Chip + signal

**判断标准**：如果组件不需要 signal，就不需要 A2UI。

## 不适用场景

大部分结构化内容用 **Markdown** 即可美观呈现：

| 内容类型 | 推荐方式 | 示例 |
|----------|---------|------|
| 表格数据 | Markdown 表格 | `| 列1 | 列2 |` |
| 有序/无序列表 | Markdown 列表 | `1. 步骤一` |
| 标题层级 | Markdown 标题 | `## 二级标题` |
| 代码展示 | Markdown 代码块 | ` ```java ` |
| 步骤路线图 | Markdown 有序列表 + 加粗 | `1. **阶段一：基础** — 详细描述` |
| 数学公式 | KaTeX | `$E=mc^2$` |
| 重要提示 | GitHub 告警块 | `> [!NOTE]` |

## 工作流

通过 `ui.emit` 工具调用提交组件树：

```json
{
  "components": [
    {
      "id": "list-1",
      "type": "List",
      "properties": {"ordered": true},
      "children": ["item-1"],
      "signal": null
    },
    {
      "id": "item-1",
      "type": "ListItem",
      "properties": {"text": "提交周报"},
      "children": [],
      "signal": {"name": "todo.complete", "payload": {"taskId": "1"}}
    }
  ]
}
```

先用文字说明上下文，再调用 `ui.emit` 渲染交互组件。

## 已注册组件

| 组件 | properties | signal |
|------|-----------|--------|
| **Text** | `{text, variant?: "caption"/"eyebrow"/"title"/"heading"}` | 无 |
| **Card** | `{title?, subtitle?, elevated?}` — 容器 | 无 |
| **Button** | `{label, variant?: "default"/"outline"/"ghost"/"destructive", disabled?}` | 有 |
| **TextField** | `{label?, placeholder?, value?}` | 有 |
| **List** | `{ordered?}` — 容器 | 无 |
| **ListItem** | `{text}` | 有 |
| **DatePicker** | `{label?, value?}` | 有 |
| **Chip** | `{label, selected?}` | 有 |
| **Divider** | `{orientation?: "horizontal"/"vertical"}` | 无 |
| **Image** | `{src, alt?, width?, height?}` | 无 |
| **Table** | `{columns: [{key, label}], rows: [{[key]: value}]}` | 无 |
| **CodeBlock** | `{code, language?}` | 无 |
| **Progress** | `{value, label?}` — value 0-100 | 无 |

## 规则

- 每次调用最多 50 个组件
- signal 放组件顶层字段，不放 properties 内
- 组件 id 必须唯一
- 不需要 signal 的展示内容优先用 Markdown，不强行套 A2UI
