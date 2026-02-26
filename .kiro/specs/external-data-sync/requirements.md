# Requirements Document

## Introduction

外部数据源同步模块（`com.lifepilot.sync`）负责将 LifePilot 内部的待办（TodoItem）、日程（ScheduleItem）、习惯（HabitItem）与外部服务进行双向同步。支持的数据源包括 CalDAV 服务器、Todoist、滴答清单（TickTick/Dida365）和 Obsidian Vault。模块通过统一的 SyncEngine 协调同步流程，支持增量同步、冲突检测与解决、OAuth Token 加密存储，并通过 Skill 系统将同步能力暴露给 Agent。

参考文档：
- 架构设计：#[[file:docs/architecture/external-data-sync.md]]
- 特性说明：#[[file:docs/features/external-data-sync.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

## Glossary

- **SyncEngine**: 同步引擎，协调本地数据与远程数据的双向同步流程，包括变更检测、冲突解决和变更应用
- **SyncConnector**: 连接器抽象（sealed interface），封装与单个外部服务的通信协议，permits CalDavConnector / TodoistConnector / DidaConnector / ObsidianConnector
- **SyncProfile**: 同步配置实例，描述一个外部数据源的连接参数、同步方向、冲突策略和同步频率
- **SyncRecord**: 同步映射记录，记录每个本地实体与远程实体的 ID 映射关系、ETag、同步时间戳等状态
- **SyncToken**: 增量同步令牌，标识上次同步的位置，用于从外部服务获取增量变更
- **ConflictPolicy**: 冲突解决策略枚举，包括 LAST_WRITE_WINS / REMOTE_WINS / LOCAL_WINS / USER_CONFIRM
- **ChangeDetector**: 变更检测器，检测本地数据自上次同步以来的变更（新增、修改、删除）
- **ConflictResolver**: 冲突解决器，根据 ConflictPolicy 决定冲突数据的最终版本
- **FieldMapping**: 字段映射接口，定义 LifePilot 内部数据模型与外部服务数据模型之间的双向转换
- **CredentialStore**: 凭证存储组件，使用 AES-GCM 加密管理 OAuth Token 和 API Key
- **SyncScheduler**: 同步调度器，管理定时轮询和事件触发的同步任务
- **RemoteChangeSet**: 远程变更集，包含从外部服务拉取的新增、修改、删除实体及新 SyncToken
- **LocalChangeSet**: 本地变更集，包含本地数据库中自上次同步以来的新增、修改、删除实体
- **SyncConflict**: 同步冲突记录，描述同一实体在本地和远程都被修改的情况，包含双方版本快照
- **SyncResult**: 同步结果，包含本次同步的统计信息（拉取数、推送数、冲突数、错误信息）
- **SyncSkillProvider**: 同步 Skill 提供者，实现 BuiltinSkillProvider 接口，将同步操作注册为 Agent 可调用的工具
- **TodoItem**: LifePilot 待办事项 record（com.lifepilot.skill.builtin.todo.TodoItem）
- **ScheduleItem**: LifePilot 日程 record（com.lifepilot.skill.builtin.schedule.ScheduleItem）
- **HabitItem**: LifePilot 习惯 record（com.lifepilot.skill.builtin.habit.HabitItem）

## Requirements

### Requirement 1: SyncConnector 连接器抽象

**User Story:** As a developer, I want a unified connector abstraction for external data sources, so that the sync engine can interact with different services through a consistent interface.

#### Acceptance Criteria

1. THE SyncConnector SHALL define a sealed interface with permits CalDavConnector, TodoistConnector, DidaConnector, ObsidianConnector
2. THE SyncConnector SHALL declare a `type()` method returning the connector type identifier as a String
3. THE SyncConnector SHALL declare a `testConnection(SyncProfile)` method returning a ConnectionTestResult indicating success or failure with error details
4. THE SyncConnector SHALL declare a `fetchChanges(SyncProfile, @Nullable String syncToken)` method returning a RemoteChangeSet containing created, updated, deleted entities and a new SyncToken
5. THE SyncConnector SHALL declare a `pushChanges(SyncProfile, List<SyncOperation>)` method returning a PushResult containing success count, failure count, and per-item error details
6. WHEN the syncToken parameter of `fetchChanges` is null, THE SyncConnector SHALL perform a full initial sync and return all remote entities as created items

### Requirement 2: CalDavConnector 实现

**User Story:** As a user, I want to sync my CalDAV calendar and tasks with LifePilot, so that my schedules and todos stay consistent across systems.

#### Acceptance Criteria

1. THE CalDavConnector SHALL support syncing VEVENT resources to ScheduleItem and VTODO resources to TodoItem
2. THE CalDavConnector SHALL use CalDAV `sync-collection` REPORT（RFC 6578）with sync-token for incremental change detection
3. WHEN a sync-token is expired or invalid, THE CalDavConnector SHALL fall back to a full sync and return a new valid sync-token
4. THE CalDavConnector SHALL support Basic Auth and OAuth2 authentication methods as specified in the SyncProfile
5. THE CalDavConnector SHALL implement FieldMapping to convert between iCalendar VEVENT/VTODO properties and LifePilot ScheduleItem/TodoItem fields
6. WHEN pushing local changes, THE CalDavConnector SHALL use ETag-based conditional PUT requests to prevent overwriting concurrent remote modifications
7. THE CalDavConnector SHALL parse iCalendar format (RFC 5545) into LifePilot data models
8. THE CalDavConnector SHALL format LifePilot data models back into valid iCalendar format
9. FOR ALL valid ScheduleItem and TodoItem instances, converting to iCalendar then parsing back SHALL produce an equivalent object (round-trip property)

### Requirement 3: TodoistConnector 实现

**User Story:** As a Todoist user, I want to sync my Todoist tasks with LifePilot, so that I can use LifePilot's AI capabilities without abandoning my existing workflow.

#### Acceptance Criteria

1. THE TodoistConnector SHALL sync Todoist tasks to TodoItem using the Todoist API v1 Sync endpoint
2. THE TodoistConnector SHALL use the Todoist sync_token mechanism for incremental change detection
3. WHEN the sync_token is invalid or the initial sync_token is "*", THE TodoistConnector SHALL perform a full sync
4. THE TodoistConnector SHALL authenticate using OAuth2 Bearer Token
5. THE TodoistConnector SHALL implement FieldMapping to convert between Todoist task JSON fields (content, description, priority, due, labels) and TodoItem fields (title, description, priority, status, dueDate, tags)
6. THE TodoistConnector SHALL map Todoist priority values (1-4, where 4 is urgent) to TodoItem.Priority (HIGH, MEDIUM, LOW)
7. WHEN pushing local changes, THE TodoistConnector SHALL use the Todoist Sync API commands (item_add, item_update, item_delete, item_complete) to batch push changes

### Requirement 4: DidaConnector 实现

**User Story:** As a 滴答清单 user, I want to sync my tasks and habits with LifePilot, so that I can leverage LifePilot's intelligent scheduling while keeping my existing habits tracked.

#### Acceptance Criteria

1. THE DidaConnector SHALL sync 滴答清单 tasks to TodoItem and habits to HabitItem using the 滴答清单 Open API
2. THE DidaConnector SHALL authenticate using OAuth2 (Authorization Code flow)
3. THE DidaConnector SHALL use the `updated` timestamp field for incremental change detection
4. THE DidaConnector SHALL implement FieldMapping to convert between 滴答清单 task JSON fields and TodoItem fields
5. THE DidaConnector SHALL implement FieldMapping to convert between 滴答清单 habit JSON fields and HabitItem fields
6. THE DidaConnector SHALL support bidirectional sync for tasks (TodoItem)
7. THE DidaConnector SHALL support pull-only sync for habits (HabitItem), as the 滴答清单 API does not support pushing habit completion records

### Requirement 5: ObsidianConnector 实现

**User Story:** As an Obsidian user, I want to sync my Vault's task and schedule notes with LifePilot, so that my knowledge base and task management stay connected.

#### Acceptance Criteria

1. THE ObsidianConnector SHALL sync Markdown files with YAML frontmatter in a configured Obsidian Vault directory to TodoItem, ScheduleItem, and HabitItem based on the `type` frontmatter field
2. THE ObsidianConnector SHALL use file modification timestamps (via Java NIO) for incremental change detection
3. THE ObsidianConnector SHALL implement FieldMapping to convert between YAML frontmatter key-value pairs and LifePilot data model fields
4. THE ObsidianConnector SHALL parse YAML frontmatter from Markdown files into LifePilot data models
5. THE ObsidianConnector SHALL format LifePilot data models back into Markdown files with valid YAML frontmatter
6. FOR ALL valid TodoItem, ScheduleItem, and HabitItem instances, converting to Markdown with YAML frontmatter then parsing back SHALL produce an equivalent object (round-trip property)
7. WHEN a Markdown file lacks a recognized `type` frontmatter field, THE ObsidianConnector SHALL skip the file without error
8. THE ObsidianConnector SHALL operate directly on the local file system without requiring the Obsidian application to be running

### Requirement 6: SyncEngine 同步引擎

**User Story:** As a developer, I want a unified sync engine that orchestrates the bidirectional sync flow, so that all connectors follow a consistent sync lifecycle.

#### Acceptance Criteria

1. THE SyncEngine SHALL execute a complete bidirectional sync for a given SyncProfile in the following order: fetch remote changes, detect local changes, detect conflicts, resolve conflicts, apply remote changes locally, push local changes remotely, update sync state
2. THE SyncEngine SHALL return a SyncResult containing counts of pulled items, pushed items, conflicts detected, conflicts resolved, and any error messages
3. WHEN the SyncProfile specifies sync direction as PULL_ONLY, THE SyncEngine SHALL skip the push phase and only apply remote changes locally
4. WHEN the SyncProfile specifies sync direction as PUSH_ONLY, THE SyncEngine SHALL skip the pull phase and only push local changes remotely
5. IF an error occurs during the fetch phase, THEN THE SyncEngine SHALL abort the sync, record the error in SyncResult, and preserve the previous sync state unchanged
6. IF an error occurs during the push phase, THEN THE SyncEngine SHALL record the error in SyncResult, retain successfully applied local changes, and mark the sync as partially completed
7. THE SyncEngine SHALL update the SyncRecord for each successfully synced entity with the new remote ID mapping, ETag, and sync timestamp

### Requirement 7: ChangeDetector 变更检测

**User Story:** As a developer, I want to detect local data changes since the last sync, so that the sync engine knows which items to push to the remote service.

#### Acceptance Criteria

1. THE ChangeDetector SHALL detect locally created entities by comparing current local entities against SyncRecord mappings (entities without a SyncRecord are new)
2. THE ChangeDetector SHALL detect locally updated entities by comparing the entity's `updatedAt` timestamp against the SyncRecord's last sync timestamp
3. THE ChangeDetector SHALL detect locally deleted entities by finding SyncRecord entries whose corresponding local entity no longer exists
4. THE ChangeDetector SHALL return a LocalChangeSet containing lists of created, updated, and deleted entities
5. THE ChangeDetector SHALL scope detection to the data types supported by the SyncProfile's connector (e.g., CalDavConnector supports TodoItem and ScheduleItem only)

### Requirement 8: ConflictResolver 冲突解决

**User Story:** As a user, I want data conflicts between LifePilot and external services to be resolved automatically or flagged for my review, so that I don't lose important changes.

#### Acceptance Criteria

1. THE ConflictResolver SHALL detect a conflict when the same entity (matched by SyncRecord mapping) appears in both RemoteChangeSet.updated and LocalChangeSet.updated
2. WHEN ConflictPolicy is LAST_WRITE_WINS, THE ConflictResolver SHALL compare the `updatedAt` timestamps of the local and remote versions and keep the version with the later timestamp
3. WHEN ConflictPolicy is REMOTE_WINS, THE ConflictResolver SHALL always keep the remote version
4. WHEN ConflictPolicy is LOCAL_WINS, THE ConflictResolver SHALL always keep the local version
5. WHEN ConflictPolicy is USER_CONFIRM, THE ConflictResolver SHALL mark the conflict as unresolved in a SyncConflict record containing both local and remote version snapshots
6. THE ConflictResolver SHALL preserve both local and remote version snapshots in the SyncConflict record regardless of the resolution strategy, enabling user review and rollback
7. WHEN a user manually resolves a USER_CONFIRM conflict by choosing a version, THE ConflictResolver SHALL apply the chosen version to both local and remote, and mark the SyncConflict as resolved

### Requirement 9: SyncProfile 配置管理

**User Story:** As a user, I want to configure multiple sync targets with independent settings, so that I can sync different data sources with different strategies.

#### Acceptance Criteria

1. THE SyncProfile SHALL contain the following fields: id, name, connectorType, connectionParams (JSON), syncDirection (BIDIRECTIONAL / PULL_ONLY / PUSH_ONLY), conflictPolicy, cronExpression, enabled flag, dataTypeFilter (list of synced data types), and timestamps (createdAt, updatedAt)
2. THE SyncProfileRepository SHALL support CRUD operations for SyncProfile records persisted in SQLite
3. THE SyncEngine SHALL allow multiple SyncProfile instances to coexist, each independently configured and scheduled
4. WHEN a SyncProfile is disabled (enabled=false), THE SyncScheduler SHALL skip scheduled syncs for the SyncProfile
5. WHEN a SyncProfile's configuration is updated, THE SyncScheduler SHALL apply the new settings (cron expression, enabled flag) without requiring application restart

### Requirement 10: CredentialStore 凭证安全存储

**User Story:** As a user, I want my OAuth tokens and API keys stored securely, so that my external service credentials are protected.

#### Acceptance Criteria

1. THE CredentialStore SHALL encrypt OAuth Access Token, Refresh Token, and API Key values using AES-GCM before storing them in the `sync_credentials` SQLite table
2. THE CredentialStore SHALL derive the encryption key using PBKDF2 from a master password or system-level key, as configured by `lifepilot.sync.credential-key-source`
3. THE CredentialStore SHALL store a unique random IV (Initialization Vector) per credential entry to prevent IV reuse
4. WHEN a stored credential is retrieved, THE CredentialStore SHALL decrypt the value and return the plaintext to the caller
5. FOR ALL credential values, encrypting then decrypting SHALL produce the original plaintext value (round-trip property)
6. WHEN a user revokes authorization for a SyncProfile, THE CredentialStore SHALL delete all associated credential entries from the database
7. THE CredentialStore SHALL reject decryption attempts with an incorrect key by detecting AES-GCM authentication tag failure and returning a descriptive error

### Requirement 11: OAuth Token 自动刷新

**User Story:** As a user, I want expired OAuth tokens to be refreshed automatically, so that my syncs continue without manual re-authorization.

#### Acceptance Criteria

1. WHEN an OAuth Access Token is expired or the remote API returns a 401 Unauthorized response, THE SyncConnector SHALL attempt to refresh the Access Token using the stored Refresh Token
2. WHEN the token refresh succeeds, THE CredentialStore SHALL update the stored Access Token and Refresh Token with the new values
3. IF the token refresh fails (e.g., Refresh Token is also expired or revoked), THEN THE SyncConnector SHALL mark the SyncProfile as requiring re-authorization and record the error
4. THE SyncConnector SHALL retry the original API request once after a successful token refresh

### Requirement 12: SyncScheduler 同步调度

**User Story:** As a user, I want syncs to run automatically on a schedule and also be triggered by local data changes, so that my data stays up-to-date without manual intervention.

#### Acceptance Criteria

1. THE SyncScheduler SHALL execute scheduled syncs for each enabled SyncProfile based on its configured Cron expression (default: every 15 minutes)
2. WHEN a local TodoItem, ScheduleItem, or HabitItem is created, updated, or deleted, THE SyncScheduler SHALL trigger an event-driven sync for all enabled SyncProfile instances that include the affected data type
3. THE SyncScheduler SHALL enforce a minimum interval between event-driven syncs for the same SyncProfile to prevent excessive API calls (configurable, default: 60 seconds)
4. THE SyncScheduler SHALL execute syncs asynchronously using Virtual Threads to avoid blocking the main application thread
5. IF a scheduled sync overlaps with an already-running sync for the same SyncProfile, THEN THE SyncScheduler SHALL skip the overlapping execution and log a warning

### Requirement 13: 数据库迁移

**User Story:** As a developer, I want the sync module's database schema managed by Flyway, so that schema changes are versioned and reproducible.

#### Acceptance Criteria

1. THE Flyway migration V17 SHALL create the `sync_profiles` table with columns for id, name, connector_type, connection_params_json, sync_direction, conflict_policy, cron_expression, enabled, data_type_filter_json, created_at, updated_at
2. THE Flyway migration V17 SHALL create the `sync_records` table with columns for id, profile_id (FK to sync_profiles), local_entity_type, local_entity_id, remote_entity_id, etag, remote_updated_at, last_sync_at, created_at, updated_at
3. THE Flyway migration V17 SHALL create the `sync_credentials` table with columns for id, profile_id (FK to sync_profiles), credential_type, encrypted_value, iv, created_at, updated_at
4. THE Flyway migration V17 SHALL create the `sync_conflicts` table with columns for id, profile_id (FK to sync_profiles), local_entity_type, local_entity_id, local_snapshot_json, remote_snapshot_json, status (UNRESOLVED / RESOLVED), resolved_at, created_at
5. THE Flyway migration V17 SHALL create the `sync_state` table with columns for id, profile_id (FK to sync_profiles), sync_token, last_sync_at, last_sync_status, last_error_message, created_at, updated_at

### Requirement 14: SyncSkillProvider Agent 集成

**User Story:** As a user, I want to trigger and manage syncs through natural language conversation with the Agent, so that I can control synchronization without leaving the chat interface.

#### Acceptance Criteria

1. THE SyncSkillProvider SHALL implement the BuiltinSkillProvider interface and register the following tools: sync-trigger, sync-status, sync-config, sync-conflicts
2. WHEN the Agent invokes the sync-trigger tool with a profile ID, THE SyncSkillProvider SHALL trigger an immediate sync for the specified SyncProfile and return the SyncResult
3. WHEN the Agent invokes the sync-status tool, THE SyncSkillProvider SHALL return the sync state of all enabled SyncProfile instances, including last sync time, status, and error messages
4. WHEN the Agent invokes the sync-config tool with action "list", THE SyncSkillProvider SHALL return all configured SyncProfile instances
5. WHEN the Agent invokes the sync-config tool with action "create" and connection parameters, THE SyncSkillProvider SHALL create a new SyncProfile and return its ID
6. WHEN the Agent invokes the sync-config tool with action "update" and a profile ID, THE SyncSkillProvider SHALL update the specified SyncProfile fields
7. WHEN the Agent invokes the sync-config tool with action "delete" and a profile ID, THE SyncSkillProvider SHALL delete the SyncProfile and its associated credentials and sync records
8. WHEN the Agent invokes the sync-conflicts tool, THE SyncSkillProvider SHALL return all unresolved SyncConflict records with both local and remote version details
9. WHEN the Agent invokes the sync-conflicts tool with a conflict ID and a chosen version (local or remote), THE SyncSkillProvider SHALL resolve the conflict accordingly

### Requirement 15: FieldMapping 数据模型映射

**User Story:** As a developer, I want a consistent field mapping mechanism between LifePilot data models and external service data formats, so that data conversion is reliable and testable.

#### Acceptance Criteria

1. THE FieldMapping interface SHALL declare a `toRemote(L local)` method converting a LifePilot entity to the remote service's data format
2. THE FieldMapping interface SHALL declare a `toLocal(R remote)` method converting a remote service entity to a LifePilot entity
3. THE FieldMapping interface SHALL declare an `extractConflictFields(L local, R remote)` method returning a Map of field names to value pairs for conflict comparison
4. WHEN mapping TodoItem to Todoist task, THE TodoistFieldMapping SHALL map title→content, description→description, priority→priority (with value inversion: HIGH→4, MEDIUM→3, LOW→2), dueDate→due.date, tags→labels
5. WHEN mapping ScheduleItem to CalDAV VEVENT, THE CalDavFieldMapping SHALL map title→SUMMARY, startTime→DTSTART, endTime→DTEND, location→LOCATION, notes→DESCRIPTION
6. WHEN mapping TodoItem to CalDAV VTODO, THE CalDavFieldMapping SHALL map title→SUMMARY, description→DESCRIPTION, dueDate→DUE, priority→PRIORITY, status→STATUS (PENDING→NEEDS-ACTION, IN_PROGRESS→IN-PROCESS, COMPLETED→COMPLETED)

### Requirement 16: 配置外部化

**User Story:** As a user, I want sync module settings configurable through application.yml, so that I can adjust sync behavior without code changes.

#### Acceptance Criteria

1. THE SyncProperties SHALL expose the following configurable parameters via `lifepilot.sync.*`: enabled (default: true), default-cron (default: "0 */15 * * * *"), default-conflict-policy (default: LAST_WRITE_WINS), timeout (default: 30 seconds), max-retries (default: 2), credential-key-source (default: system-key), obsidian-vault-path (default: empty), event-sync-min-interval (default: 60 seconds)
2. THE SyncProperties SHALL expose nested connector configuration via `lifepilot.sync.connectors.caldav.*`, `lifepilot.sync.connectors.todoist.*`, `lifepilot.sync.connectors.dida.*`
3. WHEN `lifepilot.sync.enabled` is false, THE SyncAutoConfiguration SHALL not register any sync-related Spring Beans

### Requirement 17: 同步事件与记忆集成

**User Story:** As a user, I want the Agent to be aware of sync events, so that it can proactively inform me about sync results and issues.

#### Acceptance Criteria

1. WHEN a sync completes successfully, THE SyncEngine SHALL publish a sync completion event containing the SyncProfile name, item counts, and timestamp
2. WHEN a sync fails, THE SyncEngine SHALL publish a sync failure event containing the SyncProfile name, error message, and timestamp
3. WHEN new unresolved conflicts are detected, THE SyncEngine SHALL publish a conflict event containing the SyncProfile name and conflict count
4. THE SyncEngine SHALL write sync events to the episodic memory (L2) via the memory system, enabling the Agent to recall sync history during conversations

### Requirement 18: 连接测试

**User Story:** As a user, I want to test my sync configuration before enabling it, so that I can verify connectivity and credentials are correct.

#### Acceptance Criteria

1. WHEN the Agent invokes the sync-config tool with action "test" and a profile ID, THE SyncSkillProvider SHALL call `SyncConnector.testConnection()` for the specified SyncProfile
2. THE ConnectionTestResult SHALL contain a success flag, response time in milliseconds, and an error message if the test failed
3. WHEN the connection test fails due to authentication error, THE ConnectionTestResult SHALL include a descriptive message indicating credential issues
4. WHEN the connection test fails due to network error, THE ConnectionTestResult SHALL include a descriptive message indicating connectivity issues
