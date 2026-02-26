# Design Document: CLI Commands Enhancement

## Overview

本设计扩展 `QuickCommand` 类和 `CliCompleter` 类，补齐架构文档 §8 规划的完整子命令集。变更集中在两个文件：

1. **QuickCommand.java** — 在现有 `handleBuiltinTool`、`handleLlm`、`handleMcp`、`handleSkill` 方法中新增 switch 分支，实现 15 个新子命令
2. **CliCompleter.java** — 更新 Tab 补全树，覆盖所有新增子命令

设计原则：沿用现有代码模式（switch 分支 + ResponseRenderer 输出 + 直接调用 Registry API），不引入新类或新抽象。

参考文档：
- 架构设计：`docs/architecture/cli-interaction.md`
- 需求文档：`.kiro/specs/cli-commands-enhancement/requirements.md`

## Architecture

### 变更范围

```
src/main/java/com/lifepilot/interaction/cli/
├── QuickCommand.java      ← 主要变更：新增子命令处理逻辑
├── CliCompleter.java       ← 更新补全树
└── ResponseRenderer.java   ← 无变更
```

### 新增依赖注入

`QuickCommand` 构造函数需新增 `YamlSkillLoader` 参数，用于 `skill reload` 命令：

```java
public QuickCommand(DynamicToolRegistry toolRegistry,
                    ProviderRegistry providerRegistry,
                    McpServerRegistry mcpServerRegistry,
                    SkillRegistry skillRegistry,
                    YamlSkillLoader yamlSkillLoader) // 新增
```

### 命令路由流程

所有新增命令复用现有路由路径，无需修改 `CommandRouter`：

```
用户输入 "llm enable deepseek-main"
  → CommandRouter 匹配前缀 "llm"
  → QuickCommand.execute("llm", ["enable", "deepseek-main"], renderer)
  → handleLlm(["enable", "deepseek-main"], renderer)
  → switch "enable" 分支 → llmEnable("deepseek-main", renderer)
```

## Components and Interfaces

### 依赖接口验证

| 接口 | 源码位置 | 验证状态 |
|------|---------|---------|
| `ProviderRegistry.register(ProviderConfig)` | `com.lifepilot.llm.registry.ProviderRegistry` | ✅ 已核对 — 重复 ID 抛 IllegalArgumentException |
| `ProviderRegistry.deregister(String)` | `com.lifepilot.llm.registry.ProviderRegistry` | ✅ 已核对 — void 返回，无异常 |
| `ProviderRegistry.getConfig(String)` | `com.lifepilot.llm.registry.ProviderRegistry` | ✅ 已核对 — 返回 `Optional<ProviderConfig>` |
| `ProviderRegistry.registeredIds()` | `com.lifepilot.llm.registry.ProviderRegistry` | ✅ 已核对 — 返回 `Set<String>` |
| `McpServerRegistry.connectServer(McpServerConfig)` | `com.lifepilot.mcp.registry.McpServerRegistry` | ✅ 已核对 — void 返回，异步连接 |
| `McpServerRegistry.disconnectServer(String)` | `com.lifepilot.mcp.registry.McpServerRegistry` | ✅ 已核对 — void 返回 |
| `McpServerRegistry.getClient(String)` | `com.lifepilot.mcp.registry.McpServerRegistry` | ✅ 已核对 — 返回 `Optional<McpClient>` |
| `McpServerRegistry.getServer(String)` | `com.lifepilot.mcp.registry.McpServerRegistry` | ✅ 已核对 — 返回 `Optional<McpServerEntry>` |
| `SkillRegistry.find(String)` | `com.lifepilot.skill.registry.SkillRegistry` | ✅ 已核对 — 返回 `Optional<SkillDefinition>` |
| `YamlSkillLoader.loadAll()` | `com.lifepilot.skill.yaml.YamlSkillLoader` | ✅ 已核对 — 返回 `int`（加载数量） |
| `DynamicToolRegistry.resolve(String)` | `com.lifepilot.tool.registry.DynamicToolRegistry` | ✅ 已核对 — 返回 `Optional<ToolContract>` |


### 新增子命令详细设计

#### 1. handleBuiltinTool 扩展（todo/schedule/habit）

在现有 `handleBuiltinTool` 的 switch 中新增分支：

| 子命令 | 工具 ID | 参数 | 说明 |
|--------|---------|------|------|
| `todo done <id>` | `builtin.todo.done` | `{id: <id>}` | 标记待办完成 |
| `todo delete <id>` | `builtin.todo.delete` | `{id: <id>}` | 删除待办 |
| `schedule today` | `builtin.schedule.list` | `{date: <today>}` | 今日日程 |
| `schedule tomorrow` | `builtin.schedule.list` | `{date: <tomorrow>}` | 明日日程 |
| `schedule add <内容>` | `builtin.schedule.create` | `{content: <内容>}` | 添加日程 |
| `habit checkin <名称>` | `builtin.habit.checkin` | `{name: <名称>}` | 习惯打卡 |
| `habit status` | `builtin.habit.status` | `{}` | 习惯状态 |

`schedule today/tomorrow` 通过 `java.time.LocalDate.now()` 和 `.plusDays(1)` 计算日期，以 ISO 8601 格式传入。

由于 `handleBuiltinTool` 当前只处理 `list` 和 `add`，需要将 switch 扩展为按 domain 分派的模式。具体做法：将 `handleBuiltinTool` 中的 switch 扩展，对 `done`/`delete`/`today`/`tomorrow`/`checkin`/`status` 等子命令按 domain 条件判断路由到对应工具 ID。

```java
// handleBuiltinTool switch 扩展伪代码
case "done" -> {
    // 仅 todo 支持
    if (!"todo".equals(domain)) { renderer.error(...); return; }
    if (subArgs.length < 2) { renderer.error("缺少参数: todo done <id>"); return; }
    executeToolCommand("builtin.todo.done", Map.of("id", subArgs[1]), renderer);
}
case "delete" -> {
    // 仅 todo 支持
    if (!"todo".equals(domain)) { renderer.error(...); return; }
    if (subArgs.length < 2) { renderer.error("缺少参数: todo delete <id>"); return; }
    executeToolCommand("builtin.todo.delete", Map.of("id", subArgs[1]), renderer);
}
case "today" -> {
    if (!"schedule".equals(domain)) { renderer.error(...); return; }
    String today = LocalDate.now().toString();
    executeToolCommand("builtin.schedule.list", Map.of("date", today), renderer);
}
case "tomorrow" -> {
    if (!"schedule".equals(domain)) { renderer.error(...); return; }
    String tomorrow = LocalDate.now().plusDays(1).toString();
    executeToolCommand("builtin.schedule.list", Map.of("date", tomorrow), renderer);
}
case "checkin" -> {
    if (!"habit".equals(domain)) { renderer.error(...); return; }
    if (subArgs.length < 2) { renderer.error("缺少参数: habit checkin <习惯名>"); return; }
    String habitName = String.join(" ", Arrays.copyOfRange(subArgs, 1, subArgs.length));
    executeToolCommand("builtin.habit.checkin", Map.of("name", habitName), renderer);
}
case "status" -> {
    if (!"habit".equals(domain)) { renderer.error(...); return; }
    executeToolCommand("builtin.habit.status", Map.of(), renderer);
}
```

`printBuiltinUsage` 也需按 domain 输出不同的子命令列表。

#### 2. handleLlm 扩展

新增 `add`、`remove`、`enable`、`disable` 分支：

**llm add `<providerId>` `<type>` `<modelName>` `<apiKey>`**

```java
private void llmAdd(String[] args, ResponseRenderer renderer) {
    // args: [providerId, type, modelName, apiKey]
    if (args.length < 4) {
        renderer.error("缺少参数: llm add <providerId> <type> <modelName> <apiKey>");
        return;
    }
    ProviderType type = ProviderType.valueOf(args[1].toUpperCase().replace("-", "_"));
    var config = new ProviderConfig(
        args[0], type,
        type == ProviderType.OLLAMA ? "http://localhost:11434" : "https://api.example.com",
        args[3], args[2],
        30, 100, List.of(), Set.of(ProviderCapability.CHAT),
        true, 0, 0, 128000, null, true
    );
    providerRegistry.register(config);
    renderer.success("Provider 已添加: " + args[0]);
}
```

注意：`ProviderRegistry.register()` 在重复 ID 时抛出 `IllegalArgumentException`，由外层 `execute()` 的 catch 块统一处理。

**llm remove `<providerId>`**

```java
private void llmRemove(String providerId, ResponseRenderer renderer) {
    if (providerRegistry.getConfig(providerId).isEmpty()) {
        renderer.error("Provider 未注册: " + providerId);
        return;
    }
    providerRegistry.deregister(providerId);
    renderer.success("Provider 已移除: " + providerId);
}
```

**llm enable/disable `<providerId>`**

由于 `ProviderConfig` 是 record（不可变），enable/disable 需要：
1. 获取当前 config
2. 检查是否已处于目标状态
3. deregister 旧 config
4. 用新 enabled 值构造新 config 并 register

```java
private void llmToggle(String providerId, boolean enable, ResponseRenderer renderer) {
    var configOpt = providerRegistry.getConfig(providerId);
    if (configOpt.isEmpty()) {
        renderer.error("Provider 未注册: " + providerId);
        return;
    }
    var config = configOpt.get();
    if (config.enabled() == enable) {
        renderer.info("Provider " + providerId + " 已经是" + (enable ? "启用" : "禁用") + "状态");
        return;
    }
    providerRegistry.deregister(providerId);
    var newConfig = new ProviderConfig(
        config.id(), config.type(), config.apiUrl(), config.apiKey(),
        config.modelName(), config.timeoutSeconds(), config.priority(),
        config.scenes(), config.capabilities(), enable,
        config.costPerInputToken(), config.costPerOutputToken(),
        config.maxContextWindow(), config.embeddingDimension(),
        config.supportsStreaming()
    );
    providerRegistry.register(newConfig);
    renderer.success("Provider " + providerId + " 已" + (enable ? "启用" : "禁用"));
}
```

#### 3. handleMcp 扩展

**mcp add `<name>` `<transport>` `<url>`**

```java
private void mcpAdd(String[] args, ResponseRenderer renderer) {
    // args: [name, transport, url]
    TransportType transport = TransportType.valueOf(args[1].toUpperCase().replace("-", "_"));
    var config = McpServerConfig.builder()
        .name(args[0])
        .transport(transport)
        .url(args[2])
        .autoConnect(false)
        .reconnect(true)
        .build();
    mcpServerRegistry.connectServer(config);
    renderer.success("MCP Server 连接已发起: " + args[0]);
}
```

**mcp remove `<name>`**

```java
private void mcpRemove(String name, ResponseRenderer renderer) {
    if (mcpServerRegistry.getServer(name).isEmpty()) {
        renderer.error("MCP Server 未找到: " + name);
        return;
    }
    mcpServerRegistry.disconnectServer(name);
    renderer.success("MCP Server 已断开: " + name);
}
```

**mcp test `<name>`**

```java
private void mcpTest(String name, ResponseRenderer renderer) {
    var clientOpt = mcpServerRegistry.getClient(name);
    if (clientOpt.isEmpty()) {
        renderer.error("MCP Server 不可用: " + name);
        return;
    }
    renderer.info("正在测试 MCP Server: " + name + " ...");
    long startMs = System.currentTimeMillis();
    var tools = clientOpt.get().listTools().join();
    long elapsedMs = System.currentTimeMillis() - startMs;
    renderer.success("MCP Server " + name + " 连通正常（发现 " + tools.size() + " 个工具，延迟 " + elapsedMs + "ms）");
}
```

#### 4. handleSkill 扩展

**skill reload**

```java
private void skillReload(ResponseRenderer renderer) {
    int count = yamlSkillLoader.loadAll();
    renderer.success("YAML Skill 重新加载完成，共加载 " + count + " 个 Skill");
}
```

### CliCompleter 更新

更新 `buildCompleters()` 中的 `StringsCompleter` 参数：

| 命令 | 当前子命令 | 更新后子命令 |
|------|-----------|------------|
| `todo` | `list`, `add` | `list`, `add`, `done`, `delete` |
| `schedule` | `list` | `list`, `add`, `today`, `tomorrow` |
| `habit` | `list` | `list`, `checkin`, `status` |
| `llm` | `list`, `test` | `list`, `add`, `test`, `remove`, `enable`, `disable` |
| `mcp` | `list` | `list`, `add`, `remove`, `test` |
| `skill` | `list`, `info` | `list`, `info`, `reload` |

### 用法帮助更新

每个 `printXxxUsage` 方法需列出完整子命令集。按 domain 差异化输出：

```
用法: todo <子命令>

可用子命令:
  list              列出所有待办
  add <内容>        添加待办
  done <id>         标记待办完成
  delete <id>       删除待办
```

```
用法: llm <子命令>

可用子命令:
  list              列出所有 LLM Provider
  add <id> <type> <model> <apiKey>  添加 Provider
  remove <id>       移除 Provider
  enable <id>       启用 Provider
  disable <id>      禁用 Provider
  test <id>         测试 Provider 连通性
```

## Data Models

本特性不引入新的数据模型。所有操作使用现有 record：

- **ProviderConfig** — 15 个字段的 record，enable/disable 通过构造新实例实现
- **McpServerConfig** — `@Builder(toBuilder = true)` record，mcp add 通过 builder 构造
- **SkillDefinition** — 只读查询，不修改
- **ToolInput / ToolResult** — 内置工具调用的输入输出载体


## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Builtin tool command dispatch

*For any* builtin domain (todo/schedule/habit) and any subcommand that maps to a tool ID (done, delete, today, tomorrow, add, checkin, status), and any valid argument string, executing the command shall invoke `DynamicToolRegistry.resolve()` with the correct tool ID and pass the argument as the expected parameter key-value pair.

**Validates: Requirements 1.1, 2.1, 4.1, 5.1**

### Property 2: LLM add creates and registers provider

*For any* valid combination of (providerId, providerType, modelName, apiKey) where providerId is not already registered, executing `llm add` shall result in `ProviderRegistry.getConfig(providerId)` returning a present Optional whose ProviderConfig has matching type, modelName, and enabled=true.

**Validates: Requirements 7.1, 7.2**

### Property 3: LLM remove deregisters provider

*For any* providerId that is currently registered in ProviderRegistry, executing `llm remove <providerId>` shall result in `ProviderRegistry.getConfig(providerId)` returning an empty Optional.

**Validates: Requirements 8.1**

### Property 4: LLM enable/disable toggles enabled flag

*For any* registered ProviderConfig with enabled=E, executing `llm enable` (or `llm disable`) shall result in the provider being re-registered with enabled=true (or enabled=false), while all other fields (id, type, apiUrl, apiKey, modelName, priority, scenes, capabilities, etc.) remain identical.

**Validates: Requirements 9.1, 9.2**

### Property 5: LLM toggle idempotence

*For any* registered ProviderConfig where enabled already equals the target state, executing `llm enable` (or `llm disable`) shall not modify the registry state — `ProviderRegistry.getConfig()` returns the same config before and after.

**Validates: Requirements 9.4**

### Property 6: MCP add creates config and initiates connection

*For any* valid combination of (name, transportType, url), executing `mcp add` shall invoke `McpServerRegistry.connectServer()` with an McpServerConfig whose name, transport, and url match the provided arguments.

**Validates: Requirements 10.1, 10.2**

### Property 7: MCP remove disconnects server

*For any* server name that exists in McpServerRegistry, executing `mcp remove <name>` shall invoke `McpServerRegistry.disconnectServer()` with that name.

**Validates: Requirements 11.1**

### Property 8: MCP test performs connectivity check

*For any* server name where `McpServerRegistry.getClient()` returns a present McpClient, executing `mcp test <name>` shall invoke `listTools()` on that client and render a success message containing the tool count and elapsed time.

**Validates: Requirements 12.1, 12.2**

### Property 9: Usage help completeness

*For any* command group (todo/schedule/habit/llm/mcp/skill) and any input that is either empty or an unrecognized subcommand, the rendered output shall contain all subcommands defined for that group in the architecture document §8.

**Validates: Requirements 15.1, 15.2, 15.3**

## Error Handling

所有新增子命令的错误处理遵循现有模式：

### 统一异常捕获

`QuickCommand.execute()` 的外层 try-catch 已覆盖所有子命令执行异常：

```java
try {
    switch (command) { ... }
} catch (Exception e) {
    log.warn("快捷命令执行失败: command={}, error={}", command, e.getMessage());
    renderer.error("命令执行失败: " + e.getMessage());
}
```

新增子命令无需额外的异常处理层。

### 错误场景分类

| 错误场景 | 处理方式 | 输出格式 |
|---------|---------|---------|
| 缺少必需参数 | 方法内检查 `subArgs.length` | `renderer.error("缺少参数: ...")` + `printXxxUsage()` |
| 资源不存在（Provider/Server/Habit） | 查询 Registry 返回 empty | `renderer.error("XXX 未找到: " + id)` |
| 重复注册 | `ProviderRegistry.register()` 抛 IllegalArgumentException | 由外层 catch 捕获 |
| 工具未注册 | `DynamicToolRegistry.resolve()` 返回 empty | `renderer.error("工具未注册: " + toolId)` |
| 工具执行失败 | `ToolResult.ok()` 返回 false | `renderer.error("执行失败: " + result.error())` |
| MCP 连通性检查失败 | `listTools().join()` 抛异常 | `renderer.error("MCP Server 测试失败: " + e.getMessage())` |
| Skill 重载失败 | `YamlSkillLoader.loadAll()` 抛异常 | 由外层 catch 捕获 |
| 无效枚举值（ProviderType/TransportType） | `valueOf()` 抛 IllegalArgumentException | 由外层 catch 捕获 |

### 未知子命令

所有 `handleXxx` 方法的 switch default 分支统一输出错误 + 用法帮助：

```java
default -> {
    renderer.error("未知子命令: " + domain + " " + subCommand);
    printXxxUsage(renderer);
}
```

## Testing Strategy

### 属性测试（Property-Based Testing）

- 框架：**jqwik**（JUnit 5 原生集成的 Java 属性测试库）
- 每个属性测试最少 100 次迭代
- 每个测试方法注释标注对应的 design property

**测试标注格式**：
```java
// Feature: cli-commands-enhancement, Property 1: Builtin tool command dispatch
```

**属性测试覆盖**：

| Property | 测试方法 | 生成器 |
|----------|---------|--------|
| Property 1 | `builtinTool命令分派_正确调用对应工具()` | 随机 domain + subcommand + 参数组合 |
| Property 2 | `llmAdd_创建并注册Provider()` | 随机 providerId + ProviderType + modelName + apiKey |
| Property 3 | `llmRemove_注销Provider()` | 随机已注册 providerId |
| Property 4 | `llmToggle_切换enabled标志()` | 随机 ProviderConfig + 随机 boolean |
| Property 5 | `llmToggle_幂等性()` | 随机 ProviderConfig（enabled 已等于目标值） |
| Property 6 | `mcpAdd_创建配置并发起连接()` | 随机 name + TransportType + url |
| Property 7 | `mcpRemove_断开服务器()` | 随机已注册 server name |
| Property 8 | `mcpTest_执行连通性检查()` | 随机已连接 server name |
| Property 9 | `usageHelp_包含所有子命令()` | 随机 command group + 随机无效子命令 |

### 单元测试

- 框架：JUnit 5
- Mock：Mockito（mock DynamicToolRegistry、ProviderRegistry、McpServerRegistry、SkillRegistry、YamlSkillLoader）
- ResponseRenderer 使用 `StringWriter` 构造，捕获输出内容

**单元测试覆盖**：

| 测试场景 | 测试方法 |
|---------|---------|
| todo done 缺少参数 | `todoDone_缺少参数_显示用法提示()` |
| todo delete 缺少参数 | `todoDelete_缺少参数_显示用法提示()` |
| schedule today 调用正确日期 | `scheduleToday_传入今日日期()` |
| schedule tomorrow 调用正确日期 | `scheduleTomorrow_传入明日日期()` |
| habit status 无习惯 | `habitStatus_无习惯_显示提示信息()` |
| llm add 参数不足 | `llmAdd_参数不足_显示用法提示()` |
| llm add 重复 ID | `llmAdd_重复ID_显示错误()` |
| llm remove 不存在 | `llmRemove_不存在_显示错误()` |
| llm enable 已启用 | `llmEnable_已启用_显示无变更()` |
| mcp add 参数不足 | `mcpAdd_参数不足_显示用法提示()` |
| mcp remove 不存在 | `mcpRemove_不存在_显示错误()` |
| mcp test 不可用 | `mcpTest_不可用_显示错误()` |
| mcp test 连接失败 | `mcpTest_连接失败_显示错误()` |
| skill reload 成功 | `skillReload_成功_显示加载数量()` |
| skill reload 异常 | `skillReload_异常_显示错误()` |
| CliCompleter todo 补全 | `completer_todo_返回完整子命令集()` |
| CliCompleter llm 补全 | `completer_llm_返回完整子命令集()` |
| 未知子命令显示用法 | `未知子命令_显示错误和用法帮助()` |

### 测试组织

```
src/test/java/com/lifepilot/interaction/cli/
├── QuickCommandTest.java           ← 单元测试
├── QuickCommandPropertyTest.java   ← 属性测试（jqwik）
└── CliCompleterTest.java           ← 补全器测试
```
