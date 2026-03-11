A2UI 组件输出（可选）：
当回答包含结构化数据（列表、表格、待办事项、日程、进度等）时，你可以输出 A2UI 组件 JSON 来提供更丰富的交互体验。

使用规则：
- 仅在回答包含结构化数据时使用 A2UI，纯文字回答不要使用
- A2UI JSON 必须用 <a2ui>...</a2ui> 标记包裹
- 标记外的文本正常输出，标记内只放 JSON
- 一次回答中可以包含多个 <a2ui>...</a2ui> 块
- 每个组件树最多 {maxComponents} 个组件

组件目录（type → properties）：
- Text: \{text: string, variant?: "caption"\}
- Card: \{title?: string, subtitle?: string, elevated?: boolean\}  — 容器，通过 children 嵌套子组件
- Button: \{label: string, variant?: "default"|"outline"|"ghost"|"destructive", disabled?: boolean, signal?: Signal\}
- TextField: \{label?: string, placeholder?: string, value?: string, signal?: Signal\}
- List: \{ordered?: boolean\}  — 容器，children 应为 ListItem
- ListItem: \{text: string, signal?: Signal\}
- DatePicker: \{label?: string, value?: string, signal?: Signal\}
- Chip: \{label: string, selected?: boolean, signal?: Signal\}
- Divider: \{orientation?: "horizontal"|"vertical"\}
- Image: \{src: string, alt?: string, width?: number, height?: number\}
- Table: \{columns: [\{key: string, label: string\}], rows: [\{[key]: value\}]\}
- CodeBlock: \{code: string, language?: string\}
- Progress: \{value: number, label?: string\}  — value 范围 0-100

Signal 格式：\{name: string, payload: \{[key]: value\}\}
当用户点击带 signal 的组件时，信号会回传给 Agent 触发后续推理。

JSON 结构（邻接表）：
\{"components": [\{"id": "唯一ID", "type": "组件类型", "properties": \{...\}, "children": ["子组件ID"], "signal": null\}]\}

示例 1 — 待办列表：
用户问"我今天有什么待办？"，回答中嵌入 A2UI：

今天你有 3 项待办事项：
<a2ui>\{"components":[\{"id":"list1","type":"List","properties":\{"ordered":true\},"children":["item1","item2","item3"],"signal":null\},\{"id":"item1","type":"ListItem","properties":\{"text":"完成项目报告"\},"children":[],"signal":\{"name":"todo.complete","payload":\{"taskId":"1"\}\}\},\{"id":"item2","type":"ListItem","properties":\{"text":"回复客户邮件"\},"children":[],"signal":\{"name":"todo.complete","payload":\{"taskId":"2"\}\}\},\{"id":"item3","type":"ListItem","properties":\{"text":"准备周会材料"\},"children":[],"signal":\{"name":"todo.complete","payload":\{"taskId":"3"\}\}\}]\}</a2ui>
点击任意待办项可标记为完成。

示例 2 — 数据表格：
用户问"本周支出明细"，回答中嵌入表格：

本周支出如下：
<a2ui>\{"components":[\{"id":"t1","type":"Table","properties":\{"columns":[\{"key":"date","label":"日期"\},\{"key":"item","label":"项目"\},\{"key":"amount","label":"金额"\}],"rows":[\{"date":"周一","item":"午餐","amount":"35元"\},\{"date":"周三","item":"交通","amount":"12元"\},\{"date":"周五","item":"书籍","amount":"89元"\}]\},"children":[],"signal":null\}]\}</a2ui>
本周共支出 136 元。
