# A2UI 组件目录与样例

后端 `A2uiComponentCatalog` 是组件契约的唯一真实来源；本文与之同步。`signal=有` 的组件被点击/提交后会回调到后端继续 Agent 流程。

## 已注册组件清单

| 组件 | properties | signal | 备注 |
|------|-----------|--------|------|
| Text | `{text, variant?: caption\|eyebrow\|title\|heading}` | 无 | 纯文本展示 |
| Card | `{title?, subtitle?, elevated?}` | 无 | 容器，children 引用子组件 id |
| Button | `{label, variant?: default\|outline\|ghost\|destructive, disabled?}` | 有 | 操作按钮 |
| TextField | `{label?, placeholder?, value?}` | 有 | 文本输入，提交时回传 value |
| List | `{ordered?}` | 无 | 容器，children 应指向 ListItem |
| ListItem | `{text}` | 有 | 列表行 |
| DatePicker | `{label?, value?}` | 有 | 日期选择 |
| Chip | `{label, selected?}` | 有 | 单选/筛选标签 |
| Divider | `{orientation?: horizontal\|vertical}` | 无 | 分隔线 |
| Image | `{src, alt?, width?, height?}` | 无 | src 仅支持 http(s)/`/`/`data:image/` |
| Table | `{columns: [{key,label}], rows: [{[key]: value}]}` | 无 | columns ≤12，rows ≤200 |
| CodeBlock | `{code, language?}` | 无 | code ≤12000 字符 |
| Progress | `{value, label?}` | 无 | value 范围 0-100 |

## 顶层结构

```json
{
  "components": [
    {
      "id": "<唯一 id>",
      "type": "<上表组件名>",
      "properties": { ... },
      "children": ["<子组件 id>", ...],
      "signal": { "name": "<事件名>", "payload": { ... } }
    }
  ]
}
```

`signal` 是顶层字段，**不能放进 `properties`**（放进去校验直接拒）。

## 调用样例

### 单个 Button

```json
{
  "components": [
    {
      "id": "btn-confirm",
      "type": "Button",
      "properties": {"label": "确认", "variant": "default"},
      "children": [],
      "signal": {"name": "todo.complete", "payload": {"taskId": "1"}}
    }
  ]
}
```

### List + ListItem（容器嵌套）

```json
{
  "components": [
    {
      "id": "todo-list",
      "type": "List",
      "properties": {"ordered": true},
      "children": ["item-1", "item-2"],
      "signal": null
    },
    {
      "id": "item-1",
      "type": "ListItem",
      "properties": {"text": "提交周报"},
      "children": [],
      "signal": {"name": "todo.complete", "payload": {"taskId": "1"}}
    },
    {
      "id": "item-2",
      "type": "ListItem",
      "properties": {"text": "回复客户邮件"},
      "children": [],
      "signal": {"name": "todo.complete", "payload": {"taskId": "2"}}
    }
  ]
}
```

### Chip 多选筛选

```json
{
  "components": [
    {
      "id": "chip-tech",
      "type": "Chip",
      "properties": {"label": "科技", "selected": false},
      "children": [],
      "signal": {"name": "filter.toggle", "payload": {"tag": "tech"}}
    }
  ]
}
```

## Markdown vs A2UI 选择矩阵

不需要回调后端的展示内容**优先用 Markdown**：

| 内容类型 | 推荐方式 |
|----------|---------|
| 表格数据展示 | Markdown 表格 |
| 步骤路线图 / 有序列表 | Markdown 有序列表 + 加粗 |
| 标题层级 | Markdown 标题 |
| 代码展示 | Markdown 代码块（不含 signal 才用） |
| 数学公式 | KaTeX `$E=mc^2$` |
| 图片仅展示 | Markdown 图片 |
| 重要提示 | GitHub 告警块 `> [!NOTE]` |
| 待办列表（点击完成） | A2UI ListItem + signal |
| 操作按钮（触发动作） | A2UI Button + signal |
| 表单收集 | A2UI TextField + Button |

## 硬约束（校验失败会被拒）

- 单次 `ui.render` 组件数 ≤ `app.web.a2ui.max-components-per-tree`（默认 50）
- 组件 `id` 同次调用内必须唯一
- `signal` 必须在组件**顶层**，放 `properties` 内会被拒
- `signal.name` ≤ 120 字符；`signal.payload` 条目 ≤ 16
- 组件 `type` 必须在上表清单内，未注册类型会被拒
- 组件树最大层级 12，不能有循环引用（A.children=[B], B.children=[A]）
- `children` 引用的 id 必须在同一次 `components` 数组内

## 长度限制速查

| 字段 | 上限 |
|------|------|
| Text/ListItem 的 text、TextField.value、Card.subtitle | 4000 |
| Button.label / Chip.label / 其他 label / placeholder | 120 |
| CodeBlock.code | 12000 |
| Table 单元格内容 | 500 |
| Image.width / height | 必须 > 0 |

## 常见错误处理

- **"未注册类型 X"** → 类型名拼错或大小写错（必须 PascalCase 严格匹配清单）
- **"signal 必须放在顶层字段"** → 把 signal 从 properties 移到组件顶层
- **"properties.X 必须是字符串/boolean"** → 类型不符，参考清单的 properties 签名
- **"children 引用了不存在的 id"** → 子组件 id 没在同次 components 数组里
- **"超过长度限制"** → 内容截断或拆多次渲染
- **组件类型不支持 signal** → Text/Card/List/Divider/Image/Table/CodeBlock/Progress 没有 signal，需要交互换成 Button/ListItem/Chip
