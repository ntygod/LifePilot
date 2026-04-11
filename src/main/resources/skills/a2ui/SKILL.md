---
id: a2ui
name: "结构化 UI 输出"
description: "将结构化信息渲染为交互式前端组件（表格、列表、卡片、按钮等）"
version: "1.0.0"
suggested-tools: []
triggers:
  - 表格
  - 列表展示
  - 交互式
  - 结构化输出
  - 卡片
  - A2UI
---

# A2UI 组件输出指南

当回答包含结构化信息（表格数据、列表、操作按钮等）时，输出 A2UI JSON 让前端渲染交互式界面。

## 规则

- 仅在需要结构化展示时使用；纯文本不输出 A2UI
- JSON 必须包裹在 `<a2ui>...</a2ui>` 中，每次最多一个
- `<a2ui>` 标签外的文本照常输出，标签内只放 JSON
- signal 放在组件顶层字段，不放在 properties 内
- 单组件树最多 50 个组件

## 已注册组件（type → properties）

- **Text**: `{text: string, variant?: "caption"|"eyebrow"|"title"|"heading"}`
- **Card**: `{title?: string, subtitle?: string, elevated?: boolean}` — 容器组件，通过 children 引用子组件
- **Button**: `{label: string, variant?: "default"|"outline"|"ghost"|"destructive", disabled?: boolean}`, 顶层 signal?: Signal
- **TextField**: `{label?: string, placeholder?: string, value?: string}`, 顶层 signal?: Signal
- **List**: `{ordered?: boolean}` — 容器组件，children 应指向 ListItem
- **ListItem**: `{text: string}`, 顶层 signal?: Signal
- **DatePicker**: `{label?: string, value?: string}`, 顶层 signal?: Signal
- **Chip**: `{label: string, selected?: boolean}`, 顶层 signal?: Signal
- **Divider**: `{orientation?: "horizontal"|"vertical"}`
- **Image**: `{src: string, alt?: string, width?: number, height?: number}`
- **Table**: `{columns: [{key: string, label: string}], rows: [{[key]: value}]}`
- **CodeBlock**: `{code: string, language?: string}`
- **Progress**: `{value: number, label?: string}` — value 范围 0-100

## Signal 格式

`{name: string, payload: {[key]: value}}`

## 组件树结构

`{"components":[{"id":"string","type":"string","properties":{},"children":[],"signal":null}]}`

## 示例

今天有 1 项待办：
```
<a2ui>{"components":[{"id":"list-1","type":"List","properties":{"ordered":true},"children":["item-1"],"signal":null},{"id":"item-1","type":"ListItem","properties":{"text":"提交周报"},"children":[],"signal":{"name":"todo.complete","payload":{"taskId":"1"}}}]}</a2ui>
```
