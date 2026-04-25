# 浏览器 action 完整参考

## action 清单

| action | 说明 | 关键参数 |
|--------|------|---------|
| `navigate` | 导航到 URL | `url` |
| `snapshot` | 截图 + 可交互元素标号（**首选定位**） | `injectLabels`, `maxElements`, `viewportOnly` |
| `click` | 点击元素 | `index` **或** `selector` |
| `input` | 输入文本 | `index` **或** `selector`, `value` |
| `hover` | 鼠标悬停 | `index` **或** `selector` |
| `scroll` | 滚动页面 | `direction`, `pixels` |
| `wait` | 等待元素 | `selector`, `state`, `timeout` |
| `select` | 选择下拉项 | `selector`, `value` |
| `keyboard` | 键盘操作 | `type`, `key` |
| `screenshot` | 截图（仅图，无元素列表） | `fullPage` |
| `evaluate` | 执行 JS | `expression` |
| `accessibility` | 获取无障碍树 | `rootSelector`, `maxDepth` |
| `tab` | 标签页管理 | `tabAction`, `tabId`, `url` |
| `storage` | Cookie/localStorage | `target`, `storageAction` |
| `requestHumanTakeover` | 暂停让用户接管（验证码/登录/扫码） | `reason` |
| `close` | 关闭会话 | `sessionId` |

## 典型示例

### 导航并获取内容

```
browser(action="navigate", url="https://example.com", sessionId="task-name")
```

同一任务用相同 `sessionId`，复用 cookie 和页面状态。导航返回 `partial: true` 时内容仍可用。

### snapshot：截图 + 元素编号（推荐首选）

```
browser(action="snapshot", sessionId="task-name")
```

一次返回：

- `screenshot`：当前截图（vision 输入自动生效）
- `elements`：可交互元素数组，每项 `{index, tag, role, text, name, id, ariaLabel, bbox}`
- `total` / `truncated` / `viewport` / `url` / `title`

后续 click/input/hover 优先用 `index`（定位更稳、抗 layout 抖动），只在 snapshot 不可用或元素未被识别时退回选择器。

### 交互操作：首选 index，其次 selector

`click` / `input` / `hover` 的 `index` 与 `selector` **二选一**：

```
browser(action="click", index=12, sessionId="task-name")
browser(action="input", index=8, value="搜索内容", sessionId="task-name")
browser(action="click", selector="#search-btn", sessionId="task-name")  # 退回方式
browser(action="scroll", direction="down", pixels=500, sessionId="task-name")
browser(action="wait", selector=".result-list", state="visible", timeout=10, sessionId="task-name")
browser(action="evaluate", expression="JSON.stringify(...)", sessionId="task-name")
```

页面变化（导航 / 弹窗 / 异步渲染）后旧的 elements 列表失效，操作前重新 `snapshot`。

### 提取结构化数据

```
browser(action="evaluate",
  expression="JSON.stringify(Array.from(document.querySelectorAll('.item')).map(el => ({title: el.querySelector('h3').textContent, price: el.querySelector('.price').textContent})))",
  sessionId="task-name")
```

### 其他交互

```
browser(action="select", selector="#country", value="CN", sessionId="task-name")
browser(action="keyboard", key="Enter", type="key", sessionId="task-name")
browser(action="hover", index=5, sessionId="task-name")
```

### 保存结果与关闭

```
file.write(path="output/data.json", content="抓取的数据")
browser(action="close", sessionId="task-name")
```

完成后必须关闭，释放浏览器资源。

## 元素定位策略与 fallback 链

优先级从高到低：

1. `index`（snapshot 返回）—— 首选，最稳
2. `id` 选择器：`#unique-id`
3. `data-testid`：`[data-testid="submit"]`
4. 无障碍角色：通过 `browser(action="accessibility")` 获取元素树
5. CSS 选择器：`.class-name > child`

失败 fallback：

- `click(index=N)` 返回 stale / not found → 重新 `snapshot` 对比 elements 列表是否变化
- 连续 2 次 index 失败 → 回落到 selector
- selector 也 2 次失败 → 换策略（browser → web.fetch → web.search）

## 登录墙识别与人机接管

**客观触发条件**（任一满足即调 `requestHumanTakeover`）：

- navigate 后 URL 含 `login` / `signin` / `auth` 关键词
- snapshot elements 中存在 `type=password` 的 input
- 截图明显是登录页 / 验证码 / 人机验证
- 连续 2 次 snapshot 的 elements 完全相同且 Agent 无法推进（说明操作没生效）

**调用方式**：

```
browser(action="requestHumanTakeover", sessionId="task-name", reason="需要扫码登录")
```

- `reason` 简短、用户语言（如 "需要扫码登录"、"请输入短信验证码"、"触发了人机验证"），具体措辞 Agent 根据观察自行组织
- 当前回合自动挂起，前端弹窗提示用户在浏览器内完成操作
- 用户点 "继续" 后 Agent 自动恢复，从下一步继续

**不要用于**：页面加载慢、元素暂时未出现 — 这些用 `wait`。

## 会话模式

| 模式 | 登录态 | 适用场景 |
|------|--------|---------|
| LAUNCH（默认） | 会话内保持，关闭后丢失 | 一般抓取和交互 |
| CDP | 复用用户已登录的 Chrome | 需要登录或遇到验证码的站点 |
| PERSISTENT | 首次登录后永久保留 | 长期反复访问需登录的站点 |

## 常见错误处理

- **导航失败** → 检查 `partial` 字段，有部分内容则直接使用；否则换 `web.fetch`
- **页面内容为空** → 可能 JS 未渲染完，用 `evaluate` 等待特定元素；或被反爬拦截，换 `web.search`
- **元素未找到** → 先 `snapshot` 或 `screenshot` 确认状态，可能需要 `scroll` 或检查 iframe
- **登录墙/验证码** → 调 `requestHumanTakeover` 让用户接管；长期访问可换 CDP 或 PERSISTENT 模式保留登录态
