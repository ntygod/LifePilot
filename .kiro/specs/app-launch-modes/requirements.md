# Requirements Document

## Introduction

LifePilot 当前通过 `java -jar lifepilot.jar` 启动时，同时激活 Tomcat Web 服务器和 CLI 交互式 Shell。用户需要根据不同使用场景选择启动模式：纯命令行使用、后台 Web 服务、完整模式、或系统托盘常驻模式。同时，ProactiveReasoner 主动推理引擎已实现通知分发（NotificationDispatcher），但缺少操作系统原生通知通道，需要通过系统托盘实现桌面通知推送。

参考文档：
- 特性设计：#[[file:docs/features/gateway-channels.md]]
- 架构设计：#[[file:docs/architecture/cli-interaction.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]

## Glossary

- **LaunchMode**: 应用启动模式枚举，包含 CLI、WEB、FULL、TRAY 四种模式
- **FastPathRunner**: 在 Spring 上下文初始化之前拦截简单命令的快速路径组件
- **CliShell**: 基于 JLine 3 的 CLI 交互式 Shell 主循环，实现 CommandLineRunner
- **CliAutoConfiguration**: CLI 层 Spring Boot 自动配置类，通过 `lifepilot.cli.enabled` 控制激活
- **GatewayAutoConfiguration**: Gateway 层 Spring Boot 自动配置类，通过 `lifepilot.gateway.enabled` 控制激活
- **NotificationChannel**: 通知通道接口，定义 `id()` 和 `send(ProactiveNotification)` 方法
- **NotificationDispatcher**: 通知分发器，根据紧急程度选择通道并发送通知
- **ProactiveReasoner**: 主动推理引擎，两阶段推理产生主动通知
- **SystemTray**: Java AWT 提供的系统托盘 API（`java.awt.SystemTray`）
- **TrayNotificationChannel**: 系统托盘通知通道，实现 NotificationChannel 接口，通过操作系统原生通知推送提醒
- **TrayIcon**: 系统托盘图标，显示应用运行状态并提供右键菜单

## Requirements

### Requirement 1: 启动模式参数解析

**User Story:** As a 用户, I want 通过命令行参数指定启动模式, so that 可以根据不同场景选择合适的运行方式

#### Acceptance Criteria

1. WHEN 用户传入 `--mode=cli` 参数, THE FastPathRunner SHALL 将 LaunchMode 设置为 CLI 并传递给 Spring Boot 启动流程
2. WHEN 用户传入 `--mode=web` 参数, THE FastPathRunner SHALL 将 LaunchMode 设置为 WEB 并传递给 Spring Boot 启动流程
3. WHEN 用户传入 `--mode=tray` 参数, THE FastPathRunner SHALL 将 LaunchMode 设置为 TRAY 并传递给 Spring Boot 启动流程
4. WHEN 用户未传入 `--mode` 参数, THE FastPathRunner SHALL 将 LaunchMode 默认设置为 FULL
5. WHEN 用户传入无效的 `--mode` 值, THE FastPathRunner SHALL 输出支持的模式列表并以非零退出码终止
6. THE FastPathRunner SHALL 在 `--help` 输出中包含 `--mode` 参数的说明和可选值

### Requirement 2: CLI 模式启动

**User Story:** As a 命令行用户, I want 只启动 CLI 交互式 Shell 而不启动 Tomcat, so that 可以在纯终端环境下轻量使用 LifePilot

#### Acceptance Criteria

1. WHILE LaunchMode 为 CLI, THE LifePilotApplication SHALL 禁用嵌入式 Web 服务器（设置 `spring.main.web-application-type=none`）
2. WHILE LaunchMode 为 CLI, THE CliAutoConfiguration SHALL 正常激活并注册所有 CLI Bean
3. WHILE LaunchMode 为 CLI, THE GatewayAutoConfiguration SHALL 保持激活状态以支持 CLI 通道适配器的消息处理
4. WHILE LaunchMode 为 CLI, THE CliShell SHALL 正常进入交互式命令循环或执行单次命令

### Requirement 3: Web 模式启动

**User Story:** As a 服务部署者, I want 只启动 Tomcat Web 服务器而不启动 CLI Shell, so that 可以作为后台服务运行并通过 Web UI 访问

#### Acceptance Criteria

1. WHILE LaunchMode 为 WEB, THE LifePilotApplication SHALL 正常启动嵌入式 Web 服务器
2. WHILE LaunchMode 为 WEB, THE CliAutoConfiguration SHALL 被禁用（`lifepilot.cli.enabled=false`）
3. WHILE LaunchMode 为 WEB, THE LifePilotApplication SHALL 在启动完成后保持运行而不阻塞在 CLI 输入循环上
4. WHILE LaunchMode 为 WEB, THE GatewayAutoConfiguration SHALL 正常激活以处理 Web 请求

### Requirement 4: Full 模式启动

**User Story:** As a 桌面用户, I want 同时使用 CLI 和 Web UI, so that 可以在终端和浏览器之间自由切换

#### Acceptance Criteria

1. WHILE LaunchMode 为 FULL, THE LifePilotApplication SHALL 同时启动嵌入式 Web 服务器和 CLI Shell
2. WHILE LaunchMode 为 FULL, THE CliAutoConfiguration SHALL 正常激活
3. WHILE LaunchMode 为 FULL, THE GatewayAutoConfiguration SHALL 正常激活
4. THE FULL 模式 SHALL 与当前默认启动行为保持一致

### Requirement 5: Tray 模式启动

**User Story:** As a 桌面用户, I want LifePilot 最小化到系统托盘后台运行, so that 可以通过托盘图标和原生通知与 LifePilot 交互而不占用终端窗口

#### Acceptance Criteria

1. WHILE LaunchMode 为 TRAY, THE LifePilotApplication SHALL 启动嵌入式 Web 服务器但禁用 CLI Shell
2. WHILE LaunchMode 为 TRAY, THE TrayIcon SHALL 在操作系统系统托盘区域显示 LifePilot 图标
3. WHILE LaunchMode 为 TRAY, THE TrayNotificationChannel SHALL 注册到 NotificationDispatcher 作为主通知通道
4. IF 操作系统不支持系统托盘（`SystemTray.isSupported()` 返回 false）, THEN THE LifePilotApplication SHALL 回退到 WEB 模式并输出警告日志

### Requirement 6: 系统托盘图标与菜单

**User Story:** As a 桌面用户, I want 通过系统托盘图标快速访问 LifePilot 功能, so that 可以在不打开终端或浏览器的情况下查看状态和执行常用操作

#### Acceptance Criteria

1. THE TrayIcon SHALL 显示 LifePilot 应用图标并附带工具提示文本显示应用名称和运行状态
2. WHEN 用户右键点击 TrayIcon, THE TrayIcon SHALL 显示弹出菜单，包含以下菜单项：查看最近提醒、打开 Web UI、暂停通知、恢复通知、退出
3. WHEN 用户选择「打开 Web UI」菜单项, THE TrayIcon SHALL 使用系统默认浏览器打开 LifePilot Web UI 地址
4. WHEN 用户选择「退出」菜单项, THE TrayIcon SHALL 触发 Spring ApplicationContext 优雅关闭
5. WHEN 用户选择「查看最近提醒」菜单项, THE TrayIcon SHALL 使用系统默认浏览器打开 Web UI 的通知页面

### Requirement 7: 系统托盘原生通知推送

**User Story:** As a 桌面用户, I want 收到操作系统原生通知提醒, so that 即使没有打开浏览器或终端也能及时收到 LifePilot 的主动提醒

#### Acceptance Criteria

1. THE TrayNotificationChannel SHALL 实现 NotificationChannel 接口
2. WHEN NotificationDispatcher 分发一条 HIGH 或 MEDIUM 紧急程度的通知, THE TrayNotificationChannel SHALL 通过 `TrayIcon.displayMessage()` 显示操作系统原生通知
3. THE TrayNotificationChannel SHALL 根据通知的紧急程度（Urgency）映射到对应的 `TrayIcon.MessageType`：HIGH 映射为 WARNING，MEDIUM 映射为 INFO
4. WHEN 用户通过托盘菜单选择「暂停通知」, THE TrayNotificationChannel SHALL 停止显示原生通知弹窗，但继续将通知持久化到数据库
5. WHEN 用户通过托盘菜单选择「恢复通知」, THE TrayNotificationChannel SHALL 恢复显示原生通知弹窗

### Requirement 8: 通知分发器多通道支持

**User Story:** As a 开发者, I want NotificationDispatcher 支持多个通知通道, so that 通知可以同时发送到系统托盘、CLI、日志等多个渠道

#### Acceptance Criteria

1. THE NotificationDispatcher SHALL 接受一个 NotificationChannel 列表而非单个 LogNotificationChannel
2. WHEN NotificationDispatcher 分发一条通知, THE NotificationDispatcher SHALL 将通知发送到所有已注册的 NotificationChannel
3. IF 某个 NotificationChannel 发送失败, THEN THE NotificationDispatcher SHALL 记录 WARN 级别日志并继续发送到其余通道，不中断分发流程

### Requirement 9: 启动模式配置外部化

**User Story:** As a 用户, I want 通过配置文件预设默认启动模式, so that 不需要每次启动都传入 `--mode` 参数

#### Acceptance Criteria

1. THE LifePilotApplication SHALL 支持通过 `lifepilot.app.launch-mode` 配置键设置默认启动模式
2. WHEN 同时存在命令行参数 `--mode` 和配置文件 `lifepilot.app.launch-mode`, THE LifePilotApplication SHALL 优先使用命令行参数的值
3. THE `lifepilot.app.launch-mode` 配置键的默认值 SHALL 为 `full`
