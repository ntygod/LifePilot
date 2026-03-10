# 外部数据源同步 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.sync`
> **最后更新**：2026-03

## 1. 功能概述

同步模块让知微能够与用户已有的效率工具保持数据同步。支持 CalDAV 日历服务器、Todoist、滴答清单和 Obsidian 四种数据源，可按 Cron 表达式定时同步或由 Agent 手动触发。同步过程自动检测冲突并按配置策略解决，凭证使用 AES-GCM 加密存储。

## 2. 核心特性

### 2.1 四种数据源连接器

| 连接器 | 协议 | 同步内容 |
|--------|------|---------|
| CalDAV | CalDAV 协议 | 日历事件、待办事项 |
| Todoist | REST API | 任务、项目 |
| 滴答清单 | Open API | 任务、清单 |
| Obsidian | 本地文件系统 | Markdown 笔记（YAML Frontmatter） |

每个连接器独立启用/禁用，通过 SyncProfile 配置连接参数。

### 2.2 三种同步方向

- 双向同步（BIDIRECTIONAL）：本地变更推送到远程，远程变更拉取到本地
- 仅拉取（PULL_ONLY）：只从远程拉取变更，不推送本地变更
- 仅推送（PUSH_ONLY）：只将本地变更推送到远程，不拉取远程变更

### 2.3 增量同步

首次同步执行全量拉取，后续同步通过 syncToken 实现增量同步，仅传输自上次同步以来的变更数据，减少网络开销。

### 2.4 冲突检测与解决

当同一实体在本地和远程同时被修改时，系统自动检测冲突并按配置策略解决：
- Last-Write-Wins：比较时间戳，保留较新版本
- Remote-Wins：始终以远程版本为准
- Local-Wins：始终以本地版本为准
- 用户确认：标记为未解决，等待用户手动决策

所有冲突均保存双方版本快照，便于事后审查。

### 2.5 凭证安全存储

OAuth Token 和 API Key 使用 AES-GCM 加密后存储到 SQLite。密钥通过 PBKDF2 从主密钥材料派生，每个 SyncProfile 使用独立的盐值，每次加密使用随机 IV。

### 2.6 定时调度

每个 SyncProfile 可配置独立的 Cron 表达式，SyncScheduler 按计划自动触发同步。事件触发同步有最小间隔限制（默认 60 秒），防止频繁触发。

### 2.7 Skill 集成

SyncSkillProvider 将同步能力注册为 Skill，Agent 可通过自然语言触发同步操作（如"同步一下 Todoist 的任务"）。

## 3. 使用场景

用户在 Todoist 中管理任务，希望知微能够读取这些任务并提供智能提醒。配置 Todoist 连接器后，系统每 15 分钟自动拉取 Todoist 任务变更，同步到本地待办列表。用户通过知微修改的任务也会推送回 Todoist。

用户使用 Obsidian 记录笔记，希望知微能够检索笔记内容。配置 Obsidian 连接器指向 Vault 目录后，系统定时扫描 Markdown 文件变更，将笔记内容同步到本地记忆系统。

用户同时在手机上用滴答清单和知微管理待办，双向同步确保两端数据一致。当同一任务在两端同时修改时，按 Last-Write-Wins 策略自动解决冲突。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.sync.enabled` | `true` | 同步模块总开关 |
| `lifepilot.sync.default-cron` | `0 */15 * * * *` | 默认同步频率 |
| `lifepilot.sync.default-conflict-policy` | `LAST_WRITE_WINS` | 默认冲突策略 |
| `lifepilot.sync.timeout` | `30` | 连接器请求超时（秒） |
| `lifepilot.sync.max-retries` | `2` | 最大重试次数 |
| `lifepilot.sync.obsidian-vault-path` | （空） | Obsidian Vault 目录 |
| `lifepilot.sync.event-sync-min-interval` | `60` | 事件触发最小间隔（秒） |
| `lifepilot.sync.connectors.caldav.enabled` | `false` | CalDAV 连接器开关 |
| `lifepilot.sync.connectors.todoist.enabled` | `false` | Todoist 连接器开关 |
| `lifepilot.sync.connectors.dida.enabled` | `false` | 滴答清单连接器开关 |

## 5. 限制与未来方向

当前限制：
- 仅支持四种数据源，新增连接器需要编码实现
- Obsidian 连接器依赖本地文件系统访问，不支持远程 Vault
- 冲突解决的 USER_CONFIRM 策略需要用户通过 API 手动确认
- 凭证加密密钥来源目前为配置项，生产环境建议使用密钥管理服务

未来方向：
- 支持更多数据源（Google Calendar、Notion、飞书文档）
- 连接器插件化，支持用户自定义连接器
- Web UI 同步管理页面（配置、状态监控、冲突处理）
- 实时同步（WebSocket / Webhook 推送）
