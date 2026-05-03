# 元能力系统 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.meta`
> **最后更新**：2026-05-03

## 1. 功能概述

元能力系统为 Agent 提供开箱即用的通用执行能力和系统自省能力。通过 15 个内置工具，Agent 可以搜索 Web 信息、执行 Shell 命令、操控浏览器、运行代码、读写文件、管理定时任务、推送通知；通过 `status` 工具查询系统运行状态。此外，模块还提供 `tool.search` 内省工具让 Agent 发现 MCP 工具。

## 2. 核心特性

### 2.1 基础工具集（15 个工具）

Agent 的通用执行基础设施，按功能域分为 7 类：

| 功能域 | 工具 ID | 说明 |
|--------|---------|------|
| Web 信息 | `web.search` | Web 搜索（统一使用 Tavily） |
| Web 信息 | `web.fetch` | 抓取网页内容或调用 REST API（支持 GET/POST/PUT/DELETE/PATCH、自定义 headers/body，默认开启 SSRF 防护拦截内网地址） |
| Shell 执行 | `shell.exec` | 执行 Shell 命令（含命令黑名单安全检查），支持后台执行、PTY、环境变量注入（`env`，有安全黑名单过滤）和 Unix Shell 解释器指定（`shell`） |
| Shell 执行 | `shell.process` | 后台进程管理和持久终端会话（tmux），支持 list/output/write/kill 以及 session-* 操作 |
| 浏览器自动化 | `browser` | 统一浏览器操作（通过 `action` 参数选择：navigate/click/input/scroll/wait/hover/select/keyboard/screenshot/evaluate/accessibility/tab/storage/snapshot/requestHumanTakeover/close），支持通过 `acquisitionMode`/`cdpUrl`/`userDataDir` 动态指定浏览器获取模式；`snapshot` 返回截图 + 可交互元素编号表，`click/input/hover` 可按 `index` 定位；遇到验证码/登录墙调 `requestHumanTakeover` 挂起等用户接管 |
| 代码执行 | `code` | 在沙箱中执行代码（`action=run`/`reset`/`inspect`，支持持久内核；2026-05 合并原 `code.execute`、`code.kernel.*` 四个工具） |
| 文件系统 | `file.read` | 读取文件内容（支持行范围和字符截断） |
| 文件系统 | `file.write` | 写入文件（write 覆写 / append 追加） |
| 文件系统 | `file.manage` | 文件管理（move / copy / delete / mkdir / list / edit；2026-05 合并原 `file.list` / `file.edit`） |
| 任务管理 | `cron` | 创建和管理定时任务 |
| 通知 | `notify` | 非阻塞通知推送（系统自动路由渠道） |
| 系统状态 | `status` | 系统运行概览（注册中心计数、工作流实例、MCP 连接状态、JVM 内存） |
| UI 渲染 | `ui.render` | 前端 A2UI 组件渲染 |
| 技能激活 | `skill.load` | 按需激活 Skill（1-3 个/次） |

### 2.2 系统自省 — `status` + `tool.search`

- **`status`**：系统运行状态概览，包括 Skill/Tool/Agent/Workflow 计数、运行时工作流实例、MCP 连接状态和 JVM 内存使用
- **`tool.search`**：FTS5 BM25 + 语义 RRF 融合搜索工具注册表，直接返回 inputSchema，LLM 无需二次 describe

2026-05 精简：`system.list-capabilities`、`system.explain`、`system.suggest` 已删除，由 `status` 和 `tool.search` 覆盖。

### 2.3 Skill 发现

启动时自动将内置 `find-skills` Skill 提取到用户 Skill 目录（`~/.zhiwei/skills/builtin.find-skills/`）。该 Skill 帮助 Agent 在开源生态中搜索和发现新的 Skill，扩展自身能力。已存在的文件不会被覆盖，保留用户自定义内容。

### 2.4 MCP Server 自动安装

启动时检查 npx 可用性，可用时自动注册 `mcp-installer` MCP Server（通过 STDIO 传输）。用户可通过 Agent 对话安装新的 MCP Server，无需手动配置。Node.js 不可用时优雅降级，不影响其他功能。

### 2.5 交互桥接

Agent 执行过程中需要用户输入时（确认、选择、文本输入），通过 InteractionBridge 发起阻塞式交互请求。请求通过 SSE 推送到 Web 前端或 CLI，用户响应后 Agent 继续执行。支持超时控制（默认 120 秒）。

## 3. 使用场景

Agent 在执行任务时，自动使用内置工具完成各类操作：搜索 Web 获取最新信息、执行 Shell 命令管理系统、通过浏览器自动化完成网页操作、在沙箱中运行代码验证方案、读写文件管理用户数据。当 Agent 不确定自身能力范围时，调用 `tool.search` 发现 MCP 工具或通过 `status` 查询系统状态。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.meta.infra.web-search.provider` | `tavily` | 搜索引擎提供商（当前固定为 Tavily） |
| `lifepilot.meta.infra.web-search.max-results` | `5` | 搜索最大返回数 |
| `lifepilot.meta.infra.web-search.search-depth` | `basic` | Tavily 搜索深度 |
| `lifepilot.meta.infra.web-search.topic` | `general` | Tavily 搜索主题 |
| `lifepilot.meta.infra.web-search.include-answer` | `true` | 是否附带 Tavily answer 摘要 |
| `lifepilot.meta.infra.web-fetch.timeout-seconds` | `10` | Web 抓取超时 |
| `lifepilot.meta.infra.shell.timeout-seconds` | `120` | Shell 命令超时 |
| `lifepilot.meta.infra.shell.max-output-length` | `50000` | Shell 输出最大字符数 |
| `lifepilot.meta.infra.web-fetch.ssrf.enabled` | `true` | SSRF 防护开关（内网 IP / 云 metadata 拦截） |
| `lifepilot.meta.infra.web-fetch.ssrf.allowlist` | `[]` | SSRF 白名单（host/IP 字面量） |
| `lifepilot.meta.infra.browser.enabled` | `true` | 浏览器功能开关 |
| `lifepilot.meta.infra.browser.headless` | `${BROWSER_HEADLESS:false}` | 无头模式，本地/桌面默认 false，容器部署用环境变量 `BROWSER_HEADLESS=true` 覆写 |
| `lifepilot.meta.infra.browser.storage-state-dir` | `${zhiwei.data-dir}/cache/browser/storage-state` | storageState 持久化目录 |
| `lifepilot.meta.infra.browser.persist-storage-state` | `false` | 是否在会话关闭时自动保存 storageState |
| `lifepilot.meta.infra.browser.acquisition-mode` | `LAUNCH` | 浏览器获取模式（LAUNCH / CDP / PERSISTENT） |
| `lifepilot.meta.infra.browser.cdp-url` | `""` | CDP 模式的远程调试端口 URL |
| `lifepilot.meta.infra.browser.user-data-dir` | `${zhiwei.data-dir}/cache/browser/profile` | PERSISTENT 模式的用户数据目录 |
| `lifepilot.meta.infra.browser.snapshot.max-elements` | `200` | `browser.snapshot` 最多返回元素数 |
| `lifepilot.meta.infra.browser.snapshot.viewport-only` | `true` | snapshot 是否只截 viewport |
| `lifepilot.meta.infra.browser.snapshot.inject-labels` | `false` | 是否叠加视觉编号标签 |
| `lifepilot.meta.infra.browser.takeover.timeout-seconds` | `300` | `browser.requestHumanTakeover` 挂起等待超时（秒） |
| `lifepilot.meta.infra.code-execute.enabled` | `true` | 代码执行开关 |
| `lifepilot.meta.infra.file.max-read-size` | `1048576` | 文件最大读取字节数 |
| `lifepilot.meta.infra.interaction.response-timeout-seconds` | `120` | 用户交互超时 |
| `lifepilot.meta.introspection.cache-ttl-seconds` | `60` | 能力聚合缓存 TTL |
| `lifepilot.meta.skill-discovery.enabled` | `true` | find-skills 提取开关 |
| `lifepilot.meta.mcp-installer.enabled` | `true` | mcp-installer 注册开关 |

## 5. 限制与未来方向

- 浏览器自动化依赖 Playwright，需要额外安装浏览器二进制文件
- Web 搜索当前统一通过 Tavily 执行，前端“知识与检索”页面可直接修改 API Key 与默认参数
- 交互桥接仅支持 SSE 和 CLI 两种 Channel，未来可扩展 WebSocket
- 自省工具的语义搜索依赖 SkillRegistry 的向量索引，未索引的能力仅支持关键词匹配
- 未来可增加更多基础工具（如邮件发送、日历操作等）
