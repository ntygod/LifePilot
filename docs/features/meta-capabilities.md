# 元能力系统 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.meta`
> **最后更新**：2026-04

## 1. 功能概述

元能力系统为 Agent 提供开箱即用的通用执行能力和系统自省能力。通过 25 个基础工具，Agent 可以感知环境、搜索 Web 信息、执行 Shell 命令、操控浏览器、运行代码、读写文件、与用户交互；通过 4 个自省工具，Agent 可以查询自身已注册的所有能力并按需推荐。此外，模块还提供 Skill 发现和 MCP Server 自动安装能力，帮助 Agent 动态扩展工具集。

## 2. 核心特性

### 2.1 基础工具集（22 个工具）

Agent 的通用执行基础设施，按功能域分为 8 类：

| 功能域 | 工具 ID | 说明 |
|--------|---------|------|
| 环境感知 | `env.datetime` | 获取当前日期时间 |
| 环境感知 | `env.user-profile` | 获取用户画像信息 |
| 环境感知 | `env.system-info` | 获取系统环境信息 |
| Web 信息 | `web.search` | Web 搜索（统一使用 Tavily） |
| Web 信息 | `web.fetch` | 抓取网页内容 |
| 推理辅助 | `reason.think` | 结构化思考（scratchpad） |
| 推理辅助 | `reason.calculate` | 数学计算 |
| Shell 执行 | `shell.exec` | 执行 Shell 命令（含命令黑名单安全检查），支持后台执行、PTY、环境变量注入（`env`，有安全黑名单过滤）和 Unix Shell 解释器指定（`shell`） |
| Shell 执行 | `shell.process` | 后台进程管理和持久终端会话（tmux），支持 list/output/write/kill 以及 session-* 操作 |
| 浏览器自动化 | `browser` | 统一浏览器操作（通过 `action` 参数选择：navigate/click/input/scroll/wait/hover/select/keyboard/screenshot/evaluate/accessibility/tab/close），支持通过 `acquisitionMode`/`cdpUrl`/`userDataDir` 动态指定浏览器获取模式 |
| 代码执行 | `code.execute` | 在沙箱中执行代码（支持持久内核） |
| 代码执行 | `code.kernel.list` | 列出所有活跃的持久代码内核 |
| 代码执行 | `code.kernel.reset` | 重置内核状态（清空变量） |
| 代码执行 | `code.kernel.inspect` | 检查内核变量和状态 |
| 文件系统 | `file.read` | 读取文件内容（支持行范围和字符截断） |
| 文件系统 | `file.write` | 写入文件（write 覆写 / append 追加） |
| 文件系统 | `file.list` | 文件查询（list 列目录 / search 搜索内容 / info 元数据） |
| 文件系统 | `file.edit` | 编辑文件（行级 insert/replace/delete + 文本匹配 search_replace） |
| 文件系统 | `file.manage` | 文件管理（move / copy / delete / mkdir） |
| 交互控制 | `interact.choose` | 请求用户选择（多选项） |
| 交互控制 | `interact.input` | 请求用户输入文本 |
| 交互控制 | `interact.notify` | 向用户推送结果通知（非阻塞，默认回到当前会话渠道） |

### 2.2 系统自省（4 个工具）

让 Agent 了解自身能力，实现自适应行为：

- **list-capabilities**：列出所有已注册的 Skill、Agent、工具、工作流和 MCP Server，支持按类型过滤
- **explain**：按 ID 查看任意能力的详细信息（描述、参数、风险级别等）
- **status**：系统状态概览，包括各注册中心计数、工具层次分布和 JVM 内存使用
- **suggest**：根据需求描述推荐匹配能力，结合关键词匹配和语义搜索

### 2.3 Skill 发现

启动时自动将内置 `find-skills` Skill 提取到用户 Skill 目录（`~/.zhiwei/skills/builtin.find-skills/`）。该 Skill 帮助 Agent 在开源生态中搜索和发现新的 Skill，扩展自身能力。已存在的文件不会被覆盖，保留用户自定义内容。

### 2.4 MCP Server 自动安装

启动时检查 npx 可用性，可用时自动注册 `mcp-installer` MCP Server（通过 STDIO 传输）。用户可通过 Agent 对话安装新的 MCP Server，无需手动配置。Node.js 不可用时优雅降级，不影响其他功能。

### 2.5 交互桥接

Agent 执行过程中需要用户输入时（确认、选择、文本输入），通过 InteractionBridge 发起阻塞式交互请求。请求通过 SSE 推送到 Web 前端或 CLI，用户响应后 Agent 继续执行。支持超时控制（默认 120 秒）。

## 3. 使用场景

Agent 在执行任务时，自动使用基础工具完成各类操作：搜索 Web 获取最新信息、执行 Shell 命令管理系统、通过浏览器自动化完成网页操作、在沙箱中运行代码验证方案、读写文件管理用户数据。当 Agent 不确定自身能力范围时，调用自省工具查询已注册的 Skill 和工具，或通过 suggest 工具根据用户需求推荐最匹配的能力。遇到需要用户决策的场景（如高风险操作确认、多方案选择），Agent 通过交互工具暂停执行并等待用户响应。

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
| `lifepilot.meta.infra.browser.enabled` | `true` | 浏览器功能开关 |
| `lifepilot.meta.infra.browser.headless` | `true` | 无头模式 |
| `lifepilot.meta.infra.browser.storage-state-dir` | `""` | storageState 持久化目录，空字符串关闭持久化 |
| `lifepilot.meta.infra.browser.persist-storage-state` | `false` | 是否在会话关闭时自动保存 storageState |
| `lifepilot.meta.infra.browser.acquisition-mode` | `LAUNCH` | 浏览器获取模式（LAUNCH / CDP / PERSISTENT） |
| `lifepilot.meta.infra.browser.cdp-url` | `""` | CDP 模式的远程调试端口 URL |
| `lifepilot.meta.infra.browser.user-data-dir` | `""` | PERSISTENT 模式的用户数据目录 |
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
