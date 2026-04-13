---
id: a2ui
name: "结构化 UI 输出"
description: "将结构化信息渲染为交互式前端组件（表格、列表、卡片、按钮等）。当回答包含表格数据、待办列表、操作按钮等结构化信息时使用。纯文本回答不使用 A2UI。"
version: "2.0.0"
suggested-tools: []
---

# A2UI 组件输出指南

当回答包含结构化信息时，输出 A2UI JSON 让前端渲染交互式界面。

## 适用场景

- 展示表格数据
- 展示待办列表（带交互）
- 展示操作按钮
- 展示卡片布局
- 展示进度条

## 不适用场景

- 纯文本回答 → 不输出 A2UI
- 代码展示 → 用 Markdown 代码块
- 图表可视化 → 用 data-analyst 生成图片

## 工作流

### 1. 判断是否需要 A2UI

仅在信息具有结构化特征（表格、列表、操作按钮）时使用。

### 2. 构建组件树

JSON 必须包裹在 `<a2ui>...</a2ui>` 中，每次最多一个。`<a2ui>` 标签外的文本照常输出。

结构：`{"components":[{"id":"string","type":"string","properties":{},"children":[],"signal":null}]}`

### 3. 输出

`<a2ui>` 标签内只放 JSON，标签外照常输出文字说明。

## 已注册组件

| 组件 | properties | signal |
|------|-----------|--------|
| **Text** | `{text, variant?: "caption"/"eyebrow"/"title"/"heading"}` | 无 |
| **Card** | `{title?, subtitle?, elevated?}` — 容器，通过 children 引用 | 无 |
| **Button** | `{label, variant?: "default"/"outline"/"ghost"/"destructive", disabled?}` | 有 |
| **TextField** | `{label?, placeholder?, value?}` | 有 |
| **List** | `{ordered?}` — 容器，children 指向 ListItem | 无 |
| **ListItem** | `{text}` | 有 |
| **DatePicker** | `{label?, value?}` | 有 |
| **Chip** | `{label, selected?}` | 有 |
| **Divider** | `{orientation?: "horizontal"/"vertical"}` | 无 |
| **Image** | `{src, alt?, width?, height?}` | 无 |
| **Table** | `{columns: [{key, label}], rows: [{[key]: value}]}` | 无 |
| **CodeBlock** | `{code, language?}` | 无 |
| **Progress** | `{value, label?}` — value 范围 0-100 | 无 |

## Signal 格式

`{name: string, payload: {[key]: value}}`

signal 放在组件顶层字段，不放在 properties 内。

## 示例

今天有 1 项待办：
```
<a2ui>{"components":[{"id":"list-1","type":"List","properties":{"ordered":true},"children":["item-1"],"signal":null},{"id":"item-1","type":"ListItem","properties":{"text":"提交周报"},"children":[],"signal":{"name":"todo.complete","payload":{"taskId":"1"}}}]}</a2ui>
```

## 规则

- 仅在需要结构化展示时使用，不为纯文本强加 A2UI
- 每次最多一个 `<a2ui>` 标签
- 单组件树最多 50 个组件
- signal 放顶层，不放 properties 内
- JSON 必须合法，组件 id 必须唯一
