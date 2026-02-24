# Requirements Document

## Introduction

本文档定义 LifePilot 项目骨架（Phase 0）的需求。Phase 0 是所有后续模块的基础，目标是搭建完整的 Maven 项目结构、Spring Boot 启动类、基础配置、包结构、数据库基础设施和健康检查端点，使后续模块开发可以在此骨架上直接开始。

参考文档：
- 架构设计：docs/ARCHITECTURE.md
- 编码规范：.kiro/steering/coding-standards.md
- Spec 规划规范：.kiro/steering/spec-workflow.md
- Git 工作流规范：.kiro/steering/git-workflow.md

## Glossary

- **Project_Skeleton**: LifePilot 的 Maven 项目骨架，包含 pom.xml、包结构、启动类和基础配置
- **LifePilot_Application**: Spring Boot 启动类，作为应用程序入口
- **Application_Config**: application.yml 配置文件，定义应用运行时参数
- **Data_Source**: SQLite 数据源配置，提供结构化存储能力
- **Flyway_Migration**: 基于 Flyway 的数据库版本迁移基础设施
- **Health_Endpoint**: Spring Boot Actuator 健康检查端点，用于验证应用运行状态
- **Package_Structure**: com.lifepilot 下的模块包结构，为后续模块开发预留占位

## Requirements

### Requirement 1: Maven 项目结构

**User Story:** As a 开发者, I want 一个完整配置的 Maven 项目结构, so that 所有依赖和构建配置就绪，后续模块可以直接开始开发。

#### Acceptance Criteria

1. THE Project_Skeleton SHALL 包含一个 pom.xml 文件，声明 Spring Boot 3.5.x 作为 parent
2. THE Project_Skeleton SHALL 在 pom.xml 中声明以下依赖：spring-boot-starter-web、spring-boot-starter-actuator、spring-boot-starter-data-jdbc、spring-ai-spring-boot-starter（1.1.2）、xerial sqlite-jdbc（3.51+）、flyway-core（10.x）、jline（3.28+）、junit-jupiter（5.11+）、spring-boot-starter-test
3. THE Project_Skeleton SHALL 配置 Maven Compiler Plugin 使用 Java 22 作为源码和目标版本
4. THE Project_Skeleton SHALL 配置 spring-boot-maven-plugin 生成可执行 JAR
5. THE Project_Skeleton SHALL 配置 maven-surefire-plugin 支持 JUnit 5 测试执行
6. THE Project_Skeleton SHALL 使用 groupId `com.lifepilot`、artifactId `lifepilot`

### Requirement 2: Spring Boot 启动类

**User Story:** As a 开发者, I want 一个 Spring Boot 启动类, so that 应用可以通过 `java -jar lifepilot.jar` 一键启动。

#### Acceptance Criteria

1. THE LifePilot_Application SHALL 位于 `com.lifepilot` 包下，使用 `@SpringBootApplication` 注解
2. THE LifePilot_Application SHALL 包含 `main` 方法作为应用入口
3. THE LifePilot_Application SHALL 包含符合编码规范的中文 Javadoc（含 @author zsg 和 @since 日期）
4. WHEN LifePilot_Application 启动时, THE LifePilot_Application SHALL 在日志中输出应用名称和版本信息

### Requirement 3: 应用配置

**User Story:** As a 开发者, I want 一个结构化的 application.yml 配置文件, so that 应用运行参数集中管理且易于扩展。

#### Acceptance Criteria

1. THE Application_Config SHALL 定义应用名称为 `lifepilot`
2. THE Application_Config SHALL 定义服务端口为 8080
3. THE Application_Config SHALL 配置 SQLite 数据源连接，数据库文件路径为 `~/.lifepilot/lifepilot.db`
4. THE Application_Config SHALL 配置 Flyway 启用状态和迁移脚本位置
5. THE Application_Config SHALL 配置 Spring Boot Actuator 暴露 health 端点
6. THE Application_Config SHALL 配置日志级别：root 为 INFO，com.lifepilot 为 DEBUG

### Requirement 4: 包结构

**User Story:** As a 开发者, I want 预定义的模块包结构, so that 后续模块开发有明确的代码组织位置。

#### Acceptance Criteria

1. THE Package_Structure SHALL 包含以下包：`com.lifepilot.agent`、`com.lifepilot.memory`、`com.lifepilot.llm`、`com.lifepilot.skill`、`com.lifepilot.mcp`、`com.lifepilot.interaction`、`com.lifepilot.observability`
2. THE Package_Structure SHALL 在每个包下放置 `package-info.java` 文件，包含中文包级 Javadoc 说明该模块的职责

### Requirement 5: SQLite 数据源配置

**User Story:** As a 开发者, I want SQLite 数据源正确配置并可用, so that 后续模块可以直接使用数据库存储。

#### Acceptance Criteria

1. THE Data_Source SHALL 配置 SQLite JDBC 连接，使用 WAL 日志模式
2. THE Data_Source SHALL 在连接初始化时设置以下 PRAGMA：`journal_mode=WAL`、`synchronous=NORMAL`、`foreign_keys=ON`、`busy_timeout=5000`
3. WHEN 数据库文件所在目录不存在时, THE Data_Source SHALL 自动创建该目录
4. IF SQLite 连接初始化失败, THEN THE Data_Source SHALL 记录错误日志并抛出包含中文描述的异常

### Requirement 6: Flyway 数据库迁移基础

**User Story:** As a 开发者, I want Flyway 迁移基础设施就绪, so that 后续模块可以通过迁移脚本管理数据库 Schema 变更。

#### Acceptance Criteria

1. THE Flyway_Migration SHALL 在应用启动时自动执行数据库迁移
2. THE Flyway_Migration SHALL 从 `classpath:db/migration` 目录加载迁移脚本
3. THE Flyway_Migration SHALL 包含一个初始迁移脚本 `V1__init_schema.sql`，创建 `schema_version_check` 表用于验证迁移基础设施正常工作
4. WHEN 迁移脚本执行失败时, THE Flyway_Migration SHALL 记录错误日志并阻止应用启动

### Requirement 7: 健康检查端点

**User Story:** As a 开发者, I want 一个健康检查端点, so that 可以验证应用运行状态和数据库连接状态。

#### Acceptance Criteria

1. THE Health_Endpoint SHALL 通过 `/actuator/health` 路径提供 HTTP GET 访问
2. THE Health_Endpoint SHALL 返回 JSON 格式的健康状态信息，包含应用状态
3. THE Health_Endpoint SHALL 包含数据库连接健康检查，验证 SQLite 连接可用
4. WHEN 数据库连接不可用时, THE Health_Endpoint SHALL 返回 DOWN 状态并包含错误描述

### Requirement 8: 应用启动验证测试

**User Story:** As a 开发者, I want 基础的集成测试, so that 可以验证项目骨架配置正确、应用可以正常启动。

#### Acceptance Criteria

1. THE Project_Skeleton SHALL 包含一个 Spring Boot 集成测试，验证应用上下文可以正常加载
2. THE Project_Skeleton SHALL 包含一个集成测试，验证健康检查端点返回 UP 状态
3. THE Project_Skeleton SHALL 包含一个集成测试，验证 Flyway 迁移成功执行
4. THE Project_Skeleton SHALL 使用内存 SQLite 数据库运行集成测试，测试配置文件为 `application-test.yml`
