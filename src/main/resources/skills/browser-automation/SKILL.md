---
name: browser-automation
description: 当用户要控制浏览器完成需要 JavaScript 渲染、登录态或交互操作的网页任务时使用。
version: 3.2.0
metadata:
  zhiwei:
    priority: normal
    tags:
      - browser
      - web-scraping
      - automation
      - selenium
      - playwright
    suggested_tools:
      - browser
      - web_fetch
      - web_search
      - file_write
---

# 浏览器自动化指南

`browser` 工具基于 Playwright，做需要 JS 渲染、登录态或交互的网页任务。**核心约束：能 web_fetch 搞定的不用 browser；不能交互的用 web_fetch。**

## 适用场景

- 动态页面抓取（SPA、电商搜索结果、需要 JS 渲染）
- 登录后才能访问的页面
- 表单填写与提交
- 网页截图 / 视觉验证
- 多步交互流程（点 → 等加载 → 再点）

## 不适用场景

- 静态页面抓取（文档 / 博客 / 新闻）→ `web_fetch`
- API 接口测试 → api-debugger
- 桌面应用操作 → desktop-automation
- 多源搜索 → `web_search`

## 工作流

1. **先判**：能 `web_fetch` 拿到正文 → 用 fetch；JS 渲染 / 登录 / 多步交互 → 用 `browser`
2. **navigate 开会话**：同一任务用相同 `sessionId` 复用 cookie，避免每次重登
3. **快照定位**：`snapshot` 一次拿截图 + 可交互元素编号；后续 `click` / `input` / `hover` 优先用 `index` 而非 `selector`（layout 抖动也稳）
4. **页面变化后重新 snapshot**：导航 / 弹窗 / 异步渲染会让旧 index 失效，必须重新拿
5. **失败 2 次切策略**：`index` 失败 → 改 `selector`；selector 失败 → 退 `web_fetch`；fetch 失败 → 让用户接管
6. **登录墙 / 验证码**：观察到 password input、login URL 或人机验证截图时，调 `requestHumanTakeover` 让用户接管，**不要让 LLM 假装填密码**
7. **结束**：关键产出 `file_write` 落盘后 `close` 释放浏览器资源

## 详细参考

- 完整 action 清单（snapshot / requestHumanTakeover）、index/selector fallback、登录墙触发条件、会话模式（LAUNCH/CDP/PERSISTENT）、常见错误：`{skill_dir}/references/browser-actions.md`
