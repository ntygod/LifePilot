---
id: browser-automation
name: "浏览器自动化"
description: "控制浏览器完成网页交互、信息抓取和自动化操作。用户说「打开网页」「帮我爬取」「截个图」「填表单」「登录这个网站」「自动化操作网页」「抓取数据」时使用。静态页面优先用 web.fetch，不需要浏览器。"
version: "3.1.0"
suggested-tools:
  - browser
  - web.fetch
  - web.search
  - file.write
---

# 浏览器自动化指南

通过浏览器工具完成需要 JavaScript 渲染、登录态或交互操作的网页任务。

## 适用场景

- 需要 JavaScript 渲染的动态页面（SPA、电商搜索结果）
- 需要登录后访问的页面
- 表单自动填写和提交
- 网页截图和视觉验证
- 多步骤网页操作流程

## 不适用场景

- 静态页面抓取（文档、博客、新闻） → 优先用 `web.fetch`
- API 接口测试 → 用 api-debugger
- 桌面应用操作 → 用 desktop-automation
- 搜索多个来源的信息 → 用 `web.search`

## 工具选择决策

```
需要网页内容？
├── 静态页面 → web.fetch
├── 需要 JS 渲染 → browser
├── 需要搜索多源 → web.search
└── 需要登录或交互 → browser
```

## 推荐工作流

### 1. 导航到页面

同一任务用相同 `sessionId` 复用 cookie 和页面状态。导航返回 `partial: true` 时内容仍可用。

### 2. snapshot 获取截图 + 元素编号（首选）

`browser(action="snapshot")` 一次性返回：
- `screenshot`：当前截图（vision 输入自动生效）
- `elements`：可交互元素数组，每项 `{index, tag, role, text, name, id, ariaLabel, bbox}`
- `total` / `truncated` / `viewport` / `url` / `title`

后续 click/input/hover 优先用 `index`（定位更稳、抗 layout 抖动），只在 snapshot 不可用或元素未被识别时退回选择器。

### 3. 交互操作：首选 index，其次 selector

`click` / `input` / `hover` 的 `index` 与 `selector` **二选一**：
- 已调用 snapshot → 用 `index`（如 `click(index=12)`）
- 未调用或 index 无效 → 用 `selector`（id > data-testid > 语义 CSS）

其它 action：`scroll` / `wait` / `select` / `keyboard` 仍按原参数使用。

### 4. 页面变化后重新 snapshot

导航、弹窗、异步渲染会改变 elements 列表。操作后若要继续交互，先重新 `snapshot`。

### 5. 提取数据 → 保存 → 关闭

- 结构化提取：`evaluate(expression=...)`
- 保存：`file.write(path=..., content=...)`
- 关闭：`browser(action="close", sessionId="...")` 释放资源

### 选择器失败 fallback 链

- `click(index=N)` 返回 stale/not found → 重新 snapshot 对比 elements 列表是否变化
- 连续 2 次 index 失败 → 回落到 selector
- selector 也 2 次失败 → 换策略（browser → web.fetch → web.search）

### 登录墙识别与人机接管

**客观触发条件**（任一满足即调 `requestHumanTakeover`）：
- navigate 后 URL 含 `login` / `signin` / `auth` 关键词
- snapshot elements 中存在 `type=password` 的 input
- 截图明显是登录页 / 验证码 / 人机验证
- 连续 2 次 snapshot 的 elements 完全相同且 Agent 无法推进（说明操作没生效）

**调用方式**：`browser(action="requestHumanTakeover", sessionId="...", reason="...")`

- `reason` 要简短、用户语言（如"需要扫码登录"、"请输入短信验证码"、"触发了人机验证"），具体措辞 Agent 根据观察自行组织
- 当前回合自动挂起，前端弹窗提示用户在浏览器内完成操作
- 用户点"继续"后 Agent 自动恢复，从下一步继续

**不要**用于：页面加载慢、元素暂时未出现 — 这些用 `wait`。

## 会话模式

| 模式 | 登录态 | 适用场景 |
|------|--------|---------|
| **LAUNCH**（默认） | 会话内保持，关闭后丢失 | 一般抓取和交互 |
| **CDP** | 复用用户已登录的 Chrome | 需要登录或遇到验证码的站点 |
| **PERSISTENT** | 首次登录后永久保留 | 长期反复访问需登录的站点 |

## 元素定位策略

**Phase 1 新增首选项**：snapshot → index，最稳。下面是回退链：

1. `index`（snapshot 返回）——首选
2. `id` 选择器：`#unique-id`
3. `data-testid`：`[data-testid="submit"]`
4. 无障碍角色：通过 `browser(action="accessibility")` 获取元素树
5. CSS 选择器：`.class-name > child`

## 规则

- 操作前先截图确认页面状态，不盲操作
- 同一工具连续失败 2 次后必须切换策略（browser → web.fetch → web.search）
- 不同网站用不同 sessionId（LAUNCH 模式），避免 cookie 污染
- 不创建过多并行会话，浏览器资源有限
- 任务完成后必须关闭会话

## 常见错误处理

- **导航失败** → 检查 `partial` 字段，有部分内容则直接使用；否则换 `web.fetch`
- **页面内容为空** → 可能 JS 未渲染完，用 `evaluate` 等待特定元素；或被反爬拦截，换 `web.search`
- **元素未找到** → 先 `screenshot` 确认状态，可能需要 `scroll` 或检查 iframe
- **登录墙/验证码** → CDP 模式复用已登录浏览器，或 PERSISTENT 模式保留登录态

## 完整 action 列表

| action | 说明 | 关键参数 |
|--------|------|---------|
| `navigate` | 导航到 URL | `url` |
| `snapshot` | 截图 + 可交互元素标号（**推荐首选**） | `injectLabels`, `maxElements`, `viewportOnly` |
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
