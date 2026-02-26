# Implementation Plan: 应用启动模式

## Overview

引入 LaunchMode 枚举和启动模式解析机制，通过 `--mode=cli|web|tray|full` 命令行参数或 `lifepilot.app.launch-mode` 配置控制应用启动行为。同时重构 NotificationDispatcher 为多通道分发，新增 TrayNotificationChannel 实现操作系统原生通知。按自底向上顺序实现：先建立核心枚举和配置，再改造启动流程，然后实现托盘功能，最后重构通知系统并集成。

## Tasks

- [x] 1. 创建 LaunchMode 枚举和 AppConfigProperties
  - [x] 1.1 创建 LaunchMode 枚举
    - 在 `com.lifepilot.app` 包下创建 `LaunchMode` 枚举，包含 CLI、WEB、TRAY、FULL 四个值
    - 实现 `fromString(String value)` 静态方法，忽略大小写解析，无效值返回 `Optional.empty()`
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

  - [x] 1.2 创建 AppConfigProperties
    - 在 `com.lifepilot.app` 包下创建 `AppConfigProperties`，前缀 `lifepilot.app`
    - 包含 `launchMode` 字段，默认值 `"full"`
    - 在 `application.yml` 中添加 `lifepilot.app.launch-mode: full` 配置项
    - _Requirements: 9.1, 9.3_

  - [ ]* 1.3 编写 LaunchMode 属性测试
    - **Property 1: LaunchMode 解析往返**
    - **Property 2: 无效模式字符串拒绝**
    - **Validates: Requirements 1.1, 1.2, 1.3, 1.5**

- [x] 2. 扩展 FastPathRunner 支持 --mode 解析
  - [x] 2.1 实现 resolveMode() 静态方法
    - 在 `FastPathRunner` 中新增 `resolveMode(String[] args)` 方法
    - 遍历 args 查找 `--mode=xxx` 参数，调用 `LaunchMode.fromString()` 解析
    - 无效值时输出支持的模式列表到 stderr 并调用 `System.exit(1)`
    - 未传入 `--mode` 时返回 `LaunchMode.FULL`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

  - [x] 2.2 扩展 printHelp() 输出
    - 在 `--help` 输出中添加 `--mode=<mode>` 参数说明和可选值列表
    - _Requirements: 1.6_

  - [ ]* 2.3 编写 FastPathRunner 单元测试
    - 测试 `--mode=cli/web/tray/full` 各返回正确枚举值
    - 测试无 `--mode` 参数默认返回 FULL
    - 测试 `--help` 输出包含 `--mode` 说明
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.6_

  - [ ]* 2.4 编写命令行参数覆盖配置属性测试
    - **Property 8: 命令行参数覆盖配置文件**
    - **Validates: Requirements 9.2**

- [x] 3. 改造 LifePilotApplication.main() 启动流程
  - [x] 3.1 重构 main() 方法集成 LaunchMode
    - 在 `FastPathRunner.tryFastPath()` 之后调用 `FastPathRunner.resolveMode(args)` 获取模式
    - 创建 `SpringApplication` 实例，根据 LaunchMode 通过 `setDefaultProperties()` 注入对应属性
    - CLI → `spring.main.web-application-type=none`
    - WEB → `lifepilot.cli.enabled=false`
    - TRAY → `lifepilot.cli.enabled=false` + `lifepilot.tray.enabled=true`
    - FULL → 默认配置
    - 所有模式设置 `lifepilot.app.launch-mode` 为当前模式小写名
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 3.3, 3.4, 4.1, 4.2, 4.3, 4.4, 5.1, 9.2_

  - [ ]* 3.2 编写启动模式到配置映射属性测试
    - **Property 3: 启动模式到配置映射正确性**
    - **Validates: Requirements 2.1, 3.2, 4.2, 4.3, 5.1**

- [x] 4. Checkpoint — 启动模式解析和配置注入
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. 创建 TrayConfigProperties 和 TrayAutoConfiguration
  - [x] 5.1 创建 TrayConfigProperties
    - 在 `com.lifepilot.interaction.tray.config` 包下创建 `TrayConfigProperties`，前缀 `lifepilot.tray`
    - 包含 `enabled`（默认 false）、`tooltip`（默认 "LifePilot - AI 生活助手"）、`webUiUrl`（默认 "http://localhost:8080"）字段
    - 在 `application.yml` 中添加 `lifepilot.tray` 配置段
    - _Requirements: 5.2, 6.1_

  - [x] 5.2 创建 TrayAutoConfiguration
    - 在 `com.lifepilot.interaction.tray.config` 包下创建 `TrayAutoConfiguration`
    - 使用 `@ConditionalOnProperty(name = "lifepilot.tray.enabled", havingValue = "true")` 控制激活
    - 注册 `TrayManager` 和 `TrayNotificationChannel` Bean
    - 在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 中注册
    - _Requirements: 5.1, 5.2, 5.3_

- [x] 6. 实现 TrayManager
  - [x] 6.1 实现 TrayManager 核心功能
    - 在 `com.lifepilot.interaction.tray` 包下创建 `TrayManager`
    - `initialize()`：检查 `SystemTray.isSupported()`，不支持时记录 WARN 日志并跳过
    - 创建 `TrayIcon` 并设置图标（从 classpath 加载 `tray-icon.png`，缺失时使用占位图标）
    - 设置工具提示文本（从 TrayConfigProperties 读取）
    - 创建 `PopupMenu` 包含：查看最近提醒、打开 Web UI、暂停通知、恢复通知、退出
    - _Requirements: 5.2, 5.4, 6.1, 6.2, 6.3, 6.4, 6.5_

  - [x] 6.2 实现通知控制和生命周期方法
    - `displayNotification()`：调用 `TrayIcon.displayMessage()` 显示原生通知
    - `pauseNotifications()` / `resumeNotifications()`：切换 `notificationPaused` 标志
    - `isNotificationPaused()`：返回暂停状态
    - `shutdown()`：从 SystemTray 移除 TrayIcon 并清理资源
    - 菜单项「退出」触发 `applicationContext.close()` 优雅关闭
    - 菜单项「打开 Web UI」调用 `Desktop.browse()`，失败时显示 URL 通知
    - _Requirements: 6.2, 6.3, 6.4, 6.5, 7.4, 7.5_

  - [ ]* 6.3 编写 TrayManager 单元测试
    - 测试暂停/恢复通知状态切换
    - 测试 shutdown 清理逻辑
    - _Requirements: 7.4, 7.5_

  - [ ]* 6.4 编写暂停/恢复通知属性测试
    - **Property 5: 暂停/恢复通知往返**
    - **Validates: Requirements 7.4, 7.5**

- [x] 7. 实现 TrayNotificationChannel
  - [x] 7.1 实现 TrayNotificationChannel
    - 在 `com.lifepilot.interaction.tray` 包下创建 `TrayNotificationChannel` 实现 `NotificationChannel`
    - `id()` 返回 `"tray"`
    - `send()` 逻辑：暂停时直接返回；HIGH → `MessageType.WARNING`；MEDIUM → `MessageType.INFO`；LOW → 不发送
    - 调用 `trayManager.displayNotification()` 推送通知
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

  - [ ]* 7.2 编写 TrayNotificationChannel 单元测试
    - 测试暂停时不调用 displayMessage
    - 测试 LOW 紧急度不发送
    - 测试 urgency 到 MessageType 映射
    - _Requirements: 7.2, 7.3, 7.4_

  - [ ]* 7.3 编写 Urgency 到 MessageType 映射属性测试
    - **Property 4: Urgency 到 MessageType 映射**
    - **Validates: Requirements 7.3**

- [x] 8. Checkpoint — 托盘功能实现
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. 重构 NotificationDispatcher 多通道分发
  - [x] 9.1 重构 NotificationDispatcher 构造函数
    - 将构造函数参数从 `NotificationChannel logChannel` 改为 `List<NotificationChannel> channels`
    - `dispatch()` 中 HIGH/MEDIUM 紧急度遍历所有通道调用 `send()`
    - 单个通道 `send()` 异常时记录 WARN 日志，继续分发到其余通道
    - LOW 紧急度仅入队 `PassiveNotificationQueue`，不通过通道发送
    - _Requirements: 8.1, 8.2, 8.3_

  - [x] 9.2 调整 ProactiveAutoConfiguration
    - 将 `notificationDispatcher()` Bean 方法参数从 `LogNotificationChannel` 改为 `List<NotificationChannel>`
    - Spring 自动收集所有 `NotificationChannel` Bean
    - _Requirements: 8.1, 8.2_

  - [x] 9.3 编写 NotificationDispatcher 单元测试
    - 测试多通道分发到所有通道
    - 测试单通道异常不中断其余通道
    - 测试 LOW 紧急度仅入队
    - _Requirements: 8.2, 8.3_


- [x] 10. 集成测试
  - [x] 10.1 编写 LaunchMode Spring Context 集成测试
    - 验证 CLI 模式下 Web 服务器未启动、CliAutoConfiguration 激活
    - 验证 WEB 模式下 CliAutoConfiguration 未激活
    - 验证 TRAY 模式下 TrayAutoConfiguration 激活（Mock SystemTray）
    - 验证 FULL 模式下 CLI 和 Web 均激活
    - _Requirements: 2.1, 2.2, 3.1, 3.2, 4.1, 4.2, 5.1_

  - [x] 10.2 编写 NotificationDispatcher 多通道集成测试
    - 验证 LogNotificationChannel + Mock TrayNotificationChannel 同时注册
    - 验证 dispatch 分发到两个通道
    - _Requirements: 8.1, 8.2_

- [x] 11. Final checkpoint — 全量测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- 属性测试使用 jqwik 框架，单元测试使用 JUnit 5 + Mockito
- 托盘图标资源文件 `tray-icon.png` 需放置在 `src/main/resources/` 下
- TrayManager 在无桌面环境时自动降级，不影响其他功能
- NotificationDispatcher 重构为破坏性变更，需同步修改 ProactiveAutoConfiguration
