# 浏览器 action 参考

`browser(action=...)` 路由，所有调用带 `sessionId="<task-name>"` 复用会话。

## action 清单

| action | 说明 | 关键参数 |
|---|---|---|
| `navigate` | 导航到 URL | `url` |
| `snapshot` | 截图 + 可交互元素编号表（**首选定位**） | `injectLabels`, `maxElements`, `viewportOnly` |
| `click` / `input` / `hover` | 点击 / 输入 / 悬停 | `index` 或 `selector`（二选一） |
| `scroll` | 滚动 | `direction`, `pixels` |
| `wait` | 等待元素 | `selector`, `state`, `timeout` |
| `select` | 下拉选择 | `selector`, `value` 或 `label` |
| `keyboard` | 键盘操作 | `type`, `key`（或 `text` 逐字符） |
| `screenshot` | 仅截图无元素表 | `fullPage` |
| `evaluate` | 执行 JS | `expression` |
| `accessibility` | 无障碍树 | `rootSelector`, `maxDepth` |
| `tab` | 标签页 | `tabAction`, `tabId`, `url` |
| `storage` | Cookie / localStorage | `target`, `storageAction`, `name` |
| `requestHumanTakeover` | 挂起让用户接管 | `reason` |
| `close` | 关闭会话 | `sessionId` |

## 典型流程

### 抓取动态页

```
browser(action="navigate", url="<url>", sessionId="<name>")
browser(action="snapshot", sessionId="<name>")
# 找到目标元素 index
browser(action="click", index=<n>, sessionId="<name>")
browser(action="wait", selector="<结果区>", state="visible", timeout=10, sessionId="<name>")
browser(action="evaluate",
        expression="JSON.stringify(Array.from(document.querySelectorAll('<sel>')).map(el => ({title: el.querySelector('h3').textContent, price: el.querySelector('.price').textContent})))",
        sessionId="<name>")
file_write(path="<输出路径>", content="<抓取数据>")
browser(action="close", sessionId="<name>")
```

### 表单填写

```
browser(action="navigate", url="<url>", sessionId="<name>")
browser(action="snapshot", sessionId="<name>")
browser(action="input", index=<n>, value="<值>", sessionId="<name>")
browser(action="select", selector="#country", value="CN", sessionId="<name>")
browser(action="click", index=<提交按钮 index>, sessionId="<name>")
browser(action="wait", selector="<成功提示>", state="visible", sessionId="<name>")
```

### 网页截图

```
browser(action="navigate", url="<url>", sessionId="<name>")
browser(action="screenshot", fullPage=true, sessionId="<name>")
browser(action="close", sessionId="<name>")
```

### 多步导航 + 标签页

```
browser(action="tab", tabAction="open", url="<url2>", sessionId="<name>")
browser(action="tab", tabAction="list", sessionId="<name>")
browser(action="tab", tabAction="switch", tabId="<id>", sessionId="<name>")
browser(action="tab", tabAction="close", tabId="<id>", sessionId="<name>")
```

## 元素定位 fallback 链

按优先级从高到低，连续 2 次失败切下一级：

1. `index`（snapshot 返回）—— 抗 layout 抖动，最稳
2. `id` 选择器：`#<unique-id>`
3. `data-testid`：`[data-testid="<id>"]`
4. 无障碍角色：`browser(action="accessibility", rootSelector=...)` 拿语义结构
5. CSS 选择器：`.<class> > <child>`
6. 仍失败 → 退 `web_fetch` → 退 `web_search` → 调 `requestHumanTakeover`

页面变化（导航 / 弹窗 / 异步渲染）后旧 elements 列表失效，**操作前重新 snapshot**。

## 人机接管（requestHumanTakeover）

**触发条件**（任一满足即调用，不要让 LLM 假装填密码 / 输验证码）：

- navigate 后 URL 含 `login` / `signin` / `auth` / `sso`
- snapshot elements 含 `type=password` 的 input
- 截图明显是登录页 / 验证码 / 人机验证 / 滑块
- 连续 2 次 snapshot elements 完全相同且无法推进（操作没生效）

**调用**：

```
browser(action="requestHumanTakeover",
        sessionId="<name>",
        reason="<简短用户语言>")
```

`reason` 由 Agent 根据观察自行组织（如"需要扫码登录"、"请输入短信验证码"、"触发了人机验证"），当前回合自动挂起，前端弹窗，用户点继续后从下一步恢复。

**不要用于**：页面加载慢、元素暂时未出现 → 这些用 `wait`。

## 会话模式（acquisitionMode）

仅首次创建会话生效。

| 模式 | 登录态 | 适用 | 必需参数 |
|---|---|---|---|
| `LAUNCH`（默认） | 会话内保持，关闭丢失 | 一般抓取与交互 | — |
| `CDP` | 复用用户已运行的 Chrome | 用户已登录的站点、需绕过验证码 | `cdpUrl` |
| `PERSISTENT` | 永久 profile，跨会话保留 | 长期反复访问需登录的站点 | `userDataDir` |

## 错误处理

| 现象 | 处理 |
|---|---|
| 导航返回 `partial: true` | 部分内容已渲染，可直接用；完整需要时配合 `wait` 或 `evaluate` 等关键元素 |
| 页面内容为空 | JS 未渲染完 → 加 `wait`；被反爬 → 换 `web_fetch` 或 `web_search` |
| `index` 返回 stale / not found | 重新 `snapshot` 对比 elements；2 次失败回落 `selector` |
| selector 也找不到 | 检查是否在 iframe 内；用 `accessibility` 看真实结构 |
| 登录墙 / 验证码 | `requestHumanTakeover`，长期反复访问换 `CDP` 或 `PERSISTENT` |
| `Browser closed` | 会话已关闭，重 `navigate` 起新 session |
