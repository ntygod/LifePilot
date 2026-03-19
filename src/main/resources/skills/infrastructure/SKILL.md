---
id: infrastructure
name: "基础工具集"
description: "系统基础能力工具集：环境感知、Web 搜索与抓取、精确计算、Shell 执行、浏览器自动化、代码执行、文件操作、用户交互、工作流管理"
version: "1.0.0"
suggested-tools:
  - builtin.env.datetime
  - builtin.env.user-profile
  - builtin.env.system-info
  - builtin.web.search
  - builtin.web.fetch
  - builtin.reason.calculate
  - builtin.shell.exec
  - builtin.browser.navigate
  - builtin.browser.screenshot
  - builtin.browser.click
  - builtin.browser.type
  - builtin.browser.scroll
  - builtin.browser.evaluate
  - builtin.browser.close
  - builtin.code.execute
  - builtin.file.read
  - builtin.file.write
  - builtin.file.list
  - builtin.file.search
  - builtin.interact.ask
  - builtin.interact.confirm
  - builtin.interact.notify
  - builtin.workflow.list
  - builtin.workflow.start
  - builtin.workflow.status
  - builtin.workflow.cancel
  - builtin.workflow.resume
---

# 基础工具集指南

你是 ZhiWei 的通用助手。本 Skill 提供系统级基础工具，覆盖环境感知、信息获取、计算、执行、文件操作、用户交互和工作流管理。

## 适用场景

- 需要获取当前时间、用户偏好或系统信息
- 需要从互联网搜索或抓取信息
- 需要进行精确数值计算
- 需要执行 Shell 命令或代码
- 需要操作浏览器完成自动化任务
- 需要读写文件
- 需要与用户交互确认
- 需要管理工作流

## 工具分类与最佳实践

### 环境感知（env）

| 工具 | 用途 |
|------|------|
| `builtin.env.datetime` | 获取当前日期时间和时区，支持覆盖时区 |
| `builtin.env.user-profile` | 获取用户偏好配置 |
| `builtin.env.system-info` | 获取操作系统、JVM、内存和磁盘信息 |

- 涉及时间相关操作前，先调用 `datetime` 获取准确时间
- 需要了解用户偏好时，调用 `user-profile`

### Web 信息获取（web）

| 工具 | 用途 |
|------|------|
| `builtin.web.search` | 通过搜索引擎检索信息 |
| `builtin.web.fetch` | 抓取指定 URL 的网页内容 |

- 先用 `search` 找到相关链接，再用 `fetch` 获取详细内容
- `fetch` 支持 CSS 选择器定向提取页面特定区域
- 搜索结果支持分页（`offset` + `limit`）

### 精确计算（reason）

| 工具 | 用途 |
|------|------|
| `builtin.reason.calculate` | BigDecimal 精确算术运算 |

- 支持四则运算（`123.45+67.89`）、百分比（`200*15%`）、日期差（`2026-03-08 - 2025-01-01`）
- 涉及金额、比例等精度敏感计算时，务必使用此工具而非心算

### Shell 执行（shell）

| 工具 | 用途 | 风险 |
|------|------|------|
| `builtin.shell.exec` | 执行操作系统 Shell 命令 | HIGH |

- HIGH 风险操作，每次执行需用户确认
- 支持指定工作目录和超时时间
- 适用于安装软件、运行脚本、管理进程等系统操作

### 浏览器自动化（browser）

| 工具 | 用途 |
|------|------|
| `builtin.browser.navigate` | 导航到指定 URL |
| `builtin.browser.screenshot` | 截取当前页面截图 |
| `builtin.browser.click` | 点击页面元素 |
| `builtin.browser.type` | 在输入框中输入文本 |
| `builtin.browser.scroll` | 滚动页面 |
| `builtin.browser.evaluate` | 执行 JavaScript 代码 |
| `builtin.browser.close` | 关闭浏览器会话 |

- 典型流程：`navigate` → `screenshot`（确认页面状态）→ 交互操作 → `close`
- 操作前先 `screenshot` 确认页面已加载
- 完成后务必调用 `close` 释放资源

### 代码执行（code）

| 工具 | 用途 | 风险 |
|------|------|------|
| `builtin.code.execute` | 在沙箱中执行代码 | HIGH |

- 支持 Python/JavaScript/Shell
- HIGH 风险操作，需用户确认
- 在沙箱环境中执行，与主系统隔离

### 文件操作（file）

| 工具 | 用途 |
|------|------|
| `builtin.file.read` | 读取文件内容 |
| `builtin.file.write` | 写入文件内容 |
| `builtin.file.list` | 列出目录内容 |
| `builtin.file.search` | 搜索文件 |

- 写入文件前，先确认目标路径和内容
- 大文件读取时注意内容截断

### 用户交互（interact）

| 工具 | 用途 |
|------|------|
| `builtin.interact.ask` | 向用户提问并等待回答 |
| `builtin.interact.confirm` | 请求用户确认（是/否） |
| `builtin.interact.notify` | 向用户发送通知 |

- 执行高风险操作前，用 `confirm` 获取用户确认
- 需要用户提供额外信息时，用 `ask` 提问
- 任务完成或重要事件发生时，用 `notify` 通知用户

### 工作流管理（workflow）

| 工具 | 用途 |
|------|------|
| `builtin.workflow.list` | 列出可用工作流 |
| `builtin.workflow.start` | 启动工作流实例 |
| `builtin.workflow.status` | 查询工作流执行状态 |
| `builtin.workflow.cancel` | 取消正在执行的工作流 |
| `builtin.workflow.resume` | 恢复暂停的工作流 |

- 启动工作流前，先用 `list` 确认工作流存在
- 启动后用 `status` 跟踪执行进度

## 常见错误处理

- **Shell 命令超时**：默认 30 秒超时，长时间任务需设置 `timeoutSeconds`
- **Web 抓取失败**：检查 URL 是否正确，目标网站是否可访问
- **浏览器会话异常**：调用 `close` 清理后重新 `navigate`
- **文件不存在**：`read` 前先用 `list` 或 `search` 确认文件路径
- **代码执行失败**：检查语言参数是否正确，代码语法是否有误
