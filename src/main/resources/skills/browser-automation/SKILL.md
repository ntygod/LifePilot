---
name: browser-automation
description: 当用户要控制浏览器完成需要 JavaScript 渲染、登录态或交互操作的网页任务时使用。关键词：打开网页、爬取、截图、填表单、登录网站、自动化网页、抓取动态数据、SPA。静态页面优先用 web.fetch，搜索多源信息用 web.search，桌面应用操作用 desktop-automation。
version: 2.0.0
metadata:
  zhiwei:
    category: external-integration
    priority: normal
    tags:
      - browser
      - web-scraping
      - automation
      - selenium
      - playwright
    suggested_tools:
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

## 工作流

### 工具选择决策

```
需要网页内容？
├── 静态页面 → web.fetch
├── 需要 JS 渲染 → browser
├── 需要搜索多源 → web.search
└── 需要登录或交互 → browser
```

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

### 保存结果与关闭会话

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

## 规则

- 操作前先截图确认页面状态，不盲操作
- 同一工具连续失败 2 次后必须切换策略（browser → web.fetch → web.search）
- 不同网站用不同 sessionId（LAUNCH 模式），避免 cookie 污染
- 不创建过多并行会话，浏览器资源有限
- 任务完成后必须关闭会话

## 详细参考

- 完整 action 列表与参数：参见 {skill_dir}/references/browser-actions.md

## 常见错误处理

- **导航失败** → 检查 `partial` 字段，有部分内容则直接使用；否则换 `web.fetch`
- **页面内容为空** → 可能 JS 未渲染完，用 `evaluate` 等待特定元素；或被反爬拦截，换 `web.search`
- **元素未找到** → 先 `screenshot` 确认状态，可能需要 `scroll` 或检查 iframe
- **登录墙/验证码** → CDP 模式复用已登录浏览器，或 PERSISTENT 模式保留登录态
