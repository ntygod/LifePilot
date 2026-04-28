---
title: LLM 适配器层重构 — Provider Profile 数据驱动 + 推理模型支持 + 模型探测
status: design
owner: zsg
date: 2026-04-27
scope: 把 LLM 适配器从"单类内 host 字符串分流"重构为"ProviderProfile 数据驱动 + Adapter 多态"，原生支持推理模型（DeepSeek V4 / Qwen3 / OpenAI o-系列 / Claude thinking），统一流式事件流，整合 /v1/models 探测式模型配置。项目级工作区改造与沙箱 Docker 模式不在本 spec 范围。
---

# LLM 适配器层重构 — Provider Profile 数据驱动 + 推理模型支持 + 模型探测

## 0. 一句话说明

把现有 `SpringAiProviderAdapter` 单类 + host 字符串分流的 LLM 适配层，重构为 `ProviderProfile`（数据驱动协议特性描述）+ Adapter 多态（每家 provider 自治）+ `LlmStreamEvent` 流式事件流的新架构，**原生支持推理模型多轮契约 + Anthropic thinking block + 流式 reasoning UI**，并把"探测式模型配置"作为同一场景的子模块合入。

## 1. 背景与动机

### 1.1 当前现状

LLM 适配层（`com.lifepilot.llm`）目前的形态：

1. **`SpringAiProviderAdapter` 是统一适配器**（不是基类），靠 `ChatModel` 实例（`OpenAiChatModel` / `AnthropicChatModel` / `OllamaChatModel`）区分行为
2. **`ProviderType` 枚举只有 4 个**：`OLLAMA` / `TEI` / `OPENAI_COMPATIBLE` / `ANTHROPIC`。DeepSeek、Qwen、OpenAI 官方、火山、智谱、月之暗面等全部挤在 `OPENAI_COMPATIBLE` 里，靠 `ProviderChatOptionsFactory` 内部 host 字符串（`api.deepseek.com` / `dashscope.aliyuncs.com` / `api.openai.com`）分流到不同结构化输出模式
3. **`LlmResponse` 是贫血字段**：仅含 content + tokens + providerId + modelName + latency + cached，丢失了响应中的 `reasoning_content` / `tool_calls` / `cache_stats` / 厂商私有元数据
4. **流式只产 `Flux<String>`**：`StreamingLlmResponse.stream()` 只 filter 文本 chunk，丢掉所有非文本事件（reasoning chunk / tool_call delta / usage delta）
5. **多轮 history 不带 provider awareness**：`agent/ReactAgentLoop` 走 Spring AI ChatMemory 默认行为，没有按 provider 协议决定回传哪些字段
6. **PromptCacheStrategy 模式已存在**：`AbstractJsonBodyRewritingStrategy` 已是重写请求 JSON body 的基类，DashScope cache 走这条路，本 spec 复用

### 1.2 触发：DeepSeek V4 多轮 400

2026-04-27 实测发现，配 DeepSeek-V4-Pro 作为主对话模型时：

- 第 1 轮 LLM 调用成功（无历史）
- 第 2 轮 ReAct 调用 DeepSeek 时返回 400：
  ```
  {"error":{"message":"The `reasoning_content` in the thinking mode must be passed back to the API."}}
  ```
- 连续失败 3 次达上限，agent 中断

根因：DeepSeek V4 系列是推理模型，响应里带 `reasoning_content` 字段，多轮契约要求**上一轮 assistant 的 reasoning_content 必须原样回传**给下一轮 API 请求。当前适配器只解析并保存 `content`，多轮调用 messages 数组不携带 reasoning_content → DeepSeek 拒绝。

类似问题还有：

- Qwen3 系列响应也带 `reasoning_content`，需要回传契约
- Anthropic Claude 4.6/4.7 的 Extended Thinking 走 thinking block，签名验证不能丢
- OpenAI o1/o3/GPT-5 走 `reasoning.effort` 参数，响应不返回 reasoning（不需要回传，但需要参数下发）

### 1.3 调研：业界做法

| 工具 | 推理模型识别策略 | 配置归属 |
|---|---|---|
| **LiteLLM** | 中心 model cost map → 按 model name 自动匹配 `supports_reasoning`；用户可在 `model_info` 手动覆盖 | 内置表 + 用户覆盖 |
| **OpenRouter** | 把 thinking 做成模型名变体（`xxx:thinking` vs `xxx`），同一底模两条 model id | 模型名命名约定 |
| **Open WebUI** | 后置识别：不管模型，看响应有没有 `reasoning_content` / `reasoning` / `thinking` 字段或 `<think>` 标签 | 按响应字段后验 |
| **Cline / Continue** | 不维护通用清单，让用户挑 provider，按 provider SDK 行为走 | 委托 provider |

调研结论：业界没有完美方案，主流是**先验（识别表）+ 后验（响应字段）混合**。本 spec 取一个简化路线（见 1.4）。

### 1.4 目标

- **推理模型主流场景全打通**：DeepSeek V4 / Qwen3 / OpenAI o-系列 / Anthropic Claude 4.x thinking 多轮对话不 400
- **架构统一可扩展**：协议特性数据驱动，新增 provider 不改主流程，只新增 ProviderProfile + 必要时一个 Adapter 子类
- **流式 UI 信息完整**：用户能实时看到推理过程，完成后折叠
- **历史会话可回看**：reasoning_content 落库，下次进来仍可展开
- **探测式模型配置一并落地**：用户接入新模型从"手填模型名"变成"挑 profile → 填 baseUrl/key → 拉 /v1/models → 选 model"
- **healthCheck 不再误判推理模型**：从 chat ping 改走 /v1/models（毫秒级，不耗 token）

### 1.5 不做的事（明确边界）

- ❌ **后置识别**（按响应字段自动判断是否推理）—— 选了纯人肉勾 `is_reasoning`，简单透明，配错由用户负责
- ❌ **内置推理模型识别表**（按 modelName 模糊匹配预填）—— 老板拍板"算了，简单的开关，交给用户自己判断"
- ❌ **`OpenAiCompatibleGenericProviderAdapter` 兜底**（未识别 host 走 generic 行为）—— 用户必须主动挑 profile，不留兜底
- ❌ **数据迁移**（`UPDATE model_service SET profile_id = ...` 按 host 推断老配置）—— 项目未上线，开发库重建，用户重新配
- ❌ **`@Deprecated providerType`** 保留 —— 不留兼容字段，重写一步到位
- ❌ **多模态扩展**（VISION / NATIVE_AUDIO / NATIVE_VIDEO）—— Profile 字段保留位置，本 PR 不展开
- ❌ **AI 智能 thinking_mode=auto**（短问题禁用、长问题启用）—— `auto` 等同"不下发字段，用 provider 默认"，简单语义
- ❌ **ChatHistoryAssembler 性能缓存** —— YAGNI，等真发现瓶颈再加
- ❌ **项目级工作区改造** —— 独立 spec，本 spec 完成后做
- ❌ **沙箱 Docker 模式** —— 独立 spec
- ❌ **浏览器能力补全** —— 独立 spec

## 2. 设计原则

1. **数据驱动**：协议差异由 `ProviderProfile` record 描述，不在代码里 if-else 散落
2. **适配器多态**：每家有自己的 Adapter 子类，自治；公共逻辑在抽象基类
3. **类型安全**：流式事件用 sealed interface，响应用富字段 record
4. **无兜底，用户主动选**：UI 强制挑 profile，配错由用户负责（项目未上线）
5. **测试驱动**：每家 protocol 一份 contract test（请求构造 + 响应解析 + 多轮回传 三件套）
6. **复用现有基础设施**：`AbstractJsonBodyRewritingStrategy` 是已有的请求 JSON body 重写基类，thinking 字段注入沿用同一模式

## 3. 架构总览

### 3.1 模块拓扑

```
com.lifepilot.llm/
├── profile/                                    ← 新增：协议特性数据驱动层
│   ├── ProviderProfile.java                    （record，描述协议所有维度）
│   ├── ProviderProfileRegistry.java            （内置 profile 表）
│   └── BuiltinProviderProfiles.java            （deepseek-official / qwen-dashscope / openai-official / anthropic-official / ollama-local / tei-local / 其他）
│
├── thinking/                                   ← 新增：思考模式协议层
│   ├── ThinkingProtocol.java                   （接口）
│   ├── DeepSeekThinkingProtocol.java
│   ├── QwenThinkingProtocol.java
│   ├── OpenAiReasoningEffortProtocol.java
│   ├── AnthropicThinkingProtocol.java
│   └── NoopThinkingProtocol.java
│
├── adapter/                                    ← 重写：从单类拆成多态
│   ├── ProviderAdapter.java                    （接口，扩展返回类型）
│   ├── AbstractProviderAdapter.java            （新增：timeout / advisor / 媒体共享）
│   ├── OpenAiBaseProviderAdapter.java          （新增：DeepSeek/Qwen/OpenAi 共享 OpenAI SDK 路径）
│   ├── DeepSeekProviderAdapter.java
│   ├── QwenProviderAdapter.java
│   ├── OpenAiOfficialProviderAdapter.java
│   ├── AnthropicProviderAdapter.java
│   ├── OllamaProviderAdapter.java
│   └── ProviderAdapterFactory.java             （改写：按 ProviderProfile.id 路由 → 具体子类）
│
├── stream/                                     ← 新增：流式事件流
│   ├── LlmStreamEvent.java                     （sealed interface）
│   ├── ReasoningChunk.java
│   ├── ContentChunk.java
│   ├── ToolCallDelta.java
│   ├── UsageEvent.java
│   ├── DoneEvent.java
│   └── ErrorEvent.java
│
├── history/                                    ← 新增：多轮 history 装载
│   └── ChatHistoryAssembler.java               （按 ProviderProfile.historyRules 序列化 history）
│
├── cache/                                      ← 保留，不动
├── circuit/                                    ← 保留，不动
├── multimodal/                                 ← 保留，不动
├── registry/                                   ← 保留，ProviderRegistry 内部按 profileId 分发
│
├── LlmResponse.java                            ← 重写：富字段化
├── StreamingLlmResponse.java                   ← 重写：包装 Flux<LlmStreamEvent>
└── config/
    ├── ProviderType.java                       ← 简化：仅作为 baseAdapter 类型标记
    ├── ProviderConfig.java                     ← 改：profileId 字段替代 host 猜测
    └── ...
```

### 3.2 影响半径

| 模块 | 动作 |
|---|---|
| `llm/adapter/`、`llm/profile/`、`llm/thinking/`、`llm/stream/`、`llm/history/` | 重写或新增 |
| `LlmResponse` / `StreamingLlmResponse` | 重写 |
| `ProviderType` / `ProviderConfig` | 改 |
| `llm/cache/`（含 `AbstractJsonBodyRewritingStrategy`） | 复用 |
| `llm/circuit/` / `llm/multimodal/` / `llm/registry/` | 不动 |
| `agent/callback/`（StreamingCallback / NonStreamingCallback） | 改：从 String chunks 改为 LlmStreamEvent 分发 |
| `generation/` / `embedding/` / `rerank/` 路由层 | 小改：响应类型升级 |
| `agent/ReactAgentLoop` / `ChatService` / `WorkflowEngine` | 改：消费富 LlmResponse / 处理 reasoning 多轮 |
| `session_transcript_entries.payload_json` schema | 扩展（无表迁移） |
| `model_service` 表 | V32：DROP + CREATE 重建 |
| 前端 `views/settings/ModelService*` | 改：挑 profile UI |
| 前端 chat / workflow SSE 解析 | 改：处理多事件类型 |

预估 **35-45 个文件**改动，新增 **~15 个**新文件。

## 4. ProviderProfile 数据驱动

### 4.1 ProviderProfile record

```java
public record ProviderProfile(
    String id,                              // 唯一标识：deepseek-official / qwen-dashscope / ...
    String displayName,                     // UI 展示名："DeepSeek 官方" / "通义千问 (DashScope)" / ...
    BaseAdapterType baseAdapter,            // OPENAI_BASE | ANTHROPIC_BASE | OLLAMA | TEI
    String defaultBaseUrl,                  // 内置默认 baseUrl，UI 自动预填，用户可改（自部署）
    ThinkingProtocolId thinkingProtocol,    // DEEPSEEK | QWEN | OPENAI_REASONING_EFFORT | ANTHROPIC | NONE
    StructuredOutputMode structuredOutput,  // JSON_SCHEMA | JSON_OBJECT | PROMPT_ONLY
    PromptCacheStrategyId cacheStrategy,    // ANTHROPIC_EPHEMERAL | DASHSCOPE_EXPLICIT | OPENAI_AUTO | NOOP
    ModelDiscoveryEndpoint modelDiscovery,  // /v1/models 协议描述
    Set<ProviderCapability> capabilities,   // CHAT / EMBEDDING / RERANK / VISION / NATIVE_AUDIO / NATIVE_VIDEO
    MultiTurnHistoryRules historyRules      // 多轮回传规则
) {}

public enum BaseAdapterType { OPENAI_BASE, ANTHROPIC_BASE, OLLAMA, TEI }
public enum ThinkingProtocolId { DEEPSEEK, QWEN, OPENAI_REASONING_EFFORT, ANTHROPIC, NONE }
public enum StructuredOutputMode { JSON_SCHEMA, JSON_OBJECT, PROMPT_ONLY }
public enum PromptCacheStrategyId { ANTHROPIC_EPHEMERAL, DASHSCOPE_EXPLICIT, OPENAI_AUTO, NOOP }

public record ModelDiscoveryEndpoint(
    String path,                            // 默认 "/v1/models"
    String authHeaderName,                  // 默认 "Authorization"
    String authHeaderFormat,                // "Bearer ${apiKey}" / "${apiKey}"
    String responseModelsJsonPath,          // 默认 "$.data[*].id"
    @Nullable String responseModelNameJsonPath  // null 表示用 id 当 name
) {}

public record MultiTurnHistoryRules(
    boolean injectReasoning,                // 是否回传 reasoning_content
    boolean injectReasoningSignature,       // 是否回传 reasoning_signature（Anthropic）
    boolean injectToolCalls,                // 是否回传 tool_calls 元数据
    ReasoningInjectionFormat format         // CONTENT_ONLY / EXTRA_BODY / THINKING_BLOCK
) {}

public enum ReasoningInjectionFormat { CONTENT_ONLY, EXTRA_BODY, THINKING_BLOCK }
```

### 4.2 内置 Profile 表

| profileId | baseAdapter | thinkingProtocol | structuredOutput | cacheStrategy | historyRules |
|---|---|---|---|---|---|
| `deepseek-official` | OPENAI_BASE | DEEPSEEK | JSON_OBJECT | NOOP | injectReasoning=true, format=CONTENT_ONLY |
| `qwen-dashscope` | OPENAI_BASE | QWEN | JSON_SCHEMA | DASHSCOPE_EXPLICIT | injectReasoning=true, format=CONTENT_ONLY |
| `openai-official` | OPENAI_BASE | OPENAI_REASONING_EFFORT | JSON_SCHEMA | OPENAI_AUTO | injectReasoning=false |
| `anthropic-official` | ANTHROPIC_BASE | ANTHROPIC | JSON_SCHEMA | ANTHROPIC_EPHEMERAL | injectReasoning=true, injectReasoningSignature=true, format=THINKING_BLOCK |
| `ollama-local` | OLLAMA | NONE | PROMPT_ONLY | NOOP | injectReasoning=false |
| `tei-local` | TEI | NONE | PROMPT_ONLY | NOOP | N/A |
| `volcengine-ark` | OPENAI_BASE | DEEPSEEK | JSON_OBJECT | NOOP | injectReasoning=true, format=CONTENT_ONLY |
| `zhipu-bigmodel` | OPENAI_BASE | NONE | JSON_OBJECT | NOOP | injectReasoning=false |
| `moonshot-kimi` | OPENAI_BASE | NONE | JSON_OBJECT | NOOP | injectReasoning=false |
| `minimax-text` | OPENAI_BASE | NONE | JSON_OBJECT | NOOP | injectReasoning=false |
| `siliconflow` | OPENAI_BASE | NONE | JSON_OBJECT | NOOP | injectReasoning=false |

**自部署兼容服务**（vLLM / LM Studio / Xinference）：用户挑最接近协议的 profile，改 baseUrl 即可，不需单独 profile。

### 4.3 Provider Profile Registry

```java
@Component
public class ProviderProfileRegistry {
    private final Map<String, ProviderProfile> profilesById;

    @PostConstruct
    void load() {
        profilesById = BuiltinProviderProfiles.all().stream()
            .collect(Collectors.toUnmodifiableMap(ProviderProfile::id, p -> p));
    }

    public ProviderProfile get(String id) {
        var profile = profilesById.get(id);
        if (profile == null) {
            throw new IllegalStateException("未知的 ProviderProfile id: " + id);
        }
        return profile;
    }

    public List<ProviderProfile> all() { return List.copyOf(profilesById.values()); }
}
```

`BuiltinProviderProfiles.all()` 是代码常量列表，**不进 DB**（避免迁移耦合，便于随版本演进）。

## 5. ThinkingProtocol 协议层

### 5.1 接口

```java
public interface ThinkingProtocol {
    ThinkingProtocolId id();

    /** 把 thinking_mode 转换成厂商私有字段，注入 ChatOptions 或请求 JSON body */
    void applyToRequest(RequestBuilder builder, ThinkingMode mode);

    /** 从响应（同步 / 流式 chunk）提取 reasoning_content，可能为 null */
    @Nullable String extractReasoning(JsonNode rawResponseChunk);

    /** 从响应提取 signature（仅 Anthropic 使用，其他实现返回 null） */
    @Nullable default String extractReasoningSignature(JsonNode rawResponseChunk) { return null; }

    /** 多轮回传：上一轮 assistant 的 reasoning 注入到 history message */
    void injectHistoryReasoning(AssistantMessageBuilder msgBuilder, ChatMessage prevAssistantMsg);
}

public enum ThinkingMode { AUTO, ENABLED, DISABLED }
```

`RequestBuilder` 是适配器侧新增的可变请求构造抽象（`com.lifepilot.llm.adapter.RequestBuilder`），封装：

- ChatOptions builder（OpenAiChatOptions / AnthropicChatOptions）— 适用于 SDK 原生支持的字段
- 厂商私有 extra_body Map（DeepSeek `thinking` / Qwen `chat_template_kwargs` 等字段）— 注入手段是 RestClient 拦截器重写 JSON body，复用 `AbstractJsonBodyRewritingStrategy`

ThinkingProtocol 实现按需写入合适分支：原生字段调 ChatOptions builder API；私有字段塞进 extra_body Map，由 OpenAiBaseProviderAdapter 在请求阶段统一通过拦截器合并到出站 JSON。

`AssistantMessageBuilder` 同样是适配器侧抽象（`com.lifepilot.llm.history.AssistantMessageBuilder`），由 `ChatHistoryAssembler` 组合使用，按 ReasoningInjectionFormat 决定输出形态（CONTENT_ONLY / EXTRA_BODY / THINKING_BLOCK）。

`ChatMessage` 在本 spec 是上层抽象概念，运行时**不存在该 Java 类**：实际由 `SessionTranscriptEntry` 加载、`payload_json` 反序列化为 `Map<String, Object>` / 专用 record 后传入 `ChatHistoryAssembler`。Adapter 层只感知 `ProviderMessage`（适配器内部消息类型）。

### 5.2 各实现

| Protocol | applyToRequest | extractReasoning | injectHistoryReasoning |
|---|---|---|---|
| **DeepSeekThinkingProtocol** | mode=ENABLED → `extra_body.thinking.type = "enabled"`；DISABLED → `"disabled"`；AUTO → 不下发字段 | 解析 `choices[].delta.reasoning_content` 或 `choices[].message.reasoning_content` | assistant message 加 `reasoning_content` 字段 |
| **QwenThinkingProtocol** | mode=ENABLED → `chat_template_kwargs.enable_thinking = true`；DISABLED → `false`；AUTO → 不下发 | 同 DeepSeek | 同 DeepSeek（保守起见也回传） |
| **OpenAiReasoningEffortProtocol** | mode=ENABLED → `reasoning.effort = "medium"`；DISABLED → `"none"`（仅部分模型支持）；AUTO → 不下发 | 始终返回 null（OpenAI 不返回 reasoning） | 不操作（无需回传） |
| **AnthropicThinkingProtocol** | mode=ENABLED → `thinking = {type:"enabled", budget_tokens:8192}`；DISABLED → `{type:"disabled"}`；AUTO → 4.7+ 走 `{type:"adaptive"}`，旧版降级 enabled | 解析 `content[].type == "thinking"` 块的 `thinking` 字段 | thinking block 整体回传到 content array 第一块（含 signature） |
| **NoopThinkingProtocol** | 不操作 | null | 不操作 |

### 5.3 注入手段分类

- **ChatOptions 原生支持**：OpenAI `reasoning.effort`、Anthropic `thinking` → 通过 `OpenAiChatOptions.builder().reasoningEffort(...)` 等
- **ChatOptions 不支持的私有字段**：DeepSeek `extra_body.thinking`、Qwen `chat_template_kwargs` → 复用 `AbstractJsonBodyRewritingStrategy`，写 RequestInterceptor 在 HTTP 层重写 JSON body

## 6. Adapter 多态层级

### 6.1 类层级

```
ProviderAdapter (interface)
└── AbstractProviderAdapter
    ├── OpenAiBaseProviderAdapter         // OpenAI SDK 路径共享
    │   ├── DeepSeekProviderAdapter       // thinking 协议特殊（extra_body）
    │   ├── QwenProviderAdapter           // thinking 协议特殊（chat_template_kwargs）
    │   └── OpenAiOfficialProviderAdapter // thinking 协议特殊（reasoning.effort）
    ├── AnthropicProviderAdapter
    └── OllamaProviderAdapter
```

**首批仅 5 个具体 Adapter 子类**。`zhipu-bigmodel` / `moonshot-kimi` / `minimax-text` / `siliconflow` / `volcengine-ark` 等 profile **直接复用 `OpenAiBaseProviderAdapter`**（thinking_protocol = NONE 或与 DeepSeek 同协议），只需新增 ProviderProfile，不需要新 Adapter 类。判定原则：

- 若 profile.thinkingProtocol 与已有 Adapter 子类相同，直接复用 OpenAiBaseProviderAdapter + 该 ThinkingProtocol
- 若 profile 引入全新协议处理（独有 streaming 字段位、独有错误码处理等），才新增 Adapter 子类

`volcengine-ark` 走 DEEPSEEK 协议 → 复用 OpenAiBaseProviderAdapter + DeepSeekThinkingProtocol，不需 VolcengineArkProviderAdapter。

### 6.2 责任划分

| 层 | 职责 |
|---|---|
| `AbstractProviderAdapter` | timeout 控制（virtual thread executor）、advisor 链管理、媒体处理基础（Media / MimeType）、call/stream 模板方法 |
| `OpenAiBaseProviderAdapter` | OpenAI SDK 路径（OpenAiApi / OpenAiChatModel / OpenAiChatOptions）、流式事件分派（`Flux<ChatResponse>` → `Flux<LlmStreamEvent>`）；持有注入的 ThinkingProtocol，解析阶段调用其 extract 方法 |
| 各具体 Adapter（DeepSeek / Qwen / OpenAi / Anthropic / Ollama） | 重写 ChatOptions 构造、特殊字段注入、协议私有错误码处理；非特殊 provider 直接用 OpenAiBaseProviderAdapter |
| `ProviderAdapterFactory` | `create(ProviderConfig)`：查 Registry 拿 Profile → 按 `profile.baseAdapter` + `profile.thinkingProtocol` 决定实例化哪个 Adapter 类 → 注入对应 ThinkingProtocol / CacheStrategy |

## 7. 数据模型

### 7.1 LlmResponse（重写）

```java
public record LlmResponse(
    String content,
    @Nullable String reasoningContent,
    @Nullable String reasoningSignature,         // Anthropic thinking block sign
    List<ToolCall> toolCalls,
    Map<String, Object> providerMetadata,        // 厂商私有字段，多轮回传需要
    int inputTokens,
    int outputTokens,
    @Nullable Integer reasoningTokens,           // OpenAI 等返回的 reasoning token 计费
    int cachedInputTokens,                       // prompt cache 命中量
    String providerId,
    String modelName,
    long latencyMs,
    boolean cached
) {
    public int totalTokens() {
        return inputTokens + outputTokens + Optional.ofNullable(reasoningTokens).orElse(0);
    }

    public static LlmResponse cached(String content, String providerId, String modelName) {
        return new LlmResponse(content, null, null, List.of(), Map.of(), 0, 0, null, 0, providerId, modelName, 0, true);
    }
}

public record ToolCall(String id, String name, String argumentsJson) {}
```

### 7.2 流式事件流（重写）

```java
public sealed interface LlmStreamEvent
    permits ReasoningChunk, ContentChunk, ToolCallDelta, UsageEvent, DoneEvent, ErrorEvent {}

public record ReasoningChunk(String delta, @Nullable String signature) implements LlmStreamEvent {}
public record ContentChunk(String delta) implements LlmStreamEvent {}
public record ToolCallDelta(int index, @Nullable String id, @Nullable String name, String argumentsDelta) implements LlmStreamEvent {}
public record UsageEvent(int inputTokens, int outputTokens, @Nullable Integer reasoningTokens, int cachedInputTokens) implements LlmStreamEvent {}
public record DoneEvent(@Nullable String finishReason) implements LlmStreamEvent {}
public record ErrorEvent(String code, String message) implements LlmStreamEvent {}

public record StreamingLlmResponse(
    Flux<LlmStreamEvent> events,
    String providerId,
    String modelId
) {}
```

`agent/callback/StreamingCallback` 改成消费 `LlmStreamEvent`，按 sealed interface pattern matching 分派：

```java
events.subscribe(ev -> {
    switch (ev) {
        case ReasoningChunk r -> ssePush("reasoning", r);
        case ContentChunk c -> ssePush("content", c);
        case ToolCallDelta t -> aggregateToolCall(t);
        case UsageEvent u -> recordUsage(u);
        case DoneEvent d -> finalizeTurn(d);
        case ErrorEvent e -> propagateError(e);
    }
});
```

### 7.3 session_transcript_entries.payload_json schema 扩展

**无 DB 迁移**。assistant 消息的 `payload_json` 加可选字段：

```json
{
  "role": "assistant",
  "content": "...",
  "reasoning_content": "...",
  "reasoning_signature": "...",
  "tool_calls": [...],
  "provider_metadata": { "thinking_signature": "...", "deepseek_extra": {} },
  "model_id": "...",
  "provider_id": "...",
  "thinking_mode_used": "enabled",
  "tokens": { "input": 0, "output": 0, "reasoning": 0, "cached_input": 0 }
}
```

`ChatHistoryAssembler` 装载 history 时按当前 provider 的 `MultiTurnHistoryRules` 决定要不要把 reasoning_content / reasoning_signature 拼回去。

FTS5 索引 `session_transcript_entries_fts.content` 暂不收 reasoning（reasoning 是辅助信息，全文搜索默认按用户内容；将来如果用户反馈想搜 reasoning，再加 `reasoning` 字段到 FTS）。

### 7.4 model_service 表（V32 迁移）

```sql
-- V32__model_service_provider_profile.sql
-- 项目未上线，直接重建 model_service 表，不做数据迁移

DROP TABLE IF EXISTS model_service;

CREATE TABLE model_service (
    id                            TEXT PRIMARY KEY,
    kind                          TEXT NOT NULL,
    profile_id                    TEXT NOT NULL,
    api_url                       TEXT NOT NULL,
    api_key                       TEXT,
    model_name                    TEXT NOT NULL,
    timeout_seconds               INTEGER NOT NULL DEFAULT 30,
    priority                      INTEGER NOT NULL DEFAULT 0,
    enabled                       INTEGER NOT NULL DEFAULT 1,
    is_reasoning                  INTEGER NOT NULL DEFAULT 0,
    thinking_mode                 TEXT    NOT NULL DEFAULT 'auto',
    supported_scenes_json         TEXT NOT NULL DEFAULT '[]',
    generation_capabilities_json  TEXT NOT NULL DEFAULT '[]',
    metadata_json                 TEXT NOT NULL DEFAULT '{}',
    display_name                  TEXT,
    description                   TEXT,
    created_at                    TEXT NOT NULL,
    updated_at                    TEXT NOT NULL
);

CREATE INDEX idx_model_service_kind     ON model_service(kind, enabled);
CREATE INDEX idx_model_service_profile  ON model_service(profile_id);
```

### 7.5 ModelServiceEntity（重写）

```java
public record ModelServiceEntity(
    String id,
    ModelServiceKind kind,
    String profileId,                            // 必填，UI 强制选
    String apiUrl,
    @Nullable String apiKey,
    String modelName,
    int timeoutSeconds,
    int priority,
    boolean enabled,
    boolean isReasoning,
    ThinkingMode thinkingMode,
    List<String> supportedScenes,
    Set<GenerationCapability> generationCapabilities,
    Map<String, Object> metadata,
    @Nullable String displayName,
    @Nullable String description
) {
    public ModelServiceEntity {
        Objects.requireNonNull(id, "服务 ID 不能为空");
        Objects.requireNonNull(kind, "服务类型不能为空");
        Objects.requireNonNull(profileId, "Profile ID 不能为空");
        Objects.requireNonNull(apiUrl, "API 地址不能为空");
        Objects.requireNonNull(modelName, "模型名称不能为空");
        Objects.requireNonNull(thinkingMode, "thinking_mode 不能为空");
        // ...
    }
}
```

## 8. 数据流与多轮契约

### 8.1 单轮调用数据流（同步）

```
ChatService / ReactAgentLoop
  │  prompt + history (List<ChatMessage>)
  ▼
ChatHistoryAssembler.assemble(history, profile.historyRules)
  │  List<ProviderMessage>
  ▼
ProviderAdapter.call(messages, options)
  │
  ├──→ ThinkingProtocol.applyToRequest(builder, mode)        // ChatOptions 原生字段
  ├──→ AbstractJsonBodyRewritingStrategy 注入 extra_body     // OpenAI SDK 不支持的私有字段
  │
  ▼
HTTP RestClient → 厂商 API
  │  raw JSON response
  ▼
ProviderAdapter.parseResponse(rawJson)
  │
  ├──→ ThinkingProtocol.extractReasoning(json) → reasoning?
  ├──→ ThinkingProtocol.extractReasoningSignature(json) → sig?
  │
  ▼
LlmResponse（content + reasoningContent + reasoningSignature + toolCalls + providerMetadata + ...）
  │
  ▼
ChatService 持久化到 session_transcript_entries.payload_json
```

### 8.2 流式调用数据流

```
ProviderAdapter.stream(messages, options)
  │  Flux<rawSseChunk>
  ▼
ProviderAdapter.toStreamEvents(chunk)         ← 内部分派
  ├──→ ThinkingProtocol.extractReasoning(chunk) → ReasoningChunk
  ├──→ chunk.delta.content                      → ContentChunk
  ├──→ chunk.delta.tool_calls                   → ToolCallDelta
  └──→ chunk.usage                              → UsageEvent (last)
  │
  ▼
Flux<LlmStreamEvent>
  │
  ▼
StreamingCallback.onEvent(event)              // sealed interface 分派
  │
  ▼ SSE 通道（按事件类型路由）
  │
Web 前端 useChatStream composable
  │
  ├──→ event:reasoning   → 实时拼接到 reasoning 区域（流式滚动）
  ├──→ event:content     → 实时拼接到 content 区域
  ├──→ event:tool_call   → 工具调用卡片
  ├──→ event:usage       → 状态栏 token 计数
  └──→ event:done        → reasoning 区域自动折叠
```

### 8.3 多轮 history 注入规则

`ChatHistoryAssembler` 接收 `List<ChatMessage>` 和 `MultiTurnHistoryRules`，输出适配器消费的 `List<ProviderMessage>`：

| profileId | injectReasoning | format | 输出形态 |
|---|---|---|---|
| `deepseek-official` | true | CONTENT_ONLY | assistant message 平级加 `reasoning_content` 字段 |
| `qwen-dashscope` | true | CONTENT_ONLY | 同上（默认 true，避免 Qwen 后续版本变契约导致 400） |
| `openai-official` | false | N/A | 不附加 reasoning |
| `anthropic-official` | true | THINKING_BLOCK | content array 第一块 `{type:"thinking", thinking:"...", signature:"..."}` |
| `ollama-local` / 其他 NONE | false | N/A | 不附加 |

### 8.4 thinking_mode 三态语义

| 模式 | 行为 |
|---|---|
| `auto` | 不下发 thinking 字段，使用 provider 默认（DeepSeek 默认 enabled / OpenAI 默认 medium / Anthropic 4.7 默认 adaptive） |
| `enabled` | 显式下发 enabled / effort=medium，强制开启思考 |
| `disabled` | 显式下发 disabled / effort=none，强制关闭思考（响应快、省 token） |

**约束**：仅当 `model_service.is_reasoning = true` 时，thinking_mode 字段生效；否则 ChatOptions 不下发任何 thinking 字段（等同非推理模型现有行为）。

### 8.5 错误处理

| 情况 | 处理 |
|---|---|
| 用户配 `is_reasoning=false` 但实际收到 `reasoning_content` 字段 | 仍提取展示（graceful），日志 WARN 提示用户检查配置 |
| 多轮回传时 history 缺 `reasoning_content`（DeepSeek） | 适配器层补 dummy 占位（`""`）让 API 不 400；日志 WARN |
| `thinking_mode=enabled` 但 provider 不支持 thinking 字段 | ChatOptions 注入失败时降级为 `auto`，日志 WARN，不影响调用 |
| Anthropic thinking signature 缺失 | 同上，按 disabled 路径 fallback |
| 流式中途断连 | 已积累的 ReasoningChunk + ContentChunk 落库，`DoneEvent.finishReason="interrupted"` |

### 8.6 探测端点

`POST /api/model-services/probe-models`

入参：
```json
{ "profileId": "deepseek-official", "baseUrl": "https://api.deepseek.com", "apiKey": "sk-..." }
```

逻辑：
1. 查 ProviderProfileRegistry 拿 profile
2. 按 `profile.modelDiscovery` 配置发 GET（默认 `<baseUrl>/v1/models`，Ollama 走 `<baseUrl>/api/tags`）
3. 解析返回的 model 列表（按 profile 配的 jsonPath 提取）
4. 返回 `{ "models": [{"id":"deepseek-v4-pro","name":"deepseek-v4-pro"}, ...] }`

healthCheck 改造：`AbstractProviderAdapter.healthCheck()` 优先走 probe（毫秒级），失败再 fallback chat ping with `max_tokens=10`。这能彻底解决"推理模型 chat ping 23s+ 超时被 cancel → healthy=false"的误判问题。

## 9. 上游适配清单

### 9.1 后端

| 模块 | 改动 |
|---|---|
| `agent/ReactAgentLoop.java` | 消费富 `LlmResponse`：调 LLM 后把 `reasoningContent` / `toolCalls` / `providerMetadata` 落到 turn record；history 装载走 `ChatHistoryAssembler` |
| `agent/callback/StreamingCallback.java` | 接口从 `onChunk(String)` 改 `onEvent(LlmStreamEvent)`；按 sealed interface pattern matching 分派 |
| `agent/callback/NonStreamingCallback.java` | 同上的非流式版本 |
| `agent/streaming/StreamingEventHandler.java` | SSE 通道支持多事件类型，前端兼容字段稳定 |
| `generation/router/GenerationRouter.java` | `Flux<String>` → `Flux<LlmStreamEvent>` |
| `embedding/router/EmbeddingRouter.java` | 不动 |
| `rerank/router/RerankRouter.java` | 不动 |
| `interaction/web/service/ChatTurnService.java` | 持久化时把新字段写入 payload_json |
| `interaction/web/service/ChatSessionService.java` | 历史会话回查时反序列化新字段 |
| `agent/persistence/AgentPersistenceHandler.java` | turn 续跑时恢复 reasoning history |
| `memory/semantic/RealtimeExtractor.java` 等 LLM 调用点 | 升级 LlmResponse 访问 |
| `agent/orchestration/AgentOrchestrator.java` | 同上 |
| `interaction/web/controller/ModelServiceController.java` | 新增 `POST /api/model-services/probe-models` |

### 9.2 前端

| 模块 | 改动 |
|---|---|
| `views/settings/ModelServiceManager.vue`（或对等） | 改造："先挑 profile → 填 baseUrl/apiKey → 点拉模型 → 选 model → 勾 is_reasoning → 选 thinking_mode" 流程 |
| `composables/useChatStream.ts` | SSE 处理多事件类型（reasoning / content / tool_call / usage / done） |
| `components/chat/MessageBubble.vue` 等 | 加 reasoning 折叠区域，流式状态切换（流式时实时滚动，done 后自动折叠） |
| `components/chat/ToolCallCard.vue` | tool_call 事件渲染 |
| `views/workflow/*` | 工作流执行展示同样支持 reasoning |

## 10. 测试策略

### 10.1 单元 / Contract Test

- `<ProfileId>ProviderProfileContractTest.java` × 5（DeepSeek / Qwen / OpenAI / Anthropic / Ollama）
  - 请求注入：thinking_mode 三态分别构造请求，断言 ChatOptions / 请求 body 字段
  - 响应解析：fixture 喂 raw JSON → 断言 LlmResponse 字段对位
  - 多轮回传：构造含 reasoning 的 history → 断言序列化后的厂商请求 body 字段对位

### 10.2 集成 Test

- `<ProfileId>ProviderAdapterIntegrationTest.java`：用 `MockWebServer` 起真实 HTTP，断言完整请求/响应路径
- `ChatHistoryAssemblerPropertyTest.java`：jqwik 属性测试，随机 history 长度 + reasoning 出现位置 + provider 切换，确保序列化稳定
- `ProviderProfileRegistryTest.java`：所有内置 profile 字段完整性

### 10.3 E2E 冒烟（手工，自然对话）

- 配 DeepSeek V4 → 跑 3 轮对话 → 看 UI 流式 reasoning 实时滚动 + 完成折叠 + 第 2 轮不 400
- 切 thinking_mode=disabled 重跑 → reasoning 区域不出现，响应快很多
- 切 OpenAI o3 → reasoning 不展示但 ZhiWei 不报错（OpenAI 不返回 reasoning，graceful）
- 切 Anthropic Claude Opus 4.7 → adaptive thinking 模式下流式正常
- 切 Qwen3 → 多轮不 400

### 10.4 回归

- `mvn test` 全过
- 关键场景：ReactAgentLoop 预算控制 / 工作流多步执行 / Memory consolidation 不破

## 11. 实施切片（单 PR 分 commits）

| # | Commit 主题 | 范围 | 验收 |
|---|---|---|---|
| 1 | `feat(llm): 引入 ProviderProfile 数据驱动` | profile/ 包 + BuiltinProviderProfiles | profile 表加载 + Registry 单测 |
| 2 | `feat(llm): ThinkingProtocol 接口 + 5 实现` | thinking/ 包 | 5 个 protocol 单测 |
| 3 | `refactor(llm): Adapter 多态拆分` | adapter/ 包重写 | 编译 + 现有 test 全过 |
| 4 | `feat(llm): LlmResponse 富字段化 + Stream 事件流` | LlmResponse / StreamingLlmResponse / agent/callback | 上游消费方升级，编译 + 测试全过 |
| 5 | `feat(llm): ChatHistoryAssembler + payload_json 扩展` | history/ + 持久化 | history 装载属性测试 |
| 6 | `feat(modelservice): V32 迁移 + Entity 重写` | DB + Entity | 启动校验、列加载 |
| 7 | `feat(modelservice): /v1/models 探测端点 + healthCheck 改造` | controller + service | probe 单测 + healthCheck 不再误判推理模型 |
| 8 | `feat(web): 模型路由页 profile 挑选 + 模型探测 UI` | 前端 ModelServiceManager 重写 | UI 走通配模型链路 |
| 9 | `feat(web): chat reasoning 流式 UI` | 前端 chat / SSE | UI 流式滚 reasoning 折叠 |
| 10 | `test(llm): provider contract / E2E 冒烟` | 集成测试用 MockWebServer | 3 轮对话不 400 |

每个 commit 编译可跑，`mvn compile` 不挂。

## 12. 风险与对冲

| 风险 | 对冲 |
|---|---|
| 范围大，PR 长开 | worktree 隔离，每 commit 自治；超过 2 周拆子 PR |
| Spring AI 版本不支持 Anthropic adaptive thinking | fallback 到 `enabled` + budget_tokens；profile 字段 `historyRules` 含 `anthropicMode` |
| 多轮 history 序列化频繁 | YAGNI 不提前缓存；如真发现瓶颈再加 turn 级缓存 |
| 流式事件 SSE 兼容性 | 保持 SSE 通道字段命名稳定，只新增 event 类型（前端 unknown 类型默认丢弃） |
| 内置 profile 表硬编码维护成本 | `BuiltinProviderProfiles.java` 常量 + 单元测试覆盖每个 profile 字段完整性 |
| 用户配错 profile（自部署服务挑错协议） | UI 探测端点失败时给清晰错误提示，引导用户切换 profile |

## 13. 决策记录（brainstorming 关键节点）

按时间顺序：

1. **三个待办合并 / 拆分**：决议拆三个独立 spec（推理模型多轮契约 / 探测式模型配置 / 项目级工作区），按痛点排序优先做推理模型
2. **开关粒度**：选 A（模型服务级），存 `model_service.thinking_mode`，不做会话级覆盖
3. **推理模型识别策略**：选 A（纯人肉勾 `is_reasoning`），不做内置识别表 / 不做后置识别 / 不做探测式识别 — 简单透明
4. **reasoning_content 持久化**：A（落库到 `payload_json`），不引入新字段表
5. **UI 展示形态**：B（流式实时 + 完成折叠）
6. **彻底重构 vs 小步迭代**：选彻底重构（项目未上线，趁早动土），spec 范围扩展为"LLM 适配器层重构"
7. **协议类型 + 厂商两层抽象**：`baseAdapter` 标记基础 SDK 类型 + `profileId` 标记具体厂商
8. **不要 generic 兜底**：UI 强制挑 profile，配错由用户负责
9. **探测式模型配置合并到本 spec**：同一"接入新模型"场景串起来才完整
10. **不做数据迁移 / 不留兼容字段**：项目未上线，开发库重建，重写一步到位
11. **thinking_mode `auto` 语义**：不下发字段，用 provider 默认
12. **多轮缺 reasoning 时 dummy 占位**：graceful 兜底，不向用户抛错
13. **probe 与 healthCheck 端点分离**：不同语义，probe 用于"挑模型"，healthCheck 用于运行时监控
14. **下一个 spec**：本 spec 完成后做"项目级工作区"

## 14. 跟其他 spec / PR 的关系

| 工作 | 关系 |
|---|---|
| 项目级工作区改造 | 完全独立，本 spec 完成后单独做 |
| 探测式模型配置 | 已合并到本 spec（§ 8.6 + § 9 前端） |
| 沙箱 Docker 模式 | 完全独立，跟 LLM 层无关 |
| 浏览器能力补全 | 完全独立 |
| Skill 重构 PR | 完全独立 |
| 工具暴露重构 PR #94 | 完全独立 |

---

## 附录 A：关键概念词表

| 术语 | 含义 |
|---|---|
| **ProviderProfile** | 描述一个 provider 协议特性（thinking / structured output / cache / model discovery / capability / multi-turn rules）的数据 record |
| **BaseAdapterType** | 基础 SDK 类型枚举：OPENAI_BASE / ANTHROPIC_BASE / OLLAMA / TEI |
| **ThinkingProtocol** | 推理模式协议处理器接口，5 个实现（DeepSeek / Qwen / OpenAI / Anthropic / Noop） |
| **ThinkingMode** | 用户在 model_service 上配的开关：auto / enabled / disabled |
| **LlmStreamEvent** | sealed interface，流式事件 5 种：ReasoningChunk / ContentChunk / ToolCallDelta / UsageEvent / DoneEvent / ErrorEvent |
| **ChatHistoryAssembler** | 多轮 history 装载器，按 ProviderProfile.historyRules 序列化 ChatMessage → ProviderMessage |
| **MultiTurnHistoryRules** | Profile 内嵌 record，描述多轮回传规则 |
| **ReasoningInjectionFormat** | reasoning 在 history 里的注入格式：CONTENT_ONLY / EXTRA_BODY / THINKING_BLOCK |
