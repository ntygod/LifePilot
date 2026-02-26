# 外部数据源同步 — 特性说明

## 1. 功能概述

外部数据源同步模块让 LifePilot 能够与用户已有的生产力工具保持数据同步，避免数据孤岛。用户可以继续使用 Todoist、滴答清单等熟悉的工具管理任务，同时让 LifePilot 的 Agent 获得完整的数据视图，提供更智能的建议和自动化。

**核心价值：**
- 无需手动在多个工具间复制数据
- Agent 获得用户完整的任务/日程/习惯数据，推理更准确
- 支持渐进式迁移，用户可以逐步将工作流转移到 LifePilot

## 2. 核心特性

### 2.1 多数据源连接器

支持以下外部数据源的双向同步：

| 数据源 | 协议 | 同步内容 | 认证方式 |
|-------|------|---------|---------|
| CalDAV 服务器 | CalDAV（RFC 4791） | 日程（VEVENT）、待办（VTODO） | Basic Auth / OAuth2 |
| Todoist | REST API v1 + Sync endpoint | 待办任务、项目 | OAuth2 |
| 滴答清单 | Open API | 待办任务、习惯 | OAuth2 |
| Obsidian Vault | 本地文件系统 | 待办、日程、习惯（Markdown + YAML frontmatter） | 无（本地目录） |

### 2.2 增量同步

- 基于 sync token / ETag / 文件修改时间实现增量同步
- 仅传输自上次同步以来的变更，节省带宽和 API 配额
- 支持首次全量同步 + 后续增量同步的混合模式

### 2.3 冲突检测与解决

当同一条数据在 LifePilot 和外部服务上都被修改时，系统自动检测冲突并按策略解决：

| 策略 | 行为 | 适用场景 |
|------|------|---------|
| LAST_WRITE_WINS | 保留最后修改的版本（默认） | 日常使用，冲突概率低 |
| REMOTE_WINS | 始终以外部服务为准 | 外部服务是主要编辑入口 |
| LOCAL_WINS | 始终以 LifePilot 为准 | LifePilot 是主要编辑入口 |
| USER_CONFIRM | 标记冲突，等待用户手动选择 | 重要数据，不允许自动覆盖 |

冲突发生时，系统保留双方版本的快照，用户可随时回溯。

### 2.4 同步配置管理

- 支持创建多个同步配置（SyncProfile），每个配置对应一个外部数据源
- 每个配置可独立设置同步方向（双向 / 仅拉取 / 仅推送）、冲突策略、同步频率
- 支持按数据类型过滤（如只同步待办，不同步日程）
- 配置变更即时生效，无需重启

### 2.5 定时与手动同步

- 定时同步：默认每 15 分钟自动同步一次，频率可配置（Cron 表达式）
- 手动同步：用户可通过 CLI 命令或 Agent 对话触发即时同步
- 事件触发：本地数据变更后自动触发对应数据源的同步
- 同步状态可查询：上次同步时间、同步结果、错误信息

### 2.6 OAuth Token 安全管理

- OAuth Token 使用 AES-GCM 加密存储
- 自动刷新过期的 Access Token（使用 Refresh Token）
- Token 刷新失败时通知用户重新授权
- 支持手动撤销授权和清除凭证

### 2.7 Agent 集成

同步能力通过 Skill 系统暴露给 Agent：

- `sync-trigger`：触发指定数据源的即时同步
- `sync-status`：查询同步状态和历史
- `sync-config`：管理同步配置（增删改查）
- `sync-conflicts`：查看和解决未处理的冲突

Agent 可以主动感知同步事件，例如：
- "你的 Todoist 有 3 个新任务已同步到 LifePilot"
- "CalDAV 同步失败，可能是网络问题，要重试吗？"

## 3. 使用场景

### 场景 1：Todoist 用户迁移
用户一直使用 Todoist 管理任务，开始使用 LifePilot 后，配置 Todoist 同步。LifePilot Agent 可以看到所有 Todoist 任务，提供智能排期建议，同时用户仍可在 Todoist 上操作。

### 场景 2：CalDAV 日历同步
用户使用 Nextcloud 管理日历，配置 CalDAV 同步后，LifePilot 自动获取日程数据。Agent 在安排新任务时会考虑已有日程，避免时间冲突。

### 场景 3：Obsidian 知识库联动
用户在 Obsidian Vault 中用 Markdown 管理待办和笔记，配置 Obsidian 同步后，LifePilot 自动解析 YAML frontmatter 中的任务数据，实现知识库与任务管理的联动。

### 场景 4：多源聚合
用户同时配置 Todoist（工作任务）+ CalDAV（个人日历）+ 滴答清单（习惯追踪），LifePilot Agent 获得完整的生活数据视图，提供跨领域的智能建议。

## 4. 配置项

```yaml
lifepilot:
  sync:
    enabled: true
    # 默认同步间隔（Cron 表达式）
    default-cron: "0 */15 * * * *"
    # 默认冲突解决策略
    default-conflict-policy: LAST_WRITE_WINS
    # 同步超时时间（秒）
    timeout: 30
    # 最大重试次数
    max-retries: 2
    # 凭证加密密钥来源（master-password / system-key）
    credential-key-source: system-key
    # Obsidian Vault 路径（可选）
    obsidian-vault-path: ""
    # 各连接器配置
    connectors:
      caldav:
        enabled: false
        server-url: ""
        username: ""
      todoist:
        enabled: false
        # OAuth2 Client ID / Secret 通过环境变量注入
      dida:
        enabled: false
        # OAuth2 Client ID / Secret 通过环境变量注入
```

## 5. 限制与未来扩展

### 当前限制
- CalDAV 仅支持 VEVENT 和 VTODO，不支持 VJOURNAL
- Todoist 同步不包含评论和活动日志
- 滴答清单习惯同步为单向（仅拉取），因 API 限制无法推送习惯完成记录
- Obsidian 同步依赖固定的 YAML frontmatter 格式，自定义格式需手动配置映射
- 不支持附件/文件同步（仅同步结构化数据）

### 未来扩展方向
- Google Calendar / Outlook Calendar 连接器
- Notion 数据库连接器
- Webhook 接收模式（替代轮询，降低延迟）
- 同步规则引擎（条件过滤、数据转换）
- 同步冲突的 Web UI 可视化解决界面
