# Implementation Plan: Project Skeleton (Phase 0)

## Overview

搭建 LifePilot 的 Maven 项目骨架，包含 Spring Boot 启动类、SQLite 数据源配置、Flyway 迁移基础设施、模块包结构和集成测试。每个任务按依赖顺序编排，确保增量可编译。

## Tasks

- [ ] 1. 创建 Maven pom.xml 项目配置
  - 创建 `pom.xml`，声明 `spring-boot-starter-parent` 3.5.3 作为 parent
  - groupId `com.lifepilot`、artifactId `lifepilot`、version `0.1.0-SNAPSHOT`
  - 声明所有依赖：spring-boot-starter-web、spring-boot-starter-actuator、spring-boot-starter-data-jdbc、spring-ai-spring-boot-starter（1.1.2 BOM）、xerial sqlite-jdbc（3.49.1.0+）、flyway-core（10.27.0）、jline（3.28.0）、spring-boot-starter-test
  - 配置 maven-compiler-plugin（Java 22 + `--enable-preview`）、spring-boot-maven-plugin、maven-surefire-plugin（`--enable-preview`）
  - 添加 Spring Milestones 仓库
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

- [ ] 2. 创建 Spring Boot 启动类和配置文件
  - [ ] 2.1 创建 `src/main/java/com/lifepilot/LifePilotApplication.java`
    - `@SpringBootApplication` 注解，包含 `main` 方法
    - 添加 `ApplicationStartedEvent` 监听器，启动时输出应用名称和版本
    - 中文 Javadoc，含 `@author zsg` 和 `@since 2026-02-24`
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [ ] 2.2 创建 `src/main/resources/application.yml`
    - 应用名称 `lifepilot`，端口 8080
    - SQLite 数据源 URL：`jdbc:sqlite:${user.home}/.lifepilot/lifepilot.db`
    - Flyway 启用 + 迁移脚本位置 `classpath:db/migration`
    - Actuator 暴露 health 端点，show-details: always
    - 日志级别：root INFO、com.lifepilot DEBUG
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

- [ ] 3. 创建模块包结构
  - 创建 7 个模块包的 `package-info.java` 文件：
    - `com.lifepilot.agent` — Agent 引擎核心
    - `com.lifepilot.memory` — 四层认知记忆系统
    - `com.lifepilot.llm` — LLM 路由与管理
    - `com.lifepilot.skill` — 技能系统
    - `com.lifepilot.mcp` — MCP 协议支持
    - `com.lifepilot.interaction` — 交互层
    - `com.lifepilot.observability` — 可观测性
  - 每个 `package-info.java` 包含中文 Javadoc 说明模块职责
  - _Requirements: 4.1, 4.2_

- [ ] 4. 实现 SQLite DataSource 配置类
  - 创建 `src/main/java/com/lifepilot/config/DataSourceConfig.java`
  - `@Configuration` 类，`@Bean` 方法手动创建 `SQLiteDataSource`
  - 使用 `SQLiteConfig` 设置 PRAGMA：`journal_mode=WAL`、`synchronous=NORMAL`、`foreign_keys=ON`、`busy_timeout=5000`
  - 解析 JDBC URL，数据库文件目录不存在时自动创建（`Files.createDirectories`）
  - 连接初始化失败时记录中文错误日志并抛出包含中文描述的异常
  - 中文 Javadoc，含 `@author zsg` 和 `@since 2026-02-24`
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [ ] 5. 创建 Flyway 初始迁移脚本
  - 创建 `src/main/resources/db/migration/V1__init_schema.sql`
  - 创建 `schema_version_check` 表（`id TEXT PRIMARY KEY`、`created_at TEXT`）
  - 插入 `init` 验证记录
  - _Requirements: 6.1, 6.2, 6.3_

- [ ] 6. Checkpoint — 验证项目可编译
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. 创建测试配置和集成测试
  - [ ] 7.1 创建 `src/test/resources/application-test.yml`
    - 内存 SQLite：`jdbc:sqlite::memory:`
    - Flyway 启用 + 迁移脚本位置
    - 日志级别配置
    - _Requirements: 8.4_

  - [ ] 7.2 创建集成测试类 `src/test/java/com/lifepilot/LifePilotApplicationTest.java`
    - `@SpringBootTest` + `@ActiveProfiles("test")`
    - 测试方法：`应用上下文_正常加载()` — 验证 Spring 上下文加载成功
    - 测试方法：`健康检查端点_返回UP状态()` — 使用 MockMvc 验证 GET `/actuator/health` 返回 200 + `UP` 状态
    - 测试方法：`Flyway迁移_成功执行()` — 通过 JdbcTemplate 查询 `schema_version_check` 表验证 `init` 记录存在
    - 测试方法：`SQLite连接_PRAGMA设置正确()` — 从 DataSource 获取连接，验证 4 个 PRAGMA 值
    - 中文测试方法名，中文 Javadoc
    - _Requirements: 8.1, 8.2, 8.3_
    - **Property 1: SQLite PRAGMA 一致性** — _Validates: Requirements 5.1, 5.2_
    - **Property 2: Flyway 迁移幂等性** — _Validates: Requirements 6.1, 6.3_
    - **Property 3: 健康检查端点可用性** — _Validates: Requirements 7.1, 7.2, 7.3_

- [ ] 8. Final Checkpoint — 验证所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- 所有代码遵循编码规范：中文注释、Javadoc、日志、异常消息
- 类级别 Javadoc 必须包含 `@author zsg` 和 `@since 2026-02-24`
- 测试方法名使用中文
- 每个任务/子任务完成后独立 git commit，遵循 git-workflow 规范
- Phase 0 不包含 property-based testing（jqwik），仅使用 JUnit 5
- Checkpoints 确保增量验证，避免问题累积
