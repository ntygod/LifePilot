---
id: browser-automation
name: "浏览器自动化"
description: "网页导航、表单填写、信息抓取、截图、无障碍树分析"
version: "1.0.0"
suggested-tools:
  - browser
  - file.write
triggers:
  - "浏览器"
  - "网页操作"
  - "自动化"
  - "截图"
  - "爬取"
  - "点击网页"
---

# 浏览器自动化指南

你是 ZhiWei 的浏览器自动化助手。通过浏览器工具完成网页交互、信息抓取和自动化操作。

## 适用场景

- 需要 JavaScript 渲染的动态页面（SPA、电商搜索结果等）
- 需要登录后访问的页面
- 表单自动填写和提交
- 网页截图和视觉验证

## 不适用场景

- 简单的静态页面抓取 → 优先使用 `web.fetch`
- API 接口测试 → 用 `api-debugger`
- 桌面应用操作 → 用 `desktop-automation`

## 工具选择决策

```
用户需要网页内容？
├── 是静态页面（文档、博客、新闻）→ web.fetch
├── 需要 JS 渲染（电商、SPA）→ browser
├── 需要搜索多个来源 → web.search
└── 需要登录/交互 → browser
```

## 核心工作流

### 导航并获取内容

```
browser(action="navigate", url="https://example.com", sessionId="my-task")
```

- `sessionId`：同一任务用相同 sessionId，可复用 cookie 和页面状态
- 导航可能返回 `partial: true`（超时但已加载部分内容），此时内容仍然可用

### 截图确认

```
browser(action="screenshot", sessionId="my-task")
```

**关键原则**：操作前截图确认页面状态，避免盲操作。

### 交互操作

```
browser(action="click", selector="#search-btn", sessionId="my-task")
browser(action="type", selector="#search-input", text="搜索内容", sessionId="my-task")
browser(action="scroll", direction="down", pixels=500, sessionId="my-task")
```

### 提取结构化数据

```
browser(action="evaluate", script="JSON.stringify(Array.from(document.querySelectorAll('.item')).map(el => ({title: el.querySelector('h3').textContent, price: el.querySelector('.price').textContent})))", sessionId="my-task")
```

### 关闭会话

```
browser(action="close", sessionId="my-task")
```

完成后务必关闭，释放浏览器资源。

## 失败处理策略

### 导航失败（ERR_ABORTED、超时、被拦截）

1. 检查返回的 `partial` 字段——如果有部分内容，直接使用
2. 第一次失败后换用 `web.fetch` 尝试静态抓取
3. 静态抓取也不够时，换用 `web.search` 搜索关键信息
4. **同一工具连续失败 2 次后必须切换策略**，不要反复重试

### 页面内容为空或过少

- 可能是 JS 还没渲染完 → 用 `evaluate` 等待特定元素
- 可能是被反爬拦截 → 换 web.search 获取信息
- 可能是需要登录 → 见下方「登录与持久化」章节

### 元素未找到

1. 先 `screenshot` 确认页面当前状态
2. 可能需要滚动页面 → `scroll`
3. 可能在 iframe 中 → 检查页面结构
4. 可能选择器错误 → 用 `accessibility` 获取元素树

## 登录与持久化

系统支持三种浏览器会话模式（通过 `acquisition-mode` 配置，对工具调用透明）：

| 模式 | 登录态保持 | 适用场景 |
|------|-----------|----------|
| **LAUNCH**（默认） | 会话内保持，关闭后丢失；开启 `persist-storage-state` 后可跨会话保留 cookie | 一般抓取和交互 |
| **CDP** | 复用用户已登录的 Chrome，天然拥有全部登录态 | 需要登录或遇到验证码的站点 |
| **PERSISTENT** | Chrome 完整 profile 持久化，首次登录后永久保留 | 长期反复访问需登录的站点 |

**遇到登录墙或验证码时的处理策略**：

1. 如果当前是 CDP 模式 — 用户已在浏览器中登录，直接操作即可
2. 如果当前是 PERSISTENT 模式 — 首次可能需要用户协助登录，之后登录态自动保留
3. 如果当前是 LAUNCH 模式 — 告知用户切换到 CDP 模式可复用已登录浏览器，或开启 `persist-storage-state` 保留 cookie

## 会话管理最佳实践

- **同一任务用同一 sessionId**：`browser(sessionId="jd-search")` — cookie 和登录态在会话内保持
- **不同网站用不同 sessionId**（仅 LAUNCH 模式）：避免 cookie 污染
- **CDP / PERSISTENT 模式下所有会话共享 cookie**：这是设计意图，方便复用登录态
- **任务完成后关闭会话**：`browser(action="close", sessionId="...")`
- **不要创建过多并行会话**：浏览器资源有限

## 元素定位策略

优先级从高到低：
1. `id` 选择器：`#unique-id`
2. `data-testid`：`[data-testid="submit"]`
3. 无障碍角色：通过 `accessibility` 获取元素树
4. CSS 选择器：`.class-name > child`

## 抓取数据整理

提取到数据后：
1. 使用 `evaluate` 提取结构化 JSON
2. 多个页面的数据合并整理
3. 如用户要求表格，用 Markdown 表格或 A2UI Table 组件输出
