# Implementation Plan: 外部数据源同步

## Overview

按自底向上顺序实现 `com.lifepilot.sync` 模块：先建立数据模型和数据库 Schema，再实现基础设施层（Repository、CredentialStore），然后实现核心引擎（ChangeDetector、ConflictResolver、SyncEngine），接着实现四个连接器（CalDAV、Todoist、滴答清单、Obsidian），最后实现调度层和 Agent 集成层，逐步集成并验证。

## Tasks

- [x] 1. 数据模型、枚举与 Flyway V17 迁移
  - [x] 1.1 创建枚举类型和基础 record
    - 创建 `com.lifepilot.sync.model` 包
    - 实现 ConflictPolicy、SyncDirection、SyncStatus 枚举
    - 实现 FieldDiff record
    - 实现 ConnectionTestResult、PushResult（含内部 PushError record）
    - 实现 SyncOperation sealed interface（Create / Update / Delete）
    - _Requirements: 1.1, 1.4, 1.5, 6.2_

  - [x] 1.2 创建核心数据 record
    - 实现 SyncProfile record（含 @Builder(toBuilder = true)）
    - 实现 SyncRecord record
    - 实现 SyncState record
    - 实现 SyncConflict record（含内部 ConflictStatus 枚举）
    - 实现 SyncResult record
    - 实现 RemoteChangeSet record（含内部 RemoteEntity record）
    - 实现 LocalChangeSet record（含内部 LocalEntity record）
    - _Requirements: 9.1, 6.2, 7.4, 8.5, 8.6_

  - [x] 1.3 创建 SyncException sealed 异常层次
    - 实现 SyncException sealed class
    - 实现 ConnectionException、RemoteApiException、MappingException、CredentialException、SyncStateException 子类
    - _Requirements: 6.5, 6.6_

  - [x] 1.4 创建 Flyway V17 迁移脚本
    - 创建 `V17__create_sync_tables.sql`
    - 包含 sync_profiles、sync_records、sync_credentials、sync_conflicts、sync_state 五张表
    - 包含所有索引（idx_sync_records_profile、idx_sync_records_mapping、idx_sync_credentials_profile、idx_sync_conflicts_profile、idx_sync_conflicts_status、idx_sync_state_profile）
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5_

  - [x] 1.5 创建 SyncProperties 配置类和 application.yml 配置项
    - 实现 `SyncProperties` @ConfigurationProperties(prefix = "lifepilot.sync")
    - 包含 enabled、default-cron、default-conflict-policy、timeout、max-retries、credential-key-source、obsidian-vault-path、event-sync-min-interval
    - 包含嵌套 Connectors 配置（caldav / todoist / dida）
    - 在 application.yml 中声明所有配置项及默认值
    - _Requirements: 16.1, 16.2, 16.3_

- [x] 2. Repository 层与 CredentialStore
  - [x] 2.1 实现 SyncProfileRepository
    - 基于 JdbcTemplate 实现 CRUD 操作
    - 支持 findById、findAll、findAllEnabled、create、update、delete
    - _Requirements: 9.2, 9.3_

  - [ ]* 2.2 编写 SyncProfile CRUD 往返属性测试
    - **Property 7: SyncProfile CRUD 往返**
    - 使用 jqwik 随机 SyncProfile 生成器验证 create → findById 往返一致性
    - **Validates: Requirements 9.2**

  - [x] 2.3 实现 SyncRecordRepository
    - 基于 JdbcTemplate 实现 CRUD 操作
    - 支持 findByProfileId、findByLocalEntity、findByRemoteEntity、upsert、deleteByProfileId
    - _Requirements: 6.7_

  - [x] 2.4 实现 SyncStateRepository 和 SyncConflictRepository
    - SyncStateRepository：findByProfileId、upsert
    - SyncConflictRepository：findUnresolvedByProfileId、create、resolve
    - _Requirements: 8.5, 8.6, 8.7_

  - [x] 2.5 实现 CredentialStore 凭证加密存储
    - 实现 AES-GCM 加密/解密逻辑（PBKDF2 密钥派生、随机 IV 生成）
    - 实现 store、retrieve、deleteByProfileId 方法
    - 错误密钥检测（AES-GCM auth tag 失败）
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7_

  - [ ]* 2.6 编写 CredentialStore 属性测试
    - **Property 6: 凭证加密解密往返**
    - **Property 17: 凭证 IV 唯一性**
    - 使用 jqwik 随机非空字符串生成器验证加密→解密往返一致性
    - 验证两次存储相同明文值产生不同 IV
    - **Validates: Requirements 10.3, 10.5**

- [x] 3. Checkpoint — 基础设施层验证
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. FieldMapping 接口与字段映射实现
  - [x] 4.1 创建 FieldMapping 泛型接口
    - 定义 toRemote、toLocal、extractConflictFields 方法
    - _Requirements: 15.1, 15.2, 15.3_

  - [x] 4.2 实现 ICalendarParser
    - 实现 RFC 5545 iCalendar 格式的解析和生成
    - 支持 VEVENT 和 VTODO 组件
    - 处理多行折叠、特殊字符转义
    - _Requirements: 2.7, 2.8_

  - [x] 4.3 实现 CalDavFieldMapping
    - 实现 FieldMapping<ScheduleItem, String>（VEVENT 映射）
    - 实现 FieldMapping<TodoItem, String>（VTODO 映射）
    - 字段映射：title↔SUMMARY、startTime↔DTSTART、endTime↔DTEND、location↔LOCATION、notes↔DESCRIPTION
    - 优先级映射：HIGH→1、MEDIUM→5、LOW→9
    - 状态映射：PENDING→NEEDS-ACTION、IN_PROGRESS→IN-PROCESS、COMPLETED→COMPLETED
    - _Requirements: 2.5, 15.5, 15.6_

  - [ ]* 4.4 编写 CalDAV iCalendar 往返属性测试
    - **Property 1: CalDAV iCalendar 往返转换**
    - 使用 jqwik 随机 ScheduleItem / TodoItem 生成器验证 toRemote → toLocal 往返一致性
    - **Validates: Requirements 2.9**

  - [x] 4.5 实现 TodoistFieldMapping
    - 实现 FieldMapping<TodoItem, Map<String, Object>>
    - 字段映射：title→content、description→description、priority→priority（值反转 HIGH→4, MEDIUM→3, LOW→2）、dueDate→due.date、tags→labels
    - _Requirements: 3.5, 3.6, 15.4_

  - [ ]* 4.6 编写 Todoist 字段映射往返属性测试
    - **Property 2: Todoist 字段映射往返转换**
    - 使用 jqwik 随机 TodoItem 生成器（含 Priority 枚举）验证往返一致性
    - **Validates: Requirements 3.5, 3.6**

  - [x] 4.7 实现 DidaFieldMapping
    - 实现 FieldMapping<TodoItem, Map<String, Object>>（任务映射）
    - 实现 FieldMapping<HabitItem, Map<String, Object>>（习惯映射）
    - _Requirements: 4.4, 4.5_

  - [ ]* 4.8 编写滴答清单字段映射往返属性测试
    - **Property 3: 滴答清单任务字段映射往返转换**
    - **Property 4: 滴答清单习惯字段映射往返转换**
    - 使用 jqwik 随机 TodoItem / HabitItem 生成器验证往返一致性
    - **Validates: Requirements 4.4, 4.5**

  - [x] 4.9 实现 YamlFrontmatterParser
    - 解析 `---` 分隔的 YAML frontmatter
    - 提取键值对，处理缺失字段和特殊字符
    - _Requirements: 5.4_

  - [x] 4.10 实现 ObsidianFieldMapping
    - 实现 FieldMapping<TodoItem, String>、FieldMapping<ScheduleItem, String>、FieldMapping<HabitItem, String>
    - 转换为 Markdown + YAML frontmatter 格式
    - _Requirements: 5.3, 5.5_

  - [ ]* 4.11 编写 Obsidian YAML Frontmatter 往返属性测试
    - **Property 5: Obsidian YAML Frontmatter 往返转换**
    - 使用 jqwik 随机 TodoItem / ScheduleItem / HabitItem 生成器验证往返一致性
    - **Validates: Requirements 5.6**

- [x] 5. Checkpoint — 字段映射层验证
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. ChangeDetector 与 ConflictResolver
  - [x] 6.1 实现 ChangeDetector
    - 检测本地新增（无 SyncRecord 的实体）、修改（updatedAt > lastSyncAt）、删除（SyncRecord 存在但实体不存在）
    - 按 SyncProfile.dataTypeFilter 过滤检测范围
    - 返回 LocalChangeSet
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

  - [ ]* 6.2 编写 ChangeDetector 属性测试
    - **Property 8: ChangeDetector 变更分类正确性**
    - **Property 9: ChangeDetector 数据类型过滤**
    - 使用 jqwik 随机实体集合 + SyncRecord 集合生成器
    - 验证三个列表互不相交且并集覆盖所有变更实体
    - 验证返回实体类型仅包含 dataTypeFilter 中指定的类型
    - **Validates: Requirements 7.1, 7.2, 7.3, 7.5**

  - [x] 6.3 实现 ConflictResolver
    - 检测冲突：同一实体同时出现在 RemoteChangeSet.updated 和 LocalChangeSet.updated
    - 实现四种策略：LAST_WRITE_WINS、REMOTE_WINS、LOCAL_WINS、USER_CONFIRM
    - 所有冲突保存双方版本快照到 SyncConflict
    - 返回 ConflictResolution（resolvedRemoteChanges + resolvedLocalChanges + unresolvedConflicts）
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6_

  - [ ]* 6.4 编写 ConflictResolver 属性测试
    - **Property 10: 冲突检测正确性**
    - **Property 11: 冲突策略决定解决结果**
    - **Property 12: 冲突快照完整保留**
    - 使用 jqwik 随机 RemoteChangeSet + LocalChangeSet + ConflictPolicy 生成器
    - 验证冲突检测条件、策略决定结果、快照非空
    - **Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 8.6**

- [x] 7. SyncEngine 同步引擎
  - [x] 7.1 实现 SyncEngine 核心同步流程
    - 实现 sync(SyncProfile) 方法
    - 完整双向流程：fetchRemote → detectLocal → resolveConflicts → applyRemote → pushLocal → updateState
    - 按 SyncDirection 分支：BIDIRECTIONAL / PULL_ONLY / PUSH_ONLY
    - Fetch 阶段失败中止同步、Push 阶段部分失败标记 PARTIAL
    - 更新 SyncRecord 和 SyncState
    - 写入同步事件到 EpisodicMemory
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 17.1, 17.2, 17.3, 17.4_

  - [ ]* 7.2 编写 SyncEngine 属性测试
    - **Property 13: 同步方向控制阶段执行**
    - **Property 14: Fetch 错误保留同步状态**
    - **Property 15: SyncResult 计数一致性**
    - **Property 16: 成功同步更新 SyncRecord**
    - **Property 20: 同步事件与同步结果匹配**
    - 使用 jqwik 随机 SyncDirection + Mock Connector、异常注入、随机变更集生成器
    - 验证 PULL_ONLY 不调用 pushChanges、PUSH_ONLY 不调用 fetchChanges
    - 验证 fetch 异常时 SyncState/SyncRecord 不变
    - 验证 SyncResult 计数与实际操作数一致
    - 验证成功同步后 SyncRecord 存在且 lastSyncAt 不早于同步开始时间
    - 验证同步成功/失败/冲突时发布对应事件
    - **Validates: Requirements 6.2, 6.3, 6.4, 6.5, 6.7, 17.1, 17.2, 17.3**

- [x] 8. Checkpoint — 核心引擎验证
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. 连接器实现
  - [x] 9.1 实现 SyncConnector sealed interface
    - 定义 type()、testConnection()、fetchChanges()、pushChanges() 方法签名
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

  - [x] 9.2 实现 CalDavConnector
    - 使用 Java HttpClient 发送 CalDAV REPORT / PUT / DELETE 请求
    - fetchChanges：sync-collection REPORT（RFC 6578）+ sync-token 增量
    - pushChanges：ETag 条件 PUT（If-Match header）
    - 支持 Basic Auth 和 OAuth2 Bearer Token 认证
    - sync-token 过期时回退全量同步
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.6_

  - [ ]* 9.3 编写 CalDavConnector 单元测试
    - 测试 sync-token 过期回退全量同步
    - 测试 ETag 条件 PUT
    - 测试 Basic Auth / OAuth2 认证切换
    - _Requirements: 2.2, 2.3, 2.4, 2.6_

  - [x] 9.4 实现 TodoistConnector
    - 使用 Java HttpClient 调用 Todoist API v1 Sync endpoint
    - fetchChanges：POST /sync/v1/sync 带 sync_token + resource_types
    - pushChanges：POST /sync/v1/sync 带 commands 数组（item_add / item_update / item_delete / item_complete）
    - OAuth2 Bearer Token 认证 + 401 自动 Token 刷新
    - sync_token="*" 时执行全量同步
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.7, 11.1, 11.2, 11.3, 11.4_

  - [ ]* 9.5 编写 TodoistConnector 单元测试
    - 测试 sync_token="*" 全量同步
    - 测试 batch commands 格式正确性
    - 测试 401 Token 刷新流程
    - _Requirements: 3.2, 3.3, 3.7, 11.1, 11.4_

  - [x] 9.6 实现 DidaConnector
    - 使用 Java HttpClient 调用滴答清单 Open API
    - fetchChanges：GET /open/v1/task + GET /open/v1/habit，基于 updated 时间戳过滤增量
    - pushChanges：POST/PUT/DELETE /open/v1/task（任务双向），习惯仅拉取
    - OAuth2 Authorization Code flow 认证
    - HabitItem 仅支持 PULL_ONLY 方向
    - _Requirements: 4.1, 4.2, 4.3, 4.6, 4.7_

  - [ ]* 9.7 编写 DidaConnector 单元测试
    - 测试习惯 PULL_ONLY 限制
    - 测试 OAuth2 Authorization Code flow
    - _Requirements: 4.2, 4.7_

  - [x] 9.8 实现 ObsidianConnector
    - 使用 Java NIO 操作本地文件系统
    - fetchChanges：遍历 Vault 目录，比较文件修改时间与上次同步时间
    - pushChanges：写入 Markdown 文件（YAML frontmatter + body）
    - 跳过无 type 字段或 type 不在 [todo, schedule, habit] 中的文件
    - 不依赖 Obsidian 应用运行
    - _Requirements: 5.1, 5.2, 5.5, 5.7, 5.8_

  - [ ]* 9.9 编写 ObsidianConnector 单元测试
    - 测试无 type 字段文件跳过
    - 测试文件修改时间检测
    - _Requirements: 5.2, 5.7_

- [x] 10. Checkpoint — 连接器层验证
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. SyncScheduler 同步调度
  - [x] 11.1 实现 SyncScheduler
    - 使用 ScheduledExecutorService + Virtual Thread 执行同步任务
    - 基于 SyncProfile.cronExpression 定时调度
    - 事件触发同步：@EventListener 监听本地数据变更事件
    - 防抖：同一 profile 事件触发间隔不小于 event-sync-min-interval
    - 重叠检测：ConcurrentHashMap 标记正在运行的 profile，重叠时跳过
    - 支持 start()、triggerEventSync()、refreshSchedule() 方法
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 9.4, 9.5_

  - [ ]* 11.2 编写 SyncScheduler 属性测试
    - **Property 18: 事件触发同步最小间隔**
    - **Property 19: 重叠同步跳过**
    - **Property 21: 禁用 Profile 跳过调度**
    - 使用 jqwik 随机时间间隔序列、并发同步请求、enabled 状态生成器
    - 验证间隔小于 min-interval 时第二次请求被跳过
    - 验证已有同步运行时新请求被跳过
    - 验证 enabled=false 的 profile 不执行定时同步
    - **Validates: Requirements 12.3, 12.5, 9.4**

- [x] 12. SyncSkillProvider Agent 集成
  - [x] 12.1 实现 SyncSkillProvider
    - 实现 BuiltinSkillProvider 接口，使用 @BuiltinSkill(id = "sync", order = 5) 注解
    - provide() 返回 SkillDefinition（id="sync"、name="数据同步"、4 个 allowedTools）
    - registerTools() 注册 sync-trigger、sync-status、sync-config、sync-conflicts 四个工具
    - sync-trigger：触发即时同步，返回 SyncResult
    - sync-status：查询所有启用 profile 的同步状态
    - sync-config：CRUD 操作（list / create / update / delete / test）
    - sync-conflicts：查看未解决冲突 / 手动解决冲突
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8, 14.9, 18.1, 18.2, 18.3, 18.4_

  - [ ]* 12.2 编写 SyncSkillProvider 单元测试
    - 验证 4 个工具注册正确
    - 验证各 action 参数解析
    - _Requirements: 14.1_

- [x] 13. SyncAutoConfiguration 自动配置
  - [x] 13.1 实现 SyncAutoConfiguration
    - @AutoConfiguration + @ConditionalOnProperty(name = "lifepilot.sync.enabled")
    - @EnableConfigurationProperties(SyncProperties.class)
    - 注册所有 sync 模块 Bean：Connector 实例、SyncEngine、ChangeDetector、ConflictResolver、CredentialStore、SyncScheduler、Repository 实例、SyncSkillProvider
    - enabled=false 时不注册任何 sync 相关 Bean
    - 在 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 中注册
    - _Requirements: 16.3_

  - [ ]* 13.2 编写 SyncAutoConfiguration 集成测试
    - 测试 enabled=true 时所有 Bean 正确注册
    - 测试 enabled=false 时无 sync Bean 注册
    - _Requirements: 16.3_

- [ ] 14. 集成测试
  - [ ]* 14.1 编写 SyncModule_BuiltinSkill 集成测试
    - 验证 SyncSkillProvider 注册到 SkillRegistry + DynamicToolRegistry
    - _Requirements: 14.1_

  - [ ]* 14.2 编写 SyncEngine_Repository 集成测试
    - 验证完整同步流程 + SQLite 持久化
    - 使用 Mock Connector 模拟远程服务
    - _Requirements: 6.1, 6.7_

  - [ ]* 14.3 编写 Flyway V17 集成测试
    - 验证迁移脚本执行成功
    - 验证五张表结构正确
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5_

- [x] 15. Final checkpoint — 全量验证
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from design document (21 properties)
- Unit tests validate specific examples and edge cases
- 所有代码遵循 LifePilot 编码规范：中文注释/Javadoc/测试方法名、record 优先、sealed interface 穷举
- Git scope 使用 `sync`，提交格式：`feat(sync): 中文描述`
