# A2UI 组件目录与用法

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

## ui.emit 调用样例

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

## Markdown vs A2UI 选择矩阵

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

## 硬约束

- 每次调用最多 50 个组件
- signal 放组件顶层字段，不放 properties 内
- 组件 id 必须唯一
- 不需要 signal 的展示内容优先用 Markdown，不强行套 A2UI
