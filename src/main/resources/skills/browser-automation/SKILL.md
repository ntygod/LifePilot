---
name: browser-automation
description: 当用户要控制浏览器完成需要 JavaScript 渲染、登录态或交互操作的网页任务时使用。
version: 3.2.2
metadata:
  zhiwei:
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
    outputs:
      - text
      - file
---
# 浏览器自动化指南

用于判断网页任务是否需要真实浏览器。核心原则：静态内容优先 `web.fetch`，多源搜索优先 `web.search`，只有 JS 渲染、登录态、表单、截图或多步交互才升级到 `browser`。

## 触发判断
- 动态页面抓取（SPA、电商搜索结果、需要 JS 渲染）
- 登录后才能访问的页面
- 表单填写与提交
- 网页截图 / 视觉验证
- 多步交互流程（点 → 等加载 → 再点）

不要触发：

- 静态页面抓取（文档 / 博客 / 新闻）→ `web.fetch`
- API 接口测试 → api-debugger
- 桌面应用操作 → desktop-automation
- 多源搜索 → `web.search`

## 决策路径

| 判断点 | 决策 |
|---|---|
| 只要正文 / 文档 / 新闻 | 先用 `web.fetch`，正文为空再考虑浏览器 |
| 需要比较多个来源 | 先用 `web.search`，只抓高价值来源正文 |
| 需要登录态、按钮、表单、截图 | 使用 `browser`，同一任务复用 session |
| 页面变化后继续操作 | 重新 snapshot，旧 index 不再可信 |
| 登录墙 / 验证码 / 密码 | 请求用户接管，不伪造凭证 |

执行策略：

- **定位优先级**：能用 snapshot 的 index 就不用 selector；index 失败再切 selector。
- **失败降级**：同一步失败 2 次必须换策略；浏览器拿不到内容时退回 `web.fetch` 或让用户接管。
- **状态复用**：同一网站的连续任务复用 session，跨网站或敏感登录后及时关闭。
- **产出收束**：截图、抓取结果或表单回执需要长期保留时才写文件；普通确认直接在对话中说明。


## 输出标准

- 输出已完成的页面动作、关键选择器、截图或导出文件路径。
- 需要截图时返回文件路径和截图验证结论。
- 页面状态变化要写明触发动作、结果和仍需用户确认的部分。


## 失败策略

- 未登录、验证码、权限弹窗或不稳定选择器出现时暂停并说明需要用户介入。
- 静态抓取可完成时降级到 web.fetch/web.search，不强行打开浏览器。
- 操作会提交表单、付款、删除或公开发布时必须等待确认。

## 详细参考
- 完整 action 清单（snapshot / requestHumanTakeover）、index/selector fallback、登录墙触发条件、会话模式（LAUNCH/CDP/PERSISTENT）、常见错误：`{skill_dir}/references/browser-actions.md`
