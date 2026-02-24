# 工具系统设计文档

参考文档：
- 架构设计：#[[file:docs/architecture/tool-ecosystem.md]]
- 特性设计：#[[file:docs/features/tool-ecosystem.md]]
- 编码规范：#[[file:.kiro/steering/coding-standards.md]]
- Agent 引擎：#[[file:src/main/java/com/lifepilot/agent/AgentToolProvider.java]]

---

## 1. 范围

本 spec 实现工具系统核心层，包括：
- `com.lifepilot.tool` — ToolContract sealed interface 及三种实现（BuiltinTool / YamlTool / McpTool）
- `com.lifepilot.tool.model` — ToolLayer, ToolBudget, ToolInput, ToolResult, ToolResultMeta, ValidationResult, RiskLevel（复用 agent 模块已有的 RiskLevel 或新建 tool 模块的）
- `com.lifepilot.tool.registry` — DynamicToolRegistry, BuiltinToolRegistrar
- `com.lifepilot.tool.event` — ToolRegistryEvent sealed interface
- `com.lifepilot.tool.pipeline` — ToolExecutionPipeline
- `com.lifepilot.tool.schema` — JsonSchema（轻量 JSON Schema 校验）
- `com.lifepilot.guardrail` — GuardrailPolicy, GuardrailResult
- `com.lifepilot.tool.config` — ToolAutoConfiguration, ToolConfigProperties
- AgentToolProvider 桥接实现

不包含：MCP 协议集成（Phase 1 Item 4）、YAML 声明式工具加载、Shell 沙箱、IdempotencyManager 持久化（简化为内存缓存）。

---

## 2. 包结构

```
com.lifepilot.tool/
├── ToolContract.java          # sealed interface
├── BuiltinTool.java           # Layer 3 Java 原生工具
├── YamlTool.java              # Layer 2 YAML 声明式工具（骨架，execute 抛 UnsupportedOperationException）
├── McpTool.java               # Layer 1 MCP 外部工具（骨架，execute 抛 UnsupportedOperationException）
├── ToolExecutor.java          # 函数式接口
├── model/
│   ├── ToolLayer.java         # 三层枚举
│   ├── ToolBudget.java        # 三维预算 record
│   ├── ToolInput.java         # 类型安全输入 record
│   ├── ToolResult.java        # 结构化结果 record
│   ├── ToolResultMeta.java    # 执行元信息 record
│   ├── RiskLevel.java         # 风险等级枚举（工具模块自有）
│   ├── ValidationResult.java  # sealed interface 校验结果
│   └── ValidationError.java   # 校验错误 record
├── schema/
│   └── JsonSchema.java        # 轻量 JSON Schema 校验
├── registry/
│   ├── DynamicToolRegistry.java
│   └── BuiltinToolRegistrar.java
├── event/
│   ├── ToolRegistryEvent.java # sealed interface
│   ├── ToolsRegistered.java
│   ├── ToolsUnregistered.java
│   └── ToolConflictDetected.java
├── pipeline/
│   ├── ToolExecutionPipeline.java
│   └── IdempotencyManager.java  # 内存版幂等管理
├── config/
│   ├── ToolAutoConfiguration.java
│   └── ToolConfigProperties.java
└── package-info.java

com.lifepilot.guardrail/
├── GuardrailPolicy.java
├── GuardrailResult.java
└── package-info.java

com.lifepilot.tool.bridge/
└── ToolBridgeAgentToolProvider.java  # AgentToolProvider 实现
```

---

## 3. 核心设计决策

### 3.1 RiskLevel 独立于 agent 模块
agent 模块已有 `com.lifepilot.agent.model.RiskLevel`，但工具模块的 RiskLevel 语义更丰富（requiresConfirmation / requiresAudit / requiresSecondaryVerification），因此在 `com.lifepilot.tool.model` 下新建独立的 RiskLevel 枚举。

### 3.2 YamlTool / McpTool 为骨架实现
本 spec 只定义 sealed interface 的 permits 类型，YamlTool.execute() 和 McpTool.execute() 抛出 UnsupportedOperationException，在后续 spec 中实现。

### 3.3 JsonSchema 轻量实现
不引入第三方 JSON Schema 库，使用 Map<String, Object> 表示 Schema，校验逻辑仅覆盖 required 字段检查和基础类型检查（string/integer/number/boolean）。

### 3.4 IdempotencyManager 内存版
本 spec 使用 ConcurrentHashMap 实现内存级幂等缓存，不持久化到 SQLite。持久化在后续 spec 中实现。

### 3.5 UserConfirmationService 占位接口
HIGH/CRITICAL 风险工具需要用户确认，但交互层（CLI/Web）尚未实现。本 spec 定义接口 + NoOp 实现（默认自动确认），在交互层 spec 中替换。

### 3.6 ToolAutoConfiguration 条件装配
通过 `lifepilot.tool.enabled=true`（默认）控制工具系统启用。ToolAutoConfiguration 注册所有工具系统 Bean，使用 `@ConditionalOnMissingBean` 允许覆盖。

### 3.7 ToolBridgeAgentToolProvider 覆盖 NoOp
工具系统的 ToolBridgeAgentToolProvider 实现 AgentToolProvider 接口，通过 Spring 自动配置优先级覆盖 agent 模块的 NoOpAgentToolProvider。

---

## 4. 关键接口

### 4.1 ToolContract sealed interface
```java
public sealed interface ToolContract permits BuiltinTool, YamlTool, McpTool {
    String id();
    String name();
    String description();
    JsonSchema inputSchema();
    JsonSchema outputSchema();
    RiskLevel riskLevel();
    boolean idempotent();
    ToolBudget budget();
    ToolLayer layer();
    List<String> tags();
    default boolean exportable() { return false; }
    ToolResult execute(ToolInput input);
}
```

### 4.2 DynamicToolRegistry 核心方法
```java
public class DynamicToolRegistry {
    void registerBuiltinTool(ToolContract tool);
    void registerYamlTools(List<ToolContract> tools);
    void registerMcpTools(String serverName, List<ToolContract> tools);
    void unregisterMcpTools(String serverName);
    void unregisterYamlTools();
    Optional<ToolContract> resolve(String toolId);
    List<ToolContract> getToolSnapshot();
    List<ToolContract> getToolsByLayer(ToolLayer layer);
    int getToolCount();
}
```

### 4.3 ToolExecutionPipeline 主入口
```java
public class ToolExecutionPipeline {
    ToolResult execute(String toolId, Map<String, Object> parameters,
                       String traceId, @Nullable String idempotencyKey);
}
```

### 4.4 GuardrailPolicy 检查
```java
public class GuardrailPolicy {
    GuardrailResult checkToolCall(ToolContract tool, ToolInput input);
    void addAllowedTools(List<String> toolIds);
    void removeAllowedTools(List<String> toolIds);
}
```

---

## 5. 配置

```yaml
lifepilot:
  tool:
    enabled: true
    pipeline:
      default-timeout-seconds: 30
      default-max-retries: 2
      retry-initial-delay-ms: 500
      retry-multiplier: 2.0
      retry-max-delay-ms: 5000
```

---

## 6. 数据库迁移

本 spec 不新增数据库表。IdempotencyManager 使用内存缓存，tool_executions 表在后续 spec 中创建。

---

## 7. 测试策略

- DynamicToolRegistry：优先级覆盖、冲突解析、快照不可变性
- ToolExecutionPipeline：参数校验失败、护栏拦截、超时控制、重试逻辑
- GuardrailPolicy：白名单/黑名单、风险等级审批
- ToolBridgeAgentToolProvider：ToolContract → ToolCallback 转换
