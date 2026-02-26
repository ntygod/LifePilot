---
inclusion: always
---

# LifePilot 编码规范

---

## 1. 语言要求

### 中文优先

| 场景 | 语言 | 示例 |
|------|------|------|
| 代码注释 | 中文 | `// 初始化不可变状态` |
| Javadoc | 中文 | 见下方 Javadoc 模板 |
| 日志消息 | 中文 | `log.info("Skill 注册成功: id={}", id)` |
| 异常消息 | 中文 | `throw new SkillActivationException("Skill 激活深度超过限制")` |
| 测试方法名 | 中文 | `void stepCount_单调递增()` |
| 提交消息 | 中文 | `feat(memory): 实现记忆巩固管线` |

### Javadoc 模板

类级别 Javadoc 必须包含 `@author` 和 `@since`：

```java
/**
 * Agent 不可变状态快照。
 *
 * @author zsg
 * @since 2026-02-24
 */
public record AgentState(...) {}
```

- `@author`：固定为 `zsg`
- `@since`：填写文件创建日期，格式 `yyyy-MM-dd`
- 方法级 Javadoc 不需要 `@author` 和 `@since`，只需中文描述 + 参数/返回值说明

### 英文使用场景

类名、接口名、方法名、变量名、常量名、包名、配置键、REST 路径、YAML Skill ID 使用英文。

---

## 2. 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 22 | Record / Sealed / Pattern Matching / Virtual Thread |
| Spring Boot | 3.5.x | Web 框架、自动配置、Actuator |
| Spring AI | 1.1.2 | AI 原生集成、Advisor 模式、MCP 支持、结构化输出 |
| Maven | 3.9.x | 构建、依赖管理 |
| SQLite (xerial sqlite-jdbc) | 3.51+ | 结构化存储 + FTS5 全文索引，native library 内嵌于 JAR |
| sqlite-vec | 0.1.x | 向量索引，native 扩展打包进 JAR 启动时加载 |
| JUnit 5 | 5.11+ | 单元测试 + 集成测试 |
| JLine 3 | 3.28+ | CLI 补全、高亮、历史记录 |
| Vue 3 + Vite + Pinia | 3.5 / 6.x / 3.x | 前端 SPA（独立项目 lifepilot-web，独立构建部署） |
| Flyway | 10.x | 数据库迁移（社区版，支持 SQLite） |

---

## 3. 打包与部署

- 后端单 JAR：后端 + SQLite native + sqlite-vec native（不含前端静态资源）
- 前端独立项目（`lifepilot-web`）：独立构建（`npm run build`）、独立部署（Nginx / 静态服务器）
- sqlite-jdbc：xerial 已内嵌各平台 native library（Windows / macOS / Linux），无需用户安装
- sqlite-vec：各平台 native 扩展（.dll / .so / .dylib）打包进 JAR resources，启动时解压到临时目录加载
- 后端启动：`java -jar lifepilot.jar`（纯 REST/SSE API 服务）
- 前端启动：`npm run dev`（开发）或 `npm run build` + 静态部署（生产）

---

## 4. Java 22 编码约定

- 数据载体优先使用 `record`
- 类型层次使用 `sealed interface` + `switch` 表达式穷举匹配
- 条件判断使用 Pattern Matching（`instanceof` 模式匹配、record 解构）
- I/O 密集型任务使用 Virtual Thread
- 状态对象使用 `record` + `@Builder(toBuilder = true)`，每次转换生成新实例
- 集合返回值使用 `List.copyOf()` / `Map.copyOf()`
- 可空字段标注 `@Nullable`，返回值优先 `Optional<T>`，禁止集合为 null
- 运行时注册表用 `ConcurrentHashMap`，异步用 `CompletableFuture`，避免 `synchronized`

---

## 5. 命名规范

| 类别 | 规范 | 示例 |
|------|------|------|
| 类名 / 接口名 | UpperCamelCase（接口无 I 前缀） | `AgentLoop`, `ToolContract` |
| 方法名 | lowerCamelCase | `reduce()`, `assemble()` |
| 常量 / 枚举值 | UPPER_SNAKE_CASE | `MAX_STEPS`, `HALF_OPEN` |
| 包名 | 全小写 | `com.lifepilot.agent` |
| 配置键 | kebab-case | `lifepilot.llm.providers` |
| REST 路径 | kebab-case | `/api/knowledge-bases/{id}` |
| 数据库表名 / 列名 | snake_case | `temporal_entities`, `created_at` |

---

## 6. 异常与重试

- 分层处理：LLM 层用熔断器 + 故障转移，工具层重试 + 回传 LLM，记忆层降级跳过
- 重试策略：指数退避（初始 500ms，倍数 2.0，上限 5s），最多 2 次
- 降级原则：LLM 不可用时，不依赖 LLM 的功能继续正常工作

---

## 7. 日志

- 使用参数化日志：`log.info("消息: key={}", value)`，禁止字符串拼接
- 敏感数据必须通过 `DataRedactor.redact()` 脱敏后再记录
- 级别：ERROR（不可恢复）、WARN（可恢复需关注）、INFO（关键业务事件）、DEBUG（调试）

---

## 8. 测试

- 框架：JUnit 5
- 测试方法名使用中文：`void stepCount_单调递增()`
- 单元测试不依赖外部服务，Mock LLM / MCP / 网络
- 集成测试使用 `@SpringBootTest` + 内存 SQLite
- 集成测试重点：模块间协作、端到端流程、数据库交互、Spring 上下文加载

---

## 9. 数据库

- SQLite PRAGMA：`journal_mode=WAL`, `synchronous=NORMAL`, `foreign_keys=ON`, `busy_timeout=5000`
- 主键 `TEXT` 存 UUID，时间 `TEXT` 存 ISO 8601，布尔 `INTEGER`(0/1)，JSON 用 `TEXT` + `_json` 后缀
- 所有表必须有 `created_at`，可变表必须有 `updated_at`
- 使用 Flyway 管理迁移，脚本命名 `V{版本号}__{描述}.sql`

---

## 10. 安全

- 安全策略以代码定义（`GuardrailPolicy`），在 LLM 之外强制执行
- 工具风险分级：LOW（自动）→ MEDIUM（自动+审计）→ HIGH（用户确认）→ CRITICAL（确认+二次验证）
- API 密钥通过环境变量注入，禁止硬编码或提交到版本控制
- 云端 LLM 调用前通过 `DataRedactor` 自动脱敏

---

## 11. Git

提交消息格式：`<type>(<scope>): <中文描述>`

- 类型：feat / fix / refactor / test / docs / chore
- 范围：agent / memory / llm / skill / mcp / interaction / observability / knowledge / workflow / sync
- 分支：`main`（发布）、`develop`（集成）、`feature/{name}`、`bugfix/{name}`


---

## 12. 配置外部化

### 原则

业务可调参数禁止硬编码在 Java 源码中，必须通过 `@ConfigurationProperties` + `application.yml` 外部化。纯技术常量（如数学常数、协议版本号、不可变枚举映射）允许保留为 `static final`。

### 判断标准

| 类别 | 是否外部化 | 示例 |
|------|-----------|------|
| 业务可调参数 | 必须外部化 | 超时时间、比例阈值、最大长度限制、搜索返回数量 |
| 纯技术常量 | 允许保留 | `Math.PI`、HTTP 状态码、日志格式模板 |

### 实现模式

1. 在对应模块的 `@ConfigurationProperties` 类中新增字段，设置默认值等于原硬编码值
2. 使用嵌套静态内部类组织相关配置（如 `MemoryProperties.TokenBudget`）
3. 配置键使用 kebab-case（如 `lifepilot.memory.token-budget.system-prompt-ratio`）
4. 在 `application.yml` 中显式声明所有配置项及默认值
5. 消费方通过构造函数注入 Properties 类，不直接使用 `@Value`

### 合规示例

```java
// ✅ 正确：从配置读取
public class TokenBudgetAllocator {
    private final MemoryProperties.TokenBudget budget;

    public TokenBudgetAllocator(MemoryProperties properties) {
        this.budget = properties.getTokenBudget();
    }

    public int calcSystemBudget(int windowSize) {
        return Math.round(windowSize * budget.getSystemPromptRatio());
    }
}
```

### 违规示例

```java
// ❌ 错误：硬编码业务可调参数
public class TokenBudgetAllocator {
    private static final float SYSTEM_PROMPT_RATIO = 0.10f;

    public int calcSystemBudget(int windowSize) {
        return Math.round(windowSize * SYSTEM_PROMPT_RATIO);
    }
}
```

---

## 13. 新项目无需向后兼容

### 原则

LifePilot 是全新项目（greenfield），尚无外部消费者或生产数据。因此所有开发任务无需考虑向后兼容性。

### 具体规则

- `sealed interface` 的 `permits` 列表可自由增删，无需担心已有 `switch` 穷举
- `record` 字段可自由增删改，无需保留旧构造函数或提供默认值
- 方法签名可自由修改，无需保留旧重载
- Flyway 迁移脚本无需与旧版 schema 前向兼容
- 配置键可自由重命名，无需保留旧键别名
- Spec 规划和 design 文档中不需要「兼容性」列或兼容性分析
