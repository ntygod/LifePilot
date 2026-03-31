# 外部数据源同步 — 架构设计

> **文档性质**：架构设计文档
> **模块归属**：`com.lifepilot.sync`（规划中，尚未实现）
> **最后更新**：2026-03

> 📋 **规划中**：以下功能尚未实现，仅为设计文档。当前版本中 `com.lifepilot.sync` 包尚未编码，本文档记录设计规划供后续实现参考。

## 1. 模块概述

同步模块为知微提供与外部数据源的双向数据同步能力。通过 `SyncConnector` 接口抽象不同数据源的通信协议，`SyncEngine` 编排拉取/推送/冲突解决的完整同步流程，`CredentialStore` 使用 AES-GCM 加密存储 OAuth Token 和 API Key。支持四种连接器（CalDAV / Todoist / 滴答清单 / Obsidian），三种同步方向（双向 / 仅拉取 / 仅推送），四种冲突策略（Last-Write-Wins / Remote-Wins / Local-Wins / 用户确认）。

## 2. 架构图

```mermaid
flowchart TD
    subgraph "同步引擎"
        SE["SyncEngine<br/>同步编排"]
        CD["ChangeDetector<br/>本地变更检测"]
        CR["ConflictResolver<br/>冲突解决"]
        CS["CredentialStore<br/>AES-GCM 凭证加密"]
        SS["SyncScheduler<br/>Cron 调度"]
        FM["FieldMapping<br/>字段映射"]
    end

    subgraph "连接器"
        CAL["CalDavConnector<br/>CalDAV 协议"]
        TOD["TodoistConnector<br/>Todoist REST API"]
        DID["DidaConnector<br/>滴答清单 Open API"]
        OBS["ObsidianConnector<br/>本地文件系统"]
    end

    subgraph "持久化"
        SPR["SyncProfileRepository"]
        SRR["SyncRecordRepository"]
        SSR["SyncStateRepository"]
        SCR["SyncConflictRepository"]
    end

    subgraph "外部依赖"
        SKILL["SkillActivator<br/>（技能系统）"]
        MEM["内置 Skill 数据<br/>（TodoItem / ScheduleItem）"]
    end

    SS --> SE
    SE --> CD
    SE --> CR
    SE --> CS
    SE --> FM
    SE --> CAL
    SE --> TOD
    SE --> DID
    SE --> OBS
    SE --> SPR
    SE --> SRR
    SE --> SSR
    CR --> SCR


## 3. 核心组件

### 3.1 SyncConnector（接口）

- 职责：封装与单个外部服务的通信协议
- 四个实现：`CalDavConnector`（CalDAV 协议）、`TodoistConnector`（REST API）、`DidaConnector`（滴答清单 Open API）、`ObsidianConnector`（本地文件系统）
- 核心方法：`testConnection()` 连接测试、`fetchChanges(profile, syncToken)` 拉取远程变更、`pushChanges(profile, operations)` 推送本地变更
- 增量同步：syncToken 为 null 时全量同步，非 null 时增量同步

### 3.2 SyncEngine

- 职责：编排完整同步流程，根据同步方向分派到不同执行路径
- 三种执行路径：`executeBidirectional()`（双向）、`executePullOnly()`（仅拉取）、`executePushOnly()`（仅推送）
- 双向同步流程：拉取远程变更 → 检测本地变更 → 冲突解决 → 应用远程变更到本地 → 推送本地变更到远程 → 更新同步状态
- 本地实体操作：通过内置 Skill 的数据模型（TodoItem / ScheduleItem / HabitItem）创建/更新/删除

### 3.3 ConflictResolver

- 职责：检测并解决本地与远程变更之间的冲突
- 冲突条件：同一实体（通过 SyncRecord 映射匹配）同时出现在远程 updated 和本地 updated 中
- 四种策略：LAST_WRITE_WINS（比较时间戳）、REMOTE_WINS、LOCAL_WINS、USER_CONFIRM（标记未解决）
- 所有冲突均保存双方版本快照到 sync_conflicts 表

### 3.4 ChangeDetector

- 职责：检测本地数据自上次同步以来的变更
- 通过比较 SyncRecord 中记录的上次同步哈希与当前实体哈希，识别新增/修改/删除

### 3.5 CredentialStore

- 职责：OAuth Token 和 API Key 的加密存储
- 加密算法：AES-GCM（128-bit tag, 12-byte IV）
- 密钥派生：PBKDF2（SHA-256, 65536 iterations, 256-bit），盐由固定前缀 + profileId 组合
- 每次加密使用随机 IV，防止 IV 重用

### 3.6 FieldMapping

- 职责：外部数据源字段与本地实体字段的映射规则
- 每个连接器有对应的 FieldMapping 实现（CalDavFieldMapping / TodoistFieldMapping / DidaFieldMapping / ObsidianFieldMapping）
- 处理字段名差异、类型转换、默认值填充

### 3.7 SyncScheduler

- 职责：按 SyncProfile 中的 Cron 表达式定时触发同步
- 启动时注册所有已启用 profile 的调度任务
- 事件触发同步有最小间隔限制，防止频繁触发

## 4. 核心流程

```mermaid
sequenceDiagram
    participant SS as SyncScheduler
    participant SE as SyncEngine
    participant CS as CredentialStore
    participant CON as SyncConnector
    participant CD as ChangeDetector
    participant CR as ConflictResolver
    participant REPO as Repository

    SS->>SE: sync(profile)
    SE->>CS: retrieve(profileId, "access_token")
    CS-->>SE: 解密后的 Token

    SE->>CON: fetchChanges(profile, syncToken)
    CON-->>SE: RemoteChangeSet

    SE->>CD: detectLocalChanges(profileId)
    CD-->>SE: LocalChangeSet

    SE->>CR: detectAndResolve(remote, local, policy)
    CR-->>SE: ConflictResolution

    SE->>SE: applyRemoteChanges(本地)
    SE->>CON: pushChanges(profile, operations)
    CON-->>SE: PushResult

    SE->>REPO: updateSyncState(newSyncToken)
    SE-->>SS: SyncResult
```

## 5. 设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 连接器抽象 | 普通接口（非 sealed） | Java 未命名模块限制，sealed interface 无法跨子包 permits |
| 增量同步 | syncToken 机制 | 减少数据传输量，CalDAV/Todoist 原生支持 |
| 冲突解决 | 四种策略可配置 | 不同场景需要不同策略，USER_CONFIRM 保留用户决策权 |
| 凭证加密 | AES-GCM + PBKDF2 | 认证加密（AEAD），防篡改，密钥派生安全 |
| Obsidian 连接器 | 本地文件系统 | Obsidian 无云端 API，通过 Vault 目录直接读写 Markdown |
| 字段映射 | 每连接器独立 FieldMapping | 不同数据源字段差异大，统一映射不现实 |

## 6. 集成点

| 依赖方向 | 模块 | 交互方式 |
|---------|------|---------|
| sync → skill | `com.lifepilot.skill` | 通过内置 Skill 数据模型操作本地实体 |
| sync → memory | `com.lifepilot.memory` | 同步事件写入情景记忆 |
| agent → sync | `com.lifepilot.agent` | SyncSkillProvider 注册为 Skill，Agent 可触发同步 |

## 7. 配置参考

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.sync.enabled` | `true` | 同步模块总开关 |
| `lifepilot.sync.default-cron` | `0 */15 * * * *` | 默认同步频率（每 15 分钟） |
| `lifepilot.sync.default-conflict-policy` | `LAST_WRITE_WINS` | 默认冲突策略 |
| `lifepilot.sync.timeout` | `30` | 连接器请求超时（秒） |
| `lifepilot.sync.max-retries` | `2` | 最大重试次数 |
| `lifepilot.sync.credential-key-source` | `system-key` | 凭证加密密钥来源 |
| `lifepilot.sync.obsidian-vault-path` | （空） | Obsidian Vault 目录路径 |
| `lifepilot.sync.event-sync-min-interval` | `60` | 事件触发同步最小间隔（秒） |
| `lifepilot.sync.connectors.caldav.enabled` | `false` | CalDAV 连接器开关 |
| `lifepilot.sync.connectors.todoist.enabled` | `false` | Todoist 连接器开关 |
| `lifepilot.sync.connectors.dida.enabled` | `false` | 滴答清单连接器开关 |
