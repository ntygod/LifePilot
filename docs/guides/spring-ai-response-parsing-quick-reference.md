# Spring AI 响应解析快速参考

## 🎯 核心建议

**优先使用 `.entity()` 方法，避免手动解析 JSON！**

---

## 📋 常用方法对比

| 方法 | 适用场景 | 示例 |
|------|---------|------|
| `.content()` | 只需要文本 | `String text = chatClient.prompt().user("...").call().content();` |
| `.entity(Class<T>)` | ⭐ **需要结构化对象（推荐）** | `MyResponse r = chatClient.prompt().user("...").call().entity(MyResponse.class);` |
| `.chatResponse()` | 需要元数据（token 等） | `ChatResponse r = chatClient.prompt().user("...").call().chatResponse();` |

---

## ✅ 推荐用法

### 1. 解析为 Java 对象（最推荐）

```java
// 定义响应类
public record ActionResponse(
    String actionType,
    Map<String, Object> data
) {}

// 使用 .entity() 直接解析
ActionResponse response = chatClient.prompt()
    .user(prompt)
    .call()
    .entity(ActionResponse.class);
```

**优势**:
- ✅ 自动处理 Markdown 代码块
- ✅ 自动修复常见 JSON 格式问题
- ✅ 类型安全
- ✅ 容错性强

---

### 2. 解析为集合类型

```java
import org.springframework.core.ParameterizedTypeReference;

List<MyResponse> responses = chatClient.prompt()
    .user(prompt)
    .call()
    .entity(new ParameterizedTypeReference<List<MyResponse>>() {});
```

---

### 3. 处理解析失败

```java
try {
    MyResponse response = chatClient.prompt()
        .user(prompt)
        .call()
        .entity(MyResponse.class);
    return response;
} catch (Exception e) {
    // 降级到手动解析或返回错误
    log.warn("解析失败: {}", e.getMessage());
    return fallbackParse();
}
```

---

## ❌ 不推荐的做法

```java
// ❌ 不推荐：先获取字符串再手动解析
String text = chatClient.prompt().user("...").call().content();
MyResponse response = objectMapper.readValue(text, MyResponse.class);  // 容易失败

// ✅ 推荐：直接使用 .entity()
MyResponse response = chatClient.prompt().user("...").call().entity(MyResponse.class);
```

---

## 🔧 项目中的迁移建议

### 当前问题位置

1. **`AgentLoop.callLlmAndParseAction()`** - 使用 `.content()` + `ActionParser.parse()`
2. **`ActionParser.parse()`** - 大量手动 JSON 解析逻辑
3. **`LlmJudge.parseResponse()`** - 手动 JSON 和正则解析

### 迁移步骤

1. **定义响应类**: 为每个 Action 类型定义对应的 Record/Class
2. **替换调用**: 在 `AgentLoop` 中直接使用 `.entity(ActionType.class)`
3. **保留降级**: 保留 `ActionParser` 作为解析失败的降级方案

---

## 💡 提示

- `.entity()` 会自动提取 JSON（即使被 Markdown 代码块包裹）
- `.entity()` 会自动修复常见格式问题（尾部逗号等）
- 在 Prompt 中明确要求 JSON 格式可以提高成功率
- 使用 Record 定义响应类更简洁

---

## 📚 完整文档

详细内容请参考: [spring-ai-response-parsing.md](./spring-ai-response-parsing.md)
