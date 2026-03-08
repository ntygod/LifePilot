# 外部数据源同步 — 架构设计

## 1. 模块定位与职责边界

外部数据源同步模块（`com.lifepilot.sync`）负责将 ZhiWei 内部的待办、日程、习惯等数据与外部服务（CalDAV 服务器、Todoist、滴答清单、Obsidian Vault）进行双向同步。

**核心职责：**
- 提供统一的同步引擎抽象，屏蔽各外部服务的协议差异
- 实现增量同步（基于 sync token / ETag / 时间戳），最小化网络传输
- 处理双向同步中的冲突检测与解决
- 安全存储 OAuth Token 和 API 凭证
- 通过 Skill 系统暴露同步能力，让 Agent 可以触发和管理同步

**不负责：**
- 不负责外部服务的用户认证流程（OAuth 授权页面由 Web UI 模块承载）
- 不负责数据的展示和编辑（由内置 Skill 和 Web UI 负责）
- 不实现实时推送（采用轮询 + 事件触发的混合模式）

## 2. 核心概念与术语

| 术语 | 定义 |
|------|------|
| SyncConnector | 连接器抽象，封装与单个外部服务的通信协议（CalDAV / REST API / 文件系统） |
| SyncEngine | 同步引擎，协调本地数据与远程数据的双向同步流程 |
| SyncProfile | 同步配置实例，一个用户可以配置多个同步目标（如同时同步 CalDAV 和 Todoist） |
| SyncToken | 增量同步令牌，标识上次同步的位置，用于获取增量变更 |
| ConflictPolicy | 冲突解决策略，定义双向同步中数据冲突时的处理方式 |
| FieldMapping | 字段映射规则，定义 ZhiWei 内部数据模型与外部服务数据模型之间的转换 |
| SyncRecord | 同步记录，记录每个本地实体与远程实体的映射关系和同步状态 |
| CredentialStore | 凭证存储，安全管理 OAuth Token、API Key 等敏感信息 |

## 3. 架构设计

### 3.1 分层架构

```
┌─────────────────────────────────────────────────┐
│                  Skill 层                        │
│  SyncSkillProvider（暴露同步操作为 Agent 工具）    │
├─────────────────────────────────────────────────┤
│                  引擎层                          │
│  SyncEngine（协调同步流程）                       │
│  ├─ ChangeDetector（变更检测）                    │
│  ├─ ConflictResolver（冲突解决）                  │
│  └─ SyncRecordRepository（映射关系持久化）        │
├─────────────────────────────────────────────────┤
│                  连接器层                         │
│  SyncConnector（统一接口）                        │
│  ├─ CalDavConnector（CalDAV 协议）               │
│  ├─ TodoistConnector（Todoist API v1）           │
│  ├─ DidaConnector（滴答清单 Open API）           │
│  └─ ObsidianConnector（本地文件系统）             │
├─────────────────────────────────────────────────┤
│                  基础设施层                       │
│  CredentialStore（凭证加密存储）                   │
│  SyncScheduler（定时同步调度）                     │
│  SyncProfileRepository（配置持久化）              │
└─────────────────────────────────────────────────┘
```

### 3.2 核心接口设计

#### SyncConnector — 连接器抽象

```java
public sealed interface SyncConnector
        permits CalDavConnector, TodoistConnector, DidaConnector, ObsidianConnector {

    /** 连接器类型标识。 */
    String type();

    /** 测试连接是否可用。 */
    ConnectionTestResult testConnection(SyncProfile profile);

    /** 拉取远程变更（增量）。 */
    RemoteChangeSet fetchChanges(SyncProfile profile, @Nullable String syncToken);

    /** 推送本地变更到远程。 */
    PushResult pushChanges(SyncProfile profile, List<SyncOperation> operations);
}
```

#### SyncEngine — 同步引擎

```java
public class SyncEngine {

    /**
     * 执行一次完整的双向同步。
     * 流程：拉取远程变更 → 检测本地变更 → 冲突检测 → 冲突解决 → 应用变更 → 推送变更
     */
    public SyncResult sync(SyncProfile profile) { ... }
}
```

### 3.3 同步流程

```
1. 拉取远程变更
   SyncConnector.fetchChanges(profile, lastSyncToken)
   → RemoteChangeSet { created, updated, deleted, newSyncToken }

2. 检测本地变更
   ChangeDetector.detectLocalChanges(profile, lastSyncTime)
   → LocalChangeSet { created, updated, deleted }

3. 冲突检测
   对比 RemoteChangeSet 和 LocalChangeSet，找出同一实体在两端都有修改的情况
   → List<SyncConflict>

4. 冲突解决
   根据 ConflictPolicy 解决冲突：
   - REMOTE_WINS: 远程覆盖本地
   - LOCAL_WINS: 本地覆盖远程
   - LAST_WRITE_WINS: 比较时间戳，最新的胜出
   - USER_CONFIRM: 标记为冲突，等待用户手动解决

5. 应用变更
   - 远程 → 本地：通过 Repository 写入本地数据库
   - 本地 → 远程：通过 SyncConnector.pushChanges() 推送

6. 更新同步状态
   保存 newSyncToken、lastSyncTime、同步记录
```

### 3.4 数据模型映射

ZhiWei 内部有三种核心数据类型需要同步：

| ZhiWei 类型 | CalDAV | Todoist | 滴答清单 | Obsidian |
|---------------|--------|---------|---------|----------|
| TodoItem | VTODO | Task | Task | Markdown 文件（YAML frontmatter） |
| ScheduleItem | VEVENT | — | — | Markdown 文件（YAML frontmatter） |
| HabitItem | — | — | Habit | Markdown 文件（YAML frontmatter） |

映射通过 `FieldMapping` 接口实现，每个连接器提供自己的映射实现：

```java
public interface FieldMapping<L, R> {
    /** 本地实体 → 远程实体。 */
    R toRemote(L local);
    /** 远程实体 → 本地实体。 */
    L toLocal(R remote);
    /** 提取用于冲突检测的关键字段。 */
    Map<String, Object> extractConflictFields(L local, R remote);
}
```

## 4. 关键设计决策

### 4.1 增量同步而非全量同步

**决策：** 采用增量同步，基于 sync token / ETag / 时间戳追踪变更。

**理由：**
- Todoist API v1 原生支持 sync token 增量同步（参考 [Todoist Sync API](https://developer.todoist.com/api/v1)）
- CalDAV 协议支持 `sync-collection` REPORT 和 ETag 变更检测（RFC 6578）
- 全量同步在数据量大时性能差，且无法正确处理删除操作
- Joplin 的同步架构也采用类似的增量模式，通过 `sync_time` 追踪变更状态

### 4.2 冲突解决策略：Last-Write-Wins 为默认，支持用户确认

**决策：** 默认使用 Last-Write-Wins（LWW），同时支持 User-Confirm 模式。

**理由：**
- 对于个人助手场景，用户通常在单设备操作，冲突概率低
- LWW 简单可靠，Joplin 和 DAVx⁵ 等成熟项目也采用类似策略
- 不采用 CRDT：CRDT 适合多用户实时协作场景，对于个人数据同步过于复杂，且 ZhiWei 的数据模型（TodoItem / ScheduleItem）是整体替换而非字段级合并
- 保留 User-Confirm 选项，让用户在重要数据上可以手动决策
- 参考 Stacksync 的字段级冲突检测思路，在 LWW 基础上记录冲突详情供用户回溯

### 4.3 连接器采用 sealed interface 而非插件化

**决策：** 使用 `sealed interface` 定义连接器类型，编译时确定支持的连接器集合。

**理由：**
- ZhiWei 当前阶段支持的外部服务是确定的（CalDAV / Todoist / 滴答清单 / Obsidian）
- sealed interface 配合 switch 穷举匹配，编译器保证所有连接器类型都被处理
- 未来如需扩展，可以将 sealed interface 改为 open interface + SPI 机制
- 与项目中 SkillAction、AgentAction 等已有模式保持一致

### 4.4 OAuth Token 加密存储在 SQLite

**决策：** OAuth Token 使用 AES-GCM 加密后存储在 SQLite 的 `sync_credentials` 表中。

**理由：**
- 保持单 JAR 部署的简洁性，不引入外部密钥管理服务
- AES-GCM 提供认证加密，防止篡改
- 加密密钥从用户设置的主密码派生（PBKDF2），或使用系统级密钥
- 参考 Spring Security 的 `TextEncryptor` 实现，与 Spring 生态一致
- Token 刷新逻辑封装在各连接器内部，对上层透明

### 4.5 Obsidian 同步采用本地文件系统监听

**决策：** Obsidian 连接器通过直接读写本地 Vault 目录实现同步，不依赖 Obsidian 应用运行。

**理由：**
- Obsidian 是 local-first 应用，没有官方 REST API（参考 [Obsidian 论坛](https://forum.obsidian.md/t/is-there-are-rest-api-available/78627)）
- 虽然有 Local REST API 插件，但依赖 Obsidian 应用运行，不够可靠
- 直接操作 Markdown 文件 + YAML frontmatter 是最稳定的集成方式
- 使用 Java NIO WatchService 监听文件变更，实现近实时同步
- 数据格式采用 Obsidian 社区通用的 YAML frontmatter 约定

### 4.6 同步调度：定时轮询 + 事件触发混合模式

**决策：** 结合定时轮询和事件触发两种模式。

**理由：**
- 定时轮询（默认 15 分钟）保证数据最终一致性
- 事件触发（本地数据变更时立即同步）减少延迟
- 与已有的 WorkflowEngine 触发器机制集成，支持 Cron 表达式自定义同步频率
- 避免过于频繁的 API 调用触发外部服务的速率限制

## 5. 与已有模块的集成点

| 集成模块 | 集成方式 | 说明 |
|---------|---------|------|
| Skill 系统 | SyncSkillProvider 注册为 BuiltinSkill | Agent 可通过工具调用触发同步、查看同步状态、管理同步配置 |
| 记忆系统 | 同步事件写入情景记忆 | Agent 可感知同步历史，如"上次 Todoist 同步失败" |
| 工作流引擎 | SyncTrigger 作为工作流触发器 | 支持"Todoist 新增任务时自动执行工作流" |
| Gateway | 同步状态通过 Channel 推送通知 | 同步失败或冲突时通知用户 |
| 内置 Skill | 直接调用 TodoRepository / ScheduleRepository / HabitRepository | 同步引擎通过已有 Repository 读写本地数据 |

## 6. 调研参考

### 前沿理论
- CRDT（Conflict-free Replicated Data Types）：数学上保证最终一致性的数据结构，适用于多副本并发更新场景。ZhiWei 场景下 CRDT 过于复杂，但其"冲突不可避免则自动解决"的思想值得借鉴（参考 [arxiv:2505.01144](https://arxiv.org/abs/2505.01144)）
- CalDAV Sync Protocol（RFC 6578）：WebDAV 集合同步协议，通过 sync-token 实现增量同步
- Todoist API v1 统一 API：2025 年 Todoist 将 REST API 和 Sync API 合并为统一的 v1 API，sync endpoint 支持增量同步（参考 [Todoist Developer](https://developer.todoist.com/api/v1)）

### 开源项目参考
- [Joplin](https://github.com/laurent22/joplin)（60k+ Stars）：离线优先的笔记应用，同步架构采用 FileApi 抽象层 + sync_items 状态表 + 冲突文件模式
- [DAVx⁵](https://github.com/bitfireAT/davx5-ose)（2k+ Stars）：Android CalDAV/CardDAV 同步客户端，采用 Android SyncAdapter 框架 + 增量同步
- [CalDAV4j](https://github.com/caldav4j/caldav4j)：Java CalDAV 客户端库，提供高层 API 和 ETag 缓存机制，可直接作为 CalDAV 连接器的底层依赖
- [Vikunja](https://vikunja.io/)（5k+ Stars）：开源任务管理应用，内置 CalDAV 服务端支持（VTODO），架构参考其数据模型设计

### 竞品分析
- Todoist：成熟的任务管理服务，API 设计优秀，sync token 增量同步模式值得借鉴
- 滴答清单（TickTick/Dida365）：支持 OAuth2 Open API，任务/习惯/番茄钟数据模型丰富
- Obsidian：local-first 知识管理，无官方 API，社区通过 Local REST API 插件或直接文件操作集成
