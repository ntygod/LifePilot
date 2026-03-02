# Spring AI 1.1.2 响应解析最佳实践指南

## 概述

Spring AI 提供了多种内置的、可靠的响应解析方法，**强烈建议使用官方 API 而不是手动解析**，可以避免 JSON 解析失败、格式不匹配等问题。

---

## 官方解析方法

### 1. **`.content()` - 获取纯文本响应** ⭐ 最简单

**适用场景**: 只需要文本内容，不需要结构化数据

```java
String response = chatClient.prompt()
    .user("Tell me a joke")
    .call()
    .content();
```

**优点**:
- 最简单直接
- 自动处理响应格式
- 返回纯文本，无需额外处理

**缺点**:
- 无法直接映射到 Java 对象
- 需要手动解析 JSON（不推荐）

---

### 2. **`.entity(Class<T>)` - 自动映射到 Java 对象** ⭐⭐⭐ **最推荐**

**适用场景**: 需要将 LLM 响应解析为结构化 Java 对象

```java
// 单个对象
ActorFilms actorFilms = chatClient.prompt()
    .user("Generate the filmography for a random actor.")
    .call()
    .entity(ActorFilms.class);

// 集合类型（使用 ParameterizedTypeReference）
List<ActorFilms> actorFilmsList = chatClient.prompt()
    .user("Generate the filmography of 5 movies...")
    .call()
    .entity(new ParameterizedTypeReference<List<ActorFilms>>() {});
```

**优点**:
- ✅ **自动处理 JSON 反序列化**
- ✅ **自动处理 Markdown 代码块包裹**（如 `\`\`\`json ... \`\`\``）
- ✅ **自动处理格式问题**（尾部逗号、多余空白等）
- ✅ **类型安全**，编译时检查
- ✅ **容错性强**，Spring AI 内部做了大量优化

**缺点**:
- 需要定义对应的 Java 类或 Record

**关键优势**: Spring AI 内部会自动：
1. 提取 JSON（即使被 Markdown 代码块包裹）
2. 修复常见的 JSON 格式问题
3. 处理 LLM 输出中的额外文本（如解释性文字）
4. 使用 Jackson 进行类型安全的反序列化

---

### 3. **`.chatResponse()` - 获取完整响应对象**

**适用场景**: 需要访问元数据（token 使用量、模型信息等）

```java
ChatResponse chatResponse = chatClient.prompt()
    .user("Tell me a joke")
    .call()
    .chatResponse();

// 获取文本内容
String content = chatResponse.getResult().getOutput().getText();

// 获取 token 使用量
var usage = chatResponse.getMetadata().getUsage();
int inputTokens = usage.getPromptTokens();
int outputTokens = usage.getCompletionTokens();

// 获取模型信息
String modelName = chatResponse.getMetadata().getModel();
```

**优点**:
- 可以访问完整的响应元数据
- 适合需要统计 token 使用量的场景

**缺点**:
- 需要手动提取文本内容
- 无法直接映射到结构化对象

---

### 4. **流式响应解析**

**适用场景**: 处理大型响应，需要实时显示

```java
// 流式文本
Flux<String> stream = chatClient.prompt()
    .user("Tell me a long story")
    .stream()
    .content();

// 流式对象（如果 LLM 支持流式结构化输出）
Flux<ActorFilms> stream = chatClient.prompt()
    .user("Generate filmography...")
    .stream()
    .entity(ActorFilms.class);
```

---

## 项目中的问题与解决方案

### ❌ 当前问题：手动解析容易失败

**当前代码** (`AgentLoop.java:225-230`):
```java
String response = promptBuilder
    .user(userPrompt != null ? userPrompt : "")
    .call()
    .content();  // 获取字符串

return actionParser.parse(state.phase(), response != null ? response : "");  // 手动解析
```

**问题**:
1. `ActionParser.parse()` 需要处理多种格式（Markdown 代码块、JSON 修复等）
2. 解析逻辑复杂，容易失败
3. 需要维护大量容错代码

---

### ✅ 解决方案：使用 `.entity()` 方法

#### 方案 1: 直接映射到 Action 类型（推荐）

**前提**: 需要根据 `AgentPhase` 确定对应的 Action 类型

```java
private Action callLlmAndParseAction(AgentRequest request,
                                     AgentState state,
                                     AssembledContext assembledContext) {
    try {
        ChatClient chatClient = llmRouter.getChatClient(scene);
        var promptBuilder = chatClient.prompt();

        // ... 设置 system prompt 和 tool callbacks ...

        String userPrompt = assembledContext.userPrompt();
        
        // 根据 phase 确定 Action 类型
        Action action = switch (state.phase()) {
            case UNDERSTANDING -> promptBuilder
                .user(userPrompt != null ? userPrompt : "")
                .call()
                .entity(Action.IntentUnderstood.class);
            
            case PLANNING -> promptBuilder
                .user(userPrompt != null ? userPrompt : "")
                .call()
                .entity(Action.PlanGenerated.class);
            
            case REFLECTING -> promptBuilder
                .user(userPrompt != null ? userPrompt : "")
                .call()
                .entity(Action.ReflectionComplete.class);
            
            case RESPONDING -> promptBuilder
                .user(userPrompt != null ? userPrompt : "")
                .call()
                .entity(Action.ResponseGenerated.class);
            
            case EXECUTING -> {
                // EXECUTING 阶段可能返回多种 Action 类型
                // 需要特殊处理或使用通用解析
                String response = promptBuilder
                    .user(userPrompt != null ? userPrompt : "")
                    .call()
                    .content();
                yield actionParser.parse(state.phase(), response);
            }
        };
        
        return action;
    } catch (Exception e) {
        // 如果 entity() 解析失败，可以降级到手动解析
        log.warn("entity() 解析失败，降级到手动解析: phase={}, error={}", 
                 state.phase(), e.getMessage());
        return fallbackToManualParse(request, state, assembledContext);
    }
}
```

**优点**:
- ✅ 利用 Spring AI 的自动解析能力
- ✅ 类型安全
- ✅ 减少手动解析代码

**缺点**:
- 需要为每个 Action 类型定义对应的 Java 类/Record
- EXECUTING 阶段可能需要特殊处理

---

#### 方案 2: 使用通用 Action 包装类

如果 Action 类型较多或动态，可以定义一个通用的 Action 包装类：

```java
// 定义通用的 Action 响应类
public record ActionResponse(
    String actionType,  // "IntentUnderstood", "PlanGenerated", etc.
    Map<String, Object> data  // 动态数据
) {}

// 使用
ActionResponse response = promptBuilder
    .user(userPrompt)
    .call()
    .entity(ActionResponse.class);

// 然后根据 actionType 和 data 构建对应的 Action
Action action = buildActionFromResponse(state.phase(), response);
```

---

#### 方案 3: 使用 JSON 节点（灵活但需要手动处理）

```java
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.ParameterizedTypeReference;

JsonNode jsonNode = promptBuilder
    .user(userPrompt)
    .call()
    .entity(new ParameterizedTypeReference<JsonNode>() {});

// 然后手动提取字段（但比字符串解析更可靠）
Action action = parseActionFromJsonNode(state.phase(), jsonNode);
```

---

## 实际应用示例

### 示例 1: 替换 `ActionParser` 中的手动解析

**原代码** (`ActionParser.java:70-125`):
```java
public Action parse(AgentPhase phase, String llmOutput) {
    // 大量手动解析逻辑：Markdown 提取、JSON 修复、部分提取等
    // ...
}
```

**改进后**:
```java
public Action parse(AgentPhase phase, String llmOutput) {
    // 如果 llmOutput 已经是字符串，尝试直接解析
    // 但更好的方式是使用 .entity() 在调用时直接解析
    
    // 降级方案：保留手动解析作为后备
    return tryParseWithFallback(phase, llmOutput);
}
```

**更好的方式**: 在 `AgentLoop` 中直接使用 `.entity()`，完全避免手动解析。

---

### 示例 2: 替换 `LlmJudge` 中的手动解析

**原代码** (`LlmJudge.java:152-170`):
```java
private ParsedResponse parseResponse(String content) {
    // 尝试 JSON 解析
    try {
        var jsonResult = parseAsJson(content);
        if (jsonResult != null) {
            return jsonResult;
        }
    } catch (Exception e) {
        // ...
    }
    // 尝试正则提取评分
    // ...
}
```

**改进后**:
```java
// 定义响应类
public record JudgeResponse(
    @JsonProperty("score") Double score,
    @JsonProperty("justification") String justification
) {}

// 使用 .entity() 直接解析
JudgeResponse response = chatClient.prompt()
    .user(judgePrompt)
    .call()
    .entity(JudgeResponse.class);

// 如果解析失败，可以捕获异常并降级
try {
    return new ParsedResponse(response.score(), response.justification());
} catch (Exception e) {
    // 降级到手动解析
    return fallbackParse(content);
}
```

---

## 最佳实践建议

### 1. **优先使用 `.entity()` 方法**

```java
// ✅ 推荐
MyResponse response = chatClient.prompt()
    .user(prompt)
    .call()
    .entity(MyResponse.class);

// ❌ 不推荐
String text = chatClient.prompt()
    .user(prompt)
    .call()
    .content();
MyResponse response = objectMapper.readValue(text, MyResponse.class);  // 手动解析
```

### 2. **定义清晰的响应类/Record**

```java
// ✅ 推荐：使用 Record 定义响应
public record ActorFilms(
    String actorName,
    List<Film> films
) {
    public record Film(String title, int year) {}
}

// ✅ 推荐：使用 Jackson 注解处理字段映射
public record JudgeResponse(
    @JsonProperty("score") Double score,
    @JsonProperty("justification") @Nullable String justification
) {}
```

### 3. **处理解析失败的情况**

```java
try {
    Action action = chatClient.prompt()
        .user(prompt)
        .call()
        .entity(Action.IntentUnderstood.class);
    return action;
} catch (Exception e) {
    log.warn("entity() 解析失败，使用降级方案: error={}", e.getMessage());
    // 降级到手动解析或返回错误 Action
    return new Action.ErrorRecovery("PARSE_FAILURE", true);
}
```

### 4. **使用结构化 Prompt 提高解析成功率**

在 Prompt 中明确要求 JSON 格式：

```java
String prompt = """
    请以 JSON 格式返回结果，格式如下：
    {
        "actionType": "IntentUnderstood",
        "intent": "...",
        "confidence": 0.95
    }
    
    用户问题：%s
    """.formatted(userQuestion);
```

Spring AI 的 `.entity()` 方法会自动提取 JSON，即使 LLM 返回了额外的解释文字。

---

## 常见问题与解答

### Q1: `.entity()` 方法能处理 Markdown 代码块吗？

**A**: ✅ **可以**。Spring AI 会自动提取被 `\`\`\`json ... \`\`\`` 包裹的 JSON。

### Q2: `.entity()` 方法能处理格式不规范的 JSON 吗？

**A**: ✅ **部分可以**。Spring AI 会尝试修复常见的格式问题（如尾部逗号），但严重的格式错误仍可能失败。建议在 Prompt 中明确要求 JSON 格式。

### Q3: 如果 LLM 返回了 JSON + 解释文字怎么办？

**A**: ✅ **`.entity()` 会自动提取 JSON 部分**，忽略解释文字。这是 `.entity()` 相比手动解析的最大优势。

### Q4: 如何处理动态类型的响应？

**A**: 可以使用 `JsonNode` 或 `Map<String, Object>` 作为通用类型：

```java
JsonNode response = chatClient.prompt()
    .user(prompt)
    .call()
    .entity(JsonNode.class);

// 然后根据实际字段动态处理
```

### Q5: `.entity()` 解析失败怎么办？

**A**: 可以：
1. 捕获异常并降级到手动解析
2. 在 Prompt 中更明确地要求 JSON 格式
3. 使用 `.chatResponse()` 获取原始响应，然后手动处理

---

## 迁移建议

### 步骤 1: 识别所有手动解析的地方

- `ActionParser.parse()` - Agent 动作解析
- `LlmJudge.parseResponse()` - 评分解析
- 其他使用 `objectMapper.readValue()` 或手动 JSON 提取的地方

### 步骤 2: 定义响应类/Record

为每个需要解析的响应定义对应的 Java 类或 Record。

### 步骤 3: 逐步替换

1. 先替换简单的场景（如 `LlmJudge`）
2. 再替换复杂的场景（如 `ActionParser`）
3. 保留手动解析作为降级方案

### 步骤 4: 测试验证

确保替换后的解析成功率不低于手动解析，如果更低，检查 Prompt 是否足够明确。

---

## 总结

**核心建议**: 

1. ✅ **优先使用 `ChatClient.call().entity(Class<T>)` 方法**
2. ✅ **定义清晰的响应类/Record**
3. ✅ **在 Prompt 中明确要求 JSON 格式**
4. ✅ **保留手动解析作为降级方案**

**优势**:
- 减少解析失败
- 代码更简洁
- 类型安全
- 维护成本更低

**迁移成本**:
- 需要定义响应类（但这是必要的）
- 需要逐步替换现有代码（可以并行运行，逐步切换）

---

## 项目中的实际示例

### 已有的 `.entity()` 使用

项目中 `SpringAiProviderAdapter.callEntity()` 方法已经使用了 `.entity()`：

```java
@Override
public <T> T callEntity(String prompt, Class<T> responseType) {
    return ChatClient.create(chatModel)
            .prompt(prompt)
            .call()
            .entity(responseType);
}
```

**使用示例**:
```java
// 假设有一个响应类
public record MyResponse(String field1, int field2) {}

// 调用
MyResponse response = adapter.callEntity("Generate response", MyResponse.class);
```

**建议**: 在 `AgentLoop` 和 `ActionParser` 中也采用类似的方式，直接使用 `.entity()` 而不是先获取字符串再手动解析。

---

## 参考资源

- [Spring AI ChatClient API 文档](https://docs.spring.io/spring-ai/reference/api/chat-client.html)
- [Spring AI 结构化输出](https://docs.spring.io/spring-ai/reference/api/structured-output.html)
- 项目中的 `SpringAiProviderAdapter.callEntity()` 方法已使用 `.entity()`，可作为参考
