# 浏览器 action 完整参考

## action 清单

| action | 说明 | 关键参数 |
|--------|------|---------|
| `navigate` | 导航到 URL | `url` |
| `click` | 点击元素 | `selector` |
| `input` | 输入文本 | `selector`, `value` |
| `scroll` | 滚动页面 | `direction`, `pixels` |
| `wait` | 等待元素 | `selector`, `state`, `timeout` |
| `hover` | 鼠标悬停 | `selector` |
| `select` | 选择下拉项 | `selector`, `value` |
| `keyboard` | 键盘操作 | `type`, `key` |
| `screenshot` | 截图 | `fullPage` |
| `evaluate` | 执行 JS | `expression` |
| `accessibility` | 获取无障碍树 | `rootSelector`, `maxDepth` |
| `tab` | 标签页管理 | `tabAction`, `tabId`, `url` |
| `storage` | Cookie/localStorage | `target`, `storageAction` |
| `close` | 关闭会话 | `sessionId` |

## 典型示例

### 导航并获取内容

```
browser(action="navigate", url="https://example.com", sessionId="task-name")
```

同一任务用相同 `sessionId`，复用 cookie 和页面状态。导航返回 `partial: true` 时内容仍可用。

### 截图确认状态

```
browser(action="screenshot", sessionId="task-name")
```

操作前截图确认页面状态，避免盲操作。

### 交互操作

```
browser(action="click", selector="#search-btn", sessionId="task-name")
browser(action="input", selector="#search-input", value="搜索内容", sessionId="task-name")
browser(action="scroll", direction="down", pixels=500, sessionId="task-name")
browser(action="wait", selector=".result-list", state="visible", timeout=10, sessionId="task-name")
browser(action="evaluate", expression="JSON.stringify(...)", sessionId="task-name")
```

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
browser(action="hover", selector=".menu-item", sessionId="task-name")
```

### 保存结果与关闭

```
file.write(path="output/data.json", content="抓取的数据")
browser(action="close", sessionId="task-name")
```

完成后必须关闭，释放浏览器资源。

## 会话模式

| 模式 | 登录态 | 适用场景 |
|------|--------|---------|
| LAUNCH（默认） | 会话内保持，关闭后丢失 | 一般抓取和交互 |
| CDP | 复用用户已登录的 Chrome | 需要登录或遇到验证码的站点 |
| PERSISTENT | 首次登录后永久保留 | 长期反复访问需登录的站点 |

## 元素定位策略

优先级从高到低：

1. `id` 选择器：`#unique-id`
2. `data-testid`：`[data-testid="submit"]`
3. 无障碍角色：通过 `browser(action="accessibility")` 获取元素树
4. CSS 选择器：`.class-name > child`

## 常见错误处理

- **导航失败** → 检查 `partial` 字段，有部分内容则直接使用；否则换 `web.fetch`
- **页面内容为空** → 可能 JS 未渲染完，用 `evaluate` 等待特定元素；或被反爬拦截，换 `web.search`
- **元素未找到** → 先 `screenshot` 确认状态，可能需要 `scroll` 或检查 iframe
- **登录墙/验证码** → CDP 模式复用已登录浏览器，或 PERSISTENT 模式保留登录态
</content>
</invoke>