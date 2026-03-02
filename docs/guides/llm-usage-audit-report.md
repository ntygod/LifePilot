# LLM 调用使用情况检查报告

> 基于 [Spring AI 响应解析最佳实践指南](./spring-ai-response-parsing.md) 的检查结果

**检查日期**: 2026-02-24  
**最后更新**: 2026-02-24（已完成改进）  
**检查范围**: 项目中所有使用 LLM 调用的地方

---

## 📊 总体情况

| 类别 | 数量 | 状态 |
|------|------|------|
| ✅ 符合最佳实践 | 4 | 已使用 `.entity()` 或 `callEntity()` |
| ⚠️ 需要改进 | 0 | 所有高优先级改进已完成 |
| ✅ 可接受 | 1 | 纯文本场景，使用 `.content()` 合理 |

**改进状态**: ✅ 所有高优先级改进已完成

---

## ✅ 符合最佳实践

### 1. `SpringAiProviderAdapter.callEntity()` ✅

**位置**: `src/main/java/com/lifepilot/llm/adapter/SpringAiProviderAdapter.java:85-90`

```java
@Override
public <T> T callEntity(String prompt, Class<T> responseType) {
    return ChatClient.create(chatModel)
            .prompt(prompt)
            .call()
            .entity(responseType);
}
```

**评估**:
- ✅ 正确使用了 `.entity()` 方法
- ✅ 类型安全
- ✅ 自动处理 Markdown 代码块和格式问题
- ✅ 符合 Spring AI 最佳实践

**建议**: 无需修改，可作为其他地方的参考实现。

---

## ✅ 已改进（高优先级）

### 1. `AgentLoop.callLlmAndParseAction()` ✅

**位置**: `src/main/java/com/lifepilot/agent/AgentLoop.java:196-280`

**改进状态**: ✅ **已完成**（2026-02-24）

**改进前**:
```java
String response = promptBuilder
        .user(userPrompt != null ? userPrompt : "")
        .call()
        .content();  // ❌ 获取字符串

return actionParser.parse(state.phase(), response != null ? response : "");  // ❌ 手动解析
```

**改进后**:
```java
// 优先使用 .entity() 方法进行类型安全解析
try {
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
            // EXECUTING 阶段需要特殊处理，直接使用手动解析
            String response = promptBuilder
                    .user(userPrompt != null ? userPrompt : "")
                    .call()
                    .content();
            yield actionParser.parse(state.phase(), response != null ? response : "");
        }
        
        default -> {
            // 其他阶段使用手动解析
            String response = promptBuilder
                    .user(userPrompt != null ? userPrompt : "")
                    .call()
                    .content();
            yield actionParser.parse(state.phase(), response != null ? response : "");
        }
    };
    
    log.debug("使用 .entity() 解析成功: phase={}, traceId={}", state.phase(), state.traceId());
    return action;
    
} catch (Exception e) {
    // .entity() 解析失败，降级到手动解析
    log.warn("entity() 解析失败，降级到手动解析: phase={}, error={}, traceId={}", 
             state.phase(), e.getMessage(), state.traceId());
    // ... 降级逻辑
}
```

**改进效果**:
- ✅ 利用 Spring AI 的自动解析能力
- ✅ 减少解析失败率
- ✅ 代码更简洁
- ✅ 类型安全
- ✅ 保留 `ActionParser` 作为降级方案，确保向后兼容

---

### 2. `ActionParser.parse()` ✅

**位置**: `src/main/java/com/lifepilot/agent/ActionParser.java:70-144`

**改进状态**: ✅ **已保留作为降级方案**

**说明**:
- `ActionParser` 已保留作为降级方案，当 `.entity()` 解析失败时使用
- 多策略手动解析逻辑（直接 JSON → Markdown 提取 → JSON 修复 → 部分提取）仍然有效
- 确保向后兼容和容错能力

**建议**: 
- ✅ 已迁移 `AgentLoop` 使用 `.entity()`
- ✅ 保留 `ActionParser` 作为降级方案
- 📊 监控解析成功率，如果 `.entity()` 成功率 >95%，可以考虑简化 `ActionParser`

---

### 3. `LlmJudge.parseResponse()` ✅

**位置**: `src/main/java/com/lifepilot/eval/judge/LlmJudge.java:47-100`

**改进状态**: ✅ **已完成**（2026-02-24）

**改进前**:
```java
private ParsedResponse parseResponse(String content) {
    // 尝试 JSON 解析
    try {
        var jsonResult = parseAsJson(content);
        if (jsonResult != null) {
            return jsonResult;
        }
    } catch (Exception e) {
        log.debug("JSON 解析失败，尝试正则提取: error={}", e.getMessage());
    }
    
    // 尝试正则提取评分
    var score = parseScoreFromText(content);
    if (score != null) {
        return new ParsedResponse(score, content.trim());
    }
    
    return null;
}
```

**改进后**:
```java
// 定义响应类
public record JudgeResponse(
    @JsonProperty("score") Double score,
    @JsonProperty("justification") @Nullable String justification
) {}

// 在 judge() 方法中使用
public JudgeResult judge(String actualOutput, String expectedPattern, String criteria) {
    // 优先使用 callEntity() 进行类型安全解析
    try {
        var prompt = buildPrompt(actualOutput, expectedPattern, criteria);
        JudgeResponse response = llmRouter.callEntity(scene, prompt, JudgeResponse.class);
        
        if (response != null && response.score() != null) {
            var score = clampScore(response.score());
            var justification = response.justification() != null 
                    ? response.justification() 
                    : "无评判理由";
            log.debug("LLM Judge 评估完成（使用 callEntity）: score={}", score);
            return new JudgeResult(score, justification, 0, false);
        }
        
        // 如果解析失败，降级到手动解析
        log.warn("LLM Judge callEntity 返回空结果，降级到手动解析");
        return fallbackToManualParse(actualOutput, expectedPattern, criteria);
        
    } catch (Exception e) {
        // callEntity 失败，降级到手动解析
        log.warn("LLM Judge callEntity 失败，降级到手动解析: error={}", e.getMessage());
        return fallbackToManualParse(actualOutput, expectedPattern, criteria);
    }
}
```

**改进效果**:
- ✅ 自动处理 Markdown 代码块
- ✅ 自动修复 JSON 格式问题
- ✅ 类型安全
- ✅ 减少手动解析代码
- ✅ 保留手动解析作为降级方案

---

## ✅ 已改进（中优先级）

### 4. `LlmReranker.scoreCandidate()` ✅

**位置**: `src/main/java/com/lifepilot/knowledge/rerank/LlmReranker.java:60-90`

**改进状态**: ✅ **已完成**（2026-02-24）

**改进前**:
```java
private double scoreCandidate(String query, DocumentSearchResult candidate) {
    var prompt = """
            请评估以下查询和文档的相关性，返回 0.0 到 1.0 之间的分数。
            只返回数字，不要其他内容。
            
            查询：%s
            文档：%s""".formatted(query, candidate.content());

    var response = llmRouter.call(SCENE, prompt, null);
    try {
        return Double.parseDouble(response.content().trim());
    } catch (NumberFormatException e) {
        return candidate.score();
    }
}
```

**改进后**:
```java
// 定义响应类
private record ScoreResponse(
    @JsonProperty("score") Double score
) {}

private double scoreCandidate(String query, DocumentSearchResult candidate) {
    var prompt = """
            请评估以下查询和文档的相关性，返回 JSON 格式：
            {"score": 0.85}
            
            查询：%s
            文档：%s""".formatted(query, candidate.content());

    try {
        // ✅ 优先使用 callEntity 进行类型安全解析
        ScoreResponse response = llmRouter.callEntity(SCENE, prompt, ScoreResponse.class);
        if (response != null && response.score() != null) {
            return Math.max(0.0, Math.min(1.0, response.score()));
        }
    } catch (Exception e) {
        log.debug("LLM 精排 callEntity 解析失败，降级到手动解析: error={}", e.getMessage());
    }
    
    // 降级到手动解析
    try {
        var response = llmRouter.call(SCENE, prompt, null);
        return Double.parseDouble(response.content().trim());
    } catch (NumberFormatException e) {
        log.debug("LLM 精排手动解析失败，使用原始分数: error={}", e.getMessage());
        return candidate.score();
    }
}
```

**改进效果**:
- ✅ 使用 `.entity()` 进行类型安全解析
- ✅ 自动处理 JSON 格式问题
- ✅ 保留手动解析作为降级方案
- ✅ 提高解析成功率

---

## ✅ 可接受（无需改进）

### 5. `ForgettingEngine` ✅

**位置**: `src/main/java/com/lifepilot/memory/forgetting/ForgettingEngine.java:176-177`

**当前实现**:
```java
var response = llmRouter.call(LlmScene.MEMORY_COMPRESSION, prompt, null);
var summary = response.content();
```

**评估**:
- ✅ 场景合理：需要纯文本摘要，不需要结构化数据
- ✅ 使用 `.content()` 获取文本是正确选择
- ✅ 无需改进

---

## 📋 其他发现

### 使用 `objectMapper.readValue()` 的地方

以下位置使用 `objectMapper.readValue()` 解析 JSON，但这些是**非 LLM 响应**的解析，属于正常使用：

1. `SessionManager` - 解析会话状态 JSON（数据库存储）
2. `AnalyticsController` - 解析元数据 JSON（数据库存储）
3. `KnowledgeBaseRepository` - 解析搜索结果 JSON（数据库存储）
4. `ProceduralMemory` - 解析模板步骤 JSON（数据库存储）

**评估**: ✅ 这些场景不属于 LLM 响应解析，使用 `objectMapper.readValue()` 是合理的。

---

## 🎯 改进优先级

| 优先级 | 位置 | 影响 | 工作量 | 状态 |
|--------|------|------|--------|------|
| 🔴 高 | `AgentLoop.callLlmAndParseAction()` | 核心功能，解析失败率高 | 中等 | ✅ 已完成 |
| 🔴 高 | `LlmJudge.parseResponse()` | 评估功能，解析失败影响评分 | 小 | ✅ 已完成 |
| 🟡 中 | `LlmReranker.scoreCandidate()` | 精排功能，影响较小 | 小 | ✅ 已完成 |
| 🟢 低 | `ActionParser` | 降级方案，保留即可 | - | ✅ 已保留 |

---

## 📝 改进总结

### ✅ 已完成改进（2026-02-24）

1. **✅ 迁移 `AgentLoop.callLlmAndParseAction()` 使用 `.entity()`**
   - ✅ 已替换 `.content()` + `ActionParser.parse()` 为 `.entity()`
   - ✅ 保留 `ActionParser` 作为降级方案
   - ✅ 支持 UNDERSTANDING、PLANNING、REFLECTING、RESPONDING 阶段
   - ✅ EXECUTING 阶段继续使用手动解析（特殊处理）

2. **✅ 迁移 `LlmJudge.parseResponse()` 使用 `callEntity()`**
   - ✅ 已定义 `JudgeResponse` Record
   - ✅ 使用 `LlmRouter.callEntity()` 进行类型安全解析
   - ✅ 保留手动解析作为降级方案

3. **✅ 优化 `LlmReranker.scoreCandidate()` 使用 `callEntity()`**
   - ✅ 已定义 `ScoreResponse` Record
   - ✅ 使用 `LlmRouter.callEntity()` 进行类型安全解析
   - ✅ 保留手动解析作为降级方案

### ✅ 保留现状

4. **`ForgettingEngine`** - 纯文本场景，使用 `.content()` 合理，无需改进

---

## 🔍 验证方法

### 改进前
- 记录当前解析失败率
- 记录解析失败的具体场景和错误信息

### 改进后
- 对比解析成功率（目标：>95%）
- 监控错误日志，确保降级方案正常工作
- 性能对比（`.entity()` vs 手动解析）

---

## 📚 参考文档

- [Spring AI 响应解析最佳实践指南](./spring-ai-response-parsing.md)
- [Spring AI 响应解析快速参考](./spring-ai-response-parsing-quick-reference.md)
- [Spring AI ChatClient API 文档](https://docs.spring.io/spring-ai/reference/api/chat-client.html)

---

## ✅ 检查清单

改进完成情况：

- [x] `AgentLoop` 已迁移到 `.entity()` 方法
- [x] `LlmJudge` 已迁移到 `callEntity()` 方法
- [x] `LlmReranker` 已迁移到 `callEntity()` 方法
- [x] 所有 Action 类型都是可序列化的 Record/Class
- [x] Prompt 中明确要求 JSON 格式
- [x] 降级方案已实现（保留 `ActionParser` 和手动解析逻辑）
- [ ] 解析成功率监控（需要生产环境验证）
- [ ] 单元测试已更新（建议添加测试用例）
- [x] 文档已更新

---

**报告生成时间**: 2026-02-24  
**改进完成时间**: 2026-02-24  
**下次检查建议**: 改进完成后 1 个月（验证解析成功率）
