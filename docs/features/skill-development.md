# Skill 开发指南

> 本文档从 [FEATURES.md](../FEATURES.md) 拆分而来，对应原文第五章节。

> ✅ Skill 开发体系已实现（Phase 3，模块 10），支持 YAML 声明式 Skill 和 Java 原生插件两种扩展方式。

## 1. YAML 声明式 Skill（推荐入门）

最简单的扩展方式，无需编写 Java 代码。将 YAML 文件放到 `~/.zhiwei/skills/` 目录即可，运行时自动热加载。

### 1.1 基础模板

```yaml
# ~/.zhiwei/skills/my-skill.yml
skill:
  id: my-skill                    # 唯一标识
  name: 我的技能                    # 显示名称
  description: 技能的简要描述        # Agent 用此判断何时激活
  version: "1.0"
  source: USER_DEFINED

  # 触发意图（可选，不指定则由 Agent 自行判断）
  intents:
    - my_skill.action1
    - my_skill.action2

  # 输入参数定义
  parameters:
    - name: param1
      type: string
      required: true
      description: 参数说明
    - name: param2
      type: integer
      required: false
      default: 10
      description: 可选参数

  # 执行动作
  action:
    type: http                     # http / shell / chain / template
    method: GET
    url: "https://api.example.com/endpoint"
    headers:
      Authorization: "Bearer ${env.MY_API_KEY}"
    query:
      q: "${params.param1}"
      limit: "${params.param2}"

  # 结果模板
  output:
    template: |
      查询结果：${result.data}

  # 记忆访问（可选）
  memory:
    read: []
    write: []
```

### 1.2 支持的动作类型

**HTTP 调用：**

```yaml
action:
  type: http
  method: POST
  url: "https://api.example.com/data"
  headers:
    Content-Type: application/json
  body:
    key: "${params.value}"
```

**Shell 命令：**

```yaml
action:
  type: shell
  command: "curl -s https://api.example.com/${params.query}"
  timeout-seconds: 10
```

**技能串联：**

```yaml
action:
  type: chain
  steps:
    - skill: weather-query
      params:
        city: "${params.city}"
      output: weather_data
    - skill: notification-send
      params:
        message: "今天${params.city}天气：${weather_data.condition}"
```

## 2. Java 原生插件（高级）

需要更强能力（直接访问 Spring 生态、数据库操作、复杂业务逻辑）时，可以开发 Java 原生插件。

### 2.1 实现步骤

1. 实现 `SkillPlugin` 接口
2. 标注为 Spring `@Component`
3. 系统启动时自动扫描注册

```java
package com.lifepilot.skill.plugins;

import com.lifepilot.skill.SkillPlugin;
import com.lifepilot.skill.SkillResult;
import org.springframework.stereotype.Component;

/**
 * 自定义技能插件示例。
 * 
 * 实现 SkillPlugin 接口并标注 @Component，
 * 系统启动时会自动扫描并注册此插件。
 */
@Component
public class MyCustomPlugin implements SkillPlugin {

    @Override
    public String getId() { 
        return "my-custom-plugin"; 
    }

    @Override
    public String getDescription() { 
        return "我的自定义插件，用于处理特定业务逻辑"; 
    }

    @Override
    public List<String> getSupportedIntents() {
        return List.of("my_plugin.query", "my_plugin.update");
    }

    @Override
    public String getInputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "action": { "type": "string", "enum": ["query", "update"] },
                "target": { "type": "string", "description": "操作目标" }
              },
              "required": ["action", "target"]
            }
            """;
    }

    @Override
    public String getOutputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "status": { "type": "string" },
                "data": { "type": "object" }
              }
            }
            """;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;  // 只读操作
    }

    @Override
    public SkillResult execute(String intent, Map<String, Object> params) {
        // 实现你的业务逻辑
        String target = (String) params.get("target");
        
        return switch (intent) {
            case "my_plugin.query" -> {
                var data = doQuery(target);
                yield SkillResult.success("查询完成", data);
            }
            case "my_plugin.update" -> {
                doUpdate(target);
                yield SkillResult.success("更新完成");
            }
            default -> SkillResult.error("不支持的操作: " + intent);
        };
    }
    
    private Map<String, Object> doQuery(String target) {
        // 查询逻辑...
        return Map.of("result", "查询结果");
    }
    
    private void doUpdate(String target) {
        // 更新逻辑...
    }
}
```

### 2.2 工具契约（ToolContract）

每个插件的工具调用都遵循严格的契约：

| 契约要素 | 说明 |
|---------|------|
| `inputSchema` | JSON Schema 定义输入参数，Agent 调用前自动校验 |
| `outputSchema` | JSON Schema 定义输出格式，确保结构化返回 |
| `riskLevel` | 风险等级声明，护栏引擎据此决定是否需要用户确认 |
| `idempotent` | 是否幂等，影响重试策略 |
| `budget` | 超时、重试次数、成本上限 |
