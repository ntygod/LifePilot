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

1. **选工具**：静态页 → `web.fetch`；多源搜索 → `web.search`；需 JS / 登录 / 交互 → `browser`
2. **开会话**：`navigate` 建立会话，同任务用相同 `sessionId` 复用 cookie
3. **操作前截图**：`screenshot` 确认页面状态，避免盲操作
4. **交互**：`click` / `input` / `scroll` / `wait` / `evaluate`，详细 action 清单见参考
5. **同一工具连续失败 2 次**必须切换策略（browser → web.fetch → web.search）
6. **结束**：`file.write` 保存产出 → `close` 释放资源

## 详细参考

- 完整 action 清单、会话模式（LAUNCH/CDP/PERSISTENT）、元素定位策略、常见错误：`{skill_dir}/references/browser-actions.md`
</content>
</invoke>