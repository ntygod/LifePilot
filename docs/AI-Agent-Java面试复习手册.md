# AI Agent + Java 面试复习手册

> 面向 Java 后端开发者的 AI Agent 方向面试准备资料
> 整理日期：2026-04-12

---

## 目录

- [一、AI 核心概念速查](#一ai-核心概念速查)
- [二、大模型 API 调用](#二大模型-api-调用)
- [三、Messages 数组结构](#三messages-数组结构)
- [四、模型返回值数据结构](#四模型返回值数据结构)
- [五、SSE 流式原理与处理](#五sse-流式原理与处理)
- [六、Function Calling / Tool Use](#六function-calling--tool-use)
- [七、Agent 智能体架构](#七agent-智能体架构)
- [八、RAG 检索增强生成](#八rag-检索增强生成)
- [九、向量检索详解](#九向量检索详解)
- [十、Spring AI 详解](#十spring-ai-详解)
- [十一、熔断器机制](#十一熔断器机制)
- [十二、Java 并发 × Agent 场景](#十二java-并发--agent-场景)
- [十三、面试高频问答](#十三面试高频问答)
- [十四、面试速查卡](#十四面试速查卡)

---

## 一、AI 核心概念速查

| 术语 | 白话解释 | 面试怎么说 |
|------|---------|-----------|
| **Token** | 模型处理文本的最小单元，中文约 1 字 ≈ 1-2 token | "token 是模型计费和上下文限制的基本单位" |
| **Context Window** | 一次请求能塞进去的最大 token 数（如 128K） | "上下文窗口决定了单次请求能携带多少历史信息" |
| **Prompt** | 发给模型的输入文本 | "prompt 分 system（角色设定）和 user（用户问题）" |
| **Temperature** | 控制回答随机性，0=确定性最高，1=最随机 | "生产环境一般用 0-0.3，保证稳定性" |
| **Embedding** | 把文字变成一组浮点数（向量），语义相近的向量距离近 | "Embedding 把文本映射到高维向量空间，语义相似的文本向量距离近" |
| **Streaming/SSE** | 模型一边生成一边推送，不等全部完成 | "流式用 SSE 协议，服务端持续推送 token 片段" |
| **Hallucination** | 模型编造不存在的信息 | "用 RAG 兜底 + prompt 约束'仅基于资料回答'" |

---

## 二、大模型 API 调用

### 本质

大模型就是一个 HTTP 接口，和调支付接口、物流接口没有本质区别。

### 请求结构（OpenAI 兼容格式，90% 模型通用）

```json
POST https://api.deepseek.com/v1/chat/completions
Headers: Authorization: Bearer sk-xxx

{
  "model": "deepseek-chat",
  "messages": [
    {"role": "system", "content": "你是物流助手"},
    {"role": "user", "content": "查一下单号 SF2024001"}
  ],
  "temperature": 0.3,
  "max_tokens": 2000,
  "stream": true
}
```

### 对接工程问题

| 问题 | 解决方案 |
|------|---------|
| **超时** | 连接超时 5s，读取超时按 token 动态计算（约 30-50 token/s），流式用心跳检测 |
| **限流 429** | 客户端令牌桶限流，被 429 时指数退避 + 随机抖动重试 |
| **多模型切换** | 统一接口抽象，路由层按场景选模型，配独立熔断器 |
| **Key 安全** | 放配置中心/环境变量，传输走 HTTPS，日志脱敏 |
| **成本控制** | 记录每次 token 用量，按用户/租户做配额 |

---

## 三、Messages 数组结构

### 核心理解：模型无状态

模型没有记忆，每次请求必须把之前的对话都带上。多轮对话就是不断往 messages 数组追加消息。

### 四种 Role

#### 1. system —— 人设指令（一条，最前面）

```json
{"role": "system", "content": "你是物流客服。规则：1.只回答物流相关 2.查不到引导人工"}
```

#### 2. user —— 用户说的话

```json
{"role": "user", "content": "帮我查一下单号 SF2024001"}
```

#### 3. assistant —— 模型上一轮回答的（原样塞回去）

```json
{"role": "assistant", "content": "您的包裹在杭州转运中心，预计明天到达。"}
```

#### 4. tool —— 工具执行结果（Function Calling 场景）

```json
{"role": "tool", "tool_call_id": "call_abc123", "content": "{\"status\":\"运输中\"}"}
```

### 多轮对话完整示例

```json
[
  {"role": "system",    "content": "你是物流客服"},
  {"role": "user",      "content": "SF2024001 到哪了"},
  {"role": "assistant", "content": null, "tool_calls": [{"id":"call_1", "function":{"name":"query_shipment","arguments":"{\"tracking_no\":\"SF2024001\"}"}}]},
  {"role": "tool",      "tool_call_id": "call_1", "content": "{\"status\":\"杭州转运中心\"}"},
  {"role": "assistant", "content": "您的包裹在杭州转运中心，预计明天到。"},
  {"role": "user",      "content": "能加急吗"},
  {"role": "assistant", "content": "抱歉，中转中暂不支持加急。"},
  {"role": "user",      "content": "那到了提醒我"}
]
```

### 三个必须说清楚的点

1. **模型无状态** —— 每次请求带完整历史
2. **历史太长会超窗口** —— 需截断或压缩（删早期对话 / 生成摘要）
3. **tool role 必须和 tool_call_id 对应** —— 多工具时通过 id 关联

---

## 四、模型返回值数据结构

### 普通文本回答

```json
{
  "id": "chatcmpl-abc123",
  "model": "gpt-4o",
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": "您的包裹在杭州转运中心。"
    },
    "finish_reason": "stop"
  }],
  "usage": {
    "prompt_tokens": 56,
    "completion_tokens": 31,
    "total_tokens": 87
  }
}
```

### 工具调用

```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [{
        "id": "call_abc123",
        "type": "function",
        "function": {
          "name": "query_shipment",
          "arguments": "{\"tracking_no\": \"SF2024001\"}"
        }
      }]
    },
    "finish_reason": "tool_calls"
  }]
}
```

### 判断逻辑

```java
String finishReason = choices.get(0).getFinishReason();
if ("tool_calls".equals(finishReason)) {
    // 要调工具 → 取 tool_calls 数组执行
} else if ("stop".equals(finishReason)) {
    // 正常回答 → 取 content
}
```

### finish_reason 所有值

| 值 | 含义 | 处理方式 |
|---|------|---------|
| `stop` | 正常结束 | 取 content 作为最终回答 |
| `tool_calls` | 要调工具 | 取 tool_calls 执行工具 |
| `length` | max_tokens 截断 | 回答不完整，可追加请求 |
| `content_filter` | 内容安全过滤 | 告知用户，不要重试 |

### 流式模式下 tool_calls（分片到达，需拼接）

```
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","function":{"name":"query_"}}]}}]}
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"shipment"}}]}}]}
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"tracking_no\""}}]}}]}
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":": \"SF2024001\"}"}}]}}]}
data: [DONE]
```

服务端按 index 拼接 name 和 arguments，拼完整后再执行。

### 各厂商差异

| 厂商 | 差异点 |
|------|--------|
| **DeepSeek** | 兼容 OpenAI，多一个 `reasoning_content` 字段（思维链） |
| **豆包** | 兼容 OpenAI，model 用 endpoint ID（如 `ep-20240901001-xxxx`） |
| **智谱 GLM** | 基本兼容，`arguments` 偶尔返回对象而非字符串，需兼容处理 |
| **Claude** | **完全不同**：`tool_use`（不是 tool_calls）、`stop_reason`（不是 finish_reason）、`input`（不是 arguments，直接是对象） |

#### 智谱兼容处理

```java
JsonNode argsNode = functionNode.get("arguments");
String argsJson;
if (argsNode.isTextual()) {
    argsJson = argsNode.asText();       // 标准：字符串
} else {
    argsJson = argsNode.toString();     // 非标准：对象转字符串
}
```

---

## 五、SSE 流式原理与处理

### 为什么要流式

- 非流式：等 5 秒 → 一次性全出来 → 用户以为挂了
- 流式：0.3 秒开始逐字蹦出 → 打字机效果 → 体验好

### SSE 协议

```
HTTP/1.1 200 OK
Content-Type: text/event-stream

data: {"choices":[{"delta":{"content":"您"}}]}
data: {"choices":[{"delta":{"content":"的"}}]}
data: {"choices":[{"delta":{"content":"包裹"}}]}
data: [DONE]
```

### Java 三段转发链路

```
大模型 API →(WebClient/Flux)→ Java 服务 →(SseEmitter)→ 浏览器
```

#### 消费大模型流

```java
Flux<String> modelStream = webClient.post()
    .uri("https://api.deepseek.com/v1/chat/completions")
    .header("Authorization", "Bearer " + apiKey)
    .bodyValue(Map.of("model", "deepseek-chat", "messages", messages, "stream", true))
    .retrieve()
    .bodyToFlux(String.class);
```

#### 转发给前端（SseEmitter 方式）

```java
@GetMapping("/api/chat/stream")
public SseEmitter stream(@RequestParam String question) {
    SseEmitter emitter = new SseEmitter(60_000L);
    modelStream.subscribe(
        text -> emitter.send(SseEmitter.event().name("message").data(text)),
        emitter::completeWithError,
        emitter::complete
    );
    return emitter;
}
```

### SSE 工程问题

| 问题 | 解决方案 |
|------|---------|
| **连接中断** | 断点续传 / 前端 EventSource 自动重连 |
| **模型卡死** | 心跳检测，超过 15 秒无数据判定超时 |
| **用户关页面** | 捕获 IOException，取消上游连接，停止消耗 token |
| **Tool Call + 流式** | 服务端拼完整 tool_calls 再执行，中间给前端发状态事件 |

---

## 六、Function Calling / Tool Use

### 核心理解

**模型做决策，代码做执行。** 模型不直接执行工具，只返回调用指令。

### 完整流程

```
第一次请求：用户问题 + tools 定义
  ↓
模型返回：tool_calls（工具名 + 参数）
  ↓
你的代码：执行工具，拿到结果
  ↓
第二次请求：把工具结果（role:tool）追加到 messages
  ↓
模型返回：最终文字回答
```

### 面试要点

| 追问 | 回答 |
|------|------|
| 模型怎么知道调哪个工具 | 请求里传 tools 数组（名字+描述+参数 schema），模型按语义匹配 |
| 参数靠谱吗 | 不一定，JSON Schema 约束 + 服务端二次校验 |
| 一次调多个工具 | 可以，tool_calls 是数组，服务端可并行执行 |
| 安全问题 | 工具做权限控制，敏感操作需人工确认 |
| 工具太多选不准 | 根据意图动态筛选注入，控制在 5-8 个 |

---

## 七、Agent 智能体架构

### 一句话定义

**Agent = 大模型当大脑 + 代码当手脚 + 循环当驱动**

### 模式 1：ReAct（最常用）

```
Thought: 需要查物流数据        ← 推理
Action:  query_database(...)   ← 执行
Observation: [结果数据]         ← 观察
Thought: 数据够了，可以总结     ← 推理
Answer:  "活跃用户共3562人..."  ← 回答
```

Java 伪代码：

```java
for (int i = 0; i < MAX_ITERATIONS; i++) {
    ChatResponse response = llmClient.chat(messages, tools);
    if (response.hasToolCalls()) {
        messages.add(response.getAssistantMessage());
        for (ToolCall call : response.getToolCalls()) {
            String result = toolExecutor.execute(call);
            messages.add(Message.tool(call.id(), result));
        }
    } else {
        return response.getText();  // 任务完成
    }
}
```

### 模式 2：Plan-and-Execute

先做计划再分步执行，适合目标明确的多步任务。

### 模式 3：Multi-Agent

多个专业 Agent 协作（协调者 + 数据分析 Agent + 文案 Agent + 搜索 Agent）。

### Agent 五大核心组件

| 组件 | 作用 | Java 实现 |
|------|------|-----------|
| **工具系统** | 暴露给模型调用的方法 | ToolRegistry + @Tool |
| **上下文管理** | token 预算分配、历史压缩 | 滑动窗口 / 摘要压缩 / 分层保留 |
| **记忆系统** | 跨会话知识持久化 | 短期(messages) + 长期(向量DB+MySQL) |
| **向量数据库** | 语义检索 | Milvus / pgvector / Redis Stack |
| **安全管控** | 权限校验、参数校验、结果脱敏 | 工具执行管道 |

### Agent 防控措施

| 措施 | 具体做法 |
|------|---------|
| 预算控制 | 分配总 token 预算，每步扣减，不够直接终止 |
| 步数上限 | 最多 20 轮循环 |
| 连续失败 | 连续 3 次工具调用失败强制停止 |
| 渐进降级 | 预算紧张时：压缩历史 → 裁剪工具 → 跳过记忆 |

---

## 八、RAG 检索增强生成

### 核心思路

模型不知道企业私有数据 → 先从知识库里搜相关文档 → 塞进 prompt → 模型基于文档回答。

### 完整流程

```
离线（建索引）:
  原始文档 → 清洗 → 切片(500-1000字) → Embedding → 存向量库

在线（查询）:
  用户提问 → [Query增强] → Embedding → 向量库搜索(Top-K)
           → [Reranker精排] → 结果拼入 prompt → 模型回答
```

### Function Calling vs RAG 怎么选

| 场景 | 选什么 | 理由 |
|------|--------|------|
| 查物流状态 | Function Calling | 实时动态数据，要调接口 |
| 问退货政策 | RAG | 静态知识文档 |
| 问历史报价 | RAG + FC | RAG 检索历史报价文档，FC 查实时运力 |

---

## 九、向量检索详解

### Embedding

把文字变成一组浮点数（如 1536 维），语义相近的文字距离近。

```java
// Spring AI 一行代码
float[] vector = embeddingModel.embed("怎么退货");
```

### 主流 Embedding 模型

| 模型 | 维度 | 中文效果 | 特点 |
|------|------|---------|------|
| text-embedding-3-small | 1536 | 好 | 性价比高 |
| text-embedding-3-large | 3072 | 很好 | 精度高 |
| BGE-large-zh | 1024 | 很好 | 开源，中文首选 |
| 通义 text-embedding-v3 | 1024 | 很好 | 国内部署 |

### 文档切片策略

| 策略 | 适用场景 |
|------|---------|
| 固定长度（500-1000字，10-20%重叠） | 通用 |
| 按标题/段落 | 结构化文档 |
| 语义切片 | 精度要求高 |
| Parent-Child | 检索用子块保证精度，命中后取父块保证上下文 |

### 混合搜索

```
向量搜索（语义）→ "东西不想要了" 能找到 "退货政策"
关键词搜索（精确）→ "SF2024001" 精确匹配运单号
RRF 融合排序 → 两边结果合并，取最优
```

```java
// RRF 算法：每个结果得分 = Σ 1/(k + rank)，k=60
```

### Reranker

二阶段检索：向量搜索粗筛（Top-20）→ 交叉编码器精排（Top-5）。

### RAG 优化四板斧

1. 调 chunk_size 和 overlap
2. 加元数据过滤缩小范围
3. 加 Reranker 精排
4. 加关键词做混合搜索

---

## 十、Spring AI 详解

### 10.1 ChatClient（核心 API）

```java
// 同步调用
String answer = chatClient.prompt()
    .user("查一下 SF2024001")
    .call()
    .content();

// 流式调用
Flux<String> stream = chatClient.prompt()
    .user("查一下 SF2024001")
    .stream()
    .content();

// 结构化输出
OrderInfo info = chatClient.prompt()
    .user("查一下 SF2024001 的物流信息")
    .call()
    .entity(OrderInfo.class);
```

### 10.2 @Tool 工具定义

```java
public class ShipmentTools {
    @Tool(description = "根据运单号查询物流状态")
    public ShipmentInfo queryShipment(
            @ToolParam(description = "运单号") String trackingNo) {
        return shipmentService.query(trackingNo);
    }
}

// 使用
chatClient.prompt()
    .user("查一下 SF2024001")
    .tools(new ShipmentTools(shipmentService))
    .call().content();
// Spring AI 自动处理 ReAct 循环！
```

**原理：** 反射扫描 @Tool 方法 → 生成 JSON Schema → 拼进请求发给模型。**不是 AOP。**

### 10.3 ToolContext（传递模型不该看到的上下文）

```java
@Tool(description = "查询客户信息")
public Customer getCustomer(Long id, ToolContext context) {
    String tenantId = (String) context.getContext().get("tenantId");
    return customerRepo.findByIdAndTenant(id, tenantId);
}

chatClient.prompt().user("查42号客户")
    .tools(new CustomerTools())
    .toolContext(Map.of("tenantId", "acme"))  // 模型看不到
    .call().content();
```

### 10.4 Advisors（拦截器链）

类比 Servlet Filter / Spring Interceptor。

```java
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(chatMemory).build(),   // 对话记忆
        QuestionAnswerAdvisor.builder(vectorStore).build(),     // RAG
        new SimpleLoggerAdvisor()                                // 日志
    )
    .build();
```

| Advisor | 作用 |
|---------|------|
| `MessageChatMemoryAdvisor` | 自动管多轮对话历史 |
| `QuestionAnswerAdvisor` | 自动 RAG（检索+注入） |
| `ToolCallAdvisor` | 工具调用循环放进链里 |
| `SafeGuardAdvisor` | 内容安全检查 |
| `SimpleLoggerAdvisor` | 请求/响应日志 |

### 10.5 对话记忆

```java
ChatMemory chatMemory = MessageWindowChatMemory.builder()
    .maxMessages(20)
    .chatMemoryRepository(new InMemoryChatMemoryRepository())
    .build();

// 每次对话带 conversationId
chatClient.prompt().user("我叫张三")
    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "session-001"))
    .call().content();
```

存储后端：InMemory / JDBC / Cassandra / Neo4j / MongoDB / CosmosDB。

### 10.6 VectorStore

```java
// 存文档（自动 Embedding）
vectorStore.add(List.of(
    new Document("退货政策：7天内可退", Map.of("type", "policy"))
));

// 搜索（带元数据过滤）
vectorStore.similaritySearch(SearchRequest.builder()
    .query("怎么退货")
    .topK(5)
    .similarityThreshold(0.7)
    .filterExpression("type == 'policy'")
    .build());
```

### 10.7 多模型配置

```java
@Bean("deepseekClient")
ChatClient deepseekClient() {
    OpenAiApi api = new OpenAiApi("https://api.deepseek.com", key);
    OpenAiChatModel model = new OpenAiChatModel(api,
        OpenAiChatOptions.builder().model("deepseek-chat").build());
    return ChatClient.create(model);
}

@Bean("gpt4Client")
ChatClient gpt4Client() { /* 类似配置 */ }

// 按场景路由
ChatClient client = complex ? powerful : cheap;
```

### 10.8 框架对比

| 维度 | Spring AI | LangChain4j |
|------|-----------|-------------|
| 集成度 | 和 Spring Boot 无缝 | 独立库 |
| 灵活度 | 中等 | 高 |
| 选型 | Spring Boot 项目首选 | 需要细粒度控制 |
| 类比 | Spring Data JPA | MyBatis |

---

## 十一、熔断器机制

### 三个状态

```
CLOSED（正常放行） → 连续失败达阈值 → OPEN（全部拒绝）
       ↑                                    ↓
       └── 探测成功 ← HALF-OPEN（放一个试试）← 等待超时
```

### Agent 模型路由中的应用

```java
public class CircuitBreaker {
    private volatile State state = State.CLOSED;
    private final int failureThreshold = 3;    // 连续失败 3 次触发
    private final long waitDuration = 60_000;  // 熔断 60 秒后试探

    public boolean allowRequest() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> {
                if (System.currentTimeMillis() - lastFailureTime > waitDuration) {
                    state = State.HALF_OPEN;
                    yield true;  // 放一个试探
                }
                yield false;
            }
            case HALF_OPEN -> false;
        };
    }

    public void recordSuccess() { failureCount = 0; state = State.CLOSED; }
    public void recordFailure() { failureCount++; if (failureCount >= threshold) state = State.OPEN; }
}
```

### 配合模型路由

```java
public class ModelRouter {
    private final Map<String, CircuitBreaker> breakers; // 每个模型一个熔断器
    private final List<String> priority = List.of("deepseek", "doubao", "gpt4");

    public ChatResponse route(List<Message> messages) {
        for (String model : priority) {
            if (!breakers.get(model).allowRequest()) continue; // 熔断了，跳过
            try {
                ChatResponse resp = callModel(model, messages);
                breakers.get(model).recordSuccess();
                return resp;
            } catch (Exception e) {
                breakers.get(model).recordFailure();
            }
        }
        throw new AllModelsUnavailableException();
    }
}
```

### 参数经验值

| 参数 | 建议值 | 理由 |
|------|--------|------|
| 失败阈值 | 3-5 次 | 太小易误触，太大用户等太久 |
| 等待时间 | 30-60 秒 | 模型服务恢复通常分钟级 |
| 超时判定 | 10-15 秒 | 正常 2-5 秒，超过 10 秒基本异常 |

> **注意术语：** 熔断器"打开" = 断路（拒绝请求），"关闭" = 通路（正常放行）。

---

## 十二、Java 并发 × Agent 场景

### 场景速查表

| 场景 | 并发工具 | 一句话理由 |
|------|---------|-----------|
| Agent 会话异步执行 | CompletableFuture + DeferredResult | 释放 Tomcat 线程 |
| 多工具并行 + 等全部完成 | CompletableFuture.allOf | 并行提效 |
| 控制模型 API 并发数 | Semaphore | 防 429 限流 |
| 会话状态管理 | ConcurrentHashMap | 读多写少，分段锁 |
| Agent 取消 | volatile boolean | 跨线程可见性 |
| 高并发会话 | Virtual Thread | I/O 密集，轻量级 |
| 异步管线编排 | CF 链式调用 | 步骤串联 + 并行 |
| 熔断器状态保护 | ReentrantLock | 互斥写入 |
| SSE 推送缓冲 | BlockingQueue | 生产者-消费者解耦 |

### 场景 1：Agent 会话异步化

```java
@PostMapping("/chat")
public DeferredResult<AgentResponse> chat(@RequestBody ChatRequest request) {
    DeferredResult<AgentResponse> result = new DeferredResult<>(120_000L);
    CompletableFuture
        .supplyAsync(() -> agentLoop.run(request), agentExecutor)
        .thenAccept(result::setResult)
        .exceptionally(ex -> { result.setErrorResult(ex); return null; });
    return result;
}
```

### 场景 2：多工具并行执行

```java
List<CompletableFuture<ToolResult>> futures = toolCalls.stream()
    .map(call -> CompletableFuture.supplyAsync(() -> {
        try {
            return ToolResult.success(call.id(), toolExecutor.execute(call));
        } catch (Exception e) {
            return ToolResult.error(call.id(), e.getMessage()); // 不抛异常！
        }
    }, toolPool))
    .toList();

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
    .get(60, TimeUnit.SECONDS);
```

**容错策略：** 单个工具失败不取消其他工具，错误结果返回给模型让模型决定。

### 场景 3：Semaphore 控制并发

```java
private final Semaphore permits = new Semaphore(10);

public ChatResponse chat(List<Message> messages) {
    boolean acquired = permits.tryAcquire(5, TimeUnit.SECONDS);
    if (!acquired) throw new LlmBusyException("并发已满");
    try {
        return doChat(messages);
    } finally {
        permits.release();
    }
}
```

### 场景 4：超时层次设计

```
总 deadline = 120s
  ├── 每轮 LLM: min(30s, 剩余时间)
  └── 每个工具: min(10s, 剩余时间)
越到后面每步时间越紧，不会超总时间
```

### 场景 5：Virtual Thread

```java
// Agent 是 I/O 密集型（90% 时间等 LLM + 工具），虚拟线程完美适配
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

// Spring Boot 3.2+ 一行配置
spring.threads.virtual.enabled=true
```

### 场景 6：线程池隔离（防饥饿）

```java
// ❌ 混用一个池 → LLM 占满线程，工具全排队
// ✅ 按任务类型隔离
ExecutorService llmPool = Executors.newVirtualThreadPerTaskExecutor();
ExecutorService toolPool = Executors.newFixedThreadPool(cpuCores * 4);
ExecutorService contextPool = Executors.newFixedThreadPool(cpuCores);
```

### 场景 7：生产者-消费者（SSE 缓冲）

```java
BlockingQueue<SseEvent> buffer = new LinkedBlockingQueue<>(100);

// 生产者：Agent 往队列放事件
agentLoop.run(query, event -> buffer.offer(event, 5, TimeUnit.SECONDS));

// 消费者：从队列取事件推给前端
while (true) {
    SseEvent event = buffer.poll(30, TimeUnit.SECONDS);
    if (event == null) { emitter.send(ping); continue; }  // 心跳保活
    if (event == DONE) { emitter.complete(); return; }
    emitter.send(event);
}
```

### 死锁排查

```bash
jstack <pid> > thread_dump.txt
grep -A 5 "BLOCKED" thread_dump.txt
grep -A 20 "deadlock" thread_dump.txt
```

Agent 最常见的"死锁"：线程池饥饿（Agent 和工具共用池）。解决：池隔离。

### 面试追问速答

| 追问 | 回答 |
|------|------|
| thenApply vs thenApplyAsync | thenApply 在上一步线程执行，thenApplyAsync 提交到线程池。耗时操作用 Async |
| CountDownLatch vs CF.allOf | 都能并行等待，CF.allOf 链式 API 更清晰，推荐 |
| volatile vs AtomicBoolean | 一写一读用 volatile，多写竞争用 AtomicBoolean |
| Semaphore vs 线程池 maxSize | 线程池限线程数，Semaphore 限资源访问数，不同维度 |
| ReentrantLock vs synchronized | 简单场景 synchronized 够用，需要超时/公平锁/Condition 用 ReentrantLock |
| ConcurrentHashMap vs HashMap+sync | CHM 分段锁读几乎不加锁，HashMap+sync 锁整个 Map |

---

## 十三、面试高频问答

### Q：大模型 API 和传统 API 有什么区别？

> "本质都是 HTTP 接口调用，区别在于：一是入参是 messages 数组而不是结构化参数，二是模型无状态每次要带完整上下文，三是响应可能是流式的（SSE），四是耗时更长（秒级），五是有 token 计费。"

### Q：Function Calling 和 ReAct 的区别？

> "Function Calling 是模型原生的结构化工具调用能力，模型返回 tool_calls 结构。ReAct 是一种 Agent 架构模式——用 Thought/Action/Observation 循环，可以基于 Function Calling 实现，也可以用 prompt 工程实现。现代系统一般结合：用 Function Calling 做工具调用，用 ReAct 循环做多步编排。"

### Q：怎么让模型回答企业内部数据？

> "两种方式：实时数据用 Function Calling 调业务接口查询；静态知识用 RAG——文档切片→Embedding→向量库存储，查询时向量搜索→结果注入 prompt→模型基于资料回答。"

### Q：怎么防止 prompt 注入？

> "三层防护：一是 system 和 user 严格分离设指令优先级，二是输入层做注入模式检测，三是输出层校验确保没有越权操作。"

### Q：怎么控制 AI 调用成本？

> "四个层面：模型分级（简单任务用便宜模型），语义缓存（相似问题复用答案），prompt 精简（减少输入 token），按租户设配额和告警。"

### Q：你在 Agent 项目中遇到的最难的问题是什么？

> **建议准备的方向：** 多模型路由的容错设计 / 上下文窗口管理的精细化 / 工具失败后的 Agent 行为控制

---

## 十四、面试速查卡

```
=== 必须脱口而出 ===

大模型 = 无状态 HTTP 接口，每次带全量上下文
Token = 计费和限制的最小单位
messages 四种 role = system / user / assistant / tool
finish_reason = "stop"(回答) / "tool_calls"(调工具) / "length"(截断)
tool_calls 结构 = { id, function: { name, arguments(字符串!) } }
SSE = text/event-stream，delta 增量推送，[DONE] 结束
Function Calling = 模型做决策，代码做执行
Agent = ReAct 循环（想→做→看→想→...）
RAG = 先检索再生成，让模型能回答私有数据问题
防失控 = 最大轮次 + token 预算 + 失败阈值
熔断器 = CLOSED → OPEN → HALF-OPEN，三个状态

=== Spring AI ===

ChatClient = 调模型（类比 RestTemplate）
@Tool = 声明工具（类比 @RequestMapping）
Advisor = 拦截器链（类比 Filter）
call() 内部自动做 ReAct 循环
工具注入原理 = 反射 + 序列化，不是 AOP

=== 并发 ===

线程池必须隔离（LLM 池 / 工具池 / CPU 池）
工具失败用容错策略（不是快速失败）
超时分层：总 deadline > 单次 LLM > 单个工具
Agent = I/O 密集 → 虚拟线程
取消 = volatile boolean，每轮循环检查

=== 业务判断 ===

实时数据 → Function Calling
静态知识 → RAG
两者结合 → Agent 编排
```
