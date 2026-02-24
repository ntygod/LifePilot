# Design Document: Project Skeleton (Phase 0)

## Overview

本设计文档定义 LifePilot 项目骨架的实现方案。Phase 0 的目标是搭建一个可编译、可启动、可测试的 Spring Boot 项目，为后续所有模块提供统一的基础设施。

核心交付物：
- Maven pom.xml（依赖 + 插件配置）
- Spring Boot 启动类
- SQLite DataSource 配置类（PRAGMA 设置）
- application.yml / application-test.yml
- Flyway 初始迁移脚本
- 模块包结构 + package-info.java
- 集成测试套件

参考文档：
- 架构设计：docs/ARCHITECTURE.md
- 编码规范：.kiro/steering/coding-standards.md

## Architecture

### 项目整体结构

```
lifepilot/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/lifepilot/
│   │   │   ├── LifePilotApplication.java          # Spring Boot 启动类
│   │   │   ├── config/
│   │   │   │   └── DataSourceConfig.java           # SQLite DataSource 配置
│   │   │   ├── agent/
│   │   │   │   └── package-info.java
│   │   │   ├── memory/
│   │   │   │   └── package-info.java
│   │   │   ├── llm/
│   │   │   │   └── package-info.java
│   │   │   ├── skill/
│   │   │   │   └── package-info.java
│   │   │   ├── mcp/
│   │   │   │   └── package-info.java
│   │   │   ├── interaction/
│   │   │   │   └── package-info.java
│   │   │   └── observability/
│   │   │       └── package-info.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   │           └── V1__init_schema.sql
│   └── test/
│       ├── java/com/lifepilot/
│       │   └── LifePilotApplicationTest.java       # 集成测试
│       └── resources/
│           └── application-test.yml
└── .gitignore
```

### 设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| DataSource 配置方式 | `@Configuration` + `@Bean` 手动创建 | 需要在连接初始化时执行 PRAGMA 语句，Spring Boot 自动配置无法满足 |
| PRAGMA 设置时机 | 连接初始化回调 | 确保每个连接都设置了正确的 PRAGMA，使用 `SQLiteConfig` 在连接创建时设置 |
| 测试数据库 | 内存 SQLite (`:memory:`) | 测试隔离，无文件系统副作用，速度快 |
| Flyway 与 DataSource 集成 | 使用 Spring Boot 自动配置 | Flyway 自动检测 DataSource Bean 并执行迁移 |
| 目录自动创建 | DataSource 配置类中处理 | 在创建 DataSource 之前检查并创建 `~/.lifepilot/` 目录 |

## Components and Interfaces

### 1. LifePilotApplication（启动类）

```java
package com.lifepilot;

@SpringBootApplication
public class LifePilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(LifePilotApplication.class, args);
    }
}
```

职责：
- 应用入口，触发 Spring Boot 自动配置
- 启动时通过 `ApplicationStartedEvent` 监听器输出应用名称和版本

### 2. DataSourceConfig（数据源配置类）

```java
package com.lifepilot.config;

@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String url) {
        // 1. 解析数据库文件路径，自动创建目录
        // 2. 创建 SQLiteConfig，设置 PRAGMA
        // 3. 构建 SQLiteDataSource 并返回
    }
}
```

职责：
- 解析 JDBC URL，提取数据库文件路径
- 自动创建数据库文件所在目录
- 设置 SQLite PRAGMA：`journal_mode=WAL`、`synchronous=NORMAL`、`foreign_keys=ON`、`busy_timeout=5000`
- 连接失败时抛出包含中文描述的异常

PRAGMA 设置方案：使用 `org.sqlite.SQLiteConfig` 在 `SQLiteDataSource` 上设置属性，确保每个连接自动应用 PRAGMA。

```java
var config = new SQLiteConfig();
config.setJournalMode(SQLiteConfig.JournalMode.WAL);
config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
config.enforceForeignKeys(true);
config.setBusyTimeout(5000);

var dataSource = new SQLiteDataSource(config);
dataSource.setUrl(url);
```

## Data Models

### Flyway 初始迁移脚本 V1__init_schema.sql

```sql
-- V1__init_schema.sql
-- 初始 Schema：验证 Flyway 迁移基础设施正常工作

CREATE TABLE IF NOT EXISTS schema_version_check (
    id         TEXT PRIMARY KEY,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

INSERT INTO schema_version_check (id, created_at)
VALUES ('init', datetime('now'));
```

此表仅用于验证 Flyway 迁移基础设施正常工作，后续模块会添加各自的迁移脚本。

### application.yml 配置

```yaml
spring:
  application:
    name: lifepilot
  datasource:
    url: jdbc:sqlite:${user.home}/.lifepilot/lifepilot.db
    driver-class-name: org.sqlite.JDBC
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: always

logging:
  level:
    root: INFO
    com.lifepilot: DEBUG
```

### application-test.yml 配置

```yaml
spring:
  datasource:
    url: jdbc:sqlite::memory:
  flyway:
    enabled: true
    locations: classpath:db/migration

logging:
  level:
    root: INFO
    com.lifepilot: DEBUG
```

测试配置使用内存 SQLite，覆盖生产配置中的文件路径。


### pom.xml 配置

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.3</version>
        <relativePath/>
    </parent>

    <groupId>com.lifepilot</groupId>
    <artifactId>lifepilot</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>LifePilot</name>
    <description>本地运行的个人 AI Agent 助手</description>

    <properties>
        <java.version>22</java.version>
        <spring-ai.version>1.1.2</spring-ai.version>
        <sqlite-jdbc.version>3.49.1.0</sqlite-jdbc.version>
        <flyway.version>10.27.0</flyway.version>
        <jline.version>3.28.0</jline.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Spring Boot -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jdbc</artifactId>
        </dependency>

        <!-- Spring AI -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-spring-boot-starter</artifactId>
        </dependency>

        <!-- SQLite -->
        <dependency>
            <groupId>org.xerial</groupId>
            <artifactId>sqlite-jdbc</artifactId>
            <version>${sqlite-jdbc.version}</version>
        </dependency>

        <!-- Flyway -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
            <version>${flyway.version}</version>
        </dependency>

        <!-- JLine -->
        <dependency>
            <groupId>org.jline</groupId>
            <artifactId>jline</artifactId>
            <version>${jline.version}</version>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <source>22</source>
                    <target>22</target>
                    <compilerArgs>
                        <arg>--enable-preview</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <argLine>--enable-preview</argLine>
                </configuration>
            </plugin>
        </plugins>
    </build>

    <repositories>
        <repository>
            <id>spring-milestones</id>
            <name>Spring Milestones</name>
            <url>https://repo.spring.io/milestone</url>
            <snapshots>
                <enabled>false</enabled>
            </snapshots>
        </repository>
    </repositories>
</project>
```

关键配置说明：
- `spring-boot-starter-parent` 3.5.3 作为 parent，统一管理 Spring Boot 依赖版本
- Spring AI 通过 BOM 管理版本，避免版本冲突
- `--enable-preview` 启用 Java 22 预览特性（如 unnamed patterns 等）
- `maven-surefire-plugin` 需要传递 `--enable-preview` 给测试 JVM
- `spring-milestones` 仓库用于获取 Spring AI 依赖

### 包结构 package-info.java

每个模块包下放置 `package-info.java`，包含中文 Javadoc 说明模块职责：

| 包 | 职责说明 |
|---|---------|
| `com.lifepilot.agent` | Agent 引擎核心：状态化控制循环、状态机、上下文工程 |
| `com.lifepilot.memory` | 四层认知记忆系统：工作记忆、情景记忆、语义记忆、程序记忆 |
| `com.lifepilot.llm` | LLM 路由与管理：多模型路由、熔断器、Provider 适配 |
| `com.lifepilot.skill` | 技能系统：Skill 定义、注册、生命周期管理 |
| `com.lifepilot.mcp` | MCP 协议支持：MCP Client、工具适配器、动态工具注册 |
| `com.lifepilot.interaction` | 交互层：CLI、Web UI、通道适配器 |
| `com.lifepilot.observability` | 可观测性：Trace 记录、护栏引擎、数据脱敏 |

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

Phase 0 项目骨架主要是配置和基础设施搭建，大部分验收标准是静态配置检查或特定集成行为验证，适合用 example-based 测试。经过 prework 分析，识别出以下可测试属性：

### Property 1: SQLite PRAGMA 一致性

*For any* connection obtained from the DataSource, querying `PRAGMA journal_mode` 应返回 `wal`，`PRAGMA synchronous` 应返回 `1`（NORMAL），`PRAGMA foreign_keys` 应返回 `1`（ON），`PRAGMA busy_timeout` 应返回 `5000`。

**Validates: Requirements 5.1, 5.2**

### Property 2: Flyway 迁移幂等性

*For any* 应用启动序列，Flyway 迁移执行后 `schema_version_check` 表应存在且包含 `init` 记录，且重复启动不会导致迁移失败。

**Validates: Requirements 6.1, 6.3**

### Property 3: 健康检查端点可用性

*For any* 应用正常运行状态下的 HTTP GET `/actuator/health` 请求，响应应为 JSON 格式、HTTP 200、包含 `status: UP` 且包含 `db` 组件状态。

**Validates: Requirements 7.1, 7.2, 7.3**

> 注：由于项目骨架以配置和基础设施为主，大部分验收标准（Req 1.x、2.x、3.x、4.x）通过项目能否编译和 Spring 上下文能否加载来隐式验证，不需要独立的属性测试。Property 1 是唯一适合 property-based testing 的属性（多次获取连接验证 PRAGMA 一致性），Property 2 和 3 更适合 example-based 集成测试。

## Error Handling

### DataSource 初始化错误

| 场景 | 处理方式 |
|------|---------|
| 数据库文件目录不存在 | 自动创建目录（`Files.createDirectories`），创建失败则抛出异常并记录中文错误日志 |
| SQLite 连接失败 | 抛出 `DataSourceInitializationException`，包含中文描述（如"SQLite 数据源初始化失败"），记录 ERROR 日志 |
| PRAGMA 设置失败 | 通过 `SQLiteConfig` 在连接创建时设置，失败会在连接获取时抛出 `SQLException` |

### Flyway 迁移错误

| 场景 | 处理方式 |
|------|---------|
| 迁移脚本语法错误 | Flyway 自动回滚该脚本，记录 ERROR 日志，阻止应用启动（Spring Boot 默认行为） |
| 迁移版本冲突 | Flyway 抛出 `FlywayException`，阻止应用启动 |

### 健康检查错误

| 场景 | 处理方式 |
|------|---------|
| 数据库连接不可用 | Spring Boot Actuator 自动检测，返回 `{"status": "DOWN"}` 并包含错误描述 |

## Testing Strategy

### 测试框架

- JUnit 5（单元测试 + 集成测试）
- Spring Boot Test（`@SpringBootTest` + `MockMvc`）
- 内存 SQLite（`:memory:`）用于测试隔离

### 测试方法命名

遵循编码规范，测试方法名使用中文：

```java
@Test
void 应用上下文_正常加载() { ... }

@Test
void 健康检查端点_返回UP状态() { ... }

@Test
void Flyway迁移_成功执行() { ... }

@Test
void SQLite连接_PRAGMA设置正确() { ... }
```

### 集成测试方案

所有集成测试使用 `@SpringBootTest` + `@ActiveProfiles("test")`，加载 `application-test.yml` 配置。

| 测试 | 验证内容 | 对应需求 |
|------|---------|---------|
| 应用上下文加载测试 | Spring 上下文正常加载，所有 Bean 正确注入 | Req 1.x, 2.x, 3.x |
| 健康检查端点测试 | GET `/actuator/health` 返回 200 + `UP` 状态 + db 组件 | Req 7.1, 7.2, 7.3 |
| Flyway 迁移测试 | `schema_version_check` 表存在且包含 `init` 记录 | Req 6.1, 6.3 |
| PRAGMA 验证测试 | 从 DataSource 获取连接，验证 4 个 PRAGMA 值 | Req 5.1, 5.2 |

### Property-Based Testing

由于 Phase 0 主要是配置和基础设施，property-based testing 的适用场景有限。Property 1（PRAGMA 一致性）可以通过多次获取连接来验证，但在项目骨架阶段使用标准 JUnit 5 单元测试即可充分覆盖。

后续模块（如 Agent 引擎的 StateReducer、记忆系统的序列化/反序列化）将大量使用 property-based testing。

### 测试配置

- 测试 profile：`test`
- 数据库：内存 SQLite（`jdbc:sqlite::memory:`）
- 配置文件：`src/test/resources/application-test.yml`
- 测试类位于 `src/test/java/com/lifepilot/` 下
